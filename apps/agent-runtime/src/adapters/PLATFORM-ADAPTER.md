# Real Platform adapter — next integration task

`FixturePlatform` is the only implemented `PlatformReadPort` in this template. It is never
silently used when a real datasource fails. Implement `HttpPlatformReadPort` here after
platform-api provides OIDC + resource-scoped Tool APIs. Suggested client: reqwest.

The credential must be a short-lived, audience-bound delegated credential constructed by
trusted authentication code. Never deserialize Principal/tenantId from tool arguments and
never use one all-tenant administrator token for every user. Validate the destination at
configuration time, disallow redirects, bound response bytes while reading the stream,
apply timeout, verify returned tenant/resource/time scope, and map errors without exposing
upstream response bodies. HTTP schema alone is not authorization.

MCP remains a separate adapter. Rig's own rmcp feature is NOT enabled: its SDK dependency
may differ from the separately pinned rmcp. Convert through OpsWeave DTOs; do not pass
third-party SDK types across this boundary. MCP tool metadata is not trusted policy.
