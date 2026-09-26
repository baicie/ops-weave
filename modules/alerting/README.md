# alerting

外部和本地告警规则、实例、恢复、维护窗口与抑制。

这是逻辑模块，不是独立微服务。没有实现的功能不得通过返回假数据冒充完成。

公开契约位于 `api/`；领域规则位于 `domain/`；用例位于 `application/`；实现适配位于 `infrastructure/`。

已有 `ExternalProblem` 发生/恢复/抑制与缺失状态，以及 `ProblemObservations` 的乱序/重复合并规则。`ProblemObservation` 保留合并前的不可变规范化输入、首次接收与当时实体映射；持久适配在 platform-api 的 V010/V016，不在领域引入 JDBC。没有本地规则引擎或完整厂商报文历史。读取入口及预算见 [ADR-020](../../docs/adr/020-external-problem-read.md)，历史与当前归属边界见 [ADR-028](../../docs/adr/028-normalized-problem-history.md)。
