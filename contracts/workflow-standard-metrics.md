# 标准指标工作流契约

适用范围：工作流目标METRIC格式1.1。外层工作流仍为2.0；旧ENTITY、LOG 1.0及METRIC 1.0契约保持。来源可为MANUAL_SAMPLE或[固定ZABBIX_METRIC](workflow-metric-sources.md)，预览和已发布版本测试均dryRun=true、writesPerformed=false，不代表指标落库或持续采集。

唯一目标结构：kind=METRIC、schemaVersion=1.1、metricKey，以及mappingPin{id,revision,digest}。完整定义沿用metric-mapping-definition，摘要覆盖来源键、指标名、类型、单位、原始数值类型、转换、范围及维度；定义与完整pin必须一致。工作区metricMappings为同次可信身份过滤后的目录，旧响应可缺省该字段。不接受客户端规则、来源地址、权限或身份覆盖字段。

MAP输出只能包含timestamp、sourceKey、value；来源键必须逐字匹配登记定义。数值GAUGE支持DOUBLE或INTEGER，INTEGER原始数值不能含小数，维度必须全部固定。timestamp接受带时区时间并归一为UTC；value执行登记转换后检查范围。输入保持原来的5条、每条32个标量、4096字节预算，工作流16节点/32边预算不变。

标准结果的七个字段为timestamp、metricKey、value、unit、metricType、dimensions、mappingPin。value为最多64字符的非指数十进制字符串，避免无符号整数或高精度值经JSON number丢失精度；单位、类型、维度、标识及pin均由计划生成。结果上限4096字节；嵌套对象只在成功的VALIDATE/OUTPUT步骤出现。时间、数值、范围和元数据在服务器检查，前端另核对完整定义与精确十进制范围。静态Schema不替代跨字段关系或服务器授权。

source.sync及完整指标对象的metric.read都在服务器校验，包括草稿、历史版本、比较及回执查看。MAPPING_CHANGED表示目录缺失、摘要失配或当前格式不兼容，HTTP 409；身份不足403，封闭结构或不合法配置400，数值/来源键失败属于逐条预览拒绝，不可发布。执行前及回执保存前重新核对，失配不留下新回执。新发布核对映射，已确认发布的相同命令重放返回原版本。不存在自动升级、隐藏重试或Fixture回退。

标准目标持久化使用既有工作流JSON及元数据回执，没有新增表。历史回执保留原目标pin及完整指标名；不保存逐步value、原始sample或标准结果正文。版本比较包含metricKey、mappingId、mappingRevision、mappingDigest，差异不构成兼容证明。

Schema：[目标联合](schemas/v2/workflow-output.schema.json)、[定义](schemas/v2/workflow-definition.schema.json)、[标准结果](schemas/v2/workflow-standard-metric-record.schema.json)、[预览结果](schemas/v2/workflow-result.schema.json)、[工作区](schemas/v2/workflow-page.schema.json)。合成样例：[标准定义](examples/v2/workflow-standard-metric-definition.json)、[标准结果](examples/v2/workflow-standard-metric-record.json)。

映射定义的指标类型名称与既有遥测领域统一为GAUGE/SUM/HISTOGRAM；1.1执行子集仍只有GAUGE。SUM和动态维度仅可作为登记元数据被读取，不能因此在当前流程执行。

第115节：已发布固定指标的实际样本写入、UNKNOWN原结果验证与当前点查询见[指标输出](workflow-metric-output.md)。发布和只读测试本身仍不写指标点；样本入口显式写入且最多5点，不推进持续采集checkpoint，也不创建资产绑定。
