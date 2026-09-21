# ADR-012：控制台采用 Zeus + 原生 Zeus UI

状态：已采纳 · 日期：2026-09-21

## 决策

Web Console 使用 `@zeus-js/zeus` 组织页面与响应式状态，使用 `@zeus-web/ui` 的原生 Web Components 入口（`button` / `input`）作为基础控件。不引入 React/Vue 包装层，不把 Zeus UI 做成 OpsWeave 后端客户端。

Java 平台与 Rust Agent Runtime 边界不变。图表、拓扑、Agent Console 高级组件经 `src/adapters/` 接入，逐项验收后再加依赖。

## 固定版本

- `@zeus-js/zeus` `0.1.1-beta.2`
- `@zeus-js/vite-plugin` `0.0.4`（已发布产物）
- `@zeus-web/ui` `0.1.0-beta.4`

CI 安装这些版本的 registry 产物，不以本机 `workspace:` 链接代替。

## 代价与后续

JSX 编译语义与 React 不同，属性和自定义事件必须经适配层绑定。当前发布的 `@zeus-js/compiler` 0.1.0 会把 `{props.children}` 和 `array.map(...)` 编成文本绑定，页面结构必须用 DOM 子节点、`<Show>` 和 `<For>` 插入。`<For>` 的运行时传入 accessor，适配层用 `forItem` 读取实际项。`@zeus-web/chat` 与 `@zeus-web/agent-console` 尚未在本仓库联调，不得标为已接入。
