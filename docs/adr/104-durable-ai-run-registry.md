# ADR-104：M5 可恢复诊断运行——持久 AIRun 登记、协作式取消、事件协议与分段计划

状态：已采纳（设计权威；5a、5b、5c 已实施）。2026-10-13，配合验证报告第 181 节（5a 第一段）、第 182 节（5b 执行接线、取消与恢复）与第 183 节（5c 事件流与控制台）。

## 1. 背景与范围

M4 的当前知识诊断是**同步流程**：Rust 一次模型调用、共享模型预算、证据复核、AIInsight 幂等保存（ADR-022/023）。它不可查询中途状态、不可取消、不可恢复；`apps/agent-runtime` 的 `RunState` 只是内存枚举（Queued/Running/WaitingApproval/Succeeded/Failed/Cancelled），**不是持久化执行引擎**，进程退出即消失，不能据此宣称可恢复。

M5 的目标（ROADMAP §10）是把同步诊断升级为**可查询、可取消、可恢复的运行任务**：先稳定 AIRun / StepRun / Checkpoint / RunEvent 协议，再做实时控制台。本 ADR 定义 M5 的架构决策与分段计划；它不扩大 M4 的能力边界——**不新增任何可写工具、模型不授予权限、一次诊断一次模型调用的不变式在 M5 范围内保持**。

## 2. 决策

### 2.1 持久 AIRun：状态机、幂等提交、只读回读（5a 已实施）

- AIRun 是平台侧**运行登记**：一次被受理的诊断请求及其当前状态。字段固定：`runId`（服务端签发 UUID）、`requestId`（幂等键）、`commandDigest`（只覆盖内容，请求标识是关联信息不是内容）、`incidentId`、`question`、`timeRange`（沿用 `ToolWindow`：整秒、≤1 小时、不得指向未来）、`knowledgeMode`（常量 `current`，M5 不引入历史知识模式）、`skill`（固定版本 pin：`incident.diagnose@2.0.0` + 内容摘要，运行中不跟随 `latest`）、`status`、`statusVersion`、`createdAt`/`updatedAt`。
- 状态机闭合：`PENDING → RUNNING → WAITING_TOOL ⇄ RUNNING → SUCCEEDED | FAILED | CANCELLED`；`PENDING → FAILED | CANCELLED`；终止态 `SUCCEEDED/FAILED/CANCELLED` 不再迁移。`WAITING_TOOL` 是工具等待位，5a 不产生该状态，留给 5b 的执行器。
- **幂等提交**：同租户同 `requestId` 重放返回同一 `runId`，不可变部分逐字段一致；状态字段以当前状态为准（运行可能已推进）。同 `requestId` 异内容显式 409 `AI_RUN_REQUEST_REUSED`，同 `requestId` 由另一主体重放同样 409，不产生第二条运行。请求体闭合，不接受 tenant/actor/permissions/approvalStatus 等身份字段。
- **只读回读**：按 `runId` 读一条（读取时重新授权当前 Incident 与对象范围），本人运行列表游标分页（顺序固定 `createdAt DESC, runId DESC`，`after` 严格续在其后、页间不重叠不乱序、`hasMore`⇔`nextCursor` 一致、空尾页是完整结果、limit 1..50、游标形状非法 400、当前身份不可见的游标 404 不猜测位置）。读取与翻页**永不执行**、不触发模型、不改变状态。
- 状态推进不在 5a 的受控 API 上：API 只有「提交 / 按 ID 读 / 列表分页」。持久层提供带 `statusVersion` 比较交换（CAS）的 `advance` 端口作为 5b 执行器的接缝，5a 只由测试驱动以证明状态机持久、CAS 防并发、终止态不可再改。

### 2.2 持久化与所有权（5a：Java 平台先行）

- 5a 的 AIRun 登记持久在平台 PostgreSQL（`ai_control.ai_run` + 幂等回执 `ai_control.ai_run_command`，迁移 V069，注册于 `apps/platform-api/build.gradle.kts` processResources 与 `InventoryWiring`），与 AIInsight/模型预算同库同边界：身份、授权、幂等、预算检查都在 Java 受控 API 上，Rust 只经受控平台 API 访问、不直写平台表。
- 这**不改变**目标所有权划分（ROADMAP §10「Java 拥有平台 AIInsight，Rust 拥有 Runtime 状态」）：平台侧保存的是**运行登记与回执投影**（提交身份、固定配置、状态与时间），不保存 Rust 的 StepRun/Checkpoint 内部状态。Rust 侧的 Runtime 内部状态、租约执行与跨库可靠投递/对账在 5b/5c 落地（`db/migrations/ai/V001__ai_run_prototype.sql` 的 `ai_runtime.run` 保持「未加载原型」，不删除也不假装已启用，是否启用另行 ADR）。
- Rust 侧 5a 的最小接线是**领域镜像**：`AirunStatus` 状态机与 `RunStore` 端口（默认 Disabled，无 SQLx/Rig/Axum 依赖），确保两侧状态语义同源；不在 5a 引入 Rust 持久化。

### 2.3 取消语义（5b 已实施）

> 5b 实施注（验证报告 §182）：安全点落在**执行器的派发边界**（派发诊断链前/后），链内部（工具读取与模型调用之间）的观察点不属本段；取消请求后完成的运行照常 `SUCCEEDED`（完整结果不被丢弃）；已终止运行的取消是幂等无操作（回执 `recorded=false`）。

- **协作式取消**：显式「取消任务」提交取消请求（幂等），记录 `cancelRequestedAt`，**取消请求不占状态机**；执行器在安全点（工具调用边界、模型调用前后）检查并把状态推进 `CANCELLED`。离开页面只是取消订阅，不等于取消服务端运行。
- 取消只取消本地等待，不保证模型提供商停止计费或远端动作未发生；已发生的模型费用照实保留（沿用 `model_spend` 的 RESERVED/UNCERTAIN 语义）。
- **部分结果不冒充完整**：只有 `SUCCEEDED` 才写 AIInsight 作为完整诊断结论；`FAILED/CANCELLED` 只留运行记录、事件与失败/取消原因，若存在中途产出必须显式标注部分性，不得显示为完整结论，也不得自动重发。

### 2.4 恢复语义（5b 已实施）

> 5b 实施注（验证报告 §182）：`POST /api/v1/ai/runs/{runId}/retries` 只面向**未完成运行**（`PENDING`/`RUNNING`/`WAITING_TOOL` 的恢复/新 attempt）；终止态不可迁移的闭合状态机保持，`FAILED`/`CANCELLED` 之后的新诊断是新提交（新 `requestId`/`runId`）。一次诊断一次模型调用的不变式（§2.6）不放开：同 `runId` 的「费用已花、结果丢失」状态**不重跑**，落 `FAILED(RESULT_LOST)` 并显式披露（`priorModelCalls`/`uncertainPriorFees`/`duplicateFeeRisk`），继续诊断须新提交并再次计费——这正是「不声称 exactly-once」的可执行形态。

- 恢复是**同 runId 的新 attempt**，不是新 run；恢复前重新授权（身份、权限、对象范围被撤回即拒绝），证据重新核对 `asOf`/`availableAt`/过期时间，缺失必须保留不得补猜。
- 模型调用中途崩溃存在**未知完成状态**：恢复先对账（查模型费用回执与平台 AIInsight 是否已保存）再决定认领或重跑，重复费用与结果选择策略必须显式披露；不声称跨外部服务天然 exactly-once。
- **崩溃后不双跑**：租约/fencing 沿用既有扫描租约模式（原子 claim、lease deadline、fencing token、CAS 提交、失联回收）；丢失租约的旧 worker 之后提交结果必须被拒绝。

### 2.5 事件协议（5c 已实施）

> 5c 实施注（验证报告 §183）：事件在**状态流转点同事务落库**（提交/认领/每次状态流转/首条取消请求），被拒绝的 CAS 不落事件；类型闭集 7 个（`SUBMITTED/CLAIMED/STATUS_CHANGED/TOOL_WAIT/CANCEL_REQUESTED/RECONCILED/FINISHED`），`payloadVersion=1`，payload 闭合（status/attempt/reason/disclosure，按类型 if-then 限定，无隐藏推理/密钥/原始日志）。保留窗口是服务端常量（每运行 200 条，超出丢最旧；容量预算与截断观测留 5d），游标过期回保留窗 + `gap=true` + 当前快照。SSE 每帧是一次游标回读结果（`id`=本帧最后一条 `seq`），空页不发帧、终态且无新记录即关闭连接；`afterSeq` 或标准 `Last-Event-ID` 续读（同时出现必须一致），轮询回读是等价降级通道；每连接时限 60s/字节 256 KiB/帧数上限/同时一帧在途、每进程并发流 8。

- 事件是**记录不是命令**：`RunEvent {runId, seq（每运行有序）、type、occurredAt、payloadVersion, payload}`，持久化后经游标回读（`after=<seq>`、有界页、保留窗口由服务端定义）；SSE 只是同一游标协议的推送通道，轮询保留作降级。
- **重放不触发副作用**：同一 seq 重发、客户端重连重读、断线重放都只重新投递记录，不执行工具、不调用模型、不发通知。游标过期时回到最新快照并显式标注缺口，不假装连续。
- 浏览器经 BFF（Java）验证身份并读取授权事件，不直连 Rust、不存 Token、不做租户授权；事件不输出隐藏推理内容，只输出工具记录、步骤状态、证据与业务摘要。慢客户端不得无限堆积或阻塞运行。

### 2.6 预算与超时继承（M5 范围内明确）

- **共享预算不变**：模型调用仍走既有 `ai_control.model_spend` 共享预算与 permit/回执，一次诊断一次模型调用；M5 不新增独立预算池、不新增多步 Agent/多智能体、不因「可恢复」放开调用次数。
- 超时沿用既有边界（read session deadline、dispatch deadline、工具读取预算）；AIRun 5a 只登记 `createdAt`，5b 显式化 attempt 级 deadline 与租约期限。`WAITING_TOOL` 等待不计入模型预算，但计入运行时限。

### 2.7 与 AIInsight / Skill / Tool / 控制台的边界

- AIRun **不替代** AIInsight：最终结果仍是平台业务资源 AIInsight（授权读取、过期语义不变）；AIRun 只记录运行生命周期与结果引用。
- Skill 固定版本 + 摘要进运行配置，运行中不跟随 `latest`，不覆盖已发布 Skill；工具仍是只读三件套，**不新增可写工具、不新增任意 HTTP/SQL/动作工具**；模型不能授予自己或运行新的权限，提交/取消/恢复都要求用户当前授权。
- 控制台（Web）：先协议后界面（ROADMAP §10）。5a 不含 Web；控制台只读面（Run 列表/详情/状态）与事件窗口在 5b/5c，沿用会话、契约校验、未知结果原样重试与身份切换清理的既有模式。

## 3. 分段计划与各段验收

| 段 | 内容 | 验收 |
|---|---|---|
| **5a（本段，已实施）** | 持久 AIRun 登记 + 幂等提交 + 只读回读（按 ID 读、列表游标分页）+ 领域状态机 + PG 持久（V069）+ CAS 推进端口 + 契约 Schema/OpenAPI + Rust `RunStore` 端口镜像 | 契约正负例、纯领域 smoke、真实 PG IT（幂等重放/并发单赢家/游标分页/CAS 与终止态不可变/零模型调用）、Rust 状态机镜像测试全部通过；受控 API 不执行任何模型或工具 |
| **5b（已实施）** | 执行接线（把现有同步诊断纳入 AIRun 生命周期）、协作式取消、恢复/重试与租约/fencing、未知完成对账、部分结果边界 | 重复提交、Worker 崩溃/重启、取消、权限撤回、未知结果对账用例通过；不双跑、重复费用显式披露（§182 逐项实测通过；Web 控制台面移交 5c） |
| **5c（已实施）** | RunEvent 有序事件流 + 游标回读 + SSE 通道（轮询降级）+ Web 控制台只读面 | 乱序/重复事件、断线重连、游标过期回快照、重放无副作用、慢客户端有界用例通过；前端只渲染有限窗口 |
| **5d（按需）** | Rust 侧 Runtime 内部状态（StepRun/Checkpoint）持久化与跨库可靠投递/对账、事件保留窗口与容量预算、运行/费用观测聚合 | 重启恢复演练、跨库对账、容量上限与截断披露 |

M5 不属 M0–M4，不改变既有里程碑退出状态；M4 的真实来源/真实模型/人工评估退出项继续待验收。

## 4. 否决的方案

- **5a 直接由 Rust 持久化 Runtime 状态（启用 `ai_runtime`）**：需要第二个存储、第二套迁移与运维边界，而 5a 的目标只是「可查询、幂等、崩溃不丢」的运行登记；先在平台侧取得登记与契约，Rust 侧状态在 5b/5c 按所有权划分落地。否决理由记录在 §2.2。
- **把取消做成状态机状态（CANCEL_REQUESTED）**：会让「请求取消」与「已取消」混在同一列，无法表达取消请求后仍可能成功/失败的现实；改为取消请求单独记录、安全点落 `CANCELLED`。
- **同步流程原样 + 只加查询接口**：不解决断线/重启后的状态可见性，也无法承载 5b 的取消与恢复；运行登记必须先持久。
- **事件流直接由 SSE 内存广播**：断线即丢、无法游标回读与重放，且重放语义无法证明无副作用；事件必须先持久再推送。
