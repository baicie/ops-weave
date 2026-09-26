# 来源连接自检（只读）

平台可以对人配置的来源做一次**有界只读自检**：它调用连接器已有的 probe（fixture 模式是显式标注的
合成探针，JSON-RPC 模式是 `apiinfo.version`），把结果记成回执。自检不启动扫描、不取租约、不写库存、
不调用模型，也不会在失败时回退到 fixture。

## 端点

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/v1/integrations/zabbix/connection-checks` | 执行一次自检并返回回执 |
| GET | `/api/v1/integrations/zabbix/connection-checks?limit=` | 该来源最近的自检回执（倒序，1–50，默认 20） |

权限沿用源级 `source.sync`；租户来自可信 Principal，来源来自配置。响应沿用平台统一边界
（`Cache-Control: no-store`、`nosniff`）。未知查询参数 400；无权限 403；来源未配置 503。

## 回执

```json
{
  "schemaVersion": "1.0",
  "storage": "postgres",
  "dataMode": "connection-check",
  "tenantId": "tenant-demo",
  "sourceInstanceId": "zabbix-1",
  "check": {
    "checkId": "b7d4f0a2-6c31-4f8e-9a5b-2d7c1e4f8a90",
    "sourceInstanceId": "zabbix-1",
    "actor": "operator",
    "checkedAt": "2026-09-26T06:05:00.123456Z",
    "dataMode": "labeled-fixture",
    "reachable": true,
    "statusCode": "labeled-fixture",
    "reportedVersion": null
  }
}
```

字段语义见 [source-connection-check.schema.json](schemas/v1/source-connection-check.schema.json)：

- `dataMode` 是**该来源配置的模式**（`labeled-fixture`/`zabbix-jsonrpc`/`closed`），不是这次探针的结论；
  fixture 回执永远带 `labeled-fixture`，不代表真实厂商可用。
- `statusCode` 是稳定码（`labeled-fixture`、`ok`、`unreachable`、`not-configured`），不是厂商原文。
- `reportedVersion` 是来源**自报**的版本（JSON-RPC 模式下来自 `apiinfo.version`，最多 32 个可打印字符），
  是对照“已声明支持版本”的输入，不是平台验证过的支持声明；不可达的回执必须为 `null`。
- 连接器抛异常时回执是 `reachable: false` + `unreachable`，绝不变成成功，也不回退 fixture。

## 留存与边界

每个 tenant/source 保留最近 100 份回执（新回执写入时清理更旧的），读取单页上限 50。回执不参与授权、
不能被请求覆盖，也不会触发扫描、对账或任何写入。真实 Zabbix 的 `apiinfo.version`、TLS/代理行为仍需真实
环境验收；本契约不声明厂商实例已经验证。

## 样例

- [source-connection-check.json](examples/source-connection-check.json)：一条 fixture 自检回执。
- [source-connection-check-receipt.json](examples/source-connection-check-receipt.json)：POST 响应信封。
- [source-connection-check-page.json](examples/source-connection-check-page.json)：含一条不可达回执的列表。

样例全部是自有合成数据。
