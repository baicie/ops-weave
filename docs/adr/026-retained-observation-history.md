# ADR-026：不可变来源观测与授权历史

日期：2026-09-25。状态：本机实现，验收结果见验证报告第 32 节。它为后续多源字段权威、冲突确认和撤销保存依据，不代表上述治理工作流已经完成。

## 问题与决定

Host 映射已写 Observation，但 Web 只能看到 Entity 当前投影。内存按未带租户的 ID 覆盖观测；PostgreSQL 重复 ID 会失败。来源旧数据可能回退当前投影，数据库时间还会丢失 Java Instant 的纳秒部分。

采用 `(tenant, observationId)` 不可变记录。相同记录重复提交是无副作用成功；任何字段、来源、时间、实体或 Raw 引用不同即拒绝，包含 ingestedAt 不同的同 ID 记录。重复同步产生新的 Raw/Observation，可观察每次采集；不是把内容相同的不同采集去重。字段深复制为不可变 JSON 值，数值按十进制语义比较。写入要求 Entity/Observation/ExternalLink 的身份和 lastSeen/observedAt 一致。

Entity 当前值仅接受 observedAt 不早于现值的同一来源观测；迟到旧观测依然留存。相同来源、相同观察时刻的后续新记录仍可更新当前值，这不是跨源字段权威规则。当前 writer 拒绝隐式重新分配 ExternalLink，也拒绝向已有实体加入不同外部身份。名称/IP 相同不会授权合并；未来的显式 Resolution 事务需独立实现，不可通过普通 upsert 绕过。

新 Host Observation 的 fields 加入当时 entityName/entityType/lifecycle；原始字段、Raw、映射修订与发布 digest、显式 dataMode 一并留存。已有观测缺失这些字段时显示缺口，不从当前 Entity 补写历史名称。

## 持久化

V014 为 Observation 和 Entity 时间增加 numeric epoch-nanoseconds，旧 timestamp 列仍保留。新增记录 exact_time=true；旧记录按真实 PG timestamp 精度回填，标记 legacy-microseconds，不伪造已丢失的精度。原记录时间不重新计算为“现在”。

PG 写入使用事务级观察 ID 锁，检查同键内容后写 Entity/Observation/ExternalLink；后置 Link 一致性失败时三者一起回滚。来源冲突检查在 Entity 行写锁保护下执行。现有迁移器事务和启动锁继续生效。内存 writer 同步写入/对账，并与 PG 使用相同的不可变和身份拒绝规则。

V014 的新增非空列需要本版本 writer 配套；本机增量迁移通过不等于生产升级、旧二进制回退、在线大表迁移或 HA 验收。当前历史未实施保留清理和每租户存储总量限制。

## 只读查询

`GET /api/v1/entities/{entityId}/observations` 复用 ENTITY_READ 和当前对象授权。先授权并确认当前租户中的实体存在，再查询历史；不信任请求 tenant 或返回记录自报的范围。来源没有另一个比 Entity 更细的权限模型；后续融合若引入来源级保密，必须同时更新历史授权。

- from/till 为包含端点的秒级观测窗口，最多 31 天；默认每页 25、最大 50 条。
- asOf 为平台接收时间截止，首次由服务端捕获，后续页复用。observedAt 在窗口内，ingestedAt 不晚于 asOf；在任一端按纳秒比较，避免同毫秒未来数据混入。
- 可按 source 精确筛选，按 ASCII 观测 ID 升序翻页；不是按时间排序或跨页事务快照。迟到提交与回填仍可能改变当前视图，不承诺快照完整性。
- PG 在租户/实体/时间/来源筛选后 LIMIT + 1，5 秒查询时限；单条 fields 16 KiB 读取预算。错误或超限明确失败，不返回成功空页。
- 返回 retained-observations-only。未采集时段、映射拒绝、未留存原始资料不会因历史页存在就变成“完整来源日志”。Raw 引用仅作文本，不触发远程或任意路径读取。

Canonical observation/query/page Schema 和样例在 contracts。Web 在新授权详情内读取，检查 tenant/entity/窗口/来源/ID 顺序/游标/纳秒 cutoff；凭据变更、撤权、关闭详情和页面卸载丢弃旧结果。历史名称、来源内容和字段仅作文本显示。

这不是 Entity 任意 asOf 重建、自动融合、冲突工单或完整告警 Observation 历史。MVP 的真实来源、模型、身份与人工审阅退出门槛继续保留。
