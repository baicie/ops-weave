# 工作流固定日志来源契约

唯一源为 `schemas/v2/workflow-source.schema.json`、`workflow-log-source-pin.schema.json`、`workflow-log-source-page.schema.json` 与 `workflow-log-source-output-request.schema.json`。examples 均显式使用 Fixture。

GET `/api/v2/data-sources/{sourceId}/connection/{revision}/workflow-logs` 不接受 query/body/tenant/URL。返回固定 HOST 连接引用、最多100个可读日志项选择、asOf/expiresAt 和 truncated；它只读取已保存发现元数据，不调用外部 history。不完整、失败、Fixture 或过期发现不提供新选择；分页页必须有同 owner、配置版本和摘要的服务端 root manifest。

选择项 source 是 `ZABBIX_LOG`，同时具有固定 configuration 和 log pin；log pin 为 inspectionId/itemId/hostId/sourceKey/sourceUnit/sourceValueType/digest 七字段。仅 LOG 类型可选，不要求它具有标准指标 mapping。发现中的 NO_MAPPING/null 保持真实含义。pin 摘要是 UTF-8 长度前缀串 `workflow-log-source-v1` 加上述前六字段的 SHA-256，不能用于授权。

工作流定义只允许 ZABBIX_LOG→LOG，禁止在其他来源附带 log 或在日志来源携带 metric。定义摘要在固定连接后加入 `fixed-log-source-v1` 和七字段；旧来源的定义摘要不变。版本比较报告日志发现、item/host、完整键、类型、单位、摘要差异。

预览/只读 RUN 从固定来源读取最近10分钟、最多5条；第6条只用作截断哨兵。SOURCE 输入字段：

| 字段 | 语义 |
| --- | --- |
| timestamp | 接收 clock/ns 的规范 UTC 时间；默认映射 eventTime |
| body | 未裁剪的原始正文，最多2048字符 |
| sourceKey | 固定日志项完整键 |
| logEventTime | 上游 timestamp 非零时的事件时间，否则 null |
| severityCode | 上游原始非负代码，以文本保留，不推断标准级别 |
| eventSource | 上游非空 source，否则 null；不推断服务 |
| eventId | 上游事件编号文本；0不被当成已知全局身份 |

来源前后 metadata 必须与固定 pin 一致。空记录、正文过长、非法顺序/时间/上下文或上游失败拒绝读取；不裁剪、不降级、不返回成功空数据。只读 RUN 和 PREVIEW 不写业务存储，原文不进入运行元数据。

source.sync、对应 Host 的 entity.read、日志项 `log:source.<instanceId>.item.<itemId>` 的 log.read 与既有连接/凭据边界均在服务器检查。输出目的范围独立；详情见[日志输出](workflow-log-output.md)。固定绑定过期后仍可读自己旧版本元数据，实际执行仍检查当前连接、秘密和元数据。

生产认证、连续日志检查点和历史去重未由此契约提供；见[ADR-084](../docs/adr/084-fixed-log-workflow-sources.md)。
