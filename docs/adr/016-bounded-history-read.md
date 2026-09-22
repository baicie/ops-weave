# ADR-016：有界 History 读取与采样点精度

状态：已采纳 · 日期：2026-09-22

## 决策

先交付按已授权 MetricBinding 读取数值 History 的接口，再交付 VictoriaMetrics 写入。该接口是来源读取预览，返回 `persistence=not-persisted`；不更新 SyncRun、数据库游标或持久化采样点。

`GET /api/v1/integrations/zabbix/items/{itemId}/history` 从可信主体取得租户，从服务器配置取得来源与凭据引用。执行前同时检查 `source.sync`、`metric.read`、`entity.read` 及来源、指标、实体范围。查找绑定和目录每批各一次，不逐点查 PostgreSQL。失活、缺失或范围不可见的绑定返回 404。

查询窗口最多 3600 个完整秒，每页最多返回 500 点，单进程最多 4 个 History 请求并发，不排队、不自动重试。每页先通过 `item.get` 读取实际 value_type，并核对主机、映射与单位；不能从目录 DOUBLE 推断来源一定是 float。只接受 numeric float（0）和 unsigned（3）。元数据变化须重新同步，不能继续使用过期映射。

History 按 `clock/ns` 升序读取 501 条探测记录。若达到上限，整页末尾那一秒可能被截断，暂不返回该秒，下一页重读。若所有 501 条都属于查询起始秒，返回 `HISTORY_SECOND_LIMIT`，不跳过该秒。输出分页若截在完整秒内，游标精确到纳秒；扫描完成的秒使用 `ns=999999999` 表示已读完。来源乱序、重复时间戳、外部 item 不匹配或越界数据均使整批失败。

`MetricPoint` 使用 Instant + BigDecimal。应用绑定中允许的 `identity` / `multiply:` 换算，再执行映射文档中的可选 `validation.min/max`（CPU user 为 0–1）。保留纳秒和 unsigned 64 位数值；跨语言 value 使用十进制字符串。非数值、超出数值范围、非法 unsigned 或未知转换失败，不猜测补值。响应附带指标目录版本、绑定版本及 mappingRevision。

JSON-RPC 共用传输层在接收过程中限制响应为 2 MiB，并保留 5 秒连接/10 秒请求超时；校验 JSON-RPC envelope。失败只返回稳定错误码，不返回原始来源响应或凭据。

## 限制与后续

`nextCursor` 只是当前 item 和固定窗口的排他读取位置，客户端须与该租户/来源/item/窗口及映射版本一起保存；它不是持久 ingestion checkpoint。`windowComplete` 仅描述本次有界扫描，不能证明未来不会补到旧时间戳的数据。History 无快照隔离；后续采集必须定义延迟容忍、重叠窗口和幂等去重。高密度单秒数据需要独立导出适配，不能靠增大 offset 绕过错误。

VictoriaMetrics 的批写、时间精度转换/同毫秒冲突策略、写入失败重试与“持久接受后推进游标”尚未实现；不得把这个读取接口当作已采集落库。自动轮询属于 ingestion-worker 后续增量，本次不增加启动单元。

协议依据：[Zabbix 7.0 history.get](https://www.zabbix.com/documentation/7.0/en/manual/api/reference/history/get)、[History object](https://www.zabbix.com/documentation/7.0/en/manual/api/reference/history/object)。本次用本地协议桩核验，未联调厂商实例。
