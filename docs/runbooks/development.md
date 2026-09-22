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

本接口用于来源预览与协议验证，不保存点，不提交 ingestion checkpoint。`windowComplete` 不覆盖以后补到旧时间戳的记录。读取设计见 [ADR-016](../adr/016-bounded-history-read.md)，显式采集见下节。

## 单数据流采集到 VictoriaMetrics

当前是本机开发链路，固定 tenant-demo、全部端点为 `127.0.0.1` 或 `::1`。默认关闭，不适用于直接开启远端 Compose 中的 Worker。先在平台同步 Host/Item，确保目标 item 已有活动绑定、映射数值语义和所需权限。

1. 启动 PostgreSQL 与 VictoriaMetrics。可使用 `docker compose --env-file .env -f deploy/compose/compose.yaml --profile metrics up -d postgres victoriametrics`；已有本机 PostgreSQL 时只启动 `victoriametrics`。VM 固定 v1.152.0、1ms 去重、非流式 Influx。镜像不是已验收的生产部署。
2. 配置 Worker 的 `OPSWEAVE_HISTORY_PLATFORM_URL`、`OPSWEAVE_HISTORY_PLATFORM_TOKEN`（同一开发授权边界的 Token，不写入仓库）、`OPSWEAVE_HISTORY_DATA_MODE`（`zabbix-jsonrpc` 或显式 `labeled-fixture`）、`OPSWEAVE_HISTORY_SOURCE` 和 `OPSWEAVE_HISTORY_ITEM_ID`。
3. 显式设置 `OPSWEAVE_HISTORY_INITIAL_FROM`（UTC epoch 秒）、`OPSWEAVE_HISTORY_STREAM`、`OPSWEAVE_HISTORY_VICTORIA_URL`，以及 `OPSWEAVE_HISTORY_JDBC_URL/USER/PASSWORD`。JDBC 仅允许 loopback，连接同一平台数据库的 worker 自有 schema。不要直接修改已有 checkpoint；更换存储目标、来源端点、映射或回补起点时使用新的 streamName。
4. 设置 `OPSWEAVE_HISTORY_ENABLED=true`，运行 `./gradlew :apps:ingestion-worker:bootRun`。第一次启动会建 `ingestion.history_checkpoint`；不建采样点表。

默认步长 60 秒、重叠 120 秒、延迟 10 秒、最多 4 页、完成一轮后等待 30 秒；对应配置在 Worker 的 `application.yml`。单次硬上限 8 页/4000 点/3600 秒，最大 4 页默认下是 2000 点。过密数据应缩小步长或另建支持高密度导出的适配，不得绕过分页失败跳过数据。

Fixture 在 `1789992000` 的两个不同值纳秒点落在同一毫秒，会按设计返回 `MILLISECOND_COLLISION`。若只演示成功写入，可从下一秒 `1789992001` 开始（唯一数值 0.40）；不要把第一个秒的拒绝解释为采集成功。

VM 写入后回读最多 11 次，间隔 2 秒（另有每次 HTTP 超时）。可见性确认失败、数据超出 VM 保留期或已有同毫秒不同值，都不推进游标。重新轮询会查询已存在的点；服务端仍须保持 1ms 去重。指标值/客户原文/Token 不写普通日志，只记录计数、streamName 和稳定错误码。

持久状态可查 `ingestion.history_checkpoint` 的 `completed_through/revision`，只允许管理员经测试数据库工具检查。健康检查仅代表进程存活，业务失败看 `SOURCE_FAILED`、`SINK_UNCONFIRMED`、`STORED_VALUE_CONFLICT`、`CHECKPOINT_BUSY` 等代码。没有把持久化能力暴露给模型或浏览器的任意 SQL 工具。

真实存储测试：设置 `OPSWEAVE_TEST_JDBC_URL/USER/PASSWORD` 和 `OPSWEAVE_TEST_VM_URL` 后执行 `./gradlew :apps:platform-api:test :apps:ingestion-worker:test`。缺配置时 IT 会跳过。构建两个 bootJar、安装本机 psql 并设置 `JAVA_HOME` 后，可以运行 `python3 scripts/check_history_stack.py`：该开发脚本生成临时 Token，启动真实 Java 平台/Worker，验证显式 fixture → 真 VM/PG，再关闭两个进程。

停止采集先关闭 Worker 或将 `OPSWEAVE_HISTORY_ENABLED=false` 后重启。保留 checkpoint 可以继续恢复；仅在停机和导出记录后由管理员执行 rollback SQL。详见 [ADR-017](../adr/017-history-ingestion-checkpoint.md)。
