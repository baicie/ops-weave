# 本次交付验证报告 · OpsWeave v4

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








