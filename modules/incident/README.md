# incident

Incident / Change / Timeline / 证据关联与状态转换。

这是逻辑模块，不是独立微服务。没有实现的功能不得通过返回假数据冒充完成。

公开契约位于 `api/`；领域规则位于 `domain/`；用例位于 `application/`；实现适配位于 `infrastructure/`。

已提供：有界 Incident/Problem/Timeline 领域对象、来源恢复合并、人工状态与乐观版本、授权列表/详情/变更用例、原子内存适配；PostgreSQL 适配留在 platform-api，V010 同事务保存来源发生索引、范围字段、快照与变更幂等记录。Java 领域不依赖 JDBC/Spring/JSON。

来源恢复不会自动关闭 Incident，未知恢复与未映射实体保留为缺口。一条来源发生建立一个初始 Incident；人工合并/拆分现在通过 V013 原子调整当前归属，保留来源归档和 actor/requestKey 回执，后续观测跟随新归属。可选 organization 版本使旧证据失效，AIInsight 由 ai-control 模块提供。V016 与 `ProblemHistoryService` 提供合并前规范化输入的受权窗口/截止/版本分页，保留首次映射并跟随当前归属，不补造旧历史；完整厂商原文、任意 asOf 投影和时间线分页仍缺。设计与本机验收见 ADR-021、ADR-024、[ADR-028](../../docs/adr/028-normalized-problem-history.md) 与对应 runbook。
