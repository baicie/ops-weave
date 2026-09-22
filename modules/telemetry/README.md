# telemetry

当前提供 `MetricDefinition` 的内存与 PostgreSQL 存储，以及按 `metric.read` 列出的查询。Zabbix Item 映射写入定义，不写采样点。

尚未提供 History、VictoriaMetrics 或指标查询聚合。公开契约位于 `api/`，领域规则位于 `domain/`，用例位于 `application/`，实现适配位于 `infrastructure/`。
