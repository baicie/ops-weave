# 诊断运行登记契约（ai-run）

M5（[ADR-104](../docs/adr/104-durable-ai-run-registry.md)）：把一次同步诊断请求升级为**可查询、可取消、可恢复**的运行登记。5a 覆盖「提交 / 按 ID 读 / 列表分页」；5b 覆盖「执行接线、协作式取消、恢复/重试与未知完成对账」；5c 覆盖「有序事件记录、游标回读、SSE 推送通道（轮询降级）」与 Web 控制台只读面；5d 覆盖「Runtime 状态投递回执、跨库对账报告与运行观测聚合」。本文件不描述任何未实施行为。

## 1. 对象

`ai-run` 是平台侧运行登记：一次被受理的诊断请求及其当前状态。

| 字段 | 语义 |
|---|---|
| `runId` | 服务端签发的 UUID，运行的稳定身份 |
| `requestId` | 提交幂等键（客户端签发 UUID）。同键重放返回同一 `runId`；同键异内容 409 `AI_RUN_REQUEST_REUSED` |
| `commandDigest` | 只覆盖内容（incidentId、question、timeRange、knowledgeMode、skill），请求标识是关联信息不进摘要 |
| `status` / `statusVersion` | 状态机当前值与 CAS 版本号，见 §2 |
| `incidentId`、`question`、`timeRange`、`knowledgeMode`、`skill` | 提交内容原样保留：当前知识模式（常量 `current`）、固定 Skill 版本 pin（`incident.diagnose@2.0.0` + 内容摘要），运行中不跟随 `latest` |
| `attempt` | 当前尝试序号（0 = 尚未认领）。恢复是同 `runId` 的新 attempt，不是新运行 |
| `cancelRequestedAt` | 取消请求时间（未请求时缺省）。**单独记录、不占状态机**：状态与 `statusVersion` 不因此改变 |
| `reason` | 落终态原因（闭集）：`FAILED`/`CANCELLED` 必带，`SUCCEEDED` 与未落终态的运行必无 |
| `disclosure` | 未知完成状态对账披露（见 §5），由落终态的那次 attempt 记录 |
| `createdAt` / `updatedAt` | 受理时间与最后状态变更时间 |

请求与响应**不携带身份**：tenant、subject、权限、审批状态一律由可信认证边界构造，`tenantId`/`owner`/`permissions`/`approvalStatus` 等字段在 Schema 中不存在，出现即 400。`timeRange` 服务端另有限制（整秒、`from < to`、不超过 1 小时、`to` 不得指向未来），Schema 不表达、由服务端拒绝。

## 2. 状态机

```
PENDING → RUNNING → WAITING_TOOL ⇄ RUNNING → SUCCEEDED | FAILED | CANCELLED
PENDING → FAILED | CANCELLED
```

终止态 `SUCCEEDED`/`FAILED`/`CANCELLED` 不再迁移；非法迁移是显式冲突，不是静默无操作。`statusVersion` 每次成功迁移 +1，持久层以比较交换（CAS）落库。5b 起状态由**执行器**推进：

- **认领（claim）**：`PENDING → RUNNING`（或崩溃后回收 `RUNNING`/`WAITING_TOOL` 的过期租约），同一原子步骤递增 `attempt` 与 fencing token 并写入租约期限。**一个运行、多次 attempt**：崩溃恢复永远不产生第二个运行。持有有效租约的运行不会被第二个执行器认领（不双跑）；丢失租约的旧 worker 之后提交结果一律被拒绝（fencing token 不匹配）。
- **流转**：执行器在派发诊断链前进入 `WAITING_TOOL`，返回后回到 `RUNNING`，再落终态。
- **落终态**：只有 `SUCCEEDED` 才与已保存的 AIInsight 结论并存。诊断链只在全部检查通过后保存结论（失败即止、不保存），因此「已保存完整结果」出现时运行必落 `SUCCEEDED`（即使派发随后报错或取消请求先到）；`FAILED`/`CANCELLED` 只留运行记录与 `reason`，部分结果不冒充完整。

部分结果边界：`FAILED`/`CANCELLED` 的运行没有 AIInsight 结论；模型费用照实保留（`RESERVED`/`UNCERTAIN`/`REPORTED`），不退不改。

## 3. 幂等提交与只读回读

- 同租户同 `requestId` 重放返回同一 `runId`；不可变部分逐字段一致，状态字段以当前状态为准（运行可能已推进）。
- 同 `requestId` 异内容 409 `AI_RUN_REQUEST_REUSED`；同 `requestId` 由另一主体重放同样 409；`requestId` 在提交与控制命令（cancel/retry）之间**共用一个幂等命名空间**，跨族复用同为 409。提交本身不执行模型、不执行工具、不推进状态；执行由执行器认领后进行。
- `GET /api/v1/ai/runs/{runId}`：读取前重新核对当前 Incident 与对象范围授权；不可见即 404。读取永不推进状态、不触发重试。
- `GET /api/v1/ai/runs`：本人运行列表，顺序固定 `createdAt DESC, runId DESC`；游标为最后一条的 `runId`，`after` 严格续在其后，页间不重叠不乱序，之后新增的运行不会落进后续页。
- `hasMore` ⇔ `nextCursor` 非空 ⇔ `items` 非空；空尾页（`items=[]`、`hasMore=false`）是完整结果。`limit` 1..50，缺省 20。
- 游标形状非法 400；形状合法但在当前身份下不可见（他人的运行、跨租户）明确 404，客户端置空选择重新读取，不猜测位置、不退回首页。列表只返回本人运行的元数据，不含证据与结果正文。

## 4. 协作式取消（`POST /api/v1/ai/runs/{runId}/cancel`）

命令闭合：只有 `requestId`（幂等键），目标运行在路径中；不接受 tenant/actor/permissions/reason 等字段。

- 取消请求记录 `cancelRequestedAt`（**不占状态机**：状态、`statusVersion` 不变），执行器在安全点（派发诊断链前、派发后）检查并落 `CANCELLED`（`reason=CANCELLED_BY_REQUEST`）。
- **离开页面不等于取消**：只有本显式命令是取消请求。
- **取消请求后仍可能成功**：诊断链在取消请求之后完成并保存结论时，运行照常落 `SUCCEEDED`（完整结果不被丢弃或伪装）。
- 幂等回执 `ai-run-cancel-receipt`：`recorded=true` 表示请求已记录（执行器将在安全点落 `CANCELLED`）；运行已终止时 `recorded=false`、不做任何改变（**取消已终止运行是幂等无操作**）。`status` 是回执时的运行状态。同 `requestId` 重放返回原回执逐字节一致；同 `requestId` 异内容/异运行 409 `AI_RUN_REQUEST_REUSED`。
- 取消只取消本地等待：不保证模型供应商停止计费，已发生费用照实保留。

## 5. 恢复/重试（`POST /api/v1/ai/runs/{runId}/retries`）与未知完成对账

命令闭合：只有 `requestId`（幂等键）。

- **恢复是同 `runId` 的新 attempt**，不是新运行。**恢复前重新授权**：身份、权限、对象范围与当前 Incident 被撤回即拒绝（403/404），不执行任何工作。
- **先对账再认领或重跑**：查模型费用回执（`model_spend`）与平台 AIInsight 是否已保存——
  - 已保存完整结果 → **认领**该结果，运行落 `SUCCEEDED`（`disclosure.adoptedPriorResult=true`），不再派发、不再调用模型；
  - 无费用回执、无结果 → **重跑**（`disclosure.rerun=true`）：派发完整链路，证据按当前时间重新读取并复核 `asOf`/`availableAt`/过期时间，缺失保留不补猜；
  - 有费用回执、无结果（模型中途崩溃的未知完成状态）→ **不重跑**：一次诊断一次模型调用的不变式不因可恢复而放开，同 `runId` 不再发放第二次模型调用；运行落 `FAILED`（`reason=RESULT_LOST`）并**显式披露**（见下）。
- **重复费用显式披露**（`disclosure`/`reconciliation` 闭合对象，`ai-run-reconciliation.schema.json`）：
  - `priorModelCalls`：本次运行已有的模型费用回执数（费用照实保留、不退）；
  - `uncertainPriorFees`：其中结果未知（`RESERVED`/`UNCERTAIN`）的费用数——可能已计费；
  - `duplicateFeeRisk`：费用已花而结果丢失；如需继续诊断须以**新提交**开始新运行，将再次计费。**不声称跨外部服务天然 exactly-once**：供应商侧可能已为丢失的调用计费。
  - `adoptedPriorResult` / `rerun`：对账选择策略（认领已有结果 / 重新执行），两者互斥。
- **重试仅面向未完成运行**：`SUCCEEDED`/`FAILED`/`CANCELLED` 是终态、不再执行（409 `AI_RUN_STATE_CONFLICT`）；新的诊断以新提交开始（新 `requestId`、新 `runId`）。正在被有效租约持有的运行同样 409（不双跑）。
- 幂等回执 `ai-run-retry-receipt`：`attempt`（认领的 attempt 序号）、`status`（attempt 落定后的状态）与 `reconciliation`（同上的披露对象）。同 `requestId` 重放返回原回执逐字节一致；同 `requestId` 异内容 409。

## 6. 事件记录（RunEvent）与游标回读（5c）

`ai-run-event` 是**有序持久记录**，Schema `ai-run-event.schema.json`（全部 `additionalProperties:false`）：

| 字段 | 语义 |
|---|---|
| `runId` | 记录所属运行 |
| `seq` | 每运行从 1 单调递增的序号，游标即 `seq` |
| `type` | 类型闭集 7 个：`SUBMITTED`/`CLAIMED`/`STATUS_CHANGED`/`TOOL_WAIT`/`CANCEL_REQUESTED`/`RECONCILED`/`FINISHED` |
| `occurredAt` | 事实发生时间 |
| `payloadVersion` | 载荷版本（当前常量 `1`） |
| `payload` | 闭合载荷（见下），**无隐藏推理、无密钥、无原始日志、无证据正文** |

**事件是记录不是命令**：重放同一 `seq`、客户端重连重读、断线重放都只重新投递记录，**不执行工具、不调用模型、不发通知、不推进状态**（重放零副作用）。记录与状态流转同事务落库（提交/认领/每次状态流转/取消请求在状态流转点落事件，被拒绝的 CAS 不落事件）；`RECONCILED` 是执行器对账后的显式披露记录，`CANCEL_REQUESTED` 每次取消请求至多一条（首请求落记录，重复请求不重复记录）。

`payload` 闭合（`status`/`attempt`/`reason`/`disclosure` 四个可选字段，按 `type` 用 if-then 限定）：

| type | payload |
|---|---|
| `SUBMITTED` | `status=PENDING`、`attempt=0` |
| `CLAIMED` | `status=RUNNING`、`attempt≥1`（每次认领一条） |
| `TOOL_WAIT` | `status=WAITING_TOOL` |
| `STATUS_CHANGED` | `status ∈ {RUNNING, WAITING_TOOL}` |
| `CANCEL_REQUESTED` | 空对象 |
| `RECONCILED` | `disclosure`（`ai-run-reconciliation` 闭合对象） |
| `FINISHED` | `status ∈ {SUCCEEDED, FAILED, CANCELLED}`；`FAILED`/`CANCELLED` 必带 `reason`、`SUCCEEDED` 必无；可带 `disclosure` |

**保留窗口与游标过期**：服务端定义每运行保留窗口（当前常量 200 条，超出即丢最旧）。游标过期（`afterSeq + 1 < 保留窗最低 seq`）时回**最新保留窗 + `gap=true` + `snapshot`**（`{status, statusVersion, attempt, updatedAt}` 当前运行快照），显式标注缺口、不假装连续；容量预算与截断观测见 §12（5d）。

**游标回读** `GET /api/v1/ai/runs/{runId}/events?afterSeq=&limit=`：

- 顺序固定 `seq ASC`，`afterSeq` 严格续在其后，页间不重叠不乱序；`limit` 1..50、缺省 20；`hasMore` ⇔ `nextCursor`（最后一条 `seq` 的十进制字符串）非空 ⇔ `items` 非空；空尾页（`items=[]`、`hasMore=false`）是完整结果。
- `afterSeq` 形状非法（非十进制、负数、越界）400；读取与翻页**永不执行、永不推进状态**，同一游标重复读返回逐条相同的记录。
- 授权与 `GET /api/v1/ai/runs/{runId}` 相同（每页重新核对当前 Incident 与对象范围），不可见即 404。
- `clientRequestId` 只是关联信息，不参与身份与幂等。

## 7. SSE 推送通道与轮询降级（5c）

`GET /api/v1/ai/runs/{runId}/events/stream`（`text/event-stream`）**只是同一游标协议的推送通道**：

- 每帧 `data` 是一个 `ai-run-event-page`（同 §6 的回读结果），`id` 为该帧最后一条记录的 `seq`；空页不发帧，运行落终态且无新记录即关闭连接；连接开始发 `retry:` 提示，空闲只发 keepalive 注释。
- **断线重连从游标续读**：`afterSeq` 查询参数或标准 `Last-Event-ID` 头（两者同时出现必须一致，否则 400）；重连/重放零副作用（同 §6）。游标过期同样回保留窗 + `gap=true` + `snapshot`。
- **轮询降级**：客户端无流式能力时用 §6 游标回读轮询（例如每 3s 拉一次 `afterSeq=lastSeq`），语义完全一致；SSE 不携带命令、不接受写入。
- **有界**：每连接时限 60 秒、字节上限 256 KiB、帧数上限、同一时刻至多一帧在途（慢客户端不堆积，到限即断开并按自己的游标续读）；每进程并发流上限 8，超出即 503 `UNAVAILABLE`。事件先持久再推送，客户端断开不影响运行执行。

## 8. 错误码闭集

| 状态 | `error` | 触发 |
|---|---|---|
| 400 | `INVALID_REQUEST` | 请求体/查询参数不闭合、字段越界、时间窗非法、游标形状非法 |
| 401 | — | 未认证 |
| 403 | `FORBIDDEN` | 缺少 `ai.diagnose`（提交/取消/恢复）或 `ai.insight.read`（读取）、对象范围不含目标、恢复前重新授权失败 |
| 404 | `NOT_FOUND` | Incident 不可见/不存在、运行不存在或对当前身份不可见、游标不可见 |
| 409 | `AI_RUN_REQUEST_REUSED` | 同 `requestId` 异内容或异主体重放（含提交与控制命令跨族复用） |
| 409 | `AI_RUN_STATE_CONFLICT` | 恢复目标是终止态运行或被有效租约持有的运行 |
| 503 | `UNAVAILABLE` | 存储不可用；SSE 并发流超限 |

事件读回与 SSE 通道的错误码闭集为 `200/400/401/403/404/503`（无 409）；`400 INVALID_REQUEST` 同时覆盖 `afterSeq`/`limit` 形状非法与 `afterSeq`/`Last-Event-ID` 不一致。

其他状态码与错误码不在这七条接口上出现。

## 9. 明确不在本契约内

Runtime 的 StepRun/Checkpoint **内部状态本身**（步骤明细、检查点内容、证据复核记录正文）不跨库、不在本契约内：它们由 Runtime 自有存储（Rust 侧 journal）持久，平台只接收 §10 的闭合计数回执投影，`db/migrations/ai/V001__ai_run_prototype.sql` 的 `ai_runtime.run` 仍是未加载原型。Web 控制台只读面（Run 列表/详情 + 事件时间线 + 取消/重试操作入口）随 5c 交付，**复用本契约的读回与既有控制命令，不新增可写工具**。本契约不预留可写工具、不新增权限词表、不执行任何模型调用之外的副作用；一次诊断仍是一次模型调用（共享 `model_spend` 预算与 permit/回执），Skill 固定版本 pin 不变。运行登记不替代 AIInsight：结果仍由 AIInsight 持久化并授权读取。执行安全点在派发边界（派发前/派发后）；诊断链内部（工具读取与模型调用之间）的取消观察点不属本段。

## 10. Runtime 状态投递（`POST /api/v1/ai/runs/{runId}/runtime-states`，5d）

Runtime 自有内部状态（StepRun/Checkpoint/attempt/证据复核记录）由 Runtime 持久与恢复；跨库只投递**闭合计数回执投影** `ai-run-runtime-state`（`requestId`/`runId`/`attempt`/`status`/`stepCount`/`checkpointSeq`/`evidenceRecheckCount`/`reportedAt`，全部 `additionalProperties:false`）：**不含步骤名、检查点内容、证据正文、身份字段**。

- **至少一次投递、幂等回执**：`requestId` 是投递幂等键，与提交/控制命令共用一个幂等命名空间（跨族复用 409 `AI_RUN_REQUEST_REUSED`）。重放返回**逐字节一致**的 `ai-run-runtime-state-receipt`（回执由已存回执投影导出，不带新时间戳）；同 `requestId` 异内容 409。投递可能重复，回执吸收重复；**不声称 exactly-once**。
- **回执投影有界**：每运行保留窗口 50 条，超出丢最旧并计数（`ai-run-observability.runtime.droppedReports`），截断如实披露。
- **投递永不移动运行**：不改 `status`/`statusVersion`/`attempt`/租约，不执行任何工作、不发放权限；Runtime 发送失败/结果未知时本地 outbox 记为 `Unknown`（不是「已投递」也不静默丢弃），重试仍可能重复投递。
- 通道要求 runtime attestation 凭据（`X-OpsWeave-Runtime-Key`，loopback dev 模式）加当前主体的本人运行；错误码闭集 `200/400/401/403/404/409/503`。

## 11. 跨库对账报告（`GET /api/v1/ai/runs/{runId}/runtime-reconciliation`，5d）

`ai-run-cross-store-report` 是**披露不是命令**：`{runId, comparedAt, repair: "none", platform, runtime, findings}`，两侧视图都是闭合记录（`platform` 只有 status/statusVersion/attempt/updatedAt；`runtime` 只有 status/attempt/stepCount/checkpointSeq/reportedAt），`findings` 闭集 8 个：

| finding | 语义 |
|---|---|
| `MISSING_ON_PLATFORM` | Runtime 有状态而平台登记不存在 |
| `MISSING_RUNTIME_STATE` | 平台有运行而从未收到/记录 Runtime 状态回执 |
| `STATUS_MISMATCH` | 双方都未终态但状态不一致 |
| `PLATFORM_TERMINAL_RUNTIME_ACTIVE` | 平台已终态而 Runtime 仍在运行（**显式披露，不静默修补**） |
| `RUNTIME_TERMINAL_PLATFORM_ACTIVE` | Runtime 已终态而平台仍未落定 |
| `ATTEMPT_MISMATCH` | attempt 计数不一致（如 Runtime 未见过的恢复 attempt） |
| `STALE_RUNTIME_REPORT` | 已投递状态早于平台最近一次流转 |
| `UNDELIVERED_STATE_REPORT` | Runtime 侧存在未确认送达的状态报告 |

- **`repair` 恒为 `"none"`**：报告只披露不一致，不改写任何一边、不重跑、不双跑、不启动/停止任何执行；恢复仍走 §5 的 retry 入口（重授权、租约/fencing、同 runId 新 attempt）。
- 阅读是只读投影（读取零副作用）；定期/触发式对账把同一报告记录为**每运行一份的快照**（观测产物不是状态，被更新不改变任何运行）。`findings` 的声音性（每条 finding 必须与两侧视图相符）在领域构造期重算拒绝，Schema 以 if-then 闭合可表达部分（缺失侧、终态活跃方向）。
- 错误码闭集 `200/400/401/403/404/503`。

## 12. 运行观测聚合（`GET /api/v1/ai/runs/{runId}/observability`，5d）

`ai-run-observability` 是**有界只读聚合**：`events`（保留窗口 `window`、现存 `retained`、被截断 `dropped`、截断标记 `truncated`——三者互相印证，截断历史不冒充完整）、`spend`（`priorModelCalls`/`uncertainPriorFees`，费用照实保留不退、`uncertainPriorFees ≤ priorModelCalls`）、`runtime`（已投递回执 `deliveredReports`/`droppedReports` 与最新回执的 `latestAttempt`/`lastReportedAt`，无回执时为空）。读取永不执行、**没有隐藏后台任务**；错误码闭集 `200/400/401/403/404/503`。
