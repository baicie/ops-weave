# 治理契约：采集绑定、回执留存与运维删除

三个彼此独立、形状一致的**显式治理命令**：都是幂等 requestId + 理由必填 + 不可变回执 + 闭合 Schema，
都不接受 tenant/actor/权限，都不自动调度。Schema 唯一源在 `contracts/schemas/v2/`，示例在
`contracts/examples/v2/`，OpenAPI 登记在 `contracts/openapi/platform-draft.yaml`。

## 1. 启用采集绑定（发布 ≠ 自动采集绑定）

发布产生不可变版本，**不产生采集绑定**。绑定是来源实例与某个已发布版本之间的独立关系对象：

| 接口 | 请求 | 响应 |
|---|---|---|
| POST `/api/v1/integrations/workflows/collection-bindings/enable` | `workflow-collection-binding-command` | `workflow-collection-binding-receipt` |
| POST `/api/v1/integrations/workflows/collection-bindings/disable` | 同上（`operation: DISABLE`） | 同上 |
| GET `/api/v1/integrations/workflows/collection-bindings` | 禁止查询参数 | `workflow-collection-binding-page` |
| GET `/api/v1/integrations/workflows/collection-bindings/commands/{requestId}` | — | `workflow-collection-binding-receipt` |

命令字段闭合：`requestId`(UUID)、`id`/`revision`/`digest`（已发布版本 pin）、`sourceInstanceId`、
`operation`(ENABLE/DISABLE)、`reason`(1–256、无控制字符，**必填**)。`commandDigest` 只覆盖内容，
请求标识是关联信息不是内容，因此重放识别内容一致、内容变化 409 `CONFLICT`。
未知请求标识 404；未发布版本 404；版本摘要不符 409。回执与随后的 GET **逐字节一致**，
所以"未知结果原样重试"可以直接把原请求重发并取回同一份回执。

绑定状态 `BOUND`/`UNBOUND` 可回读，携带固定版本 pin、来源实例、代数与理由。**消费门禁**：
只有 `BOUND` 且 pin 完全匹配（同 workflowId/revision/digest/来源实例）才允许受控批次消费；
`POST /runtime/execute` 携带 `syncRunId`（来源批次）与 `tick`/Host批次消费都先核对绑定，不满足即
`COLLECTION_BINDING_REQUIRED` 失败停止，任务转 `FAILED` 且不再读取来源。**解绑即时停止消费**。
`MANUAL_SAMPLE` 显式执行是用户显式输入，不是采集，不需要绑定。任务启停仍由既有
[运行时控制契约](workflow-runtime-control.md) 负责，本契约不重复其语义。

启用/解绑都**不启动采集、不写实体、不调用模型**；发布、读取、翻页都不会创建绑定。

## 2. 回执保留预算与清理

仿扫描运行预算的形状（每范围上限 + 可见预算），但把不适合回执的部分改掉：**打开扫描时不清除、
读取不清理、受保护记录永不删除、默认不自动删除**。

| 接口 | 请求 | 响应 |
|---|---|---|
| GET `/api/v1/integrations/receipts/retention` | 禁止查询参数 | `receipt-retention-budget` |
| POST `/api/v1/integrations/receipts/retention/preview` | `receipt-retention-preview-command` | `receipt-retention-preview` |
| POST `/api/v1/integrations/receipts/retention/confirm` | `receipt-retention-confirm-command` | `receipt-retention-receipt` |
| GET `/api/v1/integrations/receipts/retention/commands/{requestId}` | — | `receipt-retention-receipt` |

回执类型与范围（`kind`/`scopeKey` 闭集）：

| kind | 存储 | 范围 scopeKey | 受保护（永不删除） |
|---|---|---|---|
| `onboarding` | `integration.source_setup` | owner 主体 | 仍被 `integration.source_instance` 引用（即还存在来源实例）的接入回执 |
| `source-command` | `integration.source_command` | 来源实例 id | 每个来源实例**当前最新**的命令回执（该实例的当前权威） |
| `external-link` | `inventory.external_link_registration` | 登记命名空间 | 每个外部对象键**当前最新**的登记回执（绑定权威） |

预算默认每范围 1000 / 每租户 5000（`kept` 只计可清理行，`keptProtected` 另计），状态
`OK/NEAR_LIMIT/AT_LIMIT`（80% 起告警）。预览**只读**，返回按时间从旧到新的候选 id（≤100）、
`previewDigest`、`keptProtected`、`keptUnfinished`；`keptUnfinished` 恒为 0 —— 这三类回执没有
"未结束"状态，未结束记录（进行中扫描、未结束关系、未关闭 Incident）**从不进入候选集**，
该字段保留是为了让"未结束记录未被删除"这一保证在回执上可见。
确认命令必须带回 `previewDigest` 与 `maxRemove`；候选集变化即 409（不静默扩大删除范围），
同 requestId 重放返回原回执且不重复删除。删除只移除**被取代**的旧回执，不级联、不改写任何对象。

## 3. 运维删除入口

| 接口 | 请求 | 响应 |
|---|---|---|
| POST `/api/v1/entities/{entityId}/deletions` | `entity-deletion-command` | `entity-deletion-receipt` |
| GET `/api/v1/entities/deletions/commands/{requestId}` | — | `entity-deletion-receipt` |

命令字段闭合：`requestId`、`entityId`、`reason`(必填)。要求租户级 `entity.manage`（对象级授权只能读，
不能删）。**只做软删除**：`inventory.entity.lifecycle → DELETED`、版本 +1（CAS），行保留，
所以旧 Incident/Evidence 引用继续可解析，关系/历史/证据行一律不级联、不改写（设计 §5 语义）。
目标仍是**当前关系记录端点**时返回 409 `ENTITY_DELETION_PROTECTED`（受保护，不删除）；
已删除目标的新请求 409 `ENTITY_DELETION_CONFLICT`；同 requestId 重放返回原回执。
回执记录 `auditCount`（保留的审计行数）、`keptProtected`/`keptUnfinished`（保留未删的记录数），
使"未删除受保护/未结束记录"这件事在回执上可见。**应用层删除不等于物理擦除**：WAL、副本、
磁盘残留不在平台控制范围内（ADR-048）。

## 边界

- 三类命令都不接受 tenant/actor/权限/地址；身份一律来自可信 Principal。
- 备份/恢复/WAL 擦除**仍未实施**，本契约不提供任何恢复或擦除承诺。
- 清理会移除旧请求的幂等答案，因此永远是显式人工动作；没有定时删除、没有读取触发删除。
- Web 面沿用"未知结果保留原请求原样重试、身份切换清理私有状态"的既有边界。
