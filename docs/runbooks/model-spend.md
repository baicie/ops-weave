# 模型费用本机验收

本机协议回归不需要真实模型密钥：mock 整链使用真实 Java/Rust/PostgreSQL/VictoriaMetrics，模型适配器另用明确标记的 Responses/Chat 协议桩验证。2026-09-27 已额外完成 OpenCode Go/deepseek-v4.1-flash 的真实候选整链7/7，详见验证报告§65；两类证据不可混称。

保持现有本机 dev/OIDC 与 Runtime key 配置。mock 无新增金额配置，显示未调用外部模型及零用量。真实付费环境准备好后，两端设置相同 `OPSWEAVE_PROVIDER=rig-openai`、`OPSWEAVE_LLM_MODEL`，平台必须使用 PostgreSQL；Runtime 还要求 `OPSWEAVE_ALLOW_MODEL_EGRESS=true`、`OPENAI_API_KEY`（安全注入）。可配置 `OPENAI_BASE_URL`，仅接受无内嵌凭据、query 或 fragment 的 HTTPS 地址。不要把密钥写入仓库、任务消息或普通日志。

Java 必须显式设置：

| 配置 | 含义 |
|---|---|
| `OPSWEAVE_MODEL_PRICE_VERSION` | 1–64 字符运维费率版本 |
| `OPSWEAVE_MODEL_INPUT_MICROS_PER_MILLION` | 每百万输入 Token 的 USD micro，正整数 |
| `OPSWEAVE_MODEL_OUTPUT_MICROS_PER_MILLION` | 每百万输出 Token 的 USD micro，正整数 |
| `OPSWEAVE_MODEL_MAX_CALL_MICROS` | 单次准入上限，需容纳81920输入+2048输出的估算 |
| `OPSWEAVE_MODEL_DAILY_MICROS` | 租户每日已确认费用与全部未确认预留之和的上限，不小于单次上限 |

当前费率按平台配置统一，账本按租户隔离并串行准入；并未实现每租户自定义费率。金额上限1e12 micro，各租户最多保留10000条。不要通过删除未知记录或换runId绕过待确认费用。费用不确定时先按原runId“读取用量与费用”，必要时运维核对提供方账单；自动核销接口未提供。

验收内容：

1. 纯领域与真实PG：并发仅一个赢家、跨UTC日/重开适配器保留未知金额、相同reserve409、同usage原回执、不同usage409、actor/session/tenant隔离。
2. JavaHTTP：Runtime证明、读会话、权限、参数闭合/溢出、mock预留/报告/GET，保存实际脱敏产物。
3. Rust默认/all-features：平台回执Schema/范围/时间/费率/金额检查；无额度不调模型、输出错误也先报告用量、报告失败不保存结果；本机Responses桩的用量/零输出/重定向/503/缺用量/超大响应，无网络fallback。
4. Web：显式查询金额、未知状态、错误费率/计数、迟到结果和身份清理；151项全量包含这些场景，实际结果以报告为准。
5. `scripts/check_metrics_stack.mjs --pipeline --runtime` 与 `scripts/check_oidc_stack.mjs --history-service`：真实持久账本→页面查询/刷新，原Worker/OIDC/资产/诊断链回归。依赖现有本机PG/VM/Chromium配置；脚本不会调付费提供方。

MVP100%仍需真实来源、模型、登录部署及人工审阅。成本估算、日志屏蔽和协议桩通过不替代这些验收。

## OpenCode Go 本机配置（§65）

Runtime设置 `OPSWEAVE_MODEL_API=chat-completions`、`OPENAI_BASE_URL=https://opencode.ai/zen/go/v1`；两端模型名均为 `deepseek-v4.1-flash`。现有 `rig-openai` 是兼容适配标识。禁止根据HTTP失败自动换协议、模型或回退Mock。API密钥只安全注入Runtime，平台只需模型/预算配置。

本次可信费率快照采用官方公布峰值作为保守估算：输入300000、输出1200000 micro/百万Token，版本opencode-go-20260927-peak-ceiling；单次30000 micro、租户每日100000 micro。一次最大预留27034 micro。实际Go订阅额度、峰谷和缓存计价需以提供方记录核对，本机数值不是最终账单。

请求使用自身User-Agent及平台签发会话UUID作为x-opencode-session。只发送本地测试Incident和用户态CPU摘要；不会调用修复工具。已保存结果与费用可重复读取，读取不会调用模型。首次失败调用的已报用量保留，不借换runId自动重试。用户可显式新建独立诊断。

协议选择、边界与[官方链接见ADR-053](../adr/053-explicit-model-chat-protocol.md)。
