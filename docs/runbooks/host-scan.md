# 本机 Host 扫描与租约故障

先使用已有来源/身份配置和固定版本同步流程。没有新增开关或凭据；PG 模式启动应用迁移 V020，内存模式仅为显式 fixture。生产数据库迁移尚未验收，不能在仍运行旧写进程时混合部署本变更。

收到 `SOURCE_SCAN_BUSY` 时查看原 HTTP 结果与运行进度，等待占用扫描结束；不要循环 POST。正常流程在 fetch 前后和每条记录续租。30秒未续租可由新的显式扫描接管，单次最多5分钟；调用失败没有自动重试。不要手动清空租约表，否则会丢失 fencing 历史。

`SOURCE_SCAN_LOST` / `SOURCE_SCAN_DEADLINE` 表示该运行已停止继续写入和缺失对账。此前已经提交的记录会保留，必要时由有权限的操作者显式重新发起完整扫描。旧进程恢复、旧请求迟到或 finally 释放都不能动新持有者。请求超时/`CHECKPOINT_FAILED` 可能有已提交数据，不意味着操作没有发生。

仅为定位问题，可由数据库操作员使用固定 scope 查看 `inventory.source_scan_lease` 的 run_id、fence、started_at、deadline_at、lease_until、released，并对照 integration SyncRun。这些字段不是模型输入授权，不应暴露到普通日志。不要把业务数据库交给模型查询。

本机验收使用独立随机租户、真实 loopback PostgreSQL 和合成 Host：两个独立适配器争用；重建适配器仍识别持有者；测试专用租约过期后接管；旧 upsert/对账/释放无效；等待实体行锁期间过期，实体/Observation/下线变化一起回滚。HTTP 测试验证 503、零进展计数和禁止用户提供 fence；纯领域测试恢复被暂停的旧扫描并统计连接器调用。实际结果见 [验证报告 §41](../VALIDATION-REPORT.md)。

这些检查不是真实 Zabbix、生产迁移、跨主机 HA 或通用二来源 presence 验收；用户暂未准备外部环境，保持未完成。
