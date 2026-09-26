# ADR-023：当前知识诊断与平台 AIInsight

状态：接受（2026-09-25，本机开发实现）。延续 ADR-022，不修改旧历史 asOf 流程。

## 决策

新增 canonical `current-diagnose-request`、`current-context`、`insight-submission`、`ai-insight`、`ai-insight-result`。请求只有 runId、Incident、问题、采样窗口和 current 模式；身份由 Java 可信边界构造。CPU User 是本轮唯一固定诊断指标。当前上下文包含两个完整、结构化的不可变证据文档；知识截止在读取完成后捕获，不能据此声称历史采集可见时间已恢复。

新增独立 Skill 包 `extensions/skills/incident-diagnosis-current`，版本 2.0.0；原 1.0.0 包不覆盖。Java 与 Rust 对 skill.json、prompt.md、output.schema.json 使用相同的长度前缀 SHA-256。运行绑定版本和 digest；不远程下载 Schema。模型只收到问题、固定指令、结构化证据和输出 Schema，不能访问平台凭据或 Runtime 提交密钥。

浏览器向同源 Java `POST /api/v1/ai/diagnoses` 提交请求。Java 先检查 ai.diagnose、ai.insight.read、Incident/关联资产权限，再向固定 loopback Rust `/api/v1/current-diagnoses` 转发当前调用者的 Bearer。Rust 通过 Java 创建的会话接收可信 Principal；不解析用户提供的身份字段。返回成功前，Java 从自己的 AIInsight Store 重新授权查询结果，不把 Runtime 任意响应当作已保存证明。

每次会话四次调用（Incident、指标、两份证据复核）、最多五个实体、500 点和一小时整秒窗口。当前 Skill 截止 55 秒，单次模型调用最多 20 秒，Runtime 请求整体最多 60 秒；Java 转发最多 62 秒，独立四并发，无排队/重定向/重试。平台普通受控读写执行器仍四并发、15 秒。上下文预算 64 KiB；模型输入连同输出 Schema 上限 72 KiB；Java 保存结果上限 96 KiB；跨进程结果响应最多 128 KiB。失败不回退 mock、不自动追加模型调用。

## 持久化与复核

V012 在现有 PostgreSQL `ai_control` schema 增加 AIInsight 与 Evidence 关联表，不增加数据库或启动单元。Java 复核会话所有者、四次预算、两次成功证据复核、当前 Incident 版本/实体集合、可信租户/对象权限、证据 availableAt/asOf/当前过期时间及引用。缺口和 mock/当前知识/引用非因果限制由服务器保留，模型不能删掉它们。

POST 保存还要求独立 `X-OpsWeave-Runtime-Key`，并校验配置的模型标识和打包 Skill digest。这只是 loopback 开发进程证明，**不能替代用户授权或生产服务身份**。默认未配置即关闭；Web 不持有该密钥。Rust 仅在固定的结果 POST 路径发送密钥，Evidence/查询请求不发送。

事务锁定 tenant/runId 和读取会话，并锁定当前 Incident；结果与两条证据外键关联一并提交。相同 tenant/runId/subject/原始请求摘要返回同一结果，不同内容冲突；每个会话最多一个结果。外键失败、版本变化或截止时间失效时不部分提交。刷新后可 GET 查询保存结果；GET 不调用模型，并重新检查 Incident、原实体/指标、证据权限及当前过期时间。

结果有效期等于最早证据过期时间（本轮证据 24 小时）。过期返回 410，存储留存清理尚未实施；“保存”不承诺永久可读。普通日志只记录运行标识、provider、状态等元数据，不记录问题、上下文、凭据或完整模型输出。

## 重试与界面

Web 在提交前创建 runId 并放入 URL，Token 只在内存。成功展示结构化发现、观察/假设、来源 fixture 标签、provider、Skill digest、窗口/知识时间/过期时间及缺口，证据点击时重新授权。切换身份、卸载和过期清理结果，外部内容只作文本显示。

连接中断/超时显示保存状态待确认，用户先按 runId GET 查询；不自动重试模型。同一进程的并发 runId 有临时互斥，已有保存结果的同键 POST 会先回读，不再次调用模型。当前同键回读仍会先创建读取会话，受四个活动会话限制。未保存的运行没有持久恢复语义；多个 Runtime 副本不保证只调用一次模型，不提供 HA 声明。持久 AIRun/可靠投递属于 M5。

## 验收边界

本机链路使用真实 Java/Rust/PG/VM 与明确标记的 fixture/mock；真实 Zabbix、Rig 模型、OIDC 和人工抽样审阅仍需独立验收。真实 provider 仍需显式允许出网，不因本 ADR 自动启用。实际命令与结果见验证报告第 29 节。
