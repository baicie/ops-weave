# 依赖与升级基线

这是初始化选择，不是“所有版本已兼容测试”的声明。核对日期2026-09-21；真实依赖解析和编译见验证报告。

| 部分 | 声明 | 说明 |
|---|---|---|
| Java | 21 | 沿用平台后端 |
| Spring Boot | 4.0.8 | 固定候选基线，不宣称最新版 |
| Gradle | 8.14.3 | 首次生成并验证Wrapper |
| Rust | 1.98.1（48a229cea 2026-09-01） | 2026-09-21 本地初始化后固定；Docker 生产前再锁镜像 digest |
| Tokio | 1.x | 异步I/O，Cargo.lock固定实际版本 |
| Axum | =0.8.9 | HTTP入口 |
| Rig | =0.42.0，optional | 使用root `rig` facade，features agent/rustls |
| MCP | rmcp =3.4.0，optional | 独立使用，不调用Rig的旧rmcp类型接口 |
| jsonschema | =0.56.0 | 默认网络解析feature关闭，另禁止外部ref |
| React | 已移除 | 控制台改为 Zeus，见 ADR-012 |
| Zeus | 0.1.1-beta.2 | `@zeus-js/zeus`；Vite 插件 `0.0.4`；已验证组合见 `FRONTEND-COMPATIBILITY.md` |
| Zeus UI | 0.1.0-beta.4 | 样式来自 `@zeus-web/ui` CSS；注册用 `@zeus-web/button` / `@zeus-web/input` 的 `wc/auto` |
| Vite | ^8.0.0 | Node22.12+；包管理 pnpm 10.34.3 |
| PG | 17开发镜像 | 生产固定patch/digest，独立应用账号 |

Rig0.42 root facade重导出core和agent；不要误用旧教程`rig-core`重命名后期望获得全部runtime接口。当前适配器只依赖ModelPort；领域层不泄漏Rig类型。`default_max_turns(1)`按固定版本API文档表示总模型调用预算，包含首次调用。模型用量/费用计费仍须补充集成测试。

官方rmcp3.4与Rig可选rmcp集成的依赖版本可能不同，所以本模板不打开Rig的rmcp feature，也不跨版本传递rmcp类型。独立Probe不能证明生产MCP授权已完成。

`Cargo.lock`、`pnpm-lock.yaml`、Gradle lockfile和Wrapper在首次可信环境真正生成，再提交；本交付无法联网取完整依赖，未捏造内容。CI保留缺失门禁。前端包管理使用 pnpm 10.34，不混用 npm lock。

Python仅承担开发检查；不提供Python Agent、FastAPI或LangGraph运行服务。未来SQLx、reqwest和算法库在实现真实端口时按需求加入，不以空依赖堆砌“完成度”。

来源：设计文档S1—S8。每次升级运行默认/全部feature编译、Schema契约、权限负例、上下文历史时点、真实provider集成、取消/超时测试；补ADR与版本清单。
