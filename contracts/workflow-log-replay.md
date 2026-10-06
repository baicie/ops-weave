# 固定历史日志投影重建

使用同一 Java 平台、受控来源及现有日志引擎。公开契约唯一源为 v2 Schema；原持续窗口和样本契约保持。

| 对象 | Schema | 样例 |
| --- | --- | --- |
| 范围检查命令 | [command](schemas/v2/workflow-log-replay-command.schema.json) | [command](examples/v2/workflow-log-replay-command.json) |
| 已保存范围与证明 | [plan](schemas/v2/workflow-log-replay-plan.schema.json) | [plan](examples/v2/workflow-log-replay-plan.json) |
| 确认执行命令 | [execute](schemas/v2/workflow-log-replay-execute.schema.json) | [execute](examples/v2/workflow-log-replay-execute.json) |
| 原执行回执 | [receipt](schemas/v2/workflow-log-replay-receipt.schema.json) | [receipt](examples/v2/workflow-log-replay-receipt.json) |
| 最近范围记录 | [page](schemas/v2/workflow-log-replay-page.schema.json) | [page](examples/v2/workflow-log-replay-page.json) |
| 范围的原执行 | [execution](schemas/v2/workflow-log-replay-execution.schema.json) | [execution](examples/v2/workflow-log-replay-execution.json) |
| 明确读取的输出页 | [data](schemas/v2/workflow-log-replay-data.schema.json) | [data](examples/v2/workflow-log-replay-data.json) |

根路径 `/api/v1/integrations/workflows/log-replays`。POST `/plans` 只检查来源和转换；POST `/execute` 才允许输出。GET `/plans/{uuid}`、`/commands/{uuid}` 与 `/plans/{uuid}/execution` 查询原元数据；GET `/workflows/{id}/versions/{revision}/{digest}/plans` 最近20条并明确截断。POST `/commands/{uuid}/verification` 无请求正文，只核验原输出。GET `/plans/{uuid}/records` 与 `/plans/{uuid}/records/after/{index}` 明确查看50条输出页，沿用窗口数据结构，不修改回执。

命令闭合且不能提供 tenant、subject、权限、来源地址、原始记录或通知/动作设置。服务端每次检查当前 `workflow.replay` 与工作流范围、原 `source.sync` 与固定来源/连接/发现/实体门禁；日志使用原 `log` 资源 `workflow.{id}`，元数据/正文需 `log.read`，创建与执行还需 `log.write`。菜单与浏览器解析不替代执行器授权，默认权限不扩大。

历史窗为过去24小时内完整60秒且结束至少10秒，最多1,000条；网络失败或不完整不能变成空成功。第一步持久 PREPARING 后读取并转换，READY 保存原始输入摘要、输出摘要、数量、原始位置及接受索引，10分钟有效。正文与标准字段值只在内存和日志存储，不在 PG 证明或普通日志。tenant 字符语法与已有领域保持128字符上限；UUID/UTC纳秒位置使用规范表示。

确认命令绑定原范围与两份摘要。每个范围只允许一个执行 UUID，先存 PENDING，再重新读取同一窗并严格匹配；新增、缺失、正文或转换变化拒绝输出。结果写入既有日志引擎中的独立 `workflow_log_replays` 表，持续窗口表、样本表、原任务/授权/checkpoint不改。该表名由构建时白名单选择，不接受请求或模型指定。

整窗一次输出及精确回读；明确拒绝才 FAILED，写后读取失败或潜在写入保持 UNKNOWN。原 UUID 重复只查原记录；显式核验只读原范围/输出并比较摘要，早期 PENDING 不提前核验，超过180秒也不能推断未写入。输出页的 complete 是当前读取匹配，不取代原回执确认；不完整保留缺失，正文以文本展示。空完整范围确认0条且不调用输出。

本人最多200范围，最近20条加按 UUID 回查是开发容量门禁，不是生产留存。来源历史重新读取不保证曾经保存原始快照；不覆盖旧日志、不修复资产、不自动通知/动作，不声明跨存储 exactly-once 或租约/fencing/HA。

参见[ADR-099](../docs/adr/099-bounded-historical-log-reconstruction.md)、[操作说明](../docs/runbooks/workflow-log-replay.md)、[完整窗口](workflow-log-windows.md)与[历史指标重建](workflow-metric-replay.md)。
