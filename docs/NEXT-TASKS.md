# 首轮研发任务清单

规划里程碑与 Issue 拆分见 [ROADMAP.md](ROADMAP.md)。本页只列**当前可启动**的工作，不把未验收能力写成已完成。

## 当前：M0（Zeus 迁移验收）

| ID | 状态 | 说明 |
|---|---|---|
| OW-R01 | 完成（本地） | 兼容性清单已写；去掉 `node_modules` 的工作树拷贝上 frozen 安装 / typecheck / build 已跑。不是干净 `git clone`，也未复跑 GitHub Actions |
| OW-R02 | 完成（本地） | Playwright Chromium：注册 WC、输入、提交、401/503、取消、卸载、证据按文本渲染。7 passed。拦截 `/agent`，不需要 Rust Demo |
| OW-R03 | 消费端已规避 | `@zeus-web/ui` JS 非 sideEffects、编译器相邻插值、`<For>` accessor 均在产品侧规避。未向上游提交最小复现 |

下一步是 M1 / OW-R04 身份契约（不依赖前端再迁一次）。中文 IME、完整键盘矩阵仍未测。

## 已完成的 Bootstrap

锁文件、Gradle Wrapper、Rust 1.98.1、pnpm workspace 与 Zeus 迁移提交见 `VALIDATION-REPORT.md` 第 5–7 节。不要再安排“从零替换 React”。

## 其后（M1 起，按路线图）

1. 平台身份：OIDC/BFF 会话、租户与资源范围、允许/拒绝矩阵。不要把 Demo token 升级成生产认证。
2. 一个来源的资产同步（默认 Zabbix Host），失败扫描不删除。
3. 指标、外部告警、Incident 工作台。
4. 真实只读诊断：Java Tool Gateway、Rust HTTP Port、AIInsight 持久化。
5. 可恢复 AIRun 与 Agent 控制台（先协议后 UI）。
6. 配置式 Skill 发布；受控试点。
