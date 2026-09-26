# ADR-013：身份由认证边界构造，授权在执行器侧完成

状态：已采纳 · 日期：2026-09-22

## 决策

`Principal`、`TenantId`、`ResourceScope` 和 `Permission` 只由 `platform-api` 的认证边界构造。业务用例调用 `authorize(principal, resource, action)`，不从 HTTP body、query 或模型输出读取 tenant/user/权限。

第一版只覆盖 allow/deny 矩阵与显式 Dev adapter。OIDC adapter 作为拒绝 mock 回退的占位，不在本 ADR 接入 Keycloak。不实现组织树、ABAC DSL、SSO 管理后台或完整用户生命周期。

## 代价与后续

Demo token 仍只能用于 loopback。生产必须替换为校验 issuer/audience/expiry 的 OIDC adapter，并继续在每次资源访问时授权。

后续：2026-09-25 的 [ADR-030](030-oidc-bff-and-bounded-runtime-delegation.md) 已实现 OIDC authorization-code BFF 与短期委托；本 ADR 的 dev 边界保持不变，原始 Bearer OIDC 解析仍关闭。
