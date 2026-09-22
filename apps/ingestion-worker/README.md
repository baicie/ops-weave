# ingestion-worker

默认只提供健康检查，所有非健康 HTTP 路径仍拒绝。显式启用后可运行一个有界 History 采集流：受控平台 API → 数值/毫秒校验 → VictoriaMetrics 批写及回读确认 → PostgreSQL checkpoint。

`./gradlew :apps:ingestion-worker:bootRun`

`OPSWEAVE_HISTORY_ENABLED` 默认 `false`。当前只支持固定 tenant-demo 与 loopback 平台/存储端点，需显式配置平台开发 Token、来源模式、item、初始时间和数据库连接；不能直接开启远端 Compose 的生产采集。配置项及运行步骤见 [开发规程](../../docs/runbooks/development.md)。

默认每轮推进 60 秒、重读 120 秒、避开最近 10 秒；最多 4 页/2000 点，计划轮询间隔 30 秒。源码硬上限 8 页/4000 点。失败保留旧游标，在下一轮显式重试；不把源失败当空结果。VM 按 1ms 去重，批内不同值同毫秒拒绝；写前比对已有点，写后有限回读。该流程不是分布式租约引擎或跨库 exactly-once。

验证命令：`./gradlew :apps:ingestion-worker:test`。设置 `OPSWEAVE_TEST_JDBC_URL/USER/PASSWORD` 和 `OPSWEAVE_TEST_VM_URL` 才执行真实存储 IT；缺少配置会跳过，不能宣称已验证。构建两个 bootJar 后，`scripts/check_history_stack.py` 可启动真实 Java 平台/Worker 验证合成来源链。Python 只用于开发检查。
