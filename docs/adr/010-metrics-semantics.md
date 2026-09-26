# ADR-010：保留 counter/histogram/预测语义

状态：建议基线 · 日期：2026-09-21

## 决策

指标单位、类型、维度、temporality、transformVersion 完整；Forecast 单独保存 issuedAt 与 targetTime。

## 代价与后续

不能平均 p95、不能把 user CPU 叫 total、不能把异常分数直接叫概率。

## 2026-09-26 落地：计数器变化率与 reset 策略

SUM（计数器）查询在保留原始点的同时返回派生视图，固定策略为 `reset-counts-from-zero`；原始点、单位、来源、映射版本、时间窗口与状态一律不变，页面默认展示变化率并可切回原始累计值。策略细节、契约与边界见 [ADR-046](046-counter-rate-reset-policy.md)，验收记录见 [验证报告第 52 节](../VALIDATION-REPORT.md)。变化率是平台按固定策略推导的视图，不是厂商上报速率。

关联：`docs/architecture/v4-design.md`。
