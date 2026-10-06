# OpsWeave · 观织

固定日志发布版本支持有界历史独立投影，先检查完整范围和摘要，再明确确认；正文按50条明确查看，原持续任务及检查点保持。见[日志历史重放](docs/runbooks/workflow-log-replay.md)和实际[验证报告](docs/VALIDATION-REPORT.md)§137；完整后续目标仍进行。

固定发布指标可在运行记录中进行有界历史投影重建，独立权限和两步摘要确认、原UUID回查及未知输出核验见[指标历史重放](docs/runbooks/workflow-metric-replay.md)。独立系列不改变持续任务或检查点；完整后续目标仍进行。

2026-10-06 §135：固定版本检查历史与稳定分页、V055、可信范围游标和复用节点详情已实现。运行记录默认读取，较早固定版本可明确直链；原预览/测试及未测量状态保持。实际验证及本机部署见[状态](docs/IMPLEMENTATION-STATUS.md)、[报告](docs/VALIDATION-REPORT.md)和[ADR-097](docs/adr/097-scoped-recorded-workflow-history.md)。完整目标active，重放/通知/保留/生产容量及租约身份继续。

2026-10-06 §134：固定版本增加显式质量阈值，配置CAS/原UUID回执与有界证据评估由服务器执行；共享请求容器/纯Panel及紧凑明暗布局已实现。1774契约、83领域main、58套273项平台、Rust两构建及Web构建通过；相关177整组及最终局部样式后的25专项实际验证。平台/Runtime/Web均200/200，原数据只读保持，阈值未自动启用。完整目标active，通知/历史/生产容量及租约身份继续。见[状态](docs/IMPLEMENTATION-STATUS.md)、[验证](docs/VALIDATION-REPORT.md)与[ADR-096](docs/adr/096-configurable-workflow-quality-thresholds.md)。

2026-10-05 §133：本地工作流FIFO实测入队/出队，旧排队值保持为空，纯展示组件与当前状态/可信授权重查已实现。1735契约、82领域main、57套271项平台、Rust两构建和Web构建/152相关浏览器场景实际通过。新版平台31404就绪200，原主环境数据只读保持；完整目标active，阈值/历史/生产容量及租约身份继续。见[状态](docs/IMPLEMENTATION-STATUS.md)、[验证](docs/VALIDATION-REPORT.md)与[ADR-095](docs/adr/095-measured-local-workflow-dispatch.md)。

2026-10-05 §132：运行诊断新增实测来源失败分母和闭合元数据变化原因，旧记录保持；纯展示组件、现有V049及固定来源/目标权限门禁已实现。1708契约、81领域main、56套268项扩大平台及最终HTTP3、Rust两构建、Web构建/132相关浏览器场景已实际验证。新版平台10032就绪200，主环境原数据只读保持，queueWait仍未测量，完整目标active。见[状态](docs/IMPLEMENTATION-STATUS.md)、[验证](docs/VALIDATION-REPORT.md)与[ADR-094](docs/adr/094-measured-source-port-diagnostics.md)。


2026-10-05 §131：单次样本UNKNOWN可明确终止确认，原证明/点值/日志保持；共享前端容器/Panel、V053和服务器原UUID/权限/核验竞态门禁已实现。真实平台56套266项、最终HTTP专项4项、1675契约、80领域main、Rust两构建及Web构建/69相关浏览器场景已实际验证。新版平台30856就绪200，主环境原数据只读保持，完整目标active。见[状态](docs/IMPLEMENTATION-STATUS.md)、[验证](docs/VALIDATION-REPORT.md)与[ADR-093](docs/adr/093-explicit-sample-output-closure.md)。


2026-10-05 第130节：旧日志FAILED缺少明确未写入证据时，质量报告及原UUID详情投影为UNKNOWN/OUTPUT_UNCONFIRMED，预期条目计入未知、不计拒绝或成功；原批次体、游标和输出保持。按版本持续日志状态增加有界uncertainBatchIds，前端显示结果待确认，恢复/替换仍受服务器门禁，不提供原FAILED不支持的核验。当前可信身份可明确终止恢复，原UUID回执幂等、代数/时间/容量/父对象/本人范围保持；终止后更高发布版本可明确START，历史终止事件和原未知质量继续。最近20条之外的原批次可查UUID后终止，关闭详情不丢失待确认原命令。

实际通过：1650契约；79个纯Java main/2617打印检查（质量35）；真实平台55套255项零失败/错误/跳过；Rust --locked默认41/all-features49；TypeScript/build。最终前端实现覆盖67个不同相关协议Fixture场景：整组三文件66通过/1终态文案断言失败，修正断言后该专项1通过，未再宣称整组67同时通过；此前65整组通过不能替代最终增量。三宽度/明暗未知展示与原UUID边界已执行，宽屏亮色和窄屏暗色截图已查看，截图在仓库外。

主平台新包PID29744实际200/200，Runtime/Web仍200/200；Worker端口200/200但非当前管理器所有，未操作。两份实际按版本状态Schema和V050/V051/V052存在复核通过；原19接入/19创建回执/2凭据、21流程、原主机/12指标点/日志2+1及暂停诊断只读保持，无主环境业务POST。旧1000条误分类Fixture实际日志仍1000，原FAILED体与记录保持。真实独立测试租户证明终止前后实际日志存在、写入一次、未知统计及原回执保持。

完整目标仍active：单次样本UNKNOWN、来源异常分母、单位漂移细分、queueWait、阈值告警、同版本运行历史/重放、保留与生产容量、租约/fencing/HA及生产身份/独立OIDC持续LOG验收继续。无新迁移、数据库、启动单元、依赖升级或提交/推送。

见[旧结果决策](docs/adr/092-legacy-log-outcome-uncertainty.md)、[终止恢复](docs/runbooks/workflow-recovery.md)和[验证报告](docs/VALIDATION-REPORT.md)。

2026-10-05 第129节：已停止/失败的固定任务可明确 START 更高发布版本，保留不可变终态历史与原批次；运行中、降级、旧版本 RESUME 及未确认输出不能绕过门禁。新任务使用当前代数、授权和新的窗口/扫描。V051只存终态元数据，V052保留旧日志结果正文并增加明确未写入证据门禁；写入成功后回读失败保留 UNKNOWN。旧FAILED缺少该证据时不能恢复/替换，历史质量仍按原记录展示，不据FAILED推断未发生写入。沿用既有PG和四启动单元。

实际通过：1643契约；79个纯Java main/2612打印检查（版本33、连续日志47）；平台55套254项零失败/错误/跳过；Rust --locked默认41/all-features49；TypeScript/build及129相关浏览器场景。主平台新包已启动200/200，V050/V051/V052实际结构与两份按版本状态Schema复核通过；原19接入/19创建回执/2凭据、21流程、旧主机/12指标点/日志2+1及暂停诊断只读保持，无业务POST。

完整目标仍active：单次样本UNKNOWN、旧拒绝记录的完整不确定性投影/显式处置、来源异常分母、单位漂移细分、queueWait、阈值告警、同版本运行历史/重放、保留与生产容量、租约/fencing/HA及生产身份/独立OIDC持续LOG验收继续。没有新增数据库或启动单元，没有提交/推送。

见[任务版本更新](docs/runbooks/workflow-task-versions.md)、[ADR-091](docs/adr/091-explicit-terminal-task-version-replacement.md)和[验证报告](docs/VALIDATION-REPORT.md)。


第128节源码进展：固定任务明确终止恢复、原回执和新旧版本隔离已实现；当时最终真实联测与主平台部署待依赖恢复，第129节已完成；不能将编译或协议Fixture等同运行验收。见[实现状态](docs/IMPLEMENTATION-STATUS.md)、[终止恢复](docs/runbooks/workflow-recovery.md)及验证报告§128。

工作流检查统计见[操作说明](docs/runbooks/workflow-diagnostics.md)：持续指标/日志和固定连接主机分页的逐次处置及节点异常，部分检查保留未知，来源未返回列表不推测数量；主机统计按原实体范围校验读取。

工作流已发布版本可展开[运行质量与异常](docs/runbooks/workflow-quality.md)，查看逐批确认/未知、迟到与去重，并按UUID查原证明；缺失分母保留“—”。实际检查与未完成范围见验证报告§125。

统一可观测与智能运维平台。**Java 平台 + Rust Agent Runtime + TypeScript 前端**。

固定日志工作流支持持续窗口采集、停止/恢复、上一分钟迟到补采和确认后进度；日志记录每页50条。见[操作说明](docs/runbooks/workflow-log-streams.md)，实际验证及剩余范围见验证报告§124。

> 这是 v4 设计配套初始化仓库，不是生产产品。本地已用 Rust 1.98.1、JDK 21、pnpm 10.34 做过构建与 Demo 诊断。提交 `b5404a8` 的 GitHub Actions `opsweave-template`（contracts / rust / java / web）已通过。准确状态见 [验证报告](docs/VALIDATION-REPORT.md) 与 [实现状态](docs/IMPLEMENTATION-STATUS.md)。

## 阅读入口

完整日志窗口内部端口见[契约](contracts/workflow-log-windows.md)和[ADR-085](docs/adr/085-bounded-complete-log-window-ports.md)。实际1,000条Synthetic Fixture来源/存储已联测；持续任务控制、证明和检查点尚未接通，准确记录见验证报告§123。

| 文档 | 内容 |
|---|---|
| [路线图](docs/ROADMAP.md) | M0—M7 与三仓库职责；规划不是已完成 Tag |
| [v4 设计](docs/architecture/v4-design.md) | 服务边界、统一模型、接入、AI、治理、安全、分布式 |
| [初始化手册](docs/architecture/repository-guide.md) | Windows / Linux 启动与第一批研发任务 |
| [实现状态](docs/IMPLEMENTATION-STATUS.md) | 有源码、已验证、未实现分别是什么 |
| [前端兼容性](docs/FRONTEND-COMPATIBILITY.md) | React / shadcn 当前基线与历史产物验证 |
| [运维控制台](docs/runbooks/admin-console.md) | 工作台、查询范围、导航与主题 |
| [工作流任务控制](docs/runbooks/workflow-runtime-control.md) | 启动/停止原回执、未知结果确认和当前状态 |
| [依赖基线](docs/DEPENDENCIES.md) | 版本、锁文件、特性开关与升级边界 |
| [Agent 开发约束](AGENTS.md) | 供人和代码助手共同遵守 |

## 四个启动单元

| 目录 | 语言 | 当前内容 |
|---|---|---|
| `apps/platform-api` | Java 21 / Spring Boot | Dev Principal 保护的 Entity/Host、Item 目录/绑定与有界 History 读取；默认 closed，无生产 OIDC |
| `apps/ingestion-worker` | Java 21 / Spring Boot | 默认关闭的本机 History 采集流；VictoriaMetrics 批写/回读、PostgreSQL checkpoint、重叠去重 |
| `apps/agent-runtime` | Rust / Tokio / Axum | 只读固定流程、动态 Skill 包、合成证据、Mock、可选 Rig 适配源码 |
| `apps/web-console` | React / shadcn / TypeScript / Vite | 运维工作台、数据接入、工作流、资产与故障查询、只读诊断及证据展示 |

`modules/` 是 Java 领域模块；`contracts/` 是跨语言契约源；`extensions/skills/` 是配置，不是任意可执行代码。

## 交互式启动、重启和停止（跨平台）

源码保留在 `scripts/devctl`，日常通过已编译的 `opsweave-dev` 管理四个应用。首次 `pnpm dev:build` 构建本机产物（构建需要 Go 1.23+）；之后 `pnpm ops` 或 `pnpm dev` 打开交互菜单，运行管理器无需 Go。退出菜单后服务继续运行。

命令示例：`pnpm ops restart web`、`pnpm ops start all`、`pnpm ops status`、`pnpm ops logs platform`、`pnpm ops stop all`。这些命令直接调用 `dist/devctl/<系统>-<架构>/opsweave-dev`（Windows 为 `.exe`），只透传参数，不隐式编译。`pnpm dev:package` 构建 Windows/Linux/macOS 的 amd64/arm64 六份产物及 SHA256 校验文件；二进制也可直接运行。应用本身仍需要 Java/Rust/Node 工具链和既有依赖环境。

默认读取本机 `.env.dev` 等配置；可参考 [.env.dev.example](.env.dev.example)。显式 `pnpm ops start all --demo` 使用 memory/Fixture/Mock 演示，Worker 采集关闭。详细用法、产物位置与配置边界见 [开发服务管理器](docs/runbooks/dev-manager.md)。

Go管理器启动的本地开发预览自动建立平台会话，打开页面无需填写Token；刷新会建立新会话。后端随机开发Token留在Vite服务端，业务请求继续经过Java身份与权限校验。仅支持 `127.0.0.1`，正式OIDC模式仍使用登录，见[本地会话](docs/runbooks/local-session.md)。

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

真实平台 API 已有本机 Dev Principal 与资源授权切片，**生产 OIDC 仍未实现**；不能把 demo token 或 loopback 服务改成公网部署。目录中有 MCP 探测示例，不代表已开放动态 MCP 执行。默认没有 shell、SQL、任意 HTTP 或动作 Tool。

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

固定指标工作流的实际样本写入、原请求结果确认和点回读见[运行手册](docs/runbooks/workflow-metric-output.md)。持续完整窗口与checkpoint继续，样本写入不等于完整S5验收。
