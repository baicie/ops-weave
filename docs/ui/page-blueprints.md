# 0.1.0 页面蓝图 · UI-PAGES-001

设计目标仅围绕三张关键页面；所有字段都是**目标页面与契约建议**，不是现有 API 功能承诺。页面设计前先确认需要的数据已存在；没有则允许使用显式 Fixture Preview，不得伪装已联通。

## 1. Incident List（值班首页）

**决策**：哪个故障现在应该处理？

```text
[Faults]    最近 24h · 搜索 / 服务 / 严重级别 / 状态       [刷新]
——————————————————————————————————————————————————————————————
 Critical / High 计数简览（不超过 2 项，包含查询时间）
——————————————————————————————————————————————————————————————
Sev | Incident / affected services | Start | Status | Evidence | AI State
... 行点击打开详情；加载更多/分页；无数据展示明确 Empty ...
```

- Primary Action：进入一个 Incident（整行主互动）；刷新为次级。
- 首屏不展示底层存储类型、全局配置、全部 Source revision、复杂模型预算。
- 异常：`partial` 表示本次查询不完整；`forbidden` 不能先显示旧数据再延迟清理。

## 2. Investigation Workspace（产品核心）

**决策**：问题最可能由何处引起？证据是什么？如何验证？

```text
← Incident List / INC-0241   [SEV-2] [Investigating]             [开始调查]
order-service · 起始时间 · 影响范围 · 证据完整度 / 当前调查状态
————————————————————————————————————————————————————————————————————————
Evidence timeline / Charts (58%)     │ Hypothesis & Agent (42%)
 ├─ 统一时间条 + 单位/时区         │ 首要候选 / 证据支持 / 反证
 ├─ Zabbix Host CPU Trend         │ 下一步检查 / 数据缺口
 ├─ APM P95 & DB Span             │ Agent Tool Step Timeline
 └─ Log/Error events             │ 报告视图 / 人工审核
————————————————————————————————————————————————————————————————————————
Selected evidence drawer: 来源、原始查询、时间窗、样本、可用性
```

- Primary Action：开始/继续 AI 调查。完成后主 CTA 改为“查看报告”，保留历史调查入口；不能并列三个蓝色主按钮。
- 情况栏：真实数据、Fixture、Stale、Partial、unknown 必须各自明显。
- 互动：图表选点 ↔ 时间线定位 ↔ Evidence 详情；Hypothesis 点击能高亮支持/反证证据；Tool 进度透明；返回后保留上次筛选和时间窗。
- 1024px：时间线占主列，调查在 Inspector/Tab；390px：概要/证据/调查三个 Tab，不要求同时渲染双栏。
- 不把 Agent 详细思考、凭据、原始系统 Prompt 或全量敏感日志呈现在 UI。

## 3. Data Source Setup（接入向导）

**决策**：如何安全配置一条有效的数据接入？

```text
Zabbix 接入                    步骤 2/6 · 配置连接
[来源]—[连接]—[Scope]—[发现]—[Mapping]—[启用]
——————————————————————————————————————————————————————————————
Zabbix URL           [verified endpoint preview]
Credential           [Secret Ref selector]  [Test Connection]
Authorization Status / Scope restriction / Last test outcome
                                      [上一步] [继续]
```

- Primary Action：继续 / 启用（根据步骤决定）；Test Connection 为次级，不隐式启动采集。
- YAML/复杂 Workflow 只在高级模式，不把技术字段/Contract Digests 暴露给新用户。
- 验证失败保留表单；认证值在 UI 不回显，连接的真实结果明确表示为“未连接/已验证/失效”。
- 成功不代表已收到数据：`Connection Valid` 和 `Data Received` 分开显示。

## 4. 共同约束

- 所有页面 1440/1024/390 三宽度 light/dark 各有设计与浏览器 QA 证据。
- 初始异步加载必须有 skeleton 或已知 Empty，不闪烁旧用户会话内容。
- `Widget` 必须处理无数据、部分数据、无权限、失效；数据页统一 UTC 存储、显示带时区的本地时间，不把本地时间字符串误当无偏移时间。
