# ADR-043：只有已验证快照才允许对账

状态：实现与实际验证见 [验证报告第49节](../VALIDATION-REPORT.md)。

## 问题与决定

ADR-040/042 让默认 Host/Item 连接器按水位完成“已验证快照”，但这条规则只存在于连接器的自觉里：
用例仍然只看 `Page.snapshotComplete()`，因此一个未实现边界的连接器（自定义、未来新增，或声明完成却
只给 `offset-scan-attempt` 的适配器）仍可触发 `retireMissing`，把只是没被观测到的对象错误下线。
这与第41/45节修掉的“谁能写”不同，是“凭什么能退休”。

决定：把规则提升为系统级不变量。`SyncScan.verified(label)` 只承认 `hostid-watermark-snapshot` 与
`itemid-watermark-snapshot`；两个采集用例在 `snapshotComplete` 为真时仍要复核该标签，不满足就
以 `SOURCE_SCAN_UNVERIFIED` 失败、退休数为 0，已提交页与 Raw 保留。默认连接器不受影响（它们本来就
给出水位标签），而任何“声明完成但无法证明边界”的适配器从此无法退休对象。

## 语义与不变量

- 完成 ≠ 快照：`snapshotComplete: true` 只说明这次 walk 走完了，退休还需要一个被验证的边界标签。
- 未读任何页就失败的扫描保持默认 `offset-scan-attempt`：它没有对方法作出声明，也不会退休。
- 边界标签与失败码的关系不变：标签描述方法，`snapshotComplete` 与失败码描述结果。
- 该规则不引入新的授权、服务或调度：它只是把“不误删除”从默认连接器的行为变成用例层的强制检查。

## 边界

这条规则不能修复上游漂移，只能拒绝在不完整视图上做对账；它也不改变 offset 分页的语义（Zabbix 没有
keyset 过滤）。Problem/历史采集没有缺失对账，因此不适用。运行记录没有自动清理或总量配额；真实 Zabbix
行为、厂商实例验收与人工诊断审阅仍未完成。
