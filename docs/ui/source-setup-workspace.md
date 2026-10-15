# 数据源设置工作台蓝图

日期：2026-10-15。范围：U0，为 U2a 提供可实施的信息层级与实际契约映射。本文件是设计交付，尚未实现页面或运行本页视觉验收。

## Screen purpose / Primary Task

运维人员为一个已授权来源选择登记地址、固定凭据版本和对象范围，验证当前配置，再明确进入固定版本的清洗编排与执行入口，能够分别确认「配置已保存」「授权读取通过」「已发布」「任务已启用」「业务输出已确认」。

成功不是出现一个绿色“已连接”标签，而是每一步都有属于同一来源/固定版本的可回查结果；失败和 UNKNOWN 保留原请求，不因导航重发。

## 现有入口与边界

- 当前页面：`apps/web-console/src/pages/integrations/SourceCenterPage.tsx`，`#/integrations/sources`。复用 `SourceInstancePanel`、`SourceConnectionPanel`、`SourceCredentialPanel` 与现有 Drawer/Inspection 展示。
- 默认视图为已配置接入。接入类型、凭据管理、不可变创建回执仍是不同视图；创建回执不能作为最新实例列表，当前列表不能代替原命令回执。
- 新登记连接在实例区“新增连接”完成；类型目录中的 v1 来源确认是既有兼容入口。两条创建路径需明确标识，不能让旧来源描述符替代 v2 的地址/凭据/范围选择。
- 旅程导航是对已有独立入口的整理，不声称存在一个服务端六步向导、通用启用 API 或事务式“一键完成”。读写、发布和启停保持原门禁；页面打开、切换步骤或查询回执不启动采集。

## Priority hierarchy / 真实字段映射

| 优先级与区域 | 真实 adapter / 字段 | 呈现约束 |
| --- | --- | --- |
| Primary：实例列表 | `source-instances.ts` 的 `SourceInstance.name/description/source.kind/state/updatedAt/configurationRevision` | 名称、类型、可维护/归档与更新时间；ACTIVE 是维护状态，不能叫采集中 |
| Primary：数据来源 | `SourceInstance.dataMode`，检查/预览/执行各自的 `dataMode` 或 `origin` | fixture、labeled-fixture、MANUAL_SAMPLE 明确标记；zabbix-jsonrpc 只标协议来源，不证明生产质量 |
| Supporting：列表范围 | `InstancePage.items/truncated`（最多 20） | 当前返回范围，不能显示全租户总数；本地名称/类型/状态筛选仅作用于已读范围 |
| Primary：连接输入 | `ConnectionCommand.name/description/endpointPin/credentialPin/hostGroupIds` | 地址来自授权 `Endpoint` 目录；凭据选择精确 credentialId/revision/versionId，不能填写任意 URL/请求头/代理 |
| Supporting：连接可用性 | `ConnectionView.availability/canConfigure`，`connection.revision/createdAt` | AVAILABLE 只证明登记与密钥元数据可用，不等于网络验证通过 |
| Supporting：凭据能力 | `CredentialPage.vaultAvailable/canConfigure/secretWriteAvailable`；版本元数据与 revoked | 创建/轮换秘密按现有非受控输入路径提交后立即清空；秘密不进入 React 草稿、回执、URL、日志或快照 |
| Primary：连接检查 | `Inspection.state/check.reachable/check.statusCode` 与 `InspectionView.validity` | COMPLETED 与 CURRENT 分开；UNREACHABLE/UNVERIFIED/Fixture 不显示为真实连通 |
| Supporting：范围/发现 | `connection.hostGroupIds`；`discovery.fields/observedRecords/complete/scope/scanConsistency` | Host 发现仅第一页、最多 5 条与 5 个字段；字段存在不证明全来源覆盖 |
| Supporting：指标发现 | `metricDiscovery.items/complete/scope`；`metricPage.manifest/offset/items/complete/nextOffset` | 单页至多 20；CURRENT 不等于全量完整，保留 manifest 到期与分页限制 |
| Primary：工作流固定版本 | `workflows.ts` 的 `Entry.definition/source/target/state/preview/digest/editVersion` | 来源配置、模型、算子和映射各自保留完整 pin；新建跳转仅准备未保存草稿 |
| Supporting：预览 | `Evaluation.receipt/evaluation.dryRun/writesPerformed/retainedCount/missingRaw/truncated/sourceStatus` | dryRun 与 writesPerformed=false；读取/接受计数不冒充持久输出或已收到业务数据 |
| Supporting：实际执行 | `workflow-runtime.ts`、`workflow-host-schedules.ts`、`workflow-metric-streams.ts`、`workflow-log-streams.ts` 的固定版本、任务/周期状态与批次确认 | 仅在其原能力入口读取/控制；运行中不证明每个批次已确认，UNKNOWN 不改成失败或成功 |
| Advanced：审计 | source UUID、连接 digest、配置/编辑版本、原请求标识、固定 pin、时间与原回执 | 按需展开，不塞进列表标题；只展示当前身份授权元数据 |

## 创建、连接、范围、预览、映射、启用：实际 API 清单

路径以当前 adapter 和 `contracts/` 为准；通用页面示意不是额外能力承诺。

| 用户意图 | 已有 API | 成功能证明什么 |
| --- | --- | --- |
| 浏览类型与旧创建回执 | `GET /api/v1/integrations/sources`；`POST .../sources/confirm`；`GET .../sources/{id}` 与 `/{id}/continuation` | 保存不可变创建回执；target 可空，不能保证已生成或发布工作流，更不启动采集 |
| 浏览当前实例/维护状态 | `GET /api/v2/data-sources`、`/{id}`、`/{id}/configurations`；`PATCH /{id}`；`GET /{id}/commands/{requestId}` | 当前维护元数据、不可变配置历史及原维护结果；归档不隐式停止其他主体任务 |
| 选择登记地址 | `GET /api/v2/data-sources/endpoints` | 当前授权地址目录；UI 不注册任意远程目标 |
| 维护凭据 | `/api/v2/data-sources/credentials` 的 GET/POST、`/{id}` 的 GET/PATCH、`/{id}/versions`、`/{id}/versions/{revision}/revoke`、`/{id}/commands/{requestId}` | 密钥版本与元数据；轮换不自动替换现有连接 pin |
| 创建/修改连接及 Scope | `POST /api/v2/data-sources/connections`；`PATCH /{id}/connection`；`GET /{id}/connection`、`/connection/history`、`/connection/commands/{requestId}` | 保存登记地址 pin、固定凭据 pin 和 1–32 个主机组 ID；Scope 是连接版本的一部分，没有单独的授予权限步骤 |
| 显式检查连接 | `POST /api/v2/data-sources/{id}/connection-check`（`/test` 为语义别名）；`GET /{id}/inspections/{requestId}`、`/inspections`、`/connection-checks` | 对原固定版本的有界授权读取结果，有效期 15 分钟；不启动任务 |
| 发现 Host/指标 | `POST /{id}/discover`、`/discover-metrics`、`/metric-discoveries`；原 inspection GET | 首页字段或有界指标元数据/快照页；不能当作已存资产、时序点或完整采集 |
| 清洗与模型映射 | `GET /api/v1/integrations/workflows`；`POST .../drafts`、`/preview`、`/run`、`/publish`；固定 drafts/versions GET | 保存、只读预览、已发布分别成立；`/run` 在此为 dryRun 评估，不等于业务写入 |
| 既有指标映射绑定维护 | `GET /api/v2/metric-bindings`、具体 binding GET；`POST /mapping` 与原 `/mapping/commands/{requestId}` GET | 固定一条已授权 binding 的映射 pin，不能概括为来源已启用 |
| 明确启用任务 | 现有 `/api/v1/integrations/workflows/runtime`、`/host-schedules`、`/metric-streams`、`/log-streams` 中各能力支持的状态/启停/恢复/原命令 API | 仅该固定版本与该能力的控制结果；不建立新的通用 `/activate`，不能一次启动全部信号类型 |
| 核对业务输出 | 原执行/批次/输出确认与授权数据回读 API | 与精确固定版本、批次和摘要匹配的已确认输出；缺失结果、未知写入或预览成功不能替代此证明 |

实例 Drawer 的注册连接问题分页是只读来源窗口，不创建 Incident；指标同步回执是目录/绑定扫描，也不能冒充指标点已写入。旧 v1 全局来源端口的 fixture-only 语义保持，真实注册连接失败不能回退旧入口。

## Desktop / Narrow / Mobile wireframes

沿用当前 Shell。候选 Token 仅在未来该工作台的 `.ow-v2` 根内映射；本设计不改全局主题、导航或四启动单元。

```text
1440 × 900
[数据源中心 / 当前返回范围]                               [新增连接]
[已配置接入 | 接入类型 | 凭据 | 创建回执]        [搜索/类型/状态/刷新]
名称 / 来源与模式 / 配置版本 / 维护状态 / 更新时间 / 维护与编排
────────────────────────────────────────────────────────────────────
选择实例 → 单层右侧设置区（约 480px，可内部纵向滚动）
[连接与范围 | 检查与发现 | 配置历史]
登记地址 / 精确凭据版本 / 主机组范围
当前配置检查：未检查/当前/过期/未知           [测试连接：次级]
[保存连接：本设置区唯一主操作]   [取消]
已保存 → 明确“编排此固定配置” → 原工作流页面

1024 × 768
列表保留名称/来源/状态，技术详情移入设置区
设置区占可用宽度约一半；空间不足时切换为单层全宽设置区
编辑/未知命令期间不允许通过页签切换丢失原输入

390 × 844
[标题] [新增连接]
[已配置 | 类型 | 凭据 | 回执：可横向滚动且有名称]
[搜索与筛选单列]
紧凑列表：名称 + 来源/模式 + 维护状态 + 明确打开入口
设置区全宽单层；字段单列；底部保存操作可触达
长版本表在自身横向滚动，秘密字段不回显
```

页面拥有主纵向滚动，打开设置区后由该区域拥有唯一内部滚动。不能同时叠加实例 Drawer、连接 Drawer 与凭据弹窗；跨任务需先处理 dirty/UNKNOWN，再在同一层切换。关闭恢复触发焦点；窄屏不以隐藏错误或强制截断操作解决空间问题。

## Async states / Failure matrix

| 状态 | 呈现与恢复 |
| --- | --- |
| initial | 授权后首次可见读取一次；未读目录不能显示“没有接入”或“已断开” |
| loading | 保持表格/字段结构，显示读取或保存类型；冻结重复提交 |
| ready | 各区域显示自己的已确认结果和固定版本，保留分阶段状态 |
| empty | 成功返回空实例/目录时才显示空状态；受限目录为空不等于系统不支持来源 |
| partial | truncated、首分页 scope、不完整发现和扫描缺口逐项保留；本页计数不是全量 |
| stale | 配置/登记地址/凭据变更或回执过期使旧检查失效；显示原时间与原因，明确重新检查 |
| error | 400 输入提示、409 CAS/内容冲突、429/容量、503 来源/密钥不可用分开；不清空本地非秘密草稿，不自动重试 |
| forbidden | 401/403 清空实例、连接、检查、秘密输入和隐藏缓存，迟到响应不可恢复；菜单隐藏不能授予权限 |
| fixture | 类型、检查、预览与执行各自标记 Fixture/手工样本，不能把某一阶段真实协议推及全链 |
| unknown-write | 保留原 UUID/原固定版本/原内容；先读原回执，按原协议显式原样重放；关闭/改版/新建有门禁 |

## Interaction map / Acceptance tests

- 列表、页签和主题切换只读或纯本地呈现；只有明确动作执行检查、保存、发现、发布或启停。失败后停止自动读取，显式重试才继续。
- 更换地址、凭据或 Scope 创建新配置修订，使原检查失效；已有发布/任务仍固定原 pin，不静默迁移。
- 从实例跳转编排携带 `sourceInstance/configurationRevision/connectionDigest` 三元组，经服务端重授权和精确历史核对，仅准备未保存草稿；缺失或归档时安全失败。
- 只有原执行/批次确认及授权回读才允许显示相应业务数据已确认；连接通过、发现记录数和预览通过均不够。
- U2a 实现后实际运行三宽度×明暗截图、全部异步/dirty/UNKNOWN/身份状态、键盘焦点返回及受影响 Playwright。逐个核验请求数量和字段，不用截图替代真实来源或输出验收。
- 自审：列表不铺满 KPI，设置不嵌套 Card，主动作随当前任务变化；没有声明新向导接口、任意地址、通用启用或自动采集能力。

Open questions：当前蓝图实施无阻塞问题；真实注册来源/生产身份与输出整链另按 E0/E1 验收。`visual: NOT_RUN`。

依据： [连接配置](../../contracts/source-connections.md)、[检查与发现](../../contracts/source-inspections.md)、[来源回执](../../contracts/source-setups.md)、[固定来源](../../contracts/workflow-source-bindings.md)、[周期主机](../../contracts/workflow-host-schedules.md)。旧契约段落的阶段性“待实现”不能覆盖当前 adapter 中后续已支持能力，具体发布范围仍按实现状态与实际验收证据判定。
