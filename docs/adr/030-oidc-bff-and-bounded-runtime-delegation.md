# ADR-030：OIDC BFF 与短期 Runtime 委托

状态：接受；2026-09-25。本机协议切片，不宣称真实提供方或生产部署已验收。

此前只有 loopback dev Principal，浏览器把开发凭据转给 Java，诊断时再传给 Rust。M1 需要真实可实现的登录边界、服务端撤销、CSRF 和无 Token 浏览器会话，同时保持四启动单元与既有纯领域授权。

选用锁定的 Spring Security 7.0.7 authorization-code 支持，使用 PKCE/state/nonce 与固定 HTTPS 端点验证 ID Token；纯领域新增 `IdentityGrant`，其余框架适配留在 platform-api。身份仅由 verified issuer/sub 映射操作员管理的租户、用户、权限和范围；不接受提供方额外 tenant/roles/permission claims。首次采用有大小限制的本机授权文件，逐请求重新验证，不引入新数据库或 IAM 管理后台。

Java BFF 保留单进程、有限期、有限数量的 HttpOnly Cookie 会话；只返回平台身份与 CSRF 元数据。成功登录旋转会话 ID 和 CSRF，显式退出使所有引用该 session 的委托失效。Web 配置 Cookie 模式后不显示开发 Token 输入、不自动降级；刷新只获取登录元数据，显式业务读取继续检查服务器权限。

不能把浏览器 Cookie 或 IdP Token 转发到内部 Runtime。因此 Java 为一次已授权诊断签发短期 opaque credential，限定原始会话、Incident/窗口/runId/问题、只读 Tool 与 AIInsight 保存，限制并发/调用次数/期限，任务结束删除。Rust 原有 loopback `platform-dev` HTTP 传输接收该请求凭据；其名称不代表生产服务身份。Java 才是权限裁决方，Runtime 的 read-session、证据和保存仍经过原有独立边界。

完整契约和明确限制见 [browser-identity](../../contracts/browser-identity.md)，配置与复现见 [runbook](../runbooks/oidc-bff.md)，实际结果见验证报告第 36 节。只通过本机 OIDC 协议服务、fixture 来源、mock 模型与真实 Java/PG/VM/Rust/浏览器。真实 IdP HTTPS/密钥轮换/部署代理验收、Worker 服务身份、跨主机认证、分布式 session、IdP 退出通知不在本切片完成范围。

框架 API 以锁文件与本机编译为准，参考官方 [OAuth2 Login Advanced Configuration](https://docs.spring.io/spring-security/reference/7.0/servlet/oauth2/login/advanced.html)、[CSRF](https://docs.spring.io/spring-security/reference/7.0/servlet/exploits/csrf.html) 和 [Authorization Grant Support](https://docs.spring.io/spring-security/reference/7.0/servlet/oauth2/client/authorization-grants.html)。
