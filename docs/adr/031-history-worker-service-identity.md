# ADR-031：独立 History Worker 服务身份

- 状态：Accepted（本地实现与协议验收；真实提供方/TLS 未验收）
- 日期：2026-09-25
- 关联：ADR-013/017/030、M1；不新增启动单元、数据库或模型 Tool。

## 背景

ADR-030 提供浏览器 OIDC BFF 与单次 Runtime 委托，但持续采集不能依赖浏览器 Cookie 或复制人的 dev token。现有 History Worker 已具备完整分页、VM 回读确认、PG checkpoint/租约/fencing，需要独立且有界的来源读取权限。

## 决策

新增默认关闭的机器入口 `/api/v1/service/ingestion/items/{itemId}/history`。Worker 通过固定提供方的 OAuth2 `client_credentials` 获取短期 JWT；平台按 RFC 9068 的固定 RS256/at+jwt profile 验证签名、issuer、单一 audience、scope、client_id、sub、jti 和期限。只信任运维授权文件中的租户、资源、时间和预算，不从 JWT 的 tenant/permissions claim 或 Worker 请求构造授权。

纯 Java `HistoryServiceGrant` 定义有效时间、历史数据窗口和数量边界，不依赖 Spring/JWT/JDBC；`IdentityGrant` 负责可信 issuer/sub 绑定。HTTP/JWKS/文件解析仅在平台适配层。契约唯一源为 `contracts/schemas/v1/history-service-grants.schema.json`；语义跨字段检查保留在领域层和文件解析器。文件每请求重新读取，损坏或不可读直接失败，不缓存旧授权。

机器路由通过独立过滤器先于 OIDC/dev 认证执行；关闭时也拒绝所有服务路由。Cookie、Origin、转发头、dev token、Runtime delegation 不能作为机器凭据。该 access token 不可调用普通用户路径、同步写入或 Tool。复用现有有权 History 用例，继续复核 entity/metric/source 和真实租户；原始 Zabbix 口令不会提供给 Worker。

令牌交换和 JWKS 网络请求有固定目标、完整正文时限、字节上限及禁止跳转。Worker 单调时钟缓存令牌，401 清除缓存但不隐式重试本次读取。失败让当前轮询失败；只有下一次已配置轮询才能重新交换。修正 Java HttpClient 仅靠请求 timeout 不能截断停滞正文的问题，采用可取消 future 的整体期限。

服务请求限定 4 并发，每 client 运维上限至多 120 次/分钟，单次至多 3600 秒/500 点，必须是过去窗口；整个授权有效期至多 90 天、可读数据范围至多 31 天。限流为进程内，不声称跨副本全局预算。普通模式要求直接 TLS；loopback 协议测试必须显式启用并绑定 loopback。没有自动提供方发现或 opaque token introspection。

Worker 的身份目标（平台地址、认证模式、token endpoint、client ID）进入原有系列 fingerprint，secret 不进入；同 client secret 轮换可续采。原有 dev fingerprint 保持兼容，dev 租户仍固定 tenant-demo。响应租户必须匹配 Worker 配置，否则拒绝写入。VM/PG 继续仅 loopback；本次不放开远程存储、跨主机 Runtime 或多流调度。

## 验收与限制

`HistoryServiceHttpIT` 覆盖实际 HTTP 的 JWT 负例、身份来源、混用、范围、撤销、文件故障、限流和并发；Worker 测试覆盖交换、缓存、401、provider 错误/跳转/超量/慢正文。`check_oidc_stack.mjs --history-service` 以独立 client 的协议 fixture 驱动真实 Worker/Java/PG/VM，检查撤销/故障不推进 cursor/revision、secret 轮换后续采，并与 OIDC 浏览器诊断/退出整链共存。

真实 IdP、TLS 部署和厂商来源仍待用户环境，M1/MVP 不关闭。授权文件需运维原子替换并控制文件权限；进程内会话/限流不提供 HA。详细已执行结果见验证报告第 37 节，操作配置见 [runbook](../runbooks/history-service-identity.md)。

参考：[RFC 6749 §4.4](https://www.rfc-editor.org/rfc/rfc6749.html#section-4.4)、[RFC 9068](https://www.rfc-editor.org/rfc/rfc9068.html)、[Spring Security JWT](https://docs.spring.io/spring-security/reference/7.0/servlet/oauth2/resource-server/jwt.html)。
