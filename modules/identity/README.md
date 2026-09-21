# identity

可信身份上下文、租户成员关系、资源授权；认证协议接企业 OIDC。

已实现：`Principal` / `TenantId` / `ResourceScope` / `Permission` / `AuthorizationDecision`，以及 `authorize(principal, resource, action)` 的 allow/deny。本地使用显式 `DevPrincipalResolver`；`OidcPrincipalResolver` 是占位，失败时禁止 mock 回退。

未实现：组织架构、ABAC DSL、SSO 管理后台、LDAP、完整用户生命周期、生产 OIDC。

公开契约位于 `api/`；领域规则位于 `domain/`；用例位于 `application/`；实现适配位于 `infrastructure/`。
