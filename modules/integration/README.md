# integration

Connector SPI、同步任务、游标、映射和受控写入端口。

第一版 `PipelineDefinition` 绑定 Zabbix Host：Source → Parse → Map → Validate → EntityResolve → WriteObservation。`FixtureZabbixHostConnector` 必须显式打开；JSON-RPC connector 失败不会回退到 fixture，也不会把错误当成完整空快照。

未实现：Item/Trigger/History、可视化编辑器、Integration Copilot、生产游标存储。

公开契约位于 `api/`；领域规则位于 `domain/`；用例位于 `application/`；实现适配位于 `infrastructure/`。
