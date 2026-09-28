# 前端兼容性基线

日期 2026-09-28。当前控制台运行时是 React 19 + shadcn/ui。2026-09-21 的 Zeus 组合已不再使用，记录留在文末升级历史。

升级时：先在本清单写目标版本与原因 → 用锁文件安装 → typecheck / 生产构建 → 再跑受影响的 Playwright。CI 不以本机 `link:` 或 `workspace:` 目录代替 registry 产物。

## 当前组合

版本以 `apps/web-console/package.json` 的固定版本和根目录 `pnpm-lock.yaml` 为准。2026-09-28 本机 `pnpm install` 后，`tsc --noEmit` 与 `vite build` 通过。内置浏览器抽查了开始页、资产会话、主题、搜索、数据源失败提示、关系模型空状态和 390px 导航抽屉。Playwright 全量用例本轮未跑。

| 项 | 版本 |
|---|---|
| `react` / `react-dom` | 19.3.0 |
| `@types/react` / `@types/react-dom` | 19.3.0 |
| `vite` | 8.3.1 |
| `@vitejs/plugin-react` | 6.1.1 |
| `typescript` | 7.0.2 |
| `tailwindcss` / `@tailwindcss/vite` | 4.3.3 |
| `shadcn` | 4.21.0 |
| `radix-ui` | 1.6.7 |
| `@playwright/test` | 1.63.0 |
| pnpm | 10.34.3 |

组件源码在 `src/components/ui`。`select`、`checkbox`、`dialog` 保持原生元素。X6 与 G6 仍按需动态加载。

命令（仓库根目录）：

```bash
pnpm install --frozen-lockfile
pnpm typecheck:web
pnpm build:web
pnpm --filter @opsweave/web-console exec playwright install chromium
pnpm test:web
```

## 历史：Zeus 组合（已退出）

日期 2026-09-21。下面是当时用过的发布产物，不是当前运行时。

| 项 | 版本 | 来源 | lockfile integrity（sha512） |
|---|---|---|---|
| `@zeus-js/zeus` | `0.1.1-beta.2` | npm registry | `3A63TzcL1Gvi4tKw017hY8p08B/uqsQeuxYkibK0scUEeXkByQTBwsdvVMOl6ZAnrGlx5yCqHDLfsCcTpUcqvg==` |
| `@zeus-js/vite-plugin` | `0.0.4` | npm registry | `OY6cCK+hV9AMloSxejdjExmRzLUrvkUv5FAO7T2sHZZ5XAGOVDcolNbu4niiL4pWHx6nHAg66rgpgzcMyNhVjw==` |
| `@zeus-js/compiler` | `0.1.0` | 由 vite-plugin 拉取 | `3jDH3+zu+1mywjvSNsr6td1o0P9aNP4BgK/+Fr6nZs27YIbvNJ7yGFW5vE6MyBRXJaKYr/CCcF+UvViLT+7y/g==` |
| `@zeus-web/ui` | `0.1.0-beta.4` | npm registry | `nbLl3riisA40FgqXEJcPEaVvIZTBBxS0Mk4Ib9L6hyCWdCme7bX08/MaNGusqwPXQ8Z1qmyTbj795Rm5LQk81Q==` |
| `@zeus-web/button` | `0.1.0-beta.4` | npm | `ProxOyDP7ZzpoWzArPd2djdXnCx3wtiYrAFV5Qc76NG+JQLKXLEVnPTwj94B8Z1WoQSCckXlBYN0U/vmetGuvQ==` |
| `@zeus-web/input` | `0.1.0-beta.4` | npm | `c5uYmm+PrRoRdTxMKnJJhlr6Ti1enhhstiAt7SKtCVV96yJ+D5wK5LyuesJ3Eu61thU6HAesMKu/91GVQLb9uw==` |

当时的编译器限制（`children`、`array.map`、`<For>` accessor）只解释旧源码，不适用于现在的 React JSX。

## 未纳入本基线

- `@zeus-web/chat`、`@zeus-web/agent-console`、data-grid
- 生产 OIDC/BFF
- 中文输入法组合输入、完整键盘/焦点矩阵
- 本轮 Playwright 全量回归

## 升级记录

| 日期 | 提交 | 变更 | 结果 |
|---|---|---|---|
| 2026-09-21 | `e5b5827` | React → Zeus 0.1.1-beta.2 + Zeus UI 0.1.0-beta.4 | 本地 typecheck/build 与诊断 Demo 手工路径通过；Actions run 35594155351 success |
| 2026-09-21 | `b5404a8` | Playwright 诊断页；`wc/auto` 注册 | 本地 7 passed；Actions run 35600475743：contracts/rust/java/web success |
| 2026-09-28 | 工作树，未提交 | Zeus → React 19.3.0 + shadcn/ui 4.21.0 | 本机 `tsc --noEmit` 与 Vite 8.3.1 生产构建通过；内置浏览器抽查通过；Playwright 未跑 |
