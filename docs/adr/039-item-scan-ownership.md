# ADR-039：Item 扫描的来源租约与受围栏对账

状态：实现与实际验证见 [验证报告第45节](../VALIDATION-REPORT.md)。

## 问题与决定

第41节给 Host 扫描加了持久来源租约、单调 fence 与事务内对账，但 Item 扫描一直缺这一层：它会
`retireMissing` 把本来源缺失的指标绑定置为 `INACTIVE`，却既不取租约也不复核所有权。两个并发的 Item
扫描可以交错写入，一个已经过期或被接管的旧扫描还能按自己那份更旧的 presence 去对账，把新扫描刚观测到
的绑定错误下线。这是与 Host 路径同类、且更难人工发现的正确性缺陷。

决定：Item 扫描复用同一套来源租约（scope 的 `externalType` 为 `item`），不新增表、服务、队列或调度。
扫描在取到租约后才发第一个来源请求；每页开始前续租；目录/绑定写入与缺失对账都通过一个受围栏端口
`SourceItemWritePort` 提交，必须携带当前租约；扫描在 `finally` 里释放自己的租约，且只释放与自己 token
完全一致的租约。租约失败映射为既有的稳定失败码 `SOURCE_SCAN_BUSY/LOST/DEADLINE/LIMIT`，与 Host 路径一致。

## 围栏语义

PostgreSQL 适配器在同一事务里做三件事：先按 scope 取咨询锁并读取租约（`PostgresSourceScans.require`，
用数据库时钟判断，过期即 LOST、超过 5 分钟即 DEADLINE），再写目录/绑定或执行缺失对账，最后在事务结束前
复查时间并续租；任何一步失败都会回滚整笔调用，因此"写了一半"不会发生。对账只把本来源在该次扫描中缺失的
绑定置为 `INACTIVE`，不删除目录定义，也不触碰其他来源。

内存适配器执行真实的租约校验（失去、过期、被接管都会拒绝），但它不是事务性的：这是显式标注的开发实现，
生产语义以 PostgreSQL 适配器为准。

## 边界

所有权解决的是"谁能写"，不是"上游是否一致"：Item 扫描完成态仍是 `offset-scan-attempt`，offset 分页期间
的上游漂移依旧不修复。已提交页的目录/绑定保留（与 Host 相同），只有缺失对账被围栏拒绝；遗留的 `RUNNING`
元数据仍不自动修复。Host 与 Item 是彼此独立的 scope，可并发；Problem/历史采集走的是 ingestion 自有
checkpoint 租约（V002），它没有缺失对账，因此不在本次改动范围。跨租户/跨来源没有全局所有权，也没有
后台调度或分布式 HA。真实 Zabbix/模型/IdP/TLS 验收与人工诊断审阅仍未完成。

同时修正两处上一节留下的实现缺口：V023 读取索引此前既没有登记进 `InventoryWiring` 的迁移列表、也没有
登记进 `processResources`，因此从未真正创建；现在两者都补齐。`SOURCE_SCAN_*` 的固定摘要原先写作
"host scan"，在 Item 扫描也会返回这些码之后改成作用域中性的表述。
