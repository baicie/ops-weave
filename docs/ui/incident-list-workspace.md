# 故障列表工作台蓝图

日期：2026-10-15。范围：U0，为 U2b 提供实际字段、状态和响应式设计。本文件不表示页面已按此改版或完成浏览器验收。

## Screen purpose / Primary Task

值班人员在当前身份可见的 Incident 范围中找到需要处理的记录，打开重新授权的详情，核对外部问题/恢复事实与人工状态，再明确进入只读调查。

成功路径为「应用状态筛选 → 读取一页 → 打开详情 → 核对缺口/来源 → 进入调查」，进入列表、详情或调查链接均不自动提交诊断、导入告警、改变 Incident 状态或调用模型。

## 当前入口与契约边界

- 页面：`apps/web-console/src/pages/incidents/IncidentsPage.tsx`，`#/incidents`；adapter 为 `src/api/incidents.ts`，详情的资产钻取使用 `entities.ts`。
- 当前列表只支持状态筛选和 UUID 游标分页；adapter 固定每页 25 条，顺序按 UUID 升序，不是按严重度或最近发生时间排序。
- 当前 `IncidentHeader` 没有服务名、影响范围、实际问题开始时间、Evidence 数量、AI 状态、来源 dataMode 或全量计数；首版不显示这些字段，不并行逐行 GET 来伪造完整列表聚合。
- 通用页面规范中“最近 24h/服务搜索/全局严重度计数/AI State”属于候选设计，不是本页可用查询能力。未来需要这些能力时先补独立需求与契约；本轮不扩大接口。
- 列表 `createdAt` 只能叫“Incident 创建时间”，不能冒充外部问题发生时间；发生时间在详情 `Problem.observation.occurredAt` 中。

## Priority hierarchy / 真实字段映射

| 优先级与区域 | 实际字段 | 呈现约束 |
| --- | --- | --- |
| Primary：紧凑列表 | `IncidentHeader.title/status/severity/createdAt` | 标题、人工处理状态、来源等级 0–5、创建时间；不用没有依据的事故分级别名覆盖来源等级 |
| Supporting：分页/范围 | `IncidentPage.items/nextCursor`、当前 status/after、客户端读取完成时间 | 明确本页 N 条与下一页；没有总数，不声称最新顺序或一致快照；客户端时间标为本次读取时间 |
| Advanced：列表标识 | `id/version/tenantId/storage` | UUID 与版本置于详情/依据；tenant/storage 不作用户任务标题，浏览器不能用 tenant 字段替换身份 |
| Primary：详情 | `IncidentDetail.record.incident` | 标题/状态/版本来自这次授权读取，不能直接用旧列表头替代 |
| Primary：来源与事实 | `problems[].dataMode/sourceContract/observation.title/state/severity/suppressed` | labeled-fixture 明确合成，zabbix-jsonrpc 标协议来源；ACTIVE/RECOVERED/RECOVERY_UNKNOWN 不是人工处理状态 |
| Supporting：时间 | `occurredAt/observedAt/recoveredAt/firstReceivedAt/lastReceivedAt` | 原始 UTC 分开呈现；recoveredAt=null 是未知或未恢复，不能显示“0 秒恢复” |
| Supporting：缺口 | `record.gaps` 与 `observation.gaps` | 日志未接入、变更未接入、实体映射缺失、恢复事件不可用；空列表/缺日志不能推断健康 |
| Supporting：关联对象 | `problems[].entities[].hostId/entityId` | 点击后经 `getEntity` 再授权；当前资产不等于故障发生时资产快照 |
| Supporting：时间线 | `timeline[].kind/occurredAt/availableAt/fromStatus/toStatus/actor` 等闭合字段 | 事件发生与平台可见分开；状态变更和来源事实分开，关联不等于因果证明 |
| Supporting：合并状态 | `organization?.mergedInto/version/changeId` | 已合并快照指向目标，不在旧快照继续状态迁移或开始错误对象的调查 |
| Advanced：人工状态写入 | `TransitionRequest.expectedVersion/target/requestKey` | 原版本 CAS 与幂等键保留；RESOLVED 的全部恢复事实由服务器校验，来源恢复不自动关闭 Incident |

列表响应不含来源 dataMode，因此未读详情前只能写“来源模式尚未读取”，不能默认 REAL 或用 storage=postgres 推断真实数据。Fixture 模式必须在获得实际问题详情后明显显示；不能把一次导入回执的模式扩散到全部历史 Incident。

## 实际请求与交互意图

| 意图 | 当前接口/选择 | 边界 |
| --- | --- | --- |
| 读列表/下一页 | `GET /api/v1/incidents?status=&limit=25&after=` | status 回显、页大小与游标逐项校验；无服务/时间/严重度筛选参数 |
| 读详情 | `GET /api/v1/incidents/{id}` | 当前对象授权，重新读取完整记录；不执行诊断 |
| 读关联资产 | `GET /api/v1/entities/{id}` | 当前资产的独立授权读取 |
| 进入调查 | `#/incidents/current-diagnose?incidentId={id}` | 只带读取选择，不带身份/权限/模型命令，不自动提交 AIRun |
| 查看指标上下文 | 既有 `#/metrics?entityId=...&from=...&till=...` | 由实际问题 occurredAt 得出前后 30 分钟，最大 1 小时；页面仍重新授权 |
| 人工状态迁移 | `POST /api/v1/incidents/{id}/transitions` | 只提交 expectedVersion、target、requestKey；结果未知保留原内容，不隐式换键 |
| 人工归属调整 | `#/incidents/reorganize?incidentId={id}` | 既有独立页面的合并/拆分/记录流程，列表不复刻其控制器 |
| 旧告警导入 | `POST /api/v1/integrations/zabbix/problems/ingest` | 当前服务端为 fixture-only legacy compatibility；显式兼容演示操作，非真实注册连接导入承诺 |
| 注册来源问题页 | `GET /api/v2/data-sources/{sourceId}/connection/{revision}/problems` | 只读来源窗口，不创建 Incident；不能因为已有该端点就承诺自动把真实问题导入本列表 |

旧告警导入保留在高级区，明确“兼容演示导入”。窗口为 UTC 秒、最多 24 小时/100 条，导入失败不能当空快照删除现有记录。不能用给 v1 输入真实地址或换成 v2 只读请求的方式伪造真实接入完成。

## Desktop / Narrow / Mobile wireframes

页面唯一主要互动是打开一个 Incident。列表刷新、翻页、状态筛选为次级操作，不再同时铺多个同色实心按钮。详情中的“进入只读调查”是当前选中对象唯一突出动作；状态维护收在明确的次级操作区。

```text
1440 × 900
[故障事件] 当前身份可见范围                            [刷新：次级]
[状态筛选]   本页 N 条 · 本次读取时间                     [上页/下页]
────────────────────────────────────────────────────────────────────
等级 | Incident 标题 / 创建时间 | 人工状态 | 打开详情
0–5  | 实际标题                 | OPEN...  | [查看]
────────────────────────────────────────────────────────────────────
打开后：列表主列约 60% / 右侧详情约 40%（不足时转单层全宽）
[标题 / 人工状态 / Fixture / 版本]                    [进入只读调查]
[数据缺口] [外部问题与恢复事实] [发生/可见时间线]
[高级：原标识、人工状态维护、兼容演示导入]

1024 × 768
[标题 / 筛选 / 本页范围 / 刷新]
列表保留等级、标题、人工状态；创建时间作为标题次行
详情切换为独立全宽区域或单层 Drawer，明确返回列表
关闭返回原行焦点与当前已读页，不自动刷新/导入/诊断

390 × 844
[故障事件]
[状态选择] [刷新] [分页]
单列紧凑行：等级 + 标题 / 人工状态 + 创建时间
选择 → 全宽详情
[返回列表] [标题 / 来源模式 / 缺口]
[进入只读调查]
[问题 | 时间线 | 高级]，仅切换本地内容
```

沿用现有 Shell 与主纵向滚动，Drawer 只使用单层内部滚动。列表和长标识在自身范围内布局，不让整页横向溢出；不对移动端显示不可用的假列。详情主要时间格式统一带 UTC 或明确时区，原始时间值按需查看。

## Async states / Failure matrix

| 状态 | 呈现与恢复 |
| --- | --- |
| initial | 尚未授权读取；恢复的 URL 只代表状态/游标/对象选择，没有旧数据，也不自动导入或执行 |
| loading | 稳定表头/区域与文字状态，明确当前读列表还是读详情，禁止重复操作 |
| ready | 仅实际返回页与授权详情；状态、来源恢复事实和模型调查分开 |
| empty | 列表请求成功且 items=[] 才显示“当前筛选没有可见记录”；不代表全平台没有故障或采集健康 |
| partial | 下一页、当前页与恢复游标范围明确；详情数据缺口逐项显示。nextCursor 不等于来源 partial，不能把正常分页误称采集失败 |
| stale | 原列表读取时间/旧详情仍是当时快照；人工版本冲突或来源事实变化后需明确重读。没有 TTL 契约时不伪造服务器失效时间 |
| error | 路由/游标非法、404 目标不可见、409 状态或版本冲突、429/503 分开；保留当前筛选与可安全读取的范围，不循环重试 |
| forbidden | 401/403 立即清空列表、详情、资产、导入结果和隐藏选中；迟到响应不能恢复私有数据 |
| fixture | 以实际 Problem/ImportResult.dataMode 标记；列表无模式时保留尚未读取，不能默认真实 |
| unknown-write | 人工状态请求保留原对象/expectedVersion/target/requestKey；先明确回读，不能把 HTTP 超时当保存失败再用新键提交 |

## Interaction map / Acceptance tests

- URL 仅保存 `status/after/incidentId`，拒绝重复、未知和非法参数；不增加尚无契约的搜索/时间字段，不把正文或身份写入地址/存储。
- 列表选择与业务写入分离；主行用真实 button/link 支持键盘打开，返回恢复焦点。打开详情不调用 AIRun、模型或旧导入接口。
- 列表严重度保留 0–5 原值及说明；没有约定的等级映射时不宣称 SEV1/SEV2 或自动分诊。排序说明准确，不能把 UUID 顺序显示成“最近故障”。
- 人工状态维护单独明确意图；来源恢复、人工 RESOLVED、人工 CLOSED 与根因确认不能混为一个状态。已合并快照保留真实目标与历史。
- U2b 实现后必须取得三宽度×明暗的 READY 与详情截图，另补 empty/error/forbidden/partial-gap/UNKNOWN/Fixture、长标题、键盘返回和身份变更负例。
- 实际检查分页请求/原幂等键与只读跳转，确认无逐行隐藏请求、自动导入、重复模型提交；基础检查、视觉测试和真实来源验收分别记录。
- 自审：不添加缺少契约依据的 KPI、AI 进度列、虚构影响服务、完整性百分比或工具时间线；不复制整套归属管理页面。

Open questions：当前紧凑列表改版无阻塞问题；真实注册问题进入 Incident 的整链能力需独立依据与 E1 证据，不由本蓝图补造。`visual: NOT_RUN`。

依据：[Incident Page Schema](../../contracts/schemas/v1/incident-page.schema.json)、[Incident Record Schema](../../contracts/schemas/v1/incident-record.schema.json)、[状态请求 Schema](../../contracts/schemas/v1/incident-transition-request.schema.json)、[注册问题分页](../../contracts/registered-problem-pages.md)、[调查试点蓝图](investigation-workspace.md)。
