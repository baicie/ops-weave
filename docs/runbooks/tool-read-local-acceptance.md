# 本机 Tool Gateway / Rust HTTP 验收

仅面向本机开发。Java 开发认证固定本进程租户、随机 Token、loopback；数据源明确使用 fixture，指标值是合成采样。这个验收不调用真实模型，也不替代 OIDC 或厂商来源验收。

准备专用 loopback PostgreSQL 和 VictoriaMetrics，通过环境注入 `OPSWEAVE_TEST_JDBC_URL`、`OPSWEAVE_TEST_JDBC_USER`、`OPSWEAVE_TEST_JDBC_PASSWORD`、`OPSWEAVE_TEST_VM_URL`。不要使用生产库或把凭据放在命令参数、任务消息、普通日志或提交文件中。已有 [Incident 验收](incident-local-acceptance.md) 和 [指标验收](metric-query-acceptance.md) 描述了基础环境。

```powershell
.\gradlew.bat :apps:platform-api:test :apps:ingestion-worker:test :apps:platform-api:bootJar :apps:ingestion-worker:bootJar --console=plain
pnpm --dir apps/web-console build
cargo build -p opsweave-agent-runtime --example platform_read_probe --locked -j 1
node scripts/check_metrics_stack.mjs --pipeline --runtime
```

若 Chromium 未安装，可在本机设置 `OPSWEAVE_TEST_CHROMIUM_EXECUTABLE` 为已安装的测试浏览器可执行文件。脚本为本次 Java 进程生成随机租户和 Token，显式授予验收权限；不会修改平台默认权限。脚本执行 Host/Item 同步、合成 VM 采样、问题导入、Incident 页面和受控读取：

1. Java 创建 PG 读取会话；并行执行 Incident 读取和指标摘要。验证 Gauge 均值 `0.355`、单位 `1`、fixture 来源和实际采集时间。
2. 回读持久证据，再通过 `evidence.get@2.0.0` 复核两次；第五次 Tool 调用必须 429。
3. Rust probe 使用同一开发调用者凭据向固定 Java 地址创建独立会话，读取两个输入并复核。它只输出 PASS，不输出凭据、问题内容或证据原文。
4. 继续流水线草稿/发布/持久只读重放回归，最后关闭脚本拥有的 Java/Vite/Chromium。

HTTP 合成验收输出保存在忽略目录 `.tmp/metrics-acceptance/`，三个 Tool JSON 可按同名 canonical Schema 验证。测试会写入指定的开发 PG/VM；数据库容器由操作者负责生命周期，脚本不会停止或删除已有容器。

独立 Rust probe 的环境为 `OPSWEAVE_PLATFORM_URL`、`OPSWEAVE_PLATFORM_TOKEN`、`OPSWEAVE_PROBE_INCIDENT`、`OPSWEAVE_PROBE_FROM`、`OPSWEAVE_PROBE_TILL`。后两者是整秒 epoch。目标必须为无路径、无内嵌凭据的数字 loopback HTTP origin；probe 要求 PG 存储和本手册的两条 fixture 指标样本。

真实模型摘要、AIInsight 持久化和诊断页面尚未接入本 probe；详情见 [ADR-022](../adr/022-current-knowledge-tool-evidence.md)。
