# telemetry

当前提供指标目录和来源绑定的内存与 PostgreSQL 存储。查询按 `metric.read` 分别列出定义和绑定。Zabbix Item 映射写入这两类对象，不写采样点。

尚未提供 History、VictoriaMetrics 或指标查询聚合。公开契约位于 `api/`，领域规则位于 `domain/`，用例位于 `application/`，实现适配位于 `infrastructure/`。
