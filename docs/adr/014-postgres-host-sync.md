# ADR-014：Host 同步先落 PostgreSQL，完整快照才对账

状态：已采纳 · 日期：2026-09-22

## 决策

Zabbix Host 的 Entity、ExternalLink、Observation、Raw 元数据和 SyncRun 写入 PostgreSQL。Raw payload 第一版放 JSONB，不引入对象存储。

同步按游标翻页并记录 checkpoint。只有 `snapshotComplete` 才把本次未见的外部 Host 标为 `INACTIVE`。中途失败保留已有 Entity，不把失败当成空快照。

`OPSWEAVE_INVENTORY_STORE=memory` 只作为显式测试替身。默认 `postgres`，缺少 JDBC 配置时启动失败，不回退内存。

## 代价与后续

尚未做行级安全、最小权限运行角色和对象存储。Pipeline Preview/Replay 与 Zabbix Item 仍在这条持久链之后。
