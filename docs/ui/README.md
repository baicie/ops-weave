# Web Console UI 约束包

这组文档是 OpsWeave 0.1.0 前端工作的工程约束，服务于现有 `apps/web-console`，并为后续 `apps/web-next` 保留适配空间。

规则分为两层：

- `.agents/rules/` 是所有 Agent 共用的强制规则，使用普通 Markdown；根 `AGENTS.md` 要求按规则索引中的适用范围显式读取并遵守，不依赖编辑器自动加载。
- `.agents/skills/` 是页面设计、页面实现、视觉审查和可观测图表工作的分阶段流程。

`visual-system.md`、`interaction-contract.md`、`component-contracts.md`、`observability-visualization.md`、`page-blueprints.md` 和 `review-scorecard.md` 共同定义页面结构、状态语义、组件边界和视觉验收口径。它们不代表已有页面或后端能力已经全部实现；页面必须以实际契约和真实检查结果为准。

`templates/opsweave.tokens.v2.css` 是候选 Token，只能在明确的 `.ow-v2` 范围内试用，不能覆盖现有控制台全局样式。`templates/visual-regression.spec.ts` 是截图起点，需要按实际路由、授权和 READY 状态适配。`scripts/check_ui_static_rules.mjs` 只做无依赖的静态启发式检查，不能替代真实浏览器截图、键盘操作、可访问性检查或人工视觉审查。

需要在 Claude Code 中发现这些 Skills 时，可显式运行 `python scripts/install_claude_skills.py`；默认不会覆盖已有同名 Skill，`--force` 只应在审阅后使用。

当前仓库的基线命令是：

```bash
pnpm check:ui
```

新页面完成前，应依次使用 `opsweave-ui-design`、`opsweave-page-build` 和 `opsweave-visual-audit`；涉及 Metrics、Traces、Logs、Evidence 时间线或 RCA 图表时，再使用 `opsweave-observability-viz`。视觉门禁必须记录 1440×900、1024×768、390×844 的 light/dark 实际截图，无法运行时记录 `visual: NOT_RUN`。
