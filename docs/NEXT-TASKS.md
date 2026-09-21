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

## 本机已落地：OW-R04 切片（不是生产 IAM）

`Principal` / `TenantId` / `ResourceScope` / `Permission` / `authorize()` allow-deny 已有领域测试。`platform-api` 从 Bearer 构造 Principal，拒绝 query/header 覆盖 tenant。Dev adapter 显式开启；OIDC 占位拒绝 mock 回退。**不要把 Demo token 升级成生产认证，也不要继续做组织树/ABAC/SSO 后台。**

## 当前：Zabbix Host 链加深，而不是 Copilot

| 顺序 | ID | 工作 |
|---|---|---|
| 1 | OW-R07 真实端口 | 对可达的 Zabbix JSON-RPC 跑 `host.get`；失败不得回退 fixture，也不得当成空快照删资产 |
| 2 | OW-R06 | 把 Entity/Observation/ExternalLink 从标注内存存储迁到租户隔离持久化 |
| 3 | OW-R11 余量 | Preview/Replay/不可变 PipelineVersion；仍无画布、无 Copilot |
| 4 | 下一对象 | 只接 Zabbix Item → MetricDefinition / Metric，不要一次铺 Trigger/History/Template |

OW-R05（前端会话清理）和 OW-R08（资产页）可以跟在真实 Host 数据之后，不要先扩前端框架。

OW-R12 Integration Copilot **暂缓**：必须等 R11/R07 可运行，且只生成定义 diff。

## 已完成的 Bootstrap

锁文件、Gradle Wrapper、Rust 1.98.1、pnpm、Zeus 迁移与诊断页 E2E 见 `VALIDATION-REPORT.md` 第 5–10 节。身份与 Host 切片见第 11 节。

## 更后（M3 起）

外部告警、Incident；Java Tool Gateway 与 Rust 真实读取；可恢复 AIRun；配置式 Skill；受控试点。
