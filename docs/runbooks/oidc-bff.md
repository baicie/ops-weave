# 本机 OIDC BFF 验收

这份记录不要求真实登录环境。用户当前选择先完成本地实现；开发 Token 模式和新增 OIDC 协议测试分别验收，均不得冒充真实来源/模型/生产登录。

配置入口在 `apps/platform-api/src/main/resources/application.yml`。普通 OIDC 模式需要 `OPSWEAVE_AUTH_MODE=oidc`、`OPSWEAVE_OIDC_ISSUER`、`OPSWEAVE_OIDC_AUTHORIZATION_URI`、`OPSWEAVE_OIDC_TOKEN_URI`、`OPSWEAVE_OIDC_JWK_SET_URI`、`OPSWEAVE_OIDC_CLIENT_ID`、`OPSWEAVE_OIDC_CLIENT_SECRET`、`OPSWEAVE_PUBLIC_ORIGIN`、`OPSWEAVE_IDENTITY_GRANTS_FILE`。端点和 public origin 使用 HTTPS，无 query/userinfo/fragment；callback 精确注册为 `${OPSWEAVE_PUBLIC_ORIGIN}/api/v1/auth/callback`，同源代理将 `/api` 交给 Java。只申请 openid，授权码交换为 client_secret_basic，签名 RS256；平台不使用 UserInfo。不要把 secret 写入命令行参数、仓库、普通日志或任务消息。

授权文件参照 `contracts/examples/identity-grants.json`，需用真实 verified sub 与 issuer 配置内部身份。它不是请求正文，不提供上传或 HTTP 修改接口。采用最小权限和对象范围；更新时准备好完整文件后原子替换，读取失败或某行更改会撤销对应旧会话。`OPSWEAVE_SESSION_SECONDS` 默认 900，允许 60–1800，仍受 ID Token 到期限制。

Web 用 `VITE_PLATFORM_AUTH=oidc pnpm --filter @opsweave/web-console build` 的相应 shell 环境变量语法构建；同源部署。不能给 Vite 定义任何 provider secret。缺配置/登录失败会显示错误，不能改回 fixture。开发构建默认仍为显式输入 Token 的既有模式。

本机验收命令：

```text
./gradlew.bat :apps:platform-api:test --tests *Oidc* :apps:platform-api:bootJar
node scripts/check_oidc_stack.mjs
```

脚本要求环境中提供专用 loopback `OPSWEAVE_TEST_JDBC_URL`、`OPSWEAVE_TEST_JDBC_USER`、`OPSWEAVE_TEST_JDBC_PASSWORD`、`OPSWEAVE_TEST_VM_URL`；可选 `OPSWEAVE_TEST_CHROMIUM_EXECUTABLE`。提前完成 Rust 默认及 all-features 测试和 Runtime 构建，再运行脚本，避免 Windows 锁住正在使用的 exe。不要在运行验收时重建它所用的 Java jar。旧链仍用 `scripts/check_metrics_stack.mjs --pipeline --runtime` 另行回归。

脚本自建显式 loopback OIDC 协议服务、随机 RSA/客户端密钥、授权文件、Java、Rust 和独立 OIDC Web 构建；已有 PG/VM 不由脚本销毁。它验证实际浏览器跳转、Cookie/CSRF、无自动业务读取、资产/指标、一次短期委托诊断、PG 保存/刷新/证据、双标签退出和授权撤销。来源为 labeled-fixture，模型为 mock-deterministic。只有验收脚本在写入后等待 VM 可见；产品不会自动重试。退出会关闭自建服务与浏览器。

输出在 `.tmp/oidc-acceptance`，不得提交。截图不含凭据；`browser-session.json` 仅 CSRF 字段脱敏，其他会话元数据用于 Schema 检查；`identity-grants.json` 为脚本自己的合成配置。没有保存 IdP Token、Cookie、授权码或委托凭据。

验证后仍需真实 IdP/TLS/反向代理及来源/模型环境验收、人工诊断抽样；Worker 独立服务身份已补本机实现与协议链，见 [服务身份 runbook](history-service-identity.md)；跨主机 Runtime 认证仍待实现。单进程 session 不承诺 HA，重启全部失效；平台退出不声称退出 IdP。锁文件、契约、实现边界和本轮实际结果见 [ADR-030](../adr/030-oidc-bff-and-bounded-runtime-delegation.md)、[身份契约](../../contracts/browser-identity.md)、`VALIDATION-REPORT.md` 第 36 节。
