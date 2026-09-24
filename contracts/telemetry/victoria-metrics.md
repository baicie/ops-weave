# History → VictoriaMetrics 写入约定 v1

输入契约仍为 `schemas/v1/metric-history-page.schema.json` 与 platform OpenAPI 的 History 端点。Worker 不接受外部请求中的 tenant/权限/任意来源 URL。此文定义存储适配，不新增 Agent Tool 或 Python 后端。

当前使用 VictoriaMetrics **single-node v1.152.0** 的非流式 InfluxDB HTTP 协议，固定 `POST /write?precision=ms`、`Stream-Mode: 0`。测量名 `opsweave_metric`，唯一字段 `value`，存储后的序列名为 `opsweave_metric_value`。

| 标签 | 来源 |
|---|---|
| `tenant_id`、`source_instance_id`、`external_item_id` | 可信配置与平台授权响应必须一致 |
| `entity_id`、`metric_key`、`unit` | 平台响应的实体引用和指标语义 |
| `mapping_revision` | 固定映射修订 |
| `data_mode` | `labeled-fixture` 或 `zabbix-jsonrpc`，必须与配置一致 |
| `dimension_<name>` | 平台固定维度，前缀避免覆盖身份标签 |

不把 runId、采样时间、Prompt 或日志放入标签。当前标签名限 `[a-zA-Z_][a-zA-Z0-9_]{0,63}`，值限 `[a-zA-Z0-9_.:/%\-]{1,128}`，超出字符子集则明确拒绝。定义/绑定的每次同步版本不作为标签；单次读取跨页必须保持版本一致。

时间戳向下转换到毫秒，同毫秒同值合并，不同值报 `MILLISECOND_COLLISION`，整批不写。值只接受可用十进制字符串往返表示的有限 float64；超精度报 `VALUE_PRECISION_LOSS`，不会悄悄舍入 uint64。

写前用 `/api/v1/export` 回读同一组完整标签与有限时间范围：已有同值点跳过，已有不同值报 `STORED_VALUE_CONFLICT`。只发送缺失点，HTTP 204 后最多回读 11 次、间隔 2 秒（最多 20 秒等待，HTTP 各自限时）确认；失败不更新 checkpoint。服务端必须配置 `-dedup.minScrapeInterval=1ms`，兼容的 Influx 命名/毫秒精度，且不开启 Influx 强制流式处理。每批校验 `/flags`。

HTTP 成功不代表逐点保留成功（例如数据已超出保留期）；只有全部点可查询且值一致才视为本轮确认。确认可见性不等于跨库事务或断电持久化保证。未知写入结果允许下轮显式重放，VM 按同序列/毫秒去重；不声称物理 exactly-once。

产品查询不接受调用方传入的 PromQL 或 MetricsQL。`VictoriaMetricsQueryAdapter` 只用 `tenant_id`、`entity_id`、`metric_key` 生成 `opsweave_metric_value` 的 `/api/v1/export` 选择器，窗口最多 3600 秒，返回点最多 500。同一指标的不同 `source_instance_id` 保持多条 series。导出为空是 `NO_DATA`；连接失败或非 200 是 `SOURCE_UNAVAILABLE`，不能写成空数组。

来源：[VictoriaMetrics InfluxDB 协议](https://docs.victoriametrics.com/victoriametrics/integrations/influxdb/)、[存储与去重](https://docs.victoriametrics.com/victoriametrics/)、[查询可见延迟](https://docs.victoriametrics.com/victoriametrics/keyconcepts/#query-latency)。OpsWeave 的限制与失败码由本仓库定义。
