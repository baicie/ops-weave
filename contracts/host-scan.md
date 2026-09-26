# Host 扫描并发与失败契约

入口仍为 `POST /api/v1/integrations/zabbix/hosts/sync`。可信 Principal 的 `source.sync` 和来源范围授权先于运行；来源为操作员配置，映射版本先发布并固定到运行。请求仅接受已有的可选 `pipelineVersion`，不接受身份、执行工具、租约或 fence。

内部库存协议为 begin → renew → fenced upsert → finish，失败时释放自己的 token。作用域为 tenant/source/host，30秒租约和5分钟运行截止，PG 来源锁与数据库时间决定所有权。租约不是公开身份凭据，不返回浏览器或模型。错误使用 HTTP 503 与 [host-sync-failure schema](schemas/v1/host-sync-failure.schema.json)。无自动重试或 fixture 回退。

| 失败码 | 含义 |
|---|---|
| SOURCE_SCAN_BUSY | 同一来源范围已有有效扫描，本次未开始来源读取 |
| SOURCE_SCAN_LOST | 过期、被接管、完成后的旧 token 或范围不匹配，本次不能继续库存写入/对账 |
| SOURCE_SCAN_DEADLINE | 到达内部5分钟绝对截止，本次不能继续库存写入/对账 |
| SOURCE_SCAN_LIMIT | fence 到达上限，未开始来源读取，需操作员处理 |
| SOURCE_SCAN_UNVERIFIED | 本次 walk 结束但没有快照证明（边界后行数不再相等，或未看到边界行）；不对账，已提交页保留 |
| CHECKPOINT_FAILED | 状态保存失败；此前提交的记录或最终对账可能已经生效 |

其余来源/Raw/映射/库存/游标失败码保留。失败中的 `snapshotComplete` 固定 false，计数只代表本次已知进展，不保证全部回滚或上游一致性。`scanConsistency` 描述本次 walk 的**方法**：`offset-scan-attempt` 是纯 offset 尝试，`hostid-watermark-snapshot` 是下面的水位边界 walk；两者都不因标签本身证明某页已读取，只有 `snapshotComplete: true` 才表示本次边界被验证。BUSY 的 pages/fetched/accepted/rejected 都为0。关闭来源没有伪造 run 或失败阶段。

默认 Host 来源（fixture 与 JSON-RPC 连接器）在第一个分页请求之前先读取最高 `hostid` 和总行数作为边界：分页按 `hostid` 升序读，只有观测行数等于捕获计数、且确实看到该最高 `hostid` 时，才把 `snapshotComplete` 置为 true 并标注 `hostid-watermark-snapshot`。边界捕获之后新建的 host 不属于本次快照；上游删除会让 offset 漂移、观测行数少于捕获计数，此时返回 `SOURCE_SCAN_UNVERIFIED` 并拒绝对账。游标携带边界（offset、水位、计数、上一行与已观测数），服务端不从请求推断边界，客户端也不得构造游标；边界请求失败按 `SOURCE_FETCH_FAILED` 处理，不会退化成空快照。

未出现的 Host 只在有效所有者完成**已验证**分页后进行 INACTIVE 对账，不做物理删除。部分已提交 upsert、Raw 与运行记录保留；失去所有权的旧扫描不能对新数据执行缺失对账。水位快照假定 `hostid` 单调递增（Zabbix 主机 ID 由服务端分配），它是对本次 walk 的边界证明，不是数据库级一致快照，也不代表厂商实例已经验收；Item 采集仍是 `offset-scan-attempt`。lease 是写入所有权，不是快照证明。重复采集继续使用稳定 Entity ID。

实现上这条规则是强制的：只有 `hostid-watermark-snapshot`（Host）或 `itemid-watermark-snapshot`（Item）才允许对账。连接器报告 `snapshotComplete: true` 却只给出 `offset-scan-attempt` 时，扫描以 `SOURCE_SCAN_UNVERIFIED` 失败、退休数为 0，已提交页与 Raw 仍保留——即“未实现边界的连接器不能通过声明完成来退休任何对象”。未读到任何页就失败的扫描保持默认标签，它没有对方法作出声明。

本契约不扩展模型权限，不改变其他采集类型，不实现通用多源实体合并或自动后台扫描。
