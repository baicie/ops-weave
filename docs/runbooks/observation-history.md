# 来源观测历史本机验收

设计见 [ADR-026](../adr/026-retained-observation-history.md)，实际结果见 [验证报告第 32 节](../VALIDATION-REPORT.md)。使用专用 loopback 开发环境；不提交或打印凭据。

按 [指标查询验收](metric-query-acceptance.md) 配置测试 PG/VM 和工具链，按 [当前诊断验收](insight-local-acceptance.md) 构建 Java/Rust/Web。执行 `node scripts/check_metrics_stack.mjs --pipeline --runtime`，在原有业务链上增加浏览器资产详情→授权观测历史→真实 PG 来源/映射/Raw/双时间验证。脚本自建固定测试租户与随机开发凭据，来源为 labeled-fixture、模型为 mock，不调用厂商。

自动回归包含 ObservationHistorySmoke、PostgresObservationIT、ObservationHttpIT 和 Web observations.spec.ts。必须运行全量契约、领域、Java/PG/VM、Rust 默认/all-features 与 Web 类型/构建/回归；不可用单个 HTTP 200 代替这些证据。

手动检查：同步 Host，读取资产详情，选最近 24 小时/7 天/30 天，再读取观测历史；fixture 的固定观测时刻可能不在最近 24 小时内。核对来源实例、外部 ID/generation、映射、原始引用、当时名称、观测和接收时间。下一页应保持相同 from/till/asOf；改来源或窗口从首批开始。撤权或清除会话后，历史随详情消失。

`.tmp/metrics-acceptance/observations.png` 和 observation/page JSON 是合成测试产物。旧记录明确显示微秒精度及历史字段缺口；不从当前资产推测过去。没有记录不证明来源没有变化。暂未提供时间顺序分页、完整事件日志、来源冲突确认/撤销、存储留存清理或任意历史状态重建。
