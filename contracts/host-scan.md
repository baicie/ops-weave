# Host / Item 扫描并发与失败契约

Host 入口为 POST /api/v1/integrations/zabbix/hosts/sync，Item 入口为 /api/v1/integrations/zabbix/items/sync。可信 Principal 的来源范围和 source.sync 授权先于运行；来源来自操作员配置。Host 的映射版本先发布并固定到运行，请求仅接受已有的可选 pipelineVersion，不接受身份、执行工具、租约或 fence。

内部写入协议为 begin → renew → fenced upsert → finish；tenant/source/对象类型范围、30秒租约和5分钟绝对截止由执行器检查。错误使用 HTTP 503 与 [host-sync-failure schema](schemas/v1/host-sync-failure.schema.json)，不自动重试或回退 fixture。

| 失败码 | 含义 |
|---|---|
| SOURCE_SCAN_BUSY | 同一范围已有有效扫描，未开始来源读取 |
| SOURCE_SCAN_LOST | 所有权过期、被接管或范围不匹配，停止写入与对账 |
| SOURCE_SCAN_DEADLINE | 到达5分钟绝对截止 |
| SOURCE_SCAN_LIMIT | fence 到达上限，需操作员处理 |
| SOURCE_SCAN_UNVERIFIED | 清单发生变化、批次缺项/重复/含未请求 ID，或连接器不能证明完整性；不对账 |
| SOURCE_FETCH_FAILED | 上游失败、清单数量超限、ID 非法/乱序，或捕获时计数不一致；不作为空快照 |
| CHECKPOINT_FAILED | 状态保存失败；此前提交或最终对账可能已生效 |

失败时 snapshotComplete 固定 false，已成功提交的页、Raw 与运行记录保留，不保证回滚。只有已验证完整成员集合的有效所有者能够将未出现对象设为 INACTIVE，不做物理删除。

## 真实 Zabbix 的有界成员清单

2026-09-27 在 Zabbix 7.0.27 实测发现 host.get / item.get 忽略 offset。连接器不再发送 offset，也不以最高 ID 加总数相等作为完整证明。

1. 读取 countOutput，超过1000个对象即拒绝，不读无限结果、不静默截断。
2. 只读取对应 ID 字段，按 ID 升序，limit=1001；数量必须与计数一致且不超过1000，ID 必须严格递增、无重复。
3. 游标固定方法与成员清单 SHA-256、计数和本地位置。每页重新读取有界 ID 清单，必须与游标一致。
4. 使用厂商支持的 hostids / itemids 精确选取最多500个 ID。返回 ID 集合及顺序必须与请求完全一致；缺项、重复或意外项拒绝整页，不能写入该页。
5. 最后一页（包括空集合）再次验证成员清单；一致才 snapshotComplete=true。上游字段仍可能并发变化，成员在两次观察之间变化后又恢复也不可能被保证检测；这不是数据库事务快照。

游标只在服务端流转。旧 offset 格式不被接受，重新发起同步；没有自动恢复旧游标或绕过完成条件。当前1000个对象上限是明确的 MVP 容量边界，与既有 Raw 准入额度独立；不声称支持任意大来源。每页只重复读取有界 ID 元数据，历史采样不走该清单。

保留 wire 标签 hostid-watermark-snapshot / itemid-watermark-snapshot 以兼容已持久运行与契约。新实现的证明更强，但旧记录不能被追认为已经执行新算法。只有 snapshotComplete=true 且标签获用例层认可才允许对账；offset-scan-attempt 仍表示未证明的尝试，首次请求失败也保持该默认标签。

Fixture 使用内存中的固定成员列表分页，继续显式标记 labeled-fixture。真实上游失败禁止切换 Fixture。身份、源范围、时间/数量预算、租约和映射版本边界保持不变。

官方参数：[host.get](https://www.zabbix.com/documentation/7.0/en/manual/api/reference/host/get)、[item.get](https://www.zabbix.com/documentation/7.0/en/manual/api/reference/item/get)。真实本地验证和失败尝试见验证报告第64节；不替代客户环境与生产认证验收。
