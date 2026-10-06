# 来源指标发现契约

唯一Schema源：`schemas/v2/source-metric-discovery*.schema.json`与扩展后的`source-inspection.schema.json`。样例明确使用合成Fixture。

`POST /api/v2/data-sources/{id}/discover-metrics`沿用三字段检查命令，kind由服务器固定为DISCOVER_METRICS；原请求查询和最近历史复用inspections接口。拒绝query/未知字段、重复JSON键、身份/地址/分页注入。固定配置和凭据不匹配拒绝，不降级为环境凭据或Fixture。

metricDiscovery包括items、complete、scanConsistency、statusCode、fingerprint、scope、limit。scope恒为FIRST_ITEM_PAGE，limit恒20；最多20项且itemId严格数值递增，不允许重复。原ID为1到20位非零起始十进制字符串，避免浏览器Number精度丢失。

每项保留itemId/hostId/sourceKey/name/sourceUnit/sourceValueType/mappingStatus/mapping。sourceKey≤2048字符、name≤512、sourceUnit≤64且可空；文本禁止控制字符。类型为FLOAT/UNSIGNED/CHARACTER/LOG/TEXT/BINARY/UNKNOWN。未配置映射时mapping=null且状态NO_MAPPING；精确匹配已有配置时带完整映射引用，按现有数值/字符串兼容规则区分MAPPED和TYPE_MISMATCH。映射观察不等于绑定已生效或数据已入库。

映射包含id/revision/digest/metricKey/unit/valueType/valueTransform；标准指标键完整保留且≤128字符，revision为正整数，digest为sha256。字段闭合，不带脚本或任意URL。映射摘要域metric-mapping-v1，依次为id/connector/itemKeyExact/metricKey/displayName/metricType/unit/valueType/valueTransform/revision/minimum/maximum、维度数量及原顺序维度、固定维度数量及按键排序的键值对。缺失边界为空串。

发现指纹域source-metric-metadata-v1，按项顺序追加itemId/hostId/sourceKey/name/sourceUnit/sourceValueType/mappingStatus；无映射追加absent，有映射追加present/id/revision/digest/metricKey/unit/valueType/valueTransform。所有摘要使用UTF-8字节长度前缀连接，与服务器及浏览器一致。不把外部值插入执行代码。

两次首分页一致为FIRST_PAGE_MATCH；显式Fixture为LABELED_FIXTURE；发生变化为UNVERIFIED。complete要求已核对且状态READ_VERIFIED；不完整为INCOMPLETE；UNREACHABLE必须无items、complete=false、scanConsistency=UNVERIFIED。空完整清单与失败保持区别。哨兵不返回，分页未覆盖部分不计总量。重复页一致不是事务快照，不能触发资源删除或推进checkpoint。

新完成记录只含metricDiscovery，check/discovery为null；旧种类metricDiscovery只能为空或缺省。PENDING/UNKNOWN不含任何结果。服务器返回no-store，前端核对来源、原请求、kind、配置版本、摘要、时间、类型、顺序与指纹后才解除命令锁定；失败或迟到响应不自动重跑。

实现决策见ADR-070，实际验证见验证报告§108。

第109节补充：本接口继续保留首分页语义和旧记录；新界面改用[受控清单分页](source-metric-pages.md)。不得把旧FIRST_ITEM_PAGE记录直接作为新清单的父页或提升为全量扫描证明。
