# 本地预览自动会话

用户选择本地页面打开即用后，Go管理器启动的Web预览自动建立短时会话。此流程仅适用于本机开发，不实现正式IAM或替换Java授权边界。

## 使用

```text
pnpm dev:build
pnpm ops restart web
pnpm ops status
```

打开 `http://127.0.0.1:5173/#/integrations/sources` 或平台其他页面，无需填写开发Token。可以直接点击“读取数据源”或选择类型打开配置。只有会话握手自动执行；业务数据读取、确认配置、采集与模型诊断保持现有显式操作。

会话最长30分钟，刷新建立新会话并清除旧业务结果。过期、401或连接失败会显示“重新连接”；点击后恢复会话，业务数据重新读取。没有隐藏重试、无身份访问或失败转Mock。页面离开会清理内存，浏览器恢复事件可建立新会话，不恢复旧私有响应。

## 服务端与浏览器边界

1. Go按平台配置 `.env` → `.env.dev` → `.env.dev.platform` → 当前终端环境读取 `OPSWEAVE_AUTH_MODE` 和随机 `OPSWEAVE_DEV_TOKEN`。显式demo复用已有demo凭据。只向Web派生私有 `OPSWEAVE_WEB_LOCAL_TOKEN`，不写入前端环境文件或产物。
2. Vite仅在serve模式且配置私有凭据时启用桥接，并将前端模式设为 `local-preview`。构建产物不包含此桥接或后端Token。
3. 浏览器以同源GET请求 `/__opsweave/local-session`，携带 `X-OpsWeave-Local-Session: bootstrap`。此开发握手返回且仅返回 `sessionNonce`（32随机字节的base64url编码）和 `expiresAt`（ISO时间）。它不返回后端Token、租户、用户或权限声明，不是业务跨语言契约。
4. nonce只保存在标签页内存；业务请求携带 `X-OpsWeave-Local-Session`，无Bearer和Cookie。Vite检查nonce后移除该头，在固定 `/api/v1/` 代理到本机Java之前添加服务端Bearer。
5. Java仍逐请求验证可信身份、tenant、对象范围、权限和执行预算。浏览器不能指定身份或修改权限。Vite不向 `/agent` 的独立Fixture Runtime或任意目标注入平台Token。

桥接要求绑定127.0.0.1，核对回环来源、精确Host/端口、Origin（若有）与Fetch Metadata，拒绝跨站、同站但非同源、浏览器Bearer覆盖及缺少nonce的API请求。握手只接受固定GET和自定义头，不提供跨域许可。最多保留64份未过期会话；满额拒绝新会话，过期清理。nonce不放入URL、持久存储、DOM或普通日志。

## 其他模式

`.env.dev.web` 的 `OPSWEAVE_WEB_LOCAL_SESSION=false` 可保留手动开发模式；`VITE_PLATFORM_AUTH=oidc` 保留正式登录路径。直接运行Vite而没有私有桥接配置时沿用原模式。非dev平台不自动派生Token，配置无效时明确失败，不回退匿名。独立Fixture诊断页继续使用明确演示会话，不将Mock标为真实。

固定开发身份和随机Token仍属于开发边界；用户提供的Tenant/Team/Project/Scope/Policy设计作为后续IAM参考，本轮没有新增角色、权限策略、IdP或生产认证。

## 验证

`pnpm test:dev-session` 执行显式本机HTTP负例和浏览器传输/生命周期协议测试；`pnpm test:web-local` 启动4175独立Vite Fixture，验证无输入自动会话、刷新、失败重连和401数据清理。它不使用5173现有业务环境，不触发来源采集或真实模型。已有 `pnpm test:web` 继续验证手动与OIDC协议Fixture。实际结果见VALIDATION-REPORT §79。
