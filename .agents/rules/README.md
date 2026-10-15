# OpsWeave 通用工程规则

这里是所有参与本仓库工作的 Agent 共用的强制规则，使用普通 Markdown，由根 `AGENTS.md` 要求按下表读取并遵守。目录本身不假定任何编辑器会自动加载规则。

| 规则 | 必须读取的任务 |
|---|---|
| [00-product-boundary.md](00-product-boundary.md) | 所有仓库任务 |
| [10-ui-design.md](10-ui-design.md) | 前端页面、组件、布局和样式的设计或修改 |
| [20-ui-interaction.md](20-ui-interaction.md) | 前端页面、组件和交互的设计或修改 |
| [30-observability-visualization.md](30-observability-visualization.md) | Incident、Metric、Trace、Log、资源关系、Evidence、调查或 RCA 的呈现 |
| [40-ui-qa.md](40-ui-qa.md) | UI 实现、视觉或交互修改、视觉审查任务的交付 |

Rules 定义必须满足的工程约束；`.agents/skills/` 定义专项工作的执行流程；`docs/ui/` 提供详细设计规范和验收标准。三者一起使用，均须遵守根 `AGENTS.md` 的身份、安全、契约和真实验证边界。
