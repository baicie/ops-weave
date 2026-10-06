# 来源采样映射维护契约

公开Schema及显式合成样例在schemas/v2和examples/v2的metric-mapping-*文件中。所有对象封闭，schemaVersion为2.0。旧v1指标绑定响应保持原形状；迁移不把缺少完整pin的历史数据标为已固定。

| 方法与路径 | 返回或命令 |
|---|---|
| GET /api/v2/metric-bindings | items（最多20项）、truncated |
| GET /api/v2/metric-bindings/{source}/{item} | view |
| POST /api/v2/metric-bindings/{source}/{item}/mapping | requestId、expectedBindingVersion、mappingPin；返回receipt |
| GET /api/v2/metric-bindings/{source}/{item}/mapping/commands/{requestId} | 原receipt |

source是1至64字符的稳定标识，item是1至20位非零开头数字，requestId为规范小写UUID。拒绝查询参数、未知JSON字段、重复键、尾随JSON和超过65KiB的请求体。身份、权限、URL、secret、规则内容和执行授权不由命令提供。响应no-store。

binding只公开来源类型/实例、监控项、实体与主机标识、完整指标键、固定维度、来源单位、转换、mappingRevision、lifecycle、version和可空mappingPin。没有tenant/owner或秘密。mappingPin必须恰好包含id、revision和sha256摘要，revision与mappingRevision一致；id最多96字符，revision为1至1000000。binding.version为1至1000000000；命令expectedBindingVersion为1至999999999。

view包含binding、canConfigure及兼容的candidates（登记目录上限100）。每个候选包含完整mappingPin、connector、sourceKey、metricKey、displayName、metricType、unit、valueType、dimensionSchema、fixedDimensions、valueTransform、minimum和maximum。范围为十进制字符串或null，不能通过JSON浮点数舍入。客户端重新计算完整摘要，核对维度、范围及绑定兼容性；canConfigure仅供展示，服务器仍检查可信身份与对象范围。

读取至少需要metric.read、entity.read以及source.sync或source.configure，并满足三类对象范围。维护另外同时需要该来源的source.sync和source.configure。列表在SQL范围过滤后有界扫描201份授权候选，输出20份，扫描或展示超出预算时truncated=true；不能返回范围外记录数量。更多单项可通过已知来源/监控项精确读取，未实现列表翻页或客户端任意扫描。

首次固定仅允许当前登记定义与未固定绑定的版本、转换、固定维度及标准指标语义一致。已固定绑定只接受同一pin或同一映射id的更高revision；标准指标、单位、类型和维度定义必须兼容。同id/同revision改摘要、降级和跨id替换拒绝。原样选择不改变绑定版本；实际改变pin则version加一，原转换与固定维度由服务器登记定义导出，不采用请求里的规则。

receipt包含requestId、expectedBindingVersion、commandDigest、可空previousPin、当时的完整binding及createdAt。它是不可变命令结果，不是当前状态投影或全部历史点的谱系证明。相同tenant/owner/UUID且相同命令精确回读原结果；目录后续变更不阻止合法原结果回读。不同命令占用同UUID冲突，其他主体无法读取；当前对象授权仍须成立。每主体最多200份，达到容量返回显式不可用，不自动删除历史。

摘要使用WorkflowDefinition.hash的UTF-8字节长度前缀序列。映射顺序为metric-mapping-v1、id、connector、sourceKey、metricKey、displayName、metricType、unit、valueType、valueTransform、revision、minimum或空串、maximum或空串、维度定义数量及依次名称、固定维度数量及按键排序的键值对。命令顺序为metric-mapping-maintenance-v1、source、item、requestId、expectedBindingVersion、pin.id、pin.revision、pin.digest。保持旧来源指标发现摘要相同；摘要是完整声明语义，不是程序二进制哈希。

400表示命令或路径不合约，401/403表示身份或范围拒绝，404表示无可读取对象，409表示版本/命令/兼容冲突，503表示容量或存储不可用。网络失败或503不证明写入未发生；前端保留原命令，只查询原GET。查询结果必须匹配UUID、原命令摘要、前后pin、绑定身份、版本增量及选定定义的完整规则；不一致继续锁定。409后重新读取为显式操作，不自动重发POST。

维护事务不访问来源、不持久指标值、不修改工作流、不补采历史、不增加后台权限。真实历史读取在网络前检查完整pin，在网络后复核绑定及指标定义，变化则拒绝整页与游标。未固定或目录失配明确失败，不能静默回退当前定义或Mock。工作流标准指标映射、固定批次执行和生产后台身份不属于这个契约。
