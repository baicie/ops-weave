# OpsWeave · 观织

统一可观测与智能运维平台。**Java 平台 + Rust Agent Runtime + TypeScript 前端**。

> 这是 v4 设计配套初始化仓库，不是生产产品。本地已用 Rust 1.98.1、JDK 21、pnpm 10.34 做过构建与 Demo 诊断；**CI 是否在 GitHub 上通过、干净 clone 是否可复现，以当时 job / 验证报告为准。** 准确状态见 [验证报告](docs/VALIDATION-REPORT.md) 与 [实现状态](docs/IMPLEMENTATION-STATUS.md)。

## 阅读入口

| 文档 | 内容 |
|---|---|
| [路线图](docs/ROADMAP.md) | M0—M7 与三仓库职责；规划不是已完成 Tag |
| [v4 设计](docs/architecture/v4-design.md) | 服务边界、统一模型、接入、AI、治理、安全、分布式 |
| [初始化手册](docs/architecture/repository-guide.md) | Windows / Linux 启动与第一批研发任务 |
| [实现状态](docs/IMPLEMENTATION-STATUS.md) | 有源码、已验证、未实现分别是什么 |
| [前端兼容性](docs/FRONTEND-COMPATIBILITY.md) | Zeus / Zeus UI 已验证产物组合 |
| [依赖基线](docs/DEPENDENCIES.md) | 版本、锁文件、特性开关与升级边界 |
| [Agent 开发约束](AGENTS.md) | 供人和代码助手共同遵守 |

## 四个启动单元

| 目录 | 语言 | 当前内容 |
|---|---|---|
| `apps/platform-api` | Java 21 / Spring Boot | 模块装配、健康端点、其他业务默认拒绝 |
| `apps/ingestion-worker` | Java 21 / Spring Boot | 启动骨架；真实 Connector 待实现 |
| `apps/agent-runtime` | Rust / Tokio / Axum | 只读固定流程、动态 Skill 包、合成证据、Mock、可选 Rig 适配源码 |
| `apps/web-console` | Zeus / TypeScript / Vite | 诊断表单、开发代理、证据与缺失数据展示、其他模块占位 |

`modules/` 是 Java 领域模块；`contracts/` 是跨语言契约源；`extensions/skills/` 是配置，不是任意可执行代码。

## 最小本地演示：无需数据库、Python 服务或模型 Key

前提：可信来源安装 Rust stable（带 cargo/rustfmt/clippy）、Node 22.12+ 与 pnpm 10.34+；首次联网解析依赖。

```bash
# 在仓库根目录执行。首次需要生成真实锁文件并审查依赖。
cargo generate-lockfile
cargo fmt --all
cargo test --workspace --locked
cargo check --workspace --all-targets --all-features --locked

pnpm install --lockfile-only
pnpm install --frozen-lockfile
pnpm build:web
pnpm --filter @opsweave/web-console exec playwright install chromium
pnpm test:web

# Python 在这里仅生成本地随机开发凭据，并不是 Agent 服务。
python scripts/init_env.py
# Linux/macOS
bash scripts/demo.sh
# Windows PowerShell：.\scripts\demo.ps1
```

另一个终端：

```bash
pnpm dev:web
```

访问 `http://127.0.0.1:5173`，在表单中填入本机 `.env` 的 `OPSWEAVE_DEV_TOKEN`。不要分享 token；页面仅在内存持有，不写 localStorage。

该流程读取 **fixture 合成数据**，默认 `mock` 仅输出确定性示例，绝非真实 AI/RCA。`POST /api/v1/diagnoses` 是同步本地示例，无持久队列、历史恢复或生产授权。Rig 真模型示例需额外开关，见初始化手册。

不使用 Python 的替代方式：通过系统密码管理器生成至少 32 字节随机 token，手动设置 `OPSWEAVE_MODE=demo`、`OPSWEAVE_LISTEN=127.0.0.1:8090`、`OPSWEAVE_DEV_TOKEN` 和 `OPSWEAVE_PROVIDER=mock`，然后 `cargo run -p opsweave-agent-runtime`。

## 安全默认值

未设置 `OPSWEAVE_MODE=demo` 时为 `closed`：`/healthz` 可用，`/readyz` 和业务请求拒绝。Demo 只能监听 loopback，固定映射至 `tenant-demo/inc-demo`，不接受请求体覆盖 tenant/user/权限/模型/工具。

真实平台 API 适配、OIDC 和多租户授权未实现；**不能通过把 demo 监听地址改成公网来部署生产**。目录中有 MCP 探测示例，不代表已开放动态 MCP 执行。默认没有 shell、SQL、任意 HTTP 或动作 Tool。

## 其他检查与 Java 初始化

```bash
python -m pip install -r requirements-dev.txt
python scripts/check_repo.py
python scripts/check_java_domain.py
python -m pytest tests/contracts

# 需要 Gradle 8.14.3，随后统一使用生成的 Wrapper
gradle wrapper --gradle-version 8.14.3 --distribution-type bin
./gradlew :apps:platform-api:dependencies :apps:ingestion-worker:dependencies --write-locks
./gradlew :apps:platform-api:bootJar :apps:ingestion-worker:bootJar
```

Windows 将 `./gradlew` 改成 `.\gradlew.bat`。不要把 `javac` 领域检查当作 Spring Boot 完整构建。首次审查后提交 Cargo.lock、pnpm-lock.yaml、Wrapper、Gradle lockfile，并把 Rust toolchain 固定至测试通过的精确版本。

CI 有意要求锁文件，不自动替你选择新版本。仓库没有伪造锁文件或 Wrapper JAR。

## Git 初始化

```bash
git init -b main
git add .
git commit -m "chore: initialize OpsWeave Java platform and Rust agent"
```

不含 `.git`，不预设远程地址，不修改 Git 用户配置。发布前确定项目许可证；`com.acme.opsweave` 是待替换的组织命名空间，不代表域名归属。

第一条真实业务链见 [路线图](docs/ROADMAP.md)：**登录授权 → 只读 CMDB/Zabbix → Entity/Observation → 指标/告警/Incident → 只读 Tool → Rust 诊断 → AIInsight**。当前 Demo 仍是 loopback 合成数据。
