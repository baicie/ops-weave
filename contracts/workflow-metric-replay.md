# 固定版本指标历史重放协议

独立的v2闭合Schema和既有工作流API根；四启动单元及旧任务/输出/诊断协议保持。JSON Schema约束形状和预算，Java/Web另校验固定父版本、时间、摘要、数量守恒和原UUID。所有响应仍受可信身份、no-store及原请求追踪边界保护。

| 接口（根 `/api/v1/integrations/workflows/metric-replays`） | 语义 |
| --- | --- |
| POST `/plans` | 六字段command；持久受理后一次来源读取和转换，返回16字段Plan |
| GET `/plans/{uuid}` | 原选择回查，无来源/输出请求 |
| GET `/workflows/{id}/versions/{revision}/{digest}/plans` | 本人固定版本最近20个选择，SQL取21检测截断 |
| POST `/execute` | 四字段requestId/planId/inputDigest/batchDigest；一次输出尝试，返回九字段Receipt |
| GET `/commands/{uuid}` | 原执行回执查询，无来源/输出请求 |
| GET `/plans/{uuid}/execution` | `receipt`为原执行回执或null；刷新页面后查回原执行 |
| POST `/commands/{uuid}/verification` | 无正文；只核验原系列和原时间戳，禁止重发 |

字段定义与样例：[选择命令](schemas/v2/workflow-metric-replay-command.schema.json)、[执行命令](schemas/v2/workflow-metric-replay-execute.schema.json)、[Plan](schemas/v2/workflow-metric-replay-plan.schema.json)、[Receipt](schemas/v2/workflow-metric-replay-receipt.schema.json)、[列表](schemas/v2/workflow-metric-replay-page.schema.json)。样例均为明确Synthetic Fixture，不能冒充真实来源。

窗口`[from,till)`精确整秒且长度60秒，服务器受理时已结束至少10秒且起点不早于24小时。最多60输入/存储点，过滤和毫秒合并计数守恒；空完整选择允许0点确认而不调用输出。READY有效期为受理后600秒；输入或输出摘要不符、失效或更换UUID绕过已受理选择返回409。固定来源配置、发现项、映射和输出目标在执行前复查。

Plan固定`REBUILD_METRIC_PROJECTION`、`ISOLATED_METRIC_SERIES`、notifications=false/actions=false；请求不提供这些开关。Proof只保存摘要/计数/标签/时间戳，不保存点值、客户原文、凭据或地址。输出标签由服务器构造并绑定tenant/本人/来源/完整指标/版本/映射及replay_id；普通持续系列和checkpoint不改。

选择状态PREPARING→READY/FAILED；执行PENDING→CONFIRMED/FAILED/UNKNOWN，UNKNOWN只可保持或变为CONFIRMED。FAILED表示有明确未输出证据，UNKNOWN不是空输出。相同UUID和相同内容返回原对象；相同UUID变更内容409。并发重复命令可能读取到原PENDING，不会产生第二次来源/输出尝试。核验当前PENDING受理不足180秒时返回原对象、不读取输出；其后缺失/失败仍只保留UNKNOWN。

可信`workflow.replay`独立权限及对象范围必须与原工作流/来源/指标/实体门禁同时满足；默认grant不新增。禁止query参数、额外字段、重复JSON键、尾随正文和非规范UUID。400非法请求、401未认证、403范围/权限拒绝、404本人对象不存在、409固定选择/摘要/窗口/重复执行冲突、429共享预算忙、503来源/存储/输出配置不可用。受理后的来源失败返回原FAILED Plan，受理后的执行结果按原Receipt显示，不以成功HTTP冒充成功输出。

生产身份由可信grant显式授予，不能从模型、前端菜单或请求授予。迁移[前进](../db/migrations/platform/V056__workflow_metric_replay.sql)/[回退](../db/rollback/platform/V056__workflow_metric_replay.sql)位于既有PG；保留记录时回退拒绝。两步流程不是原始快照保留服务、生产历史补偿或分布式执行引擎。
