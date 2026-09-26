# ADR-019：私有流水线草稿与并发保存

状态：接受（2026-09-25，本机开发切片）。沿用现有 integration 模块和平台 PostgreSQL，不增加启动单元。

## 决定

为 Host 固定六节点定义增加工作草稿。草稿是可修改的工作副本；发布版本仍由 `PipelineVersionStore` 不可变保存，不用草稿替换已发布内容。

- 草稿键为可信 Principal tenant、服务器配置 source、当前 subject、流水线 id 和目标 revision。请求不得携带身份、来源或权限覆盖。所有读写要求配置来源的 source.sync；只读取本人草稿，不引入共享编辑权限或生产 IAM。
- `expectedEditVersion=0` 表示创建；更新必须提交上次读取的编辑号。存储以原子 compare-and-swap 增加 editVersion，PG 使用单条 INSERT/UPDATE RETURNING，开发内存使用同步互斥。冲突返回 409 DRAFT_CONFLICT，不覆盖先成功保存的内容。
- editVersion 是草稿保存序号，definition.revision 是将来的发布版本号，两者独立。即使内容相同，旧编辑号也报冲突。响应丢失时读取核对，不自动假定保存失败或无条件重试覆盖；没有强制覆盖接口。
- 保存只接受现有有界 Host PipelineDefinition；同样拒绝脚本、任意路径/HTTP/SQL。存储计算并保存规范 digest，读取验证内容/digest 及 id/revision 一致性。数据库语句限时 5 秒，HTTP body 最多 16 KiB。
- 最近列表仅包含引用、编辑号和更新时间，默认 20 / 最多 50 条，额外读取一条判定 truncated。它不是完整草稿目录；较早草稿仍按 id/revision 精确查询。暂未提供共享、删除、TTL、全量分页或总容量配额。
- 保存草稿不运行 Preview、不发布、不抓取来源、不写库存/Observation，也不发通知或动作。发布仍接收用户预览过的完整固定定义，不读取“最新草稿”；另一个页面的保存不能改变这次发布的内容。

## 页面语义

显式保存与载入，不自动保存凭据或定义到浏览器持久存储。编辑配置保留已读 editVersion；修改 id/revision 则改为新草稿键，若该键已存在由服务端拒绝覆盖。载入会替换表单并作废旧 Preview/发布状态；保存后也需重新 Preview 才可通过页面发布。

冲突保留本地编辑值，提示先记录修改、读取最新草稿并比较；不自动合并。切换 Token 清理草稿、历史、表单及旧请求结果。草稿状态明确为 DRAFT，storage=memory 明示服务重启丢失；PostgreSQL 使用 V009 持久保存。

本功能不代表发布审批、全量草稿审计历史、服务端 Preview 审批门禁、真实 Zabbix 接入或生产鉴权已完成。当前发布 API 仍可由授权调用方直接提交固定定义；页面的先预览流程不是权限控制。验证见 VALIDATION-REPORT 第 24 节。
