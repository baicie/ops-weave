# ADR-053：显式 Chat Completions 模型协议

日期：2026-09-27。状态：已实现，本地真实提供方候选整链已验证，非生产验收。

## 背景

用户指定 OpenCode Go 与 DeepSeek V4.1 Flash。官方模型 ID 为 `deepseek-v4.1-flash`，接口为 `https://opencode.ai/zen/go/v1/chat/completions`。现有 Rig 适配仅发 Responses，不能根据错误自动切换协议或模型。

## 决策

- `OPSWEAVE_MODEL_API` 默认 `responses`，新增显式 `chat-completions`；未知配置启动失败。`OPENAI_BASE_URL` 继续要求 HTTPS 且无内嵌凭据、查询或片段。保留锁定 Rig 0.42.0，没有新增依赖或服务。
- `rig-openai` 是现有 OpenAI 兼容适配标识，不表示实际调用由 OpenAI 提供；本轮实际网关为 OpenCode Go，模型由可信配置和平台许可固定。没有修改业务 Schema 或发布 Skill 2.0.0 的内容/digest。
- Chat 请求使用一次非流式生成、n=1、最多2048输出 Token、JSON object，显式关闭 DeepSeek thinking。发出自己的 User-Agent，使用可信平台会话 UUID 作为 x-opencode-session；各请求独立构造头，无共享可变会话状态。密钥仅进入 Authorization。
- 共用无代理/重定向/重试的有界 HTTP：3秒连接、18秒响应、256 KiB；工作流单模型20秒、四次受控读取/复核、平台费用准入不变。不会向模型暴露动作工具或直接数据库权限。
- 必须有有效 prompt/completion/total 用量，输入1–81920、输出0–2048且总数相符。缓存统计缺失按0缓存作保守费用估算；它不是提供方账单或精确缓存证明。
- 在 Rig 归一化之前校验原始响应，防止 SDK 丢弃不完整 tool_calls 后留下表面合法文本。截断、拒绝、工具/旧 function_call 或混合非文本内容不给出诊断草稿；可核验用量仍先上报。缺失/错误用量保留未知预留。
- 输出还需经过固定 Skill/契约和证据范围/时间复核。日志仅新增固定失败阶段/码，不写模型正文、提问、证据或凭据。

## 验证与限制

4项新增协议测试覆盖并发会话头隔离、无密钥正文、有界请求、用量缺失/越界、截断/拒绝/非法工具、503/重定向/超大响应无重试无fallback。首次测试发现 Rig 将文本序列化为数组，修正为只接受纯文本字符串或文本数组。

本地真实候选脚本7/7通过，持久AIInsight与两类证据已回读。首次真实调用有有效用量但未形成结果，失败记录保留；随后显式新验收成功，不宣称模型输出可靠性已全面验收。完整结果见验证报告§65。

## 来源

- [OpenCode Go接口与客户端标识](https://opencode.ai/docs/go/)
- [DeepSeek thinking参数](https://api-docs.deepseek.com/guides/thinking_mode/)
- [DeepSeek JSON输出](https://api-docs.deepseek.com/guides/json_mode/)
