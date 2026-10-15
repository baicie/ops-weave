# UI 交互、状态与可访问性

适用范围：所有前端页面、组件和交互的设计与修改，包括 `apps/web-console/**` 及未来的 `apps/web-next/**`。

优先阅读 `docs/ui/interaction-contract.md` 与 `docs/ui/component-contracts.md`。

- 每个异步区域列出 Loading、Empty、Error、Partial、Forbidden、Stale、Fixture；禁止错误时无声变空。按钮的 Pending/Success/Failure/Disabled 状态不能仅靠颜色。
- 业务状态与请求状态分离：API 返回成功只证明请求成功，不代表故障根因已确认、数据完整或任务已落库。
- 进入新页面后首屏应有一个明确任务和主要内容；不把高级字段和流水号散布在首屏；高级诊断放 Inspector/Disclosure。
- 有危险或不可逆操作必须有清楚的确认；UNKNOWN 操作结果不可默认重发。授权变化必须清理当前缓存/选中/抽屉中的敏感数据。
- 键盘可到达所有按钮/表单/图表关键点，保留可见焦点、语义化标签、错误关联和键盘返回；需要遵守 `prefers-reduced-motion`。
- 弹层 ≤1 主层级；长日志/长标识符必须有滚动或复制策略；窄屏表格需可横向滚动并明确列标题。
