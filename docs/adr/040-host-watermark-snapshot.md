# ADR-040：Host 扫描的 hostid 水位快照与漂移拒绝

状态：实现与实际验证见 [验证报告第46节](../VALIDATION-REPORT.md)。

## 问题与决定

M2 的退出条件要求一条来源“完成分页、游标、完整快照”，而此前 Host 扫描固定标注 `offset-scan-attempt`：
按 `hostid` 升序的 limit/offset 分页在上游新增时会让后续页漂移，删除更会让 offset 跳过一行；漂移之后
仍然执行缺失对账，就会把只是被跳过的资产错误置为 `INACTIVE`。

决定：默认 Host 来源（fixture 与 JSON-RPC 连接器）在第一个分页请求之前先读取两个边界——当前最高
`hostid` 与当前总行数——然后在该水位内升序分页。walk 只有在“观测行数等于捕获计数”且“确实看到水位行”
时才把 `snapshotComplete` 置为 true，并把方法标注为 `hostid-watermark-snapshot`；否则本次 walk 以
`SOURCE_SCAN_UNVERIFIED` 失败且不对账。游标携带边界（offset、水位、计数、上一行、已观测数），服务端
不从请求推断边界，客户端也不得构造游标。

## 语义与不变量

- 水位捕获之后新建的 host（`hostid` 大于水位）不属于本次快照：它们不出现在记录里，也不参与对账。
- 上游删除会让 offset 漂移：观测行数少于捕获计数，walk 结束但不是快照 → `SOURCE_SCAN_UNVERIFIED`，
  已提交页保留、缺失对账不发生。`hostid` 非数字、行序倒退、跨页重复都按同一路径拒绝。
- 空来源（计数为 0）是**已验证的空快照**，不需要分页请求，可以正常对账；边界请求本身失败则是
  `SOURCE_FETCH_FAILED`，绝不退化成空快照。
- `scanConsistency` 描述**方法**：失败运行也会带方法标签，只有 `snapshotComplete: true` 才代表边界被验证。
  `offset-scan-attempt` 仍保留给未实现边界的连接器（例如自定义或未来的连接器）。
- 水位快照假定 `hostid` 单调递增（Zabbix 由服务端分配主机 ID）。它是对本次 walk 的边界证明，不是数据库级
  一致快照，也不是厂商实例验收：真实 Zabbix 的 `hostid` 分配、`countOutput` 与排序行为仍需真实环境验证。

## 边界

Item 采集仍是 `offset-scan-attempt`（本轮只做 Host）；Problem/历史采集使用 ingestion 自有 checkpoint 租约。
水位边界不解决 `limit/offset` 之外的分页语义，也不引入后台重扫、分布式调度或跨来源快照。运行记录本身仍不
保存一致性标签（追溯页显示状态与 `snapshotComplete`，方法标签只在同步响应中返回）；把标签持久化到
`source_sync_run` 是后续工作。
