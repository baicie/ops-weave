# 实例连接配置 · v2

`source-connection-*.schema.json`是公共连接配置契约唯一源。请求只包含登记地址pin、固定凭据版本pin和1至32个主机组ID，不接受URL、秘密、请求头、代理、租户、用户、权限、工作流或调度。主机组ID必须是唯一正十进制字符串；服务端按数值顺序规范化。实际读取复用[受控地址](source-endpoints.md)和[版本化凭据](source-credentials.md)边界。

| 方法 | 路径 | 行为 |
|---|---|---|
| POST | `/api/v2/data-sources/connections` | requestId同时是新实例ID；名称、说明、endpointPin与credentialPin创建配置v1/edit1 |
| PATCH | `/api/v2/data-sources/{id}/connection` | 另含expectedEditVersion；ACTIVE Host实例CAS更新，来源和创建回执不可变 |
| GET | `/api/v2/data-sources/{id}/connection` | 当前实例、完整非秘密快照、LEGACY/AVAILABLE/UNAVAILABLE/ARCHIVED及canConfigure |
| GET | `/api/v2/data-sources/{id}/connection/history` | 至多100个不可变完整快照；旧来源在首次显式绑定前没有完整快照 |
| GET | `/api/v2/data-sources/{id}/connection/commands/{requestId}` | 原连接命令、原实例版本和原完整快照；不返回最新实例代替原结果 |

响应schemaVersion=2.0、storage=memory或postgres。新完整快照固定sourceId、revision、connectorVersion=`host-jsonrpc-v2`、登记地址元数据/摘要、credentialId/revision/versionId、hostGroupIds、connectionDigest及createdAt。语义摘要以UTF-8字节长度前缀哈希：`source-connection-v3, sourceId, connectorVersion, endpointId, endpointDigest, credentialId, credentialRevision, credentialVersionId, groupCount, ...hostGroupIds`。修改实例名称/说明保持配置版本；地址、凭据pin或主机组范围改变才追加配置版本。新命令摘要使用`source-connection-write-v3`并在凭据pin后加入`groupCount, ...hostGroupIds`。ID按位数和字典序规范化后参与摘要，因此等价输入保持幂等，不同范围必然得到不同固定版本。

历史`host-jsonrpc-v1`快照仍按原`source-connection-v2`摘要读取，且不带`hostGroupIds`；这些快照仅供历史查看，不能执行新连接检查、发现或工作流读取。编辑时必须显式保存非空范围，生成新配置修订。旧原回执继续按`source-connection-write-v2`校验。主机、指标发现以及工作流的Host/METRIC/LOG读取都带同一固定`groupids`过滤；Host清单的count、manifest和每页请求保持同范围，分页摘要绑定范围。范围外对象不会进入成功快照。发现回执仍固定`scope=FIRST_HOST_PAGE`，语义为当前连接已选主机组范围内读取的第一页。

可信Principal提供tenant/subject。现有workflow根SOURCE_SYNC门禁和source对象范围继续执行；完整元数据还检查endpoint/credential范围，写入需三个对象的SOURCE_SYNC和SOURCE_CONFIGURE。操作员scope文件支持workflow/source/source-endpoint/credential，默认身份和权限不增加。重复原命令返回原回执，原请求内容变化409；旧地址失效或秘密撤销不妨碍授权读取已保存回执。原回执摘要会从原实例与完整快照复核，不能把实例元数据维护回执当作连接命令。

新实例物理ID为`connection-{UUID}`。旧Host首次绑定保留原物理ID，追加通用配置与完整快照；旧MANUAL来源不能绑定。V038增加同一PostgreSQL的integration.source_connection_configuration，引用通用配置四字段主键，body最多16KiB，只追加。实例、通用历史、完整历史与回执同事务提交；连续版本、摘要、时间和数据库身份列不一致拒绝读取。上限沿用200份创建记录、100配置版本、1000编辑版本、200主体维护回执，不隐藏删除或重试。

公开TEST/DISCOVER按被认领实例的完整快照读取；事务内检查目录pin、固定凭据可用性与密钥版本，网络IO在事务外。地址/秘密在IO前后复核，结束后再次复核实例版本。完整固定凭据的成功结果才可能CURRENT；旧env来源仍UNVERIFIED。改版、撤销和过期分别保留STALE/EXPIRED，读中配置变化成为UNKNOWN；查询或重复原请求不重发网络。AVAILABLE只表示当前登记与密钥元数据可用，不表示测试成功、采集已启用或工作流已绑定。

本阶段新连接用于实例维护及显式测试/首分页字段发现。工作流完整来源/算子/模型/映射pin及可信后台执行仍待后续实现，新实例不提供未接通的工作流跳转。发布版本和旧任务不自动迁移；发现仍限最多5条Host/5个原始字段，不等于完整模型或指标发现。
