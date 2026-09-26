# ADR-020：先建立有界外部问题读取与恢复关联

状态：接受，2026-09-25。是 M3 的读取切片，尚未交付持久告警/Incident 闭环。

后续增量：ADR-021 已增加独立 POST 导入、Incident 持久化和页面；本 ADR 的 GET 仍为 not-persisted，以下未实现项描述的是当时的读取切片，现状以 ADR-021 和实现状态为准。

## 决策

第一版依据 Zabbix 7.0 `event.get` 契约读取 trigger problem occurrence。用 tenant + configured source + problem event ID 标识一次发生，不能用 trigger ID 把多次故障合为一次。按 event ID 数值升序分页，下一页从最后 ID + 1 开始；同秒事件不会因按秒推进而跳过。

查询窗口最大 24 小时、每页最大 100 条。先读取窗口内活动的问题，再批量按明确的恢复 ID 读取恢复事件，最多两次来源调用。每个应用实例并发最多 2，沿用传输层单次 10 秒超时、2 MiB 响应上限，不重试、不回退 fixture。尚无跨进程配额或后台扫描调度。

恢复引用不可读时保留 `RECOVERY_UNKNOWN` 和 `RECOVERY_EVENT_UNAVAILABLE`，不伪造恢复时间。维护/手动抑制标记保留；此接口不触发诊断。全局关联关闭是不同关系，本切片遇到非零 `c_eventid` 时整页拒绝，不能当作仍活动或普通恢复。后续支持时需补独立用例与契约。

`occurredAt/recoveredAt` 保留来源秒和纳秒；`observedAt` 是本次实际获取时间。历史窗口查询仍获得当前 severity/suppression/recovery 观测，不能宣称是历史 asOf 快照。领域合并拒绝相同观察时刻的冲突，忽略更旧观测，已知恢复不得退回活动态；持久化实现必须在事务内锁定/比较后应用该规则，目前尚未接入。

## API 与边界

`GET /api/v1/integrations/zabbix/problems?from=...&till=...&limit=25` 需要配置来源的 `source.sync`；这是来源管理员读取入口，后续普通用户从授权 Incident/Evidence 读取。主体与来源均从可信配置构造，请求不能指定 tenant、权限、URL 或凭据。返回再次检查租户/来源/窗口/数量/顺序。没有资产存在性推断，host IDs 只是待映射的外部引用。

契约唯一源为 `contracts/schemas/v1/external-problem*.schema.json`。返回 `storage=not-persisted` 和明确 `dataMode`。`sourceContract=zabbix-7.0-event-v1` 表示适配契约版本，不声称已检测服务器版本。fixture 采用固定发生 ID/时间，不随请求重新造事件；只在显式 fixture 配置启用。

尚未完成：真实厂商版本/实例验收、原始告警持久化、幂等导入、Incident 投影与事务、资产映射缺失、时间线/页面、人工状态/合并/拆分、后台调度、基于抑制的自动诊断策略。读取成功不能被 UI 标为已入库或已创建 Incident。

## 契约依据

- [Zabbix 7.0 event.get](https://www.zabbix.com/documentation/7.0/en/manual/api/reference/event/get)：事件范围、问题窗口、host 选择与排序参数。
- [Zabbix 7.0 event object](https://www.zabbix.com/documentation/7.0/en/manual/api/reference/event/object)：发生、恢复引用及抑制字段。

2026-09-25 已核对上述官方页面；本地协议桩和 HTTP 测试不能替代厂商验收。
