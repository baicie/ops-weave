# ADR-011: Rust-native Agent Runtime
Status: accepted design decision, implementation starter

Java owns platform domains and AI control-plane configuration. Rust owns Agent execution,
context selection, evidence checks and Tool dispatch. TypeScript owns the console. Python
is not a deployed dependency; the optional repository validation scripts use it only during development.

Use Rig via a small adapter. The pinned 0.42 facade differs from older rig-core imports.
Do not leak Rig, rmcp, Axum or SQLx types into domain contracts. Native platform Tools go
through the platform's authenticated APIs; MCP is an adapter, not the service transport standard.

The starter implements a bounded read-only workflow, not a general DAG interpreter. The
optional model adapter is a single Rig model step without autonomous actions. Durable
RunStore, lease recovery and generic multi-turn planning must pass dedicated tests before enablement.
