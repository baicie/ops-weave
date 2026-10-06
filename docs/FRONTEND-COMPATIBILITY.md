# 前端兼容性基线

2026-10-06 第137节：日志重放增加独立command/execute/plan/receipt/page/execution/data闭合Schema，API/Web与V057同步部署；数据页沿用既有日志窗口协议。WorkflowLogReplaySection独立持有固定版本、原UUID、取消、私有正文/游标和待确认门禁；WorkflowReplayPanel共用于指标/日志且只展示。首次展开仅元数据，正文每页50条由明确操作读取，身份/父范围变化清除；当前完整性不修改原回执。完整来源键、128字符tenant及规范UTC纳秒位置校验保持；动作自然宽度/窄屏换行，不改公共Shell。见[契约](../contracts/workflow-log-replay.md)。

2026-10-06 第136节：历史指标重放增加独立command/execute/plan/receipt/page/execution Schema，API/Web与V056同步部署。浏览器可信会话识别显式workflow.replay权限，默认权限不扩大。WorkflowMetricReplaySection持有请求、原UUID、取消和关闭/版本门禁，Panel纯展示；展开仅元数据一次读取，检查范围不输出。原UUID回查、404原样重发和UNKNOWN原输出核验分开；空批次摘要也校验。局部明暗/三宽度样式，原历史及持续任务协议保持。见[契约](../contracts/workflow-metric-replay.md)。

2026-10-06 第135节：新增独立历史Page/私有Cursor及GET接口，需要API/Web与V055同步部署，原诊断13/14/15字段和预览详情保持。运行页默认读目录/固定版本一次、视图切换不重读；目录外固定直链走原版本GET，不选最新替代。严格校验同快照/数量/连续offset/顺序/唯一UUID，失败保留原页，403清理后无自动重试；凭据明确变更才重新读取。共享WorkflowDiagnosticDetail仅展示，局部样式不改公共Shell。见[协议](../contracts/workflow-history.md)。

2026-10-06 第134节：质量阈值增加独立配置/回执/状态契约及 V054；原质量、诊断、样本和按版本任务协议保持。API/Web与迁移同步部署，新Web在旧API不可用时保留失败，不自动启用规则或退回Mock。共享请求容器与纯Panel沿用既有主题、会话与关闭门禁；403清理全局私有页面。客户端与服务器校验原UUID、摘要、CAS、固定版本、精确区间、去重与缺失状态，不将未达阈值冒充总体健康。见[阈值契约](../contracts/workflow-quality-alerts.md)及验证报告§134。

2026-10-05 第133节：诊断可选 dispatch 与数值 queueWaitMillis 成对升级，旧13字段和仅来源统计14字段保留，历史不回填。旧客户端会拒绝新增调度字段，API/Web须同步部署；沿用 V049，无迁移或依赖升级。新增纯展示组件，既有导航、主题、请求及可信会话门禁保持。见[调度观察](../contracts/workflow-dispatch-diagnostics.md)与验证报告§133。

2026-10-05 第132节：新诊断可选 sourceRead 严格闭合解析，新增来源失败分母与纯展示组件。旧公开记录省略该字段，旧存储仍可读，历史不回填；旧客户端会拒绝新增字段，API/Web须同步部署。列表仅在有实测值时增加来源列，转换、来源及输出数量分开。既有导航、主题、页面容器和依赖保持，排队时长缺少观测仍为空。见[契约](../contracts/workflow-source-diagnostics.md)及验证报告§132。

2026-10-05 第131节：单次指标/日志UNKNOWN增加独立终止确认协议，需要API/Web同步部署和既有PG的V053；原样本证明/数据协议不改。共用请求容器与纯Panel，默认一次元数据读取，丢失回执保留原UUID，明确404后原样重发，待确认折叠/版本/关闭门禁、会话清理与隐藏取消保持。终止只移除原核验入口，原UNKNOWN和授权回读保留。无依赖升级或公共Shell变化。见[协议](../contracts/workflow-sample-recovery.md)与验证报告§131。

2026-10-05 第130节：持续日志按版本状态新增可选uncertainBatchIds，闭合校验与原batch体分开；旧客户端会拒绝新增字段，API/Web须同步升级，无依赖升级。运行质量原UUID详情允许打开对应恢复入口，原命令待确认时不因关闭详情丢失。67个不同最终相关Fixture场景分整组66通过与修正专项1通过；未重跑最终整组67。明暗/1440/1024/390与本机实际Schema只读验证见§130。

第129节：主机新版本明确启动，不恢复旧版本；日志读取服务器resumeAllowed，旧拒绝没有证据时禁用恢复。原代数/UUID/会话失效和历史只读保持。129相关浏览器场景、TypeScript/build实际通过；共享外壳未修改。

2026-10-05 第128节：新增两份恢复Schema、ABANDONED任务状态和按版本状态可选control元数据，平台/Web须同步部署及V050复核。原未带参数状态接口保持形状。请求容器与纯展示分开；显式确认、原UUID/摘要/版本/批次/代数严格匹配，未知无自动重发，原404后可原样重发或明确放弃本地确认（服务器命令未取消）。待确认阻止关闭/版本切换/质量容器折叠，身份清理、隐藏取消和迟到隔离保持。124相关协议Fixture及六种主题/宽度通过；最终主平台联测/部署待Docker恢复。见[协议](../contracts/workflow-recovery.md)。

2026-10-05 第127节：固定连接主机新增检查统计入口，复用既有请求容器和纯展示组件；旧未固定连接任务不显示该入口。HOST_SCAN展示本次来源分页，from/till和单位异常保持null。公开协议不变，新增私有stored Schema不接受为HTTP响应；严格拒绝私有字段、超过5条或假窗口。69相关浏览器场景及六种真实宽度/主题通过，原控制/质量行为保持。见[协议](../contracts/workflow-diagnostics.md)及验证报告§127。

2026-10-05 第126节：四份闭合检查Schema和新增只读diagnostics路由需平台/Web同步部署及V049前迁移；原质量/任务/控制/输出协议不改。WorkflowDiagnosticsSection持有会话和取消，Panel仅展示；首次展开一次、显式刷新/原UUID、失败无自动重试、403清私有状态、隐藏取消和迟到隔离。PARTIAL未检查不推算成功，UNAVAILABLE数量null。77个不同关联浏览器场景，最终检查20+质量19及六种实际主题/宽度通过。见[契约](../contracts/workflow-diagnostics.md)和验证报告§126。

2026-10-05 第125节：增加质量观察的四份闭合Schema和只读API，平台与Web需同步更新，旧任务/样本/控制协议不变。请求容器与纯逐批表格分开，首次展开一次、无隐式重试、403清会话、完整UUID查询、隐藏取消/迟到隔离；分母缺失或0显示“—”，不累计重叠窗口。59个不同关联场景已执行，质量最终19及真实六种宽度/主题通过。见[契约](../contracts/workflow-quality.md)及验证报告§125。

2026-10-04 第124节：新增持续LOG状态/控制/证明/50条分页API与已发布固定来源入口，Web和平台需同步更新；原5条样本API、来源固定版本和默认依赖不变。状态、批次及授权公开/私有Schema分开，原控制摘要与纳秒位置严格校验。76个不同关联浏览器场景、六种真实宽度/主题和TS/build实际通过；未宣称生产连续日志验收。见[契约](../contracts/workflow-log-streams.md)与验证报告§124。

2026-10-04 第123节：完整LOG窗口仅为服务器内部端口和数据契约，未改变公开样本API、5条上限、现有客户端或页面入口；没有前端依赖更新。TS/build与46项日志关联浏览器实际通过，生产连续采集功能未宣称完成。见[内部契约](../contracts/workflow-log-windows.md)及验证报告§123。

2026-10-04 第122节：新增ZABBIX_LOG/log pin、来源清单、四字段源样本命令和SOURCE_LOG_SAMPLE能力。API/Web需同步更新，旧来源摘要/手工回执保持，无依赖升级。复用输入抽屉和输出组件，来源/目的权限分开，正文不由浏览器上传，默认一次清单读取、身份与原请求门禁保持。实际250关联/29专项及四种实际只读页面主题/宽度通过（专项重叠）。见[来源契约](../contracts/workflow-log-sources.md)和验证报告§122。

2026-10-04 第121节：持续指标状态增至600点、增加maxHistoryRequests/lookbackSeconds；批次增加成对补采引用和新增点计数。API/Web须同步更新，旧前端的60点闭合校验会拒绝新状态；既有流程定义、控制请求与16字段存储证明保持兼容，无依赖升级。实际221关联及16专项通过（重叠），四种实际主题/宽度只读展示通过。见[契约](../contracts/workflow-metric-streams.md)和验证报告§121。

2026-10-04 第120节：新增固定手工LOG批次的封闭请求/回执/能力/正文/历史契约及独立log.read/log.write。API/Web须同步开放新入口，日志库明确配置后可用；旧工作流/实体/指标格式保持，没有依赖升级。请求容器管理原命令和身份，纯展示组件转义正文，当前回读与原确认分开；实际214关联浏览器及17日志专项通过（重叠），四种实际主题/宽度页只读核对通过。见[契约](../contracts/workflow-log-output.md)及验证报告§120。

2026-10-04 第117节：新增固定指标持续窗口的独立请求容器与纯展示组件，旧样本/实体运行契约保持，无依赖升级。API/Web须同步部署新入口；实际验证见报告§117。

2026-10-04 第116节：固定Host资产扫描加入公开/私有独立Schema、RESUME控制回执和元数据查询。旧Task/Execution与START/STOP格式保持，API/Web需同步部署才能开放新入口；无依赖升级。WorkflowRuntimeSection持有请求，WorkflowHostScanResults只展示。实际检查见验证报告§116。


2026-10-04 第115节：增加闭合固定指标输出/历史/点回读契约，无依赖升级。WorkflowMetricOutputSection持有会话和原命令，WorkflowMetricOutputPanel只展示。客户端核对系列、原命令和匹配点的批次摘要；未知没有自动POST/GET，显式原404才可原内容重发。实际TS/build、129关联浏览器及最终13专项通过；真实页面只读回读5点。旧实体运行和控制契约保持。见[契约](../contracts/workflow-metric-output.md)和验证报告§115。

2026-10-04 控制接口同步升级：启动/停止必填requestId，返回原受理Receipt；Web/API须成对部署，旧Task响应不再接受。现有Task/Execution格式兼容，无依赖变更。实际TypeScript/build、116项关联浏览器与最终51项整份工作流复验通过；未知结果、原回执与当前状态分离及旧代数停止见[控制契约](../contracts/workflow-runtime-control.md)和验证报告§114。

日期 2026-10-01。当前控制台运行时是 React 19 + shadcn/ui。2026-09-21 的 Zeus 组合已不再使用，记录留在文末升级历史。

升级时：先在本清单写目标版本与原因 → 用锁文件安装 → typecheck / 生产构建 → 再跑受影响的 Playwright。CI 不以本机 `link:` 或 `workspace:` 目录代替 registry 产物。

## 当前组合

版本以 `apps/web-console/package.json` 的固定版本和根目录 `pnpm-lock.yaml` 为准。2026-09-28 本机 `pnpm install` 后，`tsc --noEmit` 与 `vite build` 通过。内置浏览器抽查了开始页、资产会话、主题、搜索、数据源失败提示、关系模型空状态和 390px 导航抽屉。该次迁移未跑Playwright。2026-10-01界面改版未改依赖，最终全量276项HTTP Fixture回归、类型检查和生产构建通过；同步修复X6重建残留与普通滚轮行为。详见VALIDATION-REPORT §77。

| 项 | 版本 |
|---|---|
| `react` / `react-dom` | 19.3.0 |
| `@types/react` / `@types/react-dom` | 19.3.0 |
| `vite` | 8.3.1 |
| `@vitejs/plugin-react` | 6.1.1 |
| `typescript` | 7.0.2 |
| `tailwindcss` / `@tailwindcss/vite` | 4.3.3 |
| `shadcn` | 4.21.0 |
| `radix-ui` | 1.6.7 |
| `@playwright/test` | 1.63.0 |
| pnpm | 10.34.3 |

组件源码在 `src/components/ui`。`select`、`checkbox`、`dialog` 保持原生元素。X6 与 G6 仍按需动态加载。

页面布局与组件抽离遵循[前端开发规范](development/frontend-guidelines.md)，入口约定见apps/web-console/AGENTS.md。共用页头、内容容器、查询工具栏与统计组件位于src/components/PageLayout.tsx；不包含API或权限决策。

2026-10-02 §89未升级依赖：沿用X6的HTML节点、端口连接和HTML拖入，分支图使用避障连线，图定义/布局由React统一管理；算子库、连接表单、运行面板独立展示，懒读取与取消由运行容器负责。最终322项浏览器Fixture回归实际通过（含1440/1024/390px、明暗主题、真实端口鼠标连线和待确认回执）；TypeScript/生产构建实际成功。实际Java/PG运行范围另见VALIDATION-REPORT §89，不能以Fixture证明厂商持续消费或生产身份已验收。

命令（仓库根目录）：

```bash
pnpm install --frozen-lockfile
pnpm typecheck:web
pnpm build:web
pnpm --filter @opsweave/web-console exec playwright install chromium
pnpm test:web
```

## 历史：Zeus 组合（已退出）

日期 2026-09-21。下面是当时用过的发布产物，不是当前运行时。

| 项 | 版本 | 来源 | lockfile integrity（sha512） |
|---|---|---|---|
| `@zeus-js/zeus` | `0.1.1-beta.2` | npm registry | `3A63TzcL1Gvi4tKw017hY8p08B/uqsQeuxYkibK0scUEeXkByQTBwsdvVMOl6ZAnrGlx5yCqHDLfsCcTpUcqvg==` |
| `@zeus-js/vite-plugin` | `0.0.4` | npm registry | `OY6cCK+hV9AMloSxejdjExmRzLUrvkUv5FAO7T2sHZZ5XAGOVDcolNbu4niiL4pWHx6nHAg66rgpgzcMyNhVjw==` |
| `@zeus-js/compiler` | `0.1.0` | 由 vite-plugin 拉取 | `3jDH3+zu+1mywjvSNsr6td1o0P9aNP4BgK/+Fr6nZs27YIbvNJ7yGFW5vE6MyBRXJaKYr/CCcF+UvViLT+7y/g==` |
| `@zeus-web/ui` | `0.1.0-beta.4` | npm registry | `nbLl3riisA40FgqXEJcPEaVvIZTBBxS0Mk4Ib9L6hyCWdCme7bX08/MaNGusqwPXQ8Z1qmyTbj795Rm5LQk81Q==` |
| `@zeus-web/button` | `0.1.0-beta.4` | npm | `ProxOyDP7ZzpoWzArPd2djdXnCx3wtiYrAFV5Qc76NG+JQLKXLEVnPTwj94B8Z1WoQSCckXlBYN0U/vmetGuvQ==` |
| `@zeus-web/input` | `0.1.0-beta.4` | npm | `c5uYmm+PrRoRdTxMKnJJhlr6Ti1enhhstiAt7SKtCVV96yJ+D5wK5LyuesJ3Eu61thU6HAesMKu/91GVQLb9uw==` |

当时的编译器限制（`children`、`array.map`、`<For>` accessor）只解释旧源码，不适用于现在的 React JSX。

## 未纳入本基线

- `@zeus-web/chat`、`@zeus-web/agent-console`、data-grid
- 生产 OIDC/BFF
- 中文输入法组合输入、完整键盘/焦点矩阵
- 本轮 Playwright 全量回归

## 升级记录

| 日期 | 提交 | 变更 | 结果 |
|---|---|---|---|
| 2026-09-21 | `e5b5827` | React → Zeus 0.1.1-beta.2 + Zeus UI 0.1.0-beta.4 | 本地 typecheck/build 与诊断 Demo 手工路径通过；Actions run 35594155351 success |
| 2026-09-21 | `b5404a8` | Playwright 诊断页；`wc/auto` 注册 | 本地 7 passed；Actions run 35600475743：contracts/rust/java/web success |
| 2026-09-28 | 工作树，未提交 | Zeus → React 19.3.0 + shadcn/ui 4.21.0 | 本机 `tsc --noEmit` 与 Vite 8.3.1 生产构建通过；内置浏览器抽查通过；Playwright 未跑 |


第118节新增周期采集纯展示/请求容器，复用已发布固定Host工作流运行面板。默认首次一次GET、运行轮询、未知原控制查询、403/身份清理和窄屏明暗验证见验证报告§118；单次扫描控制保持兼容，周期抢占和停止由服务器校验。


第119节：实体/关系目录列表、字段全称与固定版本链接、发布兼容性差异和授权引用复用现有抽屉与主题变量；表格局部滚动，完整标识换行。纯展示组件与 HTTP 容器分离，身份清理/取消/失败显式重试保持，多页签跨页面关闭旧只读抽屉。实际结果见验证报告§119，截图保存在仓库外；检查通过不代表模型迁移或运行任务自动升级。
