# 数据源中心与接入配置 v1

权威JSON结构：schemas/v1/source-center-page、source-setup、source-setup-command、source-setup-confirmed.schema.json。示例examples/source-center-page.json明确为Fixture；摘要为契约占位值，不能用于真实确认。

| API | 含义 |
|---|---|
| GET /api/v1/integrations/sources | 授权可用类型、已发布ENTITY模型、本人最近20份接入配置及截断标记 |
| POST /api/v1/integrations/sources/confirm | requestId/name/description/source/connectionDigest；target可省略，保存来源回执；旧实体target仍原子创建初版草稿 |
| GET /api/v1/integrations/sources/{id} | 本人来源回执及可空的初版工作流；已有发布版本优先返回发布版 |
| GET /api/v1/integrations/sources/{id}/continuation | 本人创建记录对应的最新可读版本；最高revision，同revision发布版优先；没有版本则workflow=null |

所有API要求source.sync作用于workflow:*；Zabbix描述符和确认另要求当前source实例范围。实体target解析要求entity.read作用于catalog:*；无此权限时模型目录为空，来源确认及遥测工作流不强制读取模型目录。身份不可通过请求覆盖，不接受查询参数。POST拒绝未知/重复字段、尾随JSON及超过64KiB正文。输出no-store，日志不含原始请求、凭据或Authorization。

AVAILABLE仅表示当前身份有可用的配置类型，不表示网络已连通。ZABBIX_HOST的描述符来自平台配置；mode仅fixture或zabbix-jsonrpc，未配置/未授权为UNAVAILABLE且connection=null。MANUAL_SAMPLE固定manual，无外部连接。CMDB_SNAPSHOT固定LEGACY_IMPORT，不能通过此接口确认。JSONRPC地址不能携带userinfo/query/fragment，credentialRef只能是env引用名，绝非密钥值。

SourceSetup包含创建时dataMode，严格保留fixture标记。connectionDigest固定到当前来源配置，但不是密钥值摘要。initialTarget可以为null，此时workflow可以为null、确认不创建草稿；后续工作流按OutputKind选择格式。旧式实体target仍固定到已发布ENTITY模型和digest，空字段模型不能生成草稿。UUID与workflowId=source-{UUID}、初版revision=1、来源类型/模式、摘要完整性及模型pin由领域/客户端附加校验；Schema不能表达全部跨字段相等性。

同tenant/subject/requestId内容相同返回原记录；内容改变409。配置过时503、权限不足403、本人记录不存在404、容量达限409。未知结果可按相同requestId查询，或原样显式重试；不要换UUID掩盖不确定提交。

SourceSetup是不可变创建回执。之后在画布修改来源或输出不会更新该回执，后续版本不自动重定向；创建内容相同的确认重试仍返回原来源回执及当前可读初版工作流。旧实体默认链包含映射、TRIM和模型校验；新来源先选择输出再显式保存。LOG/METRIC模板不默认TRIM。运行继续使用v2工作流契约的只读边界，不启用采集、不写存储。每份接入回执最多16KiB，复用PG事务/容量/租户锁，运行角色只需SELECT/INSERT，不授UPDATE/DELETE。

继续编排使用独立的 `source-setup-continuation` 只读响应，不改变旧查询与确认重试所指向的初版。服务端先检查SOURCE_SYNC与本人创建记录，再在租户事务内选择本人草稿或租户可读发布版本；不包含他人私有草稿。响应只给出一个固定版本，跳转后仍按id/revision/state读取并校验。UI列表和页签仅触发读取，不测试、保存、发布或启用。setupId与工作流id=`source-{setupId}`的相等性由领域和客户端补充校验。
