# ADR-017：单数据流 History 采集、回读确认与持久游标

状态：已采纳 · 日期：2026-09-22

## 决策

在现有 ingestion-worker 中提供显式启用的单数据流轮询。默认关闭，仍为四个启动单元。当前仅 loopback 开发适配、固定 `tenant-demo`；平台 Token 来自服务器配置，所有 History 读取仍经过平台可信身份和资源授权，不由 Worker 自报权限。来源模式必须显式匹配，不允许真实来源失败后切 fixture。该开发适配不直接开启远端 Compose 的采集能力。

每轮最多读取 8 页、4000 点，固定窗口最多 3600 秒。默认每轮推进最多 60 秒，重读前 120 秒，避开最近 10 秒尚在写入的数据；完成后等待 30 秒再轮询。失败不在适配器中重试 POST；下一次计划轮询显式重读同一未完成窗口。窗口以来源时间定义，超过重叠范围的迟到点需操作员另建回补流。

分页结果先完整收集、验证版本和毫秒冲突，再一次批写。任何页失败、游标不前进、跨租户/来源、跨页版本变化或预算耗尽，均不提交部分窗口。数值/标签约束及存储协议见 [写入约定](../../contracts/telemetry/victoria-metrics.md)。

## checkpoint 与并发

同一 PostgreSQL 集群新增 worker 自有 `ingestion.history_checkpoint` 表。键为 tenant/source/item/streamName，保存 initialFrom、已完成秒、身份摘要、revision 和更新时间；不存采样点、逐点去重键或普通日志原文。

每轮只操作一条 checkpoint。事务中 `SELECT ... FOR UPDATE NOWAIT` 持有数据流锁，竞争者明确返回 `CHECKPOINT_BUSY`。来源读取、批写、回读确认都成功后，CAS revision 再提交事务；异常回滚。外部写入和 PostgreSQL 无分布式事务：写入成功后进程退出，恢复会重读窗口，并先查询时序库中的已有点。

身份摘要绑定完整序列标签、平台来源 URL/数据模式和 VictoriaMetrics 目标 URL；换目标、变更语义或 initialFrom 不会偷偷沿用旧游标。需要显式配置新的 streamName 从指定时间回补。Token 轮换不改变摘要。摘要只绑定当前适配的语义字段；完整不可变 PipelineVersion 尚待后续实现。

这不是租约/fencing 的分布式采集引擎。SQL 事务只保护 checkpoint；连接丢失后旧进程仍可能完成远端写入，靠不可变来源数据、预写比对和 VM 1ms 去重限制重复效果。先保持单数据流、单 Worker，扩展之前独立实现租约/执行器 fencing。

## 验证和限制

本地协议桩覆盖身份、Schema、503、超大响应、写入但不可查询和已有值冲突。真实 PostgreSQL 验证事务回滚、重建 Store 后恢复、键隔离和并发锁。真实 VictoriaMetrics 验证迟到点、确认后中断/重放与保留期外点拒绝。记录以 `VALIDATION-REPORT.md` 的实际执行结果为准。

回读确认只证明查询可见，不能保证 VM 磁盘丢失、超过保留期或任意数据修正后可自动恢复。原始 History 记录须视为不可变；同时间点修正需显式新版本/回补流程。重叠之外的迟到数据、生产认证、容量/HA、VictoriaMetrics 生产备份和指标查询页面不在此增量。

回退：先停用 `OPSWEAVE_HISTORY_ENABLED`。应用可回退到上一版本；新增表保留不会影响旧版本。必须删除 checkpoint 时，先导出记录，再由操作员使用 `db/rollback/ingestion/V001__history_checkpoint.sql`；此操作会使以后重新启用从 initialFrom 重放，不能在线自动执行。
