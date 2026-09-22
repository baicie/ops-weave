# telemetry

当前提供指标目录和来源绑定的内存与 PostgreSQL 存储。查询按 `metric.read` 分别列出定义和绑定。Zabbix Item 映射写入这两类对象，不写采样点。

`MetricPoint` 保留纳秒与十进制精度，数值按绑定的允许转换规范化；History 来源读取由 integration 提供。尚未提供 VictoriaMetrics 写入、持久采集游标或指标查询聚合。公开契约位于 `api/`，领域规则位于 `domain/`，用例位于 `application/`，实现适配位于 `infrastructure/`。
