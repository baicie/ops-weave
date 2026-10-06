# 工作流日志批次输出契约

固定端点 `/api/v1/integrations/workflows/log-outputs`。手工命令接受固定已发布工作流、原只读 RUN、请求 UUID 和最多5条标量样本；POST `/source` 的来源命令只接受 requestId/id/revision/digest，由服务器读取固定日志项并生成只读 RUN。请求不携带 tenant、subject、授权、端点、表名或 SQL。契约唯一源为 `schemas/v2/workflow-log-output-*.schema.json`，examples 中均为显式 Fixture。

| 方法 / 路径 | 语义 |
| --- | --- |
| GET `/workflows/{id}/capability` | 实际表结构/引擎/SELECT可用性及当前工作流范围的 log.read/log.write 权限 |
| GET `/workflows/{id}/{revision}/capability` | 固定 ZABBIX_LOG→LOG 版本的 SOURCE_LOG_SAMPLE 能力与目的权限 |
| POST `/source` | 服务器读取并转换最新最多5条样本，再受理一个独立 UUID 输出批次 |
| POST 根路径 | 先持久受理回执，再进行一次外部写入和一次精确回读 |
| GET `/commands/{requestId}` | 自己的原回执，仅元数据 |
| GET `/workflows/{id}/receipts` | 自己最近20条回执；更多以 truncated 标注 |
| GET `/commands/{requestId}/verification` | 查询原固定批次并细化回执，不写入 |
| GET `/commands/{requestId}/records` | log.read 授权后的实际正文；complete 明确标注匹配结果 |

手工端点已发布版本必须是 MANUAL_SAMPLE→LOG。其 RUN 必须属于当前 subject，同 digest/source/target/inputDigest，最近15分钟内、无拒绝/原始缺失/截断；正文转换后保留原文，时间在该 RUN 执行前24小时至回执时间内。log.write 和 log.read 是独立权限；编辑工作流或读取回执不授予正文访问权。数量、时间与共享输出并发预算均由 Java 执行器检查。

来源端点只允许固定 ZABBIX_LOG→LOG，由服务器执行规则并保存只读 RUN；浏览器不传 samples/previewId/URL/pin/身份。最新最多5条是明确样本，关联 RUN 保留截断信息；完整批次确认只指本批输出。新受理前复核配置/秘密、来源/实体/日志项与输出权限、同固定版本和有效 RUN；结果留在内存，不从 PG 重建正文。源命令摘要为 `workflow-log-source-command-v1` + id/revision/digest 的 UTF-8长度前缀 SHA-256，与手工命令 namespace 不同。已有受理回执按原 UUID 返回，停止来源新读取和输出。来源失败在输出受理前返回错误，没有已写入声明。

PG回执只保存固定引用、摘要、原输入索引和计数，不保存正文、样本、权限快照或凭据。租户、owner_scope、请求、固定工作流版本共同限定日志查询。服务端还检查原索引与整个批次摘要，HTTP 200/行数相等不代表确认。CONFIRMED只在全批次匹配时成立；部分数据、丢失响应、写后异常为UNKNOWN。PENDING表示受理后尚未完成确认。FAILED只表示外部尝试前明确拒绝。UNKNOWN/PENDING可通过查询提升为CONFIRMED，不得降为FAILED。

数值标量在命令摘要中使用去除末尾零、无指数的精确十进制表示（0保留为0）；JSON等值数字表示不产生跨语言摘要差异。原RUN输入摘要仍沿用既有流程。相同 UUID 和完整原命令只返回原回执，不再次写入；变更内容返回409。跨用户/租户回执为404，无权限为403，来源/存储不可用为503，并发满为429。解析错误不回显正文。新回执每subject最多200条，达到上限拒绝新命令，不自动清理以规避容量。

前端首次展开默认读取能力与历史，不自动POST。只有用户明确写入才读取本次手工样本并生成固定命令。网络结果不明/契约异常保留原命令；只有查询原回执明确404后，才允许显式重发完整原命令。历史回执没有原样本时只可查询/验证。正文以转义文本展示，不执行HTML/脚本或自动访问其中URL；清除身份和页面卸载中断请求并丢弃晚到结果。

外部日志样本已接通，见[来源契约](workflow-log-sources.md)与[ADR-084](../docs/adr/084-fixed-log-workflow-sources.md)。本阶段不声明持续采集/checkpoint、生产认证、分布式exactly-once、长期保留策略或自动补偿。具体实现边界见 [ADR-082](../docs/adr/082-confirmed-workflow-log-storage.md)。
