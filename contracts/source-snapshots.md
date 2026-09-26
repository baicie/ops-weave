# 已登记资产的显式来源快照

四个 HTTP 操作及唯一 wire 定义见 OpenAPI 与 `source-snapshot-config`、`source-snapshot-input`、`source-snapshot-receipt`、`source-presence-page` schema/样例。来源、namespace、tenant、actor、权限、lease/fence 均由服务端配置/可信身份构造，不允许输入覆盖。JSON重复键、尾随JSON、未知字段、时间/数量/文本越界均拒绝。

- GET `/api/v1/integrations/cmdb/snapshots/config`：读取当前租户/操作者、固定来源、命名空间、字段映射摘要与解析引擎；需要完整管理权限。
- POST `/api/v1/integrations/cmdb/snapshots`：传入固定mappingDigest和input（requestId/observedAt/complete/records）。record只允许externalId/assetUuid/四个字符串字段values。最多100条；externalId和assetUuid各不重复。
- GET `/api/v1/integrations/cmdb/snapshots/{requestId}`：同操作者回读原回执，不再次执行。未知/隐藏404不能作为重发许可。
- GET `/api/v1/entities/{entityId}/source-presence`：当前实体读取权限；服务端判断该配置来源的最后确认与有效性，返回最多一条绑定。

新鲜的已登记UUID可以自动定位已有实体，字段仍是PENDING Review；Raw引用指向回执中的不可变规范化输入，而非虚构的厂商原文。完整批次只对账该来源的已知绑定，部分/失败输入不将缺项视为缺失；连续完整批次再次确认缺失会更新确认时间/请求标识，markedAbsent计入这些绑定。主来源与第二来源任一有效确认能保留资产，第二来源到期/登记撤销后查询不再使用它保护ACTIVE。字段仍保留各自审核/过期语义。人工字段接受与快照导入共同检查来源绑定，禁止同一外部对象改指其他实体或同一实体在该来源改用另一外部对象。

409明确区分 IDENTITY_UNRESOLVED、BINDING_CONFLICT、SNAPSHOT_OUTDATED、REQUEST_CONFLICT、SNAPSHOT_LIMIT；固定映射变化沿用 SOURCE_REVIEW_CONFLICT。503表示配置/持久存储/租约不可用，禁止自动回退。错误不包含客户原始载荷、堆栈或身份提示。副作用在同一个PG事务，旧回执查询也重新授权。

模式固定`import`，引擎固定`registered-cmdb-snapshot-v1`；不宣称厂商连接、自动采集、自动字段接受或多源Entity合并完成。每source/tenant保留绑定100、回执1000上限；不提供自动删除绑定/清零/fence/Raw入口。
