# 浏览器身份与内部委托 v1

`schemas/v1/browser-session.schema.json`、`browser-logout.schema.json` 和 `identity-grants.schema.json` 是身份边界的业务 Schema；Spring OAuth、Servlet session 和 Nimbus JWT 对象不进入跨语言契约。样例均为占位数据。

`OPSWEAVE_AUTH_MODE=oidc` 启用 authorization-code BFF。`closed` 默认拒绝，`dev` 保留既有显式 loopback 开发凭据，三者不会相互回退。Web 构建以 `VITE_PLATFORM_AUTH=oidc` 启用 Cookie 模式，只自动读取 `/api/v1/auth/session` 元数据；其他业务仍需用户显式读取。当前会话不向 Web 或 Rust 返回 IdP ID/access/refresh Token。固定 `/api/v1/auth/login/opsweave` 和 `/api/v1/auth/callback`，成功只跳转到配置的 origin 下的 `/#/inventory`。

服务端验证授权码、state、nonce、S256 PKCE、issuer、audience/azp、RS256 签名及 iat/nbf/exp；授权码交换使用 client_secret_basic。配置直接指定四个 IdP URI，不执行 discovery、UserInfo、动态注册或从请求选取 IdP。token/JWKS 共享 4 个网络名额，各请求 2 秒连接、5 秒含正文期限和 64 KiB 响应上限，不使用代理、跳转、失败重试或后备来源。固定库的短期 JWKS 缓存与未知 key 刷新不是业务重试；请求仍经过同一边界。

平台权限只来自操作员文件。文件为绝对路径、最多 256 KiB/500 项，不接受叶文件符号链接；每项 issuer 必须等于配置，externalSubject 和内部 tenantId/subjectId 组合各唯一。permissions 使用稳定枚举；tenantWide=true 必须空 resources，false 必须 1–2000 个唯一资源引用。`*` 仅是已有 ResourceScope 的同类型通配，不代表所有权限。JSON 重复键、未知字段、非法文件或缺失映射均失败。每次已登录请求重新读取并比较该行 digest；enabled=false、任意行内容/版本变化使该行已有会话失效，不复用缓存授权。更新文件应在同目录准备好后原子替换。

普通模式只允许 HTTPS IdP URI 与 HTTPS public origin。Cookie 为 `__Host-opsweave; Path=/; Secure; HttpOnly; SameSite=Lax`，不能 URL 重写、不能序列化到磁盘。最多 1000 个单进程 session；登录进行中 180 秒；登录后绝对期限为 ID Token 到期与配置 60–1800 秒（默认 900）较早者，读取不延长。成功登录旋转 session ID 并清除旧 CSRF。重启使全部会话失效；没有共享 session/HA 或自动刷新 Token。

元数据返回 masked CSRF Token，Web 只保存在内存。所有 Cookie 非安全方法须精确匹配配置 Origin 并携带 `X-CSRF-TOKEN`；跨站请求、重复 Origin、客户端身份覆盖被拒绝。回调单独以待处理登录、state/nonce/PKCE 约束，未发起的回调不能清除已有登录。401 清除身份和结果；403 使旧数据和请求失效但保留已确认身份。POST logout 只有得到 `{loggedOut:true}` 才显示成功，并经 BroadcastChannel 清除其他标签页；不代表退出 IdP。

Rust 委托完全在服务端传输。Java 签发 32 字节随机 opaque credential，内存仅以 digest 索引，最多 4 个在途、65 秒、8 次原读取/保存请求；任务结束移除。ADR-033 额外允许一次当前 run/session 的模型费用预留、一次其后的用量上报；这两次不扩大原读取预算，重复/跨 run/session 拒绝。每次请求检查原始 session 身份、授权文件和绝对期限。只允许本机无 Cookie/Origin 的指定 read-session、三个 v2 Tool、当前 runId 的 AIInsight 读取/保存及两条费用写入端点；固定 incident/window/question/knowledgeMode/runId 和一次创建的 read-session。原有对象范围、Tool 四次共享预算、证据时效、Runtime 提交证明与幂等检查继续独立生效。无法用于来源同步、任意资源 API 或新诊断。登出、授权修改、新登录和到期撤销在途能力；不会把 IdP Token 转交 Rust。

`OPSWEAVE_OIDC_LOOPBACK_TEST=true` 只允许显式 HTTP loopback URI 和 loopback bind；改用 `opsweave-test-session` Cookie，元数据必须显示 `oidc-protocol-test`。它仅供本机协议验收，不是 HTTPS/真实登录验收。Worker 的独立服务身份、跨主机 Runtime 认证、IdP back-channel logout 与分布式会话均尚未提供。
