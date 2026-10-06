# 单次样本终止确认

适用于已发布固定版本的 METRIC_SAMPLE、LOG_SAMPLE 原 UNKNOWN 证明，不接受持续任务种类或代数。主体、tenant、对象范围由可信边界提供，客户端不能声明或替代。

| 方法 | 路径（公共前缀 `/api/v1/integrations/workflows/sample-recovery`） | 结果 |
| --- | --- | --- |
| POST | `/abandon` | 不可变终止回执 |
| GET | `/commands/{requestId}` | 本人原命令回执；不存在404 |
| GET | `/batches/{kind}/{batchId}` | `schemaVersion:2.0, closure:null` 或原终止回执 |

无查询参数；读取路径禁止 POST/PUT/PATCH/DELETE，返回405/Allow:GET。非法闭合结构、非规范 UUID/UTC Instant 或 ack 不为 true 返回400；未认证401，权限不足403，不存在404，状态/摘要/时间/其他终止 UUID 冲突409。数据库不可用或损坏503，禁止以空状态回退。

命令9字段：requestId、id、revision、digest、kind、batchId、batchDigest、expectedUpdatedAt、acknowledgeUncertainOutput。expectedUpdatedAt 精确匹配原证明，UTC 时间采用 Instant 规范序列化，保留纳秒比较。摘要为 UTF-8 长度前缀 SHA-256，依次为 `workflow-abandon-sample-v1`、id、revision十进制、digest、kind、batchId、batchDigest、expectedUpdatedAt、`true`；UUID是幂等键，不进入内容摘要。

回执11字段包括完整发布 reference、原 batchDigest/proofUpdatedAt、acceptedAt、state=ABANDONED 及 uncertainRecords=1..5。摘要必须由这些不可变字段重新计算吻合。无原点值、正文、游标、authority、身份或连接配置。状态响应的 closure=null 表示尚无该批次终止事件，不证明原输出不存在。

原证明仍 UNKNOWN，原重放不写入，原 data/points/records 授权读取保留。终止后原 verification 冲突，包括终止在输出读取期间提交的竞态。新的显式样本请求不构成原输出重试或历史去重。每个 owner 跨两种样本最多200事件，只有未知原证明可关闭。

唯一契约源：[command](schemas/v2/workflow-sample-recovery-command.schema.json)、[receipt](schemas/v2/workflow-sample-recovery-receipt.schema.json)、[status](schemas/v2/workflow-sample-recovery-status.schema.json)。样例：[命令](examples/v2/workflow-sample-recovery-command.json)、[回执](examples/v2/workflow-sample-recovery-receipt.json)、[状态](examples/v2/workflow-sample-recovery-status.json)。决策见[ADR-093](../docs/adr/093-explicit-sample-output-closure.md)。
