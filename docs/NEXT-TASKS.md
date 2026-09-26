# 首轮研发任务清单

规划里程碑与 Issue 拆分见 [ROADMAP.md](ROADMAP.md)。本页只列**当前可启动**的工作，不把未验收能力写成已完成。

## 最新增量（第56节，2026-09-26）

被拒写尝试已可审计：`integration.rejected_write_attempt`（V027）记录补充来源链两类人工写（字段审核、绑定更正）的**拒绝**——稳定 `reasonCode`、被拒操作的白名单标签、actor、尝试过的字段名、时间与 tenant/source；**不记录字段值、厂商报文、异常消息**。两个存储装饰器在内存与 PostgreSQL 路径记录后原样重抛同一异常（审计不是第二道闸门，也不是隐藏重试），成功路径不写，非配置来源不写，每 tenant/source 保留最近 500 条（`OPSWEAVE_REJECTED_WRITE_MAX_PER_SOURCE` 只能收紧），写入与清理同一事务。新增只读入口 `GET /api/v1/integrations/cmdb/rejected-writes`（权限沿用写入所需的 `entity.read`+`entity.manage`+源级 `source.sync`；仅 `source.sync` 为 403，未配置为 503，未认证为 401）。证据：领域 `RejectedWriteAuditSmoke` 37 项（本机 1108 项/40 个 main 全绿）、契约 1 类 Schema + 40 项用例、`PostgresRejectedWriteAttemptIT` 5 项、`RejectedWriteAuditHttpIT` 4 项；契约与 Java 在本机无法执行（无 pytest 依赖、无 PostgreSQL 与完整 Gradle 缓存），由 CI 实测 **contracts 与 java(219 tests) 全绿**，迭代中修掉 5 处新代码缺陷（详见验证报告第56节）。见[验证报告第56节](VALIDATION-REPORT.md)与 [ADR-049](adr/049-rejected-write-audit.md)。

**下一项（本地可做）**：① 被拒审计的 Web 只读面板（接口与契约已就绪）；② Incident 时间线分页与留存上限；③ 观测/快照/审核与更正回执的租户级总量预算与显式清理入口（形状复用 ADR-034 的预览→确认→回执）；④ 遗留 `RUNNING` 运行的可观测与人工标记（不做自动改写）。真实环境一旦可用，按 [real-acceptance runbook](runbooks/real-acceptance.md) 先跑 S1–S7 并附报告。

## 前一增量（第54节，2026-09-26）

扫描运行记录已**有界化**：`ScanRunRetention` 定义保留预算（每个 tenant/source/objectType 1000 行、每租户 5000 行；`OPSWEAVE_SCAN_RUN_MAX_PER_SCOPE/PER_TENANT` 只能收紧，越界或不一致在启动时按 `INVALID_REQUEST` 拒绝），两个存储适配器在**打开扫描的同一事务里、写入新行之后**清理最旧的“可删除”行。仍在 `RUNNING` 的运行与被 `sync_pipeline_pin` 钉住的运行永不删除（钉子是外键，删掉会破坏引用或抹掉映射版本归属）；读取（`recent`/`retained`）不触发清理。追溯响应新增 `retention`（预算 + 当前条数），页面显示并拒绝越界/不一致/缺字段的预算；契约新增必填 `retention` 与 19 项用例；领域新增 `ScanRunRetentionSmoke`(50) 与 `ScanRunRetentionConfigSmoke`(11)；真实 PG 新增 `PostgresScanRunRetentionIT`(4)。中间修掉三处真实缺陷（先插入后清理的顺序、可删除集合的分母语义、同毫秒运行的排序与游标不一致）与五处测试自身的错误假设，细节见[验证报告第54节](VALIDATION-REPORT.md)与 [ADR-047](adr/047-scan-run-retention.md)。**这不是修复路径**：不改写结果、不退休对象、不补做对账、不回收遗留 `RUNNING`（需独立租约/状态修复）。元数据留存与备份生命周期只在 [ADR-048](adr/048-metadata-retention-backup-lifecycle.md) 记录边界，未实现备份/恢复/擦除。

## 已关闭：M0（Zeus 迁移验收）

提交 `b5404a8`。不要再安排“从零替换 React”或继续扩前端框架面。

| ID | 状态 | 说明 |
|---|---|---|
| OW-R01 | 完成 | 兼容性清单；无 `node_modules` 拷贝 frozen 安装；GitHub Actions web job 通过 |
| OW-R02 | 完成 | Playwright 诊断页 7 passed；CI 含 `pnpm test:web` |
| OW-R03 | 消费端已规避 | `wc/auto` 保留 `customElements.define`；上游最小复现不阻塞 M1 |

残留：中文 IME / 完整键盘矩阵未测。chat / agent-console / data-grid 未接入。

## 本机已落地：OW-R04 切片（不是生产 IAM）

`Principal` / `TenantId` / `ResourceScope` / `Permission` / `authorize()` allow-deny 已有领域测试。`platform-api` 在 dev 模式从固定 Bearer 构造 Principal，OIDC 模式从已验证 issuer/sub 和操作员授权文件构造 Principal，拒绝 query/header 覆盖 tenant。OIDC BFF 与受限 Runtime 委托已通过本机协议链，真实 IdP 尚待验收。**不要把 Demo token 升级成生产认证，也不要继续做组织树/ABAC/SSO 后台。**

## 当前：Host 链已经能分页落库，下一刀仍不是 Copilot

| 顺序 | ID | 工作 |
|---|---|---|
| 1 | 真实 `host.get` | 对可达 Zabbix 跑完整分页；失败仍不得回退 fixture，也不得对账 |
| 已实现 | 指标查询与页面 | 查询 API、Schema、资产/指标与 15m/30m/1h 曲线、来源标记和状态展示。补齐 HTTP/浏览器回归与可重复的真实存储查询脚本；实际验收见验证报告第 21 节。未配置 VictoriaMetrics 时返回 503，不回退空数组 |
| 本机切片已实现 | OW-R11 | 不可变 PipelineVersion、固定版本 Host 同步、历史 Raw Preview / dry-run Replay 与表单页面，HTTP/PG/浏览器验证见第 22 节。持久只读记录、同键幂等/租约恢复和历史页面已补齐，验证见第 23 节；私有持久草稿、并发冲突保护和页面刷新载入已补齐，见第 24 节；写入型修复、后台重放调度仍未实现。无画布、无 Copilot |

Host 同步把来源 presence 和映射成功分开。完成态标记为 `offset-scan-attempt`。失败响应带回已扫描页数。`extensions/mappings/zabbix-cpu-user.yaml` 把 `system.cpu.util[,user]` 映射为 `host.cpu.usage.user`；两台主机共用一条目录记录。本机没有配置 Zabbix URL，厂商全量还没跑。组织树、ABAC 和 SSO 管理后台继续不做；OIDC 登录切片见第 36 节。

OW-R08 的受权资产服务端筛选/分页与详情已完成本机验收，含 PostgreSQL 范围先于分页、详情来源和 Token 切换清理，见验证报告第 25 节。第 27 节已迁移指标选择器，并将旧兼容 GET 列表限制为 100 条，超限返回 PAGED_READ_REQUIRED。当前 MVP 100% 目标和退出清单见 [MVP-CHECKLIST.md](MVP-CHECKLIST.md)，整体估算与口径见 [PROGRESS.md](PROGRESS.md)。

OW-R12 Integration Copilot **暂缓**。

2026-09-26 第52节：M3 此前唯一的本地缺失项——counter reset 策略与用例——已补齐（ADR-046 + ADR-010）。SUM/计数器查询新增显式派生视图：按秒变化率、下降区间从零重计并带 `counterReset = true`、非正区间与负值跳过不编造、原始点/单位/来源/窗口/状态不变；契约把 `derivation`（`counter-rate` + `reset-counts-from-zero`）与 `counterRates` 收紧为闭集，带派生视图的序列必须给 rate，无派生却带 rate 一律拒绝。Web 指标页默认展示变化率曲线并在图上标出 reset 区间，可一键切回原始累计值；非法响应（错策略、缺 rate、负 rate、rate 落在首点、非布尔 reset）不画图。证据：契约 642 passed（新增 14 项）、纯领域 1008 项/37 个 main（`CounterRateSmoke` 22 项）、Java 226 项（206 平台 + 20 Worker，零失败/错误/跳过）、真实 HTTP `MetricCounterRateHttpIT`、Web 218 passed（新增 8 项）与构建、开发链 20 PASS、OIDC/Worker 10 PASS/18 边界、Rust 40/44 + fmt、111 份产物/65 类 Schema。M3 约85%→90%，MVP 约91%。变化率是平台推导视图、不是厂商上报速率；真实采样/模型/身份与人工审阅仍待环境。

2026-09-26 第51节：只读的来源连接自检已补齐（ADR-045 + runbook）。POST `/api/v1/integrations/zabbix/connection-checks` 执行一次有界 probe 并写回执，GET 读取最近回执（1–50，默认 20，倒序）；fixture 回执保持 `labeled-fixture` 且不声明版本，JSON-RPC 模式记录来源自报 `apiinfo.version`（声明而非已验证支持结论），不可达必须不带版本、抛异常失败关闭、绝不回退 fixture；V026 每 tenant/source 保留最近 100 份，前端只读面板与链路证据齐备。真实来源到位后的第一步见 `docs/runbooks/source-connection-check.md`。M2 保持约93%，M3 保持85%，MVP 约90%。下一步继续来源失败/冲突追溯、只读诊断异常场景评估与运行记录治理；真实 Zabbix/模型/身份与人工诊断审阅仍待环境。

2026-09-26 第50节：模型请求边界已有可复核证据。审计确认实现本就正确（密钥只进 Authorization 头、提问与知识以 `untrustedUserQuestion`/`untrustedEvidenceContext`/`requiredOutputSchema` 随技能提示发出、SDK 内容诊断被抑制、失败无重试无回退）；本轮把请求形状测试改为同时捕获头与体并断言密钥不出现在请求体，并新增端到端测试用真实发布的技能提示 + 生产输入组装向本地协议桩发真实 Rig 请求，断言不可信框架、结构化证据与输出 Schema 齐全且不含密钥。Rust all-features 43→44。真实提供方调用/账单、真实来源/身份与人工诊断审阅仍待环境。

2026-09-26 第49节：对账已强制要求“已验证快照”（ADR-043）。`SyncScan.verified(label)` 只承认 `hostid-watermark-snapshot`/`itemid-watermark-snapshot`；用例在 `snapshotComplete` 为真时复核标签，不满足则以 `SOURCE_SCAN_UNVERIFIED` 失败、退休数为 0、已提交页与 Raw 保留，未读页的失败保持默认标签。审计确认 Problem/历史采集没有缺失对账，因此不做 Problem 水位边界。真实来源/模型/身份与人工诊断审阅仍待环境。

2026-09-26 第48节：Item 采集已走 itemid 水位快照（ADR-042）。首个分页请求前读取最高 itemid 与总行数，水位内升序分页，只有观测行数等于捕获计数且看到水位行才 `snapshotComplete=true` 并标注 `itemid-watermark-snapshot`；水位后新增不属于快照，删除/乱序/重复按 `SOURCE_SCAN_UNVERIFIED` 拒绝且不对账。水位算法抽成 Host/Item 共享的 `JsonRpcWatermarkBounds`，V025 扩展标签闭集。真实来源/模型/身份与人工诊断审阅仍待环境。

2026-09-26 第47节：扫描边界标签已持久化（ADR-041）。`integration.source_sync_run` 新增 `scan_consistency`（V024，默认 `offset-scan-attempt`），`succeed`/`fail` 写入、追溯接口与页面返回并渲染，旧行保留默认值；同时把前端失败摘要映射与 Java 枚举逐条对齐并补上 `SOURCE_SCAN_UNVERIFIED`（此前这类运行会被追溯页整体拒绝）。真实来源/模型/身份与人工诊断审阅仍待环境。

2026-09-26 第46节：Host 扫描已改为 hostid 水位快照（ADR-040）——首个分页请求前读取最高 hostid 与总行数，水位内升序分页，只有观测行数等于捕获计数且看到水位行才 `snapshotComplete=true` 并标注 `hostid-watermark-snapshot`；水位后新增不属于快照，删除/乱序/重复按 `SOURCE_SCAN_UNVERIFIED` 拒绝且不对账，游标携带边界、客户端不得构造，边界请求失败不伪装空快照。M2 90%→93%，MVP 约90%。真实来源/模型/身份与人工诊断审阅仍待环境。

2026-09-26 第45节：Item 扫描已取得与 Host 相同的来源 scope 租约（ADR-039）——取租约后才请求、每页续租、目录/绑定写入与缺失对账经 `SourceItemWritePort` 受围栏提交，PostgreSQL 同事务校验+复查+续租，失去/过期/被接管整笔回滚并映射为 `SOURCE_SCAN_BUSY/LOST/DEADLINE/LIMIT`，HTTP 以 503 明确失败；内存适配器显式非事务。同时修正 V023 索引未登记（迁移列表与 processResources）与 `SOURCE_SCAN_*` 的 host-only 摘要文案。真实来源/模型/身份与人工诊断审阅仍待环境。

2026-09-26 第44节：来源扫描运行已可只读追溯（ADR-038）。Host/Item 各两个 GET：最近运行分页（limit 1–50、服务端游标、hasMore/nextCursor）与按 syncRunId 单条读取；租户来自 Principal、来源来自配置、权限沿用源级 source.sync，未知/跨租户/跨来源/跨对象类型一律 404，未知查询参数与非法 limit/游标 400；失败码只还原平台自身写入的稳定前缀，未知文本不回显，并显示扫描启动时钉住的映射版本；V023 只加一条读取索引。入口不连接来源、不重试、不对账、不修复遗留 RUNNING。真实来源/模型/身份与人工诊断审阅仍待环境。

2026-09-26 第43节：固定CMDB来源绑定更正已补齐（ADR-037），需要新的来源观测、原snapshotId、双方实体版本和目标已登记UUID；生效字段先撤销，原历史不迁移，失败全事务回滚。页面预览/确认/刷新回执/资产更正历史已提供；没有自动纠错、实体合并或写入型运维修复。MVP保持约89%。真实来源/模型/身份与人工诊断审阅仍待环境。

2026-09-26 第42节：已登记 UUID 已接入显式 CMDB 快照导入，PG 原子持久化观测/待审字段/来源确认；完整缺失、连续确认、过期/登记撤销与主来源生命周期保护已实现（ADR-036）。人工字段入口和快照入口共同禁止绑定冲突；未连接厂商 API、未后台轮询或自动接受字段。M2约90%、MVP约89%、整体约57%。下一步继续来源冲突追溯与受审计的绑定更正，避免永久绑定无法纠正但也不允许隐式重绑；已有资产合并/alias及全平台存储治理仍独立保留。真实环境按用户选择暂待配置。

2026-09-26 第41节：Host 扫描并发竞态已修复，PG持久来源租约、过期接管、旧扫描写入/下线对账隔离和事务结束前时间复查已实现（ADR-035）。MVP仍约88%，不以新增测试数量提高估算。下一步继续来源 Resolver/presence；当前只保护 Host 路径，offset分页漂移、其他采集类型及全平台HA未因租约自动解决。

2026-09-26 第40节：AI正文/读取审计留存策略、保留Incident、预览/人工确认、PG原子清理和原回执查询已实现；保留原标识/范围/费用，无模型动作工具。M4约80%，MVP约88%。下一步推进持续来源Resolver/presence和冲突治理；全平台数据/元数据总量、备份生命周期和人工评估仍需完成，真实环境按用户选择暂待配置。

2026-09-26 第 39 节：模型费用预留/原费率/不可变用量、租户 PG 并发准入、未知费用跨日保留、Rig Responses 协议桩和页面独立查询通过。当时M4约70%，MVP约86%；真实提供方账单、精确输入预检、核销/聚合未提供。

2026-09-25 第 38 节：人工核对的资产 UUID 登记/撤销、租户+命名空间唯一目标、授权定位、导入 pin 及依赖撤销通过真实浏览器/Java/PG 链（ADR-032）。MVP 约 84%，100% 目标继续。下一步将登记基础接入持续来源采集、明确多源 presence/冲突处理；已有实体合并/alias 不由本切片隐式完成。费用/留存与真实环境退出条件仍保留。

2026-09-25 第 37 节：Worker 独立 client_credentials JWT、运维有界授权、专用采集入口、撤销/提供方故障不推进游标及同 client 密钥轮换续采通过本机完整链（ADR-031）。当时 MVP 约 83%；真实提供方/TLS/来源/模型仍待环境。

2026-09-25 第 36 节：OIDC BFF/PKCE/nonce、Cookie/CSRF、操作员映射及逐请求撤权、双标签退出、有界 Runtime 委托已通过本机协议与 Java/PG/VM/Rust/浏览器整链（ADR-030）。下一步补 Worker 服务身份、通用跨源 Resolver/presence 与费用/留存，真实环境仍按用户选择等待配置。

2026-09-25 第 35 节：资产/Incident 筛选与选中资源、指标实际时间窗口已可通过 URL 恢复，刷新/前进后退不自动请求或重放操作，读取仍受 Java 权限约束（ADR-029）。下一步生产身份适配与本地协议验证，再推进通用 Resolver；留存总量/清理、费用与人工评估继续保留在退出清单。真实来源/模型/登录验收待环境，继续本地实现。第 34 节规范化告警历史、当前归属和授权分页继续通过整链回归。

第 33 节固定 CMDB 导入记录补充现有 Host，经版本核对、逐字段确认/拒绝/撤销，PG 与主来源采集原子协调，历史与过期语义明确（ADR-027）。这不是已有 Zabbix 资产合并或持续二来源 presence；名字/IP 不能自动合并，Link 和指标/Incident ID 不搬移。

M3 基本链、当前知识 Tool/Rust 单次模型流程、AIInsight 页面，以及 Incident 人工合并/拆分/原子归属和回执历史已接通本机 PG/VM（ADR-021–024、验证报告第 27–30 节）。关联变化使旧证据/结果失效，后续告警按当前归属更新。模型为显式 mock，真实调用不计完成；未改变旧历史 asOf 校验。公共请求/开发会话清理通过第 31 节，字段审核通过第 33 节，规范化告警历史通过第 34 节，只读选择恢复通过第 35 节。用户已确认先完成本地实现，暂不具备真实环境配置；最新估算以本页第43节及 PROGRESS 为准，目标仍进行中。旧段落中暂缓 OIDC 的表述是早期切片顺序；第 36 节已补适配，真实提供方验收仍属于当前 MVP 退出条件。

2026-09-22 增量：History 读取已通过本地协议桩、HTTP、权限/资源范围与分页负例测试。接口明确未持久化，同秒达到探测上限即失败，不跳过采样点。详见 [ADR-016](adr/016-bounded-history-read.md) 和验证报告第 16 节。当前仍无厂商 Zabbix URL。

后续增量已提供单流 Worker 与真实存储适配，默认关闭、固定开发租户和 loopback。见 [ADR-017](adr/017-history-ingestion-checkpoint.md)。前述只读接口仍不自动持久化，只有显式启用 Worker 才进行采集。

## 已完成的 Bootstrap

锁文件、Gradle Wrapper、Rust 1.98.1、pnpm、Zeus 迁移与诊断页 E2E 见 `VALIDATION-REPORT.md` 第 5–10 节。身份与 Host 切片见第 11 节。分页同步与 PostgreSQL 见第 12 节。同步错误码与 `host.get` 游标见第 13 节。Host presence 与 Item 指标见第 14 节。目录/绑定拆分见第 15 节。

## 更后（M3 起）

外部告警、Incident；Java Tool Gateway 与 Rust 真实读取；可恢复 AIRun；配置式 Skill；受控试点。
