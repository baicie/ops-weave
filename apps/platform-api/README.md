# platform-api

启动装配：健康检查默认公开。`/api/**` 需要认证边界构造的 `Principal`。默认 `OPSWEAVE_AUTH_MODE=closed`。

本地开发显式设置 `OPSWEAVE_AUTH_MODE=dev` 与至少 32 字符的 `OPSWEAVE_DEV_TOKEN`，且仅 loopback。业务代码不得从 query/body 读取 tenant 或权限。

```bash
OPSWEAVE_AUTH_MODE=dev \
OPSWEAVE_DEV_TOKEN="$OPSWEAVE_DEV_TOKEN" \
OPSWEAVE_ZABBIX_MODE=fixture \
gradle :apps:platform-api:bootRun
```

`POST /api/v1/integrations/zabbix/hosts/sync` 与 `GET /api/v1/entities` 走授权。fixture 模式会在响应里标记 `dataMode=labeled-fixture`。`OPSWEAVE_ZABBIX_MODE=jsonrpc` 需要 URL 与 `env:` 密钥引用；失败返回 503，不回退 fixture，也不删除已有资产。

OIDC、PostgreSQL、Item/Metric/Incident 均未实现。健康检查不等于业务可用。
