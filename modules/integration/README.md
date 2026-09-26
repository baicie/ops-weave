# integration

Connector SPI、同步任务、游标、映射和受控写入端口。

第一版 `PipelineDefinition` 绑定 Zabbix Host：Source → Parse → Map → Validate → EntityResolve → WriteObservation，并且只允许单链。同步写入 `SyncRun` 与游标。来源对象只要有外部 ID 就进入 presence，映射拒绝单独计数。未走完的扫描不会对账，没有快照证明的 walk 也不会对账。失败原因是稳定错误码，不把异常原文返回给调用方。

Host 扫描的完成态现在是 `hostid-watermark-snapshot`：首个分页请求前先读最高 `hostid` 与总行数作为边界，水位内升序分页，只有观测行数等于捕获计数且看到水位行才完成并允许对账；水位后新增不属于本次快照，删除/乱序/重复按 `SOURCE_SCAN_UNVERIFIED` 拒绝。未实现边界的连接器仍标 `offset-scan-attempt`。参见 [ADR-040](../../docs/adr/040-host-watermark-snapshot.md)。

Item 扫描使用同一套水位边界（`itemid-watermark-snapshot`，最高 `itemid` + 总行数双验证，漂移同样以 `SOURCE_SCAN_UNVERIFIED` 拒绝且不对账），算法由 `JsonRpcWatermarkBounds` 在两个连接器间共享；未读任何页就失败的扫描保持默认 `offset-scan-attempt`。参见 [ADR-042](../../docs/adr/042-item-watermark-snapshot.md)。

对账是强制的“已验证快照”行为：用例在 `snapshotComplete` 为真时仍要求边界标签为水位快照，否则以 `SOURCE_SCAN_UNVERIFIED` 失败且退休数为 0。未实现边界的连接器不能通过声明完成来退休对象。参见 [ADR-043](../../docs/adr/043-verified-snapshot-reconciliation.md)。

`PipelineVersion` 发布内容与 SHA-256 固定，SyncRun 在抓取前保存版本绑定；默认仍为内置 revision 1，不使用 latest。Map 仅允许 displayNameField=name/host。`HostPipelineService` 对留存 Raw 执行预览和只读重放，受 tenant/source/entity 范围、条数/字节/并发限制；没有 Connector/InventoryWritePort/通知或动作端口。完整 API、存储与缺失语义见 [Host 流水线操作说明](../../docs/runbooks/host-pipeline.md)。

`PipelineReplayService` 将只读报告保存为按 tenant/source/owner 隔离的记录；同键同参数幂等，短事务 claim、租约和 attempt fencing 支持显式恢复，成功结果不可覆盖。历史头部分页查询，报告读取重新授权；没有后台重试或写入型修复。参见 [ADR-018](../../docs/adr/018-durable-host-replay.md)。

`PipelineDraftService` 保存本人工作副本，以独立 editVersion 原子比较更新，冲突不覆盖。草稿不会改变已发布版本或默认同步；发布仍提交固定内容。PG / 内存适配明确区分，读取验证 digest。参见 [ADR-019](../../docs/adr/019-private-pipeline-drafts.md)。

Zabbix Item 按 `extensions/mappings` 中的映射文档写成指标目录和来源绑定。History 有只读增量接口与单流采集用例：来源实际数值类型、clock/ns 分页、绑定转换、权限、重叠窗口、批写确认后提交 checkpoint。持久化/HTTP 装配位于 ingestion-worker。Trigger 规则管理、Item Preview/Replay、可视化编辑器和 Integration Copilot 仍未实现。参见 [ADR-016](../../docs/adr/016-bounded-history-read.md) 与 [ADR-017](../../docs/adr/017-history-ingestion-checkpoint.md)。

Item 扫描与 Host 扫描一样持有来源 scope 租约（`externalType=item`）：取到租约才发请求、每页续租、目录/绑定写入与缺失对账都经 `SourceItemWritePort` 携带租约提交，结束时只释放自己的 token；失去/过期/被接管一律映射为 `SOURCE_SCAN_*` 稳定失败码，且不会对账。缺少租约时不会退回无围栏写入。参见 [ADR-039](../../docs/adr/039-item-scan-ownership.md)。

公开契约位于 `api/`；领域规则位于 `domain/`；用例位于 `application/`；实现适配位于 `infrastructure/`。

Zabbix 7.0 trigger problem 读取切片使用 `event.get` 问题窗口 + 明确恢复 ID，限制每页/时间/并发/传输大小，返回 `not-persisted` 与来源模式。恢复缺失不猜测时间，拒绝当前不支持的全局关联关闭。尚无持久告警/Incident 投影或页面；详见 [ADR-020](../../docs/adr/020-external-problem-read.md)。
