# ADR-014：Host 同步先落 PostgreSQL，完整快照才对账

状态：已采纳 · 日期：2026-09-22

## 决策

Zabbix Host 的 Entity、ExternalLink、Observation、Raw 元数据和 SyncRun 写入 PostgreSQL。Raw payload 第一版放 JSONB，不引入对象存储。

同步按游标翻页并记录 checkpoint。只有 `snapshotComplete` 才把本次未见的外部 Host 标为 `INACTIVE`。中途失败保留已有 Entity，不把失败当成空快照。

已经写入的 Observation 和 Entity 当前视图可以在扫描完成前被读取。删除和失活只发生在完整快照成功之后。

失败的 `SyncRun.failureReason` 使用稳定错误码加固定摘要，例如 `SOURCE_FETCH_FAILED`、`RAW_PERSIST_FAILED`、`MAPPING_FAILED`、`INVENTORY_WRITE_FAILED`、`CHECKPOINT_FAILED`、`PAGE_NOT_ADVANCED`、`PAGE_LIMIT_EXCEEDED`。接口不返回异常原文。

`OPSWEAVE_INVENTORY_STORE=memory` 只作为显式测试替身。默认 `postgres`，缺少 JDBC 配置时启动失败，不回退内存。

## 代价与后续

尚未做行级安全、最小权限运行角色和对象存储。Pipeline Preview/Replay 与 Zabbix Item 仍在这条持久链之后。
