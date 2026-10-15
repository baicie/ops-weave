# 可观测数据与证据呈现

适用范围：Incident、Metric、Trace、Log、资源关系、Evidence、调查与 RCA 的页面、组件和图表，无论位于哪个前端目录。

优先阅读 `docs/ui/observability-visualization.md`。

- 所有指标图包含 Unit、Time Window、Source、Aggregation、必要时 Sampling；时间轴统一时区和对齐方式；多信号叠加必须注明各自轴或使用分面图。
- `NO_DATA` ≠ 0，`PARTIAL` ≠ 完整，`STALE` ≠ 当前可用，`FIXTURE` ≠ 真实。未知取值不得渲染为健康状态。
- Hypothesis 与 Fact 在文字和视觉上分离；支持证据、反证、待检验均有可访问的文本；不能用伪造百分比显示因果置信度。
- Evidence 有引用、来源、采集/事件时间及其可见性状态；点击可打开原始记录或展示可信定位条件；失效证据要有显式说明。
- Trace 表格/瀑布图不得将毫秒与纳秒混合计算；Log 保留原始时区、severity 和来源；不能把单条 Log 当整体服务健康。
