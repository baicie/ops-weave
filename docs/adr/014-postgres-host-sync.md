# ADR-014：Host 同步先落 PostgreSQL，完整快照才对账

状态：已采纳 · 日期：2026-09-22

## 决策

Zabbix Host 的 Entity、ExternalLink、Observation、Raw 元数据和 SyncRun 写入 PostgreSQL。Raw payload 第一版放 JSONB，不引入对象存储。

同步按游标翻页并记录 checkpoint。只有 `snapshotComplete` 才把本次未见的外部 Host 标为 `INACTIVE`。中途失败保留已有 Entity，不把失败当成空快照。

已经写入的 Observation 和 Entity 当前视图可以在扫描过程中被读取。删除和失活只发生在这次 offset 扫描走完之后。

`retireMissing` 使用的是来源 presence，不是映射成功集合。字段不合法但外部 ID 仍然存在的 Host 留在 presence 里，不会因为映射拒绝被标成 `INACTIVE`。

`host.get` 的 `limit/offset` 即使按 `hostid` 排序，扫描期间的新增或删除仍可能让后续页漂移。因此完成态是一次 `offset-scan-attempt`，不是数据库级一致快照。失败响应带回本次已经数到的 `pages/fetched/accepted/rejected`。

失败的 `SyncRun.failureReason` 使用稳定错误码加固定摘要，例如 `SOURCE_FETCH_FAILED`、`RAW_PERSIST_FAILED`、`MAPPING_FAILED`、`INVENTORY_WRITE_FAILED`、`CHECKPOINT_FAILED`、`PAGE_NOT_ADVANCED`、`PAGE_LIMIT_EXCEEDED`。接口不返回异常原文。

`OPSWEAVE_INVENTORY_STORE=memory` 只作为显式测试替身。默认 `postgres`，缺少 JDBC 配置时启动失败，不回退内存。

## 代价与后续

尚未做行级安全、最小权限运行角色和对象存储。Pipeline Preview/Replay 与 Zabbix Item 仍在这条持久链之后。
