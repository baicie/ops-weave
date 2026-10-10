# ADR-101 关系唯一性、终止不复活与来源关系输出边界

状态：冲突策略、RELATION 输出格式/领域语义、定义域/评估/契约与服务端执行链（执行/批次消费/PENDING 持久化回执）已实施（2026-10-09，3b-1 + 3b-2）；Web 配置面与回执展示已实施（2026-10-10，3b-3）

## 背景

设计第5节要求"来源记录与人工断言的冲突要可见，采用明确策略，禁止最后写入者静默获胜"，且"终止关联设置有效结束时间或追加撤销记录，保留历史"。此前创建入口允许同一对端点、同一关系类型重复写入关系实例，且一条已终止的关系可以被再次创建而静默复活——重复写入者覆盖了历史语义。同时，"来源关系输出"（工作流从来源批次写关系实例）尚未实现，端点解析必须显式确定，不能按名字/IP 猜测。

## 决策

1. **关系实例唯一性**：一对端点在同租户、同一关系类型下只存在一条实例记录（不区分模型 revision 与有效区间）。创建时如已存在同端点同类型记录（无论活动或已终止），写事务返回 409 `RELATION_CONFLICT`，不新增、不合并、不覆盖。同 `requestId` 的幂等重放先于该检查，行为不变。
2. **终止不复活**：已终止的关系保持唯一占位；后续任何创建（人工或来源）在同一端点/类型上都得到显式 409，历史中的原记录、理由与来源标注不被改写。"重新建立"属于将来的更正/新版本流程，不通过重复创建绕过。
3. **冲突可见**：冲突以稳定错误码 `RELATION_CONFLICT` 返回，历史/详情保留原记录、终止理由与对象版本；不引入"最后写入者获胜"，也不自动改写人工断言。
4. **来源关系输出（格式契约、定义域、评估与服务端执行链已实施 3b-1 + 3b-2，Web 配置面 3b-3 待做）**：
   - 输出目标为已发布 RELATION 模型 pin（id/revision/digest），行内 `fromField`/`toField` 保存**显式来源身份**，不接受名字/IP 猜测（`workflow-output` RELATION 变体已入契约）；
   - 端点解析走已登记的确定性外部链接（`ExternalObjectKey`/`ExternalLink`，与工作流实体输出同一命名空间，解析端口 `WorkflowRelationOutput.Endpoints`），解析不到进入**待处理（PENDING）**记录（FROM_UNRESOLVED/TO_UNRESOLVED），不写关系、不丢行；
   - 写入复用 `EntityRelationService` 的唯一性、基数、幂等与授权规则（本决策 1、ADR-100）；确定性 requestId 使同一来源行跨执行幂等；重复/复活显式 409 失败关闭；来源失败不删除既有关系；
   - **定义域/评估/契约（3b-1）已实施**：`WorkflowDefinition.Target` 新增 RELATION 变体（pin 与 `fromField`/`toField` 闭集校验、二者不同、禁止 mappingPin/metricKey），定义 digest 追加 `"relation-output",fromField,toField`；`requireOutput` 对 RELATION 模型按 ref/digest 匹配（不匹配 MODEL_CHANGED），节点字段受 `fromField`/`toField` 约束，ZABBIX_HOST 来源允许 RELATION 目标；评估 VALIDATE 步骤按行校验两个显式身份字段（存在、String/Number/Boolean、非空白、toString ≤256，否则该行 REJECTED，沿用闭集 issue 码）；`workflow-definition` 契约 target 联合加入 RELATION 变体（与 `workflow-output` 同形状）。
   - **服务端执行链与持久化（3b-2）已实施**：`/runtime/execute` 与批次消费（tick/Host 批次）在 RELATION 目标下经同一写入端写关系实例（`sourceRef="workflow:"+namespace`、origin 映射 dataMode、确定性 requestId 幂等）；`WorkflowRuntime.Execution` 与 Host 批次回执携带 `relationIds`/`pending` 闭集字段，PENDING 行不写关系、不丢行、不计入 written/filtered，SUCCEEDED 时 `entityIds+relationIds+pending=accepted`；写入异常（含本决策 1、2 的 409）记 `OUTPUT_UNAVAILABLE` 失败停止并保留故障前已写行，只有确认批次推进游标；V064 回填既有 body，wire/契约 Schema 同步闭合。
   - **未实施（后续增量）**：外部链接登记入口、多来源确认合并与关系更正（改端点/类型）；不改契约不能夹带，须独立增量。

## 后果与边界

- 已覆盖：重复创建与终止复活显式 409、历史保留、幂等语义不变。
- 边界：唯一性按"端点+关系类型"计（忽略 revision/区间），一对端点在同一关系类型下无法并存多条来源/人工记录；多来源确认合并、关系更正未实现，外部链接登记入口未实现，OW-ST05 保持部分完成。
- 存储：PostgreSQL 在写事务内、双端实体行锁定后做存在性检查，并发写入同端点被串行化；内存实现同步。
