# ADR-018：Host 只读重放记录与显式恢复

状态：接受（本机切片，2026-09-25）。补充 ADR-014 的 PostgreSQL 接入存储，不增加启动单元或数据库。

## 问题

Host PipelineVersion 已能对历史 Raw 执行有界映射比较，但同步请求结束或浏览器刷新后，报告没有可查询的记录。客户端超时不能判断服务端是否完成，进程中断也没有可识别的恢复入口。

## 决定

integration 增加 `PipelineReplayRun`、`PipelineReplaySpec`、`PipelineEvaluation` 和 `PipelineReplayStore` 端口。领域不依赖框架；平台适配器负责 PostgreSQL/JSON。沿用既有确定性 evaluator，同步运行最多 100 条 Raw，不新增队列或后台 Worker。旧 `/pipeline/replay` 保留即时响应，新 `/pipeline/replay-runs` 提供持久记录。

- 可信 Principal 的 tenant、subject 与服务器配置 source 组成记录范围。请求只提供 UUID requestKey、SyncRun、固定发布版本/digest、purpose、limit；同键不同参数拒绝。不同 owner 的历史和游标互不可见。
- 短事务创建或锁定幂等记录，评估时不持有事务。状态为 RUNNING / SUCCEEDED / FAILED，120 秒租约、递增 attempt 为 fencing token。成功写回必须同时匹配范围、ID、RUNNING、attempt 和未过期租约；旧执行不能覆盖新结果。
- 活跃重复请求返回 202，不重算。成功重复请求返回原报告，报告不随之后 Raw 清理而改变。失败或租约到期需要显式提交相同请求才能再次执行，最多三次。重新执行读取当时仍留存的 Raw，缺口仍必须展示，不能承诺重试前后样本相同。
- 第三次租约过期时，下一次相同 POST 仅将记录收束为 FAILED / REPLAY_ATTEMPTS_EXHAUSTED，不再次执行。GET 不改状态、不续租、不恢复；没有定时清理器。页面“恢复或确认过期记录”支持这一步。
- 只有成功状态存报告，失败只存安全错误码。普通日志不记录凭据、Raw payload 或报告内容。PostgreSQL 报告序列化上限 1 MiB，数据库 JSONB 文本限制 1,100,000 字节（容纳格式化空格）。超过限制失败，不静默截断。报告只含有界映射摘要与 Raw 引用，不复制原始 payload。
- 历史列表只返回头部和请求参数，默认 20 条、最多 50 条，按 createdAt/id 倒序分页，不加载报告。报告读取及成功幂等返回均重新检查每行旧/新实体的当前 `entity.read` 和对象范围。列表仍要求 source.sync。
- 仅保存重放元数据与结果；evaluator 没有库存写入、来源抓取、通知或动作端口。`dryRun=true` / `writesPerformed=false` 指业务执行副作用，不表示连重放记录都不保存。

## 结果与限制

PostgreSQL 使用 V008；内存适配只供开发，服务重启即丢失，UI 明示 storage。不确定响应重试沿用原请求键；刷新页面后重新输入 Token 即可读取本人历史。切换 Token 清理记录、报告及未完成请求；凭据不进 localStorage。

这是单平台的同步有界求值：租约依赖平台时钟，尚无跨主机时钟漂移/HA、总租户存储配额、记录 TTL/清理和跨进程并发预算验收。自动清理将来必须保留幂等墓碑或明确幂等期限，不能静默删除后把旧键当新请求。内存适配也不作为长期历史仓库。

没有持久草稿、发布审批、异步调度、写入型数据修复或 AIRun 持久化。来源仍需可达厂商 Zabbix 才能验收，不把 labeled fixture 的真实数据库测试称为真实来源接入。检查证据见验证报告第 23 节。
