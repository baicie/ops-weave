# 数据转换工作流 v2

第105节算子补充：新发布必须固定所有Node.operatorDigest；旧定义/摘要/历史执行兼容。目录、参数Schema、摘要和错误码见[算子契约](workflow-operators.md)，生成示例仍为Fixture，模型摘要须使用实际授权目录返回值。

第104节固定来源补充：工作流来源可选configuration pin；旧定义/摘要兼容，新Host连接支持即时只读预览与发布，不接收手工或旧批次。完整边界见[固定来源契约](workflow-source-bindings.md)和ADR-067，不能当作持续任务已启动。

权威结构为 schemas/v2/workflow-{definition,entry,receipt,result,page,output,log-record,metric-record,runtime,runtime-task,runtime-execution,runtime-control-request,runtime-execute-request}.schema.json。独立于 v1 Host Pipeline；示例 examples/v2/workflow-definition.json 是显式 fixture，示例模型摘要是占位值，实际执行需目录返回的固定模型 pin。领域附加校验：DAG拓扑、节点唯一、映射目标唯一、目标字段存在、模型摘要匹配；JSON Schema 本身不能表达全部跨字段约束。

## API

统一前缀 /api/v1/integrations/workflows，可信平台会话、Cache-Control: no-store。所有对象均拒绝未知字段，POST拒绝重复键、尾随JSON、超过64KiB请求，不接受tenant/user/权限覆盖。

| 路径 | 方法与命令 | 返回 |
|---|---|---|
| 根路径 | GET | workflow-page：本人的活动草稿/运行回执、租户已发布版本、可用实体模型、来源配置模式 |
| /drafts | POST {definition,layout,expectedEditVersion} | workflow-entry；首次expectedEditVersion=0，保存后+1 |
| /runs/{id} | GET，UUID；拒绝查询参数 | workflow-run-detail：本人运行摘要与可空trace，读取不重新执行 |
| /drafts/{id}/{revision} | GET | 本人草稿；无自动执行 |
| /versions/{id}/{revision} | GET | 租户已发布固定版本 |
| /preview | POST {id,revision,editVersion,digest,dryRun:true,samples} 或将samples替换为syncRunId | workflow-result，当前已保存草稿 |
| /publish | POST {id,revision,editVersion,digest,previewId} | 不可变workflow-entry；相同内容重试返回原版本 |
| /run | 同preview，但editVersion=0 | 已发布版本的只读测试结果 |

手工来源kind=MANUAL_SAMPLE且instanceId=manual；samples为1到5个平面标量对象。Zabbix来源kind=ZABBIX_HOST，instanceId必须等于当前配置实例；syncRunId为该来源已有Host批次UUID，禁止同时提供手工样本。输入是经过v1映射和对象授权的name、ip、lifecycle、entity_id，不是任意厂商JSON。未配置/来源错配、批次无Raw、越权、损坏或超限记录显式失败，没有Mock回退。

## 执行和清洗

SOURCE与OUTPUT不执行写入。MAP的config为来源字段→目标字段，只复制存在字段。TRIM去除字符串首尾空白；EMPTY_TO_NULL只把指定字段空白串改为null；DEFAULT仅补缺失字段，保留显式null；ENUM_MAP精确文本替换；SCALE乘固定十进制factor（非0、绝对值≤1,000,000、最多12位小数，结果≤JS安全数范围）；FILTER仅保留文本与equals完全相同的记录，缺失/null单独记filtered。过滤不代表删除或退役。VALIDATE按输出类型校验：ENTITY复用固定模型safe-scalars-v1；LOG/METRIC使用独立固定格式，保留缺失、空值、类型、枚举和值域问题。

节点版本仅支持1。SOURCE、MAP固定前两位并相连；VALIDATE、OUTPUT固定最后两位并相连，中间只能放白名单节点。最多16节点/32边，nodes按拓扑顺序，拒绝自环/重复边/环/未知端点和孤立节点。普通节点只有一个输入且可分流；MERGE空config显式接收多个分支，同字段不同值返回MERGE_CONFLICT，过滤分支忽略、任一分支错误拒绝整条记录。其他独立分支仍显示实际执行状态，不沿用单链全局停止。每步值只是本次响应，不持久保存；节点状态及闭集错误码按下述trace保存。

## 安全、预算与回执

所有工作流接口检查source.sync与workflow:*范围。ENTITY还检查模型目录entity.read/catalog:*及发布模型pin；LOG/METRIC不读取实体目录。无目录权限时根路径models为空，不把未授权模型返回客户端。已有Zabbix批次再检查来源和每个实体范围。模型不能授权、变更权限或增加工具。每定义4到16节点、32映射字段、字符串配置值512字符、语义JSON16KiB；坐标0到4000且恰好覆盖每个节点。每样本32字段、文本2048字符、4KiB保守JSON字节预算，最多5条，结果≤1MiB。样本中身份/凭据保留字段被拒绝。

草稿按tenant+owner存储；PG租户事务锁串行化CAS及发布。布局独立于语义digest，语义修改会清除preview，布局修改只提升editVersion。发布只接受服务端当前receipt.id、同digest、至少1条accepted、0条rejected，createdAt≤now<createdAt+15分钟；filtered计数不等于拒绝，但全过滤不能发布。回执不是上游完整快照或生产验收证明。

receipt含id、digest、inputDigest、origin、accepted/rejected/filtered、createdAt。origin严格为MANUAL_SAMPLE、fixture或zabbix-jsonrpc。result还返回retainedCount、missingRaw、truncated及MANUAL_SAMPLE/SUCCEEDED/FAILED来源批次状态，保留上游失败和缺口。Java不隐去fixture标记。page仅显示最近20项，truncated明确；模型目录最多50个自定义发布条目加5个内置实体。草稿/发布/回执上限见ADR-055；暂无分页浏览全部、清理或自动保留策略。

400为请求/定义/样本不合法，403为权限不足，404为不可读或不存在，409为并发/版本/pin/预览冲突或容量上限，429为并发已满，503为来源/存储不可用。错误不返回样本正文、SQL、凭据或异常栈。

数据源中心可通过[source-setups](source-setups.md)先确认来源回执，再在工作流选择输出并显式保存草稿；旧实体target继续同事务初始化。URL只传sourceSetup UUID或工作流选择标识，不绕过读取授权，不自动执行。

## 可持久回查的逐条结果

权威结构新增schemas/v2/workflow-run-detail.schema.json。现有摘要列表保持不变；GET详情返回schemaVersion、run摘要和trace。trace记录来源/输出target（ENTITY为模型pin，LOG/METRIC为格式版本）、批次UUID、startedAt/durationMillis、retainedCount/missingRaw/truncated/sourceStatus、dryRun=true/writesPerformed=false，以及每条index/status/steps。steps只有nodeId/type/status/issues(field/code)，没有values、配置或自由文本。三类计数必须与实际终态一致；同一运行各条节点序列相同且唯一，DAG独立分支可以继续执行，受阻下游为SKIPPED。状态和issue码均闭集。旧记录trace=null，不捏造历史。

非法输入、读取来源失败或事务未提交只返回原请求错误，不生成运行详情；本轮没有请求级失败审计日志。每主体200回执上限保持，详情仅当前主体/租户可见；允许按UUID回查最近20项以外已保存记录。见[ADR-057](../docs/adr/057-workflow-run-traces.md)。

## 输出联合与规范化格式1.0

ENTITY保留原target={id,revision,digest}闭集形状及摘要算法；不能添加kind字段。LOG/METRIC使用target={kind:"LOG"|"METRIC",schemaVersion:"1.0"}，不得附带实体id/revision/digest。遥测格式版本进入定义digest。ZABBIX_HOST在定义与历史trace中都只允许ENTITY；MANUAL_SAMPLE支持三种格式。客户端和领域校验还检查历史来源模式、字段和计数一致性。

LOG字段为eventTime、body、可选severityText/serviceName/traceId/spanId；eventTime必须有时区并规范化UTC，body非空白且最多2048字符，其他文本最多128字符，traceId/spanId分别32/16位小写十六进制。正文不隐式trim，默认模板仅SOURCE/MAP/VALIDATE/OUTPUT。

METRIC字段为timestamp/metricKey/value/metricType、可选unit。timestamp带时区并规范化UTC；metricKey为有界ASCII标识；metricType仅GAUGE。输入value可以是可转十进制的文本或数字，规范化输出为JSON数字，最多12位小数，绝对值≤9007199254740991。不接收SUM或虚构累计/速率语义；SCALE需要显式系数。

字段映射和清洗config.field只能指向所选格式字段。输入仍是1–5条有界平面样本，不新增任意JSON/Grok/脚本解析。规范化记录Schema描述预览输出，不代表存储入口。preview/run继续dry-run/writesPerformed=false，持久trace不含values或日志正文。

## 本地实体运行

仅loopback dev认证 + PostgreSQL提供，其他模式返回503/SOURCE_UNAVAILABLE。要求source.sync、目录entity.read及每个目标实体的entity.manage；停止已有任务只要求本人source.sync，权限撤销后仍可停止。身份不接受请求覆盖。

| 路径 | 方法与命令 | 返回 |
|---|---|---|
| /runtime | GET，无查询参数 | runtime：本人任务及最近20条执行元数据 |
| /runtime/executions/{id} | GET，UUID；拒绝查询参数 | 单条本人/租户范围内的已持久执行元数据；读取不重新执行、不重试，隐藏其他主体或租户的记录 |
| /runtime/execute | POST {id,revision,digest,settings,previewId,samples} 或syncRunId | runtime-execution；只允许已发布ENTITY |
| /runtime/start、/runtime/stop | POST {id,revision,digest,settings,expectedGeneration} | runtime-task；CAS，start仅ZABBIX_HOST |

settings闭集为identityField/nameField，不接受来源URL、脚本、权限或Token。previewId必须是同版本同输入的有效RUN回执（非草稿PREVIEW）；它同时是写入幂等键。同键配置/输入改变返回409，原样确认返回已记录的SUCCEEDED或FAILED。来源批次须SUCCEEDED、完整、无截断/缺失且最多5条。全批校验后写入；已确认ID不代表全批原子性，FAILED可能有部分成功，禁止隐式重试。执行回执不含输入、输出正文或异常自由文本。

任务state为RUNNING/STOPPED/FAILED，generation递增控制，固定版本和设置。cursor/cursorId按完成时间与UUID，处理新完成的已有批次，采集单独启动。失败显式终止，重新启动从当前时刻开始；不重放旧数据、不退役缺失实体。服务重启恢复RUNNING；调度器使用既有可信开发身份，不存储Token。上限：共享并发2、20任务/用户、200执行回执/用户、50待处理批次。迁移V032–V034及权限见ADR-061，日志/指标写入和生产后台委托仍缺。
