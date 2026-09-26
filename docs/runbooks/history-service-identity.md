# History Worker 服务身份本地验收

此配置提供独立机器身份和有界历史读取，不替代浏览器 OIDC 配置。默认关闭；真实 IdP/Zabbix/TLS 尚未验收。协议、Schema、错误码见 [服务身份契约](../../contracts/history-service-identity.md)，设计见 [ADR-031](../adr/031-history-worker-service-identity.md)。

## 提供方与授权文件

在既有 OAuth 提供方中配置独立 confidential client、`client_secret_basic`、client_credentials、固定 audience `opsweave-history` 和唯一 scope `opsweave.history.read`。要求 RS256 JWT access token，at+jwt 类型、client_id/sub/jti/iat/exp，最多 900 秒。不能用浏览器 ID token、普通 JWT 或开发 token 替代；不支持仅返回 opaque token 的提供方。

从 `contracts/examples/history-service-grants.json` 创建部署专属绝对路径文件。替换已验证 issuer/client_id/sub、平台 tenant/subject、实际 entity UUID/item/metric/source、有效期限和历史范围。这里的 UUID 必须来自目标租户已同步的真实库存；不可根据名字/IP 自动猜测。文件不含 secret，不复制到浏览器/模型；由运维控制读写权限，更新用同目录临时文件加原子替换。示例日期不是自动续期配置，过期必须明确调整授权。

| 平台配置 | 含义 |
|---|---|
| `OPSWEAVE_HISTORY_SERVICE_ENABLED=true` | 显式开启独立入口；与 `OPSWEAVE_AUTH_MODE=oidc` 或开发模式并存 |
| `OPSWEAVE_HISTORY_SERVICE_ISSUER` | 完全匹配 JWT issuer 的 HTTPS 地址 |
| `OPSWEAVE_HISTORY_SERVICE_JWK_SET_URI` | 固定 HTTPS JWKS 地址，禁止查询/用户信息/跳转 |
| `OPSWEAVE_HISTORY_SERVICE_GRANTS_FILE` | 运维授权文件的绝对路径 |
| `OPSWEAVE_HISTORY_SERVICE_LOOPBACK_TEST` | 默认 false；仅协议 fixture 可置 true，平台必须绑定 127.0.0.1/::1 |

普通模式要求请求直接到达平台 TLS connector；不接受转发头来证明 TLS。本轮没有部署证书或反向代理信任配置。用户 OIDC 的 Origin/Cookie/CSRF 设置仍独立生效。

## Worker

| Worker 配置 | 含义 |
|---|---|
| `OPSWEAVE_HISTORY_ENABLED=true` | 显式启用单流定时采集 |
| `OPSWEAVE_HISTORY_AUTH_MODE=client-credentials` | 使用服务身份；此时 `OPSWEAVE_HISTORY_PLATFORM_TOKEN` 必须为空 |
| `OPSWEAVE_HISTORY_PLATFORM_URL` | 平台 HTTPS origin，无路径/用户信息/查询 |
| `OPSWEAVE_HISTORY_TOKEN_URI` | 提供方固定 HTTPS token endpoint |
| `OPSWEAVE_HISTORY_CLIENT_ID` / `OPSWEAVE_HISTORY_CLIENT_SECRET` | 独立 client 与 secret，仅通过受控环境提供，不写普通日志/仓库 |
| `OPSWEAVE_HISTORY_AUTH_LOOPBACK_TEST` | 默认 false，仅显式本机协议测试使用 HTTP loopback |
| `OPSWEAVE_HISTORY_TENANT` | 本地标签和 checkpoint 身份校验；请求不发送此字段授予权限 |
| `OPSWEAVE_HISTORY_SOURCE` / `OPSWEAVE_HISTORY_ITEM_ID` | 与平台授权和绑定相符的单流来源/item |
| `OPSWEAVE_HISTORY_DATA_MODE` | 必须显式为 labeled-fixture 或 zabbix-jsonrpc，响应不符则失败 |
| `OPSWEAVE_HISTORY_STREAM` / `OPSWEAVE_HISTORY_INITIAL_FROM` | 独立流标签与初始 epoch 秒；不能改变已有流初始身份 |
| `OPSWEAVE_HISTORY_JDBC_URL/USER/PASSWORD` / `OPSWEAVE_HISTORY_VICTORIA_URL` | 原有 loopback PG checkpoint 与 VM 存储；本次不开放远程存储 |

默认每 30 秒轮询、每次 60 秒、重叠 120 秒、延迟 10 秒，最多 4 页。每页请求 limit=500，因此平台授权 maxPoints 需为 500；maxWindowSeconds 需容纳单步和重叠。有限数据范围采完后继续轮询会被范围拒绝，应明确停用作业或更新运维授权，不自动扩大范围。

撤销方式是将该 grant `enabled=false` 或移除。下一次请求拒绝，当前已接受的有界请求可能完成；撤销不删除已采样数据。文件损坏/缺失返回 503；client/subject/时间/资源被拒绝返回 403/404；令牌无效/到期返回 401；配额返回 429。Worker 失败不会推进 checkpoint，不隐式回退 dev/fixture。secret 轮换需在提供方更新后重启 Worker；同 endpoint/client/series 会沿原 PG checkpoint 续采。切换 client、endpoint、认证模式或系列需要明确的新采集计划，不可伪造旧 fingerprint。

## 可重复本地检查

先构建两个 Java bootJar、Rust all-features 二进制与 Web 依赖，并准备**专属** loopback PG/VM。设置 `OPSWEAVE_TEST_JDBC_URL/USER/PASSWORD`、`OPSWEAVE_TEST_VM_URL`、`OPSWEAVE_TEST_CHROMIUM_EXECUTABLE`、`JAVA_HOME`。额外提供 `OPSWEAVE_TEST_PG_CONTAINER`，为当前这组 PG 的拥有者提供的容器名；脚本只用 docker exec/psql 查询本次随机 tenant/stream 的 checkpoint，不操作无关容器。

```text
node scripts/check_oidc_stack.mjs --history-service
```

脚本创建本机 RSA/JWKS/OIDC 与独立服务 client fixture，启动真实 Java 平台和 Worker，核对 VM 中 0.4 采样及 PG checkpoint；随后撤销 grant、模拟 token 服务 503、重启 Worker 并轮换 secret，检查失败期间 cursor/revision 不变、恢复后前进。相同平台继续跑 Rust mock 诊断、浏览器刷新/回读/双标签退出和 OIDC 撤权。

产物 `.tmp/oidc-acceptance/service-metric-history-page.json` 与 `history-service-grants.json` 按 canonical Schema 检查；`service-checkpoint-report.json` 只是测试事实记录，不是业务 API。身份 fixture 无真实提供方连接；来源明确 labeled-fixture、模型明确 mock。access token、client secret、Cookie、code 不输出或落盘。此脚本不等同真实 IdP/TLS/厂商/模型验收。
