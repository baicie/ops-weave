# ADR-007：会话、Run、Context、Evidence 分开

状态：建议基线 · 日期：2026-09-21

## 决策

任务状态持久化，Context 按 asOf 构建，证据带期限和访问控制，缺数显式报告。

## 代价与后续

不能把聊天摘要作为授权，不能拿当下查询冒充历史快照。

关联：`docs/architecture/v4-design.md`。
