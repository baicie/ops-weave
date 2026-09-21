# 首轮研发任务清单

规划里程碑与 Issue 拆分见 [ROADMAP.md](ROADMAP.md)。本页只列**当前可启动**的工作，不把未验收能力写成已完成。

## 已关闭：M0（Zeus 迁移验收）

提交 `b5404a8`。不要再安排“从零替换 React”或继续扩前端框架面。

| ID | 状态 | 说明 |
|---|---|---|
| OW-R01 | 完成 | 兼容性清单；无 `node_modules` 拷贝 frozen 安装；GitHub Actions web job 通过 |
| OW-R02 | 完成 | Playwright 诊断页 7 passed；CI 含 `pnpm test:web` |
| OW-R03 | 消费端已规避 | `wc/auto` 保留 `customElements.define`；上游最小复现不阻塞 M1 |

残留：中文 IME / 完整键盘矩阵未测。chat / agent-console / data-grid 未接入。

## 当前：M1 身份，然后 M2 确定性流水线

按这个顺序推进，不要先做 Copilot：

| 顺序 | ID | 工作 |
|---|---|---|
| 1 | OW-R04 | 已验证主体、租户、资源范围、允许/拒绝矩阵。不要把 Demo token 升级成生产认证 |
| 2 | OW-R05 | 统一 API 生命周期与会话清理 |
| 3 | OW-R11 | `PipelineDefinition` / RawRecord / 节点 Catalog / 不可变版本。尚无 AI |
| 4 | OW-R06 + OW-R07 | Entity/Observation 持久化；Zabbix Host → Raw → Mapping → Observation → Entity |

OW-R12 Integration Copilot **暂缓**：必须等 R11/R07 可运行，且只生成定义 diff。

## 已完成的 Bootstrap

锁文件、Gradle Wrapper、Rust 1.98.1、pnpm、Zeus 迁移与诊断页 E2E 见 `VALIDATION-REPORT.md` 第 5–10 节。

## 更后（M3 起）

指标、外部告警、Incident；Java Tool Gateway 与 Rust 真实读取；可恢复 AIRun；配置式 Skill；受控试点。
