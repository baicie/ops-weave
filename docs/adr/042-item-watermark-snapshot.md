# ADR-042：Item 采集的 itemid 水位快照

状态：实现与实际验证见 [验证报告第48节](../VALIDATION-REPORT.md)。

## 问题与决定

ADR-039 让 Item 扫描取得来源 scope 租约，解决了“谁能写”；但那次 walk 仍是 `offset-scan-attempt`：
按 `itemid` 升序的 limit/offset 分页在上游删除一行后会让 offset 跳过另一行，而扫描仍然完成并执行
`retireMissing`，把只是被跳过的指标绑定错误置为 `INACTIVE`。这与 ADR-040 修掉的 Host 缺口是同一类问题，
只是后果落在指标绑定上。

决定：把 ADR-040 的水位边界同样用于 Item 采集。默认 Item 来源在第一个分页请求之前读取当前最高 `itemid`
与总行数，在该水位内升序分页；只有观测行数等于捕获计数、且确实看到水位行时才 `snapshotComplete=true`
并标注 `itemid-watermark-snapshot`，否则以 `SOURCE_SCAN_UNVERIFIED` 失败且不对账。水位之后新增的 item
不属于本次快照；边界请求失败按 `SOURCE_FETCH_FAILED` 处理，绝不退化成空快照。

水位边界的实现被抽成两个连接器共享的 `JsonRpcWatermarkBounds`（捕获、游标编解码、验证判定），避免这套
细微算法在两个连接器里各写一份。V025 把运行记录的标签闭集扩展为三种，`source_sync_run` 的约束名保持不变。

## 语义与不变量

- 游标携带边界（offset、水位、计数、上一行、已观测数）；缺少边界的游标被拒绝，服务端不推断。
- `itemid` 非数字、行序倒退、跨页重复、行数不符都按同一路径拒绝：已提交页保留，缺失对账不发生。
- 空来源（计数为 0）是已验证的空快照；它仍然需要来源租约，但不会因为“没有数据”而跳过所有权检查。
- 未读到任何页就失败的扫描（例如来源被别的扫描占用）保持默认 `offset-scan-attempt`：它没有读过页，
  就不对方法作任何声明。
- 标签描述方法而非结果；只有 `snapshotComplete: true` 才代表边界被验证。

## 边界

水位快照假定 `itemid` 单调递增（Zabbix 由服务端分配），它是对本次 walk 的边界证明，不是数据库级一致快照，
也不代表厂商实例已验收。Item 扫描的完成态仍是 offset 分页的**边界**（Zabbix 没有 keyset 过滤），因此删除
只能被“拒绝”而不能被“修复”。Problem/历史采集继续使用 ingestion 自有 checkpoint 租约，本轮不变；运行记录
没有自动清理或总量配额；把标签用于自动决策（例如“只允许水位快照触发对账”的强规则）仍未实现。
