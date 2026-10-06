# 来源指标分页契约

首次显式POST `/api/v2/data-sources/{sourceId}/metric-discoveries`，JSON仅四项：requestId、configurationRevision、connectionDigest和previousRequestId。previousRequestId首次为null；翻页填上次成功回执的requestId。接口拒绝查询参数、重复JSON键、额外身份、URL、secret、cursor、offset或itemids。网络工作前保存PENDING；配置或上一页冲突不会读取来源。同键请求只返回原记录，不重发来源请求。

结果使用既有source-inspection-read包裹，kind为DISCOVER_METRIC_PAGE。公开inspection新增previousRequestId及metricPage；旧种类这两项为null。PENDING/UNKNOWN不携带metricPage，UNKNOWN仍保留原父回执标识。原回执通过GET `/api/v2/data-sources/{sourceId}/inspections/{requestId}`读取，最近列表最多20份。可信身份、租户、主体和source.sync对象范围由服务器构造及检查，不来自命令。

metricPage包含公开manifest引用（snapshotId、asOf、expiresAt、total、fingerprint）、offset、items、statusCode、scanConsistency、fingerprint、complete、nextOffset、limit。limit固定20，offset为0至980的20倍数，total为0至1000。items沿用SourceMetricDiscovery.Item，保留完整来源键、单位、类型、精确现有映射id/revision/digest及未映射/类型不匹配状态，不含值或历史。READ_VERIFIED必须精确覆盖私有清单对应切片；仅终页complete=true且nextOffset=null。成功空清单为total=0、offset=0、items=[]、complete=true。

MEMBERSHIP_CHANGED、CAPACITY、UNREACHABLE必须items=[]、complete=false、nextOffset=null和UNVERIFIED一致性；未捕获清单时manifest=null，不宣称“零个指标”。配置当前不代表完整扫描；有效页可以CURRENT而complete=false。根manifest到期、保存配置变化、来源归档或固定凭据/地址不可用都会禁用继续翻页，历史仍可按原身份读取。根有效期固定首次asOf+15分钟；单请求availableAt和expiresAt不改变此期限。

完整ID列表只保存在source-inspection-storage的metricMembership中，公共Schema拒绝这个字段。私有编码不得用于HTTP响应。存储上限仍256KiB，旧没有metricDiscovery/metricPage/previousRequestId/metricMembership的Host记录兼容。父页不能跳跃、跨来源、跨租户或跨主体引用，也不能引用终页、失败、PENDING或过期页。完成时再次核对固定配置及凭据，有效性无法确认则持久UNKNOWN，不重复POST。

哈希采用WorkflowDefinition.hash的UTF-8字节长度前缀拼接。成员哈希顺序为`source-metric-membership-v1`、数量、依次排序的ID。页哈希为`source-metric-page-v1`、`absent`或`present`加公开manifest的snapshotId/asOf/expiresAt/total/fingerprint、offset、statusCode、scanConsistency、既有items元数据哈希。命令哈希为`source-metric-page-command-v1`、sourceId、requestId、配置版本、连接摘要、父requestId或`initial`。语义排序、精确切片、跨页和时序由领域层及客户端校验，Schema也限制结构、数量和结果互斥。

Schema及显式合成样例位于contracts/schemas/v2与contracts/examples/v2：source-metric-page-command、source-metric-manifest-reference、source-metric-page、source-metric-page-read、source-inspection-storage。现有`/discover-metrics`首分页接口仍可读取历史和显式调用，新界面统一走分页接口。发现观察不创建标准指标、修改映射、启用采集、持久指标点或替代工作流执行pin。

网络响应尚未确认时前端保留原命令、锁定新操作并查询原回执。若服务器已经持久UNKNOWN终态，则该原请求不会重跑、不会推进页位置；确认终态后可以显式发起新发现，不能把UNKNOWN作为下一页的父回执。
