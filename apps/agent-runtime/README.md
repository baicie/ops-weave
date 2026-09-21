# Rust Agent Runtime

Rust owns request validation, bounded parallel Tool calls, Context construction, reference
checks, Skill package loading and optional Rig inference. There is no Python runtime.

Default `closed` mode exposes liveness only and refuses business requests. Explicit local
`demo` mode accepts a generated developer token and uses synthetic fixture data. Even when
Rig is enabled, the input remains synthetic. This is not a production API/authentication layer.

From the repository root: `cargo run -p opsweave-agent-runtime`. See
`docs/architecture/repository-guide.md` for demo setup and optional provider commands.

No RunStore, job persistence, checkpoint resume, OIDC, real CMDB/Zabbix query adapter or
automated action is represented as implemented. The API is synchronous and does not return
an accepted background-job ID. Container images default to closed mode. Rust compilation
was not available in the delivery environment; run the supplied cargo checks locally.
