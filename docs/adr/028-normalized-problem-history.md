# ADR-028：不可变告警输入与当前归属历史

日期：2026-09-25。状态：本机实现；实际检查见验证报告第 34 节。

## 背景

`IncidentRecord.Problem` 是当前投影，合并已经确认的恢复事实，保留首次/最近接收时间。它不能回答来源某次输入是否遗漏恢复信息，也不能重建迟到观测。不能用当前投影反向补造历史。

## 决策

在已有 Java alerting 领域增加 `ProblemObservation`。平台成功接受的每个不同规范化输入，以 tenant/source/problemEventId/精确 observedAt 派生稳定 UUID。记录保存输入的 `ExternalProblem`、显式模式、固定来源契约、首次接收时间及首次接收时的 Host→Entity 映射。这里的输入是已校验的规范化字段，不是完整 Zabbix JSON。

同一身份原样重投只返回已有历史，不能改写其接收时间或映射；实时投影可以增加新解析出的实体。相同身份但不同内容拒绝整个批次，包括迟到输入。不同观测时间即独立记录，乱序输入仍保留，当前投影继续沿用已有恢复合并规则。

V016 在已有 PostgreSQL 的 `alerting.problem_observation` 留存输入，与 occurrence/Incident 更新共用同一事务和租户归属锁。保存纳秒数值用于筛选，JSON codec 同时检查行身份/时间/映射元数据，避免损坏行扩大授权范围。新表不从旧快照回填；不增加服务、数据库产品或模型 Tool。

只读查询 `/api/v1/incidents/{incidentId}/problem-observations` 要求当前 Incident `version`、最多 31 天的 observedAt 秒级闭区间；支持来源、来源内事件 ID 和 UUID 游标，每页最多 25 条，探测一条下一页记录。首次查询可省略 asOf，由服务端生成；后续页复用同一窗口/version/asOf。firstReceivedAt 必须不晚于 asOf，且 asOf 不能在当前时间之后。数据库单语句上限 3 秒，记录编码上限 16 KiB。

每次读重新核对 `incident.read`、`entity.read`、当前 Incident 及全部关联实体范围。PG 使用只读 repeatable-read 事务，将当前版本/范围核对和 occurrence 归属查询放在同一快照；版本不匹配返回 409。每条历史还必须满足首次记录的实体范围，历史映射缺失的条目只对 tenant-wide 实体读取可见，不能被之后补齐的映射追认授权。过滤先于 LIMIT。

历史跟随 occurrence 的当前归属索引；合并归档的来源快照不再拥有这些条目，不能凭旧快照继续读取已转移告警的历史。当前归属发生变化会改变 Incident 版本，翻页需重新加载详情。响应不带旧 Incident 身份。`coverage=retained-normalized-current-ownership` 与留存前历史/厂商原文缺口始终明确返回。

## 结果和边界

页面按记录 ID 展示留存输入、恢复缺失、双时间、模式和当时映射，提供来源/事件/最近 1、7、30 天筛选；更换筛选重置页，409 要求刷新详情，撤权/换身份/退出清除数据。时间按纳秒验证，文本不执行、不解引用。秒级窗口到最后整秒，不包含之后的秒内观测。

该查询不是 Incident 任意 asOf 状态、完整厂商事件日志或时间线分页。每页事务一致，但不承诺跨请求数据库快照；固定 cutoff 与版本减少翻页混杂，存储历史尚无总量配额和清理任务。V016 需要配套 writer；旧二进制继续采集不会产生新历史，尚未验证滚动升级/回退、生产容量或 HA。真实 Zabbix/模型/登录仍待环境。
