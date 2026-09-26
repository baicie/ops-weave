# identity

可信身份上下文、租户成员关系、资源授权；认证协议接企业 OIDC。

已实现：`Principal` / `TenantId` / `ResourceScope` / `Permission` / `AuthorizationDecision`，以及 `authorize(principal, resource, action)` 的 allow/deny。`IdentityGrant` 将经过验证的 issuer/sub 绑定到操作员授权，检查启用状态与有效期；领域不依赖协议框架。本地保留显式 `DevPrincipalResolver`。OIDC authorization-code BFF、Cookie/CSRF、授权文件撤权和短期 Runtime 委托由 platform-api 的适配层负责；`OidcPrincipalResolver` 仍拒绝直接解析原始 Bearer，无 mock 回退。

未实现或未验收：组织架构、ABAC DSL、SSO 管理后台、LDAP、完整用户生命周期；真实 IdP/TLS/部署代理、Worker 服务身份、跨主机认证、共享 session/HA。协议边界与实测见 [ADR-030](../../docs/adr/030-oidc-bff-and-bounded-runtime-delegation.md)。

公开契约位于 `api/`；领域规则位于 `domain/`；用例位于 `application/`；实现适配位于 `infrastructure/`。
