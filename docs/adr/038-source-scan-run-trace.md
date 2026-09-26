# ADR-038：只读的来源扫描运行追溯

状态：实现与实际验证见 [验证报告第44节](../VALIDATION-REPORT.md)。

## 问题与决定

第41–43节让扫描具备租约、fencing、快照与更正能力，但每次同步失败只把 `syncRunId` 写进响应：运行记录
（状态、游标、pages/fetched/accepted/rejected、失败码、启动时钉住的映射版本）虽然已经持久化，却没有任何
读取入口。操作员事后无法回答“上次扫描停在哪、为什么停、有没有对账”，真实来源接入时也缺少第一条排查线索。

决定：增加**只读**的扫描运行追溯，不新增服务、队列、调度或动作工具。Host 与 Item 各两个 GET：
最近运行的分页读取与按 `syncRunId` 的单条读取。租户来自可信 Principal，来源来自配置，权限沿用源级
`source.sync`；只有 `entity.read` 不能读取扫描日志。整个入口没有 Connector、租约、库存写入或对账端口，
读一次不会改变任何运行状态。

## 查询、分页与存储

运行按 `started_at DESC, id DESC` 排序，`id` 作为同一时刻的确定性次序，避免同一微秒内的运行顺序漂移。
`limit` 为 1–50（默认 20），`after` 是服务端签发的不透明游标（完整精度时间 + 运行标识的 base64url），
只接受服务端返回值：它不携带授权，也不替代 Principal。存储层按 `limit + 1` 取行以便区分“恰好取满”与
“还有下一页”，响应用 `hasMore`/`nextCursor` 表达；V023 只增加一条
`(tenant_id, source_instance_id, object_type, started_at DESC, id DESC)` 索引，让这个有界读取不随表增长退化成
全表排序，不新增表、列或数据。

未知、跨租户、跨来源或跨对象类型的 `syncRunId` 一律 404，不泄漏存在性；未知查询参数、越界 `limit`、
客户端构造的游标一律 400。响应沿用平台统一边界：`Cache-Control: no-store` 与 `nosniff`。

## 不变量与安全

`SUCCEEDED` 必须 `snapshotComplete: true`，`RUNNING`/`FAILED` 必须为 false，且 `RUNNING` 没有完成时间。
失败码只从平台自己写入的稳定前缀还原：存储里出现未知文本时，响应既不返回 `failureCode`/`failureSummary`，
也不回显原文，因此历史遗留或手工改写的行不会变成泄漏通道。`pipelineVersion` 是扫描启动时钉住的映射版本
引用；未钉住时不出现。`dataMode: scan-log` 只说明“读的是持久化扫描日志”，每条运行自己的 `dataMode`
（如 `labeled-fixture`）如实反映那次扫描写入数据时的模式，二者都不代表上游在线或真实厂商已验收。

## 边界

追溯不是修复：它不重试、不续租、不对账、不清理，也不会把遗留的 `RUNNING` 元数据改成失败或成功；第41节
记录的进程终止遗留状态仍然存在。它不返回厂商原始报文或 Raw 载荷，也不提供跨租户/跨来源的全局运行检索。
运行表本身没有自动清理或总量配额，只有单次读取的上限。生产滚动迁移、多主机故障切换、真实 Zabbix/模型/IdP
验收与人工诊断审阅仍未完成。
