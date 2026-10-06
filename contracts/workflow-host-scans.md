# 固定接入的资产扫描契约

固定配置的 ZABBIX_HOST→ENTITY 已发布流程可通过既有 runtime/start 启动有限扫描。SOURCE、配置、凭据、模型和算子pin保持固定，标识字段必须为entity_id。旧手工单次执行与旧采集批次路径保持，固定接入不调用runtime/execute。

`POST /api/v1/integrations/workflows/runtime/resume` 使用与start/stop相同的六字段命令Schema：requestId、id、revision、digest、settings、expectedGeneration。操作由路径决定，不接受tenant、主体、权限、地址或后台授权。RESUME只接受同一工作流版本、摘要及设置的FAILED/STOPPED未完成扫描，使用原游标及原待处理批次。受理代数+1；原请求重放返回原RUNNING回执，不续期、不再执行控制。同键不同内容409。

`GET /api/v1/integrations/workflows/runtime/host-scans/{id}` 返回schemaVersion、checkpoint及最近最多20份批次元数据，禁止查询参数。checkpoint计数属于当前扫描；最近批次可含旧扫描、旧版本，分别保留scanId/revision/digest。所有者由可信身份构造，来源与实体范围在服务端检查。没有扫描返回404，权限收紧返回403，失败不回退Fixture。

公开checkpoint不含nextCursor。公开batch不含records、beforeCursor、nextCursor；包含recordCount、原时间、固定设置、状态、已确认entityIds和稳定错误码。业务状态为READY、IN_FLIGHT、UNKNOWN、FAILED、CONFIRMED。UNKNOWN/FAILED保留未推进的checkpoint，显式恢复才重新受理原批次；已确认ID不能在状态转换中消失。CONFIRMED不可改写。

每页最多5条，私有游标固定清单digest/总数/位置，清单上限1000；分页与扫描完成分开。批次身份和原输入先落已有PG，再调用低频资产输出；时间、语义版本及数据不能在恢复时替换。只有整批输出确认才推进来源游标，扫描完成后Task为STOPPED且checkpoint.complete=true。空健康末页允许零条，但不会执行资产删除。

公开和私有Schema分别为workflow-host-checkpoint、workflow-host-batch及其-storage版本，响应Schema为workflow-host-scan。控制回执operation增加RESUME，其受理Task必须为RUNNING。现有Task/Execution字段、START/STOP摘要及旧存储保持兼容。Web与平台需同步部署才能开放固定接入入口。

当前不开放任意HTTP、SQL、脚本、动态执行器、自动输出重试或持续遥测调度；固定指标样本输出仍使用独立指标输出契约。[设计依据](../docs/adr/078-durable-fixed-host-workflow-batches.md)。
