# 实现状态 · v4

更新日期2026-09-26。**“源码已提供”不等于“编译/集成已通过”。**验证范围以VALIDATION-REPORT为准。

进度粗估：整体约 58%（±5 个百分点），首个只读诊断 MVP 约 91%；按路线图阶段等权估算，不是生产就绪率。分阶段依据见 [PROGRESS.md](PROGRESS.md)。

## 当前源码

最新增量：真实验收执行包已补齐（第53节）。`scripts/acceptance/real-acceptance.mjs` 按顺序跑七步只读断言（连接自检 → Host 水位快照+落库标签回读 → Item 水位快照 → 受权资产 → 指定指标 `AVAILABLE` 且有样本 → Incident+关联告警 → 只读诊断保存并回读 AIInsight），任一步失败立即停止并写 JSON 报告（每步证据、退出条件映射、固定未验证项）。它只调用平台 API：不直连来源/模型/数据库，不写来源，不重试；来源是 fixture 时除非显式 `--rehearsal` 一律以退出码 2 拒绝，报告 `mode` 只写 `rehearsal`。本机演练 7/7 步通过，另验证“fixture 被拒绝”“坏凭据快速失败”两个负例。真实环境到位后按 `docs/runbooks/real-acceptance.md` 执行。M2 约93%、M3 约90%、M4 约80%、MVP 约91%。

第52节 SUM/计数器变化率与 reset 策略继续保留：只有 SUM 查询得到派生视图（按秒变化率，下降视为重置、该区间从零起算并带 `counterReset = true`，非正区间与负值跳过，原始点/单位/来源/窗口/状态不变）；契约把 `derivation` 与 `counterRates` 收紧为闭集，Web 指标页默认画变化率曲线并标出 reset 区间、可切回原始累计值。

第51节来源连接自检（只读）继续保留：POST `/api/v1/integrations/zabbix/connection-checks` 执行一次有界 probe 并写入回执，GET 读取该来源最近回执（1–50，默认 20，倒序）；fixture 回执保持 `labeled-fixture` 且不声明版本，JSON-RPC 模式记录来源自报的 `apiinfo.version`（声明而非已验证支持结论），不可达必须不带版本，连接器抛异常仍是失败关闭，绝不回退 fixture；V026 每 tenant/source 保留最近 100 份。M2 保持约93%。

第50节模型请求边界证据继续保留：密钥只进 `Authorization` 头且不出现在请求体，提问与知识以三个具名不可信字段随真实发布的技能提示发出，失败无重试无回退（Rust all-features 44）。

第49节对账强制“已验证快照”继续保留：`SyncScan.verified(label)` 只承认两种水位标签，Host/Item 用例在 `snapshotComplete` 为真时仍复核该标签，不满足就以 `SOURCE_SCAN_UNVERIFIED` 失败、退休数为 0；Problem/历史采集经审计确认没有缺失对账。

第48节 Item 水位边界继续保留：首个分页请求前读取最高 `itemid` 与总行数，水位内升序分页，只有观测行数等于捕获计数且看到水位行才 `snapshotComplete=true` 并标注 `itemid-watermark-snapshot`；水位算法由 Host/Item 共享的 `JsonRpcWatermarkBounds` 实现，V025 扩展标签闭集。

第47节扫描边界标签持久化继续保留：`integration.source_sync_run.scan_consistency`（V024，默认 `offset-scan-attempt`）由 `succeed`/`fail` 写入，追溯接口与页面渲染为“边界 已验证 hostid/itemid 水位快照 / offset 尝试（无快照证明）”；前端失败摘要映射与 Java 枚举逐条对齐（含 `SOURCE_SCAN_UNVERIFIED`）。

第46节 Host 水位快照继续保留：首个分页请求前捕获最高 `hostid` 与总行数作为边界，水位内升序分页；只有观测行数等于捕获计数且看到水位行时才 `snapshotComplete=true` 并标注 `hostid-watermark-snapshot`，否则以 `SOURCE_SCAN_UNVERIFIED` 失败且不对账。水位之后新增的 host 不属于本次快照；边界请求失败不伪装空快照；游标携带边界且客户端不得构造。

第45节 Item 扫描所有权继续保留：与 Host 共用来源 scope 租约（`externalType=item`）——取到租约才发请求、每页续租、目录/绑定写入与缺失对账都经 `SourceItemWritePort` 携带租约提交，结束时只释放自己的 token；PostgreSQL 在同一事务内校验、复查并续租，失去或过期即整笔回滚并映射为 `SOURCE_SCAN_*` 稳定码。Item 采集仍是 `offset-scan-attempt`。

第44节来源扫描运行追溯继续保留：Host/Item 各提供最近运行分页（limit 1–50、服务端签发游标）与按 syncRunId 单条读取，跨租户/跨来源/跨对象类型 404，失败码只还原平台自身前缀。本轮同时修正 V023 读取索引未登记（迁移列表与 `processResources`，此前从未真正创建）与 `SOURCE_SCAN_*` 摘要的 host-only 文案。

第43节来源绑定更正继续保留：新观测、原snapshotId、双方实体版本和目标UUID pin，V022在同一事务保留旧绑定/操作者/原因并导入新待审字段。历史Observation、快照、指标和Incident引用不迁移；原生效字段需先撤销，未知结果查询原回执。

第42节V021显式CMDB快照按登记定位、原子观测/待审字段/来源确认、完整缺失及过期/撤销前置筛选继续保留。未连接厂商CMDB API、未提供后台轮询或已有Entity合并。

第41节 Host 扫描 V020 持久来源租约、单调 fence、30秒续租/5分钟绝对截止、跨连接接管与旧扫描隔离继续保留。等待锁时过期会回滚；原 offset-scan-attempt 限制、Item/Problem 所有权和全平台 HA 仍未解决。

第40节可信租户策略、保留 Incident、120秒有界预览、人工确认清理、PG原子正文置空/审计删除/原操作者回执及页面恢复继续保留。原运行标识、权限范围、引用和费用账本不清除；全平台数据/元数据/备份生命周期未完成。第39节模型费用仍为配置估算。

前一增量：人工核对的资产 UUID 登记/撤销、租户+命名空间唯一目标、受权精确定位，以及固定 CMDB 导入身份 pin 已实现；真实浏览器/Java/PostgreSQL 验证原始幂等回执、依赖字段先撤销、登记撤销后查无映射。并发竞争、旧待审导入失效、命名空间变更和权限负例通过，见第 38 节、ADR-032。Worker 服务身份、OIDC 浏览器/有界 Runtime 委托链继续通过。IdP 是显式协议 fixture、来源 fixture、模型 mock；通用来源自动采集/权威规则、已有实体合并和真实提供方/TLS/代理仍缺。

| 部分 | 已提供 | 未提供 |
|---|---|---|
| 真实验收执行包 | checked-in 只读执行包按序断言 M2–M4 关键退出条件（S1–S7）、失败即停并写 JSON 报告（每步证据、退出条件映射、固定未验证项）；fixture 来源无 `--rehearsal` 一律拒绝（退出码 2），`mode` 区分 rehearsal/real；只走平台 API、不写来源、不重试；本机演练 7/7 + 两个负例 | 生产 OIDC 部署的浏览器验收（见 OIDC runbook）、人工抽样审阅、提供方账单对账、真实 IdP/TLS 验收；`mode: real` 不替人判断来源是否是目标环境 |
| 来源连接自检 | 只读 POST 执行一次有界 probe 并写回执、GET 读取最近回执（1–50）；源级 `source.sync`、租户/来源强隔离；fixture 保持标注且不声明版本、JSON-RPC 记录来源自报版本、不可达不带版本、抛异常失败关闭；V026 回执表，每 tenant/source 保留 100 份；前端只读面板与链路证据 | 真实 Zabbix/TLS/代理与版本兼容矩阵验收、后台调度/跨来源汇总、全平台容量治理；回执不等于真实来源验收 |
| 对账前置条件 | 只有 `hostid-watermark-snapshot`/`itemid-watermark-snapshot` 才允许 `retireMissing`；声明完成但无边界标签时以 `SOURCE_SCAN_UNVERIFIED` 失败、退休数为 0、已提交页与 Raw 保留；未读页的失败保持默认标签；PG 与内存路径都强制 | 上游漂移的修复（只能拒绝，不能补全）、offset 分页语义变更、运行记录清理/配额、真实来源验收 |
| Host 水位快照 | 首请求前捕获最高 hostid 与总行数；水位内升序分页；只有观测行数等于捕获计数且看到水位行才完成并标注 `hostid-watermark-snapshot`；水位后新增不属于快照；删除/乱序/重复按 `SOURCE_SCAN_UNVERIFIED` 拒绝且不对账；游标携带边界；空来源是已验证空快照，边界请求失败不伪装空快照 | 真实 Zabbix 的 hostid 分配/`countOutput`/排序验收；Item/Problem 的水位边界；一致性标签持久化到运行记录；未实现边界的自定义连接器仍可完成对账 |
| Item 扫描所有权与水位 | 与 Host 共用来源 scope 租约（externalType=item）、取租约后才请求、每页续租、目录/绑定写入与缺失对账受围栏（PG 同事务校验+复查+续租，失败整笔回滚）、结束只释放自身 token；首请求前捕获最高 itemid 与总行数，只有计数与水位双验证才完成并标注 `itemid-watermark-snapshot`，漂移按 `SOURCE_SCAN_UNVERIFIED` 拒绝 | 跨来源全局所有权、后台调度、遗留 RUNNING 自动修复、分布式 HA、“只允许已验证快照触发对账”的强规则、真实来源验收 |
| 来源扫描运行追溯 | Host/Item 最近运行的只读分页（limit 1–50、服务端游标、hasMore/nextCursor）与按 syncRunId 单条读取；源级 source.sync、租户/来源/对象类型强隔离、未知标识 404；失败码只从平台自身前缀还原且不回显原文、钉住映射版本、持久化边界标签（V024）并在页面渲染、no-store；V023 读取索引 | 运行表自动清理/总量配额、遗留 RUNNING 的自动修复、跨来源或跨租户的全局检索、把标签用于自动决策、真实来源验收 |
| 来源绑定更正 | 新来源观测+原snapshotId+双方版本+目标登记pin，PG来源/实体/绑定锁、原字段先撤销、失败整笔回滚、原历史不迁移、原actor幂等回执/整源受权历史、Web预览/确认/未知结果查询；最多1000份回执并共享快照预算 | 已有Entity合并/alias、主Zabbix绑定或命名空间迁移、历史诊断重算、自动纠错/自动字段批准、全平台留存/HA |
| 已登记来源快照 | 固定 import 引擎/摘要、全租户管理权限、UUID 精确解析；最多100条/100个保留绑定/1000回执，PG同事务观测/待审字段/presence、严格时间顺序、原actor幂等回执；完整缺失、部分/失败不对账，过期/登记撤销在分页前撤销保护；Web导入/回读/状态 | 厂商CMDB API、后台轮询、自动字段批准、已有Entity合并/alias、主来源TTL及通用多源权威策略、全平台容量/生产迁移HA |
| Host 扫描所有权 | PG 来源范围租约/fencing、数据库时间、短事务续租、5分钟截止、旧写入/对账/释放隔离、过期事务回滚、稳定错误码与不可覆盖的内部 token；V020每范围一行 | 通用 Item/Problem 扫描所有权、来源一致快照、后台调度、旧RUNNING状态自动修复、生产滚动迁移/故障切换/HA |
| AI 内容留存 | 默认关闭的可信租户策略；独立管理权限/全租户scope、精确保留Incident、三类各最多100条、120秒预览与策略摘要、PG同事务清理/回执、同键原结果、旧结果授权后410、Web确认与未知结果查询；V019保留原标识/引用/费用 | 生产调度、tenant总存储限额、其他业务数据及会话/标记/费用元数据TTL、WAL/备份/物理擦除；文件与PG事务不具有共同锁 |
| 模型费用 | 单次/租户每日配置估算准入、PG 租户锁、一次 run 许可、不可变用量/费率、未知预留跨日保留、最多10000条、独立受权页面回读；Rig协议桩无重试/重定向/超量/内容日志 | 真实提供方账单、精确输入预检、按 Skill 聚合、核销/对账/时间清理；估算不保证第三方账单上限 |
| 资产强标识登记/定位 | 运维命名空间、人工核对 UUID、租户唯一生效目标、可信 actor/原因/原始回执、实体版本/共享锁、PG 并发唯一与回滚；有权精确定位、可选导入 pin、依赖字段先撤销、Web 原请求重试与会话清理；快照复核登记且撤销即时停止存在保护 | 自动来源标识核验、通用采集 Resolver、已有 Entity 合并/alias/标识迁移、tenant 总量/时间清理；不按名字/IP 自动合并 |
| URL 只读选择 | 三类 canonical selection、严格参数/长度/编码/窗口、资源和游标恢复、前进后退丢弃结果、固定实际窗口、重新读取与身份清理、初始 Token 逐字输入保持链接 | 历史子面板筛选、跨页一致快照、写操作输入/执行恢复、浏览器历史擦除或凭据持久化 |
| 告警观测历史 | 合并前规范化输入、稳定 ID/原样重投、异内容全批回滚、首次映射/接收纳秒、当前归属和版本校验、历史实体权限先于分页、Web 筛选/翻页/冲突刷新与身份清理 | 厂商原始 JSON、留存前补造、Incident 任意 asOf 投影、留存总量/清理和生产迁移/容量验收 |
| 补充来源字段审核 | 固定版本 CMDB 导入；目标版本与四字段预览、逐字段选择、可信 actor/原因/幂等回执、唯一生效绑定、V015 主来源快照/原子确认撤销、来源/过期/历史页面；UUID pin复核；与V021来源绑定双向冲突检查 | 通用已有资产合并、厂商来源自动采集、真实 CMDB API、留存清理/全平台总量配额 |
| 资产 Observation | 不可变 JSON/租户 ID、原子重复/冲突和 Link 检查、迟到保留、V014 纳秒/旧精度、授权窗口/source/ID 分页与 Web 文本展示 | 通用跨源 Resolver、完整来源事件日志、任意 asOf 投影、留存清理与存储总量限制 |
| Worker 服务身份 | 默认关闭的独立 OAuth client_credentials/RS256 at+jwt；固定 issuer/audience/scope、运维 Principal/资源/时间/点数/速率授权、4 并发；有界网络、单调缓存、撤权/故障不推进 checkpoint、轮换续采本机通过 | 真实 IdP/TLS 联调；远端 VM/PG、多流、分布式全局配额/HA 未提供 |
| OIDC BFF / 委托 | 固定提供方/回调、授权码+PKCE/nonce、操作员 issuer/sub→Principal；单进程有限期 Cookie/CSRF、授权更改/退出撤销、跨标签清理；最多 4 个/65 秒/8 次原请求及额外各一次费用预留/报告的 Runtime 委托；协议 fixture 完整链通过 | 真实 IdP/HTTPS/反向代理验收、跨主机 Runtime 认证、共享会话/HA、IdP 退出通知 |
| 公共 HTTP / 开发会话 | 同源固定路径、6 请求并发、流式字节/时限限制、无隐式重试、错误归一化与请求 UUID；内存凭据共享、30 分钟绝对保留、pagehide/401/403/换身份清理；Java API no-store/nosniff | 真实身份提供方/TLS；跨服务追踪不是当前请求 UUID 的能力 |
| Incident 归属 | 人工合并/拆分的双方权限和版本检查、当前归属索引、原子幂等回执、来源归档、授权历史分页与页面；规范化告警历史跟随当前归属，关联变化使旧证据/结果失效 | 完整厂商事件日志、时间线分页、自动相关性与撤销工作流；真实来源验收 |
| AIInsight | Java 可信提交/固定 Runtime origin/Skill 与模型证明；V012 PG 原子幂等保存和受权 GET；Web 当前知识发起/结构化结果/刷新回读/证据/过期与身份清理 | 真实模型/生产身份、账单对账与留存；持久 AIRun 和 HA 属 M5 |
| Java平台 | Principal/授权、Zabbix Host 分页同步、Item → MetricDefinition + MetricBinding、PostgreSQL 库存、指标目录/绑定 API、有界 History 只读接口、指标查询 HTTP API（SUM 计数器派生变化率与 reset 标记）；Host 不可变 PipelineVersion、固定版本同步、留存 Raw Preview / dry-run Replay API、持久重放记录/幂等/显式恢复、私有草稿与并发保存保护；外部问题/恢复原子入库、Incident/时间线/人工状态和归属变更、授权查询 | 真实 OIDC/HTTPS 与厂商 Zabbix 联调、写入型历史修复、后台重放调度 |
| Java采集 | 默认关闭的单流 History Worker；平台授权读取、毫秒冲突/数值精度检查、VictoriaMetrics 批写与回读确认、PostgreSQL checkpoint、短事务租约/fencing、重叠去重/恢复 | 服务身份真实提供方/TLS 验收、远端存储启用、多数据流调度、分布式/HA 验收、重叠范围外的自动回补 |
| Web | 诊断表单；资产授权分页详情、补充来源预览/确认拒绝撤销；指标按分页 Host/指标/15m–1h 或固定窗口查询，SUM/计数器变化率视图与 reset 标记（可切回原始累计值）；Incident 导入/状态/详情/来源缺口/时间线/人工流转和重试、规范化观测历史/冲突刷新；资产/Incident/指标核心只读筛选与窗口 URL 恢复；Host 流水线 Preview/发布/只读 Replay、记录/分页/显式恢复、草稿载入/冲突提示；统一内存开发凭据与请求层 | 真实提供方/部署联调、历史子面板筛选恢复、发布审批、Skill/Agent Console |
| 存储/运维 | Host/指标元数据迁移 V002–V004；V007 发布版本及运行绑定、V008 重放记录、V009 私有草稿、V010 来源发生索引/Incident 有界快照/状态幂等记录；V013 关联、V014 纳秒观测、V015 来源审核/主快照/回执、V016 规范化告警输入；worker 自有 `ingestion.history_checkpoint`（V002 租约/fencing，一序列一行）；PostgreSQL 与可选 VictoriaMetrics Compose profile | 行级安全、对象存储、生产 Helm/HA/备份验证 |
| Rust Runtime | Axum、本机模式、独立历史 demo/current 流程、四次读取/复核共享预算、单次模型/超时、结构化 Context、Skill 2.0.0/digest、Schema 输出和保存回执校验、Java HTTP AIInsight 保存/回读 | 真实模型调用；持久 RunStore/租约/恢复/取消 API/生产鉴权 |
| Rig | optional适配器、显式出网、一次 raw Responses、SDK用量、256 KiB/18秒、无代理/重试/重定向/内容日志；付费只允许经平台预算流程；密钥只在 Authorization 头、提问/知识以具名不可信字段随技能提示发出（本地协议桩上有测试证据） | 真实付费调用/账单验收、真实提供方侧行为、多provider/原生strict output |
| MCP | optional官方SDK本机Probe源码 | Agent内动态调用、OAuth、生产server准入 |
| Tool | 3个原 v1 prototype 契约；3个已实现 v2 Java Tool、当前知识会话、PG 证据/元数据审计、原子预算、Rust HTTP schema/范围/时间复核；AIInsight/网页经固定受控 API 访问；独立管理入口清理过期正文/审计 | 真实身份/来源验收、全量元数据留存和租户存储总额限制 |
| Skill | 可加载JSON+Prompt+Schema，内容digest，预算与模板限制 | UI创建、测试集发布、签名、热更新、多模板DAG |

## 已实际检查

2026-09-26 真实验收执行包增量：沿用第52节全套（642项契约、238个结构化文件、6个只读Tool、111份产物/65类Schema、1008项纯领域/37个main、226项Java、20个PASS/112边界完整链、OIDC 10个PASS/18边界、Rust 40/44及fmt、218项Web与TypeScript/生产构建）；本轮无产品代码改动，只新增 checked-in 执行包与文档。`node .tmp/acceptance-local.cjs` 演练：`--rehearsal` **7/7 步通过**（报告 mode=rehearsal 并打印 NOT real acceptance），同一 fixture 来源去掉 `--rehearsal` **退出码 2**，错误 Token **退出码 1 且 steps=0**。演练过程修掉执行包三处真实缺陷：资产样本覆盖、样本可见性等待、诊断时间窗必须是 RFC3339。MVP保持约91%，100%目标继续。

2026-09-26 计数器变化率与 reset 增量：642项契约（新增14项）、1008项纯领域（37个main，`CounterRateSmoke` 22项）、226项Java（206平台+20Worker，零失败/错误/跳过）、Rust默认40/all-features44及fmt、218项Web与TypeScript/生产构建、开发完整链20个PASS、OIDC与Worker10个PASS/18边界通过；111份实际产物/65类Schema通过。真实HTTP `MetricCounterRateHttpIT` 返回 `derivation.kind = counter-rate` 与 `resetPolicy`，四个原始点保留、三个区间派生、重置区间带 `counterReset = true`；Web 新 `e2e/metric-counter-rates.spec.ts` 8项覆盖默认变化率视图+reset 竖线/清单、切回原始累计值、6种非法响应拒绝、GAUGE 无变化率入口。变化率是平台推导视图、不是厂商上报速率，也没有单位换算/降采样；真实采样/模型/身份与人工审阅仍未验收。M3约90%，MVP约91%，100%目标继续。

2026-09-26 来源连接自检增量：628项契约、986项纯领域（36个main）、224项Java（204平台+20Worker，零失败/错误/跳过）、Rust默认40/all-features44及fmt、210项Web与TypeScript/生产构建通过。最终开发完整链20个PASS/112响应边界，OIDC与Worker10个PASS/18边界通过；109份实际产物/65类Schema通过。PG覆盖回执往返/倒序/隔离/留存上限100；HTTP覆盖fixture回执（labeled-fixture、无版本声明、no-store/nosniff）、列表读回、limit与未知参数400、未认证401、仅entity.read 403；前端面板拒绝未知字段/未知dataMode/不可达带版本等非法响应。中间修正：`statusCode`/`reportedVersion` 的 `$` 正则允许结尾换行（`"unreachable\n"` 曾通过校验），已改为 `(?![\\s\\S])` 并重跑全绿；POST 收紧为不接受查询参数。MVP保持约90%，100%目标继续。

2026-09-26 模型请求边界证据增量：602项契约、963项纯领域（35个main）、219项Java（199平台+20Worker，零失败/错误/跳过）、**Rust默认40/all-features44**及fmt、192项Web与TypeScript/生产构建通过。最终开发完整链19个PASS/110响应边界，OIDC与Worker10个PASS/18边界通过；105份实际产物/62类Schema通过。新增证据：请求头/体隔离（密钥只在Authorization、不在请求体）与不可信数据框架（真实技能提示+生产输入组装，请求体含三个具名不可信字段、不可信声明句、结构化证据id与输出Schema，且不含密钥）；无回退与“provider报错不落库”沿用既有测试并回归通过。本轮不改Java/契约/前端行为。MVP保持约90%，100%目标继续。

2026-09-26 已验证快照才允许对账增量：602项契约、963项纯领域（35个main）、219项Java（199平台+20Worker，零失败/错误/跳过）、Rust默认40/all-features43及fmt、192项Web与TypeScript/生产构建通过。最终开发完整链19个PASS/110响应边界，OIDC与Worker10个PASS/18边界通过；105份实际产物/62类Schema通过。PG2项（Host与Item各一）覆盖“声明完成但无边界”的连接器返回`SOURCE_SCAN_UNVERIFIED`、退休数为0、缺失对象保持ACTIVE、运行FAILED且标签保持默认；领域新增有界空来源可退休与未验证walk不退休两类断言，并把`ZabbixHostMappingSmoke`的硬编码计数改成真实计数（此前误报46、实际48）。Problem/历史采集审计确认无缺失对账。中间修正与限制见第49节。M2保持约93%，MVP约90%，100%目标继续。

2026-09-26 Item 水位快照增量：602项契约、945项纯领域（35个main）、217项Java（197平台+20Worker，零失败/错误/跳过）、Rust默认40/all-features43及fmt、192项Web与TypeScript/生产构建通过。最终开发完整链19个PASS/110响应边界，OIDC与Worker10个PASS/18边界通过；105份实际产物/62类Schema通过。PG2项覆盖真实PG上fixture item walk只退休缺失绑定且落库标签为`itemid-watermark-snapshot`、位移walk返回`SOURCE_SCAN_UNVERIFIED`且不退休（被跳过绑定从未落库）；HTTP验证成功同步标签与“未读页不声明方法”；前端渲染三种边界标签。中间修正与限制见第48节。M2保持约93%，MVP约90%，100%目标继续。

2026-09-26 扫描边界标签持久化增量：599项契约、921项纯领域（34个main）、215项Java（195平台+20Worker，零失败/错误/跳过）、Rust默认40/all-features43及fmt、192项Web与TypeScript/生产构建通过。最终开发完整链19个PASS/110响应边界，OIDC与Worker10个PASS/18边界通过；105份实际产物/62类Schema通过。PG2项覆盖标签往返与已验证/未验证walk的落库标签；HTTP追溯读取返回落库标签；前端渲染两种边界文案并拒绝未知/缺失标签，同时把失败摘要映射与Java枚举对齐（此前BUSY/LOST/DEADLINE与UNVERIFIED会被页面整体拒绝）。中间修正与限制见第47节。M2保持约93%，MVP约90%，100%目标继续。

2026-09-26 Host 水位快照增量：594项契约、919项纯领域（34个main）、215项Java（195平台+20Worker，零失败/错误/跳过）、Rust默认40/all-features43及fmt、190项Web与TypeScript/生产构建通过。最终开发完整链19个PASS/110响应边界，OIDC与Worker10个PASS/18边界通过；105份实际产物/62类Schema通过。PG2项覆盖真实PG上已验证水位walk退休缺失资产、位移walk返回`SOURCE_SCAN_UNVERIFIED`且不退休（运行FAILED、被跳过行从未落库）；HTTP协议桩改为水位/计数/分页三类请求并断言计数只读一次；Host同步标签为`hostid-watermark-snapshot`、Item仍为`offset-scan-attempt`。中间修正与限制见第46节。M2约93%，MVP约90%，100%目标继续。

2026-09-26 Item 扫描所有权增量：590项契约、885项纯领域（33个main）、213项Java（193平台+20Worker，零失败/错误/跳过）、Rust默认40/all-features43及fmt、190项Web与TypeScript/生产构建通过。最终开发完整链19个PASS/110响应边界，OIDC与Worker10个PASS/18边界通过；105份实际产物/62类Schema通过。PG4项覆盖外部持有者阻塞（0页0采纳、目录未变、运行FAILED为固定文案、持有者租约保留）、过期接管恢复与被取代token不可对账、过期token拒绝写入与对账、成功扫描交还scope；HTTP新增外部持有租约时503 `SOURCE_SCAN_BUSY` 实际响应。同时修正 V023 索引未登记导致从未创建。MVP保持约89%，100%目标继续。

2026-09-26 来源扫描运行追溯增量：590项契约、856项纯领域（32个main）、208项Java（188平台+20Worker，零失败/错误/跳过）、Rust默认40/all-features43及fmt、190项Web与TypeScript/生产构建通过。最终开发完整链19个PASS/110响应边界，OIDC与Worker10个PASS/18边界通过；105份实际产物/62类Schema通过。PG2项覆盖倒序/游标分页与租户/来源隔离、成功扫描带钉住版本、失败扫描读回且不对账不删除；HTTP3项覆盖401/403/400/404矩阵、no-store/nosniff、host与item互不可见及未知失败文本不回显；新页面支持Host/Item、10/20/50、服务端游标翻页与按标识查询。中间修正与限制见第44节。MVP保持约89%，100%目标继续。

2026-09-26 来源绑定更正增量：542项契约、819项纯领域（31个main）、203项Java（183平台+20Worker，零失败/错误/跳过）、Rust默认40/all-features43及fmt、179项Web与TypeScript/生产构建通过。最终开发完整链18个PASS/106响应边界，OIDC与Worker10个PASS/18边界通过；100份实际产物/59类Schema通过。PG6项覆盖事务内双方版本/绑定锁、原生效字段未撤销拒绝、失败整笔回滚、原快照归属不变、同actor幂等回执与未知/越权查询拒绝；网页验证原/目标预览、明确确认、回执查询、有界历史与会话清理。环境与检查工具修正、中间修正和限制见第43节。MVP保持约89%，100%目标继续。

2026-09-26 来源快照增量：519项契约、804项纯领域（30个main）、195项Java（175平台+20Worker，零失败/错误/跳过）、Rust默认40/all-features43及fmt、170项Web与TypeScript/生产构建通过。最终开发完整链17个PASS/96响应边界，OIDC与Worker10个PASS/18边界通过；93份实际产物/56类Schema通过。PG6项覆盖主来源缺失时保护、完整/部分/失败/连续缺失、到期与登记撤销前置筛选、双向绑定冲突和原回执并发幂等；网页验证导入、未自动接受字段、刷新回执与来源状态。中间修正与限制见第42节。MVP约89%，100%目标继续。

2026-09-26 Host扫描所有权增量：491项契约、788项纯领域（29个main）、185项Java（165平台+20Worker，零失败/错误/跳过）、Rust默认40/all-features43及fmt、160项Web与TypeScript/生产构建通过。开发16个PASS/90边界、OIDC与Worker10个PASS/18边界回归通过；新增HTTP占用失败实际产物，合计84份/52类Schema。新PG5项覆盖竞争、重建适配器、过期接管、范围隔离和等待锁过期的写入/对账事务回滚，见第41节。MVP保持约88%。

2026-09-26 AI内容留存增量：475项契约、755项纯领域（28个main）、179项Java（159平台+20Worker，零失败/错误/跳过）、Rust默认40/all-features43及fmt、160项Web与TypeScript/生产构建通过。开发16个PASS/90边界、OIDC与Worker10个PASS/18边界，以及83份实际产物/51类Schema通过。PG验证过期合成正文清除/原权限/ID/费用保留与回滚；浏览器链验证新数据保留、显式空批回执与刷新，具体边界和中间修正见第40节。MVP约88%，100%目标继续。

2026-09-26 模型费用增量：451项契约、731项纯领域（27个main）、169项Java（149平台+20Worker，零失败/错误/跳过）、Rust默认40/all-features43及fmt、151项Web与TypeScript/生产构建通过；开发15个PASS/87边界、OIDC与Worker10个PASS/18边界、77份实际产物/49类Schema通过，详见第39节。估算与mock标记保留，真实提供方未配置；MVP约86%，100%目标继续。

2026-09-25 Worker 服务身份增量：384 项契约、649 项纯领域（25 个 main）、155 项 Java（135 平台 + 20 Worker，零失败/错误/跳过）、Rust 默认/all-features 各 40 项、fmt、Web 构建与全量 128 项通过。完整链与实际产物最终结果见第 37 节；M1 约 90%、MVP 约 83%，100% 目标继续。

2026-09-25 OIDC BFF 增量：350 项契约、619 项纯领域（24 个 main）、142 项 Java（127 平台 + 15 Worker，零失败/错误/跳过）、Rust 默认/all-features 各 40 项及 fmt、Web 全量 128 项与构建通过。OIDC 协议 5 个 PASS/17 个浏览器响应边界、原开发模式 13 个 PASS/56 个边界通过；实际产物及最终复测见第 36 节。M1 约 85%、MVP 约 82%，100% 目标继续。

2026-09-25 URL 只读选择恢复增量：319 项契约、607 项纯领域（23 个 main）、126 项 Java（111 平台 + 15 Worker，零失败/错误/跳过）、Rust 默认/all-features 各 40 项及 fmt、TypeScript/生产构建和全量 124 项 Web 通过。补齐逐字输入初始 Token 后再次构建、48 项相关 Web 回归通过。13 条本机链、56 个浏览器 API 响应边界、56 份实际产物/35 类 Schema 通过，恢复后指标截图已核查；具体范围及修正见第 35 节。MVP 约 78%，目标继续。

2026-09-25 告警规范化历史增量：281 项契约、607 项纯领域（23 个 main）、126 项 Java（111 平台 + 15 Worker，真实本机 PG/VM，零失败/错误/跳过）、Rust 默认/all-features 各 40 项及 fmt、TypeScript/生产构建和全量 108 项 Web 测试通过。12 条本机链与 51 个浏览器响应边界通过，53 份实际产物/32 类 Schema 通过；截图核查后改进筛选框，再次运行构建、新 9 项 Web 与完整链。限制及修正见第 34 节。MVP 约 77%，目标继续。

2026-09-25 补充来源字段审核增量：259 项契约、577 项纯领域（22 个 main）、118 项 Java（103 平台 + 15 Worker，真实本机 PG/VM，零失败/错误/跳过）、Rust 默认/all-features 各 40 项与 fmt、TypeScript/生产构建和全量 99 项 Web 测试通过。11 条本机链通过，含网页暂存→PG 确认→主来源再次同步→指标仍可读→撤销；50 个浏览器响应边界通过。新字段展示修正后追加页面回归；具体范围、产物契约与限制见第 33 节。MVP 目标继续进行。

2026-09-25 资产观测历史增量：235 项契约、529 项纯领域（21 个 main）、110 项 Java（95 平台 + 15 Worker，真实本机 PG/VM，零失败/错误/跳过）、Rust 默认/all-features 各 40 项及 fmt、TypeScript/生产构建和 92 项 Web 测试通过。36 份实际 HTTP 响应/24 类 Schema 通过；完整本机链 10 个 PASS，含受权资产观测读取，来源 fixture、模型 mock。PG 同键并发、回滚、重开适配器、纳秒 cutoff 和旧精度标记有实测。截图已查看；限制和修正记录见第 32 节，MVP 目标仍进行中。

2026-09-25 公共请求/开发会话增量：217 项契约、501 项纯领域（20 个 main）、103 项 Java（88 平台 + 15 Worker，真实本机 PG/VM，零失败/错误/跳过）、Rust 默认/all-features 各 40 项及 fmt、TypeScript/生产构建通过。Playwright 84 项通过，其中 73 项浏览器 fixture 回归、11 项直接测试请求层；完整本机脚本 9 个 PASS，含 36 个浏览器 API 响应 UUID/no-store/nosniff 和清除会话验证。32 份实际 HTTP 响应/22 类 Schema 通过，截图已查看。命令、Chromium 正文调试读取问题与验收边界见第 31 节；目标仍进行中。

2026-09-25 人工关联增量：216 项契约、501 项纯领域（20 个 main）、101 项 Java（86 平台 + 15 Worker，真实本机 PG/VM，零失败/错误/跳过）、Rust 默认/all-features 各 40 项及 fmt、TypeScript/生产构建、68 项 Playwright 通过。32 份实际 HTTP 响应/22 类 Schema 通过；完整本机脚本 8 个 PASS，含网页预览→PG 合并/拆分→后续导入归属→回执历史，以及旧证据/AIInsight 409。截图已查看。详见第 30 节，MVP 目标仍进行中。

2026-09-25 当前知识诊断增量：198 项契约、467 项纯领域、95 项 Java（80 平台 + 15 Worker，真实本机 PG/VM，零失败/错误/跳过）、Rust 默认/all-features 各 40 项、TypeScript/生产构建及 62 项 Playwright 通过。实际浏览器 → Java → Rust mock → Evidence 复核 → PG AIInsight → 同键回读/刷新/证据链通过。新页面已验证身份清理、超时待确认和引用/范围负例；模型/fixture 标记明确。命令、修复记录和未验收边界见第 29 节。

2026-09-25 追加 Java 受控读取及 Rust HTTP：契约 169、纯领域 449、Java 87（真实本机 PG/VM，零失败/错误/跳过）通过；独立 Rust probe 已通过 Java 可信读取会话→PG 证据/VM 统计→Evidence 复核，本机来源明确 fixture。完整命令、Rust/Web 回归和失败重试记录见第 28 节。下一步完成当前知识模型流程和 AIInsight 幂等持久化/证据页面；原 demo 的历史 asOf 约束保持原样。

交付包阶段：Python契约/样例测试、纯Java领域编译与smoke、结构/架构静态检查。

2026-09-22：identity allow/deny 与 Zabbix Host 链见 `VALIDATION-REPORT.md` 第 11 节。同日追加分页 SyncRun 与 PostgreSQL：`PostgresHostSyncIT` 在本机库 `opsweave_host_sync` 通过；资产页 Playwright 库存用例通过。同步失败码与 `host.get` offset 游标见第 13 节。Host presence 与 Item 指标见第 14 节。目录与绑定拆分见第 15 节；History 只读接口见第 16 节，响应仍为 `not-persisted`。第 17 节追加 Worker 对显式合成数据的真实 VM/PG 采集验证。2026-09-24 第 18 节把 checkpoint 改成短事务租约和 fencing，并拒绝同一 item 的第二条 stream。2026-09-24 第 19 节在 lease 修改后重跑了 Worker 的 PostgreSQL 与 VictoriaMetrics 测试。第 20 节补上查询身份标签校验、导出响应 2MB 传输上限，以及 `entity.read`/`metric.read` 的查询用例、HTTP API 和指标页。未联调厂商 Zabbix，未接 Keycloak。指标页没有接入生产图表库，曲线由可替换的 SVG 适配器绘制。

当前阶段是 M1 开发身份切片、M2 数据接入和 M3 指标链的部分实现，尚未达到 M2 真实来源退出条件或 M4 只读诊断 MVP。M0 已关闭。2026-09-22 实查 `f0bcc32` 的 GitHub Actions：contracts/rust/java/web/deploy 五个 job 均 success；这是上一提交的 CI/部署流水线结果，不代表本次 History 修改已部署或真实来源已接通。

2026-09-25：查询响应契约、平台启动配置和指标页渲染/状态清理缺陷已修复。本机 51 项 Java 测试（真实 PG/VM，零跳过）、195 项领域 smoke、42 项契约测试、Rust 默认/全 feature 各 25 项、Web 类型/构建及 18 项 Playwright 均通过；浏览器经真实 Java API 查询 PG 元数据和 VM 曲线的独立脚本通过，来源仍为 labeled fixture。详见验证报告第 21 节，不代表新 CI 或生产部署。

## 写了测试但交付包当时未执行

Rust `runtime_tests.rs` 中的测试检查租户/incident范围、时间窗口、历史asOf、过期访问、证据伪造、并行预算、Skill输出和HTTP边界。ZIP 交付环境没有 Cargo/rustc。本地初始化已执行这些测试并编译默认与可选 feature；**仍未做真实模型调用、MCP 联调、Docker 镜像 digest 或生产鉴权**。

2026-09-25 追加持久只读重放：79 项契约、263 项领域检查、61 项 Java 测试（PG/VM，零跳过）、Rust 默认/全 feature 各 25 项、类型/构建及 32 项 Playwright 通过。真实浏览器→Java→PostgreSQL 刷新找回报告通过，库存未变化。租约中断由可控时间模拟，未做 OS 杀进程或 HA 演练；见验证报告第 23 节。

2026-09-25 追加私有草稿：92 项契约、289 项领域检查、64 项 Java（PG/VM，零跳过）、Rust 默认/全 feature 各 25 项、Web 类型/构建及 38 项 Playwright 通过；实际浏览器→Java→PostgreSQL 保存草稿→刷新载入→预览发布→持久重放通过。修复载入时 select 只更新属性而未更新所选值的问题，见第 24 节。

## 下一步优先级

当前优先级以第 37 节为准：核心只读筛选恢复、规范化告警历史、补充来源字段审核、资产观测历史、公共请求/开发会话和人工关联已完成本机切片验收；Worker 服务身份也已完成本机切片，继续通用跨源 Resolver/presence 与费用/留存。以下段落保留之前增量的历史检查，旧“下一项”已由较新章节替代。

2026-09-25 追加 M3 持久化与页面：141 项契约、408 项领域检查、80 项 Java（真实本机 PG/VM，零失败/错误/跳过）、Rust 默认/全 feature 各 25 项、类型/构建及 54 项 Playwright 通过。实际浏览器→Java→PG 告警幂等导入、状态流转和刷新找回时间线→重新授权资产→同窗口 VM 查询通过；缺失历史指标保持 No data，来源为 labeled-fixture。截图已查看，见第 27 节、ADR-021 和本机验收手册。旧实体列表接口已增加 100 条上限，超限要求分页。此前“未持久化/未创建 Incident”的描述仅适用于第 26 节的 GET 读取切片。下一项为 Java Tool Gateway/Rust HTTP/AIInsight 持久化，同时收尾人工合并/拆分、统一会话和多源来源治理。

2026-09-25 追加 M3 读取切片：Zabbix problem/recovery 有界读取 API、显式恢复缺失、抑制标记与领域乱序合并规则已提供。122 项契约、363 项领域检查、73 项 Java（PG/VM，零跳过）及实际 HTTP Schema 校验通过；修复开发环境 date-time 校验依赖缺失，见验证报告第 26 节、ADR-020。接口返回 not-persisted，尚未创建 Incident 或提供告警页面。用户确认暂未准备外部环境，继续本地实现，真实验收项不勾选。

2026-09-25 追加 OW-R08：资产服务端名称/IP、类型和生命周期筛选；UUID 游标分页、数据库查询前的租户/对象范围；新授权详情读取与来源/映射版本展示；Token 切换和 401/403 清理旧数据。104 项契约、315 项领域检查、69 项 Java（PG/VM，零跳过）、Rust 默认/全 feature 各 25 项、Web 类型/构建及 42 项 Playwright 通过。实际浏览器→Java→PG 筛选/详情通过，来源仍为 labeled fixture，见第 25 节。目标已设为 MVP 100%，当前约 55% 的估算未变；后续验收项见 [MVP-CHECKLIST.md](MVP-CHECKLIST.md)。

2026-09-25 追加 Host 流水线闭环：63 项契约、232 项领域检查、56 项 Java 测试（真实 PG/VM，零跳过）、Rust 默认/全 feature 各 25 项、Web 类型/构建与 25 项 Playwright 通过。浏览器经实际 Java API / PostgreSQL 执行 Preview→发布→只读 Replay 验收通过，来源仍明确为 fixture。详见验证报告第 22 节。

History 已推进到默认关闭的本机 Worker → VictoriaMetrics → 持久 checkpoint；详见 ADR-017 和验证报告第 17 节。采样点只在时序库存储，PostgreSQL 只保存每条数据流的进度和身份摘要。

受控时序查询 API 与指标页已实现，见验证报告第 21 节和 `runbooks/metric-query-acceptance.md`。Host PipelineVersion/Preview/只读 Replay API 及表单页面已提供，版本发布与历史 Raw 重放使用真实 PostgreSQL 验证，见第 22 节和 `runbooks/host-pipeline.md`。下一步在获得可达 Zabbix 配置后完成厂商实例验收；持久只读重放记录、幂等与显式恢复已补齐（第 23 节、ADR-018）；私有草稿保存/载入和乐观并发保护已补齐（第 24 节、ADR-019）。写入型修复、后台调度尚未实现；真实来源配置未具备期间继续资产筛选/分页/详情。不要加深 IAM 或做 Copilot，不将本机切片称为生产/分布式能力。

Rust 诊断的当前`RunState`枚举不是持久化执行引擎；同步诊断遇到进程退出会中断。Mock输出不是AI；引用校验不是事实/因果验证；前端模块卡片不是已实现模块。
