---
name: incident-diagnosis
description: 使用受控只读上下文生成带证据的故障诊断；模板默认只使用合成演示数据。
---

# 故障诊断

Runtime loads `skill.json`, `prompt.md`, `output.schema.json` at process startup.
Changing these files does not require Rust recompilation, but the process must restart.
The manifest is a server-managed, versioned package, NOT a grant of user permissions.
The UI management/publish service and dynamic package refresh are future tasks.
