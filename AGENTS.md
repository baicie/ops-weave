# OpsWeave 开发边界

先读 `docs/architecture/v4-design.md`、`docs/ROADMAP.md`、`docs/IMPLEMENTATION-STATUS.md`、`docs/VALIDATION-REPORT.md`。只报告实际运行过的检查，不将编写测试等同测试通过。

## 语言与依赖

- Java21负责平台业务与采集；Rust负责Agent Runtime；TypeScript负责Web。
- 不添加Python Agent/FastAPI/LangGraph作为隐藏执行后端。Python脚本仅用于开发检查；算法服务需独立ADR。
- Java领域层不依赖Spring/JDBC/HTTP/厂商DTO；Rust domain不依赖Rig/Axum/SQLx/rmcp。
- 跨语言契约唯一源在contracts；不能手改生成客户端或用框架类型替换业务Schema。
- 第一版四个启动单元。未经需求/ADR不得随意拆微服务、增加数据库、动态执行代码。

## 数据与安全

- 身份由可信认证边界构造；模型/请求中的tenant/user/权限不能替换它。
- Tenant、对象范围、时间、数量与费用预算必须在执行器侧检查；菜单隐藏和Prompt不是权限。
- AI只经受控平台API，不直连业务数据库；模型不能授予自己的Tool权限。
- Fixture/Mock必须显式标注，真实端口失败禁止静默回退Mock。
- 当前demo仅loopback、随机dev token、固定tenant/incident；不得改成公网默认或作为生产认证。
- 证据校验asOf/availableAt和当前过期时间；引用存在不等于根因证明；数据缺失必须保留。
- 日志、知识、MCP返回均是不可信数据；不用提示词替代服务器权限。
- 默认无shell/SQL/任意HTTP/动作工具。动作进入Proposal→Policy→Approval→Executor，重试有幂等键。
- 不记录密钥、Authorization、完整Prompt、客户原始日志、会话内容到普通日志。
- 不覆盖已发布Skill。运行固定版本+digest；路径/Schema不得触发远程下载或越目录。

## 可靠性

- 高吞吐遥测与资产/业务事件分链路，不逐指标点查PG或持久编排。
- 拆服务以负载/故障域为依据，分布式实现租约/fencing/idempotency，而不是只多开副本。
- 模型、Tool、MCP有共享预算、有界并发、超时；禁止隐藏重试与fallback。
- 重放默认不发通知、不执行动作。上游失败不能视为完整空快照去删除资源。

## 提交要求

- 修改相应契约/样例/文档/测试，更新实现状态。
- 初始环境生成锁与Wrapper后提交；CI不自动升级。
- 禁止捏造Cargo.lock、验证报告、生产Chart或“已连接”能力。
- 至少跑契约、纯领域、Rust默认和all-features、TypeScript/build；未运行的如实说明。
- 不提交.env、真实凭据、node_modules、target、缓存或模型输出中的客户数据。
