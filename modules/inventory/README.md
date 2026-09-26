# inventory

Entity / Relation / Observation / ExternalLink / 字段融合。

当前提供按可信租户查询的 `GetEntityUseCase`、授权分页/观测历史、人工字段权威/撤销，以及人工核对UUID登记。持久库存使用PostgreSQL；`InMemoryInventoryStore`只在显式memory模式使用。Host扫描以来源租约保护写入和完整快照对账。

PG显式CMDB快照按已登记UUID定位、保留输入/不可变观测并生成待审字段；新鲜第二来源可保护主来源已缺失的资产。部分或失败批次不对账，过期/撤销在读取及筛选时生效。人工来源绑定更正需要新观测/双方版本/目标pin、原字段先撤销，同事务保留旧归属审计，不迁移历史记录。接口和约束见ADR-035/036/037及contracts/source-snapshots.md、source-binding-corrections.md。尚无真实CMDB API、后台多源调度、已有实体合并/主来源迁移、通用权威规则或行级安全。

公开契约位于 `api/`；领域规则位于 `domain/`；用例位于 `application/`；实现适配位于 `infrastructure/`。
