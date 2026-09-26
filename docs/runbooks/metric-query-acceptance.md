# 指标查询验收

适用范围：本机开发身份、合成 Zabbix 来源、真实 PostgreSQL 与 VictoriaMetrics。不是厂商接入或生产认证验收。

## 前置条件

- Java 21、仓库固定的 pnpm 与依赖、Playwright Chromium。
- 独立测试 PostgreSQL 数据库和 VictoriaMetrics v1.152.0，端口只发布在 loopback。
- VictoriaMetrics 使用 `-dedup.minScrapeInterval=1ms`、`-influx.forceStreamMode=false`、`-retentionPeriod=1y`。PowerShell 中将每个带点的参数用单引号包住，避免参数被拆分。
- 通过环境设置 `OPSWEAVE_TEST_JDBC_URL`、`OPSWEAVE_TEST_JDBC_USER`、`OPSWEAVE_TEST_JDBC_PASSWORD`、`OPSWEAVE_TEST_VM_URL`。URL 不嵌入凭据；数据库仅用于测试，脚本会同步明确标注的 fixture 资产和指标。

## 执行

```text
pnpm install --frozen-lockfile
pnpm --filter @opsweave/web-console exec playwright install chromium
./gradlew :apps:platform-api:test :apps:ingestion-worker:test :apps:platform-api:bootJar :apps:ingestion-worker:bootJar
pnpm typecheck:web
pnpm build:web
pnpm test:web
node scripts/check_metrics_stack.mjs
```

Windows 使用 `gradlew.bat`。最后一步可通过 `OPSWEAVE_TEST_CHROMIUM_EXECUTABLE` 指定已经安装的测试 Chromium；默认使用 Playwright 的浏览器。脚本生成随机开发 Token，仅在子进程环境和页面内存使用，并在结束时关闭自身启动的 Java、浏览器和预览服务。

浏览器脚本使用生产构建，不拦截业务请求：`Chromium → Vite preview 代理 → Java 授权 API → PostgreSQL 元数据 / VictoriaMetrics 采样点`。插入的是标记为 `labeled-fixture` 的近期采样点；这一步验证查询，不代替 Worker 采集测试。截图保存在 gitignore 的 `.tmp/metrics-acceptance/metrics.png`。

## 必须区分的结果

- `NO_DATA`：完整成功读取后，查询窗口内无点。
- `STALE`：有点，但最新返回点早于窗口终点 300 秒。
- `PARTIAL`：扫描或返回点数上限导致结果不完整；即使返回零点，也不能证明没有数据。`fresh` 仅描述返回点。
- `503`：存储关闭、不可达或返回身份/单位/结构不合法；不得显示成功空结果。

## 计数器变化率（SUM）

只有 `MetricType.SUM`（计数器）查询会得到派生视图：响应带 `derivation = {kind: counter-rate, resetPolicy: reset-counts-from-zero}`，每个序列在保留原始 `points` 的同时给出 `counterRates`（`t` 为区间终点、`rate` 为每秒变化率、`counterReset` 标记该区间是否发生重置）。下降即视为计数器重启：该区间以当前值从零起算并标记，不产生负速率；非正区间与负值输入跳过，不编造数据；GAUGE 查询保持原始视图且 `counterRates` 为空。变化率是平台按固定策略推导的**视图**，不是厂商上报速率；验收时不要把原始点替换成速率，也不要跨序列推断重置。

页面默认展示变化率曲线并在图上画出 reset 竖线，可切换回原始累计值；没有 `derivation` 却带 rate、带 `derivation` 却缺 rate、负 rate、rate 落在首个点、非布尔 reset 都会被拒绝。契约用例在 `tests/contracts/test_metric_counter_rates.py`，领域用例在 `tests/domain/CounterRateSmoke.java`，浏览器用例在 `apps/web-console/e2e/metric-counter-rates.spec.ts`；HTTP 集成测试产物在 `.tmp/metric-counter-http/`（可 `sh .tmp/check-counter-final.sh` 复跑校验）。

响应 Schema 和样例在 `contracts/schemas/v1/metric-series-page.schema.json`、`contracts/examples/metric-series-page.json`。窗口、跨序列总点数、请求与响应对象一致性、最新时间等关系约束还由代码校验。

HTTP 集成测试会输出 `apps/platform-api/build/test-results/metric-series-page.json`，可用 JSON Schema 校验实际响应。Playwright 常规套件使用显式 HTTP fixture，覆盖曲线、三个时间窗口、来源标记、无数据/陈旧/部分结果、失败和凭据切换；与真实存储浏览器脚本分别计验收结果。
