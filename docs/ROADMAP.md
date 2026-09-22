# OpsWeave（观织）产品与 Zeus 生态协同路线图

> 版本：Roadmap 1.1 · 日期：2026-09-21  
> 适用仓库：`baicie/ops-weave`、`baicie/zeus`、`baicie/zeus-ui`  
> 技术方向：Java 平台业务 + Rust Agent Runtime + Zeus / Zeus UI 前端。  
> 范围：从当前初始化工程，推进到真实只读诊断 MVP、可恢复 Agent、配置式 Skill 和受控试点。  
> 本文是实施建议，不表示功能已经完成。M0—M7 为规划里程碑，不是已有 Git Tag；未获取团队人数、投入强度和生产规模，因此不指定完成日期。  
> 落点：本文保存在 `docs/ROADMAP.md`。兼容性基线见 `docs/FRONTEND-COMPATIBILITY.md`。默认分支 `b5404a8`：M0 前端迁移验收关闭（本地 Playwright + GitHub Actions `opsweave-template` 四 job 通过）。中文 IME / 完整键盘矩阵仍未测，不阻塞进入 M1。不再安排一次 React→Zeus 迁移。

## 1. 当前起点：不要重复建设已经提交的迁移

当前默认分支 `b5404a8`。Web Console 已用 Zeus `render` 作为运行时，页面骨架在 `app/pages/api/state/adapters/styles`；资产/指标/Skill/Agent 页仍是未实现占位，诊断页是唯一已验收交互。

| 观察项 | 当前能确认的事实 | 路线图中的处理 |
|---|---|---|
| 前端运行时 | `main.tsx` 调用 `@zeus-js/zeus` 的 `render`；生产包经 `@zeus-web/button/wc/auto` 与 `@zeus-web/input/wc/auto` 注册原生组件。[S2] | 不再迁移框架。Zeus UI 的 JS 入口非 `sideEffects` 已在消费端规避，上游复现记 OW-R03，不阻塞产品 |
| 前端依赖 | `@zeus-js/zeus=0.1.1-beta.2`、`@zeus-web/ui=0.1.0-beta.4`、`@zeus-js/vite-plugin=0.0.4`。[S1] | 升级走兼容性清单，不以 `link:` 代替 registry |
| 页面组织 | `App.tsx` 连接 Diagnose / Inventory / Metrics / Skills / Agent 与 hash 路由。[S3] | 有入口不代表对应业务完成 |
| 平台状态 | 诊断仍是合成数据与本机 Demo token；真实授权与持久任务未实现。[S4] | 下一里程碑是身份与第一条真实数据链 |
| GitHub Actions | `b5404a8` 的 `opsweave-template`：contracts / rust / java / web 均为 success | 见 `VALIDATION-REPORT.md` 第 10 节 |
| Zeus / Zeus UI | 框架与原生 WC 路线未变。[S5][S6][S7] | 只修产品验收暴露的缺陷 |

不要再开一轮前端框架迁移。M1 起做身份和真实接入。

## 2. 总目标与首版边界

### 2.1 第一条必须交付的业务链

```text
用户登录并获得资源范围
  → 只读接入 CMDB / Zabbix
  → Entity / ExternalLink / Observation
  → 一组真实指标与一次真实告警
  → Incident
  → Java 只读 Tool Gateway
  → Rust Context / Evidence / Skill
  → AIInsight
  → Zeus 页面展示结论、证据和数据缺口
```

首版只回答一个问题：**某个被授权资源最近发生了什么，有哪些证据支持当前诊断？**

不将“成功调用模型”当作业务完成，不将“模型说很确定”当作已确认根因，也不将“存在页面/包目录”当作组件已经可用。

### 2.2 第一版明确不做

不建设多智能体专家团、Skill 市场、任意脚本执行、通用 BPMN、完整拖拽 Dashboard、自研图形引擎、默认 Kubernetes 大集群或所有云厂商 Connector。暂不做自动修复。

安全、基础审计、幂等和测试从第一阶段开始，不是推迟到上线前补。

## 3. 三个仓库的职责

| 仓库 | 负责 | 不负责 |
|---|---|---|
| `zeus` | JSX 编译、响应式与 DOM 生命周期；property/event/ref 互操作；必要的开发工具修复 | OpsWeave 的实体、Incident、租户规则、模型调用 |
| `zeus-ui` | 通用控件与高级组件；键盘与焦点；虚拟滚动；通用 Chat、运行步骤与 Tool 卡片 | 平台鉴权、数据源密钥、查询 Zabbix、直接运行 MCP 或运维动作 |
| `ops-weave` | 产品页面、API 客户端、业务状态、领域数据、Agent、授权、Skill 管理、审计与交付 | 复制一套 Zeus 编译器或把通用组件长期私有分叉 |

推荐数据依赖：

```text
Zeus UI 组件 ← 展示模型 / 交互事件
                 ↑
OpsWeave 页面 / adapters / API 客户端
                 ↑
Java Platform / Rust Agent Runtime
```

组件事件只表示用户意图，例如“请求取消运行”，不能被视为已授权执行命令。

### 3.1 通用能力回收规则

先在 OpsWeave 完成具体需求。只有明确属于框架缺陷或可以被第二种业务复用的行为，才回收到 Zeus / Zeus UI。框架缺陷先提交最小复现与失败测试；通用组件需保留无 OpsWeave API 依赖的原生示例。

组件库暂未满足要求时，允许使用原生表格、简单列表或可替换第三方 DOM 适配器。不得为了等待高级组件而阻塞真实数据链。

## 4. 总路线与依赖

| 阶段 | 交付目标 | 主要工作仓库 | 退出条件 |
|---|---|---|---|
| **M0** | Zeus 迁移验收与依赖基线 | 三仓库，OpsWeave 主导 | 发布/打包产物安装可复现，诊断 Demo 的交互与负例通过 |
| **M1** | 平台基础、登录、权限与前端请求层 | OpsWeave | 服务端身份与资源授权贯通，退出后无跨会话数据残留 |
| **M2** | 数据接入：Connector、原始数据、版本化流水线，再考虑 Copilot | OpsWeave；Zeus UI 按需补表格 | 一条已授权来源经 PipelineDefinition 落到 Entity；Copilot 不是本阶段退出条件 |
| **M3** | 指标、外部告警与 Incident 工作台 | OpsWeave | 实体、指标、告警和 Incident 可真实关联并追溯 |
| **M4** | 真实只读 AI 诊断 MVP | OpsWeave | 经授权的真实数据进入 Rust，结构化结果与证据可持久查询 |
| **M5** | 可恢复运行与 Agent 控制台 | OpsWeave + Zeus UI | 刷新、断线、重复请求、Worker 重启具有明确语义与测试 |
| **M6** | 配置式 Skill 发布与评估 | OpsWeave；Zeus UI 提供通用编辑 UI | 可创建、测试、审核、发布和回退 Skill，不获得额外权限 |
| **M7** | 受控私有化试点与交付 | OpsWeave 主导 | 有容量报告、备份恢复、升级回退、安全与业务验收记录 |
| **后续** | 深度 RCA、算法 AI、分布式与自动化 | 按明确需求启用 | 不作为首版阻塞条件 |

关键路径：

```text
M0 → M1 → M2 → M3 → M4 → M5 → M6 → M7
```

M1 必须在真实来源同步之前完成服务端身份。M2 先交付确定性流水线，再考虑 Integration Copilot。M3 的页面可以先使用明确标注的契约 fixture，但 M2/M3 的真实数据验收不可用 fixture 代替。M5 的 UI 原型可以提前做，必须等运行协议和持久状态完成后才宣布可恢复。

**版本候选命名建议**：M4 为 `v0.1.0-alpha` 只读 MVP；M5—M6 为 `v0.2.0-beta`；M7 为试点候选版本。正式版本号以仓库现有版本策略为准，不自动创建 Tag。

## 5. M0：完成 Zeus 迁移验收，而不是再次迁移

### 目标

确认现有前端不是仅修改依赖声明，而是能够以真实打包产物稳定运行。

### 工作项

OpsWeave 保持现有 `app/pages/adapters/api/state/styles` 边界。核对 JSX 编译、类型配置、Vite 插件、生产构建、按需注册以及锁文件。清查产品源码和直接运行依赖中的 React 遗留；第三方开发工具的间接依赖不等同于产品仍运行 React。

建立 `docs/FRONTEND-COMPATIBILITY.md`，记录 Zeus、Zeus UI、插件、Node、pnpm、TypeScript、Vite 的已验证组合，包来源、完整性信息与验证提交。优先安装已发布的精确版本；未发布修复可以使用审查后的 tarball，但需明确存档和校验，不让 CI 依赖本地 `link:` 目录。

Zeus / Zeus UI 只修复验收暴露的问题。验证对象/数组作为 property 传递、自定义事件、组件注册、条件挂载、卸载清理以及表单输入；中文输入法组合输入、键盘和焦点也纳入测试。

### 验收

- [x] 使用干净工作树拷贝（无 `node_modules`）与锁文件完成安装、类型检查、生产构建。
- [x] 诊断页输入、提交、重复提交保护、成功、失败、取消等待均有浏览器测试。
- [x] 无效凭据被拒绝；真实模式失败不会自动回退 fixture。
- [x] 路由切换不残留旧请求、监听器或订阅；卸载后完成的请求不覆盖新页面状态。
- [x] Evidence 和日志等外部文字不能注入可执行 HTML；原始 HTML 默认不开放。
- [x] 当前 README / 验证报告能区分“源代码已有”“CI 已通过”“浏览器已验收”。`b5404a8` 的 GitHub Actions 四 job 已通过；IME / 完整键盘矩阵仍未测，不阻塞 M1。

建议使用 Playwright 对原生页面做端到端验收，按角色、标签或稳定测试标识定位。其定位支持开放的 Shadow DOM，但不支持穿透 closed shadow roots，不能据此声称任意封装内部均可直接测试。[S9]

### 暂缓

不做完整管理后台组件市场、不换一整套路由生态、不搭微前端、不为演示引入真实用户数据。

## 6. M1：前端底座与可信身份一起完成

### 目标

从本机 Demo token 进入真实平台身份；前端使用统一请求层，不把权限判断散落在组件中。

### 前端交付

沿用现有页面骨架，完善布局、导航、空状态、错误状态、加载状态和不可用能力说明。建立统一 API client：请求取消、超时、错误归一化、请求追踪标识、分页和旧响应丢弃规则。资源筛选与时间范围可还原；hash 路由暂时可以保留，不把实现新 Router 当作产品前置。

采用明确的会话方案。本路线图建议浏览器经同源 Java BFF 使用安全会话 Cookie；同时设计 CSRF、防跨源滥用与会话失效处理。Rust 服务只接受验证过的内部主体/委托上下文，不信任浏览器传入的 tenant 或权限字段。不要求必须自研 IAM，可对接既有 OIDC 身份源。

### 后端交付

Java identity/application 定义主体、租户、资源范围与权限决策。域 API、Tool Gateway 和 Evidence 查询分别做资源授权。数据库使用最小权限运行角色；来源凭据仅保存受控引用。建立基础安全审计。

将 Capability 的“已安装”“已启用”“可用”“用户可见”分开，菜单隐藏不作为授权机制。退出登录、租户切换和权限失效时清理请求缓存、图表、消息缓存与连接。

### 验收

同一请求在允许和拒绝主体下产生正确结果；修改 tenant/entity/incident ID 不会越权。未登录访问、权限撤回、会话过期和资源不存在均有确定响应。UI 的错误状态可理解，但不能泄露不可见资源详情。

### Zeus UI 的最小配合

按实际缺口完善输入、选择、按钮、提示、弹窗、Tabs 等通用交互。产品导航、租户切换和权限空状态留在 OpsWeave。无需一次完成所有组件样式。

## 7. M2：数据接入流水线，而不是直接上 AI 解析

### 目标

资产页展示来自外部系统的真实对象，并能解释数据从哪里来。接入必须先有可版本化、可预览、可重放的确定性流水线；**Integration Copilot 写进本阶段规划，但不作为本阶段开工项，更不是退出条件。**

仓库里目前没有 `PipelineDefinition`、`integration.pipeline.*` 或 Copilot 实现。不要把 Connector SPI 骨架当成流水线已完成。

### 顺序（必须遵守）

```text
M2.1 Connector          声明来源、认证引用、分页/游标、连接测试
M2.2 Raw Data           原始记录有界保留，可追溯到一次扫描
M2.3 PipelineDefinition 节点 Catalog + 版本化定义（本阶段的修改目标）
M2.4 Mapping / Transform / Validate   确定性映射、校验、权威字段
M2.5 Preview / Replay / Version       预览、重放、草稿/发布，失败不删除
M2.6 Integration Copilot              仅在 2.1–2.5 可运行之后；生成/修改/解释定义
```

先选择可访问且已有明确契约的一个来源完成纵向接入，默认优先 Zabbix Host：

```text
Zabbix Host → RawRecord → Mapping → Observation → Entity
```

该路径必须绑定一个已发布的 `PipelineDefinition` 版本，而不是 Worker 里写死的隐式转换。第二个来源再验证融合。若 CMDB 尚无接口契约，建立显式标注的导入适配与 fixture 合同测试，但不要把文件导入宣称为真实 CMDB API 已接通。

### M2.1 Connector

DataSource 配置、连接测试、分页、同步游标、完整快照标志。凭据只保存受控引用。每个 Connector 声明支持的对象类型与失败语义。来源故障、权限变化或分页失败不能被解释为大量资产删除。

### M2.2 Raw Data

原始记录（或等价的不可变引用）按租户与来源实例隔离，有保留上限。后续映射必须能指回 Raw，禁止“映射成功就丢掉无法复核的原文”。

### M2.3 PipelineDefinition

这是接入配置的唯一修改目标。定义包含：来源、对象类型、节点图或有序列表、字段映射、校验、权威规则、输出到 Observation/Entity 的契约版本。节点来自平台 Catalog，不是任意脚本。

发布产生不可变 `PipelineVersion`（内容摘要固定）。运行绑定版本，不跟随 `latest` 漂移。草稿不能驱动生产同步。

第一版编辑器可以是表单与 JSON Schema；不把完整拖拽画布当作 Zabbix Host 纵向切片的前置。

### M2.4 Mapping / Transform / Validate

身份键包含租户、来源实例与外部对象类型。通过 ExternalLink / Observation 记录原始字段，再按字段权威规则产生 Entity 当前视图。跨来源合并仅对合格稳定标识自动进行。同名、同 IP 或占位序列号不作为充分合并依据。冲突进入人工确认，保留历史与撤销路径。

确定性转换必须有测试：重复同步不新增重复 Entity；非法/缺失字段进入校验失败而非猜测补全。

### M2.5 Preview / Replay / Version

发布前可对样本 Raw 做 Preview，展示将写入的 Observation/Entity 而不落库（或写入明确标注的预览隔离）。Replay 默认只修复数据，不发通知、不触发动作。只有来源扫描完整且成功才进行对账。

### M2.6 Integration Copilot（规划保留，本阶段不开发）

等 2.1–2.5 可运行后，Copilot 才能针对 **已发布或草稿中的 `PipelineDefinition`** 工作：生成、修改、解释、根据 Preview/校验失败提出修补。它不能直连 Zabbix、不能改生产发布指针、不能授予新权限、不能输出任意代码节点。模型建议必须变成定义上的 diff，经 Validate / Preview 后仍走 Draft → Publish。

不要在没有定义对象时先做“AI 帮我解析数据”。

远期 Integration Studio（表单、可选拖拽、Copilot）都只是同一套定义的编辑面：

```text
                    Integration Studio

       ┌──────────────┼───────────────┐
       ↓              ↓               ↓
    拖拽编辑        表单配置        AI Copilot
       │              │               │
       └──────────────┼───────────────┘
                      ↓
              PipelineDefinition
                      ↓
          Validate / Preview / Test
                      ↓
                    Draft
                      ↓
                   Publish
                      ↓
              Ingestion Worker
```

拖拽画布与 Copilot 都不是第一条 Zabbix 链的阻塞项。Skill DAG（M6）是 Agent 能力编排，与本阶段数据接入流水线分开，不要合成一个“万能工作流引擎”。

### 前端交付

资产列表、服务端分页/筛选、详情、来源字段、冲突提示、同步任务、数据新鲜度和流水线版本/预览结果。先使用简单表格也可以；高级 Data Grid 尚未验收时不阻塞。

### 验收（M2 退出，不含 Copilot）

- [ ] 一条已声明支持版本的来源（默认 Zabbix Host）经已发布 PipelineVersion 完成分页、游标、完整快照。
- [ ] 重复同步不新增重复 Entity；失败扫描不误删除。
- [ ] 同 IP 的不同租户不合并；字段冲突可追溯来源、映射版本和观测时间。
- [ ] Preview / Replay 不产生通知或动作副作用。
- [ ] 未实现 Copilot 时页面不假装已有 AI 接入助手。

## 8. M3：完成指标、外部告警与 Incident

### 目标

让产品能展示真实运行情况，而不是只拥有资产清单。

### 指标边界

首轮只接一组有明确语义的关键指标。MetricDefinition 定义单位、类型、维度、聚合方式与缺失语义；保留 Gauge、Counter / Sum、Histogram 的区别，计数器变化率和单位换算在查询/转换层明确实现。不能直接把所有数值都改名成同一种 CPU 使用率，也不能平均多个 P95 得到总体 P95。OpenTelemetry 的数据模型可作为这部分契约参考，但平台命名与来源映射仍需独立说明。[S10]

### 告警与故障边界

先导入外部告警及恢复事件，不以自建完整规则引擎为前提。保留来源告警 ID、发生/接收时间、实体映射、生命周期及幂等键。

建立 Incident 列表、详情、关联告警、人工合并/拆分与状态流转。早期聚合以确定规则和人工确认为主，不先做 AI 自动大规模聚类。维护窗口和抑制策略约束自动诊断触发，数据本身不因此丢失。

### 前端交付

指标曲线、时间范围、无数据/过期数据状态、Incident 时间线和关联资产。图表与拓扑通过 DOM 适配层集成已有引擎；不要为使用 Zeus 重写图形底层。拓扑仅展示已有可信关系，缺失依赖不得臆造。

### 验收

能从一个 Incident 跳到相关实体及同时间窗口指标；重复告警不重复新建 Incident；恢复语义正确；时区、缺失点、counter reset 等有用例。日志/变更尚未接入时页面明确缺失。

## 9. M4：真实只读诊断 MVP

### 目标

Rust 从受控平台 API 获取真实数据，模型结果能够回到产品中被审阅。

### Java 工作

实现版本化 `incident.get` 与 `metric.summary`，以后按需增加拓扑、变更或日志摘要。每个工具执行服务端授权、时间窗口/基数限制、输出限制与审计。Evidence 是可受控读取的业务资源，而不是任意链接。

AIInsight 作为平台领域结果持久化，以请求/运行标识支持重复提交处理；结果保存失败必须表现为失败或待确认，不能返回已永久保存。浏览器不能直接调用数据库、Zabbix、模型服务或外部 MCP。

### Rust 工作

在现有 PlatformReadPort 后增加真实 HTTP 适配，显式区分 fixture 与 real 配置；保持 Rig/模型库在适配层。固定流程优先，独立数据并行获取。为 Tool、上下文、输入输出和模型调用设置预算与截止时间，不为一次摘要增加多智能体。

Context 中区分分析截止时间与当前访问时间，保存来源、查询窗口、输入版本、缺失项与 Evidence 引用。模型前后均执行结构和引用检查，受保护的缺失披露不能被模型抹掉。引用有效不等于根因已证实，时间相关性不直接等同因果。

### 前端工作

完成“发起诊断 → 查看结果 → 打开有权限证据”的闭环。先采用明确的请求状态和结果页面；暂不承诺刷新后的断点恢复。保留假设、支持/冲突证据和下一步建议，而不显示伪装成概率的模型自信分数。

### 评估从本阶段开始

建立小型标注集，覆盖正常、缺失、冲突、过期、越权、注入性日志、模型超时和非法输出。对比确定性摘要与模型摘要，确认模型带来的是额外信息而非更长文本。初期人工核查，不宣称已达到某个 RCA 准确率。

### 验收 / 首个 MVP 定义

- [ ] 一条真实来源链、一个真实 Incident 和一组真实指标可完成诊断。
- [ ] 输出能查到已保存结果，每条引用属于本次已授权 Evidence。
- [ ] 无法获取的数据不会被猜测补全，真实模式异常不会伪装为 Demo 成功。
- [ ] 模型请求不携带原始密钥；外部日志/知识被当作不可信数据。
- [ ] 既有负例集全部通过；抽样结果有人审阅。
- [ ] 不开放任何生产写入工具。

该阶段是受限环境的只读 MVP，不是可恢复的大规模生产 Agent 服务。

## 10. M5：可恢复 Agent Runtime 与可用 Agent Console

### 目标

从一次同步调用演进为可查询、可取消、可恢复的诊断任务。

### 后端与 Runtime

先稳定 AIRun / StepRun / Checkpoint / RunEvent 协议，再做实时控制台。运行配置固定 Skill 内容摘要、模型策略版本和工具契约版本。

运行状态持久化，明确任务租约、超时、取消、重试、Worker 崩溃和 fencing。同租户幂等键绑定同输入；相同键不同输入需报冲突。恢复先重新授权，再判断证据是否过期。模型调用中途崩溃存在未知完成状态，恢复策略需说明重复费用和结果选择，不能声称跨外部服务天然 exactly-once。

Java 拥有平台 AIInsight，Rust 拥有 Runtime 状态；跨库提交使用明确的可靠投递/对账，不让 Rust 直接写平台 Entity 表。

### 前后端协议

先提供“创建任务 + 查询状态/结果”的 HTTP 接口，再增加 SSE 作为状态更新通道；轮询保留作降级。事件至少有 `runId`、`eventId` / 每运行序号、类型、发生时间与载荷版本。

浏览器通过 BFF 验证身份、读取授权事件。断线重连可携带上次事件位置；浏览器去重，服务端定义事件保留窗口。游标过期时回到最新快照并显示缺口。SSE 标准提供 `id` / `Last-Event-ID` 等机制，但业务事件持久化与重放必须由平台实现。[S11]

用户离开页面是取消订阅，不自动等同取消服务端运行；显式“取消任务”才向后端提交取消请求，页面显示 cancel requested 与最终状态。事件流不输出隐藏推理内容，仅输出工具记录、步骤状态、证据与业务摘要。

### Zeus UI 配合

逐个验收 `chat`、`agent-console`、`virtual` 实际包产物。通用组件负责消息、步骤、Tool 卡片、滚动与用户交互，OpsWeave adapter 负责把 RunEvent 转为展示模型。不让 Zeus UI 直连 Rust、保存 Token 或做租户授权。

### 验收

重复提交、乱序/重复事件、刷新、断线、事件游标过期、Worker 重启、权限撤回、用户取消都有测试。前端只渲染有限窗口，历史按需加载。慢客户端不得无限堆积消息或阻塞 Runtime。

## 11. M6：平台内的配置式 Skill Builder

### 目标

管理员不用修改 Rust 代码，也能组合已有能力形成专业 Skill。

### 范围

创建、复制、编辑草稿、测试、比较版本、发布、停用、回退、查看运行与费用记录。配置项限定为输入/输出 Schema、Prompt、Context 需求、工具白名单、知识范围、模型策略和预算。

Skill 绑定已注册 Rust 流程模板。新增底层执行能力仍由开发者完成；修改平台配置不应触发重新编译。第一阶段不做任意节点代码或无限递归调用。

### 治理

Java ai-control 拥有不可变发布版本；Rust 拉取受信任定义并校验。发布指针可更新，但每次 AIRun 固定一个版本和摘要，运行中不跟随 `latest` 漂移。停用后的新运行被拒绝，正在运行的任务如何处理须明确。

Skill 不能授予调用者新权限，也不能通过扩大 contextRequirements 绕过预算。Playground 默认只读；生产数据测试同样授权和审计。发布前运行 M4 已建立的评估集，并补充 Skill 特有案例。

### 验收

Prompt 改动产生新版本；旧结果仍能对应旧版本；发布失败不污染线上版本；回退不改历史记录；Schema、Evidence 与权限负例阻止不合格版本发布。

### 不做

任意 Rust/Python/Shell 上传、任意 HTTP/SQL、插件市场、完整 DAG 拖拽编程。文本/表单与 JSON Schema 编辑足够支持第一版。

## 12. M7：受控试点与可交付版本

### 目标

在限定规模、限定租户和只读边界内，证明产品能够持续运行与升级，而不是仅演示成功一次。

### 交付包

提供本地开发配置与独立试点配置。锁定依赖、镜像、数据库迁移、Skill 与 Connector 版本；提供 Secret 配置方式、备份/恢复、升级/回退、容量限制、数据保留、审计和运行手册。

Kafka、独立缓存、集群化存储、Kubernetes 都按已测量负载与客户部署要求启用，不因“未来可能分布式”全部设为首版硬依赖。单节点形态明确不提供 HA 承诺。

### 试点观测

至少记录 Connector 延迟/失败/对账、Tool 查询耗时/扫描量、Context 字节和证据量、模型调用与费用、AIRun 成功/失败/取消/等待、前端主要操作与内存趋势。诊断费用按租户、Skill、模型聚合，避免未校准的总健康分代替实际指标。

### 验收

完成来源断连、队列积压、模型不可用、数据库重启、运行中升级和备份恢复演练。安全测试覆盖已定义的越权、恶意证据与输出注入案例。试点用户能完成既定任务并复核证据，问题记录有责任人与修复闭环。

## 13. 后续路线：按价值触发，不同时启动

| 方向 | 启动条件 | 第一件实际交付 |
|---|---|---|
| 日志 / 变更 / Trace 的深度 RCA | 当前诊断的主要瓶颈是证据不足 | 先接最有用的一类证据，记录改善情况 |
| RAG | 有经过授权、版本化、可维护的手册和历史案例 | 小规模检索评估、引用与文档撤权处理 |
| 异常检测 / 预测 | 指标语义、缺失率、基线与历史长度已达到算法需求 | 先确定性基线，再与模型比较；不先建完整 Feature 平台 |
| 分布式扩容 | 单节点基准、积压和恢复数据证明确有瓶颈 | 单独扩容瓶颈 Worker，验证重复消息、分区和租户公平性 |
| 接入流水线拖拽画布 | 表单/JSON 已无法覆盖真实映射，且 Catalog 节点已稳定 | 仍只编辑 PipelineDefinition，不引入任意代码节点 |
| Skill DAG（与接入流水线分开） | 多个稳定 Skill 模板已无法覆盖真实用户需求 | 受限节点、契约校验、版本化及可恢复执行 |
| MCP 扩展 | 出现明确外部工具接入需求 | 审查一个 Server，工具准入、授权、限流和审计 |
| 自动化动作 | 只读诊断经评估，具备执行权限与恢复手册 | ActionProposal → Policy → Approval → 执行前重验 → 验证；仅白名单低风险动作 |
| 多 Agent 专家协作 | 单 Agent 的可测质量瓶颈已明确 | 与单 Agent 做质量、成本和延迟对照试验 |

预测结果需同时保存生成时间、目标时间、预测范围和模型版本，不能直接覆盖实际指标。AI 输出分流到数值指标、事件或 AIInsight，防止 AI 派生数据无限回流再次触发自身。

## 14. Zeus 与 Zeus UI 的独立跟进轨道

### 14.1 Zeus：先兼容和稳定，不以功能数量驱动

| 优先级 | 工作 | 来自哪个里程碑 |
|---|---|---|
| P0 | JSX property / event / ref、类型、挂载卸载、编译与运行一致性 | M0/M1 |
| P0 | pnpm 安装产物可用、导出入口与 Vite 兼容 | M0 |
| P1 | 条件区域、长列表、订阅与批量更新的实际性能缺陷 | M2/M5 |
| P1 | 错误定位、调试与最小复现质量 | 所有阶段 |
| 暂缓 | 无消费者需要的新 Router、SSR、状态管理框架或大规模重构 | 不作 MVP 前置 |

### 14.2 Zeus UI：按产品场景验收，不按目录数算完成

| 波次 | 组件能力 | 产品消费者 |
|---|---|---|
| A | 输入、按钮、错误状态、键盘、焦点与最小主题 | 诊断表单与平台壳层 |
| B | 表格/分页/选择/筛选；实际需要时增加虚拟化 | 资产、告警和 Incident 列表 |
| C | Chat、Tool 卡片、步骤状态、有限窗口、增量更新 | Agent 控制台 |
| D | 版本差异、Schema 表单等可复用编辑行为 | Skill Builder；接入流水线表单可复用同一类行为 |
| 暂缓 | 完整拖拽 Pipeline 画布 | 不阻塞 Zabbix Host 纵向切片 |

组件代码、打包产物、原生示例和 OpsWeave 消费测试分别计完成度。headless 行为与产品视觉分离；组件库不得依赖 OpsWeave 业务包。

跨仓库提交顺序：失败复现 → 上游修复与测试 → 构建可追溯产物 → OpsWeave 升级依赖和锁文件 → 消费端验收。三个仓库不要求同时提升大版本。

## 15. 性能与可靠性：先基准，再决定复杂度

以下为建议测试场景，不是容量承诺。首次执行时记录 CPU、内存、OS、浏览器、网络、数据分布、构建模式与模型模拟延迟，再定绝对预算。

| 场景 | 建议输入 | 重点检查 |
|---|---|---|
| 资产列表 | 服务端 1 万条合成资产、分页/排序/筛选 | 不一次渲染全部 DOM；结果、选择和滚动正确 |
| 对话 / 运行历史 | 2,000 条历史消息或事件 + 连续增量 | 有界数据、视口渲染、用户向上阅读时不强制跳底 |
| 挂载与卸载 | 同一路由反复进入离开 100 次 | 订阅、连接、监听器、图表实例回收；内存是否持续增长 |
| 图表 | 多序列与时间窗口切换 | 点数预算、聚合准确、旧请求取消、resize/dispose |
| Runtime | 用确定性模型 stub 从 1 增加至 20 个并发任务 | 排队、租户公平、限流、取消、Tool 放大与内存 |
| 运行事件流 | 重复、缺口、延迟、断线与慢消费者 | 有界队列、去重、快照重建和授权 |

记录 UI 交互 P50/P95、长任务、DOM 数量、内存曲线；后端拆分排队、数据查询、Context、模型与保存耗时。外部模型端到端延迟与 Runtime 自身开销单独报告。

初期可以将同基准下 P95 或稳定内存退化超过 20% 设为人工复审触发线，需多轮复测排除噪声；这不是所有页面通用 SLO。不因为使用 Rust 或细粒度响应式就预先宣称性能达标。

安全限额从第一天可配置：单租户并发、单来源同步速率、查询扫描量、Tool 返回量、Context/输出大小、LLM 轮次、时间和费用。超限应有可解释失败或降级，不无限排队。

## 16. 首批 Issue：M0 已关闭，先身份和流水线定义

以下为建议 Issue 标题和验收范围，尚未在 GitHub 创建。OW-Rxx 是本文规划 ID，不是已有 Issue 编号。

| ID | 建议标题 | 仓库 | 优先级 | 依赖 | 最小完成定义 |
|---|---|---|---|---|---|
| OW-R01 | `chore(web): record Zeus compatibility and clean-install baseline` | ops-weave | 完成 | 无 | `b5404a8` 锁文件安装、typecheck、生产构建；GitHub Actions web job 通过 |
| OW-R02 | `test(web): cover diagnose page and Web Component interop` | ops-weave | 完成 | R01 | Playwright 7 passed；CI web job 含 `pnpm test:web` |
| OW-R03 | `fix(interop): upstream reproducible Zeus / Zeus UI defects` | 实际责任仓库 | 消费端已规避 | R02 | `wc/auto` 注册已在 OpsWeave 落地；上游最小复现可另开，不阻塞 M1 |
| OW-R04 | `feat(identity): define verified principal and resource scopes` | ops-weave | 本机切片 | 无 | Dev Principal、资源范围、允许/拒绝矩阵已落地；生产 OIDC/Keycloak 未接 |
| OW-R05 | `feat(web): centralize API lifecycle and session cleanup` | ops-weave | P0 | R04 | 取消、超时、旧响应、退出/换租户清理；会话方案一致 |
| OW-R11 | `feat(integration): define PipelineDefinition and versioned catalog` | ops-weave | P0，进行中 | R04 | 已有 Host 线性定义与 JSON Schema；Preview/不可变发布指针仍缺；尚无 Copilot |
| OW-R06 | `feat(inventory): persist entities, observations and external links` | ops-weave | P0 | R04 | 内存库存已能写入 Host Entity；尚无 PostgreSQL |
| OW-R07 | `feat(integration): synchronize Zabbix hosts via published pipeline` | ops-weave | P0，进行中 | R11/R06 | fixture 与 JSON-RPC 客户端已通；尚未对厂商 Zabbix 发 `host.get` |
| OW-R08 | `feat(web): deliver authorized inventory list and detail` | ops-weave | P1 | R05/R06 | 真 API、服务端筛选分页、来源、新鲜度、拒绝/空/错误态 |
| OW-R09 | `feat(integration): add CMDB mapping and cross-source reconciliation` | ops-weave | P1 | R07、CMDB 契约 | 二来源匹配/冲突/权威字段、成功快照对账、重放无副作用 |
| OW-R10 | `feat(observability): connect metric catalog, external alarms and incidents` | ops-weave | P1 | R06/R07 | 实体关联、指标语义、告警幂等/恢复、Incident API与页面 |
| OW-R12 | `feat(integration): Integration Copilot against PipelineDefinition` | ops-weave | 暂缓 | R11/R07 可运行 | 只生成定义 diff；经 Preview 后 Draft→Publish；禁止直连来源或发布任意代码 |

当前启动顺序：不要加深 IAM。指标目录和来源绑定已分开，映射从 `extensions/mappings` 加载。下一步是 History 增量读取，再写入 VictoriaMetrics。不要先做 R12，也不要再迁前端框架。

### Issue 描述模板

```markdown
## 目标
一个可演示、可验证的结果。

## 当前证据
涉及的源码、版本和现有失败行为；不要仅引用过期 README。

## 范围
本次做什么；明确不做什么。

## 依赖与所有权
需要哪个接口/包版本；改动归哪个仓库。

## 验收
- [ ] 正常路径。
- [ ] 失败与取消路径。
- [ ] 权限/资源范围或组件生命周期负例。
- [ ] 构建、测试、文档和回退说明。

## 验证记录
提交、命令、环境、结果与尚未验证内容。
```

## 17. 发布与验收纪律

每个里程碑至少有一次完整业务演示、自动测试结果、未解决问题和下一阶段允许进入的条件。完成度按“用户能做什么”计算，不按目录、Tool 或组件数量。

建议工作文档：

```text
docs/ROADMAP.md                         # 本文
docs/FRONTEND-COMPATIBILITY.md          # 三仓库依赖组合
docs/IMPLEMENTATION-STATUS.md           # 已有 / 已验证 / 未完成
docs/VALIDATION-REPORT.md               # 当前版本验证证据
docs/adr/                              # 身份、依赖、运行事件等决策
docs/runbooks/                         # 同步、恢复、升级与故障处置
```

已落入本仓库的是 `docs/ROADMAP.md` 与 `docs/FRONTEND-COMPATIBILITY.md`。M0 在 `b5404a8` 关闭（含 GitHub Actions）；IME 未测。下一里程碑是 M1 身份，然后是不含 Copilot 的 M2 流水线。

### 每阶段必须守住的退出条件

1. 契约与 UI/运行时行为一致，不能靠放宽 Schema 让测试通过。
2. 身份、来源、数据模式和能力状态明确，真实失败不能伪装成功。
3. 用例同时覆盖失败、重试、撤权和资源清理，而不只覆盖成功截图。
4. 不引入循环仓库依赖；更新可追溯，旧版本可恢复。
5. 未验证的包、模型、系统环境和吞吐有明确标注。

## 18. 最终推进原则

**让 OpsWeave 的真实业务推动 Zeus 与 Zeus UI 成熟，而不是等框架和组件库“全部完成”后才开始产品。**

M0 已关闭。Host 同步按页写入 PostgreSQL，完成态是一次 offset 扫描尝试。Item 映射成指标目录和来源绑定。下一步是 History，不把采样点写入 PostgreSQL。不要先做 Integration Copilot。

---

## 资料与核对依据

仓库实施基线为 2026-09-21 默认分支 `b5404a817e832cfa13c9b52a005477a453ef031d`。下表 blob SHA 来自路线图 1.0 起草时的文件快照，不是当前提交；以仓库 HEAD 与 `FRONTEND-COMPATIBILITY.md` 为准。

| 编号 | 来源 | 本次读取的 blob SHA / 用途 |
|---|---|---|
| S1 | [OpsWeave web package.json][S1] | `db84f449ac54b3cb03cfbf189ab58479b7cac6d7`；当前依赖声明 |
| S2 | [OpsWeave main.tsx][S2] | `84b20db4b115975940499d0ac174a246253e957b`；Zeus 入口 |
| S3 | [OpsWeave App.tsx][S3] | `ec77b2a59726cf38c782ee5da5dc5548e34d97f5`；当前页面组织 |
| S4 | [OpsWeave README][S4] | `9b7be1a4a8ae97edb5ead15d888714d673865830`；声明的模板边界 |
| S5 | [Zeus README][S5] | `e154e711ff3f5e915672c7494a459b741275855d`；框架定位 |
| S6 | [Zeus UI README][S6] | `7bb1f71774ec719098c13dd65d21b6055754c876`；消费入口与高级组件说明 |
| S7 | [Zeus UI Advanced README][S7] | `c0ac6866f4c22c7613cc519de6ee51bbbbf80895`；组件边界与性能契约 |
| S8 | [OpsWeave NEXT-TASKS][S8] | `fbfa342d8e6d837888ed3b26d198baf466723dcc`；与原有后端任务方向对齐 |
| S9 | [Playwright Locators][S9] | 原生浏览器测试与 Shadow DOM 支持范围 |
| S10 | [OpenTelemetry Metrics Data Model][S10] | 指标类型与时间语义的规范参考 |
| S11 | [WHATWG Server-sent events][S11] | SSE 与事件标识的协议参考 |

[S1]: https://github.com/baicie/ops-weave/blob/main/apps/web-console/package.json
[S2]: https://github.com/baicie/ops-weave/blob/main/apps/web-console/src/main.tsx
[S3]: https://github.com/baicie/ops-weave/blob/main/apps/web-console/src/app/App.tsx
[S4]: https://github.com/baicie/ops-weave/blob/main/README.md
[S5]: https://github.com/baicie/zeus/blob/main/README.md
[S6]: https://github.com/baicie/zeus-ui/blob/main/README.md
[S7]: https://github.com/baicie/zeus-ui/blob/main/packages/advanced/README.md
[S8]: https://github.com/baicie/ops-weave/blob/main/docs/NEXT-TASKS.md
[S9]: https://playwright.dev/docs/locators
[S10]: https://opentelemetry.io/docs/specs/otel/metrics/data-model/
[S11]: https://html.spec.whatwg.org/multipage/server-sent-events.html
