# 本地实际来源重放验收

此步骤验证真实 Zabbix JSON-RPC→可信平台HTTP→已有PG/指标库/日志库，来源记录为明确标注的 Synthetic Fixture。它不验收外部客户、生产身份、网络策略、吞吐或多副本；开发身份只在随机端口、独立测试tenant中获得显式权限，日常平台默认权限保持。

已有本地来源需位于 `127.0.0.1:18088`，以及既有PG、VictoriaMetrics、ClickHouse和已部署日志V003表。凭据文件、样本、运行证明与截图留在仓库外私有目录；测试读取环境变量而非提交凭据。密钥环由测试随机生成、用完清理，业务原文不进PG元数据或普通日志。

先设置 `OPSWEAVE_ACCEPTANCE_PROVIDER_CREDENTIALS` 为本机凭据JSON的绝对路径（字段username/adminPassword），`OPSWEAVE_ACCEPTANCE_REPLAY_DIR` 为仓库外新的私有目录，以及 `OPSWEAVE_ACCEPTANCE_RELOAD_PROVIDER_CACHE=1`。显式运行：

```text
node scripts/acceptance/workflow-replay-provider.mjs
```

脚本只操作固定本地来源：创建独立Synthetic Fixture组/主机/两个trapper item，验证标签和所有权后写60条CPU百分比与60条日志，精确读回；显式刷新既有本地来源容器的配置缓存，不重启服务。每个修改在本机持久attempted标记，响应丢失不自动重发。读取可见性探测有界，不属于产品隐藏重试。新目录不得复用别人的业务或旧验收状态，产物包含会话令牌，只能保存在私有目录。

随后设置 `OPSWEAVE_TEST_REPLAY_PROVIDER_FILE` 为生成的provider-fixture.json，以及 `OPSWEAVE_TEST_JDBC_URL/USER/PASSWORD`、`OPSWEAVE_TEST_VM_URL`、`OPSWEAVE_TEST_LOGS_URL/USER/PASSWORD`，在已初始化锁定环境运行：

```text
./gradlew :apps:platform-api:test --tests '*WorkflowReplayProviderHttpIT' --no-daemon
```

未提供来源文件时该可选本机测试跳过，不能报告通过；发布验收必须检查实际测试数与零跳过。测试完整经过凭据加密保存、受控连接、连接测试、分页发现、固定来源选择、草稿、真实只读预览、不可变发布、两步重放、精确输出回读及日志50/10分页。全局旧来源端口关闭，不能用Fixture fallback证明成功。

最后对本人Fixture日志增加一条已确认可见的迟到记录，新的封存范围应SOURCE_WINDOW_CHANGED且输出为空；原确认投影与原UUID仍保持，连续任务/授权/checkpoint没有变化。修改attempted标记存在时不重试同一push；每次完整复验用新私有目录。证据保留provider-proof、两类plan/receipt、分页及来源变化回执，无自动删除原记录以恢复容量。

这项验收不提供覆盖修复或原始快照留存。测试异常先观察原操作和回执，UNKNOWN不推断未写入，不重发来源或输出。参见[指标重放](workflow-metric-replay.md)、[日志重放](workflow-log-replay.md)与[实际验证](../VALIDATION-REPORT.md)。
