# 固定任务版本替换

使用现有 HOST、METRIC、LOG 控制命令的 START 和原 requestId，不增加隐式升级或新的执行接口。命令固定 id/revision/digest 和当前 expectedGeneration；新版本必须已发布且 revision 高于当前任务。受信身份、原与新来源和输出范围在服务器检查。

- STOPPED/FAILED 且没有待处理批次，可明确启动新版本。
- 待处理批次明确 FAILED 才可升级；主机还须 entityIds 为空。READY/IN_FLIGHT/UNKNOWN 阻止升级。
- ABANDONED 必须有对应终止回执和原 UNKNOWN 批次，原不确定结果保留。
- 运行中、同版本重启持续指标/日志、降级以及旧版本 RESUME 均拒绝。主机已完成扫描的同版本新扫描沿用既有行为。

替换从当前控制代数增加一代，签发新的受控后台授权，重新初始化游标和计数。原固定批次、确认记录、诊断和回执保持。旧控制 UUID 查询/重放不会改变新任务或增加快照。

私有 [workflow-task-archive.schema.json](schemas/v2/workflow-task-archive.schema.json) 和[示例](examples/v2/workflow-task-archive.json) 保存八个闭合字段：schemaVersion、reference、kind、task、schedule、replacedBy、nextGeneration、replacedAt。task/schedule 只含原质量状态五个字段；非主机的 schedule 固定 null。服务器还校验相同流程、严格递增版本、nextGeneration=task.generation+1、时间不回退、匹配已发布摘要及原批次父对象。这些跨字段约束不能用示例代替执行校验。

快照不作为公开新接口，不包含身份、授权、游标或数据正文。本人最多 200 条；与替换和控制回执一起原子提交，不自动淘汰旧历史。历史读取保持原质量报告契约，按旧版本返回终态；持续状态的 control.generation 属于当前任务，不能用历史 task.generation 控制新任务。

LOG的FAILED还必须有服务器保存的明确未写入证据；V052旧行默认没有证据，原结果体保持。按版本LOG状态可返回resumeAllowed布尔值，true要求当前固定任务STOPPED/FAILED且可用代数；字段不授予权限。写入成功后的回读失败只产生UNKNOWN；旧拒绝未证实前不允许恢复/替换。
