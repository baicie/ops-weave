# platform-api

启动装配：健康检查默认公开。`/api/**` 需要认证边界构造的 `Principal`。默认 `OPSWEAVE_AUTH_MODE=closed`。

本地开发显式设置 `OPSWEAVE_AUTH_MODE=dev` 与至少 32 字符的 `OPSWEAVE_DEV_TOKEN`，且仅 loopback。业务代码不得从 query/body 读取 tenant 或权限。

```bash
OPSWEAVE_AUTH_MODE=dev \
OPSWEAVE_DEV_TOKEN="$OPSWEAVE_DEV_TOKEN" \
OPSWEAVE_ZABBIX_MODE=fixture \
gradle :apps:platform-api:bootRun
```

`POST /api/v1/integrations/zabbix/hosts/sync` 按页拉取，直到快照完整才把未见 Host 标为 `INACTIVE`。中途失败返回 503，不回退 fixture，也不对账删除。

默认 `OPSWEAVE_INVENTORY_STORE=postgres`，需要 `OPSWEAVE_JDBC_URL` 与用户名。`memory` 只用于显式测试。Item 同步、指标目录/绑定与 History 只读接口已提供；OIDC、Incident、时序库写入与对象存储尚未实现。健康检查不等于业务可用。

`GET /api/v1/integrations/zabbix/items/{itemId}/history?from=...&till=...&limit=100` 读取已同步的活动绑定，要求 `source.sync`、`metric.read`、`entity.read` 和对象范围。窗口最多 3600 个完整秒，续页同时传 `afterClock` / `afterNs`。结果携带 `dataMode` 和 `persistence=not-persisted`，不是已落库指标。详见 [运行说明](../../docs/runbooks/development.md) 与 [契约](../../contracts/openapi/platform-draft.yaml)。
