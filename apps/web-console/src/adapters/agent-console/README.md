# agent-console adapter

目标：把平台 Diagnose / AIRun 事件转换成可展示的运行模型。

当前控制台使用 React 与 shadcn/ui。未依赖 `@zeus-web/agent-console` 或 `@zeus-web/chat`。
这两个包仍未在本仓库联调，不能标为已接入。

禁止传入：Zabbix 地址、模型 Key、租户授权、MCP URL、平台 Token。
