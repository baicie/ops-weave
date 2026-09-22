# ADR-015：先落 MetricDefinition，不把 History 写进 PostgreSQL

状态：已采纳 · 日期：2026-09-22

## 决策

Zabbix `item.get` 只产生指标定义。`system.cpu.util[,user]` 映射为 `host.cpu.usage.user`：Gauge、平台单位 `1`、维度 `mode=user`。Zabbix 的百分比到比值的 `multiply:0.01` 记在外部映射上，这一阶段不生成采样点。

未映射的 item key 记为 rejected，但仍算来源 presence，不因此失活已有定义。History、MetricPoint、VictoriaMetrics 另做，不进这张定义表。

## 代价与后续

同一指标名可以对应多个来源 item。定义表不是时序库。
