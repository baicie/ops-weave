# ADR-015：指标目录和来源绑定分开，History 仍不进 PostgreSQL

状态：已采纳 · 日期：2026-09-22

## 决策

`MetricDefinition` 只表示一种指标语义：`metricKey`、显示名、单位、数值类型、Gauge/Sum/Histogram、维度模式。同一语义只有一条目录记录。

`MetricBinding` 表示某个来源 item 绑到这条语义上：租户、来源实例、外部 item id、主机实体、固定维度、来源单位、换算、映射版本、生命周期。两台主机上的 `system.cpu.util[,user]` 共用 `host.cpu.usage.user`，各有一条绑定。

映射规则来自 `extensions/mappings` 的文档，经 `mappings/index.txt` 装入注册表。执行代码只校验并应用映射，不写死 item key 与 metric key 的对应关系。目录里的 draft 文档目前会执行；发布门槛留到 PipelineVersion。

缺席 item 只让绑定变为 INACTIVE。目录记录保留。未映射但仍出现在来源里的 item 继续算 presence，不因此失活。

`V003` 曾把语义和来源写在同一张表。`V004__metric_catalog.sql` 只执行一次，删掉那张混合表并建成目录表与绑定表。采样点仍然不进 PostgreSQL。

## 代价与后续

History 按 `itemid + clock + ns` 做增量，不沿用 Host 的 limit/offset。点写入 VictoriaMetrics，再做查询 API 和指标页。
