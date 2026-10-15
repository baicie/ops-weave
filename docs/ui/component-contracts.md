# UI Component Contracts · UI-COMPONENT-001

## 组件层级

| 层 | 文件候选 | 职责 | 禁止 |
|---|---|---|---|
| Shell | `app/AppShell.tsx` | 主导航、顶栏、项目/会话上下文 | 直接处理 Trace/Evidence 查询 |
| Primitives | `shared/ui/{Button,Input,Dialog,Table}.tsx` | 基础交互和一致 Token | 内建业务 API、独立色彩方案 |
| Patterns | `shared/patterns/{PageHeading,StatusView,DetailDrawer,DataTable,TimeRangeBar}.tsx` | 可复用布局和状态呈现 | 自动提升未知结果为成功 |
| Domain UI | `features/investigation/{EvidenceTimeline,HypothesisList,ToolRunList}.tsx` | 业务含义明确的展示 | 授权、服务调用、跨域业务写入 |
| Query/ViewModel | `features/investigation/model/*` | typed adapter、状态/查询适配 | 将 Principal/tenant 从 UI 文本拼到请求 |
| Pages | `pages/investigation/*` | 页面路由、数据容器组合 | 巨型的所有功能都在一个 TSX 文件 |

## 强制设计的复合组件

- `StatusView`: state + title + reason + action（可选）+ source/dataMode；必须覆盖 Error/Partial/Stale/Forbidden。
- `MetricChart`: series, unit, timeRange, aggregation, dataMode, gaps, samplingWarning，不能只传 x/y 后让组件猜单位。
- `EvidenceRow`: observedAt, source, kind, provenance, availability, evidenceId, navigationAction。
- `HypothesisCard`: title, state, supportEvidenceIds, contradictEvidenceIds, untestedChecks, uncertaintyText。
- `PageHeading`: breadcrumb, h1, taskContext, primaryAction?, secondaryActions?，有且仅有一个 primary slot。
- `DataSourceWizard`: step state machine、preview status、draft persist、permissions。

## 新组件评审要点

1. 组件在 2 个独立场景复用才进入 shared，单用途不要过早抽象。
2. props 优先业务语义，避免几十个 styling boolean（如 `blue`, `small2`, `noPaddingX`）。
3. 单个源文件超过约 350 行为 code review 提醒，不是自动失败；高状态组件先抽 data controller / presentational component。
4. 共享 UI CSS 只引用 Token；禁止 `!important` 作为常规布局策略。
5. 表格排序和日期格式由 typed adapter 管理，不在 JSX 中散布不一致的 `new Date(...).toLocaleString()`。
6. 需要对 `loading`, `error`, `partial`, `stale`, `forbidden`, `ready` 做交互与快照验证。
7. 旧版已有 shadcn/radix 组件可以作为实现基础，新 UI 使用统一封装，不因为换皮肤重写成熟基础能力。
