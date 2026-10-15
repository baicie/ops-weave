# 故障调查工作台蓝图

日期：2026-10-15。范围：U0/U1，既有 `#/incidents/current-diagnose` 的局部 `.ow-v2` 试点。设计先于展示组件实现；本蓝图不是已通过截图或真实来源验收的声明。

## Screen purpose / User story

值班人员针对一个已授权 Incident 和固定 UTC 采样窗口，明确提交一次只读调查，分别回读运行记录、已保存报告和关联证据，判断哪些是观察、哪些仍需验证、还有什么数据缺口。

完成一次请求、运行成功和确认根因是不同事实。本页没有确认根因、关闭 Incident 或执行修复入口。进入页面、切换页签、展开本地说明或改变主题不启动调查。

## Priority hierarchy

| 层级 | 信息 | 呈现 |
| --- | --- | --- |
| Primary | 调查对象、固定窗口、运行或报告状态、报告摘要与不确定性 | 紧凑页头、一个主操作、报告首段 |
| Supporting | 授权证据引用、观察、待验证假设、数据缺口、来源模式和运行状态 | 两列工作区；观察与假设用明确文字区分 |
| Advanced | 请求/AIRun/AIInsight 的不同标识、Skill 固定版本和 digest、保存/失效时间、定位条件、费用明细 | 折叠区；不将租户或内部标识作为标题 |

没有 Incident 名称、严重度、起始时间或影响范围的授权响应时，只显示选定对象标识和采样窗口，不从 UUID 推测业务标题。没有连续 Metric series、Trace、Log 或工具事件数据时不画趋势、瀑布、日志健康或虚构时间线。

## 真实字段映射

契约唯一源仍在 `contracts/`；页面消费现有 `src/api/insights.ts`、`src/api/ai-runs.ts` 和 `src/api/model-spend.ts` 的严格 adapter。

| 页面区域 | 现有字段 | 语义边界 |
| --- | --- | --- |
| 对象/问题/窗口 | `DiagnosisRequest.incidentId/question/timeRange`；AIRun 的同名字段 | `knowledgeMode=current`；采样窗口不是当时已知的信息 |
| 受理与执行 | `AiRun.runId/requestId/status/statusVersion/attempt/createdAt/updatedAt/reason` | 请求标识与服务器运行标识分开；SUCCEEDED 只表示运行状态 |
| 恢复披露 | `AiRun.disclosure` | 复用现有 `reconciliationText`，不改费用或旧状态 |
| 报告摘要 | `InsightResult.record.insight.summary` | 模型生成的调查摘要，不称已确认根因 |
| 观察与假设 | `findings[].kind/statement/evidenceRefs` | 只有 observation/hypothesis；引用可定位，不造支持/反证分类或置信度 |
| 数据缺口/限制 | `insight.missingData/limitations` 与 `warnings` | 空数组只表示报告未列出，不声称来源完整或系统健康 |
| 授权证据入口 | `record.evidenceIds` | 未读取前仅有引用，不推测证据种类、时间、值或可用性 |
| 证据详情 | `EvidenceDocument.evidence.kind/summary/observedAt/availableAt/expiresAt/sourceRef`、`producerTool/queryWindow/dataModes/warnings/data` | 明确点击后按原报告范围授权读取；原时间保留 UTC；data 仅以 React 文本展示，不执行 HTML/Markdown/链接 |
| 来源 | `record.dataModes` / `EvidenceDocument.dataModes` | `labeled-fixture` 明确合成数据，`zabbix-jsonrpc` 是受控来源协议，`unknown` 仍为未知；协议本身不证明生产来源 |
| 模型 | `record.model.provider/name` | `mock-deterministic` 明确 Mock；运行或 Token 数不证明模型质量 |
| 新鲜度/版本 | `asOf/builtAt/completedAt/savedAt/expiresAt`、`skill.id/version/digest`、`incidentVersion` | CURRENT_KNOWLEDGE_ONLY 与 reference_integrity_only 保留；服务端仍负责 asOf、到期及当前授权校验 |
| 费用 | `ModelSpend.record.state/usage/estimatedMicros/reservedMicros/accountedMicros/policy` | 未上报用量/估算费用显示待确认，不补 0；配置费率估算不是供应方账单 |

同步兼容诊断直接保存 AIInsight，不伪称持久 AIRun。持久路径显式提交后先展示 AIRun，再明确读取关联 AIInsight；两条路径在高级设置中明确区分。同步请求中的 runId 是结果请求标识，不能用它假造一条运行记录。

## Desktop / Narrow / Mobile wireframes

沿用现有 Shell 与其导航宽度；本切片不更改全局壳层。工作台使用候选 Token 的 4/8/12/16/24/32 间距、24px 标题、18px 章节标题、14px 正文、13px 辅助文字。只有工作台根节点使用 `.ow-v2`；双主题在该范围定义。

```text
1440 × 900（内容区域双列）
[故障调查 / 当前知识 · 只读]                [开始调查 / 读取进度 / 查看报告]
[对象标识 · UTC 采样窗 · 实际来源/状态]
─────────────────────────────────────────────────────────────────────────
[调查范围：Incident / UTC 开始 / UTC 结束 / 问题；已有报告时可折叠]
─────────────────────────────────────────────────────────────────────────
证据（约 42%）                        发现与调查（约 58%）
引用 1 · 未读取 [读取]                 报告摘要 / 当前知识限制
引用 2 · 未读取 [读取]                 观察 → 实际引用
选中证据：来源、三种时间、警告           待验证假设 → 实际引用
原始数据 [按需展开]                    数据缺口 / 限制
─────────────────────────────────────────────────────────────────────────
[高级：不同标识 / 固定版本 digest / 模式 / 用量与费用]

1024 × 768（单主列）
[紧凑页头 · 状态 · 单一主动作]
[概要 | 证据 | 调查]  ← 页签只切换本地呈现，不重新查询
[当前内容；证据详情在证据区域内展开，不叠加模态层]
[高级折叠区]

390 × 844（单列）
[故障调查]
[状态 / Fixture / 当前知识]
[主动作，可换行]
[概要 | 证据 | 调查]
[当前页签内容；输入单列；完整标识换行]
[高级折叠区；长正文仅在自身容器滚动]
```

主工作区沿用页面纵向滚动。选中证据以内联 Inspector 呈现，不增加遮挡或第二层弹窗；长 data 只有局部有界滚动，内容作为文本可选择。窄屏不要求同时展示两列。原始证据不自动读取，不持久化到浏览器存储。

## Components

- `InvestigationWorkspace`：纯展示与本地页签/折叠；接收 controller 的业务值和显式回调，不请求网络、生成请求标识或决定权限。
- `InvestigationFindings`：摘要、观察/待验证假设、实际引用、数据缺口与限制；只有一层工作区表面。
- `InvestigationEvidence`：未读取引用列表与已授权证据 Inspector；缺失的元数据明确未知。
- `InvestigationRunSummary`：既有运行状态、有限时间信息与只读导航，复用 `RunStatusBadge` / `reconciliationText`；不编造工具步骤。
- `InvestigationAdvanced`：原标识回查、同步兼容模式、Skill/version/digest 与模型费用；费用读取需明确操作。
- 复用 `PageHeader` / `PageBody` 与现有 `Button` / `Input` / `Textarea`。单用途组件留在 investigation 目录，不提前提升到 shared。

## Async states

请求状态、数据模式、新鲜度和业务运行状态分别呈现，Fixture 可以同时 loading/error/partial。

| 状态 | 可见信息 | 行为与失败路径 |
| --- | --- | --- |
| initial | 选择对象/窗口与提问；未取得报告或证据 | 显式开始；没有占位 KPI 或默认 0 |
| loading | 带文字的忙碌状态，保留区域结构 | 禁止重复提交；controller 取消/隔离迟到响应 |
| ready | 已保存摘要、观察与假设，原窗口/来源 | 逐引用读取；不宣称根因已确认 |
| empty | 已读取报告但未列出发现，或已授权查询未找到结果 | 明确无结果；不渲染健康或完整性 |
| partial | 报告列出的数据缺口/限制、原样 warnings | 不隐去已有缺口，不将引用数量当完整性评分 |
| stale | controller 失效/过期反馈 | 清理或屏蔽旧结果，明确重新授权读取或新调查 |
| error | 明确错误文本；原请求待确认与只读失败分开 | 保留输入，显式回查，不循环重试 |
| forbidden | 身份不可用/权限拒绝 | controller 清理报告、证据与费用；展示重新建立会话提示 |
| fixture/mock | 实际 dataModes 或 provider 标记 | 本地/合成链路，不用于真实质量评估 |
| unknown-write | 原命令已保留、受理/保存结果未确认 | 先按原标识查询；controller 确认允许后才显式原样重发，锁住改输入/新提交 |

## Interaction map

| 意图 | 行为 |
| --- | --- |
| 开始调查 | 唯一主 CTA；controller 固定输入及原请求标识，服务端校验身份/范围/时间/预算/Skill |
| 读取进度 | 明确只读 AIRun GET；不自动轮询，不触发恢复/模型 |
| 读取/查看报告 | 先读取授权 AIInsight；已加载时定位本地报告区域，不再请求 |
| 读取证据 | 仅当前报告的实际 evidenceId；窄屏切至证据页签显示结果 |
| 页签/折叠/主题变化 | 只改变呈现，不请求数据、不清理未知命令 |
| 原标识回查/原样重发 | 高级区区分 AIRun 和 AIInsight；未知提交先回查，重发由 controller 的显式门禁决定 |
| 查看运行历史 | 既有 AI 运行页面的只读导航；离开不取消服务器运行 |
| 准备新调查 | 已有明确回执后才清空本地选择；待确认时禁用，身份改变仍强制清私有状态 |

键盘沿自然 DOM 顺序进入主动作、页签和证据按钮。页签使用真实 button 与 aria-pressed，原始数据用原生 details/summary；焦点可见。没有弹窗焦点陷阱；读取证据后不会自动转移焦点或请求其它引用。所有长标识允许换行。

## Acceptance tests / 自审

1. 在真实浏览器取得六组 1440/1024/390 × light/dark 的 READY 截图，另覆盖初始、Fixture/Mock、缺口、错误、拒绝/失效、未知提交和展开证据。
2. 确认只有一个突出主动作，发现和证据的主次清楚，没有 Card 嵌套、横向页面溢出或关键操作遮挡。
3. Tab、主题、尺寸、展开本地数据不发请求；进入页面不提交；证据只在明确点击时读取一次。
4. 未取得证据前不显示伪造的时间/类型/数值；文本中的 HTML 不执行；缺失用量不显示零。
5. 请求标识、AIRun 与 AIInsight 分别回查；超时保留原命令，未查询前不允许重发；身份变化清空私有数据，迟到响应不能恢复。
6. 运行完成不等于根因成立；观察/假设分类来自 findings.kind；只显示 reference_integrity_only 的引用校验边界。
7. TypeScript/build、受影响 Playwright、静态启发式与仓库基础检查分别记录实际结果，不能互相替代。视觉评分需无 P0/P1 且至少 85。

自审决定：首版采用引用列表和纯文本证据，没有连续趋势或虚构 Tool Timeline；主要任务由报告状态驱动；完整 ID/digest 下沉高级区。当前没有阻塞蓝图实现的未决问题。真实来源/生产身份、人工质量与试点门禁仍独立待验。

`visual: NOT_RUN`（蓝图阶段；集成后的真实截图与整改另在验证报告记录）。
