# 实现状态 · v4

日期2026-09-21。**“源码已提供”不等于“编译/集成已通过”。**验证范围以VALIDATION-REPORT为准。

## 当前源码

| 部分 | 已提供 | 未提供 |
|---|---|---|
| Java平台 | 多项目、纯领域值对象、Connector SPI、默认拒绝的Spring组装 | 真实业务HTTP/持久化/OIDC/Connector |
| Rust Runtime | Axum接口、本机身份、固定只读流程、共享预算、并行fixture、Context/Evidence、版本化Skill加载、Schema输出验证 | 持久RunStore/租约/恢复/取消API/生产鉴权 |
| Rig | optional适配器、显式模型出网、单次model步骤 | 真实调用测试、多provider/原生strict output/费用采集 |
| MCP | optional官方SDK本机Probe源码 | Agent内动态调用、OAuth、生产server准入 |
| Tool | 3个正式只读契约、2个fixture端口实现 | Java真实ToolGateway与Rust HTTPPort |
| Skill | 可加载JSON+Prompt+Schema，内容digest，预算与模板限制 | UI创建、测试集发布、签名、热更新、多模板DAG |
| Web | 诊断表单（Zeus + Zeus UI 原生组件）、开发代理、摘要/引用/缺失数据展示 | 生产BFF/OIDC、资产/指标/Skill/Agent Console 完整 UI |
| 存储/运维 | SQL原型、Docker/Compose、CI门禁 | 已部署的PG/VM/日志/RAG/Kafka、生产Helm/HA/备份验证 |

## 已实际检查

交付包阶段：Python契约/样例测试、纯Java领域编译与smoke、结构/架构静态检查。

2026-09-21 本地初始化追加：生成并审查锁文件与 Gradle Wrapper；`cargo fmt --all`；25 项 Rust 测试通过；`--all-features` 的 `cargo check` / `clippy -D warnings` 通过；随后将前端改为 pnpm 并完成 frozen 安装与 Vite 生产构建；`:apps:platform-api:bootJar` 与 `:apps:ingestion-worker:bootJar` 通过。同日将 Web Console 从 React 迁到 Zeus + Zeus UI 原生组件。路线图、兼容性清单、无 node_modules 拷贝安装与诊断页 Playwright 见 `VALIDATION-REPORT.md` 第 5–9 节。

## 写了测试但交付包当时未执行

Rust `runtime_tests.rs` 中的测试检查租户/incident范围、时间窗口、历史asOf、过期访问、证据伪造、并行预算、Skill输出和HTTP边界。ZIP 交付环境没有 Cargo/rustc。本地初始化已执行这些测试并编译默认与可选 feature；**仍未做真实模型调用、MCP 联调、Docker 镜像 digest 或生产鉴权**。

## 下一步优先级

下一步按 `docs/ROADMAP.md`：M0 的本地安装与诊断页 Playwright 已跑通；未复跑 GitHub Actions，也未测 IME。接着做 M1 身份与统一请求层。不要跳过授权去接真实 Zabbix，也不要把 chat/agent-console 目录当成已接入。

当前`RunState`枚举不是持久化执行引擎；同步诊断遇到进程退出会中断。Mock输出不是AI；引用校验不是事实/因果验证；前端模块卡片不是已实现模块。
