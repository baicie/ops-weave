# 工作流调度观察

这是现有只读诊断的可选扩展，唯一 wire Schema 为 `schemas/v2/workflow-diagnostic-dispatch.schema.json`。Observation 的 dispatch 与 queueWaitMillis 必须成对：未实测时缺少或为 null 的 dispatch 对应 null；实测对象对应非负整数毫秒。

| 字段 | 含义与约束 |
| --- | --- |
| id | 规范小写 UUID，表示一次实际本地出队；不是批次或来源调用 UUID |
| enqueuedAt | 本次本人轮询将任务放入有界 FIFO 的规范 UTC 时间 |
| startedAt | 出队开始处理的规范 UTC 时间，不得早于入队或晚于父 Observation.startedAt |
| queueWaitMillis | floor((dispatch.startedAt - dispatch.enqueuedAt) / 1 ms)，范围 0 至 3,600,000 |

实际时间差也不得超过一小时，不能只校验截断后的毫秒。非规范时间、额外字段、标量与时间不一致、缺少实测证据的数值均拒绝。Schema 限定结构及成对关系；跨字段时间、算术及父观察校验由领域、存储解码和 Web 执行。

入队发生在现有 IO 预算取得之后。统计不包含取得预算之前、前次轮询或全局积压，不用窗口迟到、来源耗时或默认零替代。一项任务在同次出队检查两个窗口时，观察共享 dispatch.id，汇总调度次数必须去重，不能累加两个相同等待区间。

当前 PG 每本人任务快照最多 21 个，本地 FIFO 对应容量 21；超出容量明确失败，不自动删除任务。排队快照不能替代当前身份与状态，出队后仍复核停止、代数、授权时效、固定版本及来源权限。来源、转换和输出各自统计及证据保持。

沿用原诊断 GET 报告和完整 UUID 查询，无新执行接口。旧公开 13 字段、仅有 sourceRead 的 14 字段和新 15 字段均有明确兼容语义；可选对象为 null 时公开投影省略，不重写原存储。旧排队值保持 null，不能回填零。最近 20 条不是完整运行历史，损坏失败不能返回空成功。租户、本人、固定发布版本及当前对象范围门禁保持。

示例：[公开调度观察](examples/v2/workflow-diagnostic-dispatch.json)、[私有存储观察](examples/v2/workflow-diagnostic-dispatch-stored.json)。见[ADR-095](../docs/adr/095-measured-local-workflow-dispatch.md)和[操作说明](../docs/runbooks/workflow-dispatch-diagnostics.md)。
