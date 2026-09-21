# Web Console

Zeus + TypeScript + Vite。使用 Zeus UI 原生 Web Components（`zw-button` / `zw-input`），不经过 React/Vue 包装。
开发服务器把 `/agent` 代理到本机 Rust runtime。诊断 Token 只保存在页面内存，不写 `VITE_*`，不写 localStorage。模型文本按文本渲染，不作为 HTML 执行。

`pnpm install --lockfile-only` 在仓库根目录生成 `pnpm-lock.yaml`；审查后提交。
随后使用 `pnpm install --frozen-lockfile`、`pnpm typecheck:web`、`pnpm build:web`。

当前发布编译器会把 `{props.children}` 和 `Array.map` 编成文本节点，列表项经 `forItem` 读取。布局不要用 children 插槽传递页面。

Nginx 静态镜像不会自动代理本机 Demo Runtime。生产反向代理/BFF 只能在 OIDC 与授权实现后配置。
本机交互演示：`pnpm dev:web`，默认打开 Incident 诊断页。需要同时运行 `bash scripts/demo.sh`。
