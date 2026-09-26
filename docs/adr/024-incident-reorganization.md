# ADR-024：人工调整 Incident 归属与后续观测

日期：2026-09-25。状态：本机实现；真实来源和生产身份未验收。承接 ADR-021/022/023，属于 M3 人工工作台，不新增启动单元或 AI 动作工具。

## 问题

一个外部问题默认按 tenant/source/event 得到初始 Incident。人工把问题合并或拆分后，继续使用初始 ID 入库会把恢复事件写回旧 Incident。仅修改页面分组无法保证后续归属、幂等和证据有效性。

## 决定

`IncidentReorganization` 是 Java 纯领域操作。身份来自现有可信 Principal；请求只有人工意图、双方 ID/预期版本、问题键、标题、原因和 requestKey，不接受 tenant、actor 或权限。执行前要求双方 `incident.manage`、`incident.read` 及关联实体范围，存储事务内再次校验范围。拆分新 ID 也必须处于调用方允许的 Incident 范围；当前开发页需要 tenant-wide Incident 权限。

- 合并把来源全部问题交给同租户现有目标。来源不能已合并或 CLOSED，目标须未合并且处于 OPEN / INVESTIGATING。双方版本各增加 1。来源保留原问题/时间线和人工状态，标记 `mergedInto`，从活动列表移除，仍可按 ID 受权读取。它是历史归档，不再接受观测、诊断或新的状态操作。
- 目标接收问题和 PROBLEM / RECOVERY 事实，来源的人工状态历史仍留在来源。合并不会宣告恢复，也不会自动重新打开已经处置的目标。
- 拆分只允许非空真子集，来源至少留下一个问题。目标必须是新 ID、预期版本 0，建立 OPEN / 版本 1 的 Incident。选中问题及其事实时间线移入目标，保留原始发生/观察/首次接收时间；来源人工状态保持不变。
- 每个结果仍受 50 个问题、100 条时间线、100 个实体及 JSON 字节数约束，超限整体拒绝。暂未提供空来源、撤销操作、无限时间线或自动相关性规则。

`alerting.external_problem` 中 tenant/source/event 的 `incident_id` 是当前归属索引。后续导入按这个索引更新当前 Incident，不能从初始 ID 反推归属。多条问题落入同一 Incident 时，导入统计按不同 Incident 计数，版本仍按实际变化推进。空页、来源失败和迟到观测不产生虚假恢复。

## 原子性、重试与历史

V013 在原 PostgreSQL 增加合并目标列及关联请求表，不增加数据库。问题导入与关联调整使用同一租户事务 advisory lock；这是低吞吐业务事件路径，不参与指标点采集。双方记录按固定 UUID 顺序锁定。两份聚合、每个问题归属及操作回执在同一事务提交，任一失败全部回滚。

单条语句 3 秒、锁等待 2 秒，取得租户锁后循环总预算 10 秒；请求上限 16 KiB，回执上限 32 KiB。requestKey 绑定原 actor 和完整请求；同键同内容返回原回执，异内容或异 actor 冲突，不进行隐式重试。

回执记录双方版本、移出的问题键、actor、原因、发生时间。GET 不执行变更，重新检查双方当前可见范围。历史分页把双方 Incident/Entity 条件放在 LIMIT 前，最多 25 条，以 UUID 游标排序；不是时间顺序或跨页一致快照。回执不能代替完整的来源 Observation 历史。

存储初始化同时修复迁移 DDL 和迁移标记的原子提交：事务 advisory lock 后重新查标记，失败回滚整项迁移。`InventoryWiring` 持有的连接池在 Spring/测试关闭时释放，初始化失败也关闭；避免测试或反复初始化耗尽连接。这些不构成生产 HA 或迁移回退验收。

## 诊断证据

Incident 的可选 `organization` 保存最近一次关联变更对应的 Incident 版本、changeId、mergedInto；普通状态/观测更新保留此元数据。旧无关联变更的快照保持兼容。

创建读取会话拒绝合并归档。读取证据时，若 Incident 已合并或最近关联版本超过快照版本，返回 INPUT_CHANGED / HTTP 409，即使实体集合没有变化也不能继续读取旧证据。当前会话的版本固定检查和 AIInsight 保存前检查仍生效。AIInsight GET 重新检查原证据，因此关联变更后也拒绝旧结果；不可将旧分组结论当作新分组的有效诊断。

## 页面和验收

页面读取双方后固定版本，先显示预览，再由用户确认。结果不确定时冻结输入，用同一 requestKey 重试或 GET 找回回执；刷新 URL 只保留 ID/请求标识，Token 仅存在页面内存。身份切换、权限失败和过期响应有清理；版本冲突要求重新读取。

实际检查见 [验证报告第 30 节](../VALIDATION-REPORT.md)。[本机步骤](../runbooks/incident-reorganization.md) 使用显式 fixture 和真实本机 PostgreSQL/VM。真实 Zabbix、模型、登录、完整观测历史仍是 MVP 待办。
