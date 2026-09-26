# 平台读取适配器

`platform_http.rs` 已实现独立的本机 `PlatformHttp::loopback` 和绑定会话的 `BoundRead`：Java 构造可信身份和 Incident 范围，Rust 仅转发该请求的凭据，并按 canonical contracts 校验会话、证据、采样窗口和预算。凭据不会进入返回对象或模型上下文。详见 [ADR-022](../../../../docs/adr/022-current-knowledge-tool-evidence.md)。

只连接明确配置的数字 loopback HTTP origin。无代理、重定向、重试、任意 URL/SQL、Schema 下载或 fixture fallback。前后读取与 Evidence 复核共享四次调用和六十秒会话期限；来源内容仍为不可信数据。

`platform_read_probe` 已与真实本机 Java/PG/VM 联调，来源为显式 fixture。新增 `platform-dev` 模式与 `/api/v1/current-diagnoses`：独立 current-context、新 Skill 2.0.0、单次模型与两次证据复核，随后通过受控 Java API 保存 AIInsight。保存失败不能宣称成功；Runtime key 只在固定 AIInsight 保存及 ModelSpend 预留/报告路径发送，模型和浏览器不持有。Java 是结果及费用账本持久化所有者。详见 [ADR-023](../../../../docs/adr/023-current-diagnosis-insight.md)、[ADR-033](../../../../docs/adr/033-model-spend-admission.md)。

现有 `/api/v1/diagnoses` 仍只运行原固定 demo。没有改变旧 `context::build` 的历史 asOf 规则；当前知识不能证明历史采集可见性。模型默认明确 mock，真实 Rig 调用未验收。

当前流程先获得固定策略和输入 digest 绑定的额度，才调用模型；SDK 用量在业务输出解析前报告。金额为配置估算，超时/缺少用量保持未知预留。Rig raw Responses 只发一次、无工具循环；响应256 KiB、18秒、无重试/重定向/代理，SDK内容日志屏蔽。真实模型仅 platform-dev 且需要显式出网配置，旧 demo 的付费入口关闭。本机协议桩通过不代表实际外部模型账单验收。

生产接入仍需短时、audience 绑定的受信委托身份；本机开发转发不是生产认证。MCP 仍是单独 adapter spike，Rig 的 rmcp feature 不启用，不跨领域边界传递第三方 SDK 类型。
