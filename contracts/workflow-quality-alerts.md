# 工作流质量阈值契约

唯一结构源为 `schemas/v2/workflow-quality-alert-*.schema.json`。Java 领域、严格存储解码及 Web 还执行时间、引用、摘要、规则顺序与状态算术校验。请求和返回均闭合，不接受租户、本人、权限或额外字段。

根路径为 `/api/v1/integrations/workflows/quality/workflows/{id}/versions/{revision}/alerts`，仅支持已授权的固定发布版本。

| 方法与相对路径 | 行为 |
| --- | --- |
| GET 根路径 | 当前配置与一次一致快照下的评估，未配置 configuration/from/till 为 null，evaluations 为空 |
| POST /configure | requestId、id、revision、digest、expectedVersion、windowSeconds、rules；路径及正文版本必须一致 |
| GET /commands/{requestId} | 本人原不可变回执，后续配置不能覆盖它；其他本人或其他固定版本不可读 |

规则按 SOURCE_FAILURES、OUTPUT_REJECTIONS、QUEUE_WAIT、TASK_FAILURE 的固定顺序归一化，每种最多一条，总计最多四条。空数组明确停用。windowSeconds 为 60 至 86,400；前两项阈值为 1 至 20，QUEUE_WAIT 为 1 至 3,600,000 ms，TASK_FAILURE 仅为 1。

配置包含 reference、editVersion、windowSeconds、rules、updatedAt。expectedVersion 为 0 表示从未配置，成功后 editVersion 加一。回执包含 schemaVersion=2.0、requestId、commandDigest、acceptedAt、configuration；acceptedAt 等于配置 updatedAt。摘要采用项目长度前缀 SHA-256，输入依次为 workflow-quality-alerts-v1、id、revision、digest、expectedVersion、windowSeconds、规范规则的 kind/threshold。requestId 不参与摘要，但回执必须匹配原 UUID。服务器重新计算摘要，不只接受格式正确的散列。

每本人回执容量 200；非空规则达到 199 后拒绝，空规则可使用第 200 个容量。到期或超量不自动删除回执。旧回执原 UUID 可继续读。400 表示非法请求，403 表示当前权限不足，404 表示当前授权范围内不存在，409 表示并发版本或原 UUID 内容冲突；存储和证据不完整导致请求失败，不能默认为零或空列表。

评估包含 kind、state、value、unit、sampleCount、missingCount、evidenceIds、truncated、reason。样本与缺失总数最多 20，依据 UUID 唯一且有界。COUNT 用于次数和任务状态，MILLISECONDS 用于排队。TRIGGERED 的已知 value 达到阈值，NORMAL 的已知 value 未达到阈值且不包含缺失；UNAVAILABLE 的 value 为 null，原因闭合为 NO_DATA、MISSING_MEASUREMENT、HISTORY_TRUNCATED、NO_CURRENT_TASK、AMBIGUOUS_ORDER。未知不能转换为实测零。

连续失败只数已证实的最近连续次数，达到阈值即停止；不是区间失败总量。同一时间的混合结果没有可靠顺序时无法评估。排队按 dispatch UUID 去重，峰值达到阈值仍保留其他缺失；峰值未达阈值且缺失或历史截断时无法评估。任务规则只判断当前精确版本是否 FAILED，STOPPED、ABANDONED 和旧归档任务不视为当前失败。

统计区间 from=asOf-windowSeconds、till=asOf，来源和输出证据按各自完成/观察时间过滤，最近 20 条不是完整历史。当前任务规则是当前状态观察，不将任务更新时间解释为区间事件。可读的旧诊断缺少 sourceRead/dispatch 时继续保留缺失。旧 FAILED 缺少拒绝证明、UNKNOWN、IN_FLIGHT 和 READY 都不能计入连续已证实输出拒绝。

此 API 只配置和评估阈值元数据，不发送通知或执行业务动作。样例：[命令](examples/v2/workflow-quality-alert-command.json)、[配置](examples/v2/workflow-quality-alert-configuration.json)、[回执](examples/v2/workflow-quality-alert-receipt.json)、[状态](examples/v2/workflow-quality-alert-status.json)。见[ADR-096](../docs/adr/096-configurable-workflow-quality-thresholds.md)和[操作说明](../docs/runbooks/workflow-quality-alerts.md)。
