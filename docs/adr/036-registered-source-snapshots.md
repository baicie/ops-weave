# ADR-036：已登记 UUID 的来源快照解析、观测与 presence

状态：已实现，本机验证见 [验证报告第42节](../VALIDATION-REPORT.md)。

后续[ADR-037](037-source-binding-correction.md)补充独立的人工绑定更正入口：需新观测与双方版本/目标pin，旧绑定入审计回执，历史不迁移。普通快照接口仍禁止隐式重绑；以下“无绑定迁移”指本快照入口。

## 问题与决定

ADR-032 的人工资产 UUID 登记已有唯一映射，ADR-027 的补充字段仍需手选目标逐条导入。它们没有连续二来源观测/presence，主 Host 完整扫描缺失也不会检查第二来源是否仍确认资产。

新增 PostgreSQL 模式的显式 CMDB 快照导入，不引入新厂商协议或后台服务。可信操作员配置固定来源与身份命名空间；调用者必须拥有 tenant-wide 的 entity.read/entity.manage/source.sync。每次最多100条、HTTP 128KiB（浏览器公共客户端64KiB），观测时间须在过去7天内，当前来源的新请求 observedAt 必须严格递增。`complete` 是有完整来源权限的操作员声明，不是外部系统一致性快照证明。

固定 `registered-cmdb-snapshot-v1` 引擎只按当前租户+配置命名空间+已核对 UUID 精确定位。名称/IP/占位序列号不参与匹配，未登记或冲突的整批失败。锁后复核 identity pin，禁止隐式目标重绑、跨namespace迁移、同一来源两个外部对象映射同一资产，以及与生效人工字段绑定冲突。来源表保留绑定，缺失不会释放给另一个资产。

## 原子写入与人工字段审核

V021 在现有 inventory schema 保存不可变输入/回执、来源确认与登记依赖。输入的规范化字符串字段保留在原回执，引用不声称保留厂商原始字节。每个已解析对象同时写不可变 Observation 与 PENDING SourceReview；映射摘要继续指向固定 CMDB 字段映射，额外记录 resolverEngine/identity pin/source-snapshot Raw 引用。

UUID 决定目标，presence 只决定来源是否确认存在，不自动采纳 owner/name/ip/environment。字段仍经原人工选择和可撤销规则；若已有生效字段，先撤销再用新时间和新请求导入，避免用旧实体版本确认新字段。原 Zabbix ID、MetricBinding、Incident 和证据引用不迁移。

快照来源锁复用 ADR-035 的 tenant/source/cmdb-host 作用域，按实体ID排序获取共享实体锁，再验证绑定。单个有界事务完成所有观测/待审记录/presence/缺失标记/回执；30秒租约检查和数据库时间限制最终提交，不持有连接执行厂商HTTP。失败回滚全批；没有部分成功被解释为空快照。未完整的批次只能更新出现的对象。

同 actor/source/namespace/requestId/原输入/摘要返回最初回执，不同输入或主体冲突。GET 原回执重新授权，其他主体/租户/配置返回隐藏的404；404不证明未知写入失败。每租户/来源最多100个保留绑定与1000份回执，不自动清空，尚不是全平台总容量治理。

## 生命周期与时效

新鲜、present=true 且登记仍有效的第二来源可以保护主来源已缺失的实体保持 ACTIVE。主来源缺失只将其主快照置 INACTIVE；不会物理删除其他来源确认的实体。完整 CMDB 导入将该来源未出现的已有绑定标为ABSENT；连续缺失会更新最后确认时间、snapshotId和实体版本，markedAbsent包含本批再次确认缺失的绑定。部分批次和失败批次不对账。重新出现可恢复有效确认。

第二来源到期、登记撤销或明确缺失都会使其失去保护作用。PG详情/列表/分页在数据库查询时应用同一有效性判断，筛选早于分页；不依赖后台清理后才撤销过期保护。读时过期不会伪造新的来源事件或增加实体版本，响应仍为当前时刻的视图。presence页在一个一致读取事务内记录 evaluatedAt，明确 PRESENT/ABSENT/STALE/IDENTITY_REVOKED；无记录意味着未知，不能推断缺失。主来源自身的时效策略不在此变更中新增。

## 保留边界

没有真实CMDB API、后台轮询、任意Connector配置、已有Entity合并/alias/绑定迁移、自动字段批准或模型工具。输入是显式import，不标live；上游权限与完整性由具备整源管理权限的操作员确认。PG是该持久功能的唯一实现，memory模式失败关闭，不静默回退。

Zabbix Host仍是offset-scan-attempt；本实现不修复上游分页漂移。主来源过期策略、多个补充来源的通用权威规则、分布式容量与生产迁移/HA仍需后续验收。迁移应停止旧版本写进程，旧 writer 不认识来源确认规则。真实来源/模型/IdP和人工诊断审阅仍不算通过。
