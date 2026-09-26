# 当前知识诊断本机验收

范围：浏览器 → Java 授权入口 → Rust 当前知识流程 → Java Evidence 复核/AIInsight 持久化 → 刷新回读/证据。PG/VM 为真实本机存储；Zabbix 为 labeled-fixture，模型为 mock-deterministic，不是厂商/真实 AI 验收。

先按 `metric-query-acceptance.md` 配置专用 loopback 测试 PG/VM、Java21、Rust、Node/pnpm/Chromium。设置 `OPSWEAVE_TEST_JDBC_URL`、`OPSWEAVE_TEST_JDBC_USER`、`OPSWEAVE_TEST_JDBC_PASSWORD`、`OPSWEAVE_TEST_VM_URL`；凭据只经本地环境，不提交文件或粘贴日志。

```powershell
.\gradlew.bat :apps:platform-api:test :apps:ingestion-worker:test :apps:platform-api:bootJar :apps:ingestion-worker:bootJar
cargo test --workspace --locked -j 1
cargo test --workspace --all-features --locked -j 1
cargo build --workspace --examples --locked -j 1
cargo build --workspace --locked -j 1
pnpm --filter @opsweave/web-console build
node scripts/check_metrics_stack.mjs --pipeline --runtime
```

脚本每次生成随机开发 Token、独立 Runtime key、固定的随机租户，选 loopback 端口并启动/清理自有 Java/Rust/Vite/浏览器进程。显式授予本次验收所需的 `ai.diagnose`、`ai.insight.read`、Evidence/Incident/资产/指标权限；不修改默认权限。不会调用真实模型或 Zabbix，也不会启动 Worker 后台采集；VM 输入是两条明确的合成样本。

应看到原有指标、资产、Incident、Tool、Rust probe、流水线六项 PASS，以及新增当前知识诊断 PASS。新增检查含固定窗口 0.31/0.4 两点均值 0.355、四次读取/复核、PG 保存、同键 POST 回读不改结果、页面刷新 GET 找回同一结果、证据重读及 Token 清空后移除结果。JSON/截图只含合成测试数据，保存在忽略的 `.tmp/metrics-acceptance`。Schema 校验使用 canonical contracts，不能把 HTTP 200 当作验收终点。

手动本机运行时：Java 需要 `OPSWEAVE_RUNTIME_URL` 与 `OPSWEAVE_RUNTIME_KEY`；Rust 设置 `OPSWEAVE_MODE=platform-dev`、`OPSWEAVE_LISTEN=127.0.0.1:<port>`、`OPSWEAVE_PLATFORM_URL`、相同 Runtime key、`OPSWEAVE_PROVIDER=mock`、新 Skill 包目录。Java 与 Rust 模型配置必须一致，默认 mock；仍需平台显式 dev 身份及 loopback。浏览器 `#/incidents/current-diagnose` 只接收平台用户 Token。缺失 Runtime 配置返回 503，错误 Runtime key 返回 403，不回退旧 demo。

保存不确定时保留 runId，使用“读取已保存结果”；刷新会清空 Token，重新授权后再读。结果及证据到期（当前最多 24 小时）返回 410。若结果不存在且需要再次调用模型，由用户明确开始新诊断；没有后台自动重试或持久运行恢复。真实服务配置待用户提供后另做验收，MVP 目标仍为进行中。
