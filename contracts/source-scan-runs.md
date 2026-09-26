# 来源扫描运行追溯（只读）

平台把每次来源扫描的运行结果持久化在 `integration.source_sync_run`。本契约只描述**读取**这些
已经存储的运行记录；它不触发扫描、不重试、不修复元数据、不对账缺失对象，也不连接任何来源。

## 端点

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/integrations/zabbix/hosts/runs` | Host 扫描的最近运行，按开始时间倒序 |
| GET | `/api/v1/integrations/zabbix/hosts/runs/{syncRunId}` | 按运行标识读取一条 Host 扫描 |
| GET | `/api/v1/integrations/zabbix/items/runs` | Item 扫描的最近运行 |
| GET | `/api/v1/integrations/zabbix/items/runs/{syncRunId}` | 按运行标识读取一条 Item 扫描 |

查询参数只有两个：`limit`（1–50，默认 20）与 `after`（服务端返回的不透明游标，回填即可翻页）。
其它参数一律 400；`after` 不是授权凭据，租户始终来自可信 Principal，来源始终来自配置。

## 列表响应

```json
{
  "schemaVersion": "1.0",
  "storage": "postgres",
  "dataMode": "scan-log",
  "tenantId": "tenant-demo",
  "sourceInstanceId": "zabbix-1",
  "objectType": "host",
  "limit": 20,
  "after": null,
  "hasMore": false,
  "nextCursor": null,
  "items": []
}
```

`dataMode: scan-log` 表示这份响应读取的是**持久化的扫描日志**，不是数据面内容，也不代表上游在线。
每条运行自带 `dataMode`（例如 `labeled-fixture`），如实反映那次扫描写入数据时使用的模式。

## 单条响应

```json
{
  "schemaVersion": "1.0",
  "storage": "postgres",
  "dataMode": "scan-log",
  "tenantId": "tenant-demo",
  "sourceInstanceId": "zabbix-1",
  "objectType": "host",
  "run": { "syncRunId": "8a2b4c6d-1e3f-4a5b-8c7d-9e0f1a2b3c4d", "status": "SUCCEEDED" }
}
```

`run` 的完整字段见 [source-scan-run.schema.json](schemas/v1/source-scan-run.schema.json)：运行标识、
对象类型、状态、开始/完成时间、来源游标、pages/fetched/accepted/rejected、`snapshotComplete`、
运行自身 `dataMode`、`scanConsistency`（本次 walk 如何被约束：`offset-scan-attempt` 或
`hostid-watermark-snapshot`；只有 `snapshotComplete: true` 才代表边界被验证），以及可选的
`failureCode`/`failureSummary` 与 `pipelineVersion`。

## 边界与不变量

- 只返回已存储的运行；未知、跨租户、跨来源或跨对象类型的 `syncRunId` 一律 404，不泄漏存在性。
- `SUCCEEDED` 必须 `snapshotComplete: true`；`RUNNING` 与 `FAILED` 必须为 false，且 `RUNNING` 没有
  完成时间。失败扫描不因为被读取而改变状态，也不会触发对账或删除。
- `failureCode` 只可能是平台自己写入的稳定失败码之一；`failureSummary` 是该码的固定摘要。
  存储中出现任何未知文本时，响应**不带** `failureCode`/`failureSummary`，也不会回显原文。
- `pipelineVersion` 是这次扫描启动时钉住的映射版本（`pipeline-ref` 形状）；没有钉住时不出现。
- 授权沿用源级 `source.sync`；只有 `entity.read` 不能读取扫描日志。所有响应禁止缓存。
- `limit` 上限 50，`after` 只接受服务端签发的不透明值；客户端构造的游标一律 400。

## 样例

- [source-scan-run.json](examples/source-scan-run.json)：一条失败的 Host 扫描。
- [source-scan-run-page.json](examples/source-scan-run-page.json)：成功与失败各一条的列表页。
- [source-scan-run-read.json](examples/source-scan-run-read.json)：按标识读取成功扫描。

样例全部是自有合成数据，不代表真实 Zabbix 实例已经接入。
