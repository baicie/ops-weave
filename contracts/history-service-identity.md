# Worker 服务身份契约

平台默认关闭此入口。`GET /api/v1/service/ingestion/items/{itemId}/history` 仅接收独立的 OAuth2 `client_credentials` access token，响应复用 `metric-history-page.schema.json`。浏览器 Cookie、dev token、Runtime delegation 不能替代服务身份；该 JWT 也不能调用普通用户 API、同步写入或 Tool。

令牌服务地址、client ID、secret 由 Worker 部署配置提供。请求使用 `client_secret_basic`（按 RFC 6749 对 ID/secret 表单编码后再 Base64），固定表单 `grant_type=client_credentials&scope=opsweave.history.read`。只接收 JSON 中的 `access_token`、`token_type=Bearer`、整数 `expires_in`（1–900 秒）和可选同值 `scope`；拒绝 refresh token、未知字段、重复字段及重定向。响应上限 32 KiB、完整请求与正文 5 秒、每次交换一次请求。令牌只在内存中缓存至单调时钟期限前 5 秒；401 清除缓存并使本次采集失败，下一次计划轮询才可重新交换。

平台采用固定的 [RFC 9068](https://www.rfc-editor.org/rfc/rfc9068.html) 子集：`typ=at+jwt` 或 `application/at+jwt`、`alg=RS256`、固定 `iss`/JWKS，`aud` 只能是 `opsweave-history`，`scope` 必须等于 `opsweave.history.read`；要求非空 `sub`、`client_id`、`jti`、非未来 `iat`、未到期 `exp`，零时钟宽限且令牌寿命不超过 900 秒。JWT 中的 tenant/权限/资源 claim 不构成平台授权。JWK 读取共享有界网络适配器，不从 JWT 的 URL 取密钥。

`history-service-grants.schema.json` 是运维授权文件唯一业务 Schema，文件不是 HTTP 写入 API。验证后的 issuer/client_id/sub 映射为文件中的 tenant/subject；文件明确 item、entity、metric、source、授权有效时间、历史数据时间范围、单次窗口/点数和每分钟次数。文件不含凭据、不接受请求覆盖，最多 256 KiB/500 个唯一 client；有效期最多 90 天，数据范围最多 31 天，单次闭合窗口最多 3600 秒且必须早于当前时间，点数最多 500，次数最多 120/分钟。跨字段时间关系和唯一 client/tenant-subject 由服务器领域与解析器检查。每次读取文件，撤销/变更/缺失/损坏对下一次请求生效，不重用旧授权。

服务路由只允许 from/till/limit，以及必须成对的 afterClock/afterNs；未知/重复参数、身份覆盖、正文、Cookie、Origin、Forwarded 和 X-Forwarded-Proto 均拒绝。普通模式要求直接 TLS；显式 `loopback-test` 仅用于本机协议测试且要求平台绑定 loopback。全实例最多 4 个并发，限流为进程内每 client 60 秒窗口。进程重启会重置限流，不代表分布式全局配额。

Worker 配置 tenant 用于核对响应及本地 checkpoint/VM 标签，不能发送给平台作为授权来源。所有页面完整读取、版本/fingerprint 核对、VM 写入与读回确认后才推进 PG checkpoint；授权/网络失败不推进游标。服务模式将 token endpoint/client ID 加入来源 fingerprint，secret 不进入 fingerprint，因而同 client 的 secret 轮换可续采；改变身份目标或从 dev 切换服务模式不能静默沿用旧系列。

参考：[OAuth 2.0 client credentials](https://www.rfc-editor.org/rfc/rfc6749.html#section-4.4)。当前不实现 opaque token introspection、多租户自动委派、远程 VM/PG 或生产 HA。
