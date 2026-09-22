# 实现状态 · v4

日期2026-09-22。**“源码已提供”不等于“编译/集成已通过”。**验证范围以VALIDATION-REPORT为准。

## 当前源码

| 部分 | 已提供 | 未提供 |
|---|---|---|
| Java平台 | Principal/授权、Zabbix Host 分页同步、Item → MetricDefinition、PostgreSQL 库存、受保护的 Entity/指标定义 API | 生产 OIDC、厂商 Zabbix 联调、History/MetricPoint、Pipeline Preview |
| Web | 诊断表单；资产页读取平台 Entity API（开发 Token，内存持有） | 生产 BFF/OIDC、指标/Skill/Agent Console |
| 存储/运维 | Host 同步迁移 `V002__host_sync.sql`、指标定义 `V003__metric_definition.sql`、Compose 中的 PostgreSQL | 行级安全、对象存储、时序库、生产 Helm/HA/备份验证 |
| Rust Runtime | Axum接口、本机身份、固定只读流程、共享预算、并行fixture、Context/Evidence、版本化Skill加载、Schema输出验证 | 持久RunStore/租约/恢复/取消API/生产鉴权 |
| Rig | optional适配器、显式模型出网、单次model步骤 | 真实调用测试、多provider/原生strict output/费用采集 |
| MCP | optional官方SDK本机Probe源码 | Agent内动态调用、OAuth、生产server准入 |
| Tool | 3个正式只读契约、2个fixture端口实现 | Java真实ToolGateway与Rust HTTPPort |
| Skill | 可加载JSON+Prompt+Schema，内容digest，预算与模板限制 | UI创建、测试集发布、签名、热更新、多模板DAG |

## 已实际检查

交付包阶段：Python契约/样例测试、纯Java领域编译与smoke、结构/架构静态检查。

2026-09-22：identity allow/deny 与 Zabbix Host 链见 `VALIDATION-REPORT.md` 第 11 节。同日追加分页 SyncRun 与 PostgreSQL：`PostgresHostSyncIT` 在本机库 `opsweave_host_sync` 通过；资产页 Playwright 库存用例通过。同步失败码与 `host.get` offset 游标见第 13 节。Host presence、offset 扫描语义和 Zabbix Item → MetricDefinition 见第 14 节。未联调厂商 Zabbix，未接 Keycloak。未存 History 点。

## 写了测试但交付包当时未执行

Rust `runtime_tests.rs` 中的测试检查租户/incident范围、时间窗口、历史asOf、过期访问、证据伪造、并行预算、Skill输出和HTTP边界。ZIP 交付环境没有 Cargo/rustc。本地初始化已执行这些测试并编译默认与可选 feature；**仍未做真实模型调用、MCP 联调、Docker 镜像 digest 或生产鉴权**。

## 下一步优先级

下一步不要加深 IAM，也不要做 Copilot。Host 同步已能分页并写入 PostgreSQL。接着对可达的 Zabbix 跑完整 `host.get`，然后只接 Item → MetricDefinition。Pipeline 下一刀是 Preview/Replay，不是画布。

当前`RunState`枚举不是持久化执行引擎；同步诊断遇到进程退出会中断。Mock输出不是AI；引用校验不是事实/因果验证；前端模块卡片不是已实现模块。
