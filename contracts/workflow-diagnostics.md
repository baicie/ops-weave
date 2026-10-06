# 工作流检查统计

来源端口统计见[来源诊断](workflow-source-diagnostics.md)，实测本地排队见[调度观察](workflow-dispatch-diagnostics.md)。来源完整返回数量与转换分母分开，失败数量为空；未测量的可选对象省略，历史不重写。

固定发布版本的只读接口位于 `/api/v1/integrations/workflows/quality/workflows/{id}/versions/{revision}/diagnostics`。GET返回当前租户、本人、版本的最近20次检查及截断标志；`/{observationId}`按原完整UUID查询更早记录。租户和本人来自可信身份，来源、目标及读取范围沿用工作流门禁，不接受查询参数。没有采集、核验、重发或动作接口；不支持写方法返回405。

公开线协议源为六份v2 Schema：`workflow-diagnostic-node`、`workflow-diagnostic-result`、`workflow-diagnostic-source`、`workflow-diagnostic-dispatch`、`workflow-diagnostic-observation`、`workflow-diagnostic-report`。私有存储另有 `workflow-diagnostic-stored` Schema，不作为HTTP投影。公开记录没有原始输入、转换值、实体清单、凭据、authority或异常正文。时间采用规范UTC；指标/日志的from/till表示来源读取的60秒半开窗口，主机分页两者为null；startedAt/completedAt只表示本次检查时间，不表示连接健康或数据新鲜度。

| 字段 | 口径 |
| --- | --- |
| received | 来源成功返回的完整有界列表数量；来源没有返回列表时为null |
| accepted / rejected / filtered | 已实际执行转换校验的记录处置，未形成输出批次前仍不代表写入确认 |
| unknown | 提前中止后未完成处置的剩余记录；accepted+rejected+filtered+unknown=received |
| schemaMismatch | 出现闭合结构/字段校验异常的拒绝记录数，同一记录多个此类问题只计一次 |
| missingIdentity | sourceKey必需标识字段缺失、null或空值的已测拒绝数；不推算未返回列表中的标识缺失 |
| invalidTimestamp | timestamp/eventTime字段校验或实际窗口范围拒绝数 |
| unitMismatch | 固定规范化指标计划中测得的unit字段拒绝数；日志和主机不测量此维度，值为null |
| nodes | 每个实际步骤的输入、通过、拒绝、过滤、跳过和闭合异常代码计数；单节点received=accepted+rejected+filtered |
| sampleRate | 当前没有测量总体采样分母，保持null |
| queueWaitMillis / dispatch | 未实测时null；实测时为本地入队至出队的整数毫秒与闭合时间证据 |

异常类别可能重叠，不应相加推算拒绝量。节点输入不跨节点累计，不跨重叠窗口累计通过率。SOURCE_FAILED表示没有可用的转换处置统计，所有数量null、nodes为空；来源端口直接拒绝INVALID_SAMPLE也属于这种情况，不猜测收到多少条。CHECKED要求完整且无拒绝，REJECTED必须包含实际拒绝；PARTIAL保留未检查量。健康空列表的数量为0、覆盖为COMPLETE，不能据此声称整体接入健康。

当前生产者接入持续指标、持续日志和固定连接主机新分页的来源读取/转换校验。主机每页最多5条，known received等于私有规范实体UUID见证数量；被过滤或拒绝的来源实体也保留见证。报告及原UUID读取逐实体验证当前权限，并核对固定发布来源种类。正常检查的relatedBatchId指向同租户/本人/固定版本的原批次，实体集合和数量必须一致；缺失或损坏失败。拒绝发生在批次准入前时关联为空，不输出、不推进检查点。UNKNOWN显式恢复复用原分页和原统计，不重读来源或重复计数。未固定连接的旧主机任务没有新增生产者。见[ADR-089](../docs/adr/089-scoped-host-page-validation-diagnostics.md)。

主机分页COMPLETE仅表示本页处置完成；不代表完整清单、输出确认或整体健康。来源、权限或准入失败没有完成处置时仍保留UNAVAILABLE，不从错误码推算分母。unitMismatch=0仅说明固定规范化指标计划中未测得该类拒绝，来源元数据不匹配且未读出列表时统计仍不可用。输出确认、迟到去重、UNKNOWN和待确认继续使用原批次质量协议。私有见证及父批次的保留策略必须协调，不以删除父批次绕过读取校验。

既有PG的V049表只保存一次检查的闭合元数据，每个租户/本人最多200条，执行器在来源IO前检查容量、记录前再次检查当前代数/身份/版本。容量耗尽暂停新检查，不删除旧记录或自动恢复。固定版本过滤先于21条查询限制；损坏元数据失败，不变成空成功。当前没有生产保留周期、阈值告警、历史重放或分布式执行保证。
