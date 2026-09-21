# ADR-010：保留 counter/histogram/预测语义

状态：建议基线 · 日期：2026-09-21

## 决策

指标单位、类型、维度、temporality、transformVersion 完整；Forecast 单独保存 issuedAt 与 targetTime。

## 代价与后续

不能平均 p95、不能把 user CPU 叫 total、不能把异常分数直接叫概率。

关联：`docs/architecture/v4-design.md`。
