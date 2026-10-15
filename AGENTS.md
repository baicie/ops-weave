# OpsWeave 开发边界

先读 `docs/architecture/v4-design.md`、`docs/ROADMAP.md`、`docs/IMPLEMENTATION-STATUS.md`、`docs/VALIDATION-REPORT.md`。只报告实际运行过的检查，不将编写测试等同测试通过。

通用强制规则统一维护在 `.agents/rules/`：所有任务读取 `.agents/rules/00-product-boundary.md`，其他规则按 `.agents/rules/README.md` 中的适用范围读取并遵守；此要求适用于所有 Agent。

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

- 外部对标产品的名称、商标、域名、仓库路径、截图及逐项研究记录不得进入本仓库；产品文案、源码、注释、配置与规范只使用本项目术语和通用设计原则。正式依赖与已支持连接器的技术名称不受此条影响。
- 对标产品文档和原始证据保存在仓库外本机目录；不得用编码、缩写或替换域名方式把来源身份藏入规则。检查词表同样留在仓库外，以私有路径传给`python scripts/check_reference_boundary.py --policy <本机词表>`；提交前执行此检查，检查器不输出命中正文或词表内容。CI需通过私有配置提供词表，不把词表提交到仓库。
- 修改相应契约/样例/文档/测试，更新实现状态。
- 初始环境生成锁与Wrapper后提交；CI不自动升级。
- 禁止捏造Cargo.lock、验证报告、生产Chart或“已连接”能力。
- 至少跑契约、纯领域、Rust默认和all-features、TypeScript/build；未运行的如实说明。
- 不提交.env、真实凭据、node_modules、target、缓存或模型输出中的客户数据。

## OpsWeave 0.1.0 前端设计约束

- 前端任务先读取 `.agents/rules/10-ui-design.md`、`.agents/rules/20-ui-interaction.md`、`.agents/rules/40-ui-qa.md`；涉及可观测数据或调查呈现时还须读取 `.agents/rules/30-observability-visualization.md`。
- 修改 `apps/web-console/**` 或准备引入 `apps/web-next/**` 前，先阅读 `docs/ui/visual-system.md`、`docs/ui/interaction-contract.md`、`docs/ui/page-blueprints.md` 和 `docs/ui/component-contracts.md`。
- 新页面先写明 Primary Task、信息优先级、可见状态、失败路径、桌面/窄屏布局，再实现组件；不得把 Controller 字段逐项堆到页面。
- 使用统一视觉 Token 和复合组件；避免随机色值、任意层级的 Card 嵌套、随机阴影和多个同权重主按钮。候选 Token 只在明确的 `.ow-v2` 范围内试用，不覆盖现有全局样式。
- Incident、Evidence、Metric、Log、Trace 的数据标签、单位、时间窗、采样、缺失和 Fixture 必须准确；缺失数据不能渲染为 0，AI 假设不能显示成已确认根因。
- 所有异步区域覆盖 initial/loading/ready/empty/partial/stale/error/forbidden/fixture；身份变更时清理敏感状态，无法证明写入成功时保留 UNKNOWN。
- 新前端工作完成真实浏览器的 1440/1024/390 三宽度、light/dark 截图审查；无法运行时记录 `visual: NOT_RUN`，不能宣称通过视觉验收。
- 视觉审查先处理 P0/P1（越权、误导数据、操作不可达、溢出、遮挡、低对比度），再处理 P2 视觉细节；静态脚本只是启发式检查，不替代截图、可访问性和 Playwright。
- UI 工作按 `opsweave-ui-design` → `opsweave-page-build` → `opsweave-visual-audit` 分阶段；Metrics、Traces、Logs、Evidence 时间线和 RCA 图表另使用 `opsweave-observability-viz`。
