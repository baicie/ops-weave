# Incident 人工合并/拆分本机验收

适用：ADR-024 / V013。仅使用显式 loopback 开发环境、随机 dev Token 和固定 tenant。用户暂未准备真实服务配置；本手册不代表厂商或生产认证验收。

## 准备

沿用 [Incident 本机验收](incident-local-acceptance.md) 与 [当前诊断验收](insight-local-acceptance.md)，在同一固定租户导入两个 fixture 外部问题。开发权限需包含 `incident.read,incident.manage,entity.read`；诊断/证据另需其既有权限。不要把密钥写入命令正文、页面 URL、报告或普通日志。

V013 由平台启动迁移，在既有 `incident` 和 `alerting` Schema 中维护归属与回执。数据库不可达则报错，不回落 memory。多次启动跳过已提交迁移；失败迁移的 DDL 和标记在同一事务回滚。

## 页面步骤

1. 进入“Incident 归属”，输入当前页 Token、来源 ID，读取来源。输入另一个 OPEN / INVESTIGATING 目标 ID，读取目标；填写人工原因。
2. 点击“预览关联调整”，核对双方版本、移出问题和影响。此时服务器记录不应变化。点击“确认关联调整”后读取回执。
3. 刷新页面并重新输入 Token，点击“读取关联记录”。URL 中 requestKey 可找回原结果；重复提交同一请求必须返回同一回执。不要在提交结果未确定时另建请求。
4. 来源从活动列表移除，但按 ID 可读合并目标和原历史；目标包含两组问题。来源人工状态和恢复事实不得被伪造。
5. 在结果中点击“读取调整后的目标”，切换为拆分，选择部分问题，填写新标题和原因。全选必须被拒绝；确认后新 Incident 为 OPEN / 版本 1。
6. 再次导入同一 fixture 窗口，createdIncidents 应为 0，问题留在新归属，不能重建最初来源。刷新并读取关联历史，可查双方的合并/拆分回执。
7. 若合并前保存过诊断，其证据和 AIInsight GET 应返回 409；重新诊断需读取新版本。Token 清空后，结果和历史立即清除。

## HTTP 与边界

- `POST /api/v1/incidents/reorganizations`：请求和样例在 `contracts/schemas/v1/incident-reorganization-request.schema.json` 与 `contracts/examples/incident-reorganization-request.json`。
- `GET /api/v1/incidents/reorganizations/{requestKey}`：重新授权的原始回执。
- `GET /api/v1/incidents/{incidentId}/reorganizations?limit=20&after=…`：UUID 游标，最大 25 条，双方可见条件先于分页。

400 为结构/查询错误；401/403 为身份或权限；404 为未找到可见对象；409 为版本、归属、状态或请求键冲突；503 为存储/预算失败。503/超时不能等同于事务未提交，应先用原 requestKey 查询或重试。归档不接受新状态或新诊断，合法旧状态操作的幂等回执仍可按原键重试。

完整栈脚本 `scripts/check_metrics_stack.mjs --pipeline --runtime` 已包含上述流程。它要求显式 `OPSWEAVE_TEST_JDBC_URL/USER/PASSWORD`、`OPSWEAVE_TEST_VM_URL` 和本机 Chromium；先构建 Java bootJar、Rust Runtime/probe 和 Web。脚本限制存储 loopback、生成随机开发凭据并创建独立 fixture tenant；退出时关闭自己启动的 Java/Rust/Vite/浏览器，不负责删除验收数据库。

`.tmp/metrics-acceptance/reorganization.png` 是本机截图，JSON 回执供 Schema 校验，不提交凭据或临时产物。实际命令与结果见 [验证报告第 30 节](../VALIDATION-REPORT.md)。
