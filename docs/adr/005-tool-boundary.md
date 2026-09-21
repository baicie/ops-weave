# ADR-005：原生领域 Tool 为主，MCP 在边界适配

状态：建议基线 · 日期：2026-09-21

## 决策

内部应用契约使用 REST/必要时 gRPC；MCP 用于工具互通；不把 Shell 当默认模型接口。

## 代价与后续

工具权限在模型外执行，远程 MCP 元数据和返回内容按不可信输入处理。

关联：`docs/architecture/v4-design.md`。
