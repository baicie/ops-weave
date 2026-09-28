# Rust Agent Runtime

Rust owns request validation, bounded Tool reads, Context construction, evidence checks, fixed Skill loading and optional Rig inference. There is no Python runtime or action tool.

Default `closed` exposes liveness and refuses business requests. Explicit loopback `demo` uses labeled fixtures and the deterministic mock. `platform-dev` reads authorized Java APIs, reserves model spend, runs one model step, rechecks evidence and saves AIInsight through Java. The result is platform-owned PostgreSQL data; Runtime still has no durable RunStore, job recovery or cancellation API.

`OPSWEAVE_PROVIDER=rig-openai` requires the `rig-provider` feature, explicit model egress and matching Java spend policy. `OPSWEAVE_MODEL_API=responses` is the default; `chat-completions` selects the tested OpenCode Go/DeepSeek profile. This adapter identifier describes protocol compatibility, not the hosting vendor. No model/protocol fallback is performed. See [model configuration](../../docs/runbooks/model-spend.md) and [ADR-053](../../docs/adr/053-explicit-model-chat-protocol.md).

Local Zabbix 7.0.27 → Java/VM → Runtime → OpenCode Go `deepseek-v4.1-flash` → persistent AIInsight/evidence has a 7/7 candidate report (§65). This remains a local test environment with fixed development identity; it is not production identity or milestone sign-off. Historical fixture checks remain explicitly labeled.

## 当前诊断评估

运行 `node scripts/check_diagnosis_evals.mjs --all-features` 生成固定语料的流程检查和确定性摘要审阅材料。10个合成案例通过实际current工作流，读取/费用/保存为内存fixture、外部模型调用为0；它不等于本次真实模型候选链，也不自动签署人工审阅或M4。见[评估说明](../../docs/runbooks/current-diagnosis-evaluation.md)。
