# ADR-041：把扫描边界标签持久化到运行记录

状态：实现与实际验证见 [验证报告第47节](../VALIDATION-REPORT.md)。

## 问题与决定

ADR-040 让 Host 扫描按 hostid 水位完成“已验证快照”，但这个结论只出现在同步响应里：运行记录不保存
`scanConsistency`，追溯页只能看到状态与 `snapshotComplete`。一次真实来源验收之后，操作员无法回答
“这条已完成的运行到底是水位快照还是 offset 尝试”，而两者的对账含义完全不同。

决定：在 `integration.source_sync_run` 增加 `scan_consistency` 列（V024，默认
`offset-scan-attempt`，闭集约束为两种标签），由 `succeed`/`fail` 在结束时写入当时的边界标签；追溯接口与
页面把它作为运行的一部分返回并展示。旧行保留默认值——它们确实是在水位 walk 出现之前写入的 offset 尝试。

## 语义与不变量

- 标签描述**方法**，不描述结果：`hostid-watermark-snapshot` 的失败运行仍然可能（且经常）是“没有快照证明”
  的失败；只有 `snapshotComplete: true` 才代表边界被验证。
- `RUNNING` 期间默认是 `offset-scan-attempt`（尚未证明任何边界），结束时由用例写入真实标签；写入失败不影响
  运行状态与失败原因的既有语义。
- 追溯接口返回该列；契约把 `scanConsistency` 设为运行的必填闭集字段，样例同时覆盖两种标签与“历史 offset 行”。
- 标签不参与授权，也不能被请求覆盖：它由服务端在运行时写入，客户端只能读。

## 边界

Item 采集仍写入 `offset-scan-attempt`（尚未实现水位边界）；Problem/历史采集不写运行记录。运行记录仍没有
自动清理或总量配额，也没有把标签用于自动决策（例如“只允许水位快照触发对账”的强规则尚未实现）。真实 Zabbix
行为、厂商实例验收与人工诊断审阅仍未完成。
