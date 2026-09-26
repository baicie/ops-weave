# ADR-033：持久模型费用预留与用量回执

状态：采纳，本机验收范围见验证报告第 39 节。日期：2026-09-26。

## 问题

原 current diagnosis 限制单次模型、输入字节和输出 Token，但 Rig 只返回文本，没有持久的提供方用量或租户金额检查。进程中断后，无法区分未执行和可能已经计费。不得把未知状态记成零、隐式重试或通过旧 demo 绕过付费限制。

## 决策

Java ai-control 拥有 ModelSpend 领域与 V018 PostgreSQL 账本。Rust 只有调用受控平台 API 的适配器，不访问业务数据库。保留四个启动单元和已发布 Skill 2.0.0；不添加工具权限、执行后端或 AIRun 恢复功能。

两次初始读取后，Runtime 对固定 system/payload 的长度前缀字节计算 SHA-256，携带 run/session/digest/bytes 请求额度。平台从可信 Principal、read-session、当前资源授权和运维模型策略构造记录；每租户事务 advisory lock 检查单次金额、当日已上报金额、所有尚未确认的预留金额及 10000 条记录上限。重复 runId 返回 409，即使请求相同；未知的预留响应不能再次成为模型执行许可。

预留上界为 81920 输入 Token、2048 输出 Token；system 与序列化 payload 合计不超过 73728 UTF-8 字节。金额为 USD micro 整数，按 `ceil((inputTokens × inputMicrosPerMillion + outputTokens × outputMicrosPerMillion) / 1000000)` 计算。缓存输入仍按完整输入费率估算。费率和版本必须显式配置，持久记录保留原始策略，不随配置变动重算。付费模式要求 PostgreSQL；memory 只允许零费用 mock。

这是一种执行准入的配置费率估算，**不是提供方账单或外部计费的严格保证**。输入上界是针对当前固定文本请求的保守额度，未执行服务端精确 Token 预检，且第三方模型/网关可使用不同计费规则。费率需由运维核验；超过预设 Token 的回包拒绝入账并保留预留额度，不能据此证明外部费用没有超额。税费、缓存优惠、服务等级、其他工具费用和账单对账未实现。

Rig 使用锁定版本的 Responses SDK 执行一次 raw completion，无 Agent 工具循环。HTTP 无代理、无重试、无重定向，3 秒建连、18 秒整个 HTTP 请求、256 KiB 响应；工作流仍有 20 秒模型期限及原总期限。请求包含 `store:false`、`background:false`、`max_output_tokens:2048`，不携带工具或 previous_response_id。真实配置仅接受 HTTPS；HTTP 协议桩入口仅在 Rust 私有单元测试中构造。SDK 调用使用无日志 subscriber，避免环境 trace 打开后输出 Prompt、内容或错误正文。

raw response 的 input/output/cached Token 在解析业务输出、引用复核和 AIInsight 保存前上报。输入非零、输出为零的 incomplete/refusal 也可保留输入费用；无用量或非法用量无法伪造成成功。已上报用量不可改写，相同上报返回原回执。付费 AIInsight 必须匹配已上报 run/session/model。旧 mock 提交兼容；新 mock 流程也记录 `mock-no-call`，不假装提供方调用。

未上报记录到 deadline 后显示 UNCERTAIN，并跨 UTC 日继续占用全额；无自动释放、自动重试或自动对账接口。用户可按 runId 单独读取自己的用量，即使诊断输出无效或未保存。读取仍复核当前 Incident/关联实体范围及诊断/证据权限，404 不代表无费用。页面明确区分估算和账单、用量回执和诊断成功。

OIDC 委托保留四个在途、65 秒、8 次原读取/保存调用额度，另允许恰好一次绑定当前 run/session 的预留和一次其后的用量上报。Runtime key 只附加在三个固定写入路径；浏览器、模型和 Worker 不持有此密钥。撤权导致上报失败时仍保留未确认额度。

## 边界与后续

没有付费外部 API 验收；协议桩仅证明本地协议实现。账本不存 Prompt、模型输出、日志、API key 或 Authorization，仅保存输入摘要/大小、可信身份、策略、时间与用量。没有按 Skill 聚合、精确预检、账单对账、手工核销或时间清理；10000 条保留上限耗尽后拒绝新调用，不删除审计。未来清理/核销需要单独明确权限、未知费用处理和幂等语义。

官方字段核对：[Token counting](https://developers.openai.com/api/docs/guides/token-counting)、[Reasoning](https://developers.openai.com/api/docs/guides/reasoning)。Responses 的输出计数包含推理等非可见 Token，`max_output_tokens` 约束总输出；不将字符串长度当作提供方实测 Token。实际接入以锁定 Rig 0.42.0 的代码和本机协议测试为准。
