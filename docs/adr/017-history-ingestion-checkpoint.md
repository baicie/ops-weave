# ADR-017：单数据流 History 采集、回读确认与持久游标

状态：已采纳 · 日期：2026-09-22

## 决策

在现有 ingestion-worker 中提供显式启用的单数据流轮询。默认关闭，仍为四个启动单元。当前仅 loopback 开发适配、固定 `tenant-demo`；平台 Token 来自服务器配置，所有 History 读取仍经过平台可信身份和资源授权，不由 Worker 自报权限。来源模式必须显式匹配，不允许真实来源失败后切 fixture。该开发适配不直接开启远端 Compose 的采集能力。

每轮最多读取 8 页、4000 点，固定窗口最多 3600 秒。默认每轮推进最多 60 秒，重读前 120 秒，避开最近 10 秒尚在写入的数据；完成后等待 30 秒再轮询。失败不在适配器中重试 POST；下一次计划轮询显式重读同一未完成窗口。窗口以来源时间定义，超过重叠范围的迟到点需操作员另建回补流。

分页结果先完整收集、验证版本和毫秒冲突，再一次批写。任何页失败、游标不前进、跨租户/来源、跨页版本变化或预算耗尽，均不提交部分窗口。数值/标签约束及存储协议见 [写入约定](../../contracts/telemetry/victoria-metrics.md)。

## checkpoint 与并发

同一 PostgreSQL 集群使用 worker 自有 `ingestion.history_checkpoint` 表。采集身份是 tenant/source/item，一条序列一行。`stream_name` 只是任务名，不参与主键，也不写入 VictoriaMetrics 标签。表内保存 initialFrom、已完成秒、身份摘要、revision、fencing token、租约截止时间和更新时间；不存采样点、逐点去重键或普通日志原文。

每轮两次短事务。第一次插入空进度（若还没有），在租约空闲时把 fencing token 加一并写上租约截止时间，然后立即提交。平台读取、VictoriaMetrics 预查、写入和回读确认都发生在事务之外。成功后再用原 revision 与本次 fencing token 条件更新进度并释放租约。失败则用同一 token 释放租约；进程消失时租约到期后才允许下一次领取。租约被抢走或 token 不匹配时，本次不得报告成功。有效租约内的竞争者返回 `CHECKPOINT_BUSY`。同一 item 配了另一个 streamName，或 initialFrom 与已有行不一致，返回 `CONFIGURATION_INVALID`。

外部写入和 PostgreSQL 无分布式事务：写入成功后进程退出，恢复会重读窗口，并先查询时序库中的已有点。租约默认 300 秒，只限制失联接管，不把数据库连接留在网络等待上。

身份摘要绑定完整序列标签、平台来源 URL/数据模式和 VictoriaMetrics 目标 URL；换目标、变更语义或 initialFrom 不会偷偷沿用旧游标。回补必须先停掉该 item 的 Worker，由操作员导出并删除这条 checkpoint，再从新的 initialFrom 启动。不能靠新的 streamName 并行开第二条游标。Token 轮换不改变摘要。摘要只绑定当前适配的语义字段；完整不可变 PipelineVersion 尚待后续实现。

这仍是单数据流上的租约，不是多 Worker 调度器。连接丢失后旧进程仍可能完成远端写入；fencing 保证它不能再推进游标，重复点靠不可变来源数据、预写比对和 VM 1ms 去重收敛。

## 验证和限制

本地协议桩覆盖身份、Schema、503、超大响应、写入但不可查询和已有值冲突。真实 PostgreSQL 验证失败不推进游标、重建 Store 后恢复、tenant/source/item 隔离、同一 item 的第二个 streamName 被拒绝、租约占用返回 `CHECKPOINT_BUSY`、失败释放租约，以及 fencing token 被替换后不能提交。真实 VictoriaMetrics 验证迟到点、确认后中断/重放与保留期外点拒绝。记录以 `VALIDATION-REPORT.md` 的实际执行结果为准。

回读确认只证明查询可见，不能保证 VM 磁盘丢失、超过保留期或任意数据修正后可自动恢复。原始 History 记录须视为不可变；同时间点修正需显式新版本/回补流程。重叠之外的迟到数据、生产认证、容量/HA、VictoriaMetrics 生产备份和指标查询页面不在此增量。

回退：先停用 `OPSWEAVE_HISTORY_ENABLED`。V002 把主键改成 tenant/source/item，并增加 fencing token 与租约列；已有重复 stream 行时迁移会失败，需要先停 Worker 并人工合并。必须删除 checkpoint 时，先导出记录，再由操作员使用 `db/rollback/ingestion/V001__history_checkpoint.sql`；此操作会使以后重新启用从 initialFrom 重放，不能在线自动执行。
