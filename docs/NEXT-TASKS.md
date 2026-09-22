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

## 当前：Host 链已经能分页落库，下一刀仍不是 Copilot

| 顺序 | ID | 工作 |
|---|---|---|
| 1 | 真实 `host.get` | 对可达 Zabbix 跑完整分页；失败仍不得回退 fixture，也不得对账 |
| 2 | History → VictoriaMetrics | 已有 clock/ns 有界读取接口与数值换算；下一步是 Worker 批写、毫秒精度/同毫秒冲突策略、迟到点重叠去重、成功写入后推进持久 checkpoint。采样点不进 PostgreSQL |
| 3 | OW-R11 | PipelineVersion、Preview、Replay。仍无画布、无 Copilot |

Host 同步把来源 presence 和映射成功分开。完成态标记为 `offset-scan-attempt`。失败响应带回已扫描页数。`extensions/mappings/zabbix-cpu-user.yaml` 把 `system.cpu.util[,user]` 映射为 `host.cpu.usage.user`；两台主机共用一条目录记录。本机没有配置 Zabbix URL，厂商全量还没跑。OIDC、组织树、ABAC 继续不做。

OW-R12 Integration Copilot **暂缓**。

2026-09-22 增量：History 读取已通过本地协议桩、HTTP、权限/资源范围与分页负例测试。接口明确未持久化，同秒达到探测上限即失败，不跳过采样点。详见 [ADR-016](adr/016-bounded-history-read.md) 和验证报告第 16 节。当前仍无厂商 Zabbix URL。

## 已完成的 Bootstrap

锁文件、Gradle Wrapper、Rust 1.98.1、pnpm、Zeus 迁移与诊断页 E2E 见 `VALIDATION-REPORT.md` 第 5–10 节。身份与 Host 切片见第 11 节。分页同步与 PostgreSQL 见第 12 节。同步错误码与 `host.get` 游标见第 13 节。Host presence 与 Item 指标见第 14 节。目录/绑定拆分见第 15 节。

## 更后（M3 起）

外部告警、Incident；Java Tool Gateway 与 Rust 真实读取；可恢复 AIRun；配置式 Skill；受控试点。
