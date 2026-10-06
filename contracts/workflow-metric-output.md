# 固定指标工作流输出契约

唯一 wire 源是本目录 v2 Schema：

- [请求](schemas/v2/workflow-metric-output-request.schema.json)：requestId、id、revision、digest、previewId、samples 六字段。samples为1–5个闭合 timestamp/sourceKey/value 原值字符串，不接受 tenant、授权或地址。
- [回执](schemas/v2/workflow-metric-output-receipt.schema.json)：20字段的低频批次元数据，不含数值。commandDigest/seriesHash/batchDigest与时间范围不可替换；state/updatedAt和数量可按状态机细化。
- [能力](schemas/v2/workflow-metric-output-capability.schema.json)：FIXED_METRIC_SAMPLE、available、maxPoints=5。available仅代表受控配置存在，真实端口失败不回退。
- [最近记录](schemas/v2/workflow-metric-output-history.schema.json)：当前可信 owner 的某工作流最多20份，truncated明确表示部分；页面按当前版本筛选时也保留截断提示。
- [点回读](schemas/v2/workflow-metric-output-points.schema.json)：固定原请求的最多5个 timestampMillis/value 点，queriedAt、dataMode=time-series、expectedPoints及proofMatches。值为有界普通十进制字符串，不使用指数/NaN。

接口前缀 `/api/v1/integrations/workflows/metric-outputs`：GET能力，POST写入，GET `/workflows/{id}/receipts` 最近记录，GET `/commands/{requestId}` 原元数据，GET `/commands/{requestId}/verification` 显式验证，GET `/commands/{requestId}/points` 当前点。禁止额外查询参数；可信身份决定 tenant、owner、来源和完整指标访问范围。

CONFIRMED：confirmed等于归一化时间戳数、failed=unknown=0。FAILED：failed等于时间戳数、confirmed=unknown=0，仅用于确定的写入前拒绝。PENDING/UNKNOWN：unknown等于时间戳数，confirmed=failed=0；UNKNOWN固定OUTPUT_UNCONFIRMED。accepted是通过转换的行数，filtered是过滤行数，collapsed是相同毫秒相同值合并数；accepted-collapsed=时间戳数，confirmed+failed+unknown=时间戳数。跨字段、排序、摘要和原回执关系由领域和客户端进一步校验。

相同键/内容返回原记录，无来源/时序写入；不同内容409。UNKNOWN验证只读，不重写。确认元数据并不保证今后点不会因保留策略丢失，当前点必须另读并检查proofMatches。参见[ADR-077](../docs/adr/077-confirmed-workflow-metric-output.md)。
