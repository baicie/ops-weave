# ADR-032：人工核对的资产 UUID 登记与受控定位

状态：采纳（2026-09-25），只覆盖首个强标识登记/定位切片。前置 ADR-026/027；四个启动单元不变。

## 问题

固定 CMDB 导入原先要求人工先选目标 Host，字段审核只能证明“向这个目标提交了这些值”，没有独立的资产登记依据。按名字/IP 合并会把可变属性误当身份；仅相信来源声称的 UUID 同样不能证明两个已存在资产应合并。

## 决策

由具有 `entity.read`、`entity.manage` 和配置 CMDB 来源 `source.sync` 权限的用户，在核对资产登记系统后登记 canonical UUID。平台固定 `OPSWEAVE_IDENTITY_NAMESPACE` 和 `OPSWEAVE_CMDB_IMPORT_SOURCE`，两者任一缺失时能力关闭。用户提供的 UUID 是待核对业务值，不能覆盖 Principal 的 tenant、actor 或权限。

一个 `(tenant, namespace, asset-uuid)` 同时只允许一个生效目标。只支持 Host、规范小写 UUID version 1–8/variant 89ab，拒绝空值、nil、全 f、占位文本、名字和 IP。不同企业登记系统必须使用不同命名空间；命名空间不来自来源记录。HTTP 写入携带 `expectedNamespace` 作为用户已核对配置的前置条件，配置变化返回 409。

新增纯 Java `AssetIdentity`/`AssetIdentityStore`，内存实现与 PostgreSQL V017 实现相同语义。记录保留登记人、原因、时间；撤销保留自己的 requestId、actor、原因和时间。登记版本 1、生效；撤销版本 2、历史只读。登记/撤销不改 Entity ID、ExternalLink、遥测绑定、Incident 或资产属性，但原子递增 Entity version，使旧预览和证据按已有规则失效。

PostgreSQL 与来源同步/字段审核共享实体锁；跨实体竞争由 scoped key advisory lock 和唯一 partial index 保护。请求回执与实体版本、登记状态同事务；同租户 requestId 绑定原命令、目标、命名空间和可信 actor，相同重试返回原始回执，冲突全部回滚。撤销后旧登记重试仍返回原始 ACTIVE 回执，表示原命令结果；页面重新读当前登记表，不能把历史回执当现状。

全局定位仅查当前 tenant/configured namespace 内的生效登记，再检查目标 read/manage 权限；缺失或不可见目标统一 404。返回目标、当前版本和登记 pin。Web 重新读取目标并核对 tenant/version，后续 CMDB 导入可携带这个 pin。暂存与 ACCEPT 均在实体锁内复核 pin 仍生效且属于此目标；更换/撤销后的旧导入不能接受。原未携带 pin 的人工选目标流程继续存在，不宣称其已由强标识定位。

生效字段若引用此登记，撤销登记先返回 409。用户须显式撤销字段确认、恢复最新主来源，再撤销登记。无需推断或自动回滚字段。待审记录和历史记录保留原 pin，审计读取不重写历史；相同暂存请求返回原记录不构成重新授权绑定，后续接受仍检查当前状态。

最多 16 个生效登记、1000 条历史登记/实体（跨命名空间合计）；分页最多 25 条+1 探测。JSON 请求 16 KiB，查询与写入保持超时；预算耗尽显式失败。没有时间清理或 tenant 总量预算。新能力不暴露给模型 Tool、Worker 或 Runtime 委托。

## 后果与范围

这是人工核对登记 → 精确定位 → 固定字段导入依据的基础设施，不是来源自声明即可自动匹配，也不是已有 Entity 合并/alias/迁移。没有自动 CMDB API、连续多来源 presence、快照 tombstone、通用 Resolver 优先级/冲突隔离或跨源事件对账；这些仍为 M2 待办。原 `cmdb-host-import@1` 的四字段映射和 digest 不变，pin 是附加的身份前置条件及审计依据。

无新依赖、数据库或微服务。V017 为追加迁移，旧 SourceReview JSON 缺少可选 identity 时仍可读取。一般生产迁移、容量和真实资产登记来源尚未验收。实际检查见验证报告第 38 节；配置及复现见 [runbook](../runbooks/asset-identity.md)。
