# 工作流固定指标来源契约

外层工作流仍为2.0。source使用共享封闭联合：MANUAL_SAMPLE无配置/指标pin，ZABBIX_HOST可使用既有配置pin但无指标pin，ZABBIX_METRIC必须同时固定配置和指标pin，并配合METRIC 1.1目标。历史结构兼容；语义关系由服务器验证，Schema不能替代授权。

metric包含inspectionId、itemId、hostId、sourceKey、sourceUnit、sourceValueType、digest七项。数值ID为1–20位正整数；完整键上限2048字符、单位64字符且允许空单位，均禁止控制字符；类型只接受FLOAT/UNSIGNED。摘要对workflow-metric-source-v1及前六项按UTF-8字节长度加冒号、顺序连接后SHA-256计算。完整pin进入定义摘要和SOURCE版本比较。

`GET /api/v2/data-sources/{sourceId}/connection/{revision}/workflow-metrics`只接受规范UUID与1..100版本，不接受查询参数、地址、秘密或身份字段。响应schemaVersion/source/items/truncated；source为固定Host配置，items至多100，每项为source/item/asOf/expiresAt。item沿用来源发现契约。服务器从最近20份本人租户的真实已完成回执中核对固定配置、分页父根、元数据和完整映射，过滤过期和无范围的条目，按itemId去重。达到回执扫描或条目上限保留truncated；不是全量来源目录。该GET不读上游、不创建绑定或指标点。

保存和执行时检查出处与目标完整映射id/revision/digest及metricKey一致；不采用latest。可信来源、地址、凭据、指标和主机对象范围由服务端构造。旧发现可证明已保存定义的出处；可用性由当前连接及实际预览读取核验，不能把历史出处视为当前采样数据。不同物理接入来源不共用绑定身份。

固定指标POST preview/run只携带id/revision/editVersion/digest/dryRun，不携带samples、syncRunId、cursor或查询条件。dryRun必须true。服务端最多三个固定JSON-RPC请求：item.get(limit2)、history.get(limit6)、item.get(limit2)。history限定当前时刻以前的最近600秒；0–5个标准转换输入，实际空结果拒绝预览，第6点仅检测截断，不执行转换。检查同一item、clock/ns降序且唯一、时间窗、有限数值及uint64范围；再将最多5点按时间升序送入计划。

原始输入只有timestamp/sourceKey/value，value保留原始十进制字符串。映射计划执行一次登记转换并产生标准七字段结果。raw读取失败SOURCE_UNAVAILABLE，元数据变化SOURCE_CHANGED(409)，非法/空样本INVALID_SAMPLE；不存在失败转Fixture、自动升级或隐藏重试。执行前、网络前后和回执保存前按各边界复核固定依赖；失败不产生成功回执。

trace的source保存配置与指标完整pin，origin=zabbix-jsonrpc、sourceStatus=SUCCEEDED、syncRunId=null。retainedCount是实际读取数量，截断时可为6，rows最多5；该数量不是历史系列总量或已持久点数。历史仅存状态、错误与谱系，不保存输入/输出值。发布复用既有预览门禁和不可变版本，不产生MetricBinding、时序写入或后台任务。

Schema：[来源](schemas/v2/workflow-source.schema.json)、[指标pin](schemas/v2/workflow-metric-source-pin.schema.json)、[选择页](schemas/v2/workflow-metric-source-page.schema.json)、[工作流](schemas/v2/workflow-definition.schema.json)、[回执详情](schemas/v2/workflow-run-detail.schema.json)。合成样例：[真实接口形状的定义](examples/v2/workflow-real-metric-definition.json)、[指标选择页](examples/v2/workflow-metric-source-page.json)。样例中的身份和指标项是明确Fixture，不能视为实际连接证据。

第115节：已发布固定指标的实际样本写入、UNKNOWN原结果验证与当前点查询见[指标输出](workflow-metric-output.md)。发布和只读测试本身仍不写指标点；样本入口显式写入且最多5点，不推进持续采集checkpoint，也不创建资产绑定。
