# OpsWeave 仓库架构与初始化手册 · v4

> 本手册配套 Java + Rust + TypeScript 模板。首先阅读 `docs/VALIDATION-REPORT.md` 与 `docs/ROADMAP.md`；它不是全功能产品安装手册。所有命令从仓库根目录执行，另有标注除外。

## 1. 目录与所有权

```text
opsweave/
├── Cargo.toml / rust-toolchain.toml    # 一个 Cargo workspace
├── settings.gradle.kts               # 一个 Gradle 多项目
├── package.json / pnpm-workspace.yaml / pnpm-lock.yaml  # 一个 pnpm workspace
├── apps/
│   ├── platform-api/                  # Java 控制面与业务 API 组装
│   ├── ingestion-worker/              # Java 采集任务组装
│   ├── agent-runtime/                 # Rust 唯一 Agent 执行服务
│   │   ├── Cargo.toml
│   │   ├── src/
│   │   │   ├── domain.rs              # 请求、Context、Evidence、Insight、RunState
│   │   │   ├── ports.rs               # 模型与平台读取端口
│   │   │   ├── api.rs / config.rs     # HTTP / 默认拒绝 / 本地身份
│   │   │   ├── policies.rs            # 资源权限、时间范围、共享调用预算
│   │   │   ├── context.rs             # 截止时间、证据筛选与引用检查
│   │   │   ├── skills.rs              # 启动时加载版本化配置包
│   │   │   ├── workflows.rs           # 固定只读诊断链
│   │   │   └── adapters/              # Fixture / Mock / 可选 Rig
│   │   ├── examples/mcp_probe.rs      # 独立本机 MCP 探测，不挂入 Agent
│   │   └── tests/runtime_tests.rs
│   └── web-console/                   # Zeus 诊断表单与模块占位
├── modules/
│   ├── shared-kernel/                 # 极少量跨领域值类型
│   ├── identity/                      # 权限/租户，生产实现待补
│   ├── catalog/                       # EntityType / MetricDefinition
│   ├── inventory/                     # Entity / ExternalLink / Observation / Relation
│   ├── integration/                   # Connector SPI / 游标契约
│   ├── telemetry/                     # 指标/日志查询接口
│   ├── alerting/                      # 告警及其规则
│   ├── incident/                      # 故障、时间线、Change
│   ├── ai-control/                    # Skill 发布、模型策略、Insight 业务归属
│   ├── automation/                    # 审批/动作边界
│   └── audit/
├── contracts/
│   ├── schemas/v1/                    # 平台基础契约、诊断输入输出
│   ├── schemas/v2/                    # 带 availableAt/incidentId 的证据上下文
│   ├── openapi/                       # draft平台API + runtime示例API
│   ├── tools/                         # 真实 Tool 实现状态声明
│   ├── events/
│   └── examples/                      # 合成示例与版本校验
├── extensions/
│   ├── connectors/{cmdb,zabbix}/      # manifest/契约起点，不是完整连接器
│   ├── mappings/
│   ├── policies/
│   └── skills/
│       ├── incident-diagnosis/        # 当前 Rust 可加载包
│       └── incident-summary/          # 前期设计包，不自动执行
├── db/                               # SQL 原型，不自动迁移生产
├── deploy/                           # Compose/Docker/能力profile；Helm待做
├── scripts/                          # 检查、初始化、演示入口
├── tests/                            # 跨语言契约与 Java 纯领域检查
├── docs/                             # v4/ADR/状态/验证/开发规程
└── .github/workflows/ci.yml / Jenkinsfile
```

一个领域一个 Gradle 模块；一个 Rust Runtime crate 内部模块化。不要一开始把每个函数拆成服务或 crate。Java 与 Rust 只共享版本化接口定义，不共享数据库实体类或框架对象。

`com.acme.opsweave` 是模板 namespace；实际组织确定后可整体改包名。仓库名称固定 `opsweave`，不要在真实 Git 仓库名字后追加 v4。

## 2. 先准备环境

| 用途 | 需要 |
|---|---|
| Rust 示例 | Rust stable、Cargo、rustfmt、clippy，联网依赖解析 |
| Web | Node 22.12+，pnpm 10.34+ |
| Java | JDK21、首次 Gradle8.14.3；随后用 Wrapper |
| 契约/辅助检查 | Python3.12+；这不是部署中的 Agent |
| 容器验证 | Docker Compose v2，按需 |

`rust-toolchain.toml` 的 `stable` 是首次初始化选择器，不能当作可重复发布锁。初次构建通过后，将 channel 改成该次测试的精确版本，再写入 CI 和 Docker 的构建镜像版本/digest。

不运行远程一键脚本、不修改全局 Git 配置、不把 API Key 写入命令示例或提交到仓库。

## 3. 先跑已可独立执行的契约和领域检查

```bash
python -m venv .venv
# Linux/macOS: source .venv/bin/activate
# PowerShell: .\.venv\Scripts\Activate.ps1
python -m pip install -r requirements-dev.txt
python scripts/check_repo.py
python scripts/check_java_domain.py
python -m pytest tests/contracts
```

这些检查覆盖 JSON/YAML、Schema、跨语言样例和纯 Java 领域规则；**不覆盖 Rust 编译、Spring 启动、数据库授权、模型真实性**。

## 4. Rust 首次初始化

```bash
rustc --version
cargo --version
cargo generate-lockfile
cargo fmt --all
cargo test --workspace --locked
cargo check --workspace --all-targets --all-features --locked
cargo clippy --workspace --all-targets --all-features --locked -- -D warnings
```

这里的 `cargo fmt --all` 是首次格式化；此后 CI 使用 `cargo fmt --all -- --check`。本交付环境无 Rust 工具链，因此不能保证首轮构建零错误；若上游实际依赖/API兼容性有差异，按固定版本文档修复适配器，不要把 domain 污染成框架类型。

默认 feature 不包含 Rig 和 MCP 执行；可选 feature 的依赖也需一次真实解析进入同一个 Cargo.lock。`--all-features` 检查不得省略，避免 Mock 通过而真实适配无法编译。

## 5. 前端依赖与编译

```bash
pnpm install --lockfile-only
pnpm install --frozen-lockfile
pnpm build:web
pnpm --filter @opsweave/web-console exec playwright install chromium
pnpm test:web
```

首次审查实际依赖与安装脚本后提交根 `pnpm-lock.yaml`。后续统一 `pnpm install --frozen-lockfile`，不要在 CI 自动升级依赖。不要混用 npm 生成 `package-lock.json`。

Vite8 与 Zeus Vite 插件为本模板选择。具体版本通过 lockfile 固定。前端尚未接入生产 OIDC 或 Java 真实业务 API。不引入 React。

## 6. 本地演示（Mock，不调用模型）

### Linux/macOS

```bash
python scripts/init_env.py
bash scripts/demo.sh
```

### Windows PowerShell

```powershell
python scripts/init_env.py
.\scripts\demo.ps1
```

脚本进入 `demo` 模式，监听 `127.0.0.1:8090`，只加载固定的开发身份和合成 Incident。另开终端：

```bash
pnpm dev:web
```

访问 `http://127.0.0.1:5173`，从本机 `.env` 读取 `OPSWEAVE_DEV_TOKEN` 填入密码输入框。开发代理 `/agent` 转发至 Rust 本机端口；token 不落浏览器持久存储。

流程预期：两个受控合成读取 → ContextPack → Mock JSON → 双层Schema与证据引用检查 → 页面展示。输出带 `dataMode=synthetic-fixture`、`modelProvider=mock`、`verification=reference_integrity_only`。HTTP成功只说明流程完成，不说明候选根因属实。

直接调用示例（Bash；使用已设置的本地 token）：

```bash
curl --fail-with-body http://127.0.0.1:8090/api/v1/diagnoses \
  -H "Authorization: Bearer $OPSWEAVE_DEV_TOKEN" \
  -H "Content-Type: application/json" \
  --data-binary @contracts/examples/diagnose-request.json
```

诊断请求不允许带 `tenantId`、`userId`、权限、工具、模型等控制字段。未知字段或日期格式错误返回422；资源越权403；无有效凭据401；限流429；流程关闭503。详细接口在 `contracts/openapi/agent-demo.yaml`。

`demo.sh` 在子进程中设置 token，不会自动导出到另一个终端；curl 前需在该终端自行安全设置。停止演示后清除环境变量并关闭进程。

## 7. 显式测试 Rig 真实模型（仍然仅使用合成平台数据）

先保证 `cargo check --features rig-provider` 通过。使用自己获准调用的模型标识和密钥；不要把密钥发进聊天或写进代码。模型调用可能计费，包含合成上下文与用户问题；测试问题中不要放生产信息。

Bash：

```bash
export OPSWEAVE_MODE=demo
export OPSWEAVE_LISTEN=127.0.0.1:8090
export OPSWEAVE_DEV_TOKEN="$(sed -n 's/^OPSWEAVE_DEV_TOKEN=//p' .env | head -1 | tr -d '\r')"
export OPSWEAVE_PROVIDER=rig-openai
export OPSWEAVE_ALLOW_MODEL_EGRESS=true
export OPSWEAVE_LLM_MODEL='填写你账号实际可用的模型标识'
# OPENAI_API_KEY 由当前终端的密码管理器/密钥注入机制设置
cargo run -p opsweave-agent-runtime --features rig-provider
```

PowerShell：

```powershell
$env:OPSWEAVE_MODE = "demo"
$env:OPSWEAVE_LISTEN = "127.0.0.1:8090"
$line = Get-Content .env | Where-Object { $_ -match '^OPSWEAVE_DEV_TOKEN=' } | Select-Object -First 1
$env:OPSWEAVE_DEV_TOKEN = $line.Substring('OPSWEAVE_DEV_TOKEN='.Length)
$env:OPSWEAVE_PROVIDER = "rig-openai"
$env:OPSWEAVE_ALLOW_MODEL_EGRESS = "true"
$env:OPSWEAVE_LLM_MODEL = "填写你账号实际可用的模型标识"
# OPENAI_API_KEY 通过终端的安全密钥注入设置
cargo run -p opsweave-agent-runtime --features rig-provider
```

不使用 demo.sh 启动真实模型，因为该脚本故意将 provider 固定为 mock。没有透明fallback。Provider失败或输出不符合结构返回错误，不换 Mock 冒充成功，也不让模型自行调用执行工具。

本模板 Rig 示例采用 Prompt 要求JSON + 服务端Schema验证，**不是承诺所有模型都支持原生严格结构化输出**。原生output schema模式与用量费用采集属于后续适配测试。

## 8. Skill 如何改

当前加载：`extensions/skills/incident-diagnosis/`。

修改 `prompt.md` 或有权限的 `skill.json` 参数、更新version后，重启服务重新加载；不需要重新编译Rust。运行结果记录version和内容digest。运行时会限制工作流模板、工具集合、预算与输出schema，不接受任意执行代码或远程schema引用。

当前模板不支持 Skill 创建UI、在线发布、热更新、签名验证或任意DAG。下一步平台实现发布 API 后，以不可变版本/内容digest将包交给Rust，不能原地覆盖一个运行中的版本。

## 9. MCP 试接（与 Agent 隔离）

先启动你信任的本机 MCP HTTP server，然后：

```bash
export OPSWEAVE_MCP_PROBE_URL=http://127.0.0.1:8000/mcp
cargo run -p opsweave-agent-runtime --features mcp-client --example mcp_probe
```

示例仅允许这一个本机地址并列出工具名称，不执行工具、不处理生产OAuth。生产 MCP 接入需额外完成准入、凭据隔离、网络限制、OAuth/会话生命周期、工具指纹、权限与请求/响应预算，再注册为 Tool Adapter。

不能把不可信 MCP 输出、名字和注解直接当作系统指令或授权。

## 10. Java 与数据库初始化

首次安装 Gradle8.14.3 后：

```bash
gradle wrapper --gradle-version 8.14.3 --distribution-type bin
./gradlew :apps:platform-api:dependencies :apps:ingestion-worker:dependencies --write-locks
./gradlew :apps:platform-api:bootJar :apps:ingestion-worker:bootJar
./gradlew :apps:platform-api:bootRun
```

Windows替换为 `.\gradlew.bat`。另一个终端可启动 Worker。

```bash
docker compose --env-file .env -f deploy/compose/compose.yaml up -d postgres
```

当前Java服务只提供健康检查，未连接PG，也不会自动应用SQL原型。表、RLS、Outbox等需要完成迁移、最小权限账号和集成测试后才能接入真实业务。PG镜像内 `POSTGRES_USER` 初始化角色不能直接用作生产应用角色。

## 11. 容器与按需启动

生成全部锁文件后：

```bash
docker compose --env-file .env -f deploy/compose/compose.yaml --profile apps --profile agent --profile web build
docker compose --env-file .env -f deploy/compose/compose.yaml --profile apps --profile agent --profile web up -d
```

该Compose提供进程组装，不是演示全链路或生产环境。Rust容器默认closed，仅健康可用；默认Mock镜像不包含rig-provider。静态Nginx前端未配置生产BFF代理。需要交互演示时使用第6节的本机Rust + Vite。

不要为了在容器里跑demo而取消loopback保护；应先完成真正的认证模式。Helm目录标注待实现，不提供伪生产Chart。

## 12. 提交与发布前的门禁

```bash
python scripts/check_release_inputs.py
# 完成首次fmt之后：
cargo fmt --all -- --check
cargo test --workspace --locked
cargo check --workspace --all-targets --all-features --locked
cargo clippy --workspace --all-targets --all-features --locked -- -D warnings
pnpm install --frozen-lockfile
pnpm build:web
pnpm --filter @opsweave/web-console exec playwright install chromium
pnpm test:web
./gradlew :apps:platform-api:bootJar :apps:ingestion-worker:bootJar
```

应提交锁文件与 Wrapper；不提交 `.env`、Key、真实日志、target、node_modules、构建产物或本地证据。Wrapper JAR 是例外：从可信Gradle生成并验证后作为构建基础设施提交。生产记录依赖版本、SBOM、镜像digest与安全扫描结果。

模板CI在缺锁文件时失败是故意的；不把生成锁文件混入日常CI，避免构建依赖漂移。

## 13. 第一批开发任务

顺序与 ID 以 `docs/ROADMAP.md` / `docs/NEXT-TASKS.md` 为准。M0 已关闭。不要先做 Integration Copilot 或再迁前端。

| 顺序 | 任务 | 验收 |
|---|---|---|
| 0 | 构建/锁文件/CI（已完成于 `b5404a8`） | Mock 及可选适配能编译；Actions 四 job 通过 |
| 1 | OIDC与平台资源授权（OW-R04） | 本机 Dev Principal + allow/deny 已落地；生产 OIDC 未接 |
| 2 | PipelineDefinition 与 Catalog（OW-R11） | Host 线性定义已有；Preview/发布指针仍缺；尚无 Copilot |
| 3 | Zabbix Host 经已发布流水线（OW-R07） | 分页写入 PostgreSQL；完整快照才标 INACTIVE。JSON-RPC 客户端已有；未对厂商实例取数 |
| 4 | Metric/Alarm/Incident持久化 | 数据类型与单位正确、外部恢复事件闭环 |
| 5 | Java Tool Gateway + Rust HTTP Port | 按资源授权、超时、预算、无 Fixture 回退 |
| 6 | Insight入库与证据浏览 | 真实来源、过期重查、假设与事实分开 |
| 7 | Skill配置UI与不可变发布 | draft/test/publish、撤销与 run 固定版本 |
| 8 | AIRun持久执行 | 租约/失效/恢复/取消/重放与授权再检查 |

不要先开发专家团、Shell 沙箱、接入 Copilot 或通用可视化编程平台。
