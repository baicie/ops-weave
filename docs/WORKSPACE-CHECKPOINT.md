# 当前工作区检查点

更新时间：2026-10-07

## 目标

继续完成 OpsWeave 的数据接入、模型、工作流和运维控制台闭环。当前主线目标保持进行中：优先完成注册数据源的受控连接、发现与同步，再推进真实来源、持久运行、恢复、质量和生产可靠性验收。

## 已完成到第155节

- 数据源实例、端点、凭据和连接配置采用租户/主体授权、固定 revision、摘要和 CAS；凭据只保存受保护引用，不进入前端或普通日志。
- 固定连接版本支持受控连接检查、指标目录发现、分页发现、指标映射 pin，以及指标/日志工作流的来源选择和只读预览。
- 工作流画布支持节点编辑、抽屉配置、版本保存/比较/发布、运行控制、主机扫描、指标/日志窗口、输出确认、历史、恢复、重放和质量观察；服务端仍按固定来源、预算、授权、幂等和 UNKNOWN 语义校验。
- 内置及自定义实体、字段、关系模型和受控实体/关系实例 API 已接通，模型引用保持固定版本。
- Web 控制台已统一页面容器、侧栏、面包屑、多页签、亮暗主题、移动布局和接入抽屉。列表首次自动读取，业务请求容器与展示组件分离。
- 注册连接 Item Sync 已完成：`POST /api/v2/data-sources/{id}/connection/{revision}/items/sync`，并提供扫描历史分页和详情读取。同步固定 source UUID、配置 revision、连接摘要和 host group 范围；只有已验证完整快照允许在本次 host cohort 内退休缺失绑定，失败、空清单、范围漂移和失租保留旧绑定。
- 注册连接版本增加固定指标历史读取：`GET /api/v2/data-sources/{id}/connection/{revision}/metrics/{itemId}/history`。请求只接受闭合60秒窗口和有界点数，服务端从已完成发现回执解析固定 pin，并复用登记 endpoint、credential 和 host group 范围。
- 注册连接问题分页已完成：`GET /api/v2/data-sources/{id}/connection/{revision}/problems`。查询仅允许固定连接下的 `from`、`till`、`afterEventId` 和 `limit`，返回固定 connection/scope digest 与 host group 范围；只读读取不创建 Incident 或触发动作。
- Item Sync 扫描租约已加入 `scopeDigest` fencing：不同注册 revision 仍按物理 source 串行，但不会错误复用彼此 scope 或退休对方范围；新增 V062 迁移、回滚和范围 smoke。
- 注册连接问题分页已有独立 v2 Fixture 与契约回归，覆盖 authority/连接覆盖拒绝、固定范围回显和分页边界；定向问题/Item/OpenAPI 契约共42项通过，全量 `tests/contracts` 回归也通过。
- 注册问题分页 HTTP 路由已统一 `{sourceId}`，错误 advice 覆盖 ProblemReadException 的 403/429/503；新增边界 JUnit，平台编译和定向契约回归通过。
- 注册连接问题已接入实例维护抽屉：默认读取最近一小时，可编辑本机时间窗（最多24小时），分页保持时间窗且只推进事件游标；更换范围后从第一页读取。响应绑定 source/revision、连接摘要、主机组和 scope digest；503不会自动重试。
- 开发管理器支持四个既有启动单元的启动、停止、重启、状态和日志查看：`pnpm ops status`、`pnpm ops restart all` 等。默认 loopback；不会接管外部端口或静默回退 Fixture。

## 尚未完成或未在本机确认

- 第150-155节新增 Item Sync、固定指标历史和注册问题分页/页面的真实 PostgreSQL HTTP 联测、多实例并发验收尚未运行。第155节最终全量 `tests/contracts` 1955项、定向 Java 边界测试、Web typecheck/build、显式 Chromium专项4项、结构检查、私有资料边界与差异检查均实际通过。浏览器样例为Fixture；初次3/4失败因固定时间样例超出动态查询窗，修正测试数据后最终4/4通过。
- `OPSWEAVE_TEST_JDBC_URL` 未配置，因此完整 PostgreSQL 集成测试不能宣称通过。真实来源、真实身份、模型服务、TLS、生产部署和多副本租约/fencing/HA 也未完成验收。
- 开发管理器测试 `go -C scripts/devctl test ./...` 曾未通过：Windows进程树停止测试失败并留下日志句柄，不能视为管理器测试通过；本轮没有改管理器代码或重跑该测试。
- 旧环境变量驱动的全局 History、Problem、Item Sync 入口仍未完全迁入注册连接范围；注册问题分页已绑定 revision，但全局入口、真实 HTTP、多实例范围收窄/切换及旧绑定退休策略仍需单独验证。
- 当前目标状态为 active；实现状态和实际检查以 `docs/IMPLEMENTATION-STATUS.md`、`docs/ROADMAP.md`、`docs/VALIDATION-REPORT.md` 及各 ADR 为准。

## 换机恢复

1. 安装 Java 21、Node 22.12+、pnpm 10.34+、Rust/Cargo 和 Go 1.23+（Go 仅用于重建开发管理器）。
2. 在仓库根目录执行 `pnpm install --frozen-lockfile`。不要提交 `.env`、`.env.dev.*`、凭据、`node_modules`、`target`、`build` 或 `bin`。
3. 复制 `.env.dev.example` 为本机需要的 `.env.dev` 或对应组件文件，按真实环境填写 JDBC、来源和认证配置；缺失真实配置时使用明确的 `--demo`，不要把 Fixture 当成真实联调。
4. 若当前平台没有管理器产物，执行 `pnpm dev:build`；然后使用 `pnpm ops status`、`pnpm ops start all` 或 `pnpm ops start web`。状态只显示本工具自己启动的组件。
5. 前端地址为 `http://127.0.0.1:5173/#/start`。真实模式需平台会话和后端就绪；demo 模式仅用于界面和受限 Fixture 预览。
6. 恢复开发后先阅读 `AGENTS.md`、`docs/architecture/v4-design.md`、`docs/ROADMAP.md`、`docs/IMPLEMENTATION-STATUS.md`、`docs/VALIDATION-REPORT.md`，再从第155节的未完成项继续；先配置 `OPSWEAVE_TEST_JDBC_URL` 做注册 Item Sync/指标历史/问题分页的 PostgreSQL HTTP 与多实例验收，再定义旧全局入口迁移和绑定退休策略。

## 提交边界

仓库只保留本项目术语、契约、实现和通用设计原则。外部对标资料、原始截图、账号、验证码、私有检查词表和研究记录留在仓库外本机目录；不要复制、改名或编码后提交。
