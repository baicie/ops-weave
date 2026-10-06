# 固定指标连续窗口契约

唯一wire定义在schemas/v2/workflow-metric-stream-*。私有Task含authority，公开Task只含可选五字段authorization；无点值或凭据。API使用当前可信Principal，不能以请求tenant/user替换身份。

- GET /api/v1/integrations/workflows/metric-streams/workflows/{id}：闭合状态、窗口限制、Task及最近20窗元数据。首次任务为空；批次不是指标点值。
- POST /metric-streams/start、/stop、/resume：requestId、id、revision、digest、expectedGeneration；Operation由路径决定。首次START建立服务器时间窗口，后续只能恢复原固定版本。
- GET /metric-streams/commands/{id}：原不可变受理Receipt；原键查询404才允许重发同一控制内容，不能用当前Task代替原结果。
- POST /metric-streams/workflows/{id}/verification：仅batchId，读取原时序系列/时间戳并核对摘要，不发业务写入或读新来源。完整匹配才确认窗口；失败保持UNKNOWN。已失败任务确认后保持停止，恢复需新控制命令。

60秒半开来源范围[from,till)，等待10秒再读。每窗最多60点，第61点、排序/身份/语义异常均失败；健康0点和来源失败区分。摘要在首次业务输出前提交；非空IN_FLIGHT只尝试一次，UNKNOWN禁止重新写入。只有确认才能将checkpoint移到till。标准映射一次，精确毫秒存储冲突拒绝。

状态现包含maxBatchPoints=600、maxHistoryRequests=20、lookbackSeconds=60。批次可携带成对的reconcilesBatchId/latePoints；旧证明省略两字段时含义为常规窗口。非空引用必须指向同所有者/租户的已确认同版本、同系列、同范围证明，新时间戳包含全部原时间戳，latePoints等于新增标准点数量。补采确认只增加点数、不推进cursor或confirmedWindows；sessionBatches同时计常规与补采批次。WINDOW_INCOMPLETE表示来源读取未在数量/请求/时间预算内完成，SOURCE_WINDOW_CHANGED表示上一窗旧点缺失或值改变；两种情况均保留检查点。

参考[运行手册](../docs/runbooks/workflow-metric-streams.md)、[ADR-079](../docs/adr/079-confirmed-continuous-metric-windows.md)和[ADR-083](../docs/adr/083-bounded-metric-pagination-and-late-points.md)。
