# 当前资产关系查询 v1

GET /api/v1/entities/{entityId}/topology；无请求体、无查询参数，浏览器沿用平台会话。需要 entity.read，tenant和对象范围只能来自可信认证边界。无权限或不存在的中心404，存储不可用503。响应禁止缓存。

Schema：[entity-topology.schema.json](schemas/v1/entity-topology.schema.json)，示例：[Fixture](examples/entity-topology.json)。

coverage 固定 stored-current-one-hop，服务器asOf时点有效的已保存直接关系。固定limit=50，返回最多51个节点/50条关系；truncated=true时图不完整。两个端点必须都在授权范围；范围和时间过滤在LIMIT前执行。无关系时只含中心节点。节点ID唯一，关系ID唯一，所有端点必须存在、至少一个为中心，不能包含无关节点。validFrom<=asOf，validTo为空或>asOf。

节点为当前资产名称、类型、生命周期及来源标记，不提供历史asOf查询。dataMode为fixture/zabbix-jsonrpc/import/unknown；unknown不等于真实来源，fixture不可改标签。关系来源原文source_ref不返回。UI按文本显示名称和类型，不自动打开URL或下载资源。图布局不持久化，不发写请求；本接口不提供关系实例录入或完整拓扑遍历。
