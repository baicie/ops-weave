# agent-console adapter

目标：把平台 Diagnose / AIRun 事件转换成 Zeus UI Agent Console 的展示模型。

当前未依赖 `@zeus-web/agent-console` 或 `@zeus-web/chat`。这两个包仍是 headless / 待验收；
本仓库只提交诊断页已验证的 Zeus + `@zeus-web/ui` 原生入口。

禁止传入：Zabbix 地址、模型 Key、租户授权、MCP URL、平台 Token。
