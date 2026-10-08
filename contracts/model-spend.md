# 模型用量及费用准入 v1

唯一 wire 定义为 `schemas/v1/model-spend-{policy,usage,reserve,report,record,result,metrics-result}.schema.json`；OpenAPI 列出四条固定接口。金额单位 USD micro，所有数量为非负安全整数，服务端整数计算、Web 用 BigInt 复核乘积与进位。示例费率只用于测试，不是当前市场报价。

预留请求只含 runId/sessionId/inputDigest/inputBytes，不接收 tenant/subject/model/rates/budget。可信 Runtime 同时携带用户凭据（dev 或有界 OIDC 委托）与进程证明；不是模型 Tool。已有 run 永远不能重新得到许可。平台记录的 policy 是当次原始配置快照。

`RESERVED` 与 `UNCERTAIN` 的 usage/reportedAt/estimatedMicros 必须为 null，accountedMicros 等于 reservedMicros。UNCERTAIN 表示 deadline 已到仍未收到可信用量，并不证明调用失败或费用为零。已确认金额按预留发生的 UTC 日归属，未确认金额跨日继续占用直到有效报告；没有隐式退款。`REPORTED` 必须有用量、上报时间和金额；日期、费用公式、当前身份/资源和快照匹配由执行器补充校验，JSON Schema 不代替这些检查。

`provider-reported` 要求输入 Token 大于零、输出 0–2048、缓存输入不超过输入；输入最多81920。缓存按输入全费率估算。mock 必须固定 `mock-deterministic/mock-current-v1/mock-no-charge` 和全零金额/Token，usage.source=`mock-no-call`。未知或无效回包不能生成零费用回执。

一条报告用量不能改写；相同报告幂等返回原时间/原费率回执。OIDC 委托额外限制一次 report，客户端不会自动重试。读取支持诊断失败后的独立费用核对，但仍要求原主体及当前诊断/Incident/关联实体权限，不暴露其他主体的记录。HTTP 404 只表示当前身份查不到记录。

预留是配置费率估算而非外部计费保证；价格、输入 Token 上界假设与未提供的核销/对账/留存见 [ADR-033](../docs/adr/033-model-spend-admission.md)。日志不保存输入正文或提供方响应。Schema 不触发网络下载。

`GET /api/v1/ai/model-calls/metrics` 是租户范围只读聚合，身份和 tenant 只来自可信认证边界。`from`/`to` 为半开 UTC 时间窗，最多 366 天；可选 `model` 精确筛选，结果最多 100 个 provider/model 组。聚合不返回 run、session、incident 或 subject 标识；RESERVED/UNCERTAIN 记录按预留金额计入，指标仍是配置费率估算而非供应商账单。

## 显式兼容协议（2026-09-27）

现有 `rig-openai` 标识覆盖受控 Rig Responses 与显式 Chat Completions 适配；它不声明托管厂商。可信配置固定模型和协议，不由请求或模型选择。Chat 的 prompt_tokens/completion_tokens 转为现有用量字段，total必须相符；可选缓存字段缺失按0缓存进行保守估算，不伪称账单确认。截断/拒绝/不支持的内容仍记录可核验用量，再拒绝结果。金额、身份、范围与幂等契约不变。详见[ADR-053](../docs/adr/053-explicit-model-chat-protocol.md)。
