# 工作流固定接入版本

第112节扩展：[固定指标来源](workflow-metric-sources.md)在configuration之外绑定服务器发现的完整metric pin，支持单个原始数值系列的标准映射预览。下文固定Host即时读取的实体范围保持；两种来源均不因此获得后台任务或持久业务输出。

前端首版编排入口（§106）：地址采用封闭的`sourceInstance` UUID、`configurationRevision` 1–100与`connectionDigest`完整SHA-256三元组；禁止附加身份、重复/混合参数或隐式latest。跳转只读取授权实例和固定配置历史，找到精确revision/digest后准备未保存的首版草稿，source.configuration使用既有`sourceId/revision/digest`契约，同时固定已授权主机模型与算子摘要。历史链接不因当前配置轮换改写。已有工作流需从版本列表显式创建下一版，保存以服务器CAS为准；缺失/归档/失败不回退到旧来源、其他版本或Fixture。浏览器参数是选择，不是身份或执行授权；服务端继续独立核对固定版本、凭据撤销与对象范围。

权威结构在v2工作流定义、运行明细及`workflow-source-configuration-pin.schema.json`。旧API路径保持`/api/v1/integrations/workflows`，schemaVersion仍为2.0。旧来源的kind/instanceId及语义摘要保持兼容；新配置只在工作流来源包含`configuration`时启用。

```json
{
  "kind": "ZABBIX_HOST",
  "instanceId": "connection-22222222-2222-4222-8222-222222222222",
  "configuration": {
    "sourceId": "22222222-2222-4222-8222-222222222222",
    "revision": 1,
    "digest": "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
  }
}
```

这是结构示例，不能当作可用连接。服务器校验可信租户、来源创建者隔离、source/endpoint/credential对象范围，查询完整不可变配置并核对真实摘要及物理来源。地址与秘密不能进入工作流请求，MANUAL_SAMPLE不允许配置pin；不存在latest别名。配置版本1..100，规范UUID和SHA-256小写，封闭未知字段；定义仍有16KiB上限。

POST drafts保持expectedEditVersion的CAS。固定pin参与语义摘要，切换绑定清除原预览；仅改变布局不清除。连接轮换后旧工作流不换pin。归档、撤销固定秘密版本、登记地址摘要改变或密钥不可用，阻止新的保存、预览及发布；历史读取继续核对元数据和范围。

POST preview/run始终要求dryRun=true。手工来源提交samples；旧Host来源提交syncRunId；固定Host来源二者都不提交，服务端经受控连接显式读取最多5条记录，再使用name/ip/lifecycle/entity_id标准化输入执行现有确定性DAG。固定来源只接受zabbix-jsonrpc数据，禁止失败回退Fixture。读取有界、无代理/跳转、固定秘密前后复核，并逐实体检查ENTITY_READ；网络IO不在工作流PG事务中。接口失败不会保存成功回执。

固定来源trace包含完整configuration pin，syncRunId=null；这是即时只读样本，不是持久采集批次。inputDigest覆盖固定配置与实际标准化输入。retainedCount表示读入的样本数量，truncated表示仅读取部分来源；不宣称整份数据已保留或采集完成。运行元数据保存字段问题、计数及摘要，不保存输入输出值。发布要求匹配的有效预览、至少一条通过、零拒绝、来源读取成功和无缺失；还需复核依赖可用性。

现有LOCAL_DEV_ENTITY实际运行接口遇到固定来源返回SOURCE_UNAVAILABLE，不将新的只读预览映射成旧批次。该路径的持续采集、可信后台委托、固定批次谱系与真实输出仍待实现。配置保存或发布均不启动服务任务。
