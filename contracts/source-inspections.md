# 连接测试与来源发现契约

契约唯一源为`schemas/v2/source-inspection*.schema.json`。操作绑定已有接入实例的固定配置版本与连接摘要，沿用可信Principal、本人实例和物理来源授权；浏览器不能提交租户、主体、地址、凭据引用、分页游标或执行代码。

| 路径（均在`/api/v2/data-sources/{id}`下） | 行为 |
|---|---|
| POST /test | 显式连接探测并进行至多1条主机的授权读取 |
| POST /connection-check | `POST /test` 的实例级别语义别名；固定当前实例配置/凭据并执行同一有界授权读取 |
| POST /discover | 显式读取首个主机分页，至多5条，只保存字段类型和缺失情况 |
| POST /discover-metrics | 显式指标元数据发现，真实来源须固定版本化凭据，两次首21项核对、最多保留20项 |
| GET /inspections | 本人该实例最近20份记录；不探测、不分页补读、不自动重试 |
| GET /inspections/{requestId} | 查询原请求，始终复用原类型、配置版本与摘要 |
| GET /connection-checks | 仅返回该实例最近20份 `TEST` 连接检查记录；只读，不探测、不自动重试 |

所有接口拒绝未知查询参数；命令只有`requestId/configurationRevision/connectionDigest`。POST正文最多64KiB，UUID必须是完整规范格式，重复JSON键、尾随正文及额外字段拒绝。请求键在租户/主体范围内唯一；相同内容返回原记录，同键不同类型、实例、版本或摘要409。命令摘要为UTF-8字节长度前缀连接的`source-inspection-v2/sourceId/requestId/kind/configurationRevision/connectionDigest`。

先在现有integration事务保存PENDING，再在事务外读取来源，最后保存COMPLETED或UNKNOWN。进程共享两条检查并发槽；同一原请求的重放不再调用来源。完成保存时重新核对实例、固定配置与当前可信登记配置。归档、配置变化、结果不合法或超过65秒期限均不能保存为可信完成结果。超时PENDING通过读取派生为UNKNOWN，不写入另一次操作、不接管重试；65秒是结果接受期限，不能宣称所有第三方IO都在该时刻强制终止。现有连接器保持既有单次超时和有界分页协议。

完成记录包含`asOf/deadline/availableAt/expiresAt`；结果有效期15分钟。CURRENT仅能用于当前配置、未过期、完整的已核对结果。明确Fixture自检保持标注；完整连接的真实来源还需服务器在读前后及当前视图重检固定凭据版本。旧env凭据引用尚未版本化，即使本次授权读取成功也返回UNVERIFIED；指标发现要求先绑定完整版本化连接。任何旧回执都不授予后续执行权限或证明发布兼容。配置变更/归档/当前部署摘要变化为STALE，过期为EXPIRED；失败或不完整保留UNVERIFIED。历史不替换原始结果，读取校验历史配置引用，损坏失败。

来源只支持已有登记Zabbix Host，手工样本与归档实例不能测试/发现。TEST的匿名版本响应不足以证明授权读取通过；必须调用固定来源的有界主机读取。失败记录UNREACHABLE，不保留第三方错误正文、不回退Fixture。Fixture标注LABELED_FIXTURE；真实读取范围无法验证则UNVERIFIED。

DISCOVER的`scope=FIRST_HOST_PAGE`。字段路径只允许`hostid/host/name/status/interfaces.ip`，这是固定连接器暴露的原始来源键，和工作流标准化批次字段分别定义。观察类型为TEXT/NUMBER/BOOLEAN/TEXT_ARRAY/NULL/MIXED，nullable表示本次样本缺失；不保存字段值、完整客户记录或凭据。指纹按字段名排序，对`source-host-fields-v1`及各字段的`name/type/nullable`计算同一长度前缀摘要。

`complete=true`要求来源分页声明完整、没有nextCursor且扫描方法已验证；否则保留INCOMPLETE及实际观察字段。HOSTID_WATERMARK只说明受限主机集合的扫描方法，不证明所有可变属性是事务快照；空且完整的清单与UNREACHABLE失败明确区分。发现不自动修改映射、删除已有字段/资产、推进采集checkpoint或启用任务。

持久化使用V036/V039及现有PostgreSQL integration存储；最多200份检查回执/主体、20份最近读取、5个Host字段、5条Host观察记录或20项指标元数据，回执正文最多256KiB。满容量拒绝，不隐式删除证据。运行角色可SELECT/INSERT/UPDATE检查表，terminal结果只能由PENDING转换一次；没有增加数据库、启动单元、后台调度或分布式接管能力。

指标投影、类型兼容、来源/映射完整键及指纹见[source-metric-discovery.md](source-metric-discovery.md)与ADR-070。旧Host回执可缺少metricDiscovery，新指标完成结果必须包含此投影；新输出会规范化旧投影为null。

第109节新增DISCOVER_METRIC_PAGE与previousRequestId/metricPage，旧种类这两项为空。公开回执不包含私有metricMembership；PG持久编码独立，仍兼容旧回执。详情见[来源指标分页契约](source-metric-pages.md)。新分页CURRENT仅代表本页与配置有效，完整覆盖必须另外检查complete；manifest根期限优先于每页期限。
