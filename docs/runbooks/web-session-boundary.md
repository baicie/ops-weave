# 公共请求与开发会话本机验收

范围见 [ADR-025](../adr/025-web-request-session-boundary.md)，实际执行结果见 [验证报告第 31 节](../VALIDATION-REPORT.md)。本页不提供生产登录方案，使用显式 loopback 开发模式、随机凭据与 fixture 数据。

## 自动检查

1. 按仓库锁文件安装依赖。运行 `pnpm --dir apps/web-console build`，随后运行 Web Playwright 配置。`http-client.spec.ts` 是请求层直接测试，其余为浏览器 fixture；覆盖大小/编码、同源、超时、并发、旧响应、401/403、退出清理与 Runtime fixture 凭据隔离。
2. 使用专用 loopback PostgreSQL/VictoriaMetrics，设置已有测试环境变量，运行 `gradlew.bat :apps:platform-api:test :apps:ingestion-worker:test :apps:platform-api:bootJar :apps:ingestion-worker:bootJar --console=plain --rerun-tasks`。`RequestBoundaryHttpIT` 检查成功/未登录/非法与重复请求标识、禁止缓存和身份覆盖拒绝。
3. 依照 [指标查询验收](metric-query-acceptance.md) 和 [当前诊断验收](insight-local-acceptance.md) 准备本机启动包，执行 `node scripts/check_metrics_stack.mjs --pipeline --runtime`。脚本启动自己的 Java/Rust/Vite/浏览器，使用测试进程固定租户与随机 Token，结束时关闭进程；不伪造厂商或模型成功。

脚本执行原有八条链，并核对浏览器 API 响应的 `X-OpsWeave-Request-Id`、`Cache-Control`、`X-Content-Type-Options`。最后读取关联历史、清除会话、跳转资产，断言历史与凭据消失且读取按钮不可用。`.tmp/metrics-acceptance/request-boundary.json` 仅保存响应计数、失败数和凭据存储模式。

流式 Fetch 完整消费后，当前 Chromium 的 CDP `getResponseBody` 偶发丢失正文。脚本从同一 Vite 代理响应被动记录有界正文，以请求 UUID、路径、状态与浏览器响应匹配；要求响应完整，继续核对页面渲染。它不改写响应、不额外请求、不重试，也不将调试接口失败作为应用成功。记录只存在验收进程内存，单响应 2 MiB、总计 16 MiB、最多 256 条。

## 人工核对

- 在平台页面输入开发凭据，切换 hash 导航无需再次输入；每个页面仍重新读取受权数据。
- 清除/替换开发凭据后，旧表格、曲线、诊断与草稿消失；迟到请求不能恢复数据。
- 401 清除凭据；403 清除页面数据与编辑内容，保留凭据供其他授权资源使用。
- 刷新或触发 `pagehide` 清除凭据。开发凭据在输入后绝对保留至多 30 分钟，读取不续期；该期限不是服务器 Token 失效时间。
- 平台凭据不能出现在 URL、localStorage、sessionStorage、Cookie 或普通日志中，也不会传给历史 fixture Runtime。

清除只影响本标签页，不撤销服务器配置的开发 Token。模拟 `pagehide/pageshow` 的回归不等于所有浏览器 BFCache 验收。真实 OIDC、Cookie/CSRF、后端会话撤销与委托身份仍需后续实现及独立验收。
