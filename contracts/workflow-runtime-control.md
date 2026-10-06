# 工作流任务控制契约

Schema唯一源为schemas/v2中的workflow-runtime-control-request、workflow-runtime-control-receipt、workflow-runtime-control-storage和workflow-runtime-task。公开/私有任务格式分离；examples/v2/workflow-runtime-control-receipt.json是显式Fixture合成样例，摘要不代表真实已发布工作流。

| 接口 | 请求与行为 |
|---|---|
| POST /api/v1/integrations/workflows/runtime/start | 六字段封闭JSON：requestId(UUID)、id、revision、digest、settings、expectedGeneration；操作由路径决定 |
| POST /api/v1/integrations/workflows/runtime/stop | 同上；固定原任务版本/设置及当前代数，只接受RUNNING |
| GET /api/v1/integrations/workflows/runtime/commands/{requestId} | 只读原受理回执，不接受查询参数，未找到为404 |
| GET /api/v1/integrations/workflows/runtime | 当前任务及最近执行回执，不能当作原控制命令的确认 |

settings只有identityField、nameField。请求没有tenant、owner、authority、token、permission或operation；可信身份来自已有认证边界。当前HTTP控制只开放原Host实体任务，不接收固定连接版本、标准指标或日志后台任务。OIDC写请求继续要求Origin与CSRF，委托申请沿用[后台授权契约](workflow-background-authority.md)。

响应只有requestId、operation(START/STOP)、commandDigest、createdAt、task。createdAt与原Task.updatedAt一致，代数等于原expectedGeneration+1；START快照为RUNNING，STOP为STOPPED。跨字段一致性由领域和客户端校验，不能仅依赖JSON Schema。私有持久回执使用独立Task storage格式；HTTP去除issuer、externalSubject及grantDigest。

相同可信tenant/owner/requestId及相同内容返回原回执。规范摘要包括操作、工作流ID/版本/摘要、两字段设置和代数，以固定顺序、长度边界计算；不将UUID、身份或动态时间混入正文摘要。变更任意正文复用键为409。先读取原回执，再检查当前容量或申请授权；原START回执的查询/重放不会重新启动已经停止、失败或更新的任务。原回执必须仍在当前工作流读取范围内。

新命令保留generation CAS。expectedGeneration为0..1000000，新START在>=999999时返回容量拒绝，STOP可将旧RUNNING 1000000推进为STOPPED 1000001。Task代数1000001仅允许STOPPED。每owner最多200受理回执，START额度180，预留20个停止；不自动清理或复用键。被拒绝的新命令不写成功回执，不能据一次404声称旧网络请求绝对没有在途。

前端未知结果必须保留原键/输入；只在显式404后允许人工按原内容重发或放弃确认。回执匹配原请求且完整校验后，再读当前状态；读失败保留错误并禁用依赖状态的动作。不自动改代数、换键、续期或重新预览。浏览器身份更换/403清除元数据，不写localStorage/sessionStorage。关闭网页后，服务器的任务仍按独立后台授权生命周期运行。

兼容性：这是内部成对部署的控制接口升级。旧无requestId请求400；旧直接Task响应不再接受。已有Task/Execution公开字段和持久历史兼容，V041添加低频元数据表。未承诺失败命令回执、输出exactly-once、跨进程fencing、完整恢复或生产容量。设计见[ADR-076](../docs/adr/076-idempotent-workflow-control.md)。
