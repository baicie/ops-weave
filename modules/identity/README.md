# identity

可信身份上下文、租户成员关系、资源授权；认证协议接企业 OIDC。

这是逻辑模块，不是独立微服务。没有实现的功能不得通过返回假数据冒充完成。

公开契约位于 `api/`；领域规则位于 `domain/`；用例位于 `application/`；实现适配位于 `infrastructure/`。
