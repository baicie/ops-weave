# telemetry

当前提供指标目录和来源绑定的内存与 PostgreSQL 存储。查询按 `metric.read` 分别列出定义和绑定。Zabbix Item 映射写入这两类对象，不写采样点。

`MetricPoint` 保留纳秒与十进制精度；`MetricWriteBatch` 在批写前检查毫秒冲突、float64 精度和标签。History 来源读取/采集用例由 integration 提供，VictoriaMetrics 写入与 PostgreSQL 游标由 ingestion-worker 装配。`MetricQueryPort` 只接受租户、实体、指标和时间窗口；空序列是 `NO_DATA`，存储失败是 `MetricQueryException`。不同来源保持为多条 series，不在查询层合并。平台侧的 VictoriaMetrics 适配器只生成固定 export 选择器。公开契约位于 `api/`，领域规则位于 `domain/`，用例位于 `application/`，内存夹具位于 `infrastructure/`。
