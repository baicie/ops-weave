# 实现状态 · v4

日期2026-09-22。**“源码已提供”不等于“编译/集成已通过”。**验证范围以VALIDATION-REPORT为准。

## 当前源码

| 部分 | 已提供 | 未提供 |
|---|---|---|
| Java平台 | 多项目、Principal/授权、Zabbix Host Pipeline、内存库存、受保护的 Entity/同步 HTTP | PostgreSQL 持久化、生产 OIDC、Item/Metric/Incident、可视化编辑器 |
| Rust Runtime | Axum接口、本机身份、固定只读流程、共享预算、并行fixture、Context/Evidence、版本化Skill加载、Schema输出验证 | 持久RunStore/租约/恢复/取消API/生产鉴权 |
| Rig | optional适配器、显式模型出网、单次model步骤 | 真实调用测试、多provider/原生strict output/费用采集 |
| MCP | optional官方SDK本机Probe源码 | Agent内动态调用、OAuth、生产server准入 |
| Tool | 3个正式只读契约、2个fixture端口实现 | Java真实ToolGateway与Rust HTTPPort |
| Skill | 可加载JSON+Prompt+Schema，内容digest，预算与模板限制 | UI创建、测试集发布、签名、热更新、多模板DAG |
| Web | 诊断表单（Zeus + Zeus UI 原生组件）、开发代理、摘要/引用/缺失数据展示 | 生产BFF/OIDC、资产/指标/Skill/Agent Console 完整 UI |
| 存储/运维 | SQL原型、Docker/Compose、CI门禁 | 已部署的PG/VM/日志/RAG/Kafka、生产Helm/HA/备份验证 |

## 已实际检查

交付包阶段：Python契约/样例测试、纯Java领域编译与smoke、结构/架构静态检查。

2026-09-22 追加：identity 领域 allow/deny、Dev Principal Filter、Zabbix Host 确定性流水线与内存 Entity。`python3 scripts/check_java_domain.py` 三个 smoke 通过；`./gradlew :apps:platform-api:test :apps:platform-api:bootJar :apps:ingestion-worker:bootJar` 10 tests / 0 failures。未联调厂商 Zabbix，未接 Keycloak。详见 `VALIDATION-REPORT.md` 第 11 节。

## 写了测试但交付包当时未执行

Rust `runtime_tests.rs` 中的测试检查租户/incident范围、时间窗口、历史asOf、过期访问、证据伪造、并行预算、Skill输出和HTTP边界。ZIP 交付环境没有 Cargo/rustc。本地初始化已执行这些测试并编译默认与可选 feature；**仍未做真实模型调用、MCP 联调、Docker 镜像 digest 或生产鉴权**。

## 下一步优先级

下一步按 `docs/ROADMAP.md`：M0 已关闭。OW-R04 本机 Principal/授权已落地，不要继续做深 IAM。接着不要做 Copilot：需要真实 Zabbix JSON-RPC（有 URL/密钥时）、Entity 持久化，以及 Item/Alarm，而不是再扩前端。也不要把 chat/agent-console 目录当成已接入。

当前`RunState`枚举不是持久化执行引擎；同步诊断遇到进程退出会中断。Mock输出不是AI；引用校验不是事实/因果验证；前端模块卡片不是已实现模块。
