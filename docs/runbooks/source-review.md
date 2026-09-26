# 补充来源字段审核本机验收

对应 ADR-027。当前是固定 CMDB 导入契约，原始字段只允许 name、ip、owner、environment；未连接厂商 CMDB API。Zabbix 来源仍按所选配置显式显示 fixture 或真实模式，导入始终显示 `import`。

## 开启与操作

仅在现有 loopback 开发环境中，把 `OPSWEAVE_CMDB_IMPORT_SOURCE` 设为明确来源标识，如 `cmdb-import-dev`，并在已有权限配置中显式授予 `entity.manage`、`entity.read` 和对应来源的 `source.sync`。默认配置不启用该来源、不授予管理权。保持随机开发 Token 和固定开发 tenant；不在任务消息或普通日志中复制凭据。已有 PostgreSQL 使用启动迁移 V015，不增加数据库或进程。

1. 在资产详情点击“读取补充来源”，获取服务端配置的映射 digest、来源及授权分页。
2. 展开“导入一条补充记录”，填写外部资产编号、最近七天内的 UTC 观测时间，以及要补充的字段。空白字段不提交。
3. 暂存并预览。核对目标资产 UUID、版本、主来源原值、补充值和外部编号；暂存不会改写资产。
4. 每个字段选保留主来源或采用补充来源，填写原因后确认；也可以拒绝。资产版本已变或记录已过期时不能接受，需重新读取并基于当前版本建立新记录。
5. 后续 Host 同步应保留批准的补充值，并更新主来源快照和原始 Observation。当前投影有补充来源、时刻、到期时间、映射 digest 和字段列表。
6. 查看生效记录，填写原因并撤销。资产恢复最新主来源值，历史导入、观察和原回执保留。完整主来源扫描认定 INACTIVE 时，撤销不会将它复活。

每个资产及每个补充外部对象只能有一个生效绑定；必须先撤销再确认另一条。字段审核不搬移 Zabbix EntityId、ExternalLink、MetricBinding 或 Incident/Evidence 引用，不按同名或 IP 自动匹配。

提交超时或服务端失败时，页面保留原 requestId 和请求正文，可点击“按原请求重试”；不会自动重试。相同主体/正文返回原决策回执，不重复加版本；不同正文使用同键返回 409。成功写入后刷新读取失败也保留成功回执。换身份、退出页面或清除会话会清理导入内容和迟到响应。

## 可复现检查

- `python scripts/check_repo.py`、`python -m pytest tests/contracts -q`：规范 Schema/样例、固定映射、非法身份/字段/数量/时间和请求边界。Python 仅作开发检查。
- `python scripts/check_java_domain.py`：包含 `SourceReviewSmoke`，覆盖逐字段权威、幂等、乱序同步、撤销、到期、权限范围、分页与生命周期。
- 设置专用测试数据库 `OPSWEAVE_TEST_JDBC_*` 和 `OPSWEAVE_TEST_VM_URL` 后运行 `gradlew.bat :apps:platform-api:test :apps:ingestion-worker:test :apps:platform-api:bootJar :apps:ingestion-worker:bootJar --console=plain`。PG 用例验证六并发同键、两资产竞争同一外部对象、失败原子回滚、适配器重开、旧数据不回退和撤销恢复。
- Rust 默认与 all-features 检查在启动真实 Runtime 验收前完成，避免 Windows 运行中 exe 被编译器覆盖。
- `pnpm --dir apps/web-console build` 和 `pnpm test:web`：页面及共享会话回归。Zeus For 使用返回子组件的表达式回调；块体回调曾造成控件未渲染，实际浏览器测试已覆盖。
- `node scripts/check_metrics_stack.mjs --pipeline --runtime`：显式设置上面的测试存储变量及可用 Chromium；启动真实本机 Java/Rust/Vite/浏览器，主来源 fixture、模型 mock。新增页面暂存→确认→主来源再次同步→指标仍可读→撤销恢复→PG 历史的链路。只被动捕获同一次代理响应，没有替换业务响应。

实际执行结果以 VALIDATION-REPORT 第 33 节为准。测试输出只含合成数据，`.tmp` 和凭据不提交。

## 边界

V015 需要配套 writer 锁与投影规则；发布前停止旧 writer，不能把旧二进制在线并行写入称为兼容。未演练生产大表迁移、故障切换、OS 崩溃、容量或回退。页面按 UUID 实时分页，不承诺按时间排序或跨页同一事务快照。七天是固定导入新鲜度规则，不会自动刷新数据；过期后最后已知字段保留且展示到期语义。

这还不是通用资产合并、强标识自动 Resolver、多来源实时 presence 融合、任意 asOf 投影或完整来源事件日志。记录留存清理/总量配额、完整告警观测、真实厂商来源/模型/登录与人工诊断评估仍需完成。
