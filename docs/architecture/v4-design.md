# OpsWeave（观织）总体架构设计 v4

> 第137节：固定日志历史增加有界独立投影，过去24小时内60秒/最多1,000条，先检查后确认及完整摘要匹配。沿用Java完整窗口、共享预算及日志批量端口；已有PG/V057只保存证明，既有日志库V003隔离重放。原UUID只读，潜在写入保持UNKNOWN，正文仅明确分页查看；原任务/授权/checkpoint保持，默认权限不扩大。见[ADR-099](../adr/099-bounded-historical-log-reconstruction.md)及实际验证§137；资产重放、修复、留存容量和生产可靠性继续。

> 第136节：固定发布指标增加受控历史投影重建，过去24小时内60秒/最多60条，两步固定输入与输出摘要。独立workflow.replay权限与原来源/目标门禁并行；V056保存证明与唯一执行，原UUID重复不重读/重写，UNKNOWN仅显式原输出核验。独立重放系列不改变持续任务或checkpoint，不通知/动作；四单元与共享预算保持。见[ADR-098](../adr/098-bounded-historical-metric-reconstruction.md)，实际检查见验证报告§136；日志/资产重放、保留容量及生产可靠性继续。

> 第135节：同一可信事务读取固定版本已保存检查，20条分页和200条原本人容量保持。既有PG的V055插入序号固定选择，AES-GCM游标隐藏序号并绑定可信范围；新插入及回填旧时间不改变快照，每页复核逐实体见证/锚点，失效409、损坏503。Java服务无来源/输出/动作端口，原观察正文与时间不改；共享节点明细和Web请求容器沿用一次读取/取消/403无隐式重试。见[ADR-097](../adr/097-scoped-recorded-workflow-history.md)。已保存检查不是完整执行或输出确认；重放、通知、保留、生产容量及租约/身份仍继续。

> 第134节：Java 纯领域在同一事务、固定版本和 asOf 下评估显式质量阈值；配置与原UUID不可变回执使用已有PG的V054，来源/输出/身份门禁保持，无通知、动作、隐藏执行器或新启动单元。规则默认未启用，缺失及顺序不明保留无法评估；本地排队去重，当前任务与历史分开。配置CAS、命令摘要及容量由服务器校验，共享Web容器与纯Panel保持待确认门禁。见[ADR-096](../adr/096-configurable-workflow-quality-thresholds.md)与验证报告§134；保留、生产容量、租约/fencing及生产身份仍继续。

> 第133节：Java 既有本人轮询使用容量21的本地 FIFO，记录实际入队及出队时间；来源、转换和输出统计保持独立。queueWaitMillis 仅在有闭合 dispatch 证据时有值，旧未测量仍为空。当前任务、代数和可信授权在 IO 前重查；没有新线程、迁移、数据库或启动单元。观测不覆盖轮询之前或全局积压，生产公平调度、保留容量和租约/fencing/HA仍需验收。见 [ADR-095](../adr/095-measured-local-workflow-dispatch.md)及验证报告§133。

> 第131节：单次样本未知输出通过独立不可变终止事件关闭确认，原证明和输出保持；纯Java服务只依赖Store/Clock，已有PG的V053类型化外键/唯一键及事务refinement门禁防止核验竞态覆盖。新样本仍需明确请求和当前权限，无新数据库/启动单元。见[ADR-093](../adr/093-explicit-sample-output-closure.md)与验证报告§131。

> 第130节：旧日志无拒绝证据的FAILED通过只读观察保留UNKNOWN语义，原批次/输出不改；显式终止沿用纯Java服务、原回执和既有PG事务，更高版本替换重查同一父对象。没有新数据库、启动单元或隐式补偿。实际检查见验证报告§130和[ADR-092](../adr/092-legacy-log-outcome-uncertainty.md)。

> 第129节：明确任务版本替换、终态历史及日志写入确定性沿用Java应用服务、既有PG/V051/V052和四启动单元。旧未证实拒绝不能重写；新版本不继承旧授权/游标，历史只读。真实联测、部署和原环境保持见验证报告§129及[ADR-091](../adr/091-explicit-terminal-task-version-replacement.md)。

> 第128节实现：终止恢复沿用纯Java应用服务、已有PG/V050及四启动单元，无来源/输出/授权签发端口；任务/回执原子提交，原UNKNOWN和确认进度保持。明确新版本任务与历史终止事件分开，当前身份复核实际对象范围。当时最终真实联测及主平台部署待恢复，已在第129节复验完成，见[ADR-090](../adr/090-explicit-workflow-recovery-closure.md)及验证报告§128。

> 第127节：固定主机分页沿用Java执行器、已有PG/V049及四启动单元，首次转换检查记录私有逐实体范围见证、原批次关联和节点处置；当前身份及父批次一致性限制只读投影。拒绝不准入，原UNKNOWN恢复不重读/重复统计，容量在IO前检查。见[ADR-089](../adr/089-scoped-host-page-validation-diagnostics.md)。

> 第126节：既有Java执行器保存一次来源/转换检查的闭合节点和行处置元数据，首错后的未知记录保留，不逐点PG查询或存原文。V049位于既有PG，200条本人容量在IO前检查，当前代数/身份/版本二次检查；只读投影没有执行端口。固定窗口恢复与最近20条展示分开，四启动单元保持。见[ADR-088](../adr/088-measured-workflow-validation-diagnostics.md)。

> 第125节：既有Java平台对原批次提供只读质量观察，不新增数据库/采集/执行端口或启动单元。固定租户、本人、版本和读取范围，保留未知/待确认及缺失分母，不混合窗口、单位与总体健康。详见[ADR-087](../adr/087-read-only-workflow-quality-observations.md)，完整S7继续。

> 第123节：既有Java平台补完整LOG窗口读取及整窗批量存储端口，clock/ns与映射事件时间分开，最多1,000条、20请求、20秒，任何失败不返回成功前缀。算子分组在内存，整窗一次输出；原文留在内存及日志库。已有日志库新增V002表，不改旧样本表/摘要，不新增启动单元或PG正文表。持续任务控制、持久证明、未知只读恢复及确认后检查点继续。见[ADR-085](../adr/085-bounded-complete-log-window-ports.md)。

> 第122节：外部日志样本复用既有连接、发现快照、Java工作流计划及日志端口；固定 LOG item、读取前后元数据与来源/实体/日志范围校验，正文只在内存和日志引擎。先受理后一次输出，未知只读原批次；没有新增服务、PG正文表或执行后端。持续日志完整窗口/去重检查点与S7继续。见[ADR-084](../adr/084-fixed-log-workflow-sources.md)。

> 第117节：既有Java平台处理固定指标60秒完整窗口，每窗最多60点，既有算子按5条分组；点值留在内存和时序库，PG只保存窗口摘要与checkpoint。输出确认后推进，未知核验不重发，明确恢复保持原固定版本和时间。沿用四启动单元、可信身份及共享有界IO；日志、周期调度、模型依赖、HA和生产容量继续。见[ADR-079](../adr/079-confirmed-continuous-metric-windows.md)。

> 第116节：固定接入的资产流程加入原批次日志和确认后checkpoint，有限主机清单分批处理，显式停止/恢复沿用原内容与新短时授权。低频规范资产输入进入既有PG私有表，指标点仍不进入PG；四启动单元不变。单执行器恢复已实现，持续窗口、周期调度和生产HA继续。见[ADR-078](../adr/078-durable-fixed-host-workflow-batches.md)。


> 第115节：纯Java应用服务在已有PG保存一次指标批次证据，固定标准系列写入既有VictoriaMetrics，完整回读确认后细化状态；UNKNOWN恢复只查询原摘要，无隐式重写。沿用四启动单元，不逐点查PG，不新增数据库/执行框架。当前是最多5点样本，连续批次/checkpoint与S5整体继续。见[ADR-077](../adr/077-confirmed-workflow-metric-output.md)。

> 第114节：任务启动/停止增加不可变受理回执，原请求重放不重签授权或改代数。任务变化与回执在已有PG事务中提交；Web原结果确认与当前状态分离，未知结果无自动重发，停止有预留容量和代数。沿用四单元与純Java领域，不新增执行后端。固定批次/业务输出恢复及S5整体继续。见[ADR-076](../adr/076-idempotent-workflow-control.md)。

> 第113节实现更新：原Host实体任务接入有期限、可撤销的可信后台授权，公开摘要与私有引用分离，失败写入不推进游标。使用现有Java平台和PG任务JSON，不新增启动单元。固定接入配置批次、指标输出、控制命令幂等与完整恢复继续；详见[ADR-075](../adr/075-bounded-workflow-background-authority.md)。

> 第112节实现更新：工作流固定真实指标的连接版本、发现出处及完整系列元数据，受控读取最近10分钟最多5个原始点，执行一次标准映射；保存/发布/比较和只读谱系已接通。业务指标持久输出、可信可撤销后台委托及固定批次/checkpoint继续。沿用四启动单元、已有PG和纯Java计划。见[ADR-074](../adr/074-fixed-metric-workflow-sources.md)与[实现状态](../IMPLEMENTATION-STATUS.md)。

> 文档版本：4.0.0 · 设计日期：2026-09-21  
> 产品：统一可观测与智能运维平台  
> 本次收敛：**Java 平台后端 + Rust Agent Runtime + TypeScript 前端**。不部署 Python Agent，不依赖 LangGraph；Python 仅可作为后续独立算法服务或当前可选开发校验工具。  
> 状态：架构基线与初始化模板。**设计范围不等于已实现功能**；代码完成度、实际执行的检查、未完成验证，分别见 `docs/IMPLEMENTATION-STATUS.md` 和 `docs/VALIDATION-REPORT.md`。

> 第87节界面实现更新：Web采用共用页面容器和静态组件注册，支持标准/多页签布局与浅深主题。业务能力仍按Java模块、Connector与Rust受控执行边界接入四个启动单元；不是运行时安装任意前后端插件。实际扩展步骤见[新增能力](../development/adding-a-capability.md)，页面缓存和身份生命周期见[前端规范](../development/frontend-guidelines.md)。

> 第111节更新：标准指标目标1.1固定完整标识与映射摘要，纯Java计划执行精确来源键、归一化、范围及固定维度，工作流保存/只读预览/发布/比较与前端抽屉已接通。输入和业务写入仍分开，真实指标来源、持久输出和后台任务继续；不增加单元或数据库。见[ADR-073](../adr/073-standard-metric-workflow-targets.md)和[实现状态](../IMPLEMENTATION-STATUS.md)。

> 第110节更新：来源采样的完整映射pin、可信兼容维护及原回执已落地；网络前后检查固定语义，旧绑定需显式固定。继续使用既有Java模块、telemetry表和四启动单元，没有隐藏执行后端。见[ADR-072](../adr/072-explicit-metric-mapping-pins.md)。

> 2026-09-27产品范围补充：用户要求首版提供内置实体/指标、自定义实体类型/字段/关系类型、数据源中心/配置抽屉、可编辑清洗转换画布和实体关系管理。AI协助只预留扩展位。后续实现以[接入工作台设计](integration-studio.md)与[ADR-054](../adr/054-integration-studio-model-catalog.md)为补充，不把文档设计当作当前能力。

> 第68节实现更新：模型定义中心、PG私有草稿/不可变版本与默认清洗预览已落地；来源中心、v2画布及关系实例仍待开发。见[实现状态](../IMPLEMENTATION-STATUS.md)及[模型操作](../runbooks/model-catalog.md)。

## 0. 关键决策

| 决策 | 本版确定的边界 |
|---|---|
| 产品与仓库名称 | OpsWeave / 观织，仓库 `opsweave`；v4 是文档版本，不写进仓库名 |
| 首个业务闭环 | CMDB/Zabbix → Entity/Metric/Alarm → Incident → 只读诊断 → 有证据的 AIInsight |
| 语言 | Java 管平台业务，Rust 管 Agent 执行，TypeScript 管控制台 |
| Agent 基础 | 采用 Rig；不从零实现模型协议、完整 Agent SDK 或通用工作流框架 |
| Agent 业务 | 自己实现 Context、Evidence、预算、授权边界、Skill 管理和诊断流程 |
| Tool | 内部受控领域 API 为主；MCP 是额外适配边界；不默认给 Shell、任意 SQL/HTTP |
| Skill | 第一版受控配置包，绑定预置 Rust 流程；不提供任意代码或容器上传 |
| 执行方式 | 固定流程优先；需要探索时有限轮次 Agent；专家团不是基础依赖 |
| 部署 | 逻辑领域模块化，先四个启动单元；不是一开始几十个服务 |
| 治理 | 多源 observation + 字段权威；Incident 聚合故障；证据与结果可追溯 |
| 可靠性 | 至少一次交付、幂等、对账、Outbox/Inbox；不声称跨系统 exactly-once |
| 默认安全 | 关闭未实现能力；Demo 必须显式启用，只读、回环地址、合成数据 |

**假设**：小团队、私有化优先、未来支持多租户；目前未提供真实吞吐、资源数、保留期、部署预算和可用性目标。本文的上限与容量公式用于规划，不构成性能承诺。高可用、生产安全、灾备和企业合规均须独立验收。

## 1. 总体架构与职责

```mermaid
flowchart TB
  UI[Web Console / TypeScript] --> BFF[Platform API / Java]
  SRC[CMDB · Zabbix · 后续 K8s/OTLP] --> ING[Ingestion Worker / Java]
  ING --> INT[Parse · Map · Resolve · Validate · Reconcile]
  INT --> DOMAIN[平台领域服务与统一模型]
  BFF --> DOMAIN
  DOMAIN --> TOOL[受控 Tool Gateway]
  BFF --> RUN[Agent Runtime / Rust]
  RUN --> TOOL
  RUN --> CTX[Context / Evidence / Skill]
  CTX --> RIG[Rig 适配层]
  RIG --> MODEL[模型提供商或本地模型服务]
  RUN --> RESULT[AIInsight / 派生结果]
  RESULT --> DOMAIN
  RUN -.动作建议，不直接执行.-> PROPOSAL[ActionProposal]
  PROPOSAL --> POLICY[策略 · 审批 · 执行前复核]
  POLICY --> EXEC[Automation Executor]
  DOMAIN --> BUS[持久任务 / Outbox；规模扩大后 Kafka]
  BUS --> RUN
```

图表示目标架构；模板里的 Rust Demo 使用 fixture，不会假装已经调用 Java 平台，也不返回“后台任务已受理”。

### 1.1 四个启动单元

| 启动单元 | 语言 | 当前与目标职责 |
|---|---|---|
| `platform-api` | Java | 控制面、领域 API、IAM、资源/故障、Tool Gateway、Skill 发布；模板业务接口默认拒绝访问 |
| `ingestion-worker` | Java | 采集、解析、映射、同步游标、对账；模板保留 SPI，真实采集器待实现 |
| `agent-runtime` | Rust | Agent/工作流、上下文、证据、Tool 调度、模型适配；提供本机只读 Demo 源码 |
| `web-console` | TypeScript | 模块化控制台与本机诊断表单；资产/故障业务 UI 待实现 |

对外统一走平台鉴权边界；正式部署不把内部 Runtime 裸露给浏览器。模板为本机开发提供 Vite 代理，不代表生产 BFF 已完成。

### 1.2 谁拥有数据

Java 的 `ai-control` 拥有 Skill 定义、发布版本、模型策略、预算配置；Rust 拥有 AIRun、StepRun、Checkpoint 等执行状态。最终 AIInsight 是平台业务资源，由平台验证、保存和授权访问。数据库可共用 PostgreSQL 集群，但 schema、角色和写入所有权必须分开，不能双方随意改同一张表。

## 2. 技术选型与版本策略

| 范围 | 选型 | 说明 |
|---|---|---|
| 平台后端 | Java 21 + Spring Boot 4.0.8 | 延续 v3 基线，不宣称是最新版本；4.x 的 Java/Gradle 要求参见 [S1] |
| Java 构建 | Gradle 8.14.3 | 初次生成真实 Wrapper；审查依赖锁与校验元数据 |
| Rust 服务 | Tokio + Axum 0.8.9 | I/O 并发与 HTTP 服务；不是 CPU 算法加速保证 [S2][S3] |
| 模型与 Agent | Rig 0.42.0，可选编译 feature | 使用 root `rig` facade，隔离框架类型；只锁直接版本还不等于锁完整依赖树 [S4] |
| MCP | 官方 rmcp 3.4.0，独立适配 | 模板仅有管理员本机 probe，未接入在线 Agent 工具集 [S5] |
| Rust 契约 | Serde / serde_json / jsonschema 0.56.0 | JSON Schema 运行时校验；禁用远程 schema 引用，防止隐式外连 [S6] |
| Rust 平台 HTTP 客户端 | reqwest，正式接入时增加 | 当前仅定义 `PlatformReadPort`，避免假实现一个成功返回的 HTTP 客户端 |
| Rust 持久化 | SQLx + PostgreSQL，后续任务 | 当前没有实现 RunStore；不能把 async 函数当成持久任务引擎 |
| 前端 | Zeus 0.1.1-beta.2 + Zeus UI 0.1.0-beta.4 + TypeScript 5.9 + Vite 8 | 原生 Web Components；pnpm-lock 固定。不走 React/Vue 包装 [S7][S8] |
| 图表/拓扑/流程编辑 | 按页面经适配层引入；默认不捆绑 React 组件库 | 产品层选型，逐项验收。ECharts / Cytoscape 等不是模板已实现功能 |
| 元数据 | PostgreSQL | 配置、身份、实体、关系、故障、Skill、执行元数据 |
| 指标 | VictoriaMetrics | 用 Metric API 隔离具体存储；小规模单节点，大规模评估 Cluster [S9] |
| 日志/分析 | ClickHouse，按需部署 | 不同时默认部署多套日志引擎 |
| 缓存 | Valkey，按需部署 | 不保存唯一一份运行状态；所有缓存必须带租户与权限边界 |
| Raw / Evidence 大对象 | S3 兼容接口 | 本地开发可使用文件适配；实现选型单独评估，不绑具体厂商 |
| 向量知识 | pgvector，知识库上线时增加 | 向量索引不是日志/指标主库 |
| 消息 | Lite 持久任务/Outbox；规模扩大后 Kafka | 同步查询不必绕 Kafka；高频遥测不都塞业务 Outbox |
| 部署 | Compose 开发；Kubernetes/Helm 目标 | 本模板不是生产 Helm chart；不默认上 Service Mesh |

### 2.1 Rig 与 rmcp 的兼容边界

Rig 0.42 区分 `rig-core` 的可移植协议/提供商能力、`rig-agent` 的执行能力，由 `rig` 统一导出。本次使用的是新 facade，而不是把旧版 `rig-core` 简单改别名。以**固定发布版本文档与编译结果**为准，不混用 `main` 文档的新 API。[S4]

模板不启用 Rig 内置的 `rmcp` feature；它依赖的 rmcp 主版本可能与独立 SDK 不同。外部 MCP 结果必须先转换成 OpsWeave Tool DTO，再通过统一授权和输出校验，不能直接把两套 SDK 的类型互传。

### 2.2 锁定策略

`Cargo.toml` 固定关键直接依赖；`Cargo.lock` 固定实际解析的完整图。模板未伪造 lockfile，也未随包带未经生成的 wrapper.jar。初次在可联网/有内部镜像的可信环境运行 bootstrap，生成并审查 Cargo、pnpm、Gradle 锁文件，然后固定已验证 Rust toolchain 精确版本与生产镜像 digest。当前 `rust-toolchain.toml` 的 `stable` 是 bootstrap 选择器，**不是可重现构建保证**。

生产 CI 必须使用 `--locked` / `pnpm install --frozen-lockfile`，检查来源、许可证、SBOM、漏洞和 SDK 升级后的契约回归。平台项目许可证尚未选择；模板不替用户决定开源或商业授权。

## 3. 模块化仓库与依赖规则

```text
opsweave/
├── apps/
│   ├── platform-api/             # Java 启动装配
│   ├── ingestion-worker/         # Java 采集进程
│   ├── agent-runtime/            # Rust 单 crate，内部按职责分层
│   └── web-console/              # Zeus / TypeScript
├── modules/                      # Java 领域模块
│   ├── shared-kernel/
│   ├── identity/
│   ├── catalog/
│   ├── inventory/
│   ├── integration/
│   ├── telemetry/
│   ├── alerting/
│   ├── incident/
│   ├── ai-control/
│   ├── automation/
│   └── audit/
├── contracts/                    # 跨语言协议唯一来源
├── extensions/                   # 版本化 Connector / Mapping / Skill / Policy
├── db/                           # SQL 原型，不自动当生产迁移运行
├── deploy/                       # Compose、Docker、部署约束
├── scripts/                      # 初始化与验证工具
├── tests/                        # 契约/架构/Java领域/场景
├── docs/                         # 主设计、ADR、运行手册、实现状态
├── Cargo.toml
├── settings.gradle.kts
├── package.json
├── pnpm-workspace.yaml
└── pnpm-lock.yaml
```

Java：`api → application → domain`，infrastructure 实现 application 定义的端口；domain 不引用 Spring、数据库驱动、HTTP 客户端或模型 SDK。跨域通过公开接口和事件通信，禁止随便访问其他领域的表。

Rust：`api / workflows / context / policies / ports / domain / adapters / skills`。`domain` 不引用 Rig、rmcp、Axum、SQLx。先一个 crate，出现多个二进制共享、发布边界或编译需求再拆，不为每个文件建 crate。

TypeScript：只依赖公开契约，不复用后端数据库实体。v1/v2 JSON Schema 共存且明确版本。代码生成可以添加，但不能把生成文件变成手工维护的第二套真相。

## 4. 领域数据模型

### 4.1 资源与身份

| 对象 | 含义与关键约束 |
|---|---|
| Tenant / Principal | 身份与租户由可信边界构造；不能接受 LLM 自报 tenantId |
| EntityType | 属性 schema、关系约束、身份策略；允许扩展但须版本化 |
| Entity | 平台 ID、类型、生命周期；不能使用 zabbix.hostid 作为全局主键 |
| ExternalObjectKey / ExternalLink | `(tenant, sourceInstance, externalType, externalId)` 唯一，防止不同 Zabbix 实例 ID 冲突 |
| Observation | 某来源在某时刻看到的属性值，包含 observedAt/ingestedAt、来源与质量 |
| FieldAuthorityPolicy | 按字段确定权威来源、过期窗口、冲突策略，不采用“最后写入者必胜” |
| Relation / RelationObservation | 有向关系、来源、有效时间、观察时间；保留历史拓扑 |

实体识别不能仅依赖 IP 或名字。优先使用明确作用域下的云实例 ID、K8s UID、可靠 UUID/资产标识；`Default string`、空 SN 等不当强 ID。IP 要带网络/租户/时间作用域。逻辑 Service 与物理 Host 是不同实体，不能因为同名合并。

低可信匹配进入人工处理；合并必须可审计、可纠正。某来源下线只改变其 Observation 的有效性，不直接删除其他来源仍确认的实体。实体软删除后历史 Metric、Incident、Evidence 仍可引用。

### 4.2 遥测

| 对象 | 关键内容 |
|---|---|
| MetricDefinition | 名称、单位、数值类型、Gauge/Sum/Histogram、单调性、temporality、适用实体、允许维度、聚合语义 |
| MetricSeries / MetricPoint | 定义版本、实体或资源属性、维度、时间、数值/分布、起始时间、来源 |
| LogRecord | eventTime、observedTime、body、severity、attributes、entityRef、traceId/spanId |
| Trace / Span | 跨服务追踪关系；第一阶段可只保留引用，后续接入 OTLP |
| DataQuality | 数据新鲜度、缺口、覆盖率、映射失败、时钟偏移、来源健康 |

标准化要保留指标语义，而不是只统一字段名。OTel 指标数据模型区分 Sum、Gauge、Histogram 及时间语义；适配时需要保留这些含义。[S10]

例：Zabbix `system.cpu.util[,user]` 是用户态 CPU 利用率，不等于整个 CPU 使用率。若内部标准采用 0–1，输入 0–100 时要显式 `/100` 并记录转换。Prometheus 的累计 CPU seconds 不能只改名字就当成百分比，需要正确的 rate、CPU mode 与聚合规则。

p95/p99 不能简单对多实例 percentile 求平均得到全局 percentile；应基于可合并分布或明确查询算法。发生 counter reset、NaN、缺失点、乱序点时有明确策略。摘要必须带采样区间、聚合粒度和覆盖率。

未解析实体的遥测可进入待关联区或暂以资源属性引用，不能为了补齐 entityId 随意创造并合并资源。

### 4.3 事件、告警、变更与故障

```text
Event：发生过的事实，原则上不可变。
AlarmRule：规则定义；AlarmInstance：一次告警生命周期。
AlarmTransition：触发、确认、恢复等变化。
Change：发布/配置/基础设施等变化，有前后版本和操作者。
Incident：一个故障处理对象，聚合多个 Alarm、实体、Change、Evidence 和 AIInsight。
```

不要把“Alarm 全部恢复”自动等同于“Incident 已解决”。维护窗口、静默、抑制与通知策略要区分；数据仍可保留，但默认不触发无限 AI 诊断。Zabbix trigger 是规则，不是告警实例；problem/recovery event 是生命周期事实，需要关联而不是各生成一个互不相干的告警。

### 4.4 AI 业务对象

| 对象 | 所有者/职责 |
|---|---|
| ToolDefinition | 版本化契约、effect、权限、资源选择器、实现状态、Capability、预算 |
| SkillDefinition / SkillVersion | 配置、工作流模板、工具白名单、Context 策略、发布审批 |
| Conversation | 最近对话与摘要，不等同于执行状态 |
| AIRun / StepRun | 任务状态、Skill digest、步骤、尝试次数、预算、结果引用 |
| Checkpoint | 可恢复状态、schema版本、租约 fencing token；不是 SDK 对象随便序列化 |
| ContextSnapshot | 本次模型实际看到的有限信息，以及 omitted/missing 项 |
| Evidence | 来源、权限、时间范围、可用时间、过期时间、摘要/原文引用与数据血缘 |
| AIInsight | 观测、候选解释、证据、缺口、限制；引用有效不等于结论属实 |
| ActionProposal | 修改动作建议、原因、证据、目标与预期结果；不包含可伪造的审批结论 |

不把模型自己报的 `confidence=0.95` 当成校准后的概率。证据强度、人工认可、预测校准等可以独立评估；无校准时只标明候选原因及证据缺口。

## 5. 多源接入与处理流水线

### 5.1 三条不同处理通道

```text
资产/配置：Connector → Raw → Parse/Map → Resolution → Observation → Entity/Relation 投影
高频遥测：Connector/OTLP → 类型/单位校验 → 轻量标准化 → 批写时序/日志存储
业务事件：Connector → Normalize → Outbox/Inbox → Alarm/Change/Incident 状态机
```

不要每个指标点都同步查 CMDB、运行实体合并、调用 LLM 或执行复杂 DAG。元数据缓存/映射表须带版本，按来源失效更新；热点链路做批量处理。

### 5.2 Zabbix 的适配

`host/item/trigger` 元数据与历史/事件数据通常需要分别获取并关联。`item` 不总是数值指标，日志、字符和文本应按类型路由。`history` 记录不能假设天然带 host、key、完整单位；具体版本 API 和返回字段以该版本 Zabbix 官方文档为准。[S11]

Connector 管认证、限流、分页、游标、快照完成标识、退避和可观测性；Mapping 管字段/类型/单位变换；Resolver 管实体身份；Writer 管领域幂等写入。单个 Connector 出错不应拖垮整个接入池。

### 5.3 配置与扩展

Mapping 声明式化，但不承诺任意数据源都无需代码。协议、分页、增量一致性仍可能需要新 Connector。管理员只能组合允许的解析与转换节点，不可在第一版上传任意脚本、SQL 或 Shell。

Pipeline v1 采用有界阶段链；少量路由/分支可以配置。不立刻开发通用 BPMN/无限 DAG。Connector、Mapping、目标 Schema、领域规则版本一起记录进 Lineage。

### 5.4 对账、删除、重放

增量同步 + 周期全量对账。只有完整、成功、具有足够权限的快照才能触发缺失判断；来源超时、鉴权失败、分页中断不能当“全部资源已删除”。先将来源观测标记为 stale，再按策略决定资源状态。

重放必须声明目的：修复规范化数据、重建投影或重新分析。默认禁用通知、自动动作和重复计费触发；记录 replayRunId、原流水线版本、新版本、dryRun 和影响范围。高频 Raw 是否全量留存按成本策略决定，不承诺所有数据无限保存。

## 6. AI 运行时：自研与现成组件的分工

```text
自己开发：
  Context 选择/压缩、Evidence、Skill 配置/发布、领域 Tool、授权、预算、Incident 用例、评测。

采用现成：
  Tokio/Axum、Rig 的模型/Agent 能力、rmcp 协议、Serde/JSON Schema、数据库客户端。
```

Rig 不替平台完成租户隔离，也不替你保证跨系统动作 exactly-once。Agent SDK 的 checkpoint 支持可复用，但持久存储、恢复校验、权限撤销和副作用对账仍属于 OpsWeave。模型上下文窗口也不是无限数据库。

### 6.1 推荐的执行方式

普通查询：确定性 API/Tool 直接返回。摘要：确定性查数 + 一次模型生成。标准诊断：预置工作流 + 并发采证 + 模型候选分析。深度调查：允许有限探索、附加 Tool 查询和逐步证据检查。只有确实需要隔离不同专业上下文时，才考虑多 Agent。

模板实现的是“标准诊断”最小骨架：

```text
可信 Demo 身份
 → 校验请求/权限
 → 并行 incident.get 与 metric.summary（合成适配器）
 → Context Pack
 → Mock 或可选 Rig 模型步骤
 → JSON Schema + 证据引用校验
 → 返回 RunResult（同步、不持久化）
```

没有生产鉴权、真实数据或持久化的能力一律不冒充完成。不开启 Runtime 时只提供健康接口；无配置时不自动选外部模型。

### 6.2 Framework Adapter

`ModelPort` 不暴露 Rig 类型；`PlatformReadPort` 不暴露 Java DTO/数据库对象；以后使用 rmcp 也先转成 ToolResult。ModelPolicy 是能力、预算、地域与租户准入策略，不是只有一个模型字符串。结构化输出、工具调用、流式响应等能力应由接入测试确认，不假定所有兼容接口行为一致。

模板提供 `rig-provider` feature，显式指定模型名和 API Key，并设置模型外连授权。默认 Mock 无付费请求；不得把 Mock 当真实模型故障后的隐式 fallback。

## 7. Tool 与 MCP

### 7.1 工具分层

| 层 | 示例 | 实现原则 |
|---|---|---|
| 原子领域 Tool | entity.get、metric.query、log.search | 权限、范围、返回上限、明确错误 |
| 语义 Tool | metric.summary、incident.context、topology.impact | 用确定性代码批查/聚合，减少模型轮次 |
| Action Tool | deployment.rollback、alarm.ack | 进入 ActionProposal 与审批，不直接执行 |
| 通用能力 | shell、任意 SQL、任意 HTTP | v1 不提供；后期沙箱也不能替代资源授权 |

Tool 数量不设拍脑袋上限，重点是可发现性、语义清晰、授权和单次暴露数量。第一版可从少量高价值只读工具起步。按 Skill 与当前权限筛选；搜索结果/向量检索只能召回候选工具，不能授予权限。

ToolDefinition 至少包含 id/version、input/output schema、effect、requiredPermissions、requiredCapabilities、resourceSelector、executor、deadline、maxResultBytes、实现状态与幂等语义。`registered` 不代表 `implemented`，更不代表当前用户 `authorized`。

### 7.2 每次调用的信任链

```text
模型/工作流提出调用
 → Schema 与输入上限
 → Skill允许工具 ∩ 主体权限 ∩ 租户策略 ∩ 资源授权
 → 并发/总预算/取消检查
 → Native API 或受控 MCP 适配器
 → 平台/上游再次鉴权
 → 输出校验、脱敏、时间/租户/资源范围检查
 → Evidence / Artifact 引用
```

Tool 输入不包含 tenantId、permissions、approvalStatus 等可信控制字段。Tool 返回 untrusted 数据，同样可能存在注入。MCP 的描述与只读注解不能视为安全证明。MCP 支持工具接口及 Schema，但业务许可必须由平台执行。[S12]

MCP server 准入、TLS、认证、跳转、外连目的地、凭据作用域、响应体限制和工具版本变化必须治理。不要把平台用户 Token 直接透传给任意 MCP 服务。模板的 probe 只用于显式管理员联调，既不执行 shell，也不对 Agent 暴露任意 URL。

## 8. Skill 平台化范围

### 8.1 第一版

内置 Skill + 复制修改、Prompt、输入输出 Schema、Context 需求、允许 Tool、知识范围、模型策略、预算、测试、版本、发布、禁用、运行记录。主要面向平台管理员，不把普通用户变成代码发布者。

Runtime 只执行预置模板，例如 `readonly-incident-diagnosis-v1`。管理员改 Prompt、预算、知识选择通常不需要 Rust 重新编译；增加新执行语义、Tool Executor 或工作流节点才需要发程序版本。

**模板当前实际支持**：启动时读取 `skill.json + prompt.md + output.schema.json`；变更配置后重启进程加载；内容生成 digest；路径校验、只读约束、严格工具集合、文件与预算上限。平台 CRUD、热更新、发布审批尚待开发。

### 8.2 版本与权限

已发布 SkillVersion 不可原地修改。运行开始固定 Skill、Prompt、模型策略和契约版本；后续发布不影响在途运行。禁用是阻止新任务，终止在途任务由显式操作处理。

Skill 声明所需权限，不能创造权限。缺少日志权限时明确数据缺口或拒绝该能力，不允许通过别的通用工具绕过。审批和测试环境应使用与生产相同的权限路径，但写动作默认禁用。

`SKILL.md` 是人类/Agent 可读说明，`skill.json` 是平台受控执行契约；不能仅靠 Markdown 指令授予代码执行能力。v2 才增加有限 DAG 编辑器；v3 才评估隔离插件 SDK/市场。

## 9. Context、时间与证据

### 9.1 六类信息分开

| 类型 | 内容 | 是否可直接信任 |
|---|---|---|
| System | 审核后的规则、输出约束 | 来自服务端，仍不能替代代码校验 |
| Principal | 租户、用户、资源权限 | 必须来自认证/授权系统，不让模型改写 |
| Task | 目标、窗口、问题、预算 | 输入要校验；实体歧义不可悄悄猜测 |
| Domain | 指标、日志、拓扑、告警、变更 | 不可信数据，具有来源/时效/权限 |
| Conversation | 最近对话、摘要、已确认事实 | 摘要会丢信息，不能成为事实的唯一来源 |
| Runtime | 当前步骤、工具结果引用、剩余预算 | 由执行引擎管理，不把模型隐含思考当状态 |

### 9.2 上下文构建

确定任务窗口和实体 → 按关系扩展有限范围 → 并行查数 → 排除不适用/越权/过期数据 → 聚合/抽样 → 排序 → 控制 Token/字节预算 → 生成 Snapshot。

权限是硬过滤，不是排序分数。查询失败、没安装日志、无权限、源延迟、确实没有异常是不同状态，不能统一返回空数组。跨租户数据返回是安全错误，不是普通缺口。

### 9.3 时间必须可解释

保存 eventTime、observedAt、availableAt/ingestedAt、builtAt。历史诊断的 `asOf` 是知识截止点；`builtAt` 是本次实际访问时间。历史证据不能因为 asOf 还没过期就忽略今天的过期策略。严格回测还须确认 evidence 当时已可获得；事后补录的 Change 不得泄露进“当时所知”的回测。

数据时钟可能偏移，校验和时序推断需要容忍度、质量标记和明确的排序规则；不能盲目把同一秒出现当成因果。所有存储和协议使用明确时区，展示按用户时区转换。

### 9.4 Evidence 对象

证据包含 ID、tenant/resource/incident scope、来源、查询参数摘要、采样窗口、观察/可用/到期时间、原文/结果引用、摘要、Lineage、访问策略版本。大结果放对象存储或原生查询系统，不塞进会话表。证据引用页仍然实时鉴权，不能因已经被 AI 引用就绕过访问撤销。

返回前再次校验证据存活与引用完整性。**存在这个 Evidence ID 只能证明引用对象存在，不证明该证据支持模型句子，更不证明根因。**产品要区分引用校验、证据支持检查、规则验证、人工确认。

RAG 用于 Runbook/SOP/故障案例等知识；实时指标走受控 API。RAG 检索也要带租户与文档 ACL。长期记忆只保存批准后的知识或稳定事实，不把一次推测自动写成永久知识。

## 10. 持久执行与分布式

### 10.1 目标状态机

```text
queued → running → succeeded / failed / cancelled
                 → waiting_approval → queued（恢复后重新鉴权）
```

AIRun 记录输入摘要、租户/用户、Skill digest、模型策略、截止时间、预算与结果引用。StepRun 记录每步输入/输出引用、重试和耗时。RunState 和 Conversation 分离。

持久任务需要：原子 claim、lease、heartbeat、fencing token、compare-and-set 状态更新、幂等键、失联回收与取消。旧 worker 丢失租约后不能继续提交结果。Rust future 取消只能取消本地等待，不保证模型提供商停止计费或远端动作没有发生。

### 10.2 交付语义

业务写入与 Outbox 在同一个事务提交；消费者 Inbox/幂等域键避免重复业务效果。批量指标写入按序列/时间/来源策略去重。Kafka partition key 选择实体/故障等稳定范围，不承诺全局顺序。

重放不能自动重复通知和副作用。Action 发出后超时进入结果未知状态，先查目标状态/执行记录再决定重试；不能把“没收到响应”当作“没执行”。

### 10.3 部署规模

| 模式 | 建议组件 | 启用条件 |
|---|---|---|
| 本机模板 | Rust Demo；可选 Zeus 页面 | 验证流程与契约；不需要数据库和模型 Key |
| Lite 产品目标 | Platform、Ingestion、可选 Runtime、PG、按需指标库 | 完成真实领域接口与持久任务后 |
| Standard | 多副本 API/Worker、持久队列、缓存、日志 | 有明确负载/故障隔离需求 |
| Large | 分区 Worker、Kafka、各存储 HA、区域/租户隔离 | 经过容量与故障演练，而不是简单增加 Pod 数量 |

本模板的同步 Demo **不能多副本恢复任务**。只有完成持久队列与租约后才可宣称分布式执行可用。Kubernetes HPA 以可持续服务量、队列滞后和依赖压力综合决策，不只看 CPU。

## 11. AI 如何进入指标体系

源指标、派生指标、模型结果共用指标目录，但语义不能混淆。

- 异常分数/基线偏差：可以成为有定义的数值指标，带算法和版本。
- 预测：记录 `issuedAt / targetTime / horizon / modelVersion / interval`；不同发布时间的同一目标预测不能覆盖为一个点。
- 根因候选/解释：进入 AIInsight，不伪装成 Metric。
- 状态变化：进入 Event；告警由明确规则触发，不把所有 AIInsight 直接当 Alarm。

不把原值与预测写到同一序列，也不把异常分数当故障概率。预测区间/校准方法若没有就不伪造。模型版本通常放受控元数据或有限标签，不把 runId、prompt全文、日志原文作为时序标签制造基数爆炸。

避免 AI 反馈环：每个派生结果记录 producer、lineage、cause/run ID、generation depth。AI 默认不递归消费自己刚生成的告警/结果；有意启用时设置深度和速率限制。

## 12. 性能、成本与退化

主要优化方向是减少无意义模型往返、限制查询量、并行取数、确定性聚合与控制上下文。Rust 运行时适合做受控 I/O 并发，但并不自动加速远程模型；Tokio 也不是通用 CPU 密集计算调度器。[S2]

模板初始上限：全局 4 个并发诊断、单任务 30 秒、2 次预置 Tool、一次模型步骤、Context 16 KB、请求体 16 KB、模型原始结果 32 KB。这些是**防失控配置**而非性能实测。正式产品按 tenant、model、tool、source 多级限流，并设置有界队列。

服务端做聚合、日志模式提取、窗口比较；LLM 负责解释和探索。时间序列采样保留异常峰值，不用盲目抽样制造“看起来正常”。Context 预算应优先保留关键证据和数据缺口，裁剪要可观察。

缓存 key 至少包括 tenant、principal/resource authorization scope、查询参数、截止时间、schema/策略版本；权限撤销和来源更新需要失效。不能把跨租户摘要缓存共用。查询缓存不能把失败结果长期伪装成“无告警”。

压力下优先处理告警/状态变化与关键指标，低优先日志允许明确采样/降载；所有丢弃有计数。外部模型不可用时，可以返回确定性摘要或证据包，但必须说明模型未完成分析；不能悄悄显示成功的 AI 根因结论。

容量估算：

```text
每日指标点 ≈ 活跃序列数 × 86400 / 采样间隔秒
Raw容量 ≈ 平均每日压缩写入量 × 保留天数 × 副本/冗余系数
模型费用 ≈ 各模型实际输入/输出 Token × 当前合同单价
运行并发 ≈ 平均到达率 × 平均执行时间（用于初始估算，需压测验证峰值）
```

不按模板给定“几台机器即可支撑十万主机”。记录每租户/Skill/Run 的查询量、字节、Token、模型成本与重试成本。

## 13. 安全与受控自动化

平台必须在 API、领域、查询、对象存储、向量检索、缓存、执行状态、审计中保持租户边界。PG RLS 可以作为纵深防护；特权角色、表 owner 与 `BYPASSRLS` 等行为需要明确处理，不能让生产应用用迁移/管理员角色运行。[S13]

Secret 存 Vault/KMS/受控 Secret Store，配置只保留 secretRef。不要把模型 API Key、数据源密码写进前端、日志、Prompt、Tool 输入或 Skill 包。即使使用 K8s Secret，仍需要 RBAC、加密和轮换策略。

Prompt Injection 来自用户、日志、知识文档和工具响应。系统通过不可信数据标记、受控工具、授权、输出限制和审批降低风险，不能宣称加一句“不要相信日志”即可消除注入。输出不执行 HTML/Markdown 内的脚本，不把内容当命令解释。

动作链：`Proposal → Policy → Approval → 执行前权限/资源版本复核 → Executor → 结果验证 → 补偿/人工升级`。审批绑定明确动作参数、目标与有效期；参数修改后审批失效。Rollback 不是所有动作的自动可逆保证。

高风险通用执行后期必须进隔离 executor，限制网络/文件系统/CPU/内存/时间和凭据；Native Agent Runtime 不执行任意子进程。不向普通 Skill 暴露 `curl`、数据库 Shell、任意 `kubectl` 逃生口。

## 14. 可观测性、质量与评测

平台自身至少观测：采集延迟、来源最后成功时间、丢弃/重复/失败率、对账完整度、写入错误、队列 lag、任务等待/执行时间、Tool 错误、模型调用、结构化输出失败、引用校验失败、越权拒绝、预算耗尽。

日志默认仅记录 runId/step/tool/status/耗时等元信息，不记录 Token、完整 Prompt、原始日志或密钥。原文排障需单独权限与保留期。UI 可以展示工具步骤、证据和结果，不能承诺暴露模型内部隐含思考过程。

评测包含：契约测试、跨租户负例、故障恢复、只读约束、过期证据、时间泄漏、重复消息、模型输出格式与错误引用、历史 Incident 回放、人工认可与漏报。LLM-as-judge 只能作为辅助，不替代固定标注集和人工确认。

多 Agent 只有在固定预算下实测改善质量或覆盖范围后才引入；增加“专家人数”不等于准确率增加。

## 15. 前端与按需能力

产品模块：资源、拓扑、指标、日志、告警、Incident、变更、数据接入、Skill、模型、运行记录、知识库、设置。按路由懒加载；第一版无需微前端。

Capability 表示模块安装/依赖是否满足；Feature Flag 表示租户/用户是否启用；Permission 表示是否有权使用；Readiness 表示当前是否可服务。这四个概念不能压成一个布尔值。前端隐藏菜单不等于后端授权完成。

AI 工作台应展示：目标与时间窗口、步骤状态、证据可点击回查、候选原因、明确缺失数据、模型/Skill 版本、耗时/费用、取消与人工反馈。Skill 编辑界面再增加版本 diff、测试输入、失败记录与发布；不在第一版做万能编程 IDE。

模板只提供本机诊断表单和未实现模块提示；连接的是 Vite dev proxy → 本机 Rust Demo。静态 Nginx 镜像不自动建立生产 API 代理。控制台实现为 Zeus + Zeus UI 原生组件，见 ADR-012。

## 16. 数据保留、灾备与升级

Retention 按租户和数据类型配置：源 Raw、完整日志、细粒度指标、聚合指标、Incident、AI上下文、证据原文、摘要与审计可不同。选择期限要结合合同与成本，不能照搬一个固定天数。删除必须覆盖对象存储、搜索索引、向量、缓存和派生结果；法律保留/客户保留要求需显式冲突处理。

降采样不是简单平均所有指标：counter/histogram/percentile 要有对应语义。历史关系、实体软删除与 Source mapping 变化要能够解释历史查询。

数据库备份、对象存储备份/版本保护与恢复演练，分别定义 RPO/RTO。Schema 升级采用兼容字段、双读/双写过渡与回滚计划；in-flight AIRun 必须绑定可恢复的 Schema 与 Skill/SDK 版本。部署前先检查兼容性，不能升级后再碰运气反序列化 checkpoint。

插件升级有兼容范围、来源校验、签名策略、健康检查和回退。关闭插件时有任务排空与故障说明，不把任务永远挂起。

## 17. 本次模板交付的真实范围

| 内容 | 状态 |
|---|---|
| Java 模块边界、领域值对象、Connector SPI、领域 smoke | 有源码；依赖无关部分可本地验证 |
| Spring Boot 启动服务 | 有配置；业务默认 deny-all；完整依赖构建需验证 |
| Rust HTTP / Mock / Context / Skill / 引用校验 | 提供实现源码与测试；交付环境没有 Cargo，未执行 Rust 编译和测试 |
| Rig 模型适配 | 可选 feature 源码，显式外连开关；未编译、未付费调用 |
| MCP | 独立本机探测示例；未在线集成、未联调 |
| 前端 | 有诊断表单、开发代理和模块占位；完整 pnpm 构建需验证 |
| 真实 CMDB/Zabbix 数据、平台授权、Tool HTTP | 待开发，不静默回退 fixture |
| RunStore/Checkpoint/分布式 Worker | 状态设计与 SQL 原型，执行实现待开发 |
| Skill UI/发布、向量知识、自动化、生产 Helm | 待开发 |
| 锁文件/Wrapper | 必须在可信初始化环境真实生成并提交；未伪造 |

因此本模板是“有代码与契约的起点”，不是一套交付即生产上线的系统。技术选型已收敛；实现进度如实标注。

## 18. 实施顺序与验收点

**P0 初始化**：生成依赖锁和 Wrapper；格式化并编译 Rust 所有 feature；构建 Java/前端；完成静态和负例测试。先解决构建，不急着堆功能。

**P1 真实数据闭环**：OIDC/tenant/resource scope → CMDB/Zabbix Host → Observation/ExternalLink/Entity → MetricDefinition 与规范化数据 → Alarm/Incident。验收同实体跨来源映射、来源失败不误删除、单位转换与重复事件。

**P2 只读 AI 闭环**：Java Tool Gateway → Rust HttpPlatformReadPort → Context/Evidence → 单次模型摘要 → 平台保存 AIInsight。验收越权、证据过期、来源失败和输出校验；UI 必须可点击证据。

**P3 平台化 Skill**：CRUD、immutable version、测试集、发布、禁用、内容 digest、预算策略。Rust 只加载已授权/已发布包；不读任意用户路径。

**P4 可靠执行**：AIRun 持久化、租约、故障恢复、Outbox、取消、重放、上下文缓存与权限失效。验收 kill worker、超时、重复投递、恢复后授权撤销。

**P5 算法与预测**：基线/异常检测/预测作为独立能力，必要时添加 Python 算法服务；记录 lineage 和预测时间语义，不改 Agent Runtime 的 Rust 选择。

**P6 自动化与复杂协作**：先明确动作审批和幂等，再评估有限 Agent 探索/专家团；自动化可独立部署和禁用，不成为资产监控的启动依赖。

---

## 19. 参考资料与核对边界

以下为本次技术核对使用的官方文档/项目维护方文档，核对日期 2026-09-21。文档可能更新，仓库最终以固定版本、锁文件和真实构建结果为准。架构取舍是 OpsWeave 的设计建议，不是文档承诺的性能。

- [S1] Spring Boot system requirements: https://docs.spring.io/spring-boot/4.0/system-requirements.html
- [S2] Tokio tutorial / I/O vs CPU workloads: https://tokio.rs/tokio/tutorial
- [S3] Axum 0.8.9: https://docs.rs/crate/axum/0.8.9
- [S4] Rig 0.42.0 facade, runtime choices and AgentBuilder: https://docs.rs/crate/rig/0.42.0 ; https://docs.rs/rig/0.42.0/rig/agent/struct.AgentBuilder.html
- [S5] Official rmcp Rust SDK: https://docs.rs/rmcp/3.4.0/rmcp/
- [S6] jsonschema 0.56.0, format validation and reference retrieval: https://docs.rs/jsonschema/0.56.0/jsonschema/
- [S7] React versions: https://react.dev/versions
- [S8] Vite getting started and Node requirements: https://vite.dev/guide/ ; https://vite.dev/blog/announcing-vite8
- [S9] VictoriaMetrics Cluster: https://docs.victoriametrics.com/victoriametrics/cluster-victoriametrics/
- [S10] OpenTelemetry metrics data model: https://opentelemetry.io/docs/specs/otel/metrics/data-model/
- [S11] Zabbix item/history/event API: https://www.zabbix.com/documentation/current/en/manual/api/reference/item/object ; https://www.zabbix.com/documentation/current/en/manual/api/reference/history/get
- [S12] MCP tools: https://modelcontextprotocol.io/specification/2025-11-25/server/tools
- [S13] PostgreSQL Row Security: https://www.postgresql.org/docs/current/ddl-rowsecurity.html

2026-09-27 §70：原生v2数据转换工作流复用Java平台、catalog模型和现有PG；单链白名单算子、草稿/CAS、固定模型pin、15分钟预览发布门禁、运行元数据回执及Zeus画布已实现。所有工作流运行均dry-run，指标/关系/实体实例输出和自动采集绑定尚未接通。旧Host v1与Rust诊断边界保持不变，详见[ADR-055](../adr/055-native-transform-workflows.md)。

2026-09-28 §71：数据源中心接入配置与初版工作流共用Java/PG事务；创建时来源模式与模型pin持久保留，可信身份/来源授权不由前端替代。不启用采集，不新增启动单元，详见[ADR-056](../adr/056-source-center-onboarding.md)。

2026-09-28 §74：工作流视图接入 AntV X6，关系类型和资产一跳只读视图接入 G6。图引擎为 TypeScript 适配器，不进入跨语言持久契约；Java 新只读关系查询复用平台、可信对象范围和 PG 当前快照。关系实例采集/编辑和 AI 编排仍未实现，见[ADR-058](../adr/058-antv-workspace-navigation.md)。

2026-10-01 §80：来源确认与工作流输出分离，v2支持ENTITY固定模型pin及LOG/METRIC固定格式的只读转换预览。遥测不强制entity.read，来源/租户/预算继续由Java边界检查；Zabbix Host批次不冒充日志或指标。保留四单元、PG和ENTITY历史摘要，不新增持续采集/存储写入或AI节点，见[ADR-060](../adr/060-source-first-typed-workflow-output.md)。

2026-10-02 §89：v2扩展有界DAG编辑与确定性MERGE；Java平台提供已发布ENTITY流程的显式写入和本地dev任务启停，消费既有Zabbix新完成批次，不发起采集。固定版本/输入回执、稳定幂等键、每对象授权与PG事务锁覆盖停止/写入，任务与执行历史仅元数据。未新增启动单元/数据库/任意脚本；生产后台身份委托、日志/指标持续写入、关系输出仍缺，见[ADR-061](../adr/061-editable-transform-graphs-and-runtime.md)。

2026-10-03 §100：接入实例检查以固定配置版本/摘要显式执行，integration事务先持久化PENDING，既有Host Connector在事务外有界读取，终态与原键回读复用原数据库；不增加启动单元、任意URL或采集调度。字段发现只存类型/缺失，不存客户值，部分与未知不冒充完整；真实env凭据尚未固定版本，检查不作为可复用发布pin。生产后台委托和多副本执行仍待验收，见[ADR-063](../adr/063-pinned-source-inspection-metadata.md)。


## 第118节：固定主机周期采集

周期服务只调度既有单轮扫描，V045保存低频周期元数据与原控制快照；完成后等待60–900秒，不追赶补跑。每次启用/恢复20批，后续扫描共用原短时授权和已用额度。停止与扫描同租户事务串行，UNKNOWN和来源失败保留原批次并暂停；固定版本和代次变化不接管。四启动单元保持，分布式租约/fencing、生命周期删除及长期委托继续。见[ADR-080](../adr/080-bounded-periodic-host-scans.md)。


## 第119节：模型兼容性报告与固定引用

既有Java平台增加自己的已保存模型候选差异检查和固定 ID/revision/digest 的授权关系/工作流/本人任务引用，沿用四启动单元和现有PG表，不增加执行框架或数据库。报告是当前元数据，发布事务仍重新验证CAS、摘要、最新版本和端点；没有跨目录/任务一致快照或隐式迁移。Web采用列表、精确全称链接、按需引用及独立纯展示组件；权限过滤不能报告租户全局零引用。正式边界见[ADR-081](../adr/081-model-revision-review-and-fixed-references.md)、[契约](../../contracts/model-impact.md)和[模型影响操作](../runbooks/model-impact.md)。日志输出、完整分页/迟到、破坏性模型迁移及生产HA/质量/保留/容量继续。


### 受控日志批次输出边界（第120节）

按既有可选日志存储决策接通ClickHouse适配与独立logs基础设施profile，业务应用仍保持四个启动单元。当前仅已发布MANUAL_SAMPLE→LOG的最多5条批次，Java纯领域执行时间/数量/权限检查，PG V046保存元数据与原确认，正文只进入日志引擎。请求不能携带tenant/身份/SQL/端点；固定查询使用类型参数、限定原scope并显式FINAL，精确摘要/索引回读后才确认。输出与指标共用有界IO预算，不逐记录持久编排。

独立log.read/log.write经可信边界，默认关闭日志库且不扩大默认身份权限。PENDING预受理、一次外部尝试、UNKNOWN只读确认和原UUID重放已接通；不增加后台补写、通用动作工具或新的应用服务。外部日志来源、连续日志checkpoint、保留/备份/质量和生产HA仍未完成。详情见[ADR-082](../adr/082-confirmed-workflow-log-storage.md)、[日志输出契约](../../contracts/workflow-log-output.md)与验证报告§120。


## 2026-10-04 有界指标分页与迟到窗口

Java持续执行器延续固定来源/算子/映射、可信身份和四启动单元，60秒窗口完整分页至600条来源记录；历史请求最多20次、整个读取20秒，原子性仅限本次有界完整读取。下一窗就绪先回看上一分钟，保留旧点摘要，以独立补采证明写新增点；确认补采不推进cursor或confirmedWindows。V047仅扩大元数据边界，旧证明不重写；点值不落PG，补采共用原限时授权和批次额度。实际验收见报告§121及[ADR-083](../adr/083-bounded-metric-pagination-and-late-points.md)。更早迟到、历史修订、停止后的补采、生产容量与租约/fencing仍为独立后续门槛。
