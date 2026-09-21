# audit

可追溯的安全与业务审计契约；不储存 Secret 或模型隐藏思维链。

这是逻辑模块，不是独立微服务。没有实现的功能不得通过返回假数据冒充完成。

公开契约位于 `api/`；领域规则位于 `domain/`；用例位于 `application/`；实现适配位于 `infrastructure/`。
