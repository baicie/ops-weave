# catalog

实体类型、指标定义、Capability 与语义规范。

这是逻辑模块，不是独立微服务。没有实现的功能不得通过返回假数据冒充完成。

公开契约位于 `api/`；领域规则位于 `domain/`；用例位于 `application/`；实现适配位于 `infrastructure/`。

第68节已实现ModelDefinition/ModelPreview/CatalogRules、受权ModelCatalogService与显式内存适配。PostgreSQL/HTTP/JSON适配在platform-api，内置包和Wire契约在contracts。模型定义与指标语义不等于资产/关系实例或实时采样；详见[模型契约](../../contracts/model-catalog.md)。
