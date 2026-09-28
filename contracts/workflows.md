# 数据转换工作流 v2

权威结构为 schemas/v2/workflow-{definition,entry,receipt,result,page}.schema.json。独立于 v1 Host Pipeline；示例 examples/v2/workflow-definition.json 是显式 fixture，示例模型摘要是占位值，实际执行需目录返回的固定模型 pin。领域附加校验：单链拓扑、节点唯一、映射目标唯一、目标字段存在、模型摘要匹配；JSON Schema 本身不能表达全部跨字段约束。

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

SOURCE与OUTPUT不执行写入。MAP的config为来源字段→目标字段，只复制存在字段。TRIM去除字符串首尾空白；EMPTY_TO_NULL只把指定字段空白串改为null；DEFAULT仅补缺失字段，保留显式null；ENUM_MAP精确文本替换；SCALE乘固定十进制factor（非0、绝对值≤1,000,000、最多12位小数，结果≤JS安全数范围）；FILTER仅保留文本与equals完全相同的记录，缺失/null单独记filtered。过滤不代表删除或退役。VALIDATE按固定模型复用safe-scalars-v1规则，保留缺失、空值、类型、枚举和值域问题。

节点版本仅支持1。SOURCE、MAP固定前两位；VALIDATE、OUTPUT固定最后两位，中间只能放白名单清洗节点。边必须与nodes列表的相邻节点严格一致。每条记录遇到拒绝/过滤后停止，后续步骤为SKIPPED；每步值只是本次响应，不持久保存；节点状态及闭集错误码按下述trace保存。

## 安全、预算与回执

所有工作流接口检查source.sync与workflow:*范围，并检查模型目录entity.read/catalog:*。已有Zabbix批次再检查来源和每个实体范围。模型不能授权、变更权限或增加工具。每定义4到16节点、32映射字段、字符串配置值512字符、语义JSON16KiB；坐标0到4000且恰好覆盖每个节点。每样本32字段、文本2048字符、4KiB保守JSON字节预算，最多5条，结果≤1MiB。样本中身份/凭据保留字段被拒绝。

草稿按tenant+owner存储；PG租户事务锁串行化CAS及发布。布局独立于语义digest，语义修改会清除preview，布局修改只提升editVersion。发布只接受服务端当前receipt.id、同digest、至少1条accepted、0条rejected，createdAt≤now<createdAt+15分钟；filtered计数不等于拒绝，但全过滤不能发布。回执不是上游完整快照或生产验收证明。

receipt含id、digest、inputDigest、origin、accepted/rejected/filtered、createdAt。origin严格为MANUAL_SAMPLE、fixture或zabbix-jsonrpc。result还返回retainedCount、missingRaw、truncated及MANUAL_SAMPLE/SUCCEEDED/FAILED来源批次状态，保留上游失败和缺口。Java不隐去fixture标记。page仅显示最近20项，truncated明确；模型目录最多50个自定义发布条目加5个内置实体。草稿/发布/回执上限见ADR-055；暂无分页浏览全部、清理或自动保留策略。

400为请求/定义/样本不合法，403为权限不足，404为不可读或不存在，409为并发/版本/pin/预览冲突或容量上限，429为并发已满，503为来源/存储不可用。错误不返回样本正文、SQL、凭据或异常栈。

数据源中心可通过[source-setups](source-setups.md)契约在同一事务创建配置回执与初版草稿；URL只传工作流标识，不绕过读取授权，不自动执行。

## 可持久回查的逐条结果

权威结构新增schemas/v2/workflow-run-detail.schema.json。现有摘要列表保持不变；GET详情返回schemaVersion、run摘要和trace。trace记录来源/模型pin、批次UUID、startedAt/durationMillis、retainedCount/missingRaw/truncated/sourceStatus、dryRun=true/writesPerformed=false，以及每条index/status/steps。steps只有nodeId/type/status/issues(field/code)，没有values、配置或自由文本。三类计数必须与实际终态一致；同一运行各条节点序列相同且唯一，失败/过滤之后只能SKIPPED。状态和issue码均闭集。旧记录trace=null，不捏造历史。

非法输入、读取来源失败或事务未提交只返回原请求错误，不生成运行详情；本轮没有请求级失败审计日志。每主体200回执上限保持，详情仅当前主体/租户可见；允许按UUID回查最近20项以外已保存记录。见[ADR-057](../docs/adr/057-workflow-run-traces.md)。
