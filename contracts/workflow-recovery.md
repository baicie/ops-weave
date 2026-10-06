# 固定任务终止恢复协议

适用于已发布且固定接入配置的 HOST_SCAN、METRIC_STREAM、LOG_STREAM。任务因原批次输出结果不明而处于 FAILED/STOPPED 时，可由当前可信身份明确终止该版本的恢复。原批次保留原未知结果（含缺少拒绝证据的旧日志FAILED），可能已写入的资产、指标、日志不删除，不补写、不核验、不推进已确认检查点。

- `POST /api/v1/integrations/workflows/recovery/abandon`：闭合 [command](schemas/v2/workflow-recovery-command.schema.json)，必须确认 `acknowledgeUncertainOutput=true`。固定版本、digest、类别、原 batchId 和 expectedGeneration 全部匹配。
- `GET /api/v1/integrations/workflows/recovery/commands/{requestId}`：返回不可变 [receipt](schemas/v2/workflow-recovery-receipt.schema.json)。原 UUID 和原内容重放只返回原回执；不同内容返回冲突。路由不接受客户端身份/查询参数，回执不允许修改。

命令摘要采用 `WorkflowDefinition.hash` 的 UTF-8 字节长度前缀，依次编码 `workflow-abandon-recovery-v1`、id、十进制 revision、digest、kind、规范小写 batchId、十进制 expectedGeneration、`true`。requestId 作为幂等键，不参与摘要。回执代数为 expectedGeneration+1；状态 ABANDONED 是任务恢复终态，不是输出确认状态。

任务和回执在同一租户事务提交；撤掉私有后台授权，保留原游标、确认数量、待确认 batchId。主机 checkpoint 仅更新代数/更新时间，原扫描和私有分页保持；运行中的同版本周期任务停止。回执不含正文、点值、凭据、权限或 authority；HOST 的 preservedCursor 固定 null，私有清单分页游标仅保留在内部 checkpoint。每本人最多200条回执，拒绝发生在修改任务之前。任务、批次、checkpoint 的时间不得向后回退。

身份来自服务器认证边界，按本人/租户限制原证明，检查工作流、固定来源、输出读取范围；主机校验所有输入/输出实体范围。回执读取与重放重新检查当前权限和原不确定父批次，不能靠菜单或命令中的身份字段替代授权。

本版本不能 START/RESUME/STOP 或再核验原批次。明确启动更高发布版本建立新游标和新授权，以当前全流程代数做 CAS；历史回执、原批次和质量观察保持。METRIC/LOG 状态查询增加唯一可选 `?revision=N`，先过滤固定版本再限制20条；未带参数的旧接口保持原形状。按版本响应增加闭合 `control={generation,startAllowed,recoveryClosed}`，控制代数与当前版本任务分开，不将旧任务冒充新任务。任务已经由新版本替换后，旧版本质量报告仍通过原回执展示 ABANDONED。

Web 原响应丢失或非法时保留原命令，不自动查询/重发。明确查询原 UUID；原查询404后才允许原样重发，也可明确放弃本地确认；服务器命令不会因此取消。待确认时阻止关闭、版本切换及折叠请求容器。403/身份变化清私有状态，隐藏取消请求、忽略迟到响应。受理后明确刷新当前报告。

本协议不处理单次指标/日志样本证明的放弃，不提供历史补写/删除/重放，也不承诺生产 HA。参见 [ADR-090](../docs/adr/090-explicit-workflow-recovery-closure.md) 和 [使用说明](../docs/runbooks/workflow-recovery.md)。

旧日志结果扩展（§130）：LOG_STREAM 原父批次允许 UNKNOWN，或 FAILED 且私有 rejection_known=false。后者是原写入结果缺乏证据的情况；不是明确拒绝。任务仍须当前FAILED/STOPPED、固定ref/代数/原pending完全匹配。终止只改任务与追加原回执，父批次原FAILED体、证据false、游标和确认数量保持。原UUID重放、回执读取及更高版本替换都校验同一不确定父对象。明确拒绝证据true不能用此入口终止；旧不确定批次仍不可核验或重新写入，质量投影保持UNKNOWN。参见[ADR-092](../docs/adr/092-legacy-log-outcome-uncertainty.md)。
