# 工作流后台授权边界

API沿用`/api/v1/integrations/workflows/runtime`、`/start`、`/stop`与`/execute`。实体版本必须先发布；启动只处理启动后新完成且完整的原Host批次，不启动上游采集。发布、单次执行和后台启动分别授权。

`workflow-runtime.schema.json`增加DELEGATED_ENTITY模式。该模式必须返回backgroundAvailable，表示当前可信身份及操作员后台许可可否申请授权；它不替代执行器授权。LOCAL_DEV_ENTITY旧响应允许省略该字段。mode不由客户端请求提供，未知query参数拒绝。

公开任务`workflow-runtime-task.schema.json`可增加authorization：id、issuedAt、expiresAt、maxBatches、consumedBatches。最多15分钟/20批；前后端检查时间先后与已用不超过额度。公开执行`workflow-runtime-execution.schema.json`可增加nullable authorizationId。旧公开任务和执行保持可读。

私有`workflow-runtime-task-storage.schema.json`只供服务器持久化，使用nullable authority引用`workflow-task-authority.schema.json`，记录原issuer、externalSubject、完整grantDigest及期限/额度。它不包含Principal、权限、Cookie或令牌。公开任务不得出现该引用；浏览器控制只提交requestId、id/revision/digest/settings/expectedGeneration，额外身份或授权字段拒绝。

操作员私有文件遵守`workflow-background-policy.schema.json`，闭合schemaVersion/subjects字段、至多20个唯一非空subject、16KiB读取预算、绝对路径和普通非符号链接文件。contracts中的示例仅为明确Fixture；实际身份和路径留在私有配置。

稳定失败新增AUTHORIZATION_EXPIRED、AUTHORIZATION_REVOKED和EXECUTION_LIMIT。未完成批次的失败回执保留已确认写入数量/实体ID，任务保留原cursor/cursorId；失败后不自动重试。额度计数按尝试持久输出的批次计，不按行计；成功20批后下一次轮询停止，额度检查先于读取新批次。公开计数、时间和引用只用于展示，不能授予权限。

签发后的授权独立于浏览器会话，注销不自动撤销；原绝对截止不可扩大。每次执行核对当前操作员许可、完整身份映射摘要、可信租户/所有者及对象范围。政策或身份失效不回退开发身份，也不在配置损坏时沿用旧授权。开发与委托任务隔离。

第114节另行补充[控制命令原回执](workflow-runtime-control.md)，查询/重放不申请新授权。失败批次重放及未知业务输出确认仍未实现，不接受固定配置Host或指标来源，不写时序指标或日志。授权适配器恢复验证使用显式本地协议Fixture和实际PG，不等同生产IdP/整机故障/HA验收。
