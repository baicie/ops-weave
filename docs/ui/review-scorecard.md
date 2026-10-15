# UI Visual QA Scorecard · UI-QA-001

## 质量门禁

每张关键页面最高 100 分。**>=85 分且没有 P0/P1 未解决问题** 才允许声称通过“UI 视觉门禁”。评分只是一种审查辅助，不等于外部用户满意度或生产质量证明。

| 维度 | 权重 | 审查证据 |
|---|---:|---|
| 信息层级与任务聚焦 | 20 | 几秒内知道主任务、一个突出主 CTA、不需要理解内部 API |
| 密度、排版与布局 | 20 | 没有 Card 套娃、重边框、文字截断、滚动错位 |
| 视觉一致性 | 15 | 统一字号、控件、间距、Token、状态颜色 |
| 交互完整度 | 20 | 键盘、操作反馈、异步/错误状态、返回导航 |
| 数据语义与可信度 | 15 | 时间/单位/来源/缺失/Fixture/假设明确 |
| 响应式与主题 | 10 | 1440、1024、390 下 dark/light 可用 |
| **合计** | **100** | **须有真实浏览器截图/测试** |

## P0 — 任何一项立刻失败

- 误把 Mock/Fixture 标成实时数据；误将 0 作为 No Data；AI 结论把猜测伪装成确认。
- 已撤权对象仍可见，敏感数据泄漏；某条关键业务操作造成无法追踪的重复写入。
- 无法完成当前页面的主要用户任务。

## P1 — 修复后再验收

- 1440/1024/390 任一要求视口有明显遮挡、横向溢出或不可达的关键详情。
- 必须状态中的任一个缺失（特别是 Partial/Error/Forbidden）。
- 无可见焦点、键盘无法到达关键操作；文本/关键控件对比度明显不足。
- 视觉主次模糊、过多等权按钮或深层 Card 嵌套使用户必须多次探索才找到主任务。

## P2 — 视觉 polish

- 不同页面间按钮尺寸、边距和字重小幅偏差；辅助图标冗余。
- 次要空白、行高、响应式微调。

## 截图审查矩阵

| 视口 | Light | Dark | 附加测试 |
|---|---|---|---|
| 1440×900 | 必须 | 必须 | 工作台主要信息是否完整 |
| 1024×768 | 必须 | 必须 | Inspector 退化/边界和布局 |
| 390×844 | 必须 | 必须 | 只读路径/表格滚动/单手操作 |
| 1920×1080 | 推荐 | 推荐 | 大屏图表密度 |

每个视口的截图至少包含 READY；必要时另截 EMPTY/PARTIAL/ERROR/STALE/FIXTURE、键盘焦点/展开面板。

## 审查记录模板

```markdown
### Visual QA Report
- Commit / URL / browser / source mode:
- Screenshot paths: 1440-light / 1440-dark / 1024-light / 1024-dark / 390-light / 390-dark
- Score: hierarchy__/20, layout__/20, consistency__/15, interaction__/20, fidelity__/15, responsive__/10, total__/100
- P0/P1/P2 issues: (each with evidence + fix + recapture)
- Test commands actually run:
- Not run and reason:
- Final gate: PASS / FAIL / NOT_RUN
```

注意：`scripts/check_ui_static_rules.mjs` 是语法启发式，并不能代替截图、对比度检查、真实交互测试和人工评审。
