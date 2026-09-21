# 前端兼容性基线

日期 2026-09-21。记录 **OpsWeave Web Console 已经用过的发布产物组合**，不是 Zeus / Zeus UI 的最新版本承诺。

M0 前端迁移验收在提交 `b5404a817e832cfa13c9b52a005477a453ef031d` 关闭：本地 Playwright 7 passed，GitHub Actions `opsweave-template` 四 job 通过。中文 IME / 完整键盘矩阵仍未测，不阻塞 M1。

升级时：先在本清单写目标版本与原因 → 用锁文件安装 → typecheck / 生产构建 → 诊断页手工或 Playwright 回归 → 再提交 `pnpm-lock.yaml`。CI 不以本机 `link:` 或 `workspace:` 目录代替 registry 产物。

## 已验证组合

| 项 | 版本 | 来源 | lockfile integrity（sha512） |
|---|---|---|---|
| `@zeus-js/zeus` | `0.1.1-beta.2` | npm registry | `3A63TzcL1Gvi4tKw017hY8p08B/uqsQeuxYkibK0scUEeXkByQTBwsdvVMOl6ZAnrGlx5yCqHDLfsCcTpUcqvg==` |
| `@zeus-js/vite-plugin` | `0.0.4` | npm registry | `OY6cCK+hV9AMloSxejdjExmRzLUrvkUv5FAO7T2sHZZ5XAGOVDcolNbu4niiL4pWHx6nHAg66rgpgzcMyNhVjw==` |
| `@zeus-js/compiler` | `0.1.0` | 由 vite-plugin 拉取 | `3jDH3+zu+1mywjvSNsr6td1o0P9aNP4BgK/+Fr6nZs27YIbvNJ7yGFW5vE6MyBRXJaKYr/CCcF+UvViLT+7y/g==` |
| `@zeus-web/ui` | `0.1.0-beta.4` | npm registry；CSS `@zeus-web/ui/button.css`、`input.css` | `nbLl3riisA40FgqXEJcPEaVvIZTBBxS0Mk4Ib9L6hyCWdCme7bX08/MaNGusqwPXQ8Z1qmyTbj795Rm5LQk81Q==` |
| `@zeus-web/button` | `0.1.0-beta.4` | npm；`wc/auto` 负责 `customElements.define` | `ProxOyDP7ZzpoWzArPd2djdXnCx3wtiYrAFV5Qc76NG+JQLKXLEVnPTwj94B8Z1WoQSCckXlBYN0U/vmetGuvQ==` |
| `@zeus-web/input` | `0.1.0-beta.4` | npm；`wc/auto` 负责 `customElements.define` | `c5uYmm+PrRoRdTxMKnJJhlr6Ti1enhhstiAt7SKtCVV96yJ+D5wK5LyuesJ3Eu61thU6HAesMKu/91GVQLb9uw==` |
| `@playwright/test` | `1.55.1` | npm；Chromium 需 `playwright install`（`ignore-scripts`） | `IVAh/nOJaw6W9g+RJVlIQJ6gSiER+ae6mKQ5CX1bERzQgbC1VSeBlwdvczT7pxb0GWiyrxH4TGKbMfDb4Sq/ig==` |
| TypeScript | `5.9.3` | lock 解析 | 见 `pnpm-lock.yaml` |
| Vite | `8.3.0` | lock 解析 | 见 `pnpm-lock.yaml` |
| pnpm | `10.34.3` | `packageManager` | 不随前端包 integrity 记录 |
| Node（本地验证） | `v26.9.0` | 开发机 | 满足 vite-plugin `^22.18.0 \|\| >=24.11.0` |
| Node（CI 声明） | `22.18` | `.github/workflows/ci.yml` | `b5404a8` 的 web job 已用该版本跑通 typecheck / build / Playwright |

验证提交：`b5404a817e832cfa13c9b52a005477a453ef031d`（诊断页 E2E + `wc/auto` 注册）。前置 Zeus 迁移为 `e5b5827e02b8b53a5e945635596184561d43f696`。GitHub Actions：https://github.com/baicie/ops-weave/actions/runs/35600475743 （contracts / rust / java / web 均为 success）。本地步骤见 `VALIDATION-REPORT.md` 第 7–10 节。

命令（仓库根目录）：

```bash
pnpm install --frozen-lockfile
pnpm typecheck:web
pnpm build:web
pnpm --filter @opsweave/web-console exec playwright install chromium
pnpm test:web
```

生产构建产物约 `58.72 kB` JS / gzip `20.55 kB`（另有 `zw-button` / `zw-input` 懒加载 chunk），CSS `7.61 kB`。这不是性能基线。`@zeus-web/ui` 的 JS 入口未列入 package `sideEffects`，控制台从 `@zeus-web/button/wc/auto` 与 `@zeus-web/input/wc/auto` 注册原生组件。

## 产品直接依赖中的 React

`apps/web-console/package.json` 与 `src/**/*.ts(x)` **没有** `react` / `react-dom` / `@vitejs/plugin-react`。

`pnpm why react` 显示 `react@19.3.0` 来自 `@zeus-web/button` / `@zeus-web/input` 的可选 peer，以及它们依赖的 `@zeus-js/output-react-wrapper`。控制台运行时 import `@zeus-web/button/wc/auto`、`@zeus-web/input/wc/auto` 与 `@zeus-web/ui` 的 CSS。**lock 里出现 React ≠ 产品运行 React。**

未验证：去掉可选 React peer 后 Zeus UI 的 WC 入口是否仍能安装。当前不把 `auto-install-peers=false` 当作已验收配置。

## 编译器与运行时必须遵守的用法

发布的 `@zeus-js/compiler` `0.1.0` 在本仓库中观察到：

| 写法 | 实际编译结果 | OpsWeave 用法 |
|---|---|---|
| `{props.children}` | `_bindText`，把子树展成文本 | 布局不用 children 插槽传页面 |
| `array.map(() => <el/>)` | `_bindText` | 用 `<For>` |
| `<For>` 回调块 `{row => { ... return <el/> }}` | 回调被编成 `() => []` | 回调只返回单个 JSX 组件 |
| `<For>` 运行时 | `mountFor` 把 item 作为 accessor 传入 | `forItem()` 解出实际值后再交给子组件 |
| 相邻 `{a} / {b}` 文本插值 | 分隔符丢失或错位 | 拼成单个模板字符串再绑定 |
| `@zeus-web/ui/{button,input}` JS | 生产 treeshake 丢掉 `customElements.define` | 改 import 已标记 sideEffects 的 `wc/auto` |

自定义事件 `press` / `value-change` 经 `addEventListener` 绑定，不依赖 JSX `onPress` 名称映射。大型数据不走 HTML attribute。决策见 ADR-012。

## 未纳入本基线

- `@zeus-web/chat`、`@zeus-web/agent-console`、data-grid
- 本机 `link:` / `workspace:` Zeus 源码
- 中文输入法组合输入、完整键盘/焦点矩阵
- 干净 `git clone`（本地基线是去掉 `node_modules` 的工作树拷贝；CI checkout 已在 ubuntu 上跑通）

## 升级记录

| 日期 | 提交 | 变更 | 结果 |
|---|---|---|---|
| 2026-09-21 | `e5b5827` | React → Zeus 0.1.1-beta.2 + Zeus UI 0.1.0-beta.4 | 本地 typecheck/build 与诊断 Demo 手工路径通过；Actions run 35594155351 success |
| 2026-09-21 | `b5404a8` | Playwright 诊断页；`wc/auto` 注册 | 本地 7 passed；Actions run 35600475743：contracts/rust/java/web success |
