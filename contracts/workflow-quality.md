# 工作流运行质量与异常

固定发布版本的只读观察；契约唯一源为本目录四份 `workflow-quality-*.schema.json`。复用既有主机分页、指标窗口及日志窗口证明，不增加采集、数据库或启动单元。

- `GET /api/v1/integrations/workflows/quality/workflows/{id}/versions/{revision}` 返回 `workflow-quality-report`。当前租户、本人、指定发布版本最近20批；适配器最多取21批以准确返回 `truncated`，版本过滤先于限制。
- `GET .../batches/{batchId}` 返回 `workflow-quality-batch`。规范小写完整UUID，可查列表之外的原证明；租户、本人及版本必须一致。
- 拒绝查询参数和写方法。不存在404、无可信会话401、权限不足403、损坏元数据503，没有失败回退空结果。

`reference` 固定版本及摘要；`asOf` 是报告读取时间，`task.updatedAt` 是任务更新时间，`observedAt` 是准入/分页观察时间。窗口范围 `[from,till)`。这些时间不代表来源最新事件，不代替连接测试、新鲜度或完整链路健康。主机优先显示周期调度状态，否则显示单次扫描状态。

`sampleRate` 为null：没有总体分母，不推算采样率。`coverage=WINDOW` 只表示本次窗口端口完整读取；`PAGE` 只表示本页，不宣称全源或全部历史完整。转换计数只覆盖已准入批次；准入之前的来源/转换失败保留任务错误，不补造一批输入0的数据。

| 分项 | 指标窗口 | 日志窗口 | 主机分页 |
| --- | --- | --- | --- |
| input | 源样本数 | 源记录数，含旧位置 | 分页记录数 |
| accepted / rejected | 去重后通过 / 0 | 旧位置去重后新增通过 / 0 | 未保存，null |
| filtered | 过滤数 | 过滤数 | 未保存，null |
| deduplicated | 同毫秒合并数 | 补采旧接受位置数 | 未保存，null |
| outputExpected | 本批时间点数 | 本批新增位置数 | 未保存，null |
| confirmed | 本批确认点数 | 新增确认条目数 | 已有写入回执的实体数，可能只是部分 |
| outputRejected / unknown / pending | FAILED / UNKNOWN / IN_FLIGHT的预期数 | 同左 | 未保存，null |
| repeatedOutput | 补采重交的旧时间点数 | 0（不重发旧位置） | 未保存，null |
| late | 补采新增时间点数 | 补采新增接受条目数 | 未保存，null |

窗口 `input=accepted+rejected+filtered+deduplicated`，`outputExpected=confirmed+outputRejected+unknown+pending`。准入后转换无拒绝是现有协议事实，不声称准入之前没有坏数据。主机不能拿实体数反推转换通过、过滤或未知数量。

确认率仅针对同批：`confirmed/outputExpected`。分母缺失、0或分子缺失显示“—”。UNKNOWN保留未知，不计成输出拒绝或成功；IN_FLIGHT保留待确认。不跨批累加，因为补采、失败后的重新读取和重试可能覆盖同一窗口。

公有输出无正文、实体ID、指标值、位置数组、凭据或后台身份。主机恢复行及已确认实体逐一检查读取范围；指标/日志检查目的读取权限。来源配置只检查元数据访问，不接通采集端口。质量API没有核验、修复、放弃UNKNOWN或重放权限。

节点预览问题继续在只读测试运行详情展示。schemaMismatch、missingIdentity、invalidTimestamp、unitMismatch、queueWait及总体完整性等专门统计尚未持久化，不从通用错误码猜数量。本接口不宣称完成全部S7质量、告警、历史修订、保留或容量能力。

旧日志拒绝观察（§130）：LOG_STREAM 原批次为 FAILED 而缺少私有 rejection_known 证据时，公开质量批次投影为 UNKNOWN/OUTPUT_UNCONFIRMED。预期记录数全部计入 unknown，不计为 outputRejected 或 confirmed；原UUID、窗口、时间及分项不变。当前或归档失败任务关联该批次时，质量 task.error 投影为 OUTPUT_UNCONFIRMED。该读取不改原存储体、不读取来源或输出。明确拒绝证据为true的 FAILED 保持拒绝分项。显式终止入口由[恢复协议](workflow-recovery.md)提供，质量API本身仍只读。

§131：单次样本UNKNOWN可明确[终止确认](workflow-sample-recovery.md)。终止事件与原输出证明分开；原UNKNOWN质量、正文/点值及计数保持，不将终止算作成功或拒绝。原验证入口关闭，授权数据回读保留，无自动重写。
