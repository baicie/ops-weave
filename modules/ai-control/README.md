# ai-control

Tool / SkillRelease / ModelPolicy / AgentPolicy 的控制面。

这是逻辑模块，不是独立微服务。没有实现的功能不得通过返回假数据冒充完成。

`AiRetention` 提供租户策略、截止/保留标记、预览摘要与回执规则；V019 PG 适配器原子清除已过期正文并保留原运行标识、授权范围、关联与费用账本。默认关闭，只有可信管理主体可预览和显式确认；不增加模型 Tool。见 [ADR-034](../../docs/adr/034-ai-content-retention.md) 与验证报告第40节，其他数据/元数据生命周期仍未完成。

`ModelSpendService` 与纯领域费用策略负责单次/租户每日估算准入、一次性运行许可、原费率快照和不可变提供方用量；V018 PG 账本通过租户事务锁串行竞争。未知费用跨日保留，mock 明确零调用。协议、输入估算假设与核销/留存缺口见 [ADR-033](../../docs/adr/033-model-spend-admission.md)，本机验收见验证报告第39节。

公开契约位于 `api/`；领域规则位于 `domain/`；用例位于 `application/`；实现适配位于 `infrastructure/`。

当前实现 `ToolGateway`：受权当前知识读取会话、Incident/Gauge 摘要与 Evidence 复核、共享调用/时间/结果预算。`AiInsightService` 校验完成预算、两份证据复核/权限/版本、引用和当前过期时间，保护缺口与限制。内存实现必须显式配置；平台适配层 V011 保存 PG 会话/不可变证据/元数据审计，V012 原子保存 AIInsight 和证据关联，按 tenant/runId/subject/请求摘要幂等回读。生产 IAM、模型策略控制面、Skill 发布和全平台留存清理仍未完成。

边界见 [ADR-022](../../docs/adr/022-current-knowledge-tool-evidence.md)、[ADR-023](../../docs/adr/023-current-diagnosis-insight.md)，已运行检查见 [验证报告第 28–29 节](../../docs/VALIDATION-REPORT.md)。
