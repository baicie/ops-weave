# 模型费用本机验收

当前验证不需要真实模型密钥：mock 全链使用真实 Java/Rust/PostgreSQL/VictoriaMetrics，模型适配器另用本机 Responses 协议桩验证。协议桩不是已接入外部模型。详细执行结果见验证报告第 39 节。

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
