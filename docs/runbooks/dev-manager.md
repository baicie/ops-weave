# 跨平台开发服务管理器

Go 启动器管理现有四个应用：`platform`、`runtime`、`worker`、`web`。它是开发工具，不是新增业务服务。仅使用 Go 标准库，没有额外 Go 依赖或 `go.sum`。Windows 使用隐藏进程与进程树停止；Linux/macOS 使用独立进程组、先 SIGTERM，再按需 SIGKILL。平台构建直接调用仓库 Gradle Wrapper 的 Java 入口，Web 直接运行已安装的 Vite，避免 `.bat`/`.sh` 启动差异。

## 运行

已编译的管理器运行无需 Go，源码构建需要 Go 1.23+。应用仍需 Java 21、固定 Rust 工具链、Node 22.12+ 和已经安装的前端依赖。Windows Rust 编译还需要可用的 MSVC 链接器与 Windows SDK 环境，可从 Visual Studio Developer PowerShell 启动；自定义缓存位置应保留 `GRADLE_USER_HOME`、`CARGO_HOME` 等环境变量。首次执行 `pnpm install --frozen-lockfile`；启动器不会安装或升级依赖锁。数据库等依赖使用已有环境，启动器不会创建数据库或执行 Compose。

首次在仓库根目录构建一次，然后打开菜单：

```text
pnpm dev:build
pnpm ops
```

已有当前平台产物时只需 `pnpm ops`，也可使用 `pnpm dev`。npm入口通过无依赖Node适配器选择当前系统/架构的二进制，参数原样传递，保留交互输入、输出、信号和退出码；不运行 `go run`、不隐式构建或下载。缺少产物时退出1并提示执行 `pnpm dev:build`。菜单提供全部/单组件启动、停止、重启、状态和最近 80 行日志。退出菜单或关闭当前终端后，服务继续运行，再打开菜单即可管理。

命令行支持：

```text
pnpm ops start all
pnpm ops restart web
pnpm ops stop worker
pnpm ops status
pnpm ops logs platform
pnpm ops stop all
```

`pnpm dev:start`、`pnpm dev:restart`、`pnpm dev:status`、`pnpm dev:stop` 提供全部组件的快捷入口，`pnpm dev:logs platform` 查看指定组件日志。也可使用 `npm run ops -- restart web`。停止全部也会关闭后台管理进程。重启采用停止后重新构建/启动应用，读取最新环境文件；Web 是 Vite 开发模式，可热更新。已有同模式服务的 `start` 是幂等操作；切换 local/demo 必须显式 `restart`，不会把仍在运行的真实模式展示成演示模式。

## 产物与源码

本机产物默认在 `dist/devctl/<系统>-<架构>/`，可直接运行，无需 Node适配器或Go。例如：

```text
# Windows PowerShell x64
.\dist\devctl\windows-amd64\opsweave-dev.exe restart web

# Linux x64
./dist/devctl/linux-amd64/opsweave-dev restart web

# macOS Apple Silicon
./dist/devctl/darwin-arm64/opsweave-dev restart web
```

构建六种平台的分发产物：

```text
pnpm dev:package
```

生成 `windows-amd64`、`windows-arm64`、`linux-amd64`、`linux-arm64`、`darwin-amd64`、`darwin-arm64` 六个目录，以及 `SHA256SUMS` 和 `README.txt`。x64 对应 amd64，macOS 对应 darwin。构建固定 `CGO_ENABLED=0`，使用 `-trimpath` 和 `-ldflags=-s -w`；不携带本地凭据或应用配置，不升级依赖。产物已被 `dist/` 忽略规则覆盖，不提交二进制。CI三系统矩阵构建本机产物并提供下载附件；是否通过以实际CI结果为准。

已下载产物可放回仓库对应 `dist/devctl/<系统>-<架构>/` 目录，随后直接用 `pnpm ops`。Linux/macOS下载后如执行权限丢失，先 `chmod +x <二进制路径>`。管理器需要 OpsWeave 仓库和应用工具链，不是四个业务应用的独立安装包；一个二进制不能在不同系统或架构间通用。源码保留在 `scripts/devctl`，修改后显式运行 `pnpm dev:build`；开发时也可 `go -C scripts/devctl run . status`。

## 配置与演示

默认 `local` 模式依次读取根目录 `.env`、`.env.dev`、`.env.dev.<组件>`，最后覆盖当前终端的环境变量。文件值按字面读取，不执行 shell、不展开 `${变量}`。`.env.dev.example` 是可提交模板；复制后的文件被 Git 忽略。真实模式缺少 JDBC 配置会在启动前报错，来源断连或数据库失败不会选择 Fixture。

不连接数据库/来源/模型的显式演示：

```text
pnpm ops --demo
pnpm ops start all --demo
```

`--demo` 强制平台 memory/fixture、Runtime demo/mock、Worker 采集关闭和模型出网关闭，优先于所有环境配置。固定身份 `tenant-demo/user-demo`、固定诊断 Incident 以及随机开发 Token 边界保留；凭据生成在 `.tmp/dev/demo.env`，跨组件与重启共用，不打印到终端。平台页面自动建立本地会话，凭据留在服务端；独立Fixture Runtime演示仍使用其显式演示凭据。它不是生产认证。memory 模式不提供依赖 PG 的模型目录、工作流等页面能力。

`local` 按既有配置运行。配置真实模型不会触发诊断；显式请求诊断仍由平台策略/费用准入控制。启动 Worker 只有在 `OPSWEAVE_HISTORY_ENABLED=true` 时才采集，默认 schema-mode 为 `verify`；本工具不会自动迁移 Worker 库。已启用流的名称和起点应固定，不因重启而重置。

## 自动本地会话

平台为 `OPSWEAVE_AUTH_MODE=dev` 时，`start/restart web` 或包含Web的全部启动会读取平台实际配置，将随机开发凭据放入Vite的私有 `OPSWEAVE_WEB_LOCAL_TOKEN` 环境变量。它不是 `VITE_*` 变量，不进入浏览器或生产构建。首次升级源码后先 `pnpm dev:build`，再 `pnpm ops restart web`；打开或刷新平台页面自动建立30分钟会话，Token输入栏消失。

显式OIDC设置保留登录；`.env.dev.web` 设置 `OPSWEAVE_WEB_LOCAL_SESSION=false` 可使用原来的手动开发表单。直接 `pnpm dev:web` 没有私有桥接凭据时也保留手动模式。配置不完整会在重启之前报错，不改成匿名访问。自动会话仅支持loopback开发服务器，过期或401显示“重新连接”，失败不自动重试、不选择Mock。详细协议与范围见[本地会话](local-session.md)。

## 状态与进程所有权

| 组件 | 地址 | 存活 / 就绪 |
|---|---|---|
| platform | 127.0.0.1:8080 | `/actuator/health/liveness` / `/actuator/health/readiness` |
| worker | 127.0.0.1:8081 | 同上 |
| runtime | 127.0.0.1:8090 | `/healthz` / `/readyz` |
| web | 127.0.0.1:5173 | `/` / `/` |

所有启动绑定 loopback，Vite 使用 strictPort，不能自动切换到错误代理端口。状态展示 PID、构建/启动/运行/失败阶段、实际 HTTP 探针结果和模式。存活 200 不等于业务就绪；Runtime closed 的就绪 503 会保留。

后台控制端口是随机 loopback 端口，用独立随机凭据鉴权；状态文件只保存管理 PID/端口/控制凭据，路径为 `.tmp/dev/manager.json`。每次操作核对协议和仓库路径。停止只针对后台进程实际持有的子进程对象，不根据磁盘上的旧 PID 杀进程。端口已占用显示 `unmanaged` 并拒绝接管；遗留的旧 `.tmp/local-preview/start.cjs` 进程也不自动接管。

组件日志位于 `.tmp/dev/<组件>.log`。写入前屏蔽已知 Token/密钥/密码与 Authorization，跨写入分片也处理，超长行省略。不记录环境配置或控制请求。仍遵循应用本身不记录 Prompt、会话与原始客户日志的边界；本工具的日志脱敏不是通用 DLP。

单次构建上限 5 分钟，存活探针上限 90 秒；失败后保留状态与日志，无自动重试、自动重启或 Mock 回退。所有终端共享一个构建名额。构建/启动进行中可以从另一个终端停止，排队任务可取消。后台管理进程意外崩溃后遗留组件视为未受管，不凭旧 PID 清理；整机重启与生产守护不在此工具范围内。

## 开发检查

```text
go -C scripts/devctl test -v ./...
go -C scripts/devctl vet ./...
pnpm test:dev-session
pnpm test:web-local
```

Go 测试使用明确标注的本机 fixture 子进程，覆盖解析、演示隔离、日志脱敏、鉴权、启动/重启/停止、子进程树、排队取消和外部端口拒绝。它们不代表真实业务整链、来源或模型验收。管理器检查见[验证报告第 76 节](../VALIDATION-REPORT.md#76-2026-10-01-go-跨平台开发服务管理器追加)，二进制分发/npm产物调用及本轮检查见[第78节](../VALIDATION-REPORT.md#78-2026-10-01-go-管理器二进制分发与-npm-产物调用追加)。
