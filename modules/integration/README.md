# integration

Connector SPI、同步任务、游标、映射和受控写入端口。

第一版 `PipelineDefinition` 绑定 Zabbix Host：Source → Parse → Map → Validate → EntityResolve → WriteObservation，并且只允许单链。同步写入 `SyncRun` 与游标。来源对象只要有外部 ID 就进入 presence，映射拒绝单独计数。未走完的扫描不会对账。完成态是 `offset-scan-attempt`。失败原因是稳定错误码，不把异常原文返回给调用方。

Zabbix Item 只映射到 `MetricDefinition`。`system.cpu.util[,user]` 成为 `host.cpu.usage.user`。未实现 History、Trigger、Preview/Replay、可视化编辑器和 Integration Copilot。

公开契约位于 `api/`；领域规则位于 `domain/`；用例位于 `application/`；实现适配位于 `infrastructure/`。
