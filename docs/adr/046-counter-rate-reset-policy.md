# ADR-046：counter（SUM）变化率与 reset 策略

状态：实现与实际验证见 [验证报告第52节](../VALIDATION-REPORT.md)。

## 问题与决定

M3 的验收要求“时区、缺失点、**counter reset** 等有用例”，设计文档也要求“计数器变化率和单位换算在
查询/转换层明确实现”。但实现里只有 `GAUGE`/`SUM`/`HISTOGRAM` 三个类型与原始点：查询返回原始值，没有
变化率，也没有任何 reset 规则或测试——计数器回绕时既看不到速率，也没有任何东西阻止负速率被当成数据。

决定：对 `SUM` 定义，查询层在原始点之外派生**每秒变化率**，并采用显式策略
`reset-counts-from-zero`：相邻两点相减，若变小则视为计数器重置，该区间增量取当前值（按从零重新计数），
并把该区间标记 `counterReset: true`；非正区间（重复/乱序时间戳）与负值区间**跳过而不编造**；派生速率
永远非负。原始点、状态与范围不变。`GAUGE`/`HISTOGRAM` 页面保持 `derivation: null` 且不产生派生速率。

## 契约

响应新增两个可选字段（[metric-series-page.schema.json](../../contracts/schemas/v1/metric-series-page.schema.json)）：

- 页面级 `derivation`：`null` 或 `{"kind":"counter-rate","resetPolicy":"reset-counts-from-zero"}`。
- 每行 `counterRates`：`{"t":<毫秒>,"rate":"<非负十进制字符串>","counterReset":<boolean>}` 数组。

不变量：声明 `derivation` 的页面每行必须带 `counterRates`；`rate` 不接受负数、指数或空白；`counterReset`
必须是布尔值；原始 `points` 始终保留，便于对照。派生是读取期的视图，不写入存储、不改变采样。

## 边界

本轮不做窗口聚合（sum/avg/quantile）、不做单位换算、不做直方图分位、不跨 series 合并；速率固定为每秒、
小数点后 6 位（HALF_UP）。reset 检测无法区分“真正的重置”与“本就非单调的 SUM”，这类指标应映射为
`GAUGE`（映射层职责）。真实厂商采样与真实 Zabbix 行为仍未验收；本策略只保证本机语义明确、可复核。
