# 本次交付验证报告 · OpsWeave v4

最新本机追加记录见第 28 节。以下早期交付包的“未执行/未实现”按当时状态保留，不代表后续本机增量状态。

日期：2026-09-21。此报告区分实际执行、只提供源码及未具备执行条件三种状态。**未验证 Rust 编译，不声明模板可直接用于生产。**

## 1. 实际执行并通过

| 检查 | 命令 / 方法 | 结果 | 不代表什么 |
|---|---|---|---|
| 仓库结构与声明式契约 | `python scripts/check_repo.py` | 41 个 JSON/YAML 文件解析/检查，3 个只读 Tool 定义通过 | 不是全功能业务实现或安全认证 |
| Java 纯领域层 | `python scripts/check_java_domain.py` | javac 编译纯领域/接口代码，7 个 smoke 检查通过 | 不是 Gradle/Spring Boot 完整构建 |
| Python 契约测试 | `python -m pytest tests/contracts -o addopts='' -q` | 20 passed | 不等于 Rust 运行时测试 |
| Java 架构负例 | 临时在 domain 注入 `java.sql.Connection` import | 静态门禁正确退出1并报告违规；负例文件已移除 | 不是覆盖所有可能依赖的架构验证 |
| 发布前置项门禁 | `python scripts/check_release_inputs.py` | 按预期退出1，指出真实锁文件/Wrapper尚未生成 | 这是未完成初始化，不是构建通过 |
| 环境生成脚本 | 在临时目录运行两次 `init_env.py` | 首次生成随机开发凭据，二次拒绝覆盖 | 不是生产 Secret Manager；凭据未进入交付包 |
| TypeScript 语法 | 全局 TypeScript5.8.3 `transpileModule` | 2 个 TS/TSX 文件，0 个语法转换错误 | 未解析 React/Vite 类型，未进行 tsc typecheck 或 Vite build |
| 本地 Markdown 链接 | 检查仓库内 Markdown 的相对路径 | 打包前校验所有文档链接存在 | 不是外部网站永远可访问的承诺 |
| 交付完整性 | ZIP 文件清单与 SHA-256 manifest | 打包时逐文件核验 | 不替代签名、SBOM或安全扫描 |

Python用于开发契约检查，**没有部署Python Agent**。

## 2. 已编写但没有执行

`apps/agent-runtime/tests/runtime_tests.rs` 含 **25 项 Rust 测试**，涵盖：

- tenant/incident scope、权限、无身份覆盖；
- 时间窗口、历史asOf、证据availableAt、当前访问过期与模型返回后的再次检查；
- 重复/伪造证据、缺口披露、共享并发调用预算；
- Skill加载、输出JSON限制、未知动作字段拒绝；
- Axum端点的健康/就绪分离、认证、closed与demo行为。

**本交付环境没有 cargo、rustc、rustfmt、clippy。**容器无法连接外部依赖/工具链下载地址，未能补装。所以这些测试只是源码，不是通过记录；默认feature、Rig/MCP可选feature、Rust格式化和Clippy都需要初始化时执行。

## 3. 未验证或未实现

| 项目 | 原因 / 后续动作 |
|---|---|
| Cargo.lock与工具链精确锁 | 需在可信联网/内部镜像环境真实生成、编译、固定版本 |
| npm package-lock、tsc、Vite build | 未下载完整依赖；需本地npm初始化与完整构建 |
| Gradle Wrapper、依赖锁、Spring Boot启动 | 缺Gradle及完整外部依赖；不能由纯javac检查替代 |
| Docker / Compose / SQL | 无Docker运行环境；迁移、RLS、HA、备份未执行 |
| Rig实际模型调用 | 无调用凭据、未编译适配；单独开关并按提供商测试 |
| MCP server联调 | 仅probe源码，无在线MCP服务；生产授权尚未实现 |
| Java Tool Gateway / Rust真实HTTP Port | 待开发；当前严格标明fixture，不能冒充生产数据 |
| 持久AIRun / 任务恢复 / 自动化 | 只有设计与部分类型/SQL原型，不能宣称高可用或exactly-once |
| 吞吐 / 延迟 / 费用 | 没有压测数据。代码预算是本地demo上限，不是性能SLO |

## 4. 接手后必须跑

```bash
cargo generate-lockfile
cargo fmt --all
cargo test --workspace --locked
cargo check --workspace --all-targets --all-features --locked
cargo clippy --workspace --all-targets --all-features --locked -- -D warnings
pnpm install --lockfile-only
pnpm install --frozen-lockfile
pnpm build:web
# 首次用可信Gradle生成Wrapper，再完整构建Java应用。
```

依赖或SDK API出现差异时，以固定发布文档与真实编译错误为准修复adapter，再提交锁和验证结果。不要删除权限、预算、证据校验来“让demo跑通”。

完整功能状态参见 `IMPLEMENTATION-STATUS.md`。本报告只描述本次交付；后续测试结果应追加日期、命令、环境和原始输出，不覆盖成一个模糊的“全部已通过”。

## 5. 2026-09-21 本地初始化（追加）

环境：macOS aarch64；rustc 1.98.1（48a229cea 2026-09-01）；JDK Temurin 21.0.12.1+1；Gradle Wrapper 8.14.3；Node v26.9.0 / npm 11.19.1；Python 3.12。crates.io CDN 超时后，本机 Cargo 通过 USTC sparse 镜像拉取 Rig/MCP 依赖；该镜像配置在用户 `~/.cargo/config.toml`，未写入仓库。Docker daemon 当时不可用，未拉取/固定生产镜像 digest，也未启动 Compose。

| 检查 | 命令 | 结果 | 不代表什么 |
|---|---|---|---|
| 仓库结构与契约 | `python scripts/check_repo.py` | 41 个结构化文件；3 个只读 Tool 定义 | 不是业务实现或安全认证 |
| Java 纯领域 | `python scripts/check_java_domain.py` | 7 个 smoke 通过 | 不是运行中的业务 API |
| Python 契约测试 | `python -m pytest tests/contracts -q` | 20 passed | 不等于集成/E2E |
| Cargo 锁 | `cargo generate-lockfile` | 生成 `Cargo.lock`（673 packages） | 不是 SBOM 或漏洞扫描 |
| Rust 格式化 | `cargo fmt --all` 后 `cargo fmt --all -- --check` | 通过 | 不是 API 稳定性承诺 |
| Rust 默认测试 | `cargo test --workspace --locked` | 25 passed / 0 failed | 使用 fixture/Mock，不是真实 RCA |
| 全 feature 编译 | `cargo check --workspace --all-targets --all-features --locked` | Finished `dev`（含 `rig-provider`、`mcp-client`） | 未对真实模型或 MCP server 发请求 |
| Clippy | `cargo clippy --workspace --all-targets --all-features --locked -- -D warnings` | 通过 | 不是安全审计 |
| npm 锁与前端 | `npm install --package-lock-only --ignore-scripts`；`npm ci`；`npm run build:web` | lock 已生成；Vite 8.3.0 构建成功。解析版本：React 19.3.0、TypeScript 5.9.3、plugin-react 6.1.1 | 未接生产 OIDC/BFF |
| Gradle Wrapper | `gradle wrapper --gradle-version 8.14.3 --distribution-type bin` | `gradlew` / `gradle-wrapper.jar` 已生成 | 发行包来自官方 8.14.3 |
| Java 锁与 bootJar | `./gradlew :apps:platform-api:dependencies :apps:ingestion-worker:dependencies --write-locks`；`./gradlew :apps:platform-api:bootJar :apps:ingestion-worker:bootJar` | BUILD SUCCESSFUL；两个 lockfile 已写入 | 业务接口默认 deny-all；未连 PostgreSQL |
| 发布前置项 | `python scripts/check_release_inputs.py` | Bootstrap files exist | 仍需安全审查、SBOM、集成测试 |
| 本地 `.env` | `python scripts/init_env.py` | 已生成且 gitignore | 不是生产 Secret Manager |

工具链已写入 `rust-toolchain.toml` channel `1.98.1`；CI rustup 与 `deploy/docker/agent.Dockerfile` 标签改为 `rust:1.98.1-slim-bookworm`。**未记录镜像 digest。未跑 demo 进程、未调用付费模型、未联调 MCP。**

## 6. 2026-09-21 前端改为 pnpm（追加）

将根 workspace 从 npm 切换为 pnpm 10.34.3：增加 `pnpm-workspace.yaml`、`.npmrc`（`ignore-scripts=true`、`engine-strict=true`），`package.json` 写入 `packageManager`，发布门禁改为要求 `pnpm-lock.yaml`。删除 `package-lock.json`。CI / Dockerfile / Jenkinsfile 改为 `pnpm install --frozen-lockfile`。

| 检查 | 命令 | 结果 | 不代表什么 |
|---|---|---|---|
| pnpm 锁 | `pnpm install --lockfile-only` | 生成根 `pnpm-lock.yaml`（lockfileVersion 9.0；React 19.3.0、Vite 8.3.0、TypeScript 5.9.3、plugin-react 6.1.1） | 不是 SBOM |
| frozen 安装与构建 | `pnpm install --frozen-lockfile`；`pnpm typecheck:web`；`pnpm build:web` | 通过；Vite 8.3.0 生产构建成功 | 未接生产 OIDC/BFF |
| 发布前置项 | `python scripts/check_release_inputs.py` | 要求 `pnpm-lock.yaml` 与 `pnpm-workspace.yaml`；通过 | 仍需安全审查 |
| 仓库结构 | `python scripts/check_repo.py` | 43 个结构化文件 | 计数含 pnpm YAML 锁 |

## 7. 2026-09-21 Web Console 改为 Zeus + Zeus UI（追加）

将 `apps/web-console` 从 React 换为 Zeus 0.1.1-beta.2 + Zeus UI 0.1.0-beta.4 原生 Web Components（`@zeus-web/ui/button`、`@zeus-web/ui/input`）。未加入 `@zeus-web/chat` / `@zeus-web/agent-console`。Java 与 Rust 源码未改。资产/指标/Skill/Agent 页仍为未实现占位。

Vite 插件 `@zeus-js/vite-plugin` 0.0.4 使用已发布的 `@zeus-js/compiler` 0.1.0。该编译器会把 `{props.children}` 和 `array.map(...)` 编成文本绑定，且 `<For>` 运行时传入 accessor；控制台用 DOM 子节点、`<Show>`/`<For>` 和 `forItem` 适配，不把这当成 Zeus 源码仓库已修好。

| 检查 | 命令 / 方法 | 结果 | 不代表什么 |
|---|---|---|---|
| 依赖安装 | `pnpm install` | 解析 `@zeus-js/zeus` 0.1.1-beta.2、`@zeus-web/ui` 0.1.0-beta.4、`@zeus-js/vite-plugin` 0.0.4、Vite 8.3.0、TypeScript 5.9.3 | lock 仍记录 Zeus UI 包的可选 React peer；不是把 React 当控制台运行时 |
| typecheck / 生产构建 | `pnpm typecheck:web`；`pnpm build:web` | 通过。产物约 35 kB JS / gzip 13.6 kB | 未做包体积基线或性能对比 |
| 仓库结构 / 发布门禁 | `python scripts/check_repo.py`；`python scripts/check_release_inputs.py` | 43 个结构化文件；Bootstrap files exist | 不是安全审查 |
| 本机诊断 UI | `bash scripts/demo.sh`（127.0.0.1:8090）+ `pnpm dev:web`（127.0.0.1:5173）；浏览器打开诊断页，内存中填写开发 Token 后点「运行只读诊断」 | 出现合成摘要、证据引用、缺失数据与限制；hash 切到资产/Agent 占位页 | 不是 OIDC、真实 Zabbix，也不是 chat/agent-console 联调 |

**未接入、未宣称：** `@zeus-web/chat`、`@zeus-web/agent-console`、data-grid、生产 BFF。未测量长列表性能。该节对应的源码已提交为 `e5b5827`。

## 8. 2026-09-21 路线图与兼容性清单（追加）

将规划写入 `docs/ROADMAP.md`，将已验证前端组合写入 `docs/FRONTEND-COMPATIBILITY.md`。当时 M0 清单未勾选；第 9 节之后更新了本地验收项。

| 检查 | 命令 | 结果 | 不代表什么 |
|---|---|---|---|
| frozen 安装与构建 | `pnpm install --frozen-lockfile`；`pnpm typecheck:web`；`pnpm build:web` | 工作树已有 `node_modules` 时通过；Vite 8.3.0，`index-nEEgEZ7B.js` 35.22 kB | 不是干净 clone；不是 CI Node 22 job |
| 产品源码 React | 搜索 `apps/web-console/src` 与直接依赖 | 无 `react` / `react-dom` import | lock 仍含 Zeus UI 可选 React peer |

## 9. 2026-09-21 M0 本地安装与诊断页 Playwright（追加）

环境：macOS aarch64；Node v26.9.0；pnpm 10.34.3；Playwright 1.55.1 Chromium 140.0.7339.186（build v1193）。`ignore-scripts` 下浏览器需单独 `playwright install chromium`。本机 `http_proxy` 会使 Playwright 的 URL 探活误判，webServer 改为检查 TCP `4173`。

`@zeus-web/ui` 的 JS 入口未列入 package `sideEffects`，生产包原先不含 `customElements.define`。控制台改为导入 `@zeus-web/button/wc/auto` 与 `@zeus-web/input/wc/auto`，CSS 仍来自 `@zeus-web/ui`。相邻 JSX 文本插值改为单个模板字符串。

| 检查 | 命令 / 方法 | 结果 | 不代表什么 |
|---|---|---|---|
| 无 node_modules 拷贝 | `rsync` 排除 `node_modules`/`dist`/`target` 后 `pnpm install --frozen-lockfile`；`pnpm typecheck:web`；`pnpm build:web` | 通过。当时产物 `index-CUkaLau-.js` 35.32 kB（尚未含 WC 注册） | 不是 `git clone`；不是 CI runner |
| 含 WC 注册的生产构建 | `pnpm build:web` | Vite 8.3.0；`index-DbhRecI-.js` 58.72 kB / gzip 20.55 kB；`zw-button`/`zw-input` 懒加载 chunk；CSS 7.61 kB | 不是包体积基线 |
| 诊断页 E2E | `pnpm test:web`（`page.route` 拦截 `POST /agent/api/v1/diagnoses`） | **7 passed**（注册 WC、token 长度、提交与证据文本、401、503、取消、卸载后不写回） | 不需要本机 Rust Demo；不是 OIDC；未测 IME |
| 仓库结构 / 发布门禁 | `python3 scripts/check_repo.py`；`python3 scripts/check_java_domain.py`；`python3 -m pytest tests/contracts -q`；`python3 scripts/check_release_inputs.py` | 44 个结构化文件；7 项 Java smoke；20 passed；Bootstrap files exist | 不是安全审查；本节未复跑 Rust / Gradle |

**未接入、未宣称：** chat/agent-console 已接入、生产 BFF。未把 Playwright 的 JS unzip 卡死当作上游缺陷（本机用 `ditto` 解开已下载的 zip）。GitHub Actions 当时尚未作为本节证据。

## 10. 2026-09-21 GitHub Actions（`b5404a8`）

查阅命令：`gh run list --branch main`；`gh run view 35600475743`。

Run：https://github.com/baicie/ops-weave/actions/runs/35600475743  
Head：`b5404a817e832cfa13c9b52a005477a453ef031d`。conclusion = **success**。

| Job | 结果 |
|---|---|
| contracts | success |
| rust | success |
| java | success |
| web | success |

web job 含 Node 22.18、`pnpm install --frozen-lockfile`、typecheck、生产构建、Playwright Chromium 与 `pnpm test:web`。这证明 ubuntu checkout 可按锁文件安装并跑通诊断页 E2E，不等于生产 OIDC、真实 Zabbix 或 chat/agent-console 已接入。

## 11. 2026-09-22 M1 身份与 Zabbix Host 切片（追加）

环境：macOS aarch64；JDK Homebrew OpenJDK 21.0.12.1（Gradle daemon）；`javac` 默认仍可能是本机 Temurin 25，领域检查使用 `--release 21`。未对厂商 Zabbix 或 Keycloak 发请求。

| 检查 | 命令 | 结果 | 不代表什么 |
|---|---|---|---|
| 仓库结构与契约 | `python3 scripts/check_repo.py` | 47 个结构化文件；3 个只读 Tool 定义 | 不是生产认证 |
| Java 领域 smoke | `python3 scripts/check_java_domain.py` | `DomainSmoke` 7；`IdentityAuthorizationSmoke` 11；`ZabbixHostMappingSmoke` 12 | 不是可达 Zabbix |
| Python 契约 | `python3 -m pytest tests/contracts -q` | 22 passed | 不是 E2E 接入 |
| Gradle | `JAVA_HOME=<jdk21> ./gradlew :apps:platform-api:test :apps:platform-api:bootJar :apps:ingestion-worker:bootJar` | **10 tests, 0 failures**；两个 bootJar 成功 | 未跑 Rust / pnpm / Playwright；内存库存不是 PostgreSQL |
| 身份行为 | 上述 Gradle 测试 | 无 token → 401；query `tenantId` → 400；缺 `entity.read` / `source.sync` → 403；OIDC 模式拒绝启动 | 不是 Keycloak |
| Host 链 | 上述 Gradle 测试 + 领域 smoke | `labeled-fixture` 写入 2 个 host Entity；JSON-RPC 客户端打本机协议桩 | 不是厂商 `host.get` |

本节记录 `bb9a7e2`。PostgreSQL、分页对账和资产页见第 12 节。当时未宣称生产 OIDC、Item/Trigger 或 Integration Copilot。

## 12. 2026-09-22 Host 分页同步与 PostgreSQL（追加）

环境：macOS aarch64；Gradle 使用 Homebrew OpenJDK 21.0.12.1。本机 5432 上已有 PostgreSQL，角色 `opsweave_dev` 不存在，因此 JDBC 测试使用当前系统角色新建的库 `opsweave_host_sync`。未对厂商 Zabbix 发请求。

| 检查 | 命令 | 结果 | 不代表什么 |
|---|---|---|---|
| Java 领域 smoke | `python3 scripts/check_java_domain.py` | 7 + 11 + 20 项通过。含两页 fixture、完整快照才 retire、第二页失败不 retire | 不是厂商 Zabbix |
| 契约 | `python3 scripts/check_repo.py`；`python3 -m pytest tests/contracts -q` | 47 个结构化文件；22 passed | 不是安全审查 |
| Gradle | `JAVA_HOME=<jdk21> ./gradlew :apps:platform-api:test --offline`，环境变量指向本机 `opsweave_host_sync` | 11 tests，0 failures，0 skipped。其中 `PostgresHostSyncIT` 实际连上 PostgreSQL | 这次没有重跑 bootJar。更早一次未设 JDBC 时，该测试被跳过，两个 bootJar 已成功 |
| PostgreSQL | 同上测试类 `PostgresHostSyncIT`，`OPSWEAVE_TEST_JDBC_URL` 指向本机 `opsweave_host_sync` | 1 test 通过：两页写入、缺席 Host 变为 INACTIVE、后续失败扫描不把已同步 Host 标失活 | 不是 Compose 里的 `opsweave_dev`，也不是厂商实例 |
| 资产页 | `pnpm --filter @opsweave/web-console test:e2e` | 8 passed。库存用例：同步后显示 Host 字段，503 后表格仍在，请求不带 tenant。诊断页卸载后进入资产页能看到标题「资产」 | 浏览器请求被 Playwright 拦截，不是连着真实 platform-api |

CI java job 增加了 `postgres:17` 服务，并设置 `OPSWEAVE_TEST_JDBC_URL`。本轮还没有新的 GitHub Actions 结果。

## 13. 2026-09-22 同步错误码与 host.get 游标（追加）

环境：macOS aarch64；Gradle 使用 Homebrew OpenJDK 21.0.12.1。`OPSWEAVE_ZABBIX_URL` 未设置。未对厂商 Zabbix 发请求。

| 检查 | 命令 | 结果 | 不代表什么 |
|---|---|---|---|
| Java 领域 smoke | `python3 scripts/check_java_domain.py` | 7 + 11 + 32 + 15。含空快照才 retire、写库失败 / 游标不前进 / Raw 失败不 retire，以及 `host.get` 的 hostid 排序与 offset | 不是厂商 Zabbix，也不是 1000 台 Host |
| Gradle | `JAVA_HOME=<jdk21> ./gradlew :apps:platform-api:test --offline` | 11 tests，0 failures，0 skipped。协议桩要求请求含 `sortfield=hostid` 与 `offset=0`。`PostgresHostSyncIT` 仍连本机 `opsweave_host_sync`，失败码为 `SOURCE_FETCH_FAILED` | 不是厂商实例 |
| 资产页 | `pnpm --filter @opsweave/web-console test:e2e` | 8 passed。第二次同步 503 显示 `SOURCE_FETCH_FAILED`，已有行仍在 | 请求被 Playwright 拦截 |

## 14. 2026-09-22 Host presence 与 MetricDefinition（追加）

环境：macOS aarch64；Gradle 使用 Homebrew OpenJDK 21。`OPSWEAVE_ZABBIX_URL` 未设置。未对厂商 Zabbix 发请求。没有把采样点写入 PostgreSQL。

| 检查 | 命令 | 结果 | 不代表什么 |
|---|---|---|---|
| Java 领域 smoke | `python3 scripts/check_java_domain.py` | 7 + 11 + 46 + 15 + 30。含映射拒绝仍保留 presence、offset 漂移会漏掉中途变化的 Host、失败响应保留页数，以及 `system.cpu.util[,user]` → `host.cpu.usage.user` | 不是厂商 Zabbix，也不是 1000 台 Host |
| Gradle | `JAVA_HOME=<jdk21> ./gradlew :apps:platform-api:test --offline` | 13 tests，0 failures，2 skipped。跳过的是两个 PostgreSQL 测试。`ZabbixItemSyncIT` 通过 | 该次没有 JDBC |
| PostgreSQL | 同上，仅 `PostgresHostSyncIT` 与 `PostgresItemSyncIT`，JDBC 指向本机 `opsweave_host_sync` | 2 tests，0 skipped，0 failures。Item 定义写入，缺席 item 变为 INACTIVE | 不是厂商实例 |
| 资产页与结构 | `pnpm --filter @opsweave/web-console test:e2e`；`python3 scripts/check_repo.py` | Playwright 8 passed，失败提示含已扫描页数。47 个结构化文件 | 浏览器请求被拦截 |

## 15. 2026-09-22 指标目录与来源绑定（追加）

环境：macOS aarch64；Gradle 使用 Homebrew OpenJDK 21。`OPSWEAVE_ZABBIX_URL` 未设置。未对厂商 Zabbix 发请求。没有把采样点写入 PostgreSQL。本轮没有改页面，没有重跑 Playwright。

| 检查 | 命令 | 结果 | 不代表什么 |
|---|---|---|---|
| Java 领域 smoke | `python3 scripts/check_java_domain.py` | 7 + 11 + 46 + 15 + 41。两台主机共用一条目录、映射来自 YAML、映射器源码不含该 item key 与 metric key、缺席 item 只失活绑定 | 不是厂商 Zabbix |
| Gradle | `JAVA_HOME=<jdk21> ./gradlew :apps:platform-api:test --offline` | 13 tests，0 failures，0 skipped。环境里已有 `OPSWEAVE_TEST_JDBC_URL=jdbc:postgresql://127.0.0.1:5432/opsweave_host_sync`，因此 `PostgresHostSyncIT` 与 `PostgresItemSyncIT` 都实际执行 | 不是厂商实例，也不是一次没有 JDBC 的运行 |
| 结构 | `python3 scripts/check_repo.py` | 47 个结构化文件，3 个只读工具定义 | 未跑 Web |

## 16. 2026-09-22 有界 History 读取（追加）

环境：macOS aarch64；Gradle 使用 Homebrew OpenJDK 21.0.12.1；领域检查 `javac --release 21`；rustc 1.98.1；Node v26.9.0；pnpm 10.34.3；Python 3.14.6。`OPSWEAVE_ZABBIX_URL` 和 `OPSWEAVE_TEST_JDBC_URL` 均未配置。本次修改在本地分支 `codex/bounded-history-read`，未推送或部署。

实现：`GET /api/v1/integrations/zabbix/items/{itemId}/history`；活动绑定、来源/实体/指标授权、完整秒窗口、纳秒游标、十进制字符串、配置换算及 min/max 校验。来源实际 value_type 来自逐批 `item.get`，不用目录 DOUBLE 推断 history=0。响应明确 `persistence=not-persisted`。详见 ADR-016。

| 检查 | 命令 / 方法 | 结果 | 不代表什么 |
|---|---|---|---|
| 仓库结构与契约 | `python3 scripts/check_repo.py`；`python3 -m pytest tests/contracts -q` | 50 个结构化文件，3 个只读工具定义；30 项契约测试通过 | 不是厂商实例验收 |
| Java 纯领域 | `python3 scripts/check_java_domain.py` | 7 + 13 + 11 + 16 + 46 + 15 + 41 = **149 项 smoke 检查通过**；含同秒续读、截断边界、密集秒拒绝、时间范围、uint64 精度、映射 min/max | 不等于已采集落库 |
| Java 测试与启动包 | `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew :apps:platform-api:test :apps:platform-api:bootJar :apps:ingestion-worker:bootJar --offline` | **23 tests，0 failures，0 errors，2 skipped**；两个 bootJar 成功。新增 History 相关 10 tests 均执行 | 两个 PostgreSQL IT 因无 JDBC 配置跳过；本轮未验证数据库连接 |
| 实际 HTTP 响应契约 | `ZabbixHistoryIT` 写出合成响应到 `apps/platform-api/build/test-results/history-page.json`，Python `Draft202012Validator` + `FormatChecker` 对照 `contracts/schemas/v1/metric-history-page.schema.json` 校验 | 通过；构建目录产物不提交 | 不是手写样例替代实际响应；仍为显式 fixture |
| JSON-RPC 协议与边界 | 上述 Gradle 中 `ZabbixHistoryReaderIT`、`HistoryAuthorizationTest`、`ZabbixHistoryIT` | 本机 HttpServer 验证 item.get/history.get、0/3 类型、无 offset、纳秒排序、单位换算；非法值/单位范围/对象/次序/超大响应/上游失败拒绝；租户/对象/权限隔离、4 并发预算与无身份覆盖通过 | 不是厂商 Zabbix，也不证明真实数据完整性 |
| Rust 默认 | `cargo test --workspace --locked` | **25 passed，0 failed** | fixture/Mock，无真实模型调用 |
| Rust 全 feature | `cargo test --workspace --all-features --locked` | **25 passed，0 failed**；Rig/MCP 适配已编译 | 无真实模型/MCP 服务调用 |
| Rust 格式与静态检查 | `cargo fmt --all -- --check`；`cargo clippy --workspace --all-targets --all-features --locked -- -D warnings` | 通过；Clippy 曾等待另一项目的 Cargo 缓存锁，随后正常完成 | 不是安全审计 |
| TypeScript / Web | `pnpm typecheck:web`；`pnpm build:web`；`pnpm test:web` | 类型检查、Vite 构建通过；**8 Playwright passed** | 页面未增加指标曲线；浏览器请求仍被测试拦截 |
| 发布文件门禁 | `python3 scripts/check_release_inputs.py`；`git diff --check` | 通过 | 不是生产发布验收 |

最终运行输出摘录（测试总数从 Gradle XML 汇总，包含跳过项）：

```text
HistoryPageSmoke: 13 checks passed
MetricPointSmoke: 16 checks passed
BUILD SUCCESSFUL in 1m 46s
{'tests': 23, 'failures': 0, 'errors': 0, 'skipped': 2}
Actual HTTP fixture response conforms to metric-history-page v1
test result: ok. 25 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out
8 passed (22.2s)
```

当前基线进度另外实查：`gh run list --limit 5 --json databaseId,workflowName,headSha,status,conclusion,url` 与 `gh run view 35680270637 --json jobs,headSha,conclusion`。提交 `f0bcc32e7e81c2a72cb41bd98f19a285ba9f1e2f` 的 [GitHub Actions](https://github.com/baicie/ops-weave/actions/runs/35680270637) 中 contracts/web/rust/java/deploy 五个 job 均 success。**这是上一提交的 CI/部署记录，不是本次 History 修改的 CI 结果；未独立检查远端服务运行态。**

尚未验证/实现：厂商 `history.get`、VictoriaMetrics 写入/查询、毫秒精度冲突策略、持久采集 checkpoint、迟到点重叠/幂等、Worker 定时采集、生产 OIDC。History 游标不代表持久写入完成；空结果/扫描完成不代表以后不会有迟到数据。

## 17. 2026-09-22 Worker → VictoriaMetrics → checkpoint（追加）

先将第 16 节提交 `3651112` 推送到 `origin/codex/bounded-history-read`。[该提交的 CI](https://github.com/baicie/ops-weave/actions/runs/35693183287) 实查 conclusion=success。随后在同一分支实现默认关闭的单流采集、批写前/后回读、毫秒冲突与 float64 精度校验、PostgreSQL 事务锁及 checkpoint。

环境：macOS aarch64；Homebrew OpenJDK 21.0.12.1；PostgreSQL 17.10（本机系统角色，新建隔离测试库 `opsweave_history_test`）；VictoriaMetrics v1.152.0 官方 darwin-arm64 二进制，监听 `127.0.0.1:18428`，1ms 去重、非流式 Influx。Docker daemon 不可用，实际存储联调用的是原生二进制。VM 压缩包和二进制分别对照官方 checksums 校验，SHA-256 为 `2867ec3ce6f190be6c391a77d116a0bcde6a06a304799b3d248dbf97bac1f5fd`、`e82c9346d97420c2dd5d331052495531ed574c1689086595e0cf29b2ae349710`。临时二进制/数据位于 gitignore 的 `.tmp`，不提交。

| 检查 | 命令 / 方法 | 最终结果 | 不代表什么 |
|---|---|---|---|
| 仓库/契约 | `python3 scripts/check_repo.py`；`python3 -m pytest tests/contracts -q` | 50 个结构化文件、3 个只读 Tool；30 项契约测试通过。结构检查排除 `.tmp` 运行时缓存 | 未增加任何 Agent 写入 Tool |
| 纯领域 | `python3 scripts/check_java_domain.py` | **179 项 smoke 检查通过**。含重叠窗口、先写后推进、失败保留游标、目标变更拒绝、同毫秒冲突、数值精度与未声明适配身份拒绝 | 不代表生产吞吐 |
| Java 全套与启动包 | 配置 `OPSWEAVE_TEST_JDBC_URL/USER`、`OPSWEAVE_TEST_VM_URL`，`JAVA_HOME=<jdk21> ./gradlew :apps:platform-api:test :apps:ingestion-worker:test :apps:platform-api:bootJar :apps:ingestion-worker:bootJar --offline` | **Platform 23 + Worker 12 = 35 tests，0 failures，0 errors，0 skipped**；两个 bootJar 通过 | 来源是 fixture/协议桩，未连接厂商 Zabbix |
| PostgreSQL | 上述测试的 Host/Item IT 与 `PostgresHistoryCheckpointIT` | 实际连本机测试库；checkpoint 回滚、重建 Store 后恢复、tenant/source/item/stream 键隔离、并发锁通过 | 不是租约/fencing 或 HA 验收 |
| VictoriaMetrics | `VictoriaHistoryIngestionIT` 实连 v1.152.0 | 迟到点补入、重复轮询、确认后中断/重放、已有值冲突、保留期外点不可视作已确认写入通过 | 查询可见不是跨库事务或断电持久化证明 |
| 实际 Java 进程链 | 设置上述测试存储变量与 `JAVA_HOME`，`python3 scripts/check_history_stack.py` | **PASS**：临时 Token、真实 platform-api、定时 ingestion-worker、真实 VM 与 PG；fixture 的 `1789992001` / `0.40` 点可查询且 checkpoint 提交。检查后关闭两个 Java 进程 | Python 仅开发检查，不是执行后端；仍是合成来源 |
| Rust 默认/全 feature | `cargo test --workspace --locked`；`cargo test --workspace --all-features --locked` | **25 + 25 passed** | 未调用真实模型/MCP |
| Rust 格式/Clippy | `cargo fmt --all -- --check`；`cargo clippy --workspace --all-targets --all-features --locked -- -D warnings` | 通过 | 不是安全审计 |
| Web | `pnpm typecheck:web`；`pnpm build:web`；`pnpm test:web` | 类型/构建通过；**8 Playwright passed** | 本轮未增加指标页面 |
| 依赖与交付配置 | `./gradlew :apps:ingestion-worker:dependencies --write-locks --offline`；`docker manifest inspect victoriametrics/victoria-metrics:v1.152.0`；`docker compose ... --profile metrics config --quiet`；`python3 scripts/check_release_inputs.py`；`git diff --check` | Worker JDBC 锁由 Gradle 生成；镜像 manifest 可取得、Compose 配置及文件门禁通过 | 未运行本机 Docker Compose、未固定生产镜像 digest |

回归过程曾因真实 VM 新序列可见延迟超出初始确认预算而失败，checkpoint 未提交。独立探测观察到完整标签查询约 10 秒才可见，因此最终采用最多 11 次、间隔 2 秒的回读预算；未确认仍返回失败。表中 Java 总数来自最后一次完整成功运行的 XML，不使用早先失败运行作通过证据。

最终输出摘录：

```text
HistoryIngestionPolicySmoke: 14 checks passed
HistoryIngestionSmoke: 16 checks passed
BUILD SUCCESSFUL in 27s
platform-api {'tests': 23, 'failures': 0, 'errors': 0, 'skipped': 0}
ingestion-worker {'tests': 12, 'failures': 0, 'errors': 0, 'skipped': 0}
Java platform -> scheduled Java worker -> real VictoriaMetrics + PostgreSQL: PASS (labeled fixture source)
8 passed (8.8s)
```

CI java job 已加入 Worker tests 和独立 VictoriaMetrics 容器；本节本地结果不替代随后推送的 CI 状态。远端部署配置仍默认关闭采集。本次不声明厂商接入、生产鉴权、自动回补所有迟到点、分布式采集或物理 exactly-once。下一步是有资源授权与点数限制的时序查询 API、指标页与真实来源验收。

## 18. 2026-09-24 checkpoint 租约与序列互斥（追加）

把采集锁从「一个 PostgreSQL 事务包住平台读取和 VictoriaMetrics 回读」改成两段短事务：先提交 fencing token 与 300 秒租约，网络工作结束后再用 `revision + fencing_token` 条件更新。主键改为 tenant/source/item。`stream_name` 只保留为任务名；同一 item 的另一个任务名返回 `CONFIGURATION_INVALID`。

运行前清空了本机测试库 `opsweave_history_test` 里的旧 checkpoint 行。那些行来自旧主键（含 stream_name）的重复测试数据，V002 遇到同一 item 的多行会拒绝迁移。

| 检查 | 命令 / 方法 | 最终结果 | 不代表什么 |
|---|---|---|---|
| 纯领域 | `python3 scripts/check_java_domain.py` | Java domain smoke 7 项、HistoryIngestionPolicySmoke 14、HistoryIngestionSmoke 16、HistoryPageSmoke 13、Identity 11、MetricPointSmoke 16、Zabbix host mapping 46、host page 15、item mapping 41，均通过 | 不覆盖 PostgreSQL 租约 |
| Worker 测试 | `OPSWEAVE_TEST_JDBC_URL=jdbc:postgresql://127.0.0.1:5432/opsweave_history_test`、`OPSWEAVE_TEST_JDBC_USER=liuzhiwei`，`JAVA_HOME=<jdk21> ./gradlew :apps:ingestion-worker:test --offline` | 15 tests：12 通过，0 失败；`VictoriaHistoryIngestionIT` 3 项因未设置 `OPSWEAVE_TEST_VM_URL` 跳过 | 未重跑平台测试、VictoriaMetrics、`check_history_stack.py`、Rust 或 Web |
| PostgreSQL | `PostgresHistoryCheckpointIT` 6 项 | 失败不推进、重建后恢复、tenant/source/item 隔离、第二 streamName 拒绝、失败释放租约、租约占用返回 `CHECKPOINT_BUSY`、替换 fencing token 后不能提交 | 不是多绑定调度或 HA 验收 |

远端 Compose 仍未启用 History。本次不声明查询 API、指标页或生产部署已经完成。

## 19. 2026-09-24 lease 后的存储回归与查询适配器（追加）

使用本机 PostgreSQL 库 `opsweave_history_test`（系统角色 `liuzhiwei`）和已有的 VictoriaMetrics v1.152.0 二进制，监听 `127.0.0.1:18428`，参数为 `-dedup.minScrapeInterval=1ms -influx.forceStreamMode=false -retentionPeriod=1y`。数据目录在 gitignore 的 `.tmp`，不提交。

| 检查 | 命令 / 方法 | 最终结果 | 不代表什么 |
|---|---|---|---|
| Worker 全量 | `OPSWEAVE_TEST_JDBC_URL=jdbc:postgresql://127.0.0.1:5432/opsweave_history_test`、`OPSWEAVE_TEST_JDBC_USER=liuzhiwei`、`OPSWEAVE_TEST_VM_URL=http://127.0.0.1:18428`，`JAVA_HOME=<jdk21> ./gradlew :apps:ingestion-worker:test --offline --no-build-cache --rerun-tasks` | 15 tests，0 failures，0 errors，0 skipped。含 checkpoint 6 项与 `VictoriaHistoryIngestionIT` 3 项 | 未重跑平台全套、Rust、Web 或 `check_history_stack.py` |
| 查询适配器 | 同一 VM，`./gradlew :apps:platform-api:test --tests com.acme.opsweave.platform.telemetry.VictoriaMetricsQueryAdapterIT --offline --no-build-cache --rerun-tasks` | 1 test 通过：两个来源保持两条 series，空指标为 `NO_DATA`，端口不可达为 `SOURCE_UNAVAILABLE` | 不是查询 HTTP API，也没有权限用例或指标页 |
| 纯领域 | `python3 scripts/check_java_domain.py` | 原有 smoke 通过，另加 `MetricSeriesQuerySmoke` 6 项 | 不覆盖真实 VM |

尚未提供 `QueryMetricSeriesUseCase`、`GET /api/v1/entities/{entityId}/metrics/{metricKey}/series` 和 Zeus MetricsPage。查询适配器只生成固定 export 选择器，不接受 PromQL/MetricsQL。

## 20. 2026-09-24 指标查询授权、HTTP 与指标页（追加）

VictoriaMetrics 导出缺少 `source_instance_id`、`data_mode`、`external_item_id`、`unit` 或 `mapping_revision` 时返回 `INVALID_RESPONSE`，不再抛出空指针。响应体用有界订阅者在超过 2MB 时取消读取。`QueryMetricSeriesUseCase` 只检查 `entity.read` 和 `metric.read`，再确认实体与指标目录存在。未配置 `OPSWEAVE_VICTORIAMETRICS_URL` 时查询端口是关闭实现。指标页提供资产、指标和 Last 15m/30m/1h；`partial` 时显示数据不完整。曲线绘制放在可替换 SVG 适配器中，没有引入图表库。

| 检查 | 命令 / 方法 | 最终结果 | 不代表什么 |
|---|---|---|---|
| 适配器负例 | `JAVA_HOME=<jdk21> ./gradlew :apps:platform-api:test --tests com.acme.opsweave.platform.telemetry.VictoriaMetricsQueryContractTest --offline --no-build-cache --rerun-tasks` | 2 tests，0 failures。缺身份标签与超过 2MB 的导出都是 `INVALID_RESPONSE`，大响应在写完前被取消 | 不是厂商 Zabbix，也没有重跑真实 VM 双来源 IT |
| 纯领域 | `python3 scripts/check_java_domain.py` | 原有 smoke 通过，`QueryMetricSeriesSmoke` 6 项通过：授权、缺实体、缺指标、关闭存储、未来窗口 | 不覆盖 Spring MVC |
| 契约结构 | `python3 scripts/check_repo.py` | 50 个结构化文件通过 | 不是运行中的 HTTP 调用 |
| Web 类型 | `pnpm --dir apps/web-console typecheck` | `tsc --noEmit` 通过 | 未跑 Playwright，未在浏览器里对真实平台完成一次查询 |

没有重跑 Worker 的 PostgreSQL / VictoriaMetrics 全量，也没有把指标页接到生产图表库。

## 21. 2026-09-25 指标查询完整回归与真实存储浏览器验收（追加）

在 `main` 的 `febfc53` 基线上直接修改工作区，没有新建分支。本节记录本机运行结果，不表示已经推送、通过新的 GitHub Actions 或部署。

修复：`application.yml` 的 `zabbix` 缩进曾使 `secretRef` 无法绑定、平台启动失败；指标 HTTP 非数字参数原先经过错误分派返回 401，改为明确 400；存储值数组/负时间校验和返回对象/单位检查；扫描耗尽且窗口内无点时保留 `PARTIAL`，不返回已确认的 `NO_DATA`。前端修复 Zeus 编译器未渲染块体 `For` 回调的问题，统一使用现有表达式组件写法；SVG 使用正确命名空间、单点有可见标记。Token 或筛选变化会清理旧结果并取消请求，每条来源独立展示最新值与 `dataMode`。

新增 `metric-series-page` Schema/样例、HTTP 与浏览器测试，以及 `scripts/check_metrics_stack.mjs`。脚本用随机开发 Token 启动真实 Java 进程和生产前端预览；浏览器业务请求不拦截，来源仍为明确标注的 fixture。复现步骤见 [指标查询验收](runbooks/metric-query-acceptance.md)。

环境：Windows x64；Temurin Java 21.0.11；Gradle Wrapper 8.14.3；Node 24.16.0；pnpm 10.34.3；Rust 1.98.1；Playwright 1.55.1 / Chromium 140（build 1193）。隔离 Docker PostgreSQL `postgres:17`、VictoriaMetrics `v1.152.0`，随机端口仅发布至 `127.0.0.1`，显式配置 JDBC/VM 测试环境变量。未读取或使用厂商 Zabbix 凭据。

| 检查 | 实际命令 / 方法 | 结果 | 边界 |
|---|---|---|---|
| 依赖安装 | `pnpm install --frozen-lockfile` | 通过，锁文件未修改 | 未升级依赖 |
| 仓库 / 契约 | 临时 `python:3.12-slim` 开发容器中安装 `requirements-dev.txt`，运行 `python scripts/check_repo.py`、`python -m pytest tests/contracts -q` | **52 个结构化文件、3 个只读 Tool；42 项通过** | Python 仅为开发检查 |
| 纯 Java 领域 | 本机 Node 临时 runner 按 `check_java_domain.py` 相同源码集合调用 `javac --release 21 -encoding UTF-8`，逐一执行 11 个 smoke main | **195 项检查通过** | 本次未将该结果冒充 Python 脚本执行；新增查询范围与空部分结果负例 |
| Java 全量与启动包 | `gradlew.bat :apps:platform-api:test :apps:ingestion-worker:test :apps:platform-api:bootJar :apps:ingestion-worker:bootJar --console=plain --rerun-tasks` | **Platform 36 + Worker 15 = 51 tests；0 failures、0 errors、0 skipped；两个 bootJar 成功** | 真实连接本机隔离 PG/VM；HTTP 存储协议负例使用标注的协议桩 |
| HTTP 响应契约 | `MetricSeriesHttpIT` 输出的 `build/test-results/metric-series-page.json` 用 `Draft202012Validator` + `FormatChecker` 对照新 Schema 校验 | 通过 | 实际 Java HTTP 响应；数据仍为 fixture |
| Rust 默认 | `cargo test --workspace --locked` | **25 passed** | 无真实模型 / MCP 调用 |
| Rust 全 feature | `cargo test --workspace --all-features --locked -j 2` | **25 passed** | 第一次默认并发编译出现标准库 metadata 错误；最终此命令完整成功，未修改工具链版本或锁 |
| Rust 格式 | `cargo fmt --all -- --check` | 通过 | 本轮未另跑 Clippy |
| Web 类型 / 构建 | `pnpm typecheck:web`；修复后 `pnpm build:web`（含 `tsc --noEmit`） | 通过，Vite 8.3.0 | 使用固定 Zeus 依赖，无新图表库 |
| Web 浏览器回归 | `node apps/web-console/node_modules/@playwright/test/cli.js test --config .tmp/playwright-local.config.ts --max-failures=1` | **18 passed**，含新增 10 项指标用例 | 临时配置只指定本地解压的同版 Chromium、测试目录与 cwd，继承仓库配置；业务 HTTP 被测试拦截 |
| 真实存储浏览器链 | 配置 loopback 测试环境变量及本地 Chromium 路径，`node scripts/check_metrics_stack.mjs` | **PASS**：Chromium → 生产构建预览代理 → Java 授权 HTTP → PostgreSQL 元数据 / VictoriaMetrics 采样点；已查看截图 | 查询全链路，无请求拦截；合成 Zabbix 来源，采样点由开发验收脚本插入；不替代厂商或 Worker 采集验收 |
| 发布文件门禁 | `python scripts/check_release_inputs.py` | 通过 | 不是生产发布批准 |

本机 Python 包安装和 Playwright 的 JS 解压曾停滞，最终使用一次性 Python 开发容器检查契约，用 Windows `tar` 解压 Playwright 已下载的同版官方 Chromium ZIP。早先环境失败及修复前的用例失败均未计为通过。截图保存在 gitignore 的 `.tmp/metrics-acceptance/metrics.png`。

仍未验收：厂商 Zabbix、生产 OIDC、PipelineVersion/Preview/Replay、告警/Incident 到真实 AI 的闭环、生产容量/HA/备份恢复。M2/M3 不能因此宣告全部退出；本节关闭的是受控指标查询及页面的本机验收缺口。

## 22. 2026-09-25 Host 版本发布、预览与只读重放（追加）

继续直接在 `main` 工作区开发，保留第 21 节未提交修改，没有创建分支或推送。新增固定 Host 映射表单和 API：PipelineVersion 发布内容/digest 不可覆盖，同步运行在抓取前固定版本；无 body 仍选内置 revision 1，不随最新版本变化。历史 Raw 按 tenant/source/run 读取，Preview 接收未发布定义，Replay 要求已发布版本及 digest，默认且仅支持 dry-run。旧运行缺少版本绑定时不捏造血缘。同步与 Observation 记录具体版本，失败不对账。

修复旧定义校验允许错误节点顺序的问题，并让缺失 hostid 时的 `failFast` 生效；内置流水线改为明确的 `skipRecord`，维持先前跳过拒绝记录的行为。只允许固定六节点和 name/host 显示名选择，不执行脚本。预览可展示旧映射拒绝、新映射修复的结果，保留缺失/截断/超大 Raw 与失败批次标记。服务没有库存写入、来源抓取、通知或动作端口。表单、Token 或页面变化会取消/作废旧请求；发布或同步超时不宣称服务端回滚。

环境延续第 21 节的 Windows/JDK/Gradle/Node/pnpm/Rust/Chromium。另启隔离 `postgres:17` 与 VictoriaMetrics `v1.152.0`，端口只发布到 loopback，随机临时数据库密码不进入报告。自动应用平台迁移 `V007__pipeline_version.sql`。操作和摘要规范见 [Host 流水线](runbooks/host-pipeline.md)。

| 检查 | 实际命令 / 方法 | 最终结果 | 边界 |
|---|---|---|---|
| 仓库与契约 | 临时 `python:3.12-slim` 中 `python scripts/check_repo.py`；`python -m pytest tests/contracts -o addopts= -q` | **62 个结构化文件、3 个只读 Tool；63 passed** | 新增发布/预览/重放/结果 Schema、样例，摘要规范交叉校验与身份/预算/脚本负例；Python 仅为开发检查 |
| 纯 Java 领域 | 本机临时 Node runner 以 `javac --release 21 -encoding UTF-8` 编译 modules 和 tests/domain，执行全部 12 个 smoke main | **232 项检查通过**，新增 PipelineVersionSmoke 37 项 | 同 `check_java_domain.py` 源码集合；实际使用 Node runner，不冒称运行了 Python 脚本 |
| Java 全量与启动包 | `gradlew.bat :apps:platform-api:test :apps:ingestion-worker:test :apps:platform-api:bootJar :apps:ingestion-worker:bootJar --console=plain --rerun-tasks` | **Platform 41 + Worker 15 = 56 tests，0 failures/errors/skipped；两个 bootJar 成功** | 实际连接隔离 PG/VM；包括发布并发冲突、存储适配器重建后读取 pin/Raw、保留缺口、超大 Raw；不冒称 OS 进程重启或 HA 验收 |
| 实际 HTTP 契约 | `PipelineHttpIT` 保存的 fixture 发布/重放 JSON 用 Draft202012Validator + FormatChecker 对照 contracts 校验 | **通过** | HTTP 覆盖发布幂等/冲突、未发布版本同步拒绝、digest、dryRun false/越界/身份覆盖拒绝与库存未变化 |
| Rust 默认 / 全 feature | `cargo test --workspace --locked`；`cargo test --workspace --all-features --locked -j 2` | **各 25 passed** | 没有真实模型/MCP 调用 |
| Rust 格式 | `cargo fmt --all -- --check` | **通过** | 没有额外运行 Clippy |
| Web 类型 / 构建 | `pnpm typecheck:web`；浏览器测试 webServer 执行 `tsc --noEmit && vite build` | **通过** | 表单模板直接消费 contracts 样例，未增加依赖或修改锁 |
| 浏览器回归 | `node apps/web-console/node_modules/@playwright/test/cli.js test --config .tmp/playwright-local.config.ts --max-failures=1` | **25 passed**，新增 7 项流水线用例 | 此组业务请求按契约拦截：流程、已有版本读取、Token 变化、缺失标记、纯文本主机名、错误批次/写入标志/503 拒绝。已查看截图 |
| 浏览器真实存储链 | 配置 `OPSWEAVE_TEST_JDBC_*`、`OPSWEAVE_TEST_VM_URL` 和本地 Chromium，运行 `node scripts/check_metrics_stack.mjs --pipeline` | **PASS**：真实指标查询链；浏览器 Preview → 发布 → Replay → PostgreSQL Raw，重放前后库存完全一致 | 无浏览器业务请求拦截；来源明确为 labeled fixture。截图 `.tmp/metrics-acceptance/pipeline.png`；不替代厂商来源验收 |
| 文件门禁 | `python scripts/check_release_inputs.py`；`git diff --check` | **通过** | 无提交、推送、新 CI 或部署 |

验证期间第一次完整 Java 运行因临时脚本写成 `OPSWEAVE_TEST_VICTORIA_URL` 而跳过 4 项时序存储测试；改为测试实际读取的 `OPSWEAVE_TEST_VM_URL` 后，以上最终 56 项全部执行通过。浏览器首轮有一项新用例未选择契约样例的 failFast 策略，修正测试输入后最终 25 项全部通过；未把此前失败/跳过记为成功。

本轮交付的是 Host 固定映射、不可变版本、预览/只读重放及其控制台操作闭环。写入型历史修复、持久草稿/重放作业、厂商 Zabbix、生产 OIDC、跨进程预算和生产 RLS 仍未实现或未验收。M2/M3 不因此整体退出，Copilot 继续暂缓。

## 23. 2026-09-25 持久只读重放记录、幂等与显式恢复（追加）

继续在 `main` 工作区开发，没有创建分支、提交、推送或触发部署。保留前两节修改，补齐 `/pipeline/replay-runs` 创建/读取/分页接口与控制台历史记录。记录按可信 tenant、配置 source 和当前 subject 隔离；相同 requestKey 必须对应完全相同参数，成功返回原报告。120 秒租约、最多三次显式执行和 attempt fencing 防止旧执行覆盖；GET 不恢复任务，活跃重复 POST 返回 202。第三次租约过期后，相同 POST 只确认终止。契约、错误码、V008 迁移、操作说明与 [ADR-018](adr/018-durable-host-replay.md) 同步提供。

存储的是有界映射摘要与请求元数据，不复制原始 payload。历史列表不加载报告；读取报告和成功幂等返回重新校验每个旧/新实体的当前权限。失败保存安全错误码，不把异常文本写入报告。库存、Observation、来源扫描、通知和动作均不由重放触发；`writesPerformed=false` 指业务写入，不否认保存重放记录本身。开发内存模式在页面明确标注重启丢失。

环境仍为 Windows/JDK 21、Gradle 8.14.3、Node 24、pnpm 10、Rust 1.98.1 与本地 Chromium；临时 `postgres:17` 和 VictoriaMetrics `v1.152.0` 仅发布 loopback 端口，随机数据库密码不写报告。

| 检查 | 实际命令 / 方法 | 最终结果与范围 |
|---|---|---|
| 仓库与契约 | `python:3.12-slim` 内执行 `python scripts/check_repo.py`、`python -m pytest tests/contracts -o addopts= -q` | **70 个结构化文件、3 个只读 Tool；79 passed**。包含请求键/身份覆盖/预算/状态与报告一致性负例 |
| 纯 Java 领域 | 临时 Node runner 用 `javac --release 21 -encoding UTF-8` 编译 modules 与 tests/domain 并执行全部 13 个 smoke main | **263 项通过**；新增重放 31 项，包括去重、作用域、撤权、租约/fencing、三次上限、显式失败恢复、稳定分页 |
| Java 全量及启动包 | `gradlew.bat :apps:platform-api:test :apps:ingestion-worker:test :apps:platform-api:bootJar :apps:ingestion-worker:bootJar --console=plain --rerun-tasks`，配置真实隔离 PG/VM | **Platform 46 + Worker 15 = 61 tests，0 failures/errors/skipped；两个 bootJar 成功**。PG 八路并发 claim 只有一次取得执行权、过期/旧执行写回拒绝、适配器重建后读取报告、撤权和同时间戳游标测试通过 |
| HTTP 契约 | `PipelineHttpIT` 保存的实际 fixture JSON，经 Draft202012Validator + FormatChecker 对照 contracts | **发布版本、评估、持久记录、历史列表四类响应通过**；HTTP 幂等冲突/失败状态/非法 UUID/身份覆盖和只读限制测试通过 |
| Rust 默认 / 全 feature / 格式 | `cargo test --workspace --locked`、`cargo test --workspace --all-features --locked -j 2`、`cargo fmt --all -- --check` | **各 25 passed，格式通过**；没有真实模型或 MCP 调用 |
| Web 类型与构建 | `pnpm --dir apps/web-console typecheck`；Playwright webServer 执行 `tsc --noEmit && vite build` | **通过**，未增加依赖或修改锁 |
| 浏览器回归 | `node apps/web-console/node_modules/@playwright/test/cli.js test --config=.tmp/playwright-local.config.ts --max-failures=1` | **32 passed**。新增刷新找回记录、换 Token 清理、不确定响应同键重试、显式恢复、分页、撤权清理和畸形报告拒绝；此组业务请求按契约拦截 |
| 浏览器真实存储链 | 设置 `OPSWEAVE_TEST_JDBC_*`、`OPSWEAVE_TEST_VM_URL` 与本地 Chromium，执行 `node scripts/check_metrics_stack.mjs --pipeline` | **PASS**。真实指标查询；Preview→发布→持久只读 Replay→刷新页面→从 PG 找回原记录，前后库存完全一致。没有业务请求拦截，来源明确是 labeled fixture；已查看 `.tmp/metrics-acceptance/pipeline.png` |
| 文件门禁 | `python scripts/check_release_inputs.py`、`git diff --check` | **通过**；没有新 CI、发布或部署声明 |

浏览器测试首轮因新增测试中的多余括号停止；修正后发现 Playwright glob 未覆盖详情子路径，改成覆盖明确 replay-runs 路径的正则后全量 32 项通过。上述是最终实际结果，没有将失败首轮记为通过。Python 容器安装依赖发生一次 PyPI 超时重试，最终安装及检查完成。

恢复测试使用可控时钟模拟中断/租约到期，并重建存储适配器验证持久结果；没有做 OS 杀进程、跨主机时钟漂移或 HA 演练。仍无厂商 Zabbix 实例验收、后台重放 Worker、持久草稿、写入型修复、历史总容量配额/TTL、生产 OIDC/RLS 或跨进程并发预算。M2/M3 不整体宣告完成，Integration Copilot 继续暂缓。

## 24. 2026-09-25 私有草稿持久化、并发保存与进度口径（追加）

在当前 `main` 工作区继续，没有新建分支、提交或推送。增加 Host 草稿保存、按 id/revision 读取和有界最近列表；可信 tenant/source/subject 隔离，expectedEditVersion 的原子比较更新阻止旧页面覆盖新草稿。草稿独立于不可变发布版本，保存不改变来源采集或库存；发布仍提交当前预览的固定定义，不读取最新草稿。补齐 V009、契约/样例、操作说明和 [ADR-019](adr/019-private-pipeline-drafts.md)。

控制台提供保存、读取当前草稿、最近草稿与载入；冲突保留本地选项，提示读取后比较；Token 变化清理工作副本、列表、表单和迟到响应。保存/载入作废旧预览。修复原生 select 的值只写入 HTML attribute、未同步 DOM property 的缺陷，使载入草稿/发布版本的下拉框显示与内部定义一致。

| 检查 | 实际命令 / 方法 | 最终结果与边界 |
|---|---|---|
| 结构 / 契约 | 临时 `python:3.12-slim` 内运行 `python scripts/check_repo.py`、`python -m pytest tests/contracts -o addopts= -q` | **75 个结构化文件、3 个只读 Tool；92 passed**。新增草稿 Schema/样例、身份覆盖、编辑号越界、发布状态和列表上限负例 |
| 纯领域 | 临时 Node runner 以 `javac --release 21 -encoding UTF-8` 编译 modules 与 tests/domain，运行全部 14 个 smoke main | **289 项通过**，新增 PipelineDraftSmoke 26 项；不是冒称执行 Python runner |
| Java / 启动包 | 设置隔离 PG/VM 后，`gradlew.bat :apps:platform-api:test :apps:ingestion-worker:test :apps:platform-api:bootJar :apps:ingestion-worker:bootJar --console=plain --rerun-tasks` | **Platform 49 + Worker 15 = 64 tests，0 failures/errors/skipped；两个 bootJar 成功**。真实 PG 并发更新只有一个成功、另一方冲突；适配器重建后恢复草稿、owner/source/tenant 隔离、摘要篡改拒绝、已发布内容不变 |
| 实际 HTTP 契约 | Draft202012Validator + FormatChecker 校验 `PipelineHttpIT` 保存的实际 fixture 响应 | **六类响应通过**：版本、评估、重放记录、重放历史、草稿、草稿列表 |
| Rust 默认 / 全 feature / 格式 | `cargo test --workspace --locked`、`cargo test --workspace --all-features --locked -j 2`、`cargo fmt --all -- --check` | **各 25 passed，格式通过**；没有真实模型/MCP 调用 |
| Web 类型 / 构建 | `pnpm --dir apps/web-console typecheck`；最终 Playwright webServer 执行 `tsc --noEmit && vite build` | **通过**；沿用固定依赖、无锁更新 |
| 浏览器回归 | `node apps/web-console/node_modules/@playwright/test/cli.js test --config=.tmp/playwright-local.config.ts --max-failures=1` | **38 passed**；新增 6 项草稿用例：刷新载入到显式发布、旧编辑号冲突/保留本地修改、换身份作废加载、错误状态/ID/503 拒绝。此组 HTTP 按契约拦截，已查看草稿页面截图 |
| 真实存储浏览器 | 同一隔离存储和本地 Chromium，`node scripts/check_metrics_stack.mjs --pipeline` | **PASS**：指标查询；保存 PG 草稿→刷新/列出/载入→预览→固定发布→持久只读重放→刷新找回报告。无业务请求拦截，前后库存完全一致；Zabbix 来源仍是 labeled fixture |
| 文件门禁 | `python scripts/check_release_inputs.py`、`git diff --check` | **通过**；无新 CI 或部署声明 |

首次领域编译因新测试误写 Principal accessor 为 scope() 失败，修正为 resourceScope() 后全部通过。首次浏览器草稿用例发现 select 显示旧值，修复 property 绑定后重新运行全量 38 项通过；没有把首次失败计入成功。环境沿用第 23 节；临时测试容器仅暴露 loopback，测试完成后停止。

新增 [PROGRESS.md](PROGRESS.md) 记录整体约 35%（±5 个百分点）、首个只读 MVP 约 55% 的粗估依据：M0–M7 等权、能力证据而非测试数；严格完整里程碑退出只有 M0。此估算不是工时或生产就绪率。仍缺厂商 Zabbix 验收、外部告警/Incident、真实 Tool/模型/AIInsight 闭环、持久 AIRun、Skill 管理与生产试点。草稿没有审批、共享、删除/TTL、完整编辑审计、总容量配额；页面 Preview 流程不作为服务端授权门禁。

## 25. 2026-09-25 OW-R08 资产筛选、分页与详情（追加）

继续在 `main` 工作区实现。新增 `/api/v1/entities/page` 和独立 Schema/样例，支持名称/IP 字面包含、类型、生命周期和 UUID 游标，默认 25、最大 100 条。可信主体的 tenant/对象范围先于数据库 LIMIT 生效；显式范围最多 1000 个实体，返回结果再次检查授权与顺序。无任意表达式、未知/重复参数、客户端 tenant 或自定义排序。单条属性使用保守 16 KiB/深度 8 读预算，PG 查询最多 5 秒；过大记录返回失败，不伪装为空。游标是实时顺序位置，不授予权限，也不承诺跨页快照一致性。

详情每次重新执行授权读取，显示来源实例、采集模式、观测时间、Raw 引用和映射版本/digest。新增采集模式来自已配置 Connector，不能被来源 payload 覆盖；历史数据缺失标记不猜测补全。前端筛选作废游标，切换 Token/401/403 清除旧资产与详情，并丢弃迟到响应。

| 检查 | 实际命令 / 方法 | 最终结果与边界 |
|---|---|---|
| 结构 / 契约 | 临时 Python 容器运行 `scripts/check_repo.py`、`pytest tests/contracts -o addopts= -q` | **79 个结构化文件、3 个只读 Tool；104 passed** |
| 纯领域 | Node runner 编译 modules/tests/domain 并运行全部 15 个 smoke main | **315 项通过**；新增 26 项分页/对象范围/游标/字面搜索/属性预算负例 |
| Java / 启动包 | 隔离 PG/VM，`gradlew.bat :apps:platform-api:test :apps:ingestion-worker:test :apps:platform-api:bootJar :apps:ingestion-worker:bootJar --console=plain --rerun-tasks` | **Platform 54 + Worker 15 = 69 tests，0 failures/errors/skipped；两个 bootJar 成功**。真实 PG 多页不重不漏、无符号 UUID 顺序、范围先于分页、租户隔离、超限失败 |
| 实际 HTTP 契约 | Draft202012Validator + FormatChecker 检查保存的实际 HTTP 响应 | **8 类通过**；新增 entity-page 与 entity，沿用 6 类 Pipeline 响应 |
| Rust 默认 / 全 feature / 格式 | `cargo test --locked`、`cargo test --locked --all-features`、`cargo fmt --all -- --check` | **各 25 passed，格式通过** |
| Web 类型 / 构建 | `pnpm --dir apps/web-console typecheck`、`pnpm --dir apps/web-console build` | **通过** |
| 浏览器回归 | 仓库根目录 `node apps/web-console/node_modules/@playwright/test/cli.js test --config .tmp/playwright-local.config.ts` | **42 passed**；新增分页筛选、重新读取详情与撤权清理、Token 切换丢弃迟到响应、响应超限拒绝。此组 HTTP 按契约拦截 |
| 真实存储浏览器 | 隔离 PG/VM + 本地 Chromium，`node scripts/check_metrics_stack.mjs --pipeline` | **PASS**：指标查询、资产 IP 筛选/PG 详情/来源标记/身份清理、草稿/预览/发布/持久重放刷新恢复；无业务请求拦截。已查看 inventory.png，布局完整 |
| 发布输入静态检查 | `python scripts/check_release_inputs.py` | **通过**；不代表新 CI、提交或部署 |

首次新增 HTTP 测试因测试 Token 少于开发认证最小长度而启动失败，修正后重跑全量 69 项通过；首次 Playwright 从 Web 子目录启动造成临时配置工作目录错误，改从仓库根目录执行后 42 项通过。没有把失败首轮记为成功。临时 PG/VM 仅 loopback，仍供后续 MVP 验证使用。

限制：厂商 Zabbix、真实模型及真实登录提供方仍未配置/联调。旧 `/entities` 全量兼容接口仍用于指标选择器，本轮只对新分页接口保证总行数有界；多源字段冲突历史/人工确认未实现。没有容量/HA/完整 IME 与键盘矩阵验收。已建立 [MVP-CHECKLIST.md](MVP-CHECKLIST.md)，目标进行中，当前粗估仍约 55%，不以新增测试数冒充 MVP 100%。

## 26. 2026-09-25 外部问题与恢复的有界读取（追加）

在同一工作区继续 M3，新增 `ExternalProblem`、发生/恢复/抑制和缺失状态、乱序/重复观测合并规则。提供 Zabbix 7.0 event.get 读取适配和 `GET /api/v1/integrations/zabbix/problems`；需要配置来源的 source.sync。使用问题活动窗口和数值 event ID 游标，批量查询明确恢复 ID，每页最多 100、窗口最多 24h、单实例并发 2、最多两次来源调用，沿用单次 HTTP 10s/2MiB 上限。恢复记录不可读时保留缺失；不支持的全局关联关闭整页拒绝。没有持久化、创建 Incident、通知或动作，响应明确 `not-persisted`。契约与限制见 [ADR-020](adr/020-external-problem-read.md)。

| 检查 | 实际命令 / 方法 | 最终结果 |
|---|---|---|
| 结构 / 契约 | 临时 Python 容器执行 `scripts/check_repo.py`、`pytest tests/contracts -o addopts= -q` | **84 个结构化文件、3 个只读 Tool；122 passed** |
| 纯领域 | Node runner 编译 modules/tests/domain，运行全部 16 个 smoke main | **363 项通过**；新增读取/恢复/分页/错误/身份/合并 48 项 |
| Java / PG / VM / 启动包 | 同一隔离存储，重新执行 Platform/Worker 全量 test 与两个 bootJar（`--rerun-tasks`） | **Platform 58 + Worker 15 = 73 tests，0 failures/errors/skipped；两个 bootJar 成功** |
| 本地来源协议 | `ZabbixProblemContractTest` 使用 loopback HttpServer + 实际 JacksonZabbixTransport | **2 项通过**；检查有界参数、Authorization 不进入 JSON、精确恢复 ID、JSON-RPC 错误和超大响应。是协议桩，不是厂商实例 |
| HTTP 契约 | 实际 `ZabbixProblemHttpIT` 响应 + 原 8 类响应，Draft202012Validator / FormatChecker | **10 类响应通过**；新增 external-problem-page/external-problem |
| 静态门禁 | `scripts/check_release_inputs.py`、`git diff --check` | **通过** |

日期负例首次运行发现 `jsonschema` 未安装可选的日期格式实现，FormatChecker 静默放过非法 date-time。添加固定开发依赖 `rfc3339-validator==0.1.4` 及校验器可用性负例后，重新运行全部 122 项和 10 类实际 HTTP 响应均通过。此前记录的结构/UUID/其他校验结果仍然成立，但当时 date-time 格式未得到有效检查，本次已补齐。容器安装依赖时出现一次 PyPI 超时重试，最终成功。没有通过删掉日期负例规避失败。

本切片未改 Rust 或 Web，沿用同次推进第 25 节已实际运行的 Rust 默认/全 feature 各 25、fmt、TypeScript/build、42 项 Playwright 和真实存储浏览器结果，没有宣称为新告警页面验证。告警页面、持久导入/Incident 原子关联/人工流转与真实来源验收仍待实现；合并规则目前仅是领域代码，尚未接事务。单实例并发配置不是分布式预算或 HA 证明。

用户已确认外部环境“暂未准备，先推进本地实现”。MVP 100% 目标保持进行中；继续本地告警/Incident 与诊断链路，真实来源/模型/登录验收保持未完成，不将 fixture 替代真实验收。

## 27. 2026-09-25 告警持久化、Incident 工作台与同窗口指标（追加）

在 `main` 原工作区继续，未新建分支、提交、推送或部署。新增独立 problem ingest POST、V010 和 Incident 领域/应用/内存/PG 适配。每页按 tenant/source/problem event ID 原子保存问题观测、Incident 和实体关联；行锁内合并，空页不关闭、来源失败不写入、重复导入不重复建 Incident。问题/恢复事实和人工状态分别保存，明确的恢复事实不会自动推进人工状态。时间线区分发生与平台可见时间，纳秒保留。人工状态请求使用 expectedVersion 和绑定操作者/参数的 requestKey，返回稳定幂等结果。

受权列表先应用 tenant、Incident allow-list 和全部关联实体范围，再做 UUID 游标 LIMIT。未映射保留 gap，不通过名称/IP 猜实体；部分实体范围用户不能读取范围不完整的 Incident。详情、变更、关联资产分别重新授权。Web 增加导入/状态筛选/分页/详情/来源/时间线/缺口/人工状态与同键重试；Token 变化或 401/403 清空旧数据。指标链接保持一小时 UTC 窗口并重新读取资产权限；指标 Host 选择器使用服务端分页。旧 GET `/entities` 已改为有界兼容查询，100 条以内保持旧响应，更多返回 422/PAGED_READ_REQUIRED，不伪装完整列表。

| 检查 | 实际命令 / 方法 | 最终结果与边界 |
|---|---|---|
| 结构 / 契约 | Docker Python 3.12 临时容器执行 `scripts/check_repo.py`、`pytest tests/contracts -o addopts= -q` | **98 个结构化文件、3 个只读 Tool；141 passed**，包括 7 类新增示例及非法状态/窗口/来源/时间线/身份字段等负例 |
| 纯领域 | Node runner 用 `javac --release 21` 编译 modules/tests/domain，执行全部 17 个 smoke main | **408 项通过**；Incident 新增 45 项，覆盖恢复不回退、人工状态、幂等/版本、范围过滤、整页回滚与未映射补齐 |
| Java / 启动包 | 专用 loopback PG/VM，重跑 `gradlew.bat :apps:platform-api:test :apps:ingestion-worker:test :apps:platform-api:bootJar :apps:ingestion-worker:bootJar --console=plain --rerun-tasks` | **Platform 65 + Worker 15 = 80 tests，0 failures/errors/skipped；两个 bootJar 成功** |
| PG Incident | 4 个 `PostgresIncidentIT` | **通过**：并发导入只建一次、整页失败回滚、来源恢复与纳秒、租户/对象范围先于分页、未映射补齐、并发状态只有一个成功、适配器重建后幂等结果可查；不是 OS 崩溃/HA 演练 |
| Java HTTP | `IncidentHttpIT` 2 项 + `EntityLegacyLimitIT` 1 项 | **通过**：导入/重复/分页/详情/状态重试、401/400/404/409、越界整数和身份覆盖；旧实体接口 100 条返回完整结果、101 条明确 422，新分页仍可用 |
| 实际 HTTP 契约 | Draft202012Validator + 有效 FormatChecker 检查实际测试保存的响应 | **14 类响应通过**；新增 problem-ingest-result、incident-page、incident-detail、incident-transition-result |
| Rust 默认 / 全 feature / 格式 | `cargo fmt --all -- --check`、`cargo test --workspace --locked`、`cargo test --workspace --all-features --locked` | **各 25 passed，格式通过**；本轮未实现 Rust 平台 HTTP 适配或真实模型调用 |
| Web 类型 / 构建 | `pnpm --dir apps/web-console typecheck`、`pnpm --dir apps/web-console build`；最终 Playwright webServer 再构建包含最终改动 | **通过** |
| Web 回归 | 根目录 `node apps/web-console/node_modules/@playwright/test/cli.js test --config .tmp/playwright-local.config.ts` | **54 passed**；显式 HTTP 拦截 fixture。新增 Incident 导入/分页/详情/跨范围响应拒绝、状态不确定响应同键重试/冲突、撤权/迟到响应清理、资产重读/窗口跳转；新增指标分页/撤权/非法窗口拒绝 |
| 真实本机存储浏览器 | 环境包装器执行 `node scripts/check_metrics_stack.mjs --pipeline`，无业务请求拦截 | **4 个 PASS**：指标曲线、资产分页详情、告警原子导入→重复不新建→人工流转→页面刷新找回时间线→重新授权资产→同窗口 VM 查询、流水线草稿/发布/重放恢复。固定本次进程开发租户、随机 Token、fixture 来源；历史窗口未写采样，明确 No data。已查看 `incident.png`，来源/缺口/时间线/资产区完整 |
| 静态门禁 | `scripts/check_release_inputs.py`、`git diff --check` | **通过**；不等于新 CI 或部署 |

前端首次类型检查发现 unknown 值在闭包内未收窄，修正后通过。首次新增 Playwright 的路由 glob 没覆盖详情子路径，导致测试请求发到未启动的代理目标；中断该轮、修正拦截范围，最终全量 54 项通过，没有增加应用 fallback。最后一次临时容器安装出现 PyPI 超时重试，随后成功完成全部契约与实际响应校验。临时 PG/VM 容器仍仅 loopback，留供后续目标验证；自动验收启动的 Java/Vite/Chromium 已关闭。

限制：来源是明确 fixture，不是厂商验收；真实 Zabbix、模型、登录提供方仍无配置。当前一条来源发生对应一个初始 Incident，人工合并/拆分尚未提供；保存的是最新规范化观测和问题/恢复时间线，未提供完整原始问题历史和任意 asOf 回放；快照限制 50 个问题、100 条时间线、100 个实体，超限拒绝，时间线分页/留存未实现。Java Tool Gateway、Rust HTTP 与 AIInsight 持久化/证据页面仍缺。MVP 粗估约 **60%（±5 个百分点）**，目标保持进行中，M3/M4 不标完成。设计和复现步骤见 [ADR-021](adr/021-external-problem-incident.md)、[本机验收手册](runbooks/incident-local-acceptance.md)。

## 28. 2026-09-25 受控 Tool Gateway、持久证据与 Rust HTTP（追加）

继续在 `main` 原工作区实现，未新建分支、提交、推送或部署。Java 新增当前知识读取会话，固定 Incident 版本、关联实体和一小时内整秒采样窗口；三个 v2 Tool 提供 Incident 概要、分来源 Gauge 统计和 Evidence 复核。V011 在现有平台 PG 保存会话、不可变证据、仅含元数据的审计。四次调用预算由 PG 行锁共享；证据插入与完成审计原子提交。每主体最多四个未过期会话，执行器四并发/十五秒；超时工作真正退出前不释放名额。输入版本和当前权限在读取、复核前后检查，指标目录变化拒绝混合版本结果。

证据明确 `knowledgeMode: current`：查询窗口是采样范围，`availableAt` 是实际采集时刻，24 小时到期。缺失历史 ingestion time、日志、变更、映射和采样等保留 warnings，不回写历史时间以通过旧 asOf 检查。Rust 新增独立 `PlatformHttp::loopback`，逐请求向固定 Java origin 转发凭据，由会话响应构造 Principal。禁用代理/重定向/重试，限制连接/请求/剩余会话期限、并发和流式响应字节数；只从内置 canonical contracts 构建验证器，拒绝替换 tenant/Incident/实体、版本、时间、统计数量/单位和证据源地址。原 demo 和已发布 Skill 包未修改。

| 检查 | 实际命令 / 方法 | 最终结果与边界 |
|---|---|---|
| 结构 / 契约 | Docker Python 3.12 执行 `scripts/check_repo.py`、`pytest tests/contracts -o addopts= -q` | **118 个结构化文件、6 个只读 Tool 定义；169 passed**。包含三个新增 v2 实现定义、当前知识会话/证据/请求 Schema 及身份/期限/预算等负例；原 v1 prototype 仍标未实现 |
| 纯领域 | Node runner 使用 `javac --release 21` 编译 modules/tests/domain，运行全部 18 个 smoke main | **449 项通过**。新增 Tool smoke 41 项，覆盖权限、所有者、共享并发预算、存储失败、过期、输出限制、指标目录变化和复核期间 Incident 变化 |
| Java / 两个启动包 | 专用 loopback PG/VM；`gradlew.bat :apps:platform-api:test :apps:ingestion-worker:test :apps:platform-api:bootJar :apps:ingestion-worker:bootJar --console=plain --rerun-tasks` | **Platform 72 + Worker 15 = 87 tests，0 failures/errors/skipped；两个 bootJar 成功** |
| PG 会话 / 证据 | `PostgresToolReadIT` 3 项，包含在上述 87 项内 | **通过**：十二个并发消费只有四个成功、跨租户/所有者隔离、适配器重建保留额度和纳秒证据；故意不存在的完成审计导致整个证据事务回滚；过期/超时不生成证据 |
| Java HTTP / 执行器 | `ToolGatewayHttpIT` 2 项、`ToolExecutorTest` 2 项，包含在上述 87 项内 | **通过**：可信会话/固定版本工具/受权回读/共享额度、缺权限/身份覆盖/非法窗口与参数、VM 关闭返回失败；忽略中断的底层工作在退出前持续占用名额 |
| 实际响应契约 | Draft202012Validator + 有效 FormatChecker 检查测试 HTTP 输出 | **20 份响应、17 类 Schema 通过**。新增 memory HTTP 的 Incident 证据和真实 PG/VM 的 Metric 证据，两组各含读取会话/证据/ToolResult |
| Rust 默认 / 全 feature | `cargo test --workspace --locked -j 1`；`cargo test --workspace --all-features --locked -j 1` | **各 32 passed（既有 25 + HTTP 7），0 failed/ignored**。HTTP 测试涵盖固定来源、可信会话、共享额度、跨范围/未来/过期/伪造输入、复核撤权/变化、非 JSON/超长/重定向/错误端点拒绝；未调用真实模型或 MCP |
| Rust 格式 / probe | `cargo fmt --all -- --check`；`cargo build -p opsweave-agent-runtime --example platform_read_probe --locked -j 1` | **通过**。新增直接 reqwest 依赖固定为锁中实际版本 0.12.28，Cargo 实际更新锁，仅新增根包依赖关系，没有手造锁内容 |
| Web 类型 / 构建 | `pnpm --dir apps/web-console build`（执行 `tsc --noEmit` + Vite） | **通过**。本轮未修改 Web 源码；未重跑第 27 节的 54 项拦截式 Playwright，不将其记作本轮结果 |
| 真实本机链路 | 环境包装器执行 `node scripts/check_metrics_stack.mjs --pipeline --runtime`，无业务请求拦截 | **6 个 PASS**：原指标、资产、Incident、流水线四条浏览器链；新增 Java Tool→PG/VM→证据复核/第五次 429；Rust HTTP→Java 可信会话→PG 证据/VM 采样→两次授权复核。Gauge 均值 0.355、单位 1，来源明确 labeled-fixture；没有调用模型 |
| 静态门禁 | `scripts/check_release_inputs.py`、`git diff --check` | **通过**，不是新 CI、发布或部署 |

失败与修正：PG JSON 解码的整数类型与内存结果不一致，统一为 Long 后通过回读相等性检查。新增目录变化 smoke 初次编译时缺接口方法，补齐委托后全领域通过。Rust 一次并行全量编译遇 Windows 页面文件不足（OS 1455 / rlib mmap），改为单任务编译后通过；关闭端口在 Windows 可能即时拒绝或连接超时，负例修正为两种明确失败均可接受，未增加 fallback、重试或放宽成功响应校验。最终默认/全 feature 全量均成功。

范围：新 Rust HTTP 适配器目前由独立 probe 使用，现有诊断端点仍是固定 demo，尚未接入新当前知识模型流程。AIInsight 幂等持久化/结果查询/证据页面、生产委托身份、真实 Zabbix/模型/登录、人工合并拆分、历史观测/留存和租户存储总额限制仍未完成。本轮证明本机授权读取与保存证据，不能宣称真实诊断或 RCA 准确率。MVP 粗估更新至 **65%（±5 个百分点）**，目标仍进行中。设计和复现见 [ADR-022](adr/022-current-knowledge-tool-evidence.md)、[Tool 本机验收](runbooks/tool-read-local-acceptance.md)。

## 29. 2026-09-25 当前知识模型流程、AIInsight 与证据页面（追加）

在现有 `main` 工作区继续，无新分支/提交/推送/部署。新增 current 请求/上下文与结果 canonical contracts，新建 Skill 2.0.0 包而不覆盖已发布 1.0.0。Rust 读取两个结构化证据后捕获 asOf、单次有界模型、校验输出/引用、两次证据重新授权复核，再向 Java 受控保存 API 提交。Java 校验可信身份、固定 Skill digest/模型配置、预算完成与复核记录、Incident 当前版本/实体、证据时间及引用；缺口和 mock/当前知识限制不可由模型删掉。V012 原子保存 AIInsight 与两条证据外键关联，保存失败不返回成功。

Web 同源 Java 入口使用当前调用者授权和固定 loopback Runtime origin；未配置即关闭，不给浏览器 Runtime key。Runtime key 是本机进程证明，不是生产身份。结果保存后重新从 Java Store 读取返回，页面保留 runId 支持超时后查询/刷新回读，证据点击时重新授权。模型为显式 mock，真实 Rig 未调用。

| 检查 | 实际命令 / 方法 | 最终结果与边界 |
|---|---|---|
| 结构 / 契约 | Docker Python 3.12 运行 `scripts/check_repo.py`、`pytest tests/contracts -o addopts= -q` | **130 个结构化文件、6 个只读 Tool；198 passed**，含 current 请求/上下文和结果样例、身份/历史截止覆盖、动作/模型/Skill/引用等负例 |
| 纯领域 | `.tmp/domain-check.cjs` 使用 `javac --release 21` 编译 modules/tests/domain 并执行全部 19 个 main | **467 项通过**，新增 AIInsight 18 项，含无复核不保存、scope/权限、幂等/同会话多结果、输入更改、过期和伪造引用 |
| Java / 两个启动包 | 专用 loopback PG/VM；`gradlew.bat :apps:platform-api:test :apps:ingestion-worker:test :apps:platform-api:bootJar :apps:ingestion-worker:bootJar --console=plain --rerun-tasks` | **Platform 80 + Worker 15 = 95 tests，0 failures/errors/skipped；两个 bootJar 成功** |
| PG AIInsight | `PostgresAiInsightIT` 3 项，包含在上述 95 项内 | **通过**：六个并发相同请求只保存一份结果/两条关联；重建适配器保持纳秒与幂等；跨租户/所有者隔离；故意不存在证据导致整笔回滚；最终事务拒绝版本变化/过期会话 |
| HTTP / Runtime 转发 | `InsightHttpIT` 2 项、`RuntimeDispatcherTest` 3 项，包含在上述 95 项内 | **通过**：结果保存/读取/幂等、缺 Runtime key/用户权限与身份覆盖拒绝、错误 digest、默认关闭诊断、固定 loopback/不转发 Runtime key、不跟随重定向/不重试、超长成功响应拒绝 |
| 实际响应契约 | Draft202012Validator + FormatChecker 验证 HTTP 输出 | **24 份响应、19 类 Schema 通过**。新增 memory HTTP 和真实 PG/VM/Rust 链各两份 AIInsight/结果响应 |
| Rust 默认 / 全 feature | `cargo test --workspace --locked -j 1`；`cargo test --workspace --all-features --locked -j 1` | **各 40 passed（领域流程 6 + HTTP 9 + 原回归 25），0 failed/ignored**。验证结构化上下文、一次模型/四读、20 秒模型超时（可控时钟）、保存失败/伪造回执/跨租户/过期/动作拒绝、同键只读回查；未调用真实模型或 MCP |
| Web 类型 / 构建 | `pnpm --filter @opsweave/web-console build`，最终 Playwright webServer 再执行 `tsc --noEmit` 与 Vite | **通过** |
| Web 回归 | `node apps/web-console/node_modules/@playwright/test/cli.js test --config .tmp/playwright-local.config.ts` | **62 passed**，显式 HTTP fixture。新增 8 项覆盖发起/刷新/证据、XSS 文本、身份切换迟到响应、失败待确认不自动重试、伪造引用/动作/错 run/过期/越权证据 |
| 真实本机完整链路 | 环境包装器执行 `node scripts/check_metrics_stack.mjs --pipeline --runtime`，无业务请求拦截 | **7 个 PASS**。新增浏览器→Java→Rust current+mock→两次证据复核→PG AIInsight→同键 POST 返回同一结果→刷新 GET→授权证据；VM 两点均值 0.355，来源为 labeled-fixture。原指标/库存/Incident/Tool/probe/流水线链回归通过 |
| 静态 / 格式 | `scripts/check_release_inputs.py`、`git diff --check`、`cargo fmt --all -- --check` | **通过**；不是远端 CI 或部署 |

修复与复跑：Java 新控制器的 API/domain Permission 通配导入冲突，改为明确导入后编译及全量 Java 通过；TypeScript 闭包中的 unknown 引用列表未收窄，绑定局部常量后通过。首次全量 Playwright 61 成功/1 失败，原因是旧 demo 导航文案已改为“Fixture 诊断演示”而测试仍找旧标签；更新选择器后该文件 7 项通过，最终完整 62 项通过。查看真实链路截图发现 textarea 的 value 属性未设置 Zeus 原生 property，补 `prop:value`、可读配色和结果回读时表单恢复，并加入输入值断言后通过最终回归。`.tmp/metrics-acceptance/insight.png` 包含合成数据，已查看，不包含明文凭据。

限制：结果最多随证据有效 24 小时，过期返回 410；未实现留存清理/租户总存储额、真实费用计量、持久 AIRun/跨副本一次模型执行/HA。连接中断后可以按 runId 查询已保存结果，不能保证未保存运行恢复。Java/Rust/PG/VM 是真实本机实现，但来源/模型仍为 fixture/mock；真实 Zabbix、模型、OIDC 与人工抽样审阅未验收。MVP 约 **70%（±5 个百分点）**，目标保持进行中；接下来继续 Incident 人工合并/拆分、统一会话及多源冲突治理，不把外部环境尚缺当成所有本地工作的阻塞。设计和复现见 [ADR-023](adr/023-current-diagnosis-insight.md)、[AIInsight 本机验收](runbooks/insight-local-acceptance.md)。

## 30. 2026-09-25 Incident 人工合并/拆分、后续归属与证据失效（追加）

继续在原 `main` 工作区推进，无新分支、提交、推送或部署。Java 纯领域新增人工关联规则，V013 在同一 PG 保存合并目标和幂等回执。来源合并后保留原历史快照和人工状态，默认列表只返回活动 Incident；拆分保留至少一个问题，将部分问题及事实时间线移到新 OPEN Incident。问题索引记录当前归属，后续恢复和重复导入不能写回初始 ID。双方记录、归属索引与回执同事务提交，检查双方权限/版本与问题数量/字节/时间预算。

Web 新增预览、人工确认、同键重试、按请求标识回读、刷新恢复和授权历史分页。来源/实体范围在分页 LIMIT 前过滤；身份切换清除结果与迟到响应。关联版本变化后，即使实体集合不变，旧证据与关联 AIInsight 也不能继续读取，返回 409；原诊断历史 asOf 规则未放宽。

| 检查 | 实际命令 / 方法 | 最终结果与边界 |
|---|---|---|
| 结构 / 契约 | `docker run --rm -v D:/workspace/git-code/ops-weave:/workspace -w /workspace python:3.12 sh .tmp/pipeline-check.sh`；执行 check_repo、pytest、发布输入与实际响应校验 | **138 个结构化文件、6 个只读 Tool；216 passed**。新增关联请求/回执/结果/分页样例、身份覆盖/种类/版本/子集字段等负例；关联接口不是模型 Tool |
| 纯领域 | `node .tmp/domain-check.cjs`，`javac --release 21` 编译并执行全部 20 个 main | **501 项通过**。关联 smoke 29 项；Tool smoke 46 项包含同实体关联变化仍失效、合并归档拒绝、新证据过期 |
| Java / 启动包 | 既有专用 loopback PG/VM；环境包装器运行 `gradlew.bat :apps:platform-api:test :apps:ingestion-worker:test :apps:platform-api:bootJar :apps:ingestion-worker:bootJar --console=plain --rerun-tasks` | **Platform 86 + Worker 15 = 101 tests，0 failures/errors/skipped；两个 bootJar 成功** |
| PG 原子归属 | `PostgresIncidentIT` 7 项，含新增 3 项，包含在上述 101 项内 | **通过**：合并/拆分后恢复归属、一个导入页更新同一聚合的统计、适配器重建/并发同键回执、范围先于历史分页、归属索引故意缺失时两份聚合与回执整体回滚 |
| HTTP 与迁移 | `ReorganizationHttpIT` 2 项、`SchemaMigratorAtomicIT` 1 项，包含在上述 101 项内 | **通过**：HTTP 预期版本/重试/历史/归档/证据失效与非法身份/query；测试迁移先创建专用表再故意执行失败语句，确认表与迁移标记均未留下 |
| 实际响应契约 | Draft202012Validator + FormatChecker 检查 `.tmp` 中真实 HTTP 输出 | **32 份响应、22 类 Schema 通过**。新增 memory HTTP 和真实 PG 两组关联回执/结果/分页/归档详情；旧结构兼容 |
| Rust 默认 / 全 feature / 格式 | `cargo test --locked -j 1`、`cargo test --locked --all-features -j 1`、`cargo fmt --all -- --check` | **各 40 passed，0 failed/ignored；格式通过**，未调用真实模型或 MCP |
| Web 类型 / 构建 / 回归 | 根目录 `node apps/web-console/node_modules/@playwright/test/cli.js test --config .tmp/playwright-local.config.ts`，webServer 先运行 `tsc --noEmit` + Vite build | **构建通过，68 passed**。新增 6 项覆盖先预览后写入、503 同键重试/刷新回读、真子集拆分、冲突丢弃旧版本、合并归档、身份变化迟到响应、历史游标/跨对象拒绝与文本渲染；此组使用显式 HTTP fixture |
| 真实本机完整链 | `node .tmp/pipeline-test-env.cjs browser` 包装执行 `node scripts/check_metrics_stack.mjs --pipeline --runtime`，无业务请求拦截 | **8 个 PASS**：原 7 条链回归，加上网页预览（PG 未变）→合并→同键回执/刷新→旧证据/AIInsight 409→拆分 OPEN→重复导入跟随新归属→历史/身份清理。PG/VM 为真实本机存储，来源/模型为 fixture/mock |
| 静态门禁 | `scripts/check_release_inputs.py`、`git diff --check` | **通过**，不代表远端 CI、发布或生产验收 |

失败与修正：第一次全量 Java 新增 PG 用例后耗尽连接，实际专用 PG 报 too many clients；追踪到测试重建 InventoryWiring 后没有关闭连接池。为 Wiring 增加生命周期关闭和初始化失败清理，PG 测试用统一 AfterEach 释放，最终全量 101 项通过。迁移同时增加事务/启动锁/锁后重查，故意失败的 DDL 回滚用例通过。新增 Tool 负例曾把已因关联变化失效的证据当成单纯过期证据，改为先捕获新的当前证据再推进时间验证 410；关联变化仍保持 409。TypeScript 初次编译的 unknown 列表收窄与页面 history 命名遮蔽已修正，构建和完整浏览器回归通过。

已查看 `.tmp/metrics-acceptance/reorganization.png`，结果、版本、actor、原因和两条历史记录完整，Token 为密码显示。自动验收启动的 Java/Rust/Vite/Chromium 已关闭；专用 PG/VM 仍仅 loopback，供后续目标检查。临时凭据和产物未提交。

限制：不是自动相关性、完整问题 Observation 历史或任意 asOf 重建；快照仍有 50 问题/100 时间线/100 实体上限，未提供时间线分页或撤销工作流。历史回执是双方当前授权范围下的 UUID 游标视图，不是按时间排序或跨页一致快照。没有进行 OS 崩溃、HA/容量或生产迁移回退演练。统一会话、完整多源追溯、留存/总量预算/真实费用以及真实 Zabbix/模型/OIDC 和人工审阅仍未完成。MVP 粗估 **75%（±5 个百分点）**，目标继续进行；设计与复现见 [ADR-024](adr/024-incident-reorganization.md)、[本机验收](runbooks/incident-reorganization.md)。

## 31. 2026-09-25 公共 HTTP、开发凭据生命周期与响应边界（追加）

继续在 `main` 原工作区实施，没有新分支、提交、推送或部署。六个业务页面移到同源公共请求层，API 函数不再接收页面传入的 Token。开发凭据仅保存在当前文档内存，hash 导航共享；清除、替换、30 分钟绝对保留到期、pagehide、401/403 会取消在途请求并清理旧数据与客户编辑内容。401 删除凭据，403 保留凭据供其他受权资源使用。旧请求不能影响新身份，历史 Runtime fixture 使用独立凭据与路径。

公共层固定 API 路径、禁重定向/隐式重试/持久凭据；6 个共享在途名额、64 KiB 请求上限、2 MiB 流式响应上限和完整读取期限，AIInsight 仍为 128 KiB。失败正文只取安全代码/有界元数据，不显示原文。Java 新增可选 UUID 请求标识、非法/重复 400、成功和失败响应 no-store/nosniff；标识仅用于关联，不替代可信主体或幂等键。OpenAPI 所有平台路径声明该参数，没有变更领域 Schema 或增加生产认证。

| 检查 | 实际命令 / 方法 | 最终结果与边界 |
|---|---|---|
| 结构 / 契约 | `docker run --rm -v D:/workspace/git-code/ops-weave:/workspace -w /workspace python:3.12 sh .tmp/pipeline-check.sh`，执行结构、契约、发布输入与实际响应检查 | **138 个结构化文件、6 个只读 Tool；217 passed**。新增 OpenAPI 请求标识声明与 UUID 样例负例；Python 只用于开发检查 |
| 纯领域 | Node runner 以 `javac --release 21` 编译所有 modules/tests/domain，运行全部 20 个 main | **501 项通过**；本轮不修改领域业务规则 |
| Java / 两个启动包 | 专用 loopback PG/VM；`gradlew.bat :apps:platform-api:test :apps:ingestion-worker:test :apps:platform-api:bootJar :apps:ingestion-worker:bootJar --console=plain --rerun-tasks` | **Platform 88 + Worker 15 = 103 tests，0 failures/errors/skipped；两个 bootJar 成功** |
| 请求边界 HTTP | `RequestBoundaryHttpIT` 2 项，包含在上述 103 项内 | **通过**：200/401 请求 UUID 回传与缺省生成、no-store/nosniff、重复/非法标识和身份覆盖拒绝；请求标识无法授予身份 |
| 实际响应契约 | Draft202012Validator + FormatChecker 检查实际 Java HTTP 输出和本机验收产物 | **32 份响应、22 类 Schema 通过**，含关联回执/历史、AIInsight、Tool/Evidence；不是额外 32 个业务 E2E |
| Rust 默认 / 全 feature / 格式 | `cargo test --locked -j 1`、`cargo test --locked --all-features -j 1`、`cargo fmt --all -- --check` | **各 40 passed，0 failed/ignored；格式通过**。未调用真实模型或 MCP |
| Web 类型 / 构建 / 回归 | 根目录 `node apps/web-console/node_modules/@playwright/test/cli.js test --config .tmp/playwright-local.config.ts`；webServer 运行 `tsc --noEmit` 与 Vite build | **构建通过，84 passed**：73 项浏览器显式 fixture 回归，11 项直接运行 CredentialSession/JsonClient 测试。新增共享/清除/401/403、草稿清理、旧响应、到期、pagehide、fixture 隔离、大小/编码/超时/并发/地址约束；不将直接测试算作浏览器联调 |
| 真实本机链 | `node .tmp/pipeline-test-env.cjs browser` 包装执行 `node scripts/check_metrics_stack.mjs --pipeline --runtime` | **9 个 PASS**：原 8 条业务链回归，加上 hash 导航共享凭据、清除会话和无持久存储；**36 个浏览器 API 响应** UUID/no-store/nosniff 均通过。Java/PG/VM 为真实本机进程，来源 fixture、模型 mock |
| 静态门禁 | `scripts/check_release_inputs.py`、`git diff --check` | **通过**，不代表远端 CI 或生产验收 |

失败与修正：首次公共客户端把原生 fetch 作为对象方法调用，浏览器出现非法接收者问题；改为显式闭包调用后通过。原回归对跨页面空 Token、403 后保留历史的预期与新公共生命周期不一致，调整为导航共享、失权全页清理；分页测试仍单独验证有效历史翻页，没有删掉用例。

真实链曾在 Playwright `response.json()` 报 `Network.getResponseBody` 无数据。成功流消费后不再追加 abort 或 cancel；随后独立本机页面仍复现 Chromium 的流式正文调试读取问题：200 次 reader/release 中 1 次 CDP 失败，但页面 200 次均完整解析 JSON，同轮 `response.json()` 消费方式 200 次无失败。验收脚本改为被动记录同一次 Vite 代理响应，以请求 UUID、路径、状态匹配，限制单份/总量/条数并要求响应完整，继续检查 UI 结果；不改写业务响应、补发请求或重试。最终完整链 9 个 PASS。

已查看 `.tmp/metrics-acceptance/reorganization.png`：公共凭据栏、清除入口、关联回执/版本与两条历史可读，Token 密码显示；统计产物为 `request-boundary.json`（36/0）。自动验收进程已关闭，专用 PG/VM 仍仅 loopback。临时凭据与产物不提交。

限制：这是开发凭据生命周期，30 分钟不是后端 Token 撤销；清除本标签页不影响其他独立标签页。pagehide/pageshow 使用事件模拟，未完成所有浏览器 BFCache 矩阵。生产 OIDC/BFF Cookie/CSRF、后端撤销与委托主体、完整筛选 URL 恢复、多源冲突/完整观测、留存/费用以及真实来源/模型/人工审阅仍缺。M1 粗估从 50% 到 60%，MVP 取约后仍 **75%（±5 个百分点）**，整体约 50%；目标继续进行。设计与复现见 [ADR-025](adr/025-web-request-session-boundary.md)、[本机验收](runbooks/web-session-boundary.md)。

## 32. 2026-09-25 资产不可变观测与授权历史（追加）

在现有 `main` 工作区推进，没有新分支、提交、推送或部署。Observation 以 tenant/ID 不可变保存、相同记录重复无副作用、不同内容拒绝，字段深复制；Entity/Observation/ExternalLink 的身份和观测时间必须一致。迟到旧观测留存但不回退当前投影，未经显式治理的跨来源写入或 Link 重分配拒绝，不能靠同名/IP 合并。V014 保存纳秒时间，已有 PG 记录按其原微秒精度回填并显式标记；新映射保留当时名称/类型/生命周期，历史缺失不从现值补造。

新增 canonical observation/query/page 和授权只读端点。按最多 31 天的 observedAt 窗口、固定 ingestedAt 截止和来源筛选，租户/实体范围先于分页，每页最多 50 条、字段 16 KiB、PG 查询 5 秒。页面默认 25 条，复用截止时间与窗口翻页，按纳秒拒绝未来接收数据；身份变化、撤权或关闭详情时清理。来源/名称/字段和 Raw 仅显示文本，不解引用任意地址。

| 检查 | 实际命令 / 方法 | 最终结果与边界 |
|---|---|---|
| 结构 / 契约 | Docker Python 3.12 执行 `.tmp/pipeline-check.sh`（check_repo、pytest、发布输入、实际响应校验） | **144 个结构化文件、6 个只读 Tool；235 passed**。新增三个 Schema/样例和观测分页/非法身份/时间/数量/精度/缺口负例；Python 仅用于开发检查 |
| 纯领域 | `node .tmp/domain-check.cjs`，Java21 编译所有 modules/tests/domain，执行全部 main | **529 项通过，21 个 main**。新增观测 28 项：深不可变、同 ID/跨租户、旧数据不回退、接收截止/来源分页、撤权前置、异常存储输出拒绝和隐式跨源写入拒绝 |
| Java / 启动包 | 专用 loopback PG/VM；`node .tmp/pipeline-test-env.cjs full` 执行两应用 test/bootJar，`--rerun-tasks` | **95 平台 + 15 Worker = 110 tests，0 failures/errors/skipped；两个 bootJar 成功** |
| PG 原子写入与时间 | `PostgresObservationIT` 3 项，包含在上述 110 项内 | **通过**：六并发同键只产生一条记录/版本 1，适配器重开保留纳秒和值；迟到历史/接收截止/来源和租户过滤；模拟旧精度记录的读取标记；冲突 Link 导致新 Entity/Observation 回滚，跨源写入不更改现值。旧精度用例是解码验证，不是生产迁移回退演练 |
| HTTP | `ObservationHttpIT` 3 项、`EntityReadDeniedIT` 新增 1 项，包含在上述 110 项内 | **通过**：导入/历史/固定 cutoff/下一页、401/403/404、非法/重复/越界参数 400；超大历史明确 503，不成功返回空页。测试产物含真实 memory HTTP 观测与分页 |
| 实际响应契约 | Draft202012Validator + FormatChecker 校验 Java HTTP 与真实 PG/VM 浏览器产物 | **36 份响应、24 类 Schema 通过**，新增 memory/PG 两组 observation/page；不是 36 条独立 E2E |
| Rust 默认 / 全 feature / 格式 | `cargo test --workspace --locked -j 1`、`cargo test --workspace --all-features --locked -j 1`、`cargo fmt --all -- --check` | **各 40 passed，0 failed/ignored；格式通过**。没有新增模型 Tool 或真实模型调用 |
| Web 类型 / 构建 / 回归 | 根目录 Playwright `.tmp/playwright-local.config.ts`，webServer 运行 `tsc --noEmit` 与 Vite build | **构建通过，92 passed**：81 项浏览器显式 fixture + 11 项请求层直接测试。新增 8 项覆盖窗口/cutoff/来源分页、文本注入、跨 tenant/entity、超过 cutoff 1 纳秒、坏游标、撤权与迟到响应、旧精度/缺口 |
| 真实本机完整链 | `node .tmp/pipeline-test-env.cjs browser` → `scripts/check_metrics_stack.mjs --pipeline --runtime`，同一次代理响应被动记录，无业务响应替换 | **10 个 PASS**：原九条链回归，加上受权资产→真实 PG 观测→当时名称/映射 digest/Raw/双时间。37 个浏览器 API 响应 UUID/no-store/nosniff 均通过；来源 labeled-fixture、模型 mock |
| 静态门禁 | `scripts/check_release_inputs.py`、`git diff --check` | **通过**；不是远端 CI、发布或部署 |

失败与修正：新端点最初未加入仅作用于 EntityController 的 InventoryErrors，非法参数进入错误派发后返回 401；将 ObservationController 接入同一错误边界后，新增负例和全量 Java 通过。增加超大观测测试后，将 HTTP 导入用例的资产读取改为明确筛选 fixture，避免测试执行顺序影响选择。Rust 首轮和真实链同时使用目标 exe，Windows 拒绝覆盖在运行的 Runtime；验收进程结束后顺序执行默认/全 feature，均通过，没有杀死其他任务或改用 mock fallback。

已查看 `.tmp/metrics-acceptance/observations.png`，授权详情、来源实例/范围、历史卡片、当时名称、映射修订、原始引用和双时间可读；Token 为密码显示。自动验收进程已关闭，专用 PG/VM 保持 loopback，临时凭据/产物不提交。

限制：retained-observations-only，不是完整来源事件日志、Entity 任意 asOf 投影或完整告警观测历史。ID 顺序实时分页不是时间顺序或事务快照。当前拒绝隐式多来源写入只是边界保护，跨源 Resolver、字段权威、冲突确认/撤销仍待实现；没有留存清理/总量预算。V014 非空新增列要求配套 writer，不宣称旧二进制回退、在线大表升级、HA 或生产容量已验收。真实来源/模型/身份和人工审阅仍未完成，MVP 粗估保持 **75%（±5 个百分点）**，目标继续。设计与复现见 [ADR-026](adr/026-retained-observation-history.md)、[观测历史验收](runbooks/observation-history.md)。

## 33. 2026-09-25 补充来源字段人工审核、权威与撤销（追加）

继续在原 `main` 工作区推进，没有新建分支、提交、推送或部署。用户已明确真实环境暂未准备，先推进本地实现。本次实现固定 CMDB 导入记录补充现有 Host：仅 name/ip/owner/environment，明确 `import` 模式、七天有效期、独立固定映射 engine/digest。暂存保留原始输入与当时主来源值，不修改资产；逐字段选择 PRIMARY/SUPPLEMENTAL 后接受，也可拒绝。撤销仅作用于当前生效记录，恢复最新主来源字段，保留原始记录、Observation、可信 actor/原因和幂等回执。

V015 使用现有 PG，按租户/资产共享事务锁序列化采集与决策，唯一索引约束一个资产和一个补充外部对象各最多一个生效绑定。再次同步会保存未覆盖的主来源 Observation/快照并投影已确认的字段，迟到主来源不回退投影；撤销不搬移既有 Zabbix EntityId、ExternalLink、指标或 Incident/Evidence 引用。导入不声明实时 presence，主来源完整扫描仍管理生命周期；撤销不能复活已 INACTIVE 的资产。所有读写和重试都检查 `entity.read`、新增 `entity.manage` 与配置来源的 `source.sync`；默认不配置导入、不添加管理权限。

| 检查 | 实际命令 / 方法 | 最终结果与边界 |
|---|---|---|
| 结构 / 契约 | Docker Python 3.12 运行 `.tmp/pipeline-check.sh`，包含 check_repo、pytest、发布输入、实际产物校验 | **154 个结构化文件、6 个只读 Tool；259 passed**。新增五类 Schema/样例和身份/非法字段/选择/版本/时间/数量/固定映射负例；Python 仅作开发检查 |
| 纯领域 | `node .tmp/domain-check.cjs`，Java21 编译全部 modules/tests/domain 并运行 main | **577 项通过，22 个 main**。新增 48 项覆盖不可变输入、原样幂等/异内容冲突、逐字段权威、主来源新旧观测、撤销/拒绝、七天精确边界、分页、ENTITY_MANAGE 与来源/对象范围、生命周期 |
| Java / 两个启动包 | 专用 loopback PG/VM；`node .tmp/pipeline-test-env.cjs full` 执行两应用 test/bootJar，`--rerun-tasks` | **103 平台 + 15 Worker = 118 tests，0 failures/errors/skipped；两个 bootJar 成功** |
| PG 并发 / 回滚 / 重开 | `PostgresSourceReviewIT` 5 项，包含在上述 118 项内 | **通过**：六并发同键只确认一次；两资产竞争同一来源对象只有一方成功且另一方投影不变；重开恢复纳秒、原始值/历史/生效状态；同步保留权威、迟到不回退、撤销恢复最新值；旧请求返回原回执，失效版本与跨来源读取拒绝，完整扫描/撤销保持 INACTIVE；故意损坏主快照中的对象 ID 后撤销被拒绝，两个资产与生效记录不变 |
| HTTP 权限 / 流程 | `SourceReviewHttpIT` 2 项和 `EntityReadDeniedIT` 新增 1 项，包含在上述 118 项内 | **通过**：真实 memory HTTP 暂存/接受/再同步/撤销/原回执；可信 actor、401/403/404/409；非法 identity/字段、超大正文和重复/无界 query 拒绝 |
| 实际产物契约 | Draft202012Validator + FormatChecker 检查 Java HTTP、PG 重开与浏览器链产物 | **47 份请求/响应/持久记录，29 类 Schema 通过**。新增两组导入/决策请求、review/page/固定 mapping 响应和一份 PG 重开记录；不是 47 条独立 E2E |
| Rust 默认 / 全 feature / 格式 | `cargo test --workspace --locked -j 1`、`cargo test --workspace --all-features --locked -j 1`、`cargo fmt --all -- --check` | **各 40 passed，0 failed/ignored；fmt 通过**。没有新增模型 Tool，也未调用真实模型/MCP |
| Web 类型 / 构建 / 回归 | 根目录 Playwright `.tmp/playwright-local.config.ts`，webServer 运行 `tsc --noEmit` 与 Vite build | **全量 99 passed**（88 项浏览器显式 fixture + 11 项请求层直接测试）。新增 7 项覆盖逐字段明确选择/纯文本、tenant/source/cursor 拒绝、过期保留、原请求重试和退出后的迟到响应；之后修正已确认字段展示，**再次构建并运行这 7 项，全部通过** |
| 真实本机完整链 | `node .tmp/pipeline-test-env.cjs browser` → `scripts/check_metrics_stack.mjs --pipeline --runtime`，被动捕获同一次代理响应，无业务响应替换 | **11 个 PASS**：原 10 条链回归，加上浏览器暂存（资产未变）→PG 确认→主来源再次同步→已确认字段和同一指标绑定继续可用→撤销恢复主来源→PG 历史。**50 个浏览器 API 响应**请求 UUID/no-store/nosniff 通过；主来源 fixture、模型 mock、补充来源为明确的合成手工导入 |
| 静态门禁 | `scripts/check_release_inputs.py`、`git diff --check` | **通过**；不是远端 CI 或部署 |

失败与修正：纯领域新测试初次给无参 `ResourceScope.tenantWide()` 传入 tenant 导致编译失败，改为实际 API 后全部 main 通过。HTTP 新测试初始化把 Host 同步空正文误写成 `{}`，触发现有契约 400；修正测试输入后完整流程及全量 Java 通过，没有放宽同步接口。浏览器首轮新增两个用例超时，页面实际没有渲染 For 块体回调中的记录和字段；改用项目现有的“表达式返回子组件 + forItem”写法后新 7 项、全量 99 项通过。截图核查时发现已确认记录仍显示禁用的“请选择”，改为直接显示历史决定中的来源选择，再次执行构建和 7 项回归通过。最终复查新增主快照 scope 校验，故意将快照 ID 改成另一资产的 PG 负例通过，之后全量 Java 118 项与两个 bootJar 重跑通过。

已查看 `.tmp/metrics-acceptance/source-review.png`，目标资产版本、主来源/补充值、映射 digest、actor/原因/时间/原始引用、生效及撤销区域可读；Token 为密码显示。验收脚本自动关闭其 Java/Rust/Vite/Chromium，专用 PG/VM 继续仅 loopback；临时凭据/产物不提交。

限制：固定补充字段导入不是通用已有资产合并、强标识自动 Resolver、持续二来源 presence 融合或厂商 CMDB API。生效确认需先撤销再替换；过期最后已知字段保留，不自动恢复或宣称新鲜。分页按 UUID，不是时间排序或跨页事务快照。V015 需配套 writer，未测试旧二进制并行写入、生产迁移/回退、OS 崩溃或 HA。留存清理/总量预算、完整告警观测、剩余身份/筛选恢复、真实 Zabbix/模型/登录与人工诊断评估仍待完成。M2 粗估 80%，MVP 等权均值 76%、取约 **75%（±5 个百分点）**，100% 目标继续进行。设计及复现见 [ADR-027](adr/027-supplemental-source-field-review.md)、[本机验收](runbooks/source-review.md)。

## 34. 2026-09-25 不可变规范化告警历史与当前归属查询（追加）

继续原 `main` 工作区，没有新建分支、提交、推送或部署。V016 在现有 PG 保存恢复合并前的规范化输入、首次接收纳秒、显式模式/来源契约和当时 Host→Entity 映射。稳定 UUID 由 tenant/source/event/observedAt 派生；相同输入重投不改写历史，迟到输入仍留存，同身份不同内容拒绝整批并回滚投影。旧 Incident 合并快照不能重建输入，因此不回填、不把当前值冒充历史。

新增历史 API 和 canonical record/query/page。查询核对当前 Incident 版本、最多 31 天 observedAt 闭区间、固定 firstReceivedAt cutoff；来源/事件 ID/UUID 分页最多 25 条。当前 Incident 及实体授权、历史映射范围先于 LIMIT；历史映射缺失不会被之后补齐的资产映射追认。PG 在同一只读 repeatable-read 事务核对当前版本和 occurrence 归属，合并归档来源不再拥有转移告警的历史，旧版本返回 409。页面显示原始规范化状态、双时间/当时映射与未留存缺口，409 要求刷新，撤权和退出清除数据。没有新增模型 Tool。

| 检查 | 实际命令 / 方法 | 最终结果与边界 |
|---|---|---|
| 结构 / 契约 | Docker Python 3.12 执行 `.tmp/check-history-contracts.sh`：check_repo、pytest contracts、check_release_inputs | **160 个结构化文件、6 个只读 Tool；281 passed**。新增 3 类 Schema/样例、查询身份/数量/时间/事件来源组合、模式/记录字段/缺口负例 |
| 纯领域 | `node .tmp/domain-check.cjs`：Java21 编译所有 modules/tests/domain，执行全部 main | **607 项通过，23 个 main**。新增历史 30 项：原样重投、首次映射不改写、迟到、恢复遗漏保留、纳秒 cutoff、全批回滚、游标/版本/授权、异常端口输出和合并归属 |
| Java / 启动包 | `node .tmp/pipeline-test-env.cjs full` → 两应用 test/bootJar `--console=plain --rerun-tasks`，专用 loopback PG/VM | **111 平台 + 15 Worker = 126 tests，0 failures/errors/skipped；两个 bootJar 成功** |
| PG 历史验证 | `PostgresIncidentIT` 新增 5 项并扩展并发重投断言，包含在上述总数内 | **通过**：并发原样投递一条历史、重开保留纳秒、恢复遗漏/迟到原输入、全批回滚、历史映射过滤先于分页、当前归属/旧版本、损坏行元数据拒绝、128 字符 tenant 边界 |
| HTTP 边界 | `ProblemHistoryHttpIT` 2 项与权限拒绝 1 项，包含在上述总数内 | **通过**：知识截止 1 纳秒边界、分页、来源/事件筛选、401/403/404/409、非法/重复/无界参数、UUID 回传/no-store；保存真实 memory HTTP 输出 |
| 实际产物契约 | Docker Python 执行 `.tmp/check-pipeline-response.py`，Draft202012Validator + FormatChecker | **53 份请求/响应/持久记录，32 类 Schema 通过**。新增 memory HTTP 和 PG 浏览器两组 record/query/page；不是 53 条独立 E2E |
| Rust 默认 / 全特性 / 格式 | `node .tmp/rust-check.cjs` → fmt、`cargo test --workspace --locked -j 1` 与 `--all-features` | **各 40 passed，0 failed/ignored；fmt 通过**。未调用真实模型/MCP |
| Web 类型 / 构建 / 回归 | Playwright `.tmp/playwright-local.config.ts`；webServer 执行 tsc/Vite build | **全量 108 passed**（97 项浏览器显式 fixture + 11 项请求层直接测试）。新增 9 项验证原状态/文本、固定 cutoff/version 翻页、来源事件筛选、越 tenant/occurrence/历史映射、未来 1 纳秒、游标、409 手工刷新、撤权/迟到清理；截图修正后再次构建并运行新 9 项，全部通过 |
| 真实本机完整链 | `node .tmp/pipeline-test-env.cjs browser` → `scripts/check_metrics_stack.mjs --pipeline --runtime` | **12 个 PASS**；新增网页告警历史→PG 输入/版本/留存缺口。**51 个浏览器 API 响应** UUID/no-store/nosniff 通过，0 边界失败。真实 Java/PG/VM/Rust，本机来源 fixture、模型 mock；显示修正后完整链再次通过 |
| 静态检查 | `git diff --check`、发布输入检查 | **通过**；不代表远端 CI、发布或生产验收 |

失败与修正：最初 3 项 AIInsight PG 回归因独立迁移清单遗漏 V016 而失败；将使用 PostgresIncidentStore 的两个独立测试初始化加入新迁移后通过。新增 HTTP 测试误用 `X-Request-Id`，断言拿不到项目现有的 `X-OpsWeave-Request-Id`；修正测试使用既有协议，没有修改请求边界。最后复核发现 V016 tenant 长度最初误写 64，改为领域和现有表的 128，并增加真实 PG 长 tenant 用例；仅调整本任务专用 fixture 库中刚创建的表结构，无数据删除，之后全量 Java 126 项和两个启动包通过。截图中空筛选框缺少可见边界，添加局部边框/提示和长字段换行，构建、新 9 项页面回归与完整本机链复测通过。

已查看最终 `.tmp/metrics-acceptance/problem-history.png`：两条同事件不同观测时间的记录、模式/来源、首次接收/映射、当前版本/cutoff、恢复/抑制与缺口可读；输入框边界和提示可见。验收脚本自动关闭其 Java/Rust/Vite/Chromium，专用 PG/VM 继续仅 loopback；临时凭据/产物不提交。

限制：这是留存的规范化来源输入，不是完整厂商 JSON/事件日志、Incident 任意 asOf 状态或留存前回填。按 UUID 排序，每页事务一致；固定 cutoff/version 不等于跨页数据库快照。尚无留存清理/存储总量预算、时间线分页或生产迁移/回退/HA 容量验收。真实 Zabbix/模型/登录和人工评估仍待环境，继续本地实现。M3 粗估由 80% 到 85%，MVP 等权约 **77%（±5 个百分点）**，100% 目标继续；下一步筛选 URL 恢复、生产身份适配和通用跨源治理。设计与复现见 [ADR-028](adr/028-normalized-problem-history.md)、[本机验收](runbooks/problem-history.md)。

## 35. 2026-09-25 只读筛选、资源与实际指标窗口的 URL 恢复（追加）

继续原 `main` 工作区，没有新建分支、提交、推送或部署。资产页恢复已应用名称/IP、类型、生命周期、游标和选中 ID；Incident 恢复状态/游标/选中 ID；指标恢复 Host 选择、metricKey、Last 范围及实际查询的 UTC from/till。三类 canonical Schema/样例和 URL 规则是只读选择契约，不包含身份、凭据、结果缓存、导入/操作正文或幂等键。

同路径 popstate/hashchange 去重并使旧响应失效，刷新/前进后退只还原表单，必须显式重新读取。指标固定窗口不能退回最近值，目录已无选中指标时明确失败；实际 select 展示与读取参数一致。刷新只保留 URL，Token 仍仅在内存；初始逐字输入到首次读取前保持链接，已有会话换凭据/注销/拒绝/到期清理选择。非法、重复、未知或超限参数显示错误并禁用读取，显式重置恢复。没有新增 Java API、存储迁移、服务或模型 Tool。

| 检查 | 实际命令 / 方法 | 最终结果与边界 |
|---|---|---|
| 结构 / 契约 | Docker Python 3.12 执行 `.tmp/check-history-contracts.sh`：check_repo、pytest contracts、check_release_inputs | **166 个结构化文件、6 个只读 Tool；319 项通过**。新增 3 类 selection Schema/样例及 35 项身份/命令/缓存/长度/枚举/字符/UUID/时间边界负例与未选窗口正例 |
| 纯领域 | `node .tmp/domain-check.cjs`，Java21 编译全部 modules/tests/domain 并运行 main | **607 项通过，23 个 main**；本次没有改变领域行为 |
| Java / 两个启动包 | `node .tmp/pipeline-test-env.cjs full`，专用 loopback PG/VM；两个 test/bootJar，`--rerun-tasks` | **111 平台 + 15 Worker = 126 tests，0 failures/errors/skipped；两个 bootJar 成功** |
| Rust 默认 / 全 feature / 格式 | `node .tmp/rust-check.cjs`：fmt、workspace default/all-features locked，`-j 1` | **各 40 passed，0 failed/ignored；fmt 通过**。先结束 Rust 测试，再启动完整验收 Runtime，没有真实模型调用 |
| Web 类型 / 构建 / 回归 | Playwright `.tmp/playwright-local.config.ts`，webServer 运行 `tsc --noEmit` 与 Vite build | **全量 124 passed**（110 项浏览器显式 fixture + 14 项请求层/codec 直接测试）。新增 13 项浏览器与 3 项 codec；最后补齐逐字输入初始 Token 的边界后，**再次构建及相关 48 项全部通过**（45 浏览器 + 3 codec） |
| 真实本机完整链 | `node .tmp/pipeline-test-env.cjs browser` → `scripts/check_metrics_stack.mjs --pipeline --runtime` | **13 个 PASS**，含刷新无自动请求/无恢复凭据、相同资产/Incident/指标及实际窗口的 Java 重授权读取。此前历史/审核/诊断/幂等保存/重放/人工归属链继续通过。**56 个浏览器 API 响应** UUID/no-store/nosniff 通过、0 边界失败。数据来源 fixture、模型 mock，Java/PG/VM/Rust 是真实本机进程 |
| 实际产物契约 | Docker Python `.tmp/check-history-artifacts.sh`，Draft202012Validator + FormatChecker | **56 份实际产物、35 类 Schema 通过**；新增三份由真实浏览器 URL 提取的规范化选择，其余是现有 Java HTTP/PG/浏览器链产物。这不是 56 条独立 E2E |
| 静态检查 | `git diff --check`、发布输入检查 | **通过**；不代表远端 CI 或部署 |

失败与修正：首轮 TypeScript 编译发现局部 range 变量遮蔽 selection 函数，改名后通过。新增浏览器恢复用例发现 Incident/Metric 动态 option 的初始选中值未跟随内部状态，显式绑定 option.selected 后通过。契约测试首次误用 Python `from` 关键字参数导致收集失败，改为字典更新后通过，并明确正则尾部不能接受换行。全量 Web 首次 123/124，其中凭据替换后的 URL 同步断言早于浏览器地址事件，页面已清空；改为等待 URL 后全量 124 通过。最终复查初始 Token 逐字输入时不应按长度阈值误判身份替换，改为首次读取/已有会话边界，三个页面逐字输入与替换用例及相关 48 项再次通过。

已查看 `.tmp/metrics-acceptance/restored-metrics.png`：实际固定 UTC 窗口、资源/metricKey、目录下拉框、曲线和 fixture 来源可读，开发 Token 为密码显示。验收脚本退出并关闭自建 Java/Rust/Vite/Chromium；专用 PG/VM 保持 loopback，临时凭据/产物不提交。

限制：浏览器旧历史仍可能含查询词和对象 ID，不存储凭据但也不承诺擦除历史；所有链接始终重新授权。UUID 游标是实时视图，恢复后不虚构总页数。历史子面板筛选、写操作和诊断执行输入不纳入恢复协议；没有生产 OIDC/BFF/CSRF/撤销验收。真实来源/模型/身份、通用 Resolver/presence、费用/留存与人工评估继续保留在退出清单。M1 粗估由 60% 到 65%，MVP 等权约 **78%（±5 个百分点）**，100% 目标继续。设计和复现见 [ADR-029](adr/029-restorable-read-selections.md)、[本机验收](runbooks/view-selection.md)。

## 36. 2026-09-25 OIDC BFF、会话撤销与有界 Runtime 委托（追加）

继续原 `main` 工作区，没有新建分支、提交、推送或部署。用户明确真实环境暂未准备，先推进本地实现。本次增加显式 OIDC authorization-code BFF：固定 issuer/端点/回调，state/nonce/S256 PKCE、RS256/issuer/audience/时间验证；IdP 只提供经过验证的 issuer/sub，平台租户、用户、权限、资源范围由操作员授权文件决定，忽略 Token 内额外 tenant/permissions。文件逐请求重新读取与 digest 校验，禁用/改动/损坏不会沿用旧授权。

浏览器只使用 HttpOnly Cookie 和内存中的 masked CSRF。普通配置要求 HTTPS、Secure/Host Cookie；本机协议模式明确 loopback 与 `oidc-protocol-test`，不是生产认证验收。单进程最多 1000 个 session，登录准备 180 秒、登录后配置 60–1800 秒且不超过 ID Token 到期，不随读取续期；成功登录旋转 cookie ID/CSRF，退出、到期和授权变更撤销会话。Web 只自动读取登录元数据，业务选择恢复继续要求显式读取；退出清除双标签页与迟到结果。

Java 为一次已授权诊断签发最多 4 个并发、65 秒、8 次请求的 opaque Runtime 委托，固定原始 session、incident/window/runId/question/read-session 与允许端点，禁止浏览器 Cookie/Origin、非 loopback、来源写入或任意 API。每次使用复核登录/授权，任务结束删除；原有 Tool 四调用预算、对象授权、证据、Runtime 提交证明和幂等保存继续有效。IdP Token 不返回 Web，也不传给 Rust。没有新增部署单元、数据库或模型 Tool；依赖锁由 Gradle `dependencies --write-locks` 实际生成。

| 检查 | 实际命令 / 方法 | 最终结果与边界 |
|---|---|---|
| 结构 / 契约 | Docker Python 3.12 `.tmp/check-history-contracts.sh`：check_repo、pytest contracts、check_release_inputs | **350 项通过，6 个只读 Tool**。新增会话/退出/操作员授权 3 类 Schema 和样例，拒绝提供方凭据/非法身份/范围/额外字段；末次静态扫描 171 个结构化文件 |
| 纯领域 | `node .tmp/domain-check.cjs`，Java21 编译全部 modules/tests/domain，运行所有 main | **619 项、24 个 main 通过**。新增 IdentityGrant 12 项：必须匹配已验证 issuer/sub、禁用拒绝、到期纳秒边界、非法外部身份/版本等；领域无 Spring/JDBC/HTTP 依赖 |
| Java / 两个启动包 | `node .tmp/pipeline-test-env.cjs full`，专用 loopback PG/VM，两个 test/bootJar、`--rerun-tasks` | **127 平台 + 15 Worker = 142 tests，0 failures/errors/skipped；两个 bootJar 成功**。最终补充旧 CSRF 和缺 Origin 断言后再次运行 OIDC 相关 **18 项通过** |
| OIDC HTTP / 会话 / 委托 | `OidcLoginIT` 6、`OidcBoundaryTest` 7、`OidcHttpTest` 3，以及原有 raw-bearer 边界 2 项，含于上述总数 | **通过**：真实 Spring HTTP + 本机 RSA/JWKS/token 服务；错误 state/重放、issuer/audience/nonce/iat/exp/签名/sub、64 KiB advertised/chunked、503 与慢正文总时限；映射/权限/范围/撤权/文件失败；cookie 旋转、旧/缺 CSRF、缺/外 Origin；内部委托同会话/固定端点/read-session、65 秒精确期限、8 调用/4 并发、关闭/注销/新登录/改授权失效。生产 Secure/HttpOnly/Path/SameSite 是配置测试，未宣称真实 TLS 浏览器验收 |
| Rust 默认 / 全 feature / 格式 | `node .tmp/rust-check.cjs`：fmt、workspace default/all-features locked，`-j 1` | **各 40 passed，0 failed/ignored；fmt 通过**。先结束编译/测试再启动验收 Runtime；未调用真实模型/MCP |
| Web 类型 / 构建 / 回归 | Playwright `.tmp/playwright-local.config.ts`，webServer 执行 tsc/Vite build | **全量 128 passed**（110 项浏览器 fixture + 18 项请求层/codec 直接测试）。新增 4 项 Cookie/bootstrap/CSRF/无 Bearer/期限/401/403/迟到响应/严格元数据测试；最终收紧恢复时旧请求处理与文案后，**再次构建及相关 33 项通过** |
| OIDC 本机完整链 | `node scripts/check_oidc_stack.mjs`（`.tmp/oidc-stack.cjs` 仅注入专用 PG/VM 环境） | **5 个 PASS、17 个浏览器响应 UUID/no-store/nosniff 检查通过，0 边界失败**。真实浏览器授权码跳转→Java→操作员 Principal→Cookie/CSRF→有界委托→Rust mock→真实 PG/VM Tool→PG AIInsight；刷新只读元数据，显式回读结果/证据；双标签退出、401、授权文件撤销、无凭据持久化。最终 Web 修正后整链再次通过 |
| 原开发模式完整链 | `node .tmp/pipeline-test-env.cjs browser` → `scripts/check_metrics_stack.mjs --pipeline --runtime` | **13 个 PASS、56 个浏览器响应边界通过、0 失败**。资产/历史/字段审核/指标/Incident/归属/流水线/诊断与 URL 恢复继续通过，来源 fixture、模型 mock；没有把 OIDC Cookie 默默替换为 dev 凭据 |
| 产物契约 | Docker Python `.tmp/check-oidc-artifacts.sh`，Draft202012Validator + FormatChecker | **60 份产物、38 类 Schema 通过**。原 56 份加 OIDC session、logout、合成授权文件和 PG AIInsight；session 仅 CSRF 字段脱敏，其余保留实际值。不是 60 条独立 E2E |
| 静态门禁 | `git diff --check`、发布输入检查 | **通过**；没有远端 CI 或部署 |

失败与修正：最初通用 Spring session-management 策略把每次重新构造的请求身份当成新认证，反复重置 CSRF，退出收到 403；改为应用自有会话，成功登录时显式旋转 ID/CSRF，后续请求只重验证授权，旧 Token/Origin 负例和退出通过。旧占位测试仍断言 OIDC 无法启动，更新为 raw dev/provider Bearer 不得在 OIDC 模式解析，缺失/非法配置仍由启动边界拒绝。前端一项测试两次生成到期时间相差 1ms，改为同一固定样例后全量通过。OpenAPI 新端点遗漏公共请求 ID 声明，补齐后契约通过。新整链脚本曾遗漏告警导入 JSON 正文；修正测试输入，并发现 Servlet 错误 dispatch 被鉴权链改写为 401，允许框架 ERROR dispatch 后保留 415 且不会误清会话，真实 HTTP 断言通过。首次图表读取早于 VM 写入可见，验收脚本增加有界可见性等待后通过；产品没有增加重试。

已查看 `.tmp/oidc-acceptance/oidc-insight.png`：协议测试/用户租户/期限、mock 与 fixture 标记、问题/窗口/Skill、结果、两份证据和缺口均可读，无 Token 输入或凭据内容。脚本关闭自建 IdP/Java/Rust/Vite/浏览器，专用 PG/VM 保持 loopback，产物和临时凭据不提交。

限制：尚无真实 IdP HTTPS/密钥轮换/反向代理部署验收，无 IdP back-channel logout、Token 自动刷新、共享 session/HA；平台退出不代表 IdP 退出。Runtime 仍是同机 `platform-dev` 传输加 Java 委托边界，不宣称跨主机服务认证。Worker 独立服务身份、通用 Resolver/presence、费用/留存、真实来源/模型与人工评估继续未完成。M1 粗估从 65% 到 85%，MVP 等权约 **82%（±5 个百分点）**，整体约 53%，100% 目标继续。设计与配置见 [ADR-030](adr/030-oidc-bff-and-bounded-runtime-delegation.md)、[本机验收](runbooks/oidc-bff.md)。

## 37. 2026-09-25 Worker 独立服务身份与受限历史采集（追加）

继续用户指定的 `main` 工作区，不新建分支、提交、推送或部署。真实提供方配置暂未准备，按用户选择先推进本地实现。本次新增默认关闭的 OAuth client_credentials Worker profile 与独立 GET history 入口，复用原有有权历史读取、VM 确认及 PG checkpoint。浏览器/dev/Runtime 凭据不能混用；没有新启动单元、数据库、模型 Tool 或依赖升级。

平台仅接受固定 issuer/JWKS、RS256 at+jwt、单一 audience/scope、client_id/sub/jti/iat/exp 的短期 access token。运维文件决定 tenant/subject、source/item/entity/metric、有效期、可读数据范围、窗口/点数/每分钟预算；JWT 的 tenant/权限 claim 不授予权限，Worker 不发送身份字段覆盖它。文件每请求复核，禁用、损坏或消失不使用旧授权；4 个共享并发，单 client 上限 120 次/分钟。普通模式要求直接 TLS，本次实际使用显式 loopback 协议 fixture，不能替代真实 TLS 验收。

Worker 固定 token endpoint/basic/form，正文 32 KiB/完整 5 秒，无跳转或应用重试，令牌仅内存单调期限缓存；401 清缓存并结束本次读取。失败不推进 completedThrough/revision。token endpoint/client ID/认证模式进入系列 fingerprint，secret 不进入，同 client 的密钥轮换可续采。原 dev profile 保持固定 tenant-demo，VM/PG 仍仅 loopback。

| 检查 | 实际命令 / 方法 | 最终结果与边界 |
|---|---|---|
| 结构 / 契约 | Docker Python 3.12 `.tmp/check-history-contracts.sh`：check_repo、pytest contracts、check_release_inputs | **384 项通过；173 个结构化文件、6 个只读 Tool**。新增服务授权 Schema/样例、字段/身份/范围/预算负例及独立 OpenAPI 安全声明 |
| 纯领域 | `node .tmp/domain-check.cjs`，Java21 编译全部 modules/tests/domain 并运行 main | **649 项、25 个 main 通过**。新增 HistoryServiceGrant 30 项：issuer/client/sub、令牌/授权期限、source/item、闭合过去窗口、点数/速率、显式资源权限、90 天/31 天总范围 |
| Java / 两个启动包 | `node .tmp/pipeline-test-env.cjs full`，专用 loopback PG/VM，两个 test/bootJar、`--rerun-tasks` | **135 平台 + 20 Worker = 155 tests，0 failures/errors/skipped；两个 bootJar 成功** |
| 服务 HTTP / Worker 边界 | 新增 HistoryServiceHttpIT 5、HistoryServiceConfigurationTest 3、ClientCredentialsTest 5，含于 Java 总数 | **13 项通过**。真实 HTTP JWT 签名/算法/类型/issuer/audience/scope/时间/subject/client 负例，dev/普通用户路径隔离、Cookie/Origin/转发头/身份覆盖/重复参数拒绝；撤权/过期/换资源/文件故障、速率/并发；文件严格 Schema/唯一身份/预算；交换缓存/401不重试、provider 503/302/非JSON/超量/慢正文及 secret 脱敏 |
| Rust 默认 / 全 feature / 格式 | `node .tmp/rust-check.cjs`，fmt、workspace default/all-features locked，`-j 1` | **各 40 passed，0 failed/ignored；fmt 通过**。先结束检查再启动 Runtime，没有真实模型调用 |
| Web 类型 / 生产构建 / 回归 | `pnpm --filter @opsweave/web-console build`；Playwright `.tmp/playwright-local.config.ts` | **构建通过；全量 128 passed**。本轮未修改页面，OIDC/开发会话与读取流程继续回归 |
| 服务身份与 OIDC 完整链 | `node scripts/check_oidc_stack.mjs --history-service`（本机 wrapper 仅注入专用 PG/VM/Chromium/PG 容器配置） | **9 个 PASS、17 个浏览器响应边界通过**。独立 service client → 已验证 JWT/运维范围 → Java history → 真实定时 Worker → VM fixture 0.4 采样及标签 → PG checkpoint；在线撤权使多次轮询 403，游标和 revision 不变；token 服务 503 的重启 Worker 不读 history、不回退；同 client 轮换 secret 后续采。随后原 OIDC/CSRF → Rust mock → PG AIInsight、刷新回读、双标签退出、文件撤权均通过 |
| 原开发模式完整链 | `node .tmp/pipeline-test-env.cjs browser` → `scripts/check_metrics_stack.mjs --pipeline --runtime` | **13 个 PASS、56 个 API 响应 UUID/no-store/nosniff，0 边界失败**。既有资产/观测/审核/指标/Incident/人工归属/流水线/诊断/选择恢复继续通过 |
| 实际产物契约 | Docker Python `.tmp/check-oidc-artifacts.sh`，Draft202012Validator + FormatChecker | **62 份产物、40 类 Schema 通过**。原 60 份追加服务授权文件和实际 service metric-history-page；不含任何 client secret/access token。checkpoint 测试报告为事实记录，不冒充业务 Schema 或独立 E2E |
| 静态门禁 | `git diff --check`、Node 两个验收脚本 `--check`、发布输入检查 | **通过**；没有远端 CI 或部署 |

实际末次服务链中，撤权后 checkpoint 为 `completedThrough=1789992120/revision=2`，至少连续三轮拒绝后保持一致；恢复及密钥轮换后为 `1789992180/revision=3`。这是随机测试租户的合成来源数据，不是客户或真实厂商采样。脚本期间观察到 7 次 403、2 次成功 token 交换；503 只在计划轮询重新尝试，产品未添加隐式重试。

失败与修正：慢正文测试发现仅设置 Java HttpRequest.timeout 不会在响应头已到达后结束停滞正文，改为 sendAsync future 的完整期限并取消后测试通过。配置文件测试最初未将 canonical 样例加入 test resources，补上仅测试资源映射后全量通过。完整链首次通过，最终复跑暴露 Windows 强制 kill 与下一轮租约取得的竞争：新 Worker 正确等待原 300 秒 lease，脚本 60 秒期限失败。验收脚本改为仅观察白名单的批次终止日志、在持久化/释放 lease 后停止 Worker，再次完整 9 项通过；未缩短或跳过产品租约/fencing。Docker mount 首次命令引号被 cmd 解释为路径内容，未执行检查；修正参数后结构/契约与产物校验均成功。

脚本关闭自建 Worker/Java/Rust/IdP/Vite/Chromium，专用 PG/VM 保持 loopback；完整进程日志不保存，观察到的其他 Worker 日志丢弃。临时配置和产物留在忽略的 `.tmp`，没有提交。授权文件需由运维控制权限并原子替换；有限历史范围用尽后不会自动扩大。

限制：真实 IdP/HTTPS/证书/代理/厂商 Zabbix/模型与人工评估仍未验收；没有 opaque token introspection、分布式配额、共享会话、多流调度或远程 PG/VM。Runtime 仍为同机受限委托。通用跨源 Resolver/presence、费用/留存继续是下一步本地工作。M1 粗估由 85% 到 90%，MVP 等权约 **83%（±5 个百分点）**，整体约 53%，目标继续进行，不标记 100%。设计和复现见 [ADR-031](adr/031-history-worker-service-identity.md)、[服务身份验收](runbooks/history-service-identity.md)。

## 38. 2026-09-25 资产 UUID 登记、精确定位与导入身份依据（追加）

继续用户指定的 `main` 工作区，不新建分支、提交、推送或部署。用户暂未准备真实来源/模型/登录配置，本轮推进可独立验收的本地资产治理切片。新增人工核对的 canonical asset UUID 登记与撤销、租户+运维命名空间内的唯一生效目标、受权精确定位，以及固定 CMDB 导入可选身份 pin。没有更改已发布的四字段映射/digest、新增模型 Tool 或拆分启动单元。

页面先明确读取资产、命名空间和版本，核对 UUID 与原因后登记。tenant/actor/来源/权限来自可信平台；请求的 `expectedNamespace` 只作为配置前置条件，不能选任意命名空间。登记/撤销与 Entity version、原始幂等回执同事务；与来源采集/字段审核共享实体锁，跨实体同键并发由唯一索引和 scoped advisory lock 保证一个赢家。Entity/ExternalLink/指标/Incident ID 不迁移。

定位后的导入记录保留 registry ID/namespace/value/version；暂存与 ACCEPT 在实体锁内复核当前生效状态。登记撤销后旧待审记录不能生效。生效字段仍引用登记时先返回 409，必须显式撤销字段确认，再撤销标识。历史记录/原回执保持可读，相同登记请求在后续撤销后仍返回原始回执，不能据此推断当前生效状态。最多 16 生效/1000 历史登记每实体、25 条分页、16 KiB 请求；没有自动清理审计。

| 检查 | 实际命令 / 方法 | 最终结果与边界 |
|---|---|---|
| 结构 / 契约 | Docker Python 3.12 `.tmp/check-identity-final.sh`：check_repo、pytest contracts、check_release_inputs | **423 passed；189 个结构化文件、6 个只读 Tool**。8 类新 Schema/样例、4 个 HTTP 操作的 OpenAPI；UUID/namespace/隐藏身份覆盖/安全整数/状态一致性/撤销/pin/请求前置条件负例。两个 SourceReview Schema 增加可选 identity |
| 纯领域 | `node .tmp/domain-check.cjs`，Java21 编译全部 modules/tests/domain 并运行 main | **698 项、26 个 main 通过**。新增 AssetIdentity 49 项：租户/命名空间隔离、原回执、同键并发、权限/隐藏目标、pin/撤销/字段依赖、分页/16 个生效预算、非法 UUID/namespace。领域无框架/HTTP/JDBC 依赖 |
| Java / 两个启动包 | `node .tmp/pipeline-test-env.cjs full`，专用真实 loopback PG/VM，两个 test/bootJar、`--rerun-tasks` | **143 平台 + 20 Worker = 163 tests，0 failures/errors/skipped；两个 bootJar 成功** |
| 新 HTTP / PG | AssetIdentityHttpIT 3、PostgresAssetIdentityIT 5，含于 Java 总数 | **8 项通过**。真实 Spring HTTP 登记/重试/定位/导入/撤销、401/身份覆盖/重复参数/namespace 变更；真实 PG 同键同目标幂等、跨目标竞争与失败回滚、连接重开/原始回执、actor 冲突与租户/namespace 隔离、旧 pin 失效、先撤字段后撤标识、预算和分页 |
| Rust 默认 / 全 feature / 格式 | `node .tmp/rust-check.cjs`，fmt、workspace default/all-features locked，`-j 1` | **各 40 passed，0 failed/ignored；fmt 通过**；检查完成后才启动本机 Runtime，没有真实模型调用 |
| Web 类型 / 构建 / 回归 | Playwright `.tmp/playwright-local.config.ts`，webServer 实际运行 tsc/Vite build | **全量 139 passed、构建通过**。新增 11 项登记/定位/导入 pin、逐字文本、未知结果原请求重试、撤销按钮原因状态/命名空间和版本、非法响应与换行后缀、未映射 404、退出/迟到结果；原 128 项继续通过 |
| 开发模式真实存储完整链 | `node .tmp/pipeline-test-env.cjs browser` → `scripts/check_metrics_stack.mjs --pipeline --runtime`，新增 `lib/check_asset_identity.mjs` | **14 个 PASS、85 个浏览器响应 UUID/no-store/nosniff，0 边界失败**。真实浏览器→Java→PG 登记原始回执重试→定位→带 pin 导入/字段接受→依赖撤销 409 且版本不变→字段显式回滚→标识撤销/再定位 404；hostId/主来源字段保持可追溯。既有指标、Incident/归属、重放/草稿、Rust mock/AIInsight 和选择恢复回归通过 |
| Worker / OIDC 完整链 | `node .tmp/oidc-stack.cjs --history-service` → checked-in OIDC/service 脚本 | **9 个 PASS、17 个浏览器响应边界通过**。独立 service JWT/受限历史/真实 Worker/PG/VM、撤权游标不变、token 503 无回退、同 client 密钥轮换，以及 OIDC Cookie/CSRF/Runtime 委托、PG AIInsight、刷新与双标签退出继续通过 |
| 实际产物契约 | Docker `.tmp/check-identity-final.sh`，Draft202012Validator + FormatChecker | **73 份请求/响应产物、48 类 Schema 通过**。原 62 份加 registry 的 8 类产物、额外撤销回执、带 pin 的 SourceReview/import，共 11 份。只保存合成业务数据，CSRF 脱敏，不保存 Token/secret；不是 73 条独立 E2E |
| 静态门禁 | `git diff --check`、两个新增/调整整链脚本 `node --check`、发布输入检查 | **通过**；没有远端 CI 或部署 |

失败与修正：新真实浏览器链在“撤销标识”定位处超时；查看可访问性快照发现 JSX 文本与表达式拼接去掉了预期空格，改为完整模板字符串后，新增原因输入/清空/提交撤销测试与整链通过。脚本对预先建立的 response promise 附加即时拒绝处理，使后续点击失败能正常进入已有进程清理，不增加任何产品重试。最终复核还收紧 UUID/namespace/time codec 的字符串结束锚点，拒绝末尾换行，新增三个浏览器负例通过；写入 schema 的版本上限调整到安全递增范围，与领域约束一致。

已查看 `asset-identity-dependency.png`：撤销冲突说明、命名空间/UUID 定位依据、import/postgres 来源、有效期、字段撤销入口和历史记录可读，无密钥。截图只覆盖当前滚动区域，完整交互由浏览器断言覆盖。脚本退出关闭自建平台/Runtime/Worker/IdP/Vite/Chromium，专用 PG/VM 仍保留 loopback；未修改其他应用进程。

限制：这只是人工核对登记→授权精确定位→固定 CMDB 导入 pin，不是通用来源自动 Resolver、连续多来源 presence、已有资产合并/alias/标识迁移或真实 CMDB API。尚无 tenant 总量/时间清理、生产迁移/容量验收；真实来源/模型/IdP/TLS/代理和人工评估仍缺。M2 粗估从 80% 调整为 85%，MVP 等权约 **84%（±5 个百分点）**，整体约 54%；100% 目标继续，未关闭 M1–M4。设计、协议与复现见 [ADR-032](adr/032-registered-asset-identity.md)、[资产身份契约](../contracts/asset-identity.md)、[本机验收](runbooks/asset-identity.md)。

## 39. 2026-09-26 持久模型费用准入、提供方用量与独立查询（追加）

继续当前 `main` 工作区，未分支、提交、推送或部署。按用户“暂未准备，先推进本地实现”，本轮补齐诊断调用前的费用预留、调用后 Token 用量、未知费用状态与独立查询。Java ai-control 保持纯领域；新增 V018 PostgreSQL 账本、固定三条 API、六类 canonical Schema/样例，Rust 使用受控平台接口，仍为四个启动单元。没有修改已发布 Skill 或添加模型工具权限。

平台按可信主体/租户、当前 Incident/实体授权、read-session 和运维策略签发一次许可。租户 advisory lock 保证多连接并发的单次/每日估算限额；runId 不可重复获得调用许可。费率版本/金额为原始策略快照，缓存输入保守按完整输入费率。未收到合法用量的记录到期后为 UNCERTAIN，跨 UTC 日和进程重开继续占用预留；不自动重试、退款或把未知记零。最多10000条/租户，暂无时间清理或核销。原8次OIDC读取/保存预算、4并发、65秒不变，额外各一次绑定当前run/session的reserve/report。

Rig适配器改用锁定0.42.0的raw Responses单次请求，并在业务输出解析/证据复核/保存前上报input/output/cached Token。即使输出无效也先记录可核验用量；提供方无用量、传输失败或上报失败保留预留。输入已计费、输出0的情况可上报。真实提供方仅允许经平台预算的platform-dev；旧demo不再允许付费模型。HTTP无重试/重定向/代理，3秒连接、18秒整体请求、256 KiB响应、输出2048 Token；SDK日志subscriber关闭以避免trace输出内容。付费接入必须PG及显式费率，真实HTTPS地址仅在运维配置中提供。

金额是配置估算，**不是提供方账单/严格第三方计费保证**。81920输入Token上界为当前73728字节固定文本请求的保守准入假设，尚无提供方精确预检；真实网关、税费、缓存折扣、服务等级、价格变更和账单对账仍需验收。协议桩使用虚构模型/密钥/费用，不进行任何外部付费调用。

| 检查 | 实际命令 / 方法 | 最终结果与边界 |
|---|---|---|
| 结构 / 契约 | Docker Python3.12 `.tmp/check-model-spend-final.sh`：静态检查、pytest contracts、发布输入和实际产物校验 | **451 passed；201结构化文件、6只读Tool**。6新Schema/样例、3操作OpenAPI，字段/身份/金额/状态/Token/末尾换行负例；静态扫描改为提前剪枝生成目录，避免遍历target/node_modules |
| 纯领域 | `node .tmp/domain-check.cjs`，Java21编译所有modules/tests/domain并运行main | **731项、27个main通过**。新ModelSpend33项覆盖整数进位、准入/存储总数上限、并发唯一许可、重复run、跨日未知、actor/session/tenant、不可变报告及原始时间 |
| Java / 两启动包 | `node .tmp/pipeline-test-env.cjs full`，专用真实loopback PG/VM、两个test/bootJar、`--rerun-tasks` | **149平台+20Worker=169tests，0 failures/errors/skipped；两个bootJar成功**。新增HTTP2、PG3、OIDC委托1；严格body/重复键/整数溢出、进程证明、mock预留/报告/GET、PG跨连接竞争/重开/跨日/原回执、固定两次费用委托 |
| Rust 默认 / 全feature / fmt | `node .tmp/rust-check.cjs`，workspace locked、`-j 1`；最终Schema收紧后复跑 | **默认40、all-features43通过，0 failed/ignored；fmt通过**。新增Rig真实loopback HTTP协议桩3项；扩展原workflow/adapter负例，覆盖无额度不调模型、报告先于无效输出/撤权、报告失败不保存、错误范围/费率/金额/输入回执、重定向/503/缺用量/超量无重试 |
| Web 类型 / 构建 / 全量回归 | Playwright `.tmp/playwright-local.config.ts`，webServer实际执行tsc/Vite生产构建 | **151 passed、构建通过**。新增12项用量/配置估算、未知预留、禁止把404当零、错金额/缓存/主体运行/字段换行、无自动模型调用、身份清除/迟到结果；原139项继续通过 |
| 开发模式完整链 | `node .tmp/pipeline-test-env.cjs browser` → checked-in `check_metrics_stack.mjs --pipeline --runtime` | **15个PASS、87个浏览器响应UUID/no-store/nosniff，0边界失败**。真实Java→Rust mock→PG费用预留/报告→AIInsight→网页读取费用/刷新原回执；资产标识/字段审核、指标、Incident、人工归属、流水线、选择恢复继续通过 |
| OIDC / 服务Worker完整链 | `node .tmp/oidc-stack.cjs --history-service` → checked-in `check_oidc_stack.mjs --history-service` | **10个PASS、18个浏览器响应边界通过**。新费用委托与Cookie受权GET通过；原服务JWT→Worker→PG/VM、撤权游标不动、Token503无回退、同client密钥轮换，以及BFF/CSRF/AIInsight/刷新/双标签退出通过 |
| 实际产物契约 | Docker末次脚本 Draft202012Validator+FormatChecker | **77份实际产物、49类顶层Schema通过**。原73份+HTTP预留/报告、dev完整链回执、OIDC完整链回执4份；都为合成数据。不把产物数当成独立E2E数 |
| 静态门禁 | `git diff --check`、两个整链脚本`node --check`、发布输入检查 | **通过**；仍在main、无远端CI/部署。Cargo通过offline本地锁文件解析增加已锁定bytes/reqwest0.13.5的直接依赖，未升级任何crate版本，随后locked验证 |

失败与修正：新Rig测试先因fixture输入字节数写错被模型调用前校验拦截；修正后协议测试通过。原平台HTTP测试桩未提供费用路由、随后费用回执storage与会话不一致，严格适配器正确拒绝；补齐桩并断言费用失败停在两次读取、正常保存经过四次读取后全量通过。最后收紧新Schema/Web的字符串结束锚点，拒绝digest/费率版本的末尾换行，并在最终全量契约/Rust/Web检查通过后记录本节。未把这些中间失败写成成功。

已查看完整`model-spend.png`：独立用量按钮、Token/费率版本/预留/当前占用、配置估算非账单、mock零调用，以及下方诊断和证据缺口均可读。开发Token输入为掩码，截图未暴露密钥。临时配置和产物留在忽略的.tmp，没有提交；脚本退出关闭自建平台/Runtime/Worker/IdP/Vite/浏览器，专用PG/VM保持loopback，未触碰其他应用。

M4粗估60%→70%，MVP等权84%→**86%（±5个百分点）**，整体约55%。100%目标继续；真实Zabbix、模型、IdP/TLS/代理及人工审阅尚缺，通用持续来源Resolver/presence、已有资产合并、保留数据生命周期、费用核销/账单对账/按Skill聚合仍未完成。见[ADR-033](adr/033-model-spend-admission.md)、[费用契约](../contracts/model-spend.md)、[费用验收runbook](runbooks/model-spend.md)。

## 40. 2026-09-26 AI 正文留存、显式分批清理与不可重用的旧运行（追加）

按用户选择继续当前 `main`，未新建分支、提交、推送或部署。新增默认关闭的可信租户留存策略、独立 `ai.retention.manage` 与全租户范围检查、120秒预览、人工确认清理和回执查询。V019 在现有 PostgreSQL 表清除已过期 AIInsight/Evidence 的正文，保留原主键、会话唯一、引用关系和最小授权元数据；超过策略期限的 Tool 读取审计可分批删除。成本账本不清理，旧 run 不能重新获得模型许可；清除后重新授权返回 410，改输入/主体复用旧 ID 冲突。四个启动单元和六个只读 Tool 不变。

策略文件有 256 KiB/100租户/每租户1000保留Incident上限，天数1–3650，每类一批最多100条。正文创建时间及过期时间均须严格早于截止，审计开始/最后完成时间及活跃会话也检查；保留列表按原始Incident精确排除。预览摘要绑定租户、actor、策略和实际候选；PG事务锁中重算、清除、写原回执，提交前重读策略并检查时间。配置文件与数据库没有共同锁；在途请求采用提交前检查时的策略，紧急保留需停入口并等待在途结束。回执原actor同键同请求可回读，最多10000条/租户；未知结果不自动重试或继续下一批。

| 检查 | 实际命令 / 方法 | 最终结果与范围 |
|---|---|---|
| 结构 / 契约 | Docker Python3.12 `.tmp/check-retention-final.sh`，静态/pytest contracts/发布输入/实际产物 | **475 passed；209个结构化文件、6个只读Tool**。4新Schema/样例、3条OpenAPI、权限枚举同步；额外身份/执行字段、日期/digest、天数/批量、保留列表和响应负例 |
| 纯领域 | `node .tmp/domain-check.cjs`，Java21编译全部modules/tests/domain并运行main | **755项、28个main通过**。新24项覆盖保留与严格截止、预览时限、actor/tenant/策略摘要、非法预算、全租户权限及保留标记 |
| Java / 两个启动包 | `node .tmp/pipeline-test-env.cjs full`，自有loopback PG/VM，两个test/bootJar，`--rerun-tasks` | **159平台+20Worker=179 tests，0 failures/errors/skipped，两个bootJar成功**。新增PG5、HTTP3、文件1、普通诊断权限拒绝1 |
| 真实 PG 生命周期 | `PostgresAiRetentionIT`，包含于Java总数 | **5项通过**。过期合成正文置空、审计删除、原引用/会话/成本保留，原run拒绝再调用，撤销指标权限仍拒读；保留Incident/预览模式/配置改变整批回滚，两个连接并发同键只一回执，批量/hasMore/过期和边界、先清证据后清结果、跨租户隐藏 |
| HTTP 与配置 | `AiRetentionHttpIT`、`FileAiRetentionPoliciesTest`及原`ModelSpendHttpIT`增量 | **5项通过**。真实Spring/PG预览→显式空批→原回执，配置关闭后原结果回读，策略变化/摘要篡改/身份覆盖/重复键/未知query拒绝；缺授权、预览专用策略、缺失/损坏/过量配置失败关闭；普通诊断权限不授予清理 |
| Rust 默认 / 全features / fmt | `node .tmp/rust-check.cjs`，workspace locked、`-j 1` | **40/43通过，0 failed/ignored；fmt通过**。未改Rust或新增动作Tool；真实模型未调用 |
| Web 类型 / 构建 / 回归 | Playwright `.tmp/playwright-local.config.ts`，webServer实际执行tsc/Vite生产构建 | **最终全量160 passed，构建通过**。新9项：显式预览/勾选/一次提交、三类数量、预览专用策略、错误摘要/tenant/预算/字段/过期、未知结果原回执查询和退出/迟到清理。修正展示后另跑9项专项，再跑最终全量 |
| 开发模式完整链 | `node .tmp/pipeline-test-env.cjs browser` → `check_metrics_stack.mjs --pipeline --runtime` | **最终16个PASS、90个响应UUID/no-store/nosniff，0边界失败**。真实浏览器→Java/PG预览→显式空批回执→刷新重授权回读；新数据不清除、三类数量必须可见。实际老正文清除由PG测试覆盖，不把空批冒充删除验收；既有全链继续通过 |
| Worker / OIDC完整链 | `node .tmp/oidc-stack.cjs --history-service` | **10个PASS、18个浏览器响应边界通过**。原service JWT/受限Worker/PG/VM、撤权游标不动、token故障不回退、密钥轮换，以及BFF/CSRF/Runtime费用委托/诊断/退出全部回归。新留存管理使用独立权限，不加入Runtime委托 |
| 实际产物契约 | Draft202012Validator + FormatChecker，末次Docker脚本 | **83份实际产物、51类顶层Schema通过**。原77份追加PG实际删除、HTTP空批及浏览器空批的preview/receipt各一对；无密钥/真实客户载荷，不等于83条独立E2E |
| 静态门禁 | `git diff --check`、两个整链脚本`node --check`、发布输入检查 | **通过**；仍为main，无staged变更，无远端CI/部署、依赖升级或生成目录入库 |

中间失败与修正均保留：最初领域导入`java.security.*`使Principal/Permission名字冲突，改为明确的散列类型导入后编译通过。PG负例最初预期ToolFailure，但缺少entity.read时Incident层会先拒绝；补齐该权限以单独验证旧元数据的metric授权。OpenAPI最初遗漏关联头声明，契约检查失败后补齐。浏览器桩glob未匹配子路径，改为完整路径正则。截图发现清理数量为空，追加断言复现：当前Zeus编译器的For子项使用block-bodied renderer未输出行，改为与既有页面一致的表达式renderer后专项9项、全量160项和整链通过；未改框架或跳过断言。一次整链失败在待响应promise上超时，补即时拒绝处理以让主流程按既有finally清理，并在前端构建完成后串行复跑成功。产品未加自动重试。

已查看最终完整`ai-retention.png`：管理说明、原请求标识、完成时间及三类数量/字节均可读，开发凭据为掩码。验收数据是自有随机租户和明确合成来源；脚本关闭自建平台/Runtime/Worker/IdP/Vite/Chromium，专用PG/VM继续仅loopback；忽略的.tmp保留报告/临时文件，未提交敏感材料。

本轮不清除其他业务数据、会话/保留标记/引用/费用元数据，也没有生产定时调度、tenant总容量限制、WAL/备份/磁盘物理擦除验收。逻辑字节不等于释放磁盘空间。真实Zabbix、模型、IdP/TLS/代理与人工诊断审阅仍缺；通用持续来源Resolver/presence、已有资产合并和费用账单核销继续未完成。M4粗估70%→80%，MVP86%→**88%（±5个百分点）**，整体约56%；100%目标继续，未关闭M1–M4。见[ADR-034](adr/034-ai-content-retention.md)、[契约](../contracts/ai-retention.md)、[本机操作说明](runbooks/ai-content-retention.md)。

## 41. 2026-09-26 Host 扫描来源租约、过期接管与旧写入隔离（追加）

继续用户指定的当前 `main` 工作区，无新分支、提交、推送或部署。检查持续来源接入基础时发现：原实体锁不能阻止较旧完整扫描在新扫描后执行过时的缺失对账。本轮为 Host 库存写入增加 V020 持久来源租约，按可信 tenant/source/type 隔离；30秒续租、5分钟不可延长截止、单调 fence。PG 来源锁/数据库时间和库存写入在同一短事务，所有阻塞写完成后再检查时效，过期则回滚实体/观测/下线变化。旧扫描也不能释放新持有者。

Host 用例在来源读取前后、记录处理前续租，通过受保护的 upsert/finish 写库存；不持有数据库连接等待来源 HTTP，没有隐藏重试或自动恢复。租约不经过请求或模型，原权限、固定映射版本和明确 fixture 标记保留。接口新增四个稳定失败码，并修正 CHECKPOINT_FAILED 摘要：状态失败可能发生在库存已提交后，不能保证已提交对账被撤销。

| 检查 | 实际命令 / 方法 | 最终结果与边界 |
|---|---|---|
| 结构 / 契约 | Docker Python3.12 `.tmp/check-source-scan-final.sh`，静态/pytest contracts/发布输入/实际产物 | **491 passed；212个结构化文件、6个只读Tool**。新增Host失败Schema/样例与15项负例/范围测试，禁止额外身份/fence/租约、伪完整快照、无运行的阶段失败、计数越界和非法码 |
| 纯领域 | `node .tmp/domain-check.cjs`，Java21编译全部modules/tests/domain并运行main | **788项、29个main通过**。新SourceScan33项：来源占用无连接器调用、tenant/source/type隔离、30秒边界/续租/5分钟截止、fence上限、旧释放不影响新持有者；实际暂停旧线程后让新扫描完成，再恢复旧线程，旧非空页和空完成页都不能覆盖/下线新资产 |
| Java / 两个启动包 | `node .tmp/pipeline-test-env.cjs full`，自有真实loopback PG/VM，两个test/bootJar，`--rerun-tasks` | **165平台+20Worker=185 tests，0 failures/errors/skipped；两个bootJar成功**。最终HTTP/摘要修改后重跑全量 |
| 真实 PG 所有权 | `PostgresSourceScanIT`，包含于Java总数 | **5项通过**。独立适配器/连接竞争只有一个持有者，重建适配器仍读到租约；测试专用租约过期后接管，旧写入/对账/释放无效，独立写接口不能绕过；tenant/source/type隔离；持有真实实体行锁使写入/下线等待，再让租约过期，释放行锁后整事务回滚，实体仍ACTIVE、原版本与单条Observation保留 |
| HTTP | `PipelineHttpIT`新增1项，包含于Java总数 | **通过**。持有来源租约时POST返回503/SOURCE_SCAN_BUSY/零进展/失败SyncRun，无tenant/fence/lease字段；释放后新的显式请求成功；客户端提交fence/leaseUntil返回400。输出实际失败JSON供Schema验证 |
| Rust 默认 / 全features / fmt | `node .tmp/rust-check.cjs`，workspace locked、`-j 1` | **40/43通过，0 failed/ignored；fmt通过**。没有Rust产品改动或真实模型调用 |
| Web 类型 / 构建 / 回归 | Playwright `.tmp/playwright-local.config.ts`，webServer实际执行tsc/Vite生产构建 | **160 passed，构建通过**。本轮无新页面或浏览器测试；既有同步/字段审核/身份/费用/留存等继续回归 |
| 开发模式完整链 | `node .tmp/pipeline-test-env.cjs browser` → checked-in `check_metrics_stack.mjs --pipeline --runtime` | **16个PASS、90个响应UUID/no-store/nosniff，0边界失败**。真实Java/PG的多次Host同步使用新租约，固定字段审核后主来源重采/撤销、指标/告警/Runtime mock/AIInsight/费用/留存/流水线继续通过 |
| OIDC / Worker完整链 | `node .tmp/oidc-stack.cjs --history-service` | **10个PASS、18个浏览器响应边界通过**。身份协议fixture→服务JWT/Worker→PG/VM、撤权/故障游标保持、密钥轮换，以及Cookie/CSRF/受限Runtime/诊断/退出回归 |
| 实际产物 | 最终整链后 Draft202012Validator + FormatChecker | **84份产物、52类顶层Schema通过**。原83份加1份真实HTTP占用失败，都是自有合成数据，不等于84条独立E2E |
| 静态门禁 | `git diff --check`、发布输入检查、当前分支/暂存/生成目录检查 | **通过**；main、无staged变更，无新依赖升级或生成目录入库 |

首次领域编译、Java/PG回归即通过；随后补HTTP实际产物、失败契约和更准确的状态失败摘要，再执行最终全量检查。未跳过不稳定测试，也未把尚未运行项写为成功。PG到期测试仅缩短随机测试租户的 lease_until，保留真实数据库时间与 token 的不可变开始/截止；用真实阻塞行锁验证提交前过期，不依赖只检查方法调用的Mock。两个完整链结束关闭各自平台/Runtime/Worker/IdP/Vite/浏览器，自有PG/VM继续只在loopback；无真实客户或外部提供方调用。

保留边界：该切片仅覆盖 Host 库存扫描，不能替代 Item/Problem 所有权或全平台HA。上游 offset 漂移仍可能漏项，完成标记仍是offset-scan-attempt；已经提交的部分记录不全局回滚。进程终止可能遗留RUNNING元数据，过期接管不自动修复旧状态。迁移需要停止旧版本写进程，生产迁移/多主机故障切换尚未验收。通用持续来源Resolver/presence、已有实体合并、数据总量、真实Zabbix/模型/IdP/TLS/人工审阅仍未完成。

这是原同步正确性修复，**MVP保持约88%（±5个百分点），整体约56%**，不按测试数量提高进度；100%目标继续。见 [ADR-035](adr/035-host-scan-fencing.md)、[Host契约](../contracts/host-scan.md)、[操作说明](runbooks/host-scan.md)。

## 42. 2026-09-26 已登记来源快照、原子观测与第二来源确认（追加）

继续当前 `main` 工作区，无新分支、提交、推送或部署。遵循用户“暂未准备，先推进本地实现”：在已有 PostgreSQL/资产 UUID 登记/字段审核基础上新增显式 CMDB 快照导入。固定来源和命名空间由可信配置给定，只允许全租户 entity.read/entity.manage/source.sync；请求不能覆盖 tenant、actor、source、namespace、权限或租约。最多100条、HTTP128KiB、浏览器64KiB，观测须在过去7天内且同来源新请求时间严格递增。

固定引擎按已登记 UUID 定位已有资产；一笔有界PG事务保存不可变输入回执、Observation、PENDING字段审核与来源确认。未知标识、绑定冲突或失败整批回滚；不按名字/IP合并，不自动采用字段。完整快照才对该来源已知绑定标缺失，连续缺失刷新确认时间，部分批次不对账。仍新鲜且登记有效的第二来源可保护主来源已缺失的资产；到期/登记撤销在读取筛选和分页前撤销保护，不依赖后台清理。人工字段接受与新快照共同检查两方向绑定冲突。

| 检查 | 实际命令 / 方法 | 最终结果与边界 |
|---|---|---|
| 结构 / 契约 | Docker Python3.12 `.tmp/check-snapshot-final.sh`，静态/pytest contracts/发布输入/实际产物 | **519 passed；220个结构化文件、6个只读Tool**。新增四类Schema/样例及24项测试；未知身份上下文、非法UUID/字段、越界数量、伪live模式、引擎/摘要等拒绝 |
| 纯领域 | `node .tmp/domain-check.cjs`，Java21编译全部modules/tests/domain并运行main | **804项、30个main通过**。新SourceSnapshot16项：重复标识/外部对象、数量与字段限制、新鲜时间边界、回执对应和缺失/过期/撤销状态；过期的缺失确认也明确为STALE |
| Java / 两个启动包 | `node .tmp/pipeline-test-env.cjs full`，自有真实loopback PG/VM，两个test/bootJar，`--rerun-tasks` | **175平台+20Worker=195 tests，0 failures/errors/skipped；两个bootJar成功**。最后绑定检查/连续缺失修正后再次全量执行 |
| 真实 PG 来源快照 | `PostgresSourceSnapshotIT`，包含于Java总数 | **6项通过**。登记解析、字段仅待审、主Host缺失但第二来源新鲜时仍ACTIVE；部分空批保持、完整空批缺失、连续缺失更新时间、重新出现及人工字段接受；未知/旧批不部分写；同请求并发与重建适配器回执一致；身份撤销/过期在生命周期筛选前生效；新旧入口均拒绝外部对象或实体绑定冲突 |
| HTTP / 权限 | `SourceSnapshotHttpIT`两项、`SourceSnapshotBoundaryTest`一项及`SourceSyncDeniedIT`新增一项，包含于Java总数 | **通过**。实际配置/提交/同键回执/来源状态、未自动改字段、401/404及无额外身份参数；重复JSON键/尾随JSON/数组/128KiB越界/未知字段拒绝；对象范围受限的Principal即使具备三项权限也不得整源管理 |
| Rust 默认 / 全features / fmt | `node .tmp/rust-check.cjs`，workspace locked、`-j 1` | **40/43通过，0 failed/ignored；fmt通过**。本轮没有Rust产品改动或真实模型调用 |
| Web 类型 / 构建 / 回归 | Playwright `.tmp/playwright-local.config.ts`，webServer实际执行tsc/Vite生产构建 | **170 passed，构建通过**。新10项涵盖显式提交与会话清理、回执tenant/解析目标/时效/模式复核、未知结果404不重发、重复身份输入、显式来源读取、迟到配置丢弃；本地时间校验失败后表单仍可编辑 |
| 开发模式完整链 | `node .tmp/pipeline-test-env.cjs browser` → checked-in `check_metrics_stack.mjs --pipeline --runtime` 及 `lib/check_source_snapshot.mjs` | **17个PASS、96个响应UUID/no-store/nosniff，0边界失败**。实际浏览器→Java→PG登记解析/快照→待审字段/来源状态；刷新后显式重授权查询原回执；凭据清除使状态消失。原指标/Incident/流水线/Runtime mock/AIInsight/费用/留存链继续通过 |
| OIDC / Worker完整链 | `node .tmp/oidc-stack.cjs --history-service` | **10个PASS、18个浏览器响应边界通过**。协议fixture→独立服务JWT/Worker→PG/VM，撤权/故障游标保持、密钥轮换；Cookie/CSRF/有界Runtime/诊断/退出回归。最后Java修正后重新执行 |
| 实际产物 | 最终整链后 Draft202012Validator + FormatChecker | **93份产物、56类顶层Schema通过**。原84份加PG回执1份、真实HTTP4份、实际浏览器4份；输入/配置/回执/来源状态均为自有合成数据，不等于93条独立E2E |
| 静态门禁 | `git diff --check`、发布输入检查、当前分支/暂存/生成目录检查 | **通过**；main、无staged变更，无本轮依赖升级或生成目录入库 |

中间修正：最初Controller的Permission通配导入歧义导致编译失败，改为明确API导入；首次PG同键回执解码暴露Jackson数字节点类型不一致，改用相同JSON解析路径；首次HTTP测试因合成dev token不足32字符未启动，修正测试配置后运行。追加范围/尾随JSON/表单预校验负例并重跑。完整链后交叉检查发现人工字段入口可能与快照绑定矛盾，以及重复缺失未刷新确认时间，补齐共享绑定检查和真实PG断言，再重跑Java/bootJar及两条完整链。最终没有失败或跳过，不将编译或测试编写当作通过。

快照和来源状态截图已实际查看：输入、明确import/未连接说明、待审记录、来源状态与判断/到期时间均可读，凭据保持掩码。到期PG测试只调整自有随机租户的测试行，不修改全局数据库时钟；浏览器链只提交合成输入。测试日志分别保存在忽略的 `.tmp/snapshot-java-binding-verified.log`、`snapshot-domain-verified.log`、`snapshot-web-verified.log`、`snapshot-rust.log`、`snapshot-chain-final.log`、`snapshot-oidc-final.log` 和 `snapshot-contracts-verified.log`。完整链已关闭自建平台/Runtime/Worker/IdP/Vite/浏览器，自有PG/VM继续仅loopback；没有实际客户或外部提供方调用。

保留边界：这是显式人工导入适配，未连接真实CMDB API、未后台轮询，complete是有整源权限操作者的声明，不证明上游一致快照。每tenant/source保留绑定100个、回执1000份，无自动清理/全平台总容量治理；原输入为规范化字段，不声称保留厂商原始字节。绑定更正、已有Entity合并/alias、自动字段批准、主来源TTL及通用多源权威策略未实现。查询到期不伪造来源事件或增加实体版本；主Zabbix offset漂移、Item/Problem所有权、生产迁移/HA仍未验收。迁移需停止旧writer。真实Zabbix/模型/IdP/TLS/人工诊断审阅仍缺。

M2工程估算85%→90%，**MVP约89%（±5个百分点），整体约57%**；依据是补齐固定来源解析与生命周期保护能力，不是测试数量。M1–M4退出门槛仍未全部通过，MVP100%目标继续。下一步继续来源冲突追溯与受审计的绑定更正。见 [ADR-036](adr/036-registered-source-snapshots.md)、[契约](../contracts/source-snapshots.md)、[操作说明](runbooks/source-snapshots.md)。

## 43. 2026-09-26 来源绑定更正、历史归属保留与有界更正历史（追加）

继续当前 `main` 工作区，无新分支、提交、推送或部署。遵循用户“暂未准备，先推进本地实现”：第42节的已登记来源快照只允许新增观测，一旦人工发现绑定错位，就没有在保留历史归属的前提下纠正当前绑定的办法。本节补充受审的人工绑定更正。固定来源与命名空间仍由可信配置给定，只允许全租户 entity.read/entity.manage/source.sync；请求不能覆盖 tenant、actor、来源、命名空间或权限。

更正必须同时给出原 snapshotId、目标已登记 UUID、双方实体版本、晚于原快照的观测时间和原因；字段只允许 name/ip/owner/environment，至少一项且一律先进入 PENDING 审核。一笔有界 PG 事务对来源、双方实体与绑定加锁并复核版本、pin 与来源关系：原绑定存在生效字段时必须先撤销（否则 409），未知结果、版本或 pin 不符、目标非法或事务中任一失败都整笔回滚。历史 Observation、快照、指标与 Incident 引用不迁移，原快照仍归属原实体。更正回执按原 actor 幂等，最多保留 1000 份并共享快照预算；查询按原请求取回原结果，查询与更正历史分页都需要整源管理权限。Web 提供原/目标预览、明确确认勾选、未知结果回执查询与有界更正历史。

| 检查 | 实际命令 / 方法 | 最终结果与边界 |
|---|---|---|
| 结构 / 契约 | Docker Python3.12 `.tmp/check-correction-final.sh`（静态检查 + pytest contracts + 发布输入 + 实际产物校验） | **542 passed；226 个结构化文件、6 个只读 Tool**。较第42节新增 3 类 Schema（输入/回执/页面）与样例共 6 个结构化文件、23 项契约测试；V022 发布输入检查通过 |
| 纯领域 | `node .tmp/domain-check.cjs`，Java21 编译全部 modules/tests/domain 并逐个运行 main | **819 项、31 个 main 通过**。新增 `SourceBindingCorrectionSmoke` 15 项：新观测晚于原快照、原 snapshotId/双方版本/目标 pin、字段与原因限制、未知结果与原 actor 幂等 |
| Java / 两个启动包 | `node .tmp/pipeline-test-env.cjs full`，自有真实 loopback PG/VM，两个 test 与 bootJar，`--rerun-tasks` | **183 平台 + 20 Worker = 203 tests，0 failures/errors/skipped；两个 bootJar 成功**。完整链使用该次构建的 `platform-api-0.1.0-SNAPSHOT.jar` |
| 真实 PG 绑定更正 | `PostgresSourceBindingCorrectionIT` 6 项；`SourceSnapshotHttpIT` 4 项与 `SourceSnapshotBoundaryTest` 1 项回归（均含于 Java 总数） | **全部通过**。事务内来源/实体/绑定锁与版本复核、原生效字段未撤销拒绝、失败整笔回滚、原快照归属不变、同 actor 幂等回执、未知与越权查询拒绝 |
| Rust 默认 / 全 features / fmt | `node .tmp/rust-check.cjs`，workspace locked、`-j 1` | **40/43 通过，0 failed/ignored；fmt 通过**。本轮没有 Rust 产品改动或真实模型调用 |
| Web 类型 / 构建 / 回归 | `apps/web-console` 的 `npm run build`（tsc --noEmit + Vite 生产构建）与 Playwright `.tmp/playwright-local.config.ts` | **179 passed，构建通过（78 modules，dist 产物生成）**。新增 9 项更正页测试：预览/明确确认/回执查询/有界历史、会话清理与非法输入 |
| 开发模式完整链 | `node .tmp/pipeline-test-env.cjs browser` → checked-in `check_metrics_stack.mjs --pipeline --runtime` 与 `lib/check_source_binding_correction.mjs` | **18 个 PASS；106 个响应边界检查 0 失败**（`.tmp/metrics-acceptance/request-boundary.json` 记录 `checkedResponses: 106, failures: 0`）。实际浏览器→Java→PG：更正 + 新待审观测 + 原快照归属不变 + 回执/历史刷新；凭据仅在内存 |
| OIDC / Worker 完整链 | `node .tmp/oidc-stack.cjs --history-service` | **10 个 PASS、18 个浏览器响应边界通过**。撤权/故障游标/密钥轮换与既有诊断、委托、Cookie/CSRF 回归 |
| 实际产物 | 最终整链后 Draft202012Validator + FormatChecker | **100 份产物、59 类顶层 Schema 通过**。新增 7 份：PG 回执 1、真实 HTTP 3、实际浏览器 3；均为自有合成数据，不等于 100 条独立 E2E |
| 静态门禁 | `git diff --check`、当前分支与暂存检查 | **通过**；`main`、无 staged 变更，无本轮依赖升级或生成目录入库 |

环境与检查工具修正：首次整链在 `check_metrics_stack.mjs` 的 Runtime readiness 处报 `Runtime exited before readiness`，而该脚本用 `stdio: 'ignore'` 丢弃了 Runtime 的 stderr。先让脚本把 Runtime stderr 带进断言信息，再用两个探针确认同一二进制在平台 URL 不可达与可达两种情况下都能 `/readyz` 就绪，排除“平台在线导致退出”的假设。最终定位为本机 Docker Desktop 停止后遗留的 AF_UNIX socket 无法删除，导致引擎自身启动失败（`backend.error.json` 记录 `remove …/dockerInference: The file cannot be accessed by the system`）。把两个残留目录改名留档、重启引擎并重建 loopback PG/VM 后，完整链一次通过。这是本机开发环境修复，不改变产品行为；stderr 捕获保留在脚本里，便于下次直接定位。

更正与历史截图已实际查看：`.tmp/metrics-acceptance/source-correction-preview.png` 与 `source-correction-history.png` 显示原资产/原快照/目标 UUID/观测时间/字段 JSON/原因、预览摘要（原确认 PRESENT、原资产版本 10、目标版本 3）、未勾选确认时按钮禁用，以及更正历史（原快照及其历史归属保留、目标版本 4、新待审字段记录、原 actor 与时间）。凭据保持掩码。日志保存在忽略的 `.tmp/correction-java-verified.log`、`correction-domain.log`、`correction-contracts-final.log`、`correction-ts-final.log`、`correction-web-verified.log`、`correction-rust.log`、`correction-chain.log` 和 `correction-oidc.log`。自有 PG/VM 仅 loopback，本轮没有真实厂商或外部提供方调用。

保留边界：这是显式人工更正，不自动纠错、不自动批准字段、不做已有 Entity 合并/alias 或历史诊断重算。原生效字段必须先撤销，原 Observation/指标/Incident 归属不迁移，因此“当前绑定”与历史归属长期不一致是有意设计，而不是未清理的脏数据。没有厂商 CMDB/Zabbix API、后台轮询、主来源 TTL 或通用多源权威策略；每 tenant/source 保留 1000 份回执且无自动清理与全平台容量治理，迁移需停止旧 writer。真实 Zabbix/模型/IdP/TLS/代理与人工诊断抽样审阅仍未验收；Runtime 仍是同机 `platform-dev` 受限委托，模型为显式 mock。查询到期不伪造来源事件或增加实体版本。

M2工程估算保持90%，**MVP约89%（±5个百分点），整体约57%**；本节补齐来源链的可审计更正能力，不据此提高阶段估算。M1–M4退出门槛仍未全部通过，MVP100%目标继续。下一步核对 M0–M4 本地退出缺口，继续来源失败/冲突追溯与只读诊断异常场景评估。见 [ADR-037](adr/037-source-binding-correction.md)、[契约](../contracts/source-binding-corrections.md)、[操作说明](runbooks/source-binding-correction.md)。

## 44. 2026-09-26 来源扫描运行的可追溯只读查询（追加）

继续当前 `main` 工作区，无新分支、提交、推送或部署。遵循用户“暂未准备，先推进本地实现”：第41–43节让扫描具备租约、fencing、快照与更正能力，但同步失败只把 `syncRunId` 写进响应——运行记录（状态、游标、页数与计数、失败码、启动时钉住的映射版本）虽已持久化，却没有任何读取入口，操作员事后无法回答“上次扫描停在哪、为什么停、有没有对账”。本节补齐**只读**追溯，不新增服务、队列、调度或动作工具。

Host 与 Item 各两个 GET：最近运行分页与按 `syncRunId` 单条读取。租户来自可信 Principal，来源来自配置，权限沿用源级 `source.sync`（只有 `entity.read` 会被 403）；跨租户、跨来源、跨对象类型与未知标识一律 404，未知查询参数、越界 `limit`、客户端构造的游标一律 400。运行按 `started_at DESC, id DESC` 排序，`limit` 1–50（默认 20），`after` 为服务端签发的不透明游标；存储层取 `limit + 1` 行以区分“恰好取满”与“还有下一页”，V023 只增加一条 `(tenant_id, source_instance_id, object_type, started_at DESC, id DESC)` 读取索引，不新增表、列或数据。

| 检查 | 实际命令 / 方法 | 最终结果与边界 |
|---|---|---|
| 结构 / 契约 | Docker Python3.12 `.tmp/check-scanrun-final.sh`（静态检查 + pytest contracts + 发布输入 + 实际产物） | **590 passed；232 个结构化文件、6 个只读 Tool**。较第43节新增 3 类 Schema（运行/列表/单条）与样例共 6 个结构化文件、48 项契约测试；用例覆盖信封拒绝未知字段与 live/import 模式、limit 与游标边界、items 上限、状态与完成度不变量、失败码闭集、多行失败摘要拒绝、钉住版本引用形状 |
| 纯领域 | `node .tmp/domain-check.cjs`，Java21 编译全部 modules/tests/domain 并逐个运行 main | **856 项、32 个 main 通过**。新增 `SourceScanRunQuerySmoke` 37 项：无 `source.sync` 与空来源拒绝、对象类型与 limit/游标边界、租户/来源/对象类型隔离、`limit + 1` 分页与游标续读不重复、失败码只从稳定前缀还原、未知文本不回显、钉住版本只在存在时出现、游标保留微秒精度 |
| Java / 两个启动包 | `node .tmp/pipeline-test-env.cjs full`，自有真实 loopback PG/VM，两个 test 与 bootJar，`--rerun-tasks` | **188 平台 + 20 Worker = 208 tests，0 failures/errors/skipped；两个 bootJar 成功**。完整链使用该次构建的 `platform-api-0.1.0-SNAPSHOT.jar`（13:38:39） |
| 真实 PG 追溯 | `PostgresSourceScanRunIT` 2 项（含于 Java 总数） | **通过**。真实 PG 上的倒序/游标分页与租户/来源隔离；成功扫描 `SUCCEEDED`+`snapshotComplete` 且带钉住版本；随后一次真实失败的 Host 同步可被读回为 `FAILED`、0 页 0 抓取、失败码为 `SOURCE_FETCH_FAILED` 且摘要为固定文案，租户 ACTIVE 实体数不变（失败不对账、不删除） |
| HTTP / 权限 | `SourceScanRunHttpIT` 2 项与 `SourceScanRunDeniedIT` 1 项（含于 Java 总数） | **通过**。实际列表/单条读取、`no-store`/`nosniff`、item 与 host 互不可见；401 未认证、403 仅 `entity.read`、400（`limit=0/51/abc`、`after=***`、未知参数、重复参数、非法 UUID、单条读取带分页参数）、404（未知标识、跨类型标识）；存储中出现 `CUSTOM: …` 等未知失败文本时响应不含 `failureCode` 且不回显原文 |
| Rust 默认 / 全 features / fmt | `node .tmp/rust-check.cjs`，workspace locked、`-j 1` | **40/43 通过，0 failed/ignored；fmt 通过**。本轮没有 Rust 产品改动 |
| Web 类型 / 构建 / 回归 | `apps/web-console` 的 `pnpm typecheck`、`pnpm build` 与 Playwright 全量 | **190 passed（新增 11 项），构建通过（80 modules）**。新页面 `来源扫描` 支持 Host/Item、10/20/50、显式读取、服务端游标翻页、按标识查询、空结果与 400/403/404 提示、身份变更清空；客户端拒绝未知枚举、`SUCCEEDED` 缺完成度、`RUNNING` 带完成时间与未知失败码等非法响应 |
| 开发模式完整链 | `node .tmp/pipeline-test-env.cjs browser` → checked-in `check_metrics_stack.mjs --pipeline --runtime` 与 `lib/check_source_scan_runs.mjs` | **19 个 PASS；110 个响应边界检查 0 失败**（`.tmp/metrics-acceptance/request-boundary.json` 记录 `checkedResponses: 110, failures: 0`）。实际浏览器→Java→PG 读取该次真实 Host 同步的运行（含钉住版本），item 追溯单独成立且不混入 host 运行，凭据清除后结果消失 |
| OIDC / Worker 完整链 | `node .tmp/oidc-stack.cjs --history-service` | **10 个 PASS、18 个浏览器响应边界通过**。撤权/故障游标/密钥轮换与既有诊断、委托、Cookie/CSRF 回归 |
| 实际产物 | 最终整链后 Draft202012Validator + FormatChecker | **105 份产物、62 类顶层 Schema 通过**。新增 5 份：真实 HTTP 3（列表/单条/未知失败文本）、实际浏览器 2（列表/单条）；均为自有合成数据，不等于 105 条独立 E2E |
| 静态门禁 | `git diff --check`、当前分支与暂存检查 | **通过**；`main`、无 staged 变更，无本轮依赖升级或生成目录入库 |

中间修正：首次链路检查假设 item 追溯为空，但同一次验收本来就会做 item 同步，因此改为断言“item 运行单独成立、host 运行不出现在 item 追溯里”，并让浏览器断言 item 行存在且 host 行缺席；`.tmp/check-scanrun-final.sh` 最初把失败运行产物按不存在的 schema 校验，实际它是单条读取信封，改为按 `source-scan-run-read` 校验。前端首轮 e2e 与类型检查暴露的非法响应处理（未知枚举、完成度不一致、未知失败码）改为拒绝渲染而不是显示可疑数据。两次失败都是检查脚本/测试自身的问题，修正后完整链与契约链一次通过。

追溯截图已实际查看：`.tmp/metrics-acceptance/source-scan-runs.png` 显示 `acceptance-… · zabbix-1 · host · postgres · 本页 2 条`、两条 `SUCCEEDED` 运行的时间/游标/页数/抓取/采纳/拒绝/完整快照与 `labeled-fixture`、钉住映射版本 `zabbix-host-default` 修订 1 与摘要、禁用的“下一页扫描运行”，以及按标识读取面板。凭据保持掩码。日志保存在忽略的 `.tmp/scanrun-java-full.log`、`scanrun-domain.log`、`scanrun-contracts-final.log`、`scanrun-web-full.log`、`scanrun-web-focused.log`、`scanrun-rust.log`、`scanrun-chain.log` 与 `scanrun-oidc.log`。

保留边界：追溯不是修复——它不重试、不续租、不对账、不清理，也不会把遗留的 `RUNNING` 元数据改成失败或成功；第41节记录的进程终止遗留状态仍然存在。它不返回厂商原始报文或 Raw 载荷，不提供跨租户/跨来源的全局运行检索，运行表本身没有自动清理或总量配额（只有单次读取上限 50）。真实链总会有运行记录，“空追溯”状态只由 mock 的 Web 用例覆盖。真实 Zabbix/模型/IdP/TLS/代理与人工诊断抽样审阅仍未验收；Runtime 仍是同机 `platform-dev` 受限委托，模型为显式 mock。

M2工程估算保持90%，**MVP约89%（±5个百分点），整体约57%**；本节补齐来源扫描链的可观测性，属可追溯性补强而不是退出门槛本身，因此不据此提高阶段估算。M1–M4退出门槛仍未全部通过，MVP100%目标继续。下一步继续来源失败/冲突追溯与只读诊断异常场景评估，并在真实环境可用时先跑连接自检。见 [ADR-038](adr/038-source-scan-run-trace.md)、[契约](../contracts/source-scan-runs.md)、[操作说明](runbooks/source-scan-runs.md)。

## 45. 2026-09-26 Item 扫描来源租约与受围栏对账（追加）

继续当前 `main` 工作区，无新分支、提交、推送或部署。遵循用户“暂未准备，先推进本地实现”：第41节给 Host 扫描加了持久来源租约、单调 fence 与事务内对账，但 Item 扫描一直缺这一层——它会 `retireMissing` 把本来源缺失的指标绑定置为 `INACTIVE`，却既不取租约也不复核所有权。两个并发的 Item 扫描可以交错写入，一个已过期或被接管的旧扫描还能按自己那份更旧的 presence 对账，把新扫描刚观测到的绑定错误下线；这类错误只会在指标静默消失时才被发现。

本节让 Item 扫描复用同一套来源租约（scope 的 `externalType` 为 `item`），不新增表、服务、队列或调度：取到租约才发第一个来源请求，每页开始前续租，目录/绑定写入与缺失对账都经新的 `SourceItemWritePort` 携带租约提交，结束时在 `finally` 中只释放与自己 token 完全一致的租约。PostgreSQL 适配器在同一事务里按 scope 取咨询锁、用数据库时钟校验租约、写目录/绑定或执行对账、结束前复查时间并续租，任何一步失败整笔回滚；租约失败映射为既有的稳定码 `SOURCE_SCAN_BUSY/LOST/DEADLINE/LIMIT`。内存适配器执行真实租约校验但显式标注为非事务性（开发实现）。

| 检查 | 实际命令 / 方法 | 最终结果与边界 |
|---|---|---|
| 结构 / 契约 | Docker Python3.12 `.tmp/check-scanrun-final.sh`（静态检查 + pytest contracts + 发布输入 + 实际产物） | **590 passed；232 个结构化文件、6 个只读 Tool**。本节不新增 Schema：Item 失败响应沿用既有失败形状与封闭失败码集合，实际产物仍为 105 份/62 类 |
| 纯领域 | `node .tmp/domain-check.cjs`，Java21 编译全部 modules/tests/domain 并逐个运行 main | **885 项、33 个 main 通过**。新增 `ItemScanOwnershipSmoke` 29 项：外部持有者使扫描返回 `SOURCE_SCAN_BUSY` 且不写不删；租约在分页中途过期时受围栏写入被拒绝、失败为 `SOURCE_SCAN_LOST` 且不对账；接管后旧扫描既不能对账也不能清掉新持有者；健康扫描只下线真正缺失的项并释放 scope；已过期 token 不能写也不能对账 |
| Java / 两个启动包 | `node .tmp/pipeline-test-env.cjs full`，自有真实 loopback PG/VM，两个 test 与 bootJar，`--rerun-tasks` | **193 平台 + 20 Worker = 213 tests，0 failures/errors/skipped；两个 bootJar 成功**。完整链使用该次构建的 `platform-api-0.1.0-SNAPSHOT.jar`（14:27:10） |
| 真实 PG 所有权 | `PostgresItemScanOwnershipIT` 4 项与既有 `PostgresItemSyncIT`（均含于 Java 总数） | **通过**。外部持有租约时扫描返回 `SOURCE_SCAN_BUSY`、0 页 0 采纳、目录未变、运行记录为 `FAILED` 且原因为固定文案、持有者租约仍有效，释放后再次扫描可完成并只下线缺失项；过期租约可被接管恢复，但被取代的 token 对账抛 `LOST` 且目录前后完全一致；过期 token 的受围栏写入与对账都被拒绝；成功扫描把 scope 交还给下一个 fence |
| HTTP / 权限 | `ZabbixItemSyncIT` 新增 1 项（含于 Java 总数） | **通过**。外部持有租约时 `POST /items/sync` 返回 503，`error=source_unavailable`、`failureCode=SOURCE_SCAN_BUSY`、摘要等于固定文案、`snapshotComplete=false`、0 页 0 采纳、带 `syncRunId`；释放后同一入口返回 200 |
| Rust 默认 / 全 features / fmt | `node .tmp/rust-check.cjs`，workspace locked、`-j 1` | **40/43 通过，0 failed/ignored；fmt 通过**。本轮没有 Rust 产品改动 |
| Web 类型 / 构建 / 回归 | `apps/web-console` 的 `pnpm typecheck`、`pnpm build` 与 Playwright 全量 | **190 passed，构建通过**。本节不改前端；页面/凭据边界回归通过 |
| 开发模式完整链 | `node .tmp/pipeline-test-env.cjs browser` → checked-in `check_metrics_stack.mjs --pipeline --runtime` | **19 个 PASS；110 个响应边界检查 0 失败**。链内真实 Item 同步在租约+受围栏写入下继续完成，来源扫描追溯、Host 流水线、Tool/Runtime/AIInsight 回归不变 |
| OIDC / Worker 完整链 | `node .tmp/oidc-stack.cjs --history-service` | **10 个 PASS、18 个浏览器响应边界通过**。撤权/故障游标/密钥轮换与既有诊断回归 |
| 实际产物 | 最终整链后 Draft202012Validator + FormatChecker | **105 份产物、62 类顶层 Schema 通过**（数量与第44节一致，产物已按新语义重新生成） |
| 静态门禁 | `git diff --check`、当前分支与暂存检查 | **通过**；`main`、无 staged 变更，无本轮依赖升级或生成目录入库 |

同时修正第44节留下的两处实现缺口：V023 读取索引此前既没有登记进 `InventoryWiring` 的迁移列表，也没有登记进 `processResources`，因此**从未真正创建**——本轮在 Item PG 测试报 `Missing inventory migration` 时暴露，两处已补齐；`SOURCE_SCAN_*` 的固定摘要原先写作“host scan”，在 Item 扫描也会返回这些码之后改成作用域中性表述（`SourceScanRunQuerySmoke` 的断言同步更新）。中间还修正了一处测试假设：租约在页内失效时，受围栏写入必须先被拒绝，因此该次扫描的 `accepted` 为 0 而不是 1。

保留边界：所有权解决的是“谁能写”，不是“上游是否一致”。Item 扫描完成态仍是 `offset-scan-attempt`，offset 分页期间的上游漂移不修复；已提交页的目录/绑定保留，只有缺失对账被围栏拒绝；遗留 `RUNNING` 元数据仍不自动修复。Host 与 Item 是彼此独立的 scope（可并发，无全局所有权），Problem/历史采集继续使用 ingestion 自有 checkpoint 租约且没有缺失对账。内存适配器不是事务性的，生产语义以 PostgreSQL 为准。真实 Zabbix/模型/IdP/TLS 与人工诊断抽样审阅仍未验收。

M2工程估算保持90%，**MVP约89%（±5个百分点），整体约57%**；本节是与第41节同类的同步正确性修复，不据此提高阶段估算。M1–M4退出门槛仍未全部通过，MVP100%目标继续。下一步继续来源失败/冲突追溯与只读诊断异常场景评估，并在真实环境可用时先跑连接自检。见 [ADR-039](adr/039-item-scan-ownership.md)、[集成说明](../modules/integration/README.md)、[操作说明](runbooks/source-scan-runs.md)。

## 46. 2026-09-26 Host 扫描的 hostid 水位快照与漂移拒绝（追加）

继续当前 `main` 工作区，无新分支、提交、推送或部署。遵循用户“暂未准备，先推进本地实现”：M2 退出条件要求一条来源“完成分页、游标、完整快照”，而此前 Host 扫描固定标注 `offset-scan-attempt`——按 `hostid` 升序的 limit/offset 分页在上游新增时会让后续页漂移，删除更会让 offset 跳过一行，而漂移之后仍执行缺失对账，就会把只是被跳过的资产错误置为 `INACTIVE`。

本节让默认 Host 来源在第一个分页请求之前先捕获两个边界（当前最高 `hostid` 与当前总行数），在该水位内升序分页；只有“观测行数等于捕获计数”且“确实看到水位行”时才把 `snapshotComplete` 置为 true 并标注 `hostid-watermark-snapshot`，否则以 `SOURCE_SCAN_UNVERIFIED` 失败且不对账。游标携带边界（offset、水位、计数、上一行、已观测数），服务端不从请求推断边界、客户端也不得构造；水位之后新建的 host 不属于本次快照；边界请求失败按 `SOURCE_FETCH_FAILED` 处理，绝不退化成空快照。

| 检查 | 实际命令 / 方法 | 最终结果与边界 |
|---|---|---|
| 结构 / 契约 | Docker Python3.12 `.tmp/check-scanrun-final.sh`（静态检查 + pytest contracts + 发布输入 + 实际产物） | **594 passed；232 个结构化文件、6 个只读 Tool**。失败契约的 `scanConsistency` 由常量放宽为 `offset-scan-attempt`/`hostid-watermark-snapshot` 闭集，失败码集合新增 `SOURCE_SCAN_UNVERIFIED`；新增 4 项契约用例覆盖两种标签、新失败码与非法标签拒绝。不新增 Schema 类型，产物仍为 105 份/62 类 |
| 纯领域 | `node .tmp/domain-check.cjs`，Java21 编译全部 modules/tests/domain 并逐个运行 main | **919 项、34 个 main 通过**。新增 `HostScanBoundarySmoke` 25 项：已验证水位快照才退休、删除导致行数不符时 `SOURCE_SCAN_UNVERIFIED` 且不退休、水位后新增不进快照、分页请求失败不伪装空快照、缺少边界的游标被拒绝；`ZabbixHostPageContractSmoke` 从 15 项扩到 24 项（水位/计数请求形状、跨页游标携带边界、越界新增、位移、空来源、缺失水位行） |
| Java / 两个启动包 | `node .tmp/pipeline-test-env.cjs full`，自有真实 loopback PG/VM，两个 test 与 bootJar，`--rerun-tasks` | **195 平台 + 20 Worker = 215 tests，0 failures/errors/skipped；两个 bootJar 成功**。完整链使用该次构建的 `platform-api-0.1.0-SNAPSHOT.jar`（15:00:03） |
| 真实 PG 边界 | `PostgresHostScanBoundaryIT` 2 项（含于 Java 总数） | **通过**。真实 PG 上已验证水位 walk 完成、标注 `hostid-watermark-snapshot`、退休缺失资产；位移 walk 返回 `SOURCE_SCAN_UNVERIFIED`、`snapshotComplete=false`、运行记录为 `FAILED` 且原因为固定文案、缺失资产保持 ACTIVE，被跳过的行从未落库 |
| HTTP / 协议桩 | `ZabbixJsonRpcConnectorIT` 改为三类请求（水位/计数/分页）并含于 Java 总数；`IdentityAndZabbixHostIT`、`SourceScanRunHttpIT` 断言新标签 | **通过**。真实 HTTP 到本地协议桩：水位用 `output:["hostid"]`+DESC，计数只读一次，分页 ASC offset 0；Host 同步响应标签为 `hostid-watermark-snapshot`，Item 同步仍为 `offset-scan-attempt` |
| Rust 默认 / 全 features / fmt | `node .tmp/rust-check.cjs`，workspace locked、`-j 1` | **40/43 通过，0 failed/ignored；fmt 通过**。本轮没有 Rust 产品改动 |
| Web 类型 / 构建 / 回归 | `apps/web-console` 的 `pnpm typecheck`、`pnpm build` 与 Playwright 全量 | **190 passed，构建通过**。本节不改前端；页面/凭据边界回归通过 |
| 开发模式完整链 | `node .tmp/pipeline-test-env.cjs browser` → checked-in `check_metrics_stack.mjs --pipeline --runtime` | **19 个 PASS；110 个响应边界检查 0 失败**。链内真实 Host 同步走水位 walk 完成并继续对账，来源扫描追溯、Item 同步、Tool/Runtime/AIInsight 回归不变 |
| OIDC / Worker 完整链 | `node .tmp/oidc-stack.cjs --history-service` | **10 个 PASS、18 个浏览器响应边界通过** |
| 实际产物 | 最终整链后 Draft202012Validator + FormatChecker | **105 份产物、62 类顶层 Schema 通过**（数量不变，产物按新语义重新生成） |
| 静态门禁 | `git diff --check`、当前分支与暂存检查 | **通过**；`main`、无 staged 变更，无本轮依赖升级或生成目录入库 |

中间修正：walk 结束却没有快照证明，原先落在 `PAGE_NOT_ADVANCED`（“游标没有前进”），语义不符——现在改为 `SOURCE_SCAN_UNVERIFIED`，而游标真的重复时仍是 `PAGE_NOT_ADVANCED`；`ZabbixHostMappingSmoke` 的“stalled cursor”用例随之改名并断言不对账。边界 smoke 的第一版断言也写错了：位移 walk 会把**实际读到的行**全部落库（3 行）而只是拒绝对账，断言已改为“已读页保留 + 被跳过的行从未落库”。`ZabbixJsonRpcConnectorIT` 的本地协议桩原来只应答旧分页请求，已扩成三类请求并断言计数只读一次。

保留边界：水位快照假定 `hostid` 单调递增（Zabbix 由服务端分配主机 ID），它是对本次 walk 的边界证明，不是数据库级一致快照，也不代表厂商实例已验收——真实 Zabbix 的 `hostid` 分配、`countOutput` 与排序行为仍需真实环境验证。Item 采集与 Problem/历史采集本轮不变（前者仍是 `offset-scan-attempt`，后者用 ingestion 自有 checkpoint 租约）。未实现边界的自定义连接器仍可能完成并触发对账（生产 Host 连接器都已实现边界）；“只有已验证快照才允许对账”的强规则与把一致性标签持久化到运行记录（供追溯页显示）都留作后续工作。运行记录仍不保存方法标签。

M2工程估算90%→93%（补齐本地可实现的“完整快照”边界与漂移拒绝），**MVP约90%（±5个百分点），整体约57%（四舍五入不变）**。M1–M4退出门槛仍未全部通过，MVP100%目标继续；下一步继续来源失败/冲突追溯、把一致性标签写入运行记录，并在真实环境可用时先跑连接自检。见 [ADR-040](adr/040-host-watermark-snapshot.md)、[Host 扫描契约](../contracts/host-scan.md)、[操作说明](runbooks/source-scan-runs.md)。

## 47. 2026-09-26 扫描边界标签持久化与追溯展示（追加）

继续当前 `main` 工作区，无新分支、提交、推送或部署。第46节让 Host 扫描按 hostid 水位完成“已验证快照”，但结论只出现在同步响应里：运行记录不保存 `scanConsistency`，追溯页只能看到状态与 `snapshotComplete`，真实来源验收后无法回答“这条已完成的运行到底是水位快照还是 offset 尝试”，而两者的对账含义完全不同。本节把该标签持久化并暴露到追溯链路。

`integration.source_sync_run` 新增 `scan_consistency`（V024，默认 `offset-scan-attempt`，闭集约束两种标签），由 `succeed`/`fail` 在结束时写入当时的边界标签；追溯接口与页面把它作为运行的一部分返回并展示。旧行保留默认值——它们确实是在水位 walk 出现之前写入的 offset 尝试。V024 同时登记进迁移列表与 `processResources`（第45节曾因漏登记导致 V023 从未创建）。标签不参与授权、不能被请求覆盖，`RUNNING` 期间保持默认值。

| 检查 | 实际命令 / 方法 | 最终结果与边界 |
|---|---|---|
| 结构 / 契约 | Docker Python3.12 `.tmp/check-scanrun-final.sh`（静态检查 + pytest contracts + 发布输入 + 实际产物） | **599 passed；232 个结构化文件、6 个只读 Tool**。`source-scan-run` 增加必填 `scanConsistency` 闭集字段，样例覆盖两种标签与历史 offset 行；新增 5 项契约用例（两种标签合法、未知标签拒绝、字段必填、多行文本拒绝） |
| 纯领域 | `node .tmp/domain-check.cjs`，Java21 编译全部 modules/tests/domain 并逐个运行 main | **921 项、34 个 main 通过**。追溯 smoke 增至 39 项，新增“已完成 walk 保留边界标签”“失败 walk 保留自己的方法标签”两项 |
| Java / 两个启动包 | `node .tmp/pipeline-test-env.cjs full`，自有真实 loopback PG/VM，两个 test 与 bootJar，`--rerun-tasks` | **195 平台 + 20 Worker = 215 tests，0 failures/errors/skipped；两个 bootJar 成功**。完整链使用该次构建的 `platform-api-0.1.0-SNAPSHOT.jar`（15:19:28） |
| 真实 PG 标签 | `PostgresSourceScanRunIT`（标签往返）与 `PostgresHostScanBoundaryIT`（已验证/未验证 walk 的落库标签），含于 Java 总数 | **通过**。直接 `succeed`/`fail` 写入的两种标签都能按租户读回；已验证水位 walk 的落库标签是 `hostid-watermark-snapshot`；`SOURCE_SCAN_UNVERIFIED` 的失败运行同样保留该标签且状态为 `FAILED` |
| HTTP / 追溯读取 | `SourceScanRunHttpIT`（含于 Java 总数） | **通过**。真实 HTTP 同步后的运行，按 `syncRunId` 读取返回 `scanConsistency: hostid-watermark-snapshot`；未知失败文本仍不回显 |
| Web 类型 / 构建 / 回归 | `apps/web-console` 的 `pnpm typecheck`、`pnpm build` 与 Playwright 全量 | **192 passed（新增 2 项拒绝用例），构建通过（80 modules）**。页面把标签渲染为“边界 已验证 hostid 水位快照 / offset 尝试（无快照证明）”；客户端拒绝未知标签与缺失标签的响应 |
| 开发模式完整链 | `node .tmp/pipeline-test-env.cjs browser` → checked-in `check_metrics_stack.mjs --pipeline --runtime` 与 `lib/check_source_scan_runs.mjs` | **19 个 PASS；110 个响应边界检查 0 失败**。链内在真实浏览器→Java→PG 上断言落库标签为水位快照、Item 运行仍为 offset 标签，并断言页面显示对应文案 |
| OIDC / Worker 完整链 | `node .tmp/oidc-stack.cjs --history-service` | **10 个 PASS、18 个浏览器响应边界通过** |
| 实际产物 | 最终整链后 Draft202012Validator + FormatChecker | **105 份产物、62 类顶层 Schema 通过**（数量不变，运行产物带上了新字段） |
| 静态门禁 | `git diff --check`、当前分支与暂存检查 | **通过**；`main`、无 staged 变更，无本轮依赖升级或生成目录入库 |

同时修正一处真实缺口：前端 `FAILURE_SUMMARY` 映射仍停留在第45节之前的 host-only 文案（`SOURCE_SCAN_BUSY/LOST/DEADLINE` 的三条摘要与 Java 端不一致），且缺少第46节新增的 `SOURCE_SCAN_UNVERIFIED`。由于客户端只渲染与固定摘要完全一致的失败码，这类运行在追溯页会被整体拒绝——即“有记录但读不出来”。现在映射与 Java 枚举逐条对齐，并新增 e2e 拒绝用例（未知边界、缺失边界）与渲染断言把它钉住。

保留边界：标签描述方法而不是结果，`hostid-watermark-snapshot` 的失败运行仍可能是“没有快照证明”的失败，只有 `snapshotComplete: true` 才代表边界被验证；标签不参与授权、不能被请求覆盖。Item 采集仍写 `offset-scan-attempt`（尚未实现水位边界），Problem/历史采集不写运行记录。运行记录仍没有自动清理或总量配额，也没有把标签用于自动决策（“只允许水位快照触发对账”的强规则尚未实现）。真实 Zabbix/模型/IdP/TLS 与人工诊断抽样审阅仍未验收。

M2工程估算保持93%，**MVP约90%（±5个百分点），整体约57%**；本节只是把第46节的保证变成可追溯的产品事实，不据此提高阶段估算。M1–M4退出门槛仍未全部通过，MVP100%目标继续。下一步给 Item/Problem 补水位边界、继续来源失败/冲突追溯与只读诊断异常场景评估，并在真实环境可用时先跑连接自检。见 [ADR-041](adr/041-persisted-scan-consistency.md)、[扫描运行契约](../contracts/source-scan-runs.md)、[操作说明](runbooks/source-scan-runs.md)。

## 48. 2026-09-26 Item 采集的 itemid 水位快照（追加）

继续当前 `main` 工作区，无新分支、提交、推送或部署。第45节让 Item 扫描取得来源 scope 租约，解决了“谁能写”；但那次 walk 仍是 `offset-scan-attempt`——按 `itemid` 升序的 limit/offset 分页在上游删除一行后会让 offset 跳过另一行，而扫描仍然完成并执行 `retireMissing`，把只是被跳过的指标绑定错误置为 `INACTIVE`。这与第46节修掉的 Host 缺口是同一类问题，只是后果落在指标绑定上。

本节把同一套水位边界用于 Item 采集：默认 Item 来源在第一个分页请求前读取当前最高 `itemid` 与总行数，在该水位内升序分页；只有观测行数等于捕获计数且确实看到水位行时才 `snapshotComplete=true` 并标注 `itemid-watermark-snapshot`，否则以 `SOURCE_SCAN_UNVERIFIED` 失败且不对账。水位之后新增的 item 不属于本次快照；边界请求失败按 `SOURCE_FETCH_FAILED` 处理。水位算法抽成两个连接器共享的 `JsonRpcWatermarkBounds`（捕获、游标编解码、验证判定），V025 把运行记录的标签闭集扩展为三种且约束名不变。

| 检查 | 实际命令 / 方法 | 最终结果与边界 |
|---|---|---|
| 结构 / 契约 | Docker Python3.12 `.tmp/check-scanrun-final.sh`（静态检查 + pytest contracts + 发布输入 + 实际产物） | **602 passed；232 个结构化文件、6 个只读 Tool**。`source-scan-run` 与失败契约的标签闭集扩为三种（含 `itemid-watermark-snapshot`），新增 3 项契约用例（item 运行保留自身边界、非法标签拒绝、失败 walk 说明其边界）。不新增 Schema 类型，产物仍为 105 份/62 类 |
| 纯领域 | `node .tmp/domain-check.cjs`，Java21 编译全部 modules/tests/domain 并逐个运行 main | **945 项、35 个 main 通过**。新增 `ItemScanBoundarySmoke` 24 项：已验证 itemid walk 才退休、位移 walk 报 `SOURCE_SCAN_UNVERIFIED` 且不退休、水位后新增不进快照、分页失败不伪装空快照、缺少边界的游标被拒绝、失败运行保留方法标签；`ZabbixItemMappingSmoke` 的完成态断言改为 `itemid-watermark-snapshot` |
| Java / 两个启动包 | `node .tmp/pipeline-test-env.cjs full`，自有真实 loopback PG/VM，两个 test 与 bootJar，`--rerun-tasks` | **197 平台 + 20 Worker = 217 tests，0 failures/errors/skipped；两个 bootJar 成功**。完整链使用该次构建的 `platform-api-0.1.0-SNAPSHOT.jar`（15:39:42） |
| 真实 PG 边界 | `PostgresItemScanBoundaryIT` 2 项（含于 Java 总数） | **通过**。真实 PG 上 fixture item walk 完成、标注 `itemid-watermark-snapshot`、只退休缺失绑定（1 采纳/1 拒绝/1 退休），落库标签一致；位移 walk 返回 `SOURCE_SCAN_UNVERIFIED`、`snapshotComplete=false`、运行 `FAILED` 且原因为固定文案、标签保留，缺失绑定保持 ACTIVE，被跳过的绑定从未落库 |
| HTTP / 标签 | `ZabbixItemSyncIT` 2 项（含于 Java 总数） | **通过**。fixture item 同步响应标签为 `itemid-watermark-snapshot`；来源被占用时（未读任何页）仍为默认 `offset-scan-attempt`，即“没读过页就不对方法作声明” |
| Web 类型 / 构建 / 回归 | `apps/web-console` 的 `pnpm typecheck`、`pnpm build` 与 Playwright 全量 | **192 passed，构建通过**。客户端接受三种边界标签并渲染“已验证 itemid 水位快照”；item 用例断言该文案，未知标签仍被拒绝 |
| 开发模式完整链 | `node .tmp/pipeline-test-env.cjs browser` → checked-in `check_metrics_stack.mjs --pipeline --runtime` 与 `lib/check_source_scan_runs.mjs` | **19 个 PASS；110 个响应边界检查 0 失败**。链内真实 Item 同步走 itemid 水位 walk 完成，追溯断言 item 运行携带自身边界标签、Host 运行携带 hostid 标签 |
| OIDC / Worker 完整链 | `node .tmp/oidc-stack.cjs --history-service` | **10 个 PASS、18 个浏览器响应边界通过** |
| 实际产物 | 最终整链后 Draft202012Validator + FormatChecker | **105 份产物、62 类顶层 Schema 通过** |
| 静态门禁 | `git diff --check`、当前分支与暂存检查 | **通过**；`main`、无 staged 变更，无本轮依赖升级或生成目录入库 |

中间修正：契约链两次失败都来自“每个样例必须有同名 schema”的仓库约定——新增的 `source-scan-run-item` 变体样例没有同名 schema。最终不新增变体样例，改由契约用例从基础样例派生 item 运行（去掉 host 专属的 `pipelineVersion`）并覆盖三种标签，避免为样例发明一个只做窄化的 schema。`ZabbixItemSyncIT` 的占用用例也纠正过一次：未读任何页的失败扫描不应声称水位方法。

保留边界：水位快照假定 `itemid` 单调递增（Zabbix 由服务端分配），它是对本次 walk 的边界证明，不是数据库级一致快照，也不代表厂商实例已验收。Item 完成态仍是 offset 分页的**边界**（Zabbix 没有 keyset 过滤），因此上游删除只能被“拒绝”而不能被“修复”。Problem/历史采集继续使用 ingestion 自有 checkpoint 租约，本轮不变；“只允许已验证快照触发对账”的强规则仍未实现（未实现边界的自定义连接器仍可完成对账）。运行记录没有自动清理或总量配额。真实 Zabbix/模型/IdP/TLS 与人工诊断抽样审阅仍未验收。

M2工程估算保持93%，M3保持85%，**MVP约90%（±5个百分点），整体约57%**；本节是与第41/45节同类的同步正确性修复，不据此提高阶段估算。M1–M4退出门槛仍未全部通过，MVP100%目标继续。下一步继续来源失败/冲突追溯与只读诊断异常场景评估，把标签用于“只有已验证快照才允许对账”的强规则，并在真实环境可用时先跑连接自检。见 [ADR-042](adr/042-item-watermark-snapshot.md)、[集成说明](../modules/integration/README.md)、[操作说明](runbooks/source-scan-runs.md)。

## 49. 2026-09-26 只有已验证快照才允许对账（追加）

继续当前 `main` 工作区，无新分支、提交、推送或部署。第40/42节让默认 Host/Item 连接器按水位完成“已验证快照”，但这条规则此前只存在于连接器的自觉里：两个采集用例仍只看 `Page.snapshotComplete()`，因此一个未实现边界的连接器（自定义、未来新增，或声明完成却只给 `offset-scan-attempt` 的适配器）仍可触发 `retireMissing`，把只是没被观测到的对象错误下线。这与第41/45节修掉的“谁能写”不同，是“凭什么能退休”。

本节把规则提升为系统级不变量：`SyncScan.verified(label)` 只承认 `hostid-watermark-snapshot` 与 `itemid-watermark-snapshot`；用例在 `snapshotComplete` 为真时仍复核该标签，不满足就以 `SOURCE_SCAN_UNVERIFIED` 失败、退休数为 0，已提交页与 Raw 保留。默认连接器不受影响（本来就给出水位标签），而任何“声明完成但无法证明边界”的适配器从此无法退休对象。同时先做了缺口审计：Problem/历史采集路径没有任何 `retire`/`reconcile`（只有幂等入库与显式缺口），因此不存在同类缺口，本轮不做“Problem 水位边界”。

| 检查 | 实际命令 / 方法 | 最终结果与边界 |
|---|---|---|
| 结构 / 契约 | Docker Python3.12 `.tmp/check-scanrun-final.sh`（静态检查 + pytest contracts + 发布输入 + 实际产物） | **602 passed；232 个结构化文件、6 个只读 Tool**。本节是行为约束，不新增/修改 Schema：失败码与标签闭集沿用第46–48节，产物仍为 105 份/62 类；[Host 扫描契约](../contracts/host-scan.md) 明确写出“未实现边界的连接器不能通过声明完成来退休任何对象” |
| 纯领域 | `node .tmp/domain-check.cjs`，Java21 编译全部 modules/tests/domain 并逐个运行 main | **963 项、35 个 main 通过**。`HostScanBoundarySmoke` 增至 33 项、`ItemScanBoundarySmoke` 增至 32 项：新增“有界空来源是已验证快照且可以退休”“未实现边界却声明完成的连接器报 `SOURCE_SCAN_UNVERIFIED` 且退休数为 0”；`ZabbixHostMappingSmoke` 的两个旧断言改为新语义（未验证 walk 不再退休，位移跳过的 host 保持 ACTIVE），并把该 smoke 的硬编码计数改成真实计数（此前它会误报 46 而实际执行 48 项） |
| Java / 两个启动包 | `node .tmp/pipeline-test-env.cjs full`，自有真实 loopback PG/VM，两个 test 与 bootJar，`--rerun-tasks` | **199 平台 + 20 Worker = 219 tests，0 failures/errors/skipped；两个 bootJar 成功**。完整链使用该次构建的 `platform-api-0.1.0-SNAPSHOT.jar`（16:06:31） |
| 真实 PG 强制规则 | `PostgresHostScanBoundaryIT` 与 `PostgresItemScanBoundaryIT` 各新增 1 项（含于 Java 总数） | **通过**。真实 PG 上“声明完成但无边界”的连接器返回 `SOURCE_SCAN_UNVERIFIED`、退休数为 0、缺失对象保持 ACTIVE、运行记录为 `FAILED` 且标签保持默认 `offset-scan-attempt`（没读过页就不声明方法） |
| HTTP / 协议 | 既有 `ZabbixJsonRpcConnectorIT`、`ZabbixItemSyncIT`、`SourceScanRunHttpIT` 全部回归（含于 Java 总数） | **通过**。生产 Host/Item 连接器仍走水位边界，同步响应标签与落库标签不变；本节没有改变任何 HTTP 形状 |
| Rust 默认 / 全 features / fmt | `node .tmp/rust-check.cjs`，workspace locked、`-j 1` | **40/43 通过，0 failed/ignored；fmt 通过**。本轮没有 Rust 产品改动 |
| Web 类型 / 构建 / 回归 | `apps/web-console` 的 `pnpm typecheck`、`pnpm build` 与 Playwright 全量 | **192 passed，构建通过**。本节不改前端 |
| 开发模式完整链 | `node .tmp/pipeline-test-env.cjs browser` → checked-in `check_metrics_stack.mjs --pipeline --runtime` | **19 个 PASS；110 个响应边界检查 0 失败**。默认连接器都是水位边界，因此强规则不改变正常路径，链内 Host/Item 同步、追溯标签与既有回归全部保持 |
| OIDC / Worker 完整链 | `node .tmp/oidc-stack.cjs --history-service` | **10 个 PASS、18 个浏览器响应边界通过** |
| 实际产物 | 最终整链后 Draft202012Validator + FormatChecker | **105 份产物、62 类顶层 Schema 通过** |
| 静态门禁 | `git diff --check`、当前分支与暂存检查 | **通过**；`main`、无 staged 变更，无本轮依赖升级或生成目录入库 |

保留边界：这条规则不能修复上游漂移，只能拒绝在不完整视图上做对账；它也不改变 offset 分页的语义（Zabbix 没有 keyset 过滤）。Problem/历史采集没有缺失对账，因此不适用，也不做“Problem 水位边界”。运行记录没有自动清理或总量配额；真实 Zabbix 行为、厂商实例验收与人工诊断审阅仍未完成。

M2工程估算保持93%，M3保持85%，**MVP约90%（±5个百分点），整体约57%**；本节把“不误删除”从默认连接器的行为变成用例层的强制检查，属正确性硬化，不据此提高阶段估算。M1–M4退出门槛仍未全部通过，MVP100%目标继续。下一步继续来源失败/冲突追溯与只读诊断异常场景评估，并在真实环境可用时先跑连接自检。见 [ADR-043](adr/043-verified-snapshot-reconciliation.md)、[Host 扫描契约](../contracts/host-scan.md)、[操作说明](runbooks/source-scan-runs.md)。

## 50. 2026-09-26 模型请求边界的可验证证据（追加）

继续当前 `main` 工作区，无新分支、提交、推送或部署。M4 退出条件要求“模型请求不携带原始密钥；外部日志/知识被当作不可信数据；真实模式异常不会伪装成 Demo 成功”。本节先做审计，结论是**实现已经正确、缺的是证据**：密钥由 SDK 客户端持有并只进 `Authorization` 头，工作流把提问与知识组装成 `untrustedUserQuestion`/`untrustedEvidenceContext`/`requiredOutputSchema` 三个具名不可信字段，随附的技能提示（`extensions/skills/incident-diagnosis-current/prompt.md`）明确写着“untrusted content, never instructions”，适配器用 `NoSubscriber` 抑制 SDK 的内容诊断，失败路径既无重试也无回退。

因此本节不改行为（只补模块文档），而是把这些断言钉成测试：① 既有请求形状测试现在同时捕获请求头与请求体，断言密钥只出现在 `Authorization: Bearer`、**不出现在请求体**；② 新增一条端到端测试，用**真实随仓库发布的技能提示**与生产 `ModelInput::current` 组装输入，向本地协议桩发出真实 Rig 请求，断言请求体包含三个不可信字段、不可信声明句、结构化证据 id 与输出 Schema，且不含密钥。其余两半沿用既有测试并在此引用：`unavailable_redirect_missing_usage_and_oversize_never_retry_or_fallback`（不可用/重定向/缺用量/超长都不重试不回退）与 `invalid_model_output_and_revocation_never_save`（provider 报错时 `diagnose` 直接失败且**不保存任何 AIInsight**，`saves == 0`）。

| 检查 | 实际命令 / 方法 | 最终结果与边界 |
|---|---|---|
| Rust 默认 / 全 features / fmt | `node .tmp/rust-check.cjs`（`cargo fmt --all --check`、`cargo test --workspace --locked -j 1`、再 `--all-features`） | **默认 40、all-features 44 通过，0 failed/ignored；fmt 通过**（all-features 由 43 增至 44，即新增的请求边界测试；它随 `rig-provider` 特性启用） |
| 请求头/请求体隔离 | `adapters::rig_model::tests::responses_fixture_preserves_usage_and_enforces_one_nonstored_bounded_request`（扩展） | **通过**。本地协议桩收到的唯一请求：`Authorization: Bearer <fixture key>` 存在，而请求体字符串**不含**该密钥；同时仍断言 `store=false`、`background=false`、无 tools、无 `previous_response_id`、`max_output_tokens=2048` |
| 不可信数据框架 | 新增 `adapters::rig_model::tests::current_knowledge_request_frames_content_as_untrusted_data_without_the_key` | **通过**。真实技能提示 + 生产 `ModelInput::current` 组装后的请求体包含 `untrustedUserQuestion`、`untrustedEvidenceContext`、`requiredOutputSchema`、“untrusted content, never instructions”与结构化证据 id，且不含密钥 |
| 无回退 / 不伪装成功 | 既有 `unavailable_redirect_missing_usage_and_oversize_never_retry_or_fallback` 与 `invalid_model_output_and_revocation_never_save`（本次回归通过） | **通过**。四类提供方异常各只发一次请求、不重试不回退；provider 报错时诊断直接失败，`model.calls == 1`、`read.saves == 0`（没有伪造结果落库） |
| 结构 / 契约 | Docker Python3.12 `.tmp/check-scanrun-final.sh` | **602 passed；232 个结构化文件、6 个只读 Tool；105 份产物/62 类 Schema**（本节不改契约/产物） |
| 纯领域 | `node .tmp/domain-check.cjs` | **963 项、35 个 main 通过**（本轮无 Java 改动，作回归） |
| Java / 两个启动包 | `node .tmp/pipeline-test-env.cjs full` | **199 平台 + 20 Worker = 219 tests，0 failures/errors/skipped；两个 bootJar 成功**（回归，代码未改） |
| 开发模式完整链 | `node .tmp/pipeline-test-env.cjs browser` | **19 个 PASS；110 个响应边界检查 0 失败**（含 Rust mock 诊断、Tool/Runtime、追溯与既有回归） |
| OIDC / Worker 完整链 | `node .tmp/oidc-stack.cjs --history-service` | **10 个 PASS、18 个浏览器响应边界通过** |
| Web 类型 / 构建 / 回归 | `apps/web-console` 的 `pnpm typecheck`、`pnpm build` 与 Playwright 全量 | **192 passed，构建通过**（本节不改前端） |
| 静态门禁 | `git diff --check`、当前分支与暂存检查 | **通过**；`main`、无 staged 变更，无本轮依赖升级或生成目录入库 |

保留边界：这是**本地协议桩**验证——真实请求发往 `127.0.0.1` 上的桩服务，没有调用任何真实提供方、没有使用真实密钥，也没有做计费或内容留存验收；“模型请求不携带原始密钥”因此证明的是适配器实际发出的请求形状，而不是厂商侧行为。真实模型、账单对账、真实 Zabbix/IdP/TLS 与人工诊断抽样审阅仍未完成。Rust 侧仍未提供生产 RunStore/取消/恢复，也没有把提示或知识写入普通日志的路径（这是设计约束，本节只做了内容诊断抑制的既有断言）。

M2保持93%、M3保持85%、M4保持80%，**MVP约90%（±5个百分点），整体约57%**；本节只把既有实现钉成可复核证据，不据此提高阶段估算。M1–M4退出门槛仍未全部通过，MVP100%目标继续。下一步继续来源失败/冲突追溯、只读诊断异常场景评估与运行记录治理，并在真实环境可用时先跑连接自检。见 [Rig 适配器](../apps/agent-runtime/src/adapters/rig_model.rs)、[模型费用准入](adr/033-model-spend-admission.md)、[当前诊断与 AIInsight](adr/023-current-diagnosis-insight.md)、[工具证据边界](adr/022-current-knowledge-tool-evidence.md)。

## 51. 2026-09-26 只读的来源连接自检（追加）

继续当前 `main` 工作区，无新分支、提交、推送或部署。M2 退出条件要求“一条**已声明支持版本**的来源”完成分页与完整快照，但 `Connector.probe` 虽然早已实现（fixture 显式标注、JSON-RPC 调 `apiinfo.version`），却没有任何入口调用它：真实来源到位后第一步“连不连得上、自报什么版本”无处执行，也没有回执可引用。本节补齐这一步。

POST `/api/v1/integrations/zabbix/connection-checks` 执行一次有界 probe 并写入回执，GET 同路径读取该来源最近回执（1–50，默认 20，倒序）。权限沿用源级 `source.sync`；租户来自可信 Principal、来源来自配置；自检不启动扫描、不取租约、不写库存、不调用模型，失败也不回退 fixture。回执记录配置模式、稳定 `statusCode`、来源自报版本（最多 32 个可打印字符）、actor 与时间，每个 tenant/source 保留最近 100 份（写新回执时清理更旧的），读取单页上限 50。不可达的回执必须 `reportedVersion: null`，连接器抛异常时回执是 `unreachable`，绝不变成成功。

| 检查 | 实际命令 / 方法 | 最终结果与边界 |
|---|---|---|
| 结构 / 契约 | Docker Python3.12 `.tmp/check-connection-final.sh`（静态检查 + pytest contracts + 发布输入 + 实际产物） | **628 passed；238 个结构化文件、6 个只读 Tool**。新增 3 类 Schema（回执/信封/列表）与样例，用例覆盖未知字段拒绝、`connection-check` 之外的 dataMode 拒绝、不可达却带版本拒绝、状态码换行/超长拒绝、列表上限 |
| 纯领域 | `node .tmp/domain-check.cjs`，Java21 编译全部 modules/tests/domain 并逐个运行 main | **986 项、36 个 main 通过**。新增 `SourceConnectionCheckSmoke` 23 项：无 `source.sync`/空来源拒绝、fixture 回执保持标注且不声明版本、自报版本按原样保存、不可达不带版本、连接器抛异常仍失败关闭、留存上限 100、倒序与租户/来源隔离、limit 边界 |
| Java / 两个启动包 | `node .tmp/pipeline-test-env.cjs full`，自有真实 loopback PG/VM，两个 test 与 bootJar，`--rerun-tasks` | **204 平台 + 20 Worker = 224 tests，0 failures/errors/skipped；两个 bootJar 成功**。完整链使用该次构建的 `platform-api-0.1.0-SNAPSHOT.jar`（16:59:48） |
| 真实 PG / HTTP | `PostgresSourceConnectionCheckIT`、`SourceConnectionCheckHttpIT`、`SourceConnectionCheckDeniedIT`（含于 Java 总数） | **通过**。PG 上回执往返、倒序、limit 与租户/来源隔离、留存上限（写 102 份后 `kept == 100` 且最旧两份消失）；真实 HTTP 上 fixture 自检返回 `reachable: true` + `labeled-fixture` + `reportedVersion: null` + `no-store`/`nosniff`，列表读回同一 `checkId`，`?limit=0/51/abc`、未知参数与重复参数一律 400，POST 带任何查询参数 400，未认证 401，只有 `entity.read` 403 |
| Web 类型 / 构建 / 回归 | `apps/web-console` 的 `pnpm typecheck`、`pnpm build` 与 Playwright 全量 | **210 passed（新增 18 项），构建通过**。面板只读，显示 fixture 与“无版本声明”，客户端拒绝未知字段、未知 dataMode、不可达却带版本、未知 `statusCode` 等非法响应，身份切换清空回执与列表 |
| 开发模式完整链 | `node .tmp/pipeline-test-env.cjs browser` → checked-in `check_metrics_stack.mjs --pipeline --runtime` 与 `lib/check_source_connection.mjs` | **20 个 PASS；112 个响应边界检查 0 失败**。链内真实浏览器→Java→PG：点击自检得到 labeled-fixture 回执、列表读回、凭据清除后回执消失；既有 19 条链（Host/Item 水位、追溯、Tool/Runtime、AIInsight、留存、OIDC 回归）继续通过 |
| OIDC / Worker 完整链 | `node .tmp/oidc-stack.cjs --history-service` | **10 个 PASS、18 个浏览器响应边界通过** |
| 开发模式完整链 | `node .tmp/pipeline-test-env.cjs browser` → `check_metrics_stack.mjs --pipeline --runtime` | **20 个 PASS，退出码 0**。链内真实浏览器→Java→PG/VM 的指标页仍走 GAUGE 原始视图（`host.cpu.usage.user`，映射 `kind: gauge`），计数器变化率由 HTTP 与浏览器 fixture 覆盖；Host/Item 水位、追溯、Tool/Runtime、AIInsight、留存、绑定更正、扫描追溯与连接自检等既有链路继续通过 |
| OIDC / Worker 完整链 | `node .tmp/oidc-stack.cjs --history-service` | **10 个 PASS、18 个浏览器响应边界通过**；无提供方回退（本轮复跑 `.tmp/counter-web-oidc.log`） |
| Rust 默认 / 全 features / fmt | `node .tmp/rust-check.cjs` | **40/44 通过，0 failed/ignored；fmt 通过**（本轮无 Rust 改动） |
| 实际产物 | 最终整链后 Draft202012Validator + FormatChecker | **109 份产物、65 类顶层 Schema 通过**。新增 4 份：真实 HTTP 2（回执/列表）+ 实际浏览器 2；均为自有合成数据 |
| 静态门禁 | `git diff --check`、当前分支与暂存检查 | **通过**；`main`、无 staged 变更，无本轮依赖升级或生成目录入库 |

中间修正：新增契约用例第一次运行就抓到一个真实缺陷——`statusCode`/`reportedVersion` 的 `^[ -~]{1,64}$` 在 Python 正则里允许结尾换行，因此 `"unreachable\n"` 会通过校验。已按仓库惯例改成 `^[ -~]{1,64}(?![\\s\\S])`（版本字段同理）并重跑，628 项全绿。另把 POST 端点收紧为不接受任何查询参数（GET 只接受 `limit`），并补上 `entity.read` 不足以自检的 403 用例。

保留边界：`reportedVersion` 是**来源自报**的版本，用于对照“已声明支持版本”，不是平台验证过的支持结论；fixture 回执永远带 `labeled-fixture`，不证明真实厂商可用。自检没有后台调度、重试或跨来源汇总，也不替代真实 `host.get` 分页、完整快照与人工诊断审阅。真实 Zabbix/模型/IdP/TLS 与人工抽样审阅仍未验收；运行记录与回执表都没有全平台容量治理（回执按 tenant/source 保留 100 份）。真实环境到位后的第一步见 [runbook](runbooks/source-connection-check.md)。

M2工程估算保持93%，M3保持85%，M4保持80%，**MVP约90%（±5个百分点），整体约57%**；本节补齐“已声明支持版本”的第一步骤与回执，但真实来源验收仍未发生，不据此提高阶段估算。M1–M4退出门槛仍未全部通过，MVP100%目标继续。下一步继续来源失败/冲突追溯、只读诊断异常场景评估与运行记录治理。见 [ADR-045](adr/045-source-connection-check.md)、[契约](../contracts/source-connection-checks.md)、[操作说明](runbooks/source-connection-check.md)。

## 52. 2026-09-26 计数器变化率、reset 标记与 Web 变化率视图（追加）

M3 退出条件要求“counter reset 等有用例”，而 [MVP-EXIT-AUDIT.md](MVP-EXIT-AUDIT.md) 逐条核对时指出全仓只有设计文档提到它：`MetricType` 只有 GAUGE/SUM/HISTOGRAM，查询返回原始点，**没有任何 reset 策略、变化率推导或测试**。本节补齐这唯一的本地缺失项，并把“计数器只能看原始累计值”的页面改成显式的变化率视图。

规则固定为 `reset-counts-from-zero`：只有 SUM（计数器）查询得到派生视图；每个区间按秒计算变化率，出现下降即视为计数器重启，该区间以当前值从零起算并带 `counterReset = true`；非正区间与负值输入直接跳过而不是编造数据；原始点、单位、来源、时间窗口与状态一律不变。契约把 `derivation` 收紧为闭集（`kind = counter-rate`、`resetPolicy = reset-counts-from-zero`），并要求带派生视图的每个序列都必须给出 `counterRates`，`rate` 只能是非负十进制、`counterReset` 必须是布尔。Web 端默认画变化率曲线并在图上标出 reset 区间，可切换回原始累计值；没有 `derivation` 却带 rate、或带 `derivation` 却缺 rate 的响应一律拒绝。

| 检查 | 实际命令 / 方法 | 最终结果与边界 |
|---|---|---|
| 结构 / 契约 | Docker Python3.12 `.tmp/check-counter-final.sh`（沿用连接自检整链 + pytest contracts + 实际产物） | **642 passed；238 个结构化文件、6 个只读 Tool**。新增 `tests/contracts/test_metric_counter_rates.py` 14 项：原始页无派生仍合法、派生页必须带 reset 策略、`kind`/`resetPolicy` 闭集、缺 `counterRates` 拒绝、负值/科学计数/空串/前后空格 rate 拒绝、非布尔 reset 拒绝、缺时间拒绝 |
| 纯领域 | `node .tmp/domain-check.cjs` | **1008 项、37 个 main 通过**。新增 `CounterRateSmoke` 22 项：单调计数器无 reset、下降区间从零重算且只标该区间、重复时间点不产生区间、负值输入跳过不编造、SUM 查询返回 `derivation = counter-rate` 且原始点保留、GAUGE 页 `counterRates` 为空 |
| Java / 两个启动包 | `node .tmp/pipeline-test-env.cjs full`（自有 loopback PG/VM，`--rerun-tasks`） | **206 平台 + 20 Worker = 226 tests，0 failures/errors/skipped；两个 bootJar 成功**（本轮复跑 `.tmp/counter-web-java-full.log`：BUILD SUCCESSFUL in 1m 38s，bootJar 18:02 生成） |
| 真实 HTTP | `MetricCounterRateHttpIT`（含在上述 Java 总数内） | **通过**。真实 HTTP 计数器页返回 `derivation.kind = counter-rate` 与 `resetPolicy`，四个原始点保留、三个区间派生、重置区间 `counterReset = true` 且 rate 非负；GAUGE 页 `counterRates` 为空。产物写入 `.tmp/metric-counter-http/` |
| Web 类型 / 构建 / 回归 | `apps/web-console` 的 `pnpm typecheck` 与 Playwright 全量（`.tmp/playwright-local.config.ts`） | **218 passed（新增 8 项），TypeScript 与生产构建通过**。新 `e2e/metric-counter-rates.spec.ts`：默认变化率视图带 reset 竖线与清单、切回原始累计值后仍是 4 个原始点与末值 40、6 种非法响应（错策略、无策略却有 rate、缺 rate、负 rate、rate 落在首点、非布尔 reset）被拒绝且不画图、GAUGE 页没有变化率入口 |
| Rust 默认 / 全 features / fmt | `node .tmp/rust-check.cjs` | **40/44 通过，0 failed/ignored；fmt 通过**（本轮无 Rust 改动） |
| 实际产物 | 最终整链后 Draft202012Validator + FormatChecker | **111 份产物、65 类顶层 Schema 通过**。新增 2 份：真实 HTTP 计数器页与原始页；均为自有合成数据 |
| 静态门禁 | `git diff --check`、当前分支与暂存检查 | **通过**；`main`、无 staged 变更，无本轮依赖升级或生成目录入库 |

中间环境观察：完整链在同一版本上先失败两次——第一次与 Docker 契约链并行运行，在扫描追溯检查处等待响应超时；第二次紧随其后单跑，在留存预览处按钮仍处于未启用状态。两次都发生在重负载/前次运行清理期间，失败点互不相同；确认无残留进程后单独重跑得到 **20 个 PASS / 112 边界 0 失败**，同版本另有一次子代理运行同样通过。这里如实记录为**本机 E2E 的抖动**，不把失败归因于被测代码，也不把成功单次当作稳定性证明：链路目前没有重试或超时兜底，重负载下仍可能抖动。

保留边界：变化率是平台按固定策略推导的**视图**，不是厂商上报的速率，也没有做单位换算、降采样或百分位合并；reset 只看同一序列相邻点下降，不跨来源/跨序列推断。原始点始终保留，切回原始值即可看到回绕。真实 Zabbix 采样、真实模型与人工审阅仍未验收，本节的 fixture 与协议桩不构成厂商验收。

M2 保持93%、M3 从85%调整为90%（counter reset 策略与用例是 M3 此前唯一的本地缺失项，补齐后 M3 的本机可验证条件全部有证据），M4 保持80%，**MVP约91%（±5个百分点），整体约58%**；真实来源/模型/身份与人工抽样审阅仍未通过，MVP100%目标继续。下一步继续来源失败/冲突追溯、只读诊断异常场景评估与运行记录治理。见 [ADR-010](adr/010-metrics-semantics.md)、[指标查询验收](runbooks/metric-query-acceptance.md)、[指标页](../apps/web-console/src/pages/metrics/MetricsPage.tsx)。

## 53. 2026-09-26 真实验收执行包（只读、可留证）（追加）

继续当前 `main` 工作区，无新分支、提交、推送或部署。第52节的审计确认本机可验证的退出条件已全部有证据，剩下的只有环境阻塞（真实 Zabbix、真实模型/账单、真实 IdP/TLS、人工审阅）。本节把“环境到位后怎么验收”变成可执行、可留证的一步：新增 checked-in 的只读验收执行包，按顺序调用平台 HTTP API，逐步断言 M2–M4 的关键退出条件，并写出 JSON 报告。

`scripts/acceptance/real-acceptance.mjs` 的七步：S1 来源连接自检（reachable + dataMode + 来源自报版本）→ S2 Host 同步并要求 `hostid-watermark-snapshot` 且**落库标签回读一致** → S3 Item 同步并要求 `itemid-watermark-snapshot` → S4 受权资产读取 → S5 指定指标必须 `AVAILABLE` 且有样本（计数器同时记录 reset 数）→ S6 Incident 与关联告警可读（未指定则先导入一个外部告警窗口）→ S7 一次只读诊断保存为 PG AIInsight 并回读（证据引用数 + provider）。任一步失败立即停止、写失败报告、退出码 1。它只调用平台 API：不直连 Zabbix/模型/数据库，不写来源，不重试。

**诚实性守卫**：来源自检返回 `labeled-fixture` 时，除非显式 `--rehearsal`，脚本以退出码 2 拒绝运行并打印“fixture 运行绝不是真实验收”；即使带 `--rehearsal`，报告的 `mode` 也只写 `rehearsal`。报告固定列出脚本不能替代的三项未验证项：人工抽样审阅、真实模型提供方账单/配额对账、真实 IdP/HTTPS/反向代理验收。

| 检查 | 实际命令 / 方法 | 最终结果与边界 |
|---|---|---|
| 验收执行包演练（通过路径） | `node .tmp/acceptance-local.cjs`（本机启动真实平台 + Runtime + loopback PG/VM，写入显式 `labeled-fixture` 样本后调用执行包 `--rehearsal`） | **7/7 步通过**：S1 `labeled-fixture`+无版本声明、S2 `accepted=2/pages=1`、S3 `accepted=1/rejected=1`、S4 资产、S5 `AVAILABLE` 且有样本、S6 Incident+1 条关联告警、S7 AIInsight 落库并回读（`evidence=2`、`provider=mock-deterministic`）；报告 `mode=rehearsal` 并打印“NOT real acceptance” |
| 诚实性守卫 | 同一 fixture 来源去掉 `--rehearsal` | **退出码 2 且不产生真实报告**；报告 `mode=rehearsal`，步骤为空 |
| 失败路径 | 同一执行包用错误 Token | **退出码 1**，报告 `failed=true`、`steps=0`，未把任何步骤记为通过 |
| 结构 / 契约 | Docker Python3.12 `.tmp/check-counter-final.sh` | **642 passed；238 个结构化文件、6 个只读 Tool；111 份产物/65 类 Schema**（本节只新增脚本与文档，不改契约与产物） |
| 静态门禁 | `git diff --check`、`node --check`（执行包与演练脚本）、当前分支与暂存检查 | **通过**；`main`、无 staged 变更 |
| 回归（本轮无产品代码改动） | 沿用第52节全套：领域 1008/37、Java 226（206+20）、浏览器链 20 PASS/112 边界、OIDC 10 PASS/18 边界、Rust 40/44 + fmt、Web 218 + typecheck/build | **未重跑**；本节只新增 `scripts/acceptance/real-acceptance.mjs` 与文档，不改动 Java/Rust/TypeScript/契约 |

演练过程中修掉三处真实缺陷：① 演练脚本最初只给一台资产写样本，而执行包会挑另一台资产 → 现在为每个受权资产都准备样本；② 执行包在样本尚未被平台可见时就查询 → 演练脚本先等待“样本通过平台可见”；③ 执行包最初把诊断时间窗写成 epoch 秒，而 Runtime 要求 RFC3339 → 已改为 ISO 字符串。第 3 条是**执行包自身的真实缺陷**，被这次演练抓到并修复，而不是等真实环境才发现。

保留边界：执行包用开发模式 Bearer Token 驱动平台 API，覆盖“真实来源 + 真实模型”在开发/平台开发模式下的验收；生产 OIDC 部署仍按 OIDC runbook 用浏览器流程验收。报告里的 `mode: "real"` 只说明来源不是 fixture，**不替人判断这个来源是不是目标环境**——`sourceDataMode`、`reportedVersion` 与 provider 仍需人工核对。人工抽样审阅、账单对账与 IdP/TLS 验收固定列为未验证项。

M2 保持93%、M3 保持90%、M4 保持80%，**MVP约91%（±5个百分点），整体约58%**；本节是验收工具与文档，不是产品能力，因此不调整估算。真实环境一旦可用，按 [real-acceptance runbook](runbooks/real-acceptance.md) 执行并把报告附到验收记录；M1–M4 退出门槛在此之前仍未全部通过。

## 54. 2026-09-26 扫描运行记录的有界保留与配额（追加）

本轮与前 53 节不同：用户明确要求**先提交并推送**，再继续推进 MVP。因此本节记录的是已提交、已推送的变更，验证一部分在本机执行，另一部分由 GitHub Actions 执行（本机没有 PostgreSQL/Playwright 运行环境）。

第43/49/51/53 节都把同一件事写成已知缺口：`integration.source_sync_run` 只增不减，没有自动清理、没有每来源上限、没有租户总量配额。第44节的追溯依赖这张表，“最近运行”这个读取语义也本来不需要无限历史。本节把它变成**有界存储**：预算写在领域（`ScanRunRetention`），两个存储适配器同样执行。

规则：每个 `tenant + sourceInstanceId + objectType` 范围最多 `maxRunsPerScope = 1000` 行，每个租户最多 `maxRunsPerTenant = 5000` 行；配置（`opsweave.scan-run-retention.*`，环境变量 `OPSWEAVE_SCAN_RUN_MAX_PER_SCOPE/PER_TENANT`）只能收紧，越界或不一致（租户上限小于范围上限）一律按 `INVALID_REQUEST` 拒绝。清理发生在**打开一次扫描的同一事务里，且在新行写入之后**：超预算时从最旧的“可删除”行删起。仍在 `RUNNING` 的运行与被 `sync_pipeline_pin` 钉住的运行永不删除——钉子是外键，删掉它要么破坏引用、要么抹掉“这次运行按哪个映射版本走”的答案。追溯页新增 `retention`（预算与当前条数），读取本身不触发清理。

| 检查 | 实际命令 / 方法 | 最终结果与边界 |
|---|---|---|
| 纯领域 | `node .tmp/domain-check.cjs`，Java21 编译全部 modules/tests/domain 并逐个运行 main | **1071 项、39 个 main 通过**，连续三次复跑均通过（无抖动）。新增 `ScanRunRetentionSmoke` 50 项与 `ScanRunRetentionConfigSmoke` 11 项：预算默认值/收紧/越界拒绝、范围与租户预算、六个扫描后只剩预算行、刚结束的运行不会被自己的 sweep 删掉、打开中的运行与未结束运行受保护、超过预算的范围收敛、读取不清理、内存适配器与游标分页同一全序；`SourceScanRunQuerySmoke` 改为断言“同一毫秒的运行顺序不属于契约”，改用覆盖性/不重复/时间单调断言（41 项） |
| Web 类型 | `apps/web-console` 的 `pnpm typecheck` | **通过**。`source-scan-runs` 解析新增必填 `retention`，并拒绝越界预算、范围上限大于租户上限、负数、超过范围预算的条数、比本页还小的条数、缺字段与额外字段 |
| 静态门禁 | `git diff --check`、`git status`、提交内容复核 | **通过**；`main`，工作区干净，未提交 `.tmp/`、`node_modules`、`target` 或凭据（`.gitignore` 覆盖） |
| 契约 / Java / Rust / Playwright | GitHub Actions `opsweave-template`（push `86884af` 触发：contracts / rust / web / java 四个 job） | **见下**“CI 结果”一节；本机没有 pytest 依赖、PostgreSQL 与 Playwright 运行环境（Docker Desktop 未运行、pip 与直连网络不可用），因此这四项不在本机执行，不把它们写成已通过 |
| 真实 PG 保留 | `PostgresScanRunRetentionIT`（新增，需 `OPSWEAVE_TEST_JDBC_URL`） | **新增 4 项**，本机无法执行；CI 的 java job 自带 PostgreSQL 17 与 VictoriaMetrics，覆盖：六个扫描后真实行数为 3、读取不改行数、被钉住的运行与其 pin 在多次 sweep 后仍可解析、预算按范围/来源/租户隔离 |

中间修正（都是本轮真实抓到的缺陷，不是测试写法）：

1. **sweep 与插入的顺序**：最初在插入新行之前清理，于是“预算”实际是“预算 + 一个刚结束的运行”，范围永远比预算多一行。改为**先写入新行、再在同一事务内清理**，并让**正在打开的运行计入预算**，范围才会收敛到预算。
2. **可删除集合的语义**：第一版把“受保护行”也算进预算分母，导致一个含 `RUNNING` 行的范围反而更早开始删除历史。现在预算只数“可以删的行”，受保护行不算分母也不被删。
3. **同毫秒运行的顺序**：为了让淘汰确定，曾把内存适配器的排序改成“插入序号”，但追溯的游标分页按 `started_at DESC, id DESC` 过滤，两种顺序一旦不一致就会出现“游标跳过行/重复行”——`SourceScanRunQuerySmoke` 立刻抓到。最终保留与分页一致的 `startedAt + id` 全序，改为让内存适配器**分配单调递增的开始时间**（同刻则加 1 微秒），并把“同一毫秒谁先被淘汰”从契约里剔除：契约是行数上限与受保护行。
4. **测试自身的错误假设**：`ScanRunRetentionSmoke` 初版有 5 处断言写错了语义（把“刚结束的运行”当成会被立即淘汰、把未结束运行当成会占预算分母、把 `retained()` 当成预算值）。这些都被真实运行暴露并改写为不变量断言。

保留边界：保留策略**不是修复路径**——它不改写任何存储结果、不退休对象、不补做缺失对账，失败的运行和成功的运行一样按时间淘汰；未结束的 `RUNNING` 行受保护，所以崩溃留下的遗留运行需要独立的租约/状态修复才能回收，本轮不提供自动修复。没有后台清理任务、跨租户总量治理、备份或物理擦除，也没有把预算用于自动决策。`source_sync_run` 之外的业务/授权元数据生命周期仍按 [ADR-048](adr/048-metadata-retention-backup-lifecycle.md) 保持开放。真实 Zabbix、真实模型、真实 IdP/TLS 与人工抽样审阅仍未验收。

M2 保持93%、M3 保持90%、M4 保持80%，**MVP约91%（±5个百分点），整体约58%**；本节补齐的是第43–53节反复记录的运维硬化缺口（不属于 M2–M4 退出门槛本身），因此不据此提高阶段估算。见 [ADR-047](adr/047-scan-run-retention.md)、[扫描运行契约](../contracts/source-scan-runs.md)、[操作说明](runbooks/source-scan-runs.md)。
