# 开发规程

1. 按初始化手册创建真实依赖锁，先验证默认Mock和所有可选feature。
2. 本地交互演示使用Rust+Vite，不依赖PG。所有数据为合成数据。
3. 合成数据与真实平台适配必须显式选择，禁止失败时静默回退。
4. `/healthz`只验证进程，`/readyz`只描述当前模式；都不是生产可用性承诺。
5. 输出失败排查runId、provider、预算、Schema和证据引用；不要把完整Prompt和Key打印到日志。
6. 导入真实数据前完成租户权限、Connector只读scope、Schema映射及去重；操作前备份。
7. 重放默认关闭通知/Action；误采数据通过可审计迁移修正，不盲删资源。

完整命令见 `docs/architecture/repository-guide.md`。

## History 只读验证

先启动 loopback 的 platform-api，显式选择 `fixture` 或 `jsonrpc` 来源模式并同步 Item，使目录和活动绑定存在。开发主体至少需要 `source.sync,metric.read,entity.read`；显式对象范围还须允许所选来源、指标和主机。真实模式需要服务器侧 Zabbix URL 和 SecretRef，失败不回退 fixture。

Fixture 的 `20001` 在 `2026-09-21T12:00:00Z` 有 ns=100、200 两点（25%、30%），下一秒有 40%。读取路径如下，Authorization 使用当前进程的开发 Token，勿把实际 Token 写入命令历史或文档：

```text
GET /api/v1/integrations/zabbix/items/20001/history?from=1789992000&till=1789992010&limit=1
```

第一点为十进制字符串 `0.25`，单位 `1`，维度 `mode=user`。后续请求保持同一 item、from/till，附加响应中的 `afterClock` / `afterNs`，直到 `windowComplete=true`。接口限 3600 个完整秒、每页 1–500 点，并标记 `persistence=not-persisted`。

错误响应不包含下一游标。`HISTORY_SECOND_LIMIT` 表示起始秒达到 501 条探测上限；不可手工跳过该秒后宣称完整。`METADATA_CHANGED` 需要检查映射并重跑 Item 同步。`SOURCE_FETCH_FAILED` 需要检查来源可达性/权限，错误原文不会返回客户端。`HISTORY_BUSY` 表示本进程 4 个并发槽已占用；服务端不自动重试。

本接口用于来源预览与协议验证，不保存点，不提交 ingestion checkpoint。`windowComplete` 不覆盖以后补到旧时间戳的记录；迟到点、VictoriaMetrics 批写、时间精度策略与持久采集任务仍待开发。设计见 [ADR-016](../adr/016-bounded-history-read.md)。
