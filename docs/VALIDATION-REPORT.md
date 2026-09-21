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
