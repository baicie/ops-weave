# ADR-059：控制台改为 React 与 shadcn/ui

日期：2026-09-28。状态：已实现。替代 ADR-012。

Web Console 使用 React 19 与 shadcn/ui（Tailwind CSS 4）组织页面和基础控件。不再使用 `@zeus-js/zeus`、`@zeus-js/vite-plugin` 或 `@zeus-web/*`。Java 平台、Rust Agent Runtime 与四个启动单元不变。

## 依赖

直接依赖固定在 `apps/web-console/package.json`，由根目录 `pnpm-lock.yaml` 锁定。本轮选用当时 registry 的最新稳定版，包括 React 19.3.0、React DOM 19.3.0、Vite 8.3.1、`@vitejs/plugin-react` 6.1.1、TypeScript 7.0.2、Tailwind CSS 4.3.3、`shadcn` 4.21.0、`radix-ui` 1.6.7。类型来自 `@types/react` 与 `@types/react-dom` 19.3.0，因为 React 19.3.0 发布包本身不再附带声明文件。

shadcn 组件源码放在 `src/components/ui`，不从 CDN 运行时拉取。按钮、输入和多行文本使用这些组件；原生 `select`、`checkbox` 和 `dialog` 保留，以维持现有键盘和 Playwright 选择器。

## 边界

身份、租户和开发凭据仍只存在于页面内存或既有浏览器会话，不写入 `VITE_*` 或 localStorage。主题偏好仍可写入 `opsweave.ui.theme`。模型文本继续按文本渲染。

AntV X6 / G6 仍由适配层按需加载，节点保持引擎自己的 DOM，不改用 React 节点包装，也不把图 JSON 当作持久协议。ADR-058 的查询与导航边界不变。

Agent 控制台、chat 组件和生产 OIDC/BFF 仍未接入。

## 2026-10-01 运维管理界面

按用户指定的 [shadcn-admin](https://github.com/satnaing/shadcn-admin) 参考其后台布局、分组侧栏、卡片和标签页结构。沿用本仓库 React、shadcn/ui、hash 路由和可信会话适配，不引入模板的示例用户、营收数据、Clerk 或 TanStack Router。新增本地 Card 与 Radix Tabs 组合，统一亮暗主题变量和业务页面容器。

开始页改为运维工作台，显式查询既有有界 GET API，所有统计仅代表当前查询页。缺少权限、超时和未知数据不会变成正常或零值。图适配层固定最多16节点，使用同步视图更新避免节点ID重用后旧DOM残留；普通滚轮仍滚动页面。使用和验证范围见[控制台说明](../runbooks/admin-console.md)与 VALIDATION-REPORT §77。
