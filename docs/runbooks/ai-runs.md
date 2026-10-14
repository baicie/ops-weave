# 诊断运行与事件时间线（Run Console）

M5 5c 的控制台只读面：本人运行登记、状态与有序事件时间线，外加既有取消/恢复命令的入口。入口在 `/#/ai/runs`（左侧「AI 管理 → 诊断运行」）。契约见[诊断运行登记契约](../../contracts/ai-runs.md)与 [ADR-104](../adr/104-durable-ai-run-registry.md)。

## 1. 前置

- 在页面顶部建立平台会话：开发模式填平台开发 Token（只保存在当前标签页内存），OIDC 模式先登录。会话失效、身份切换或页面隐藏恢复都会清空页面上的运行、事件与待确认状态。
- 读取需要 `ai.insight.read`（并且当前 Incident 与对象范围仍可读）；提交/取消/恢复需要 `ai.diagnose`。菜单可见不代表有权，服务端每次读取都会重新授权。
- 运行列表只显示**本人**的运行；他人的运行、跨租户一律 404，不猜测位置。

## 2. 运行列表与详情

- 列表在会话就绪后自动读取一次，顺序固定 `createdAt DESC, runId DESC`；「加载更多运行」用游标续读，空尾页是完整结果。
- 详情显示状态（`PENDING/RUNNING/WAITING_TOOL/SUCCEEDED/FAILED/CANCELLED`）、`statusVersion`、第几次尝试（`attempt`）、落定原因（`reason` 闭集）、取消请求时间（`cancelRequestedAt`）与**对账披露**（`disclosure`：既有模型调用数、结果未知费用数、是否认领旧结果/重跑、`duplicateFeeRisk`）。
- 对账披露是事实记录：费用照实保留、不退，页面不声称跨外部服务 exactly-once；费用已花而结果丢失时，继续诊断须**新提交**（新 `requestId`/`runId`）并再次计费。
- 完整结论由 AIInsight 保存并授权读取；`SUCCEEDED` 之外的运行没有完整结论，页面不把部分结果当成结论显示。

## 3. 事件时间线

- 事件是**记录不是命令**：`{runId, seq, type, occurredAt, payloadVersion, payload}`，类型闭集 `SUBMITTED/CLAIMED/STATUS_CHANGED/TOOL_WAIT/CANCEL_REQUESTED/RECONCILED/FINISHED`。载荷只含状态、尝试、原因与对账披露——没有隐藏推理、密钥、原始日志或证据正文。
- 时间线按 `seq` 升序展示，「加载更多记录」用游标（`afterSeq`）续读；已到保留窗口末尾时明确提示，空尾页是完整结果。
- **事件缺口**：游标过期时服务端返回保留窗口内的记录 + `gap=true` + 当前快照，页面顶部显式显示「事件缺口」，缺失的历史事件不补猜、不假装连续。
- 实时更新优先走 SSE 推送通道（`GET /api/v1/ai/runs/{runId}/events/stream`，每帧是一次游标回读结果）；客户端无流式能力或推送失败时**降级为轮询回读**（页面标注当前通道）。断线重连从最后收到的 `seq` 续读，重放/重连/重复读都只重新投递记录，**零副作用**。

## 4. 取消与恢复（复用既有受控命令）

- 「请求取消」= `POST /api/v1/ai/runs/{runId}/cancel`（协作式取消）：只记录取消请求，执行器在安全点落 `CANCELLED`；**离开页面不等于取消**。取消请求后仍可能完成，完成则照常 `SUCCEEDED`。
- 「恢复/重试（同运行新尝试）」= `POST /api/v1/ai/runs/{runId}/retries`：同 `runId` 的新 attempt，恢复前重新授权并先对账；已保存结果会被认领，有费用无结果不会重跑（一次诊断一次模型调用不变式）。
- 两个命令都用客户端 `requestId` 作幂等键。**未知结果原样重试**：请求超时/网络失败时页面显示「提交结果待确认」，保留原 `requestId`，只提供「查询运行当前状态」与「原样重发同一请求」——绝不自动重试、不改用新标识；同一 `requestId` 重发返回原回执。
- 已终止运行（`SUCCEEDED/FAILED/CANCELLED`）不再迁移：取消是幂等无操作、重试不会改写结论，两个入口在详情里保持禁用而不是隐藏；新的诊断请重新提交。

## 5. 边界

- 控制台是只读面：除既有取消/恢复命令外没有任何写入口，不新增可写工具；浏览器不能代替服务端授权。
- 事件与运行记录不执行任何操作：回读、翻页、重连、重放都不调用模型、不执行工具、不发通知。
- 一次诊断仍是一次模型调用（共享 `model_spend` 预算），Skill 固定版本 pin（`incident.diagnose@2.0.0` + 摘要）不变。
- SSE 每连接有界（时限/字节/帧数/并发数），慢客户端会被断开并按自己的游标续读，不会无界堆积。
