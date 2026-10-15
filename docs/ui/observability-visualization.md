# Observability Visualization Contract · UI-DATA-001

## 图形选择原则

| 问题 | 推荐 | 不推荐 |
|---|---|---|
| 延迟随时间变化？ | 折线图，显示 P50/P95 等独立含义 | 柱图且没有时间粒度 |
| 哪个组件慢？ | Trace waterfall，含 Parent/Span/Duration | 只列 Span 名字 |
| 什么事件在何时发生？ | Incident/Event 时间线 | 多个无关联 Card |
| 哪些错误重复发生？ | 可过滤日志表格 + 错误聚合 | 把日志正文全部塞卡片 |
| 哪个资源关联？ | 受控资源关系图 + 列表回退 | 只显示漂亮的力导向图 |
| 假设是否成立？ | 支持/反证/未知检查三栏或列表 | 未校准置信度百分比大仪表 |

## 必须传递的 ViewModel 字段

```ts
interface SignalViewMeta {
  sourceId: string;
  dataMode: 'real' | 'fixture' | 'mock' | 'unknown';
  unit?: string;
  aggregation?: 'raw' | 'average' | 'sum' | 'p50' | 'p95' | 'p99';
  queryWindow: { from: string; to: string; timezone: string };
  sampled?: boolean;
  sampleRate?: number; // 只有真实提供的采样率才能出现
  partial: boolean;
  stale: boolean;
  warnings: string[];
}
```

## 时间轴

- 跨源数据统一解析成带时区时间或 Epoch；图表内部显示统一时区；原始 `eventTime`/`observedAt` 分开。
- 采样率不同的曲线不得伪装严格对齐。缺失区间显示 gap，不做 silent interpolation。
- P95 与均值不可混称，Gauge 与 Counter 不可共用相同的计算公式。
- Timestamp 纳秒精度在前端不得直接通过 JavaScript Number 无损表示；优先字符串/BigInt/受控转换。

## 证据

- 关键结论必须能定位到 Evidence ID 和 Source Query，必要时显示“引用已过期/目前无权限”。
- `Fact`（观察）、`Hypothesis`（解释）、`Human-confirmed root cause`（确认）应使用不同语言与展示层，不要用“已证实”描述未审查模型推断。
- 证据不足输出 `inconclusive`，面板不应显示绿色“根因定位成功”。
- 未经脱敏的日志正文不可放入截图、控制台 `console.log` 或长期浏览器缓存。

## AI 调查过程

允许展示：工具类型、目的、时间、范围、返回码、采样、支持/反证、预算进度。
禁止默认展示：系统 Prompt、模型私有推理、Credential、未授权 Entity 或全量查询 payload。
