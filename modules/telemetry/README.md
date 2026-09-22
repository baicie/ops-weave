# telemetry

当前提供指标目录和来源绑定的内存与 PostgreSQL 存储。查询按 `metric.read` 分别列出定义和绑定。Zabbix Item 映射写入这两类对象，不写采样点。

`MetricPoint` 保留纳秒与十进制精度；`MetricWriteBatch` 在批写前检查毫秒冲突、float64 精度和标签。History 来源读取/采集用例由 integration 提供，VictoriaMetrics 写入与 PostgreSQL 游标由 ingestion-worker 装配。指标查询聚合尚未提供。公开契约位于 `api/`，领域规则位于 `domain/`，用例位于 `application/`，实现适配位于 `infrastructure/`。
