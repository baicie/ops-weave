# inventory

Entity / Relation / Observation / ExternalLink / 字段融合。

当前提供按可信租户查询的 `GetEntityUseCase`。生产库存是 PostgreSQL；`InMemoryInventoryStore` 只在显式 `memory` 模式使用。完整快照才把缺失外部对象标为 `INACTIVE`。

尚未提供字段权威融合引擎或行级安全。

公开契约位于 `api/`；领域规则位于 `domain/`；用例位于 `application/`；实现适配位于 `infrastructure/`。
