# Web Console

React 19 + shadcn/ui + TypeScript + Vite。基础控件在 `src/components/ui`，样式使用 Tailwind CSS 4 与现有页面布局。
开发服务器把 `/agent` 代理到本机 Rust runtime。诊断 Token 只保存在页面内存，不写 `VITE_*`，不写 localStorage。模型文本按文本渲染，不作为 HTML 执行。

`pnpm install` 在仓库根目录更新 `pnpm-lock.yaml`；审查后提交。
随后使用 `pnpm install --frozen-lockfile`、`pnpm typecheck:web`、`pnpm build:web`。
诊断页浏览器用例：`pnpm test:web`（Playwright Chromium；因 `ignore-scripts` 需先 `pnpm --filter @opsweave/web-console exec playwright install chromium`）。用例拦截 `/agent`，不要求本机 Rust Demo。

原生 `select`、`checkbox` 和 `dialog` 保留现有可访问名称。Agent 控制台尚未接入。

Nginx 静态镜像不会自动代理本机 Demo Runtime。生产反向代理/BFF 只能在 OIDC 与授权实现后配置。
本机交互演示：`pnpm dev:web`，默认打开开始使用页。需要同时运行 `bash scripts/demo.sh`。
