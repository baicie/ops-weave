# 数据源中心与接入配置 v1

权威JSON结构：schemas/v1/source-center-page、source-setup、source-setup-command、source-setup-confirmed.schema.json。示例examples/source-center-page.json明确为Fixture；摘要为契约占位值，不能用于真实确认。

| API | 含义 |
|---|---|
| GET /api/v1/integrations/sources | 授权可用类型、已发布ENTITY模型、本人最近20份接入配置及截断标记 |
| POST /api/v1/integrations/sources/confirm | requestId/name/description/source/connectionDigest/target；原子保存接入配置和v2第一版草稿 |
| GET /api/v1/integrations/sources/{id} | 本人配置和第一版草稿，已发布则返回对应发布版本 |

所有API要求source.sync作用于workflow:*，entity.read作用于catalog:*；Zabbix描述符和确认另要求当前source实例范围。身份不可通过请求覆盖，不接受查询参数。POST拒绝未知/重复字段、尾随JSON及超过64KiB正文。输出no-store，日志不含原始请求、凭据或Authorization。

AVAILABLE仅表示当前身份有可用的配置类型，不表示网络已连通。ZABBIX_HOST的描述符来自平台配置；mode仅fixture或zabbix-jsonrpc，未配置/未授权为UNAVAILABLE且connection=null。MANUAL_SAMPLE固定manual，无外部连接。CMDB_SNAPSHOT固定LEGACY_IMPORT，不能通过此接口确认。JSONRPC地址不能携带userinfo/query/fragment，credentialRef只能是env引用名，绝非密钥值。

SourceSetup包含创建时dataMode，严格保留fixture标记。connectionDigest固定到当前来源配置，但不是密钥值摘要。目标固定到已发布ENTITY模型和digest，空字段模型不能生成草稿。UUID与workflowId=source-{UUID}、初版revision=1、来源类型/模式、摘要完整性及模型pin由领域/客户端附加校验；Schema不能表达全部跨字段相等性。

同tenant/subject/requestId内容相同返回原记录；内容改变409。配置过时503、权限不足403、本人记录不存在404、容量达限409。未知结果可按相同requestId查询，或原样显式重试；不要换UUID掩盖不确定提交。

SourceSetup是不可变创建回执。之后在画布修改来源或目标不会更新该回执，后续版本也不自动重定向。默认链只包含映射、TRIM和模型校验，运行继续使用v2工作流契约的只读边界；不启用新采集、不写实体。每份接入回执最多16KiB，复用PG事务/容量/租户锁，运行角色只需SELECT/INSERT，不授UPDATE/DELETE。
