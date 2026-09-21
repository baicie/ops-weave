# inventory

Entity / Relation / Observation / ExternalLink / 字段融合。

当前提供按可信租户查询的 `GetEntityUseCase`，以及标注为非生产的内存库存。Entity 当前视图来自 Observation 映射，不是客户端传入的 tenant。

尚未提供 PostgreSQL 持久化或字段权威融合引擎。

公开契约位于 `api/`；领域规则位于 `domain/`；用例位于 `application/`；实现适配位于 `infrastructure/`。
