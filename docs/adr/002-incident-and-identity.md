# ADR-002：身份中心与故障中心分离

状态：建议基线 · 日期：2026-09-21

## 决策

Entity/Relation 描述资源；Incident 聚合故障过程，Alarm/Change/Insight/Evidence 通过关系加入。

## 代价与后续

合并和状态转换可解释、可纠正，AI 推测不自动成为 confirmed root cause。

关联：`docs/architecture/v4-design.md`。
