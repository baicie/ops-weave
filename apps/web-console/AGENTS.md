# Web Console 开发约定

日志历史重放由WorkflowLogReplaySection持有固定版本、原UUID、请求取消、正文游标和待确认门禁，指标/日志共享WorkflowReplayPanel纯展示。展开仅元数据一次，正文明确读取且每页50条，复用文本日志组件，不执行HTML。父范围/身份变化清除正文；当前complete不替代原回执。原UUID查询/明确404重发、UNKNOWN原输出核验和关闭门禁保持；动作自然宽度窄屏换行。见[协议](../../contracts/workflow-log-replay.md)。

指标历史重放由WorkflowMetricReplaySection持有会话、固定版本、原UUID、输入/输出摘要、取消和待确认门禁，Panel纯展示。展开仅元数据一次读取，范围检查与确认输出分开；丢失/非法回执查询原UUID，404后原样重发，UNKNOWN只核验原输出。空证明摘要也校验，独立重放系列不冒充持续输出。见[协议](../../contracts/workflow-metric-replay.md)。

工作流历史由WorkflowRunsPage/WorkflowHistorySection持有授权目录、固定版本及分页请求，WorkflowHistoryPanel和共享WorkflowDiagnosticDetail仅展示。默认活动页就绪各读一次；明确凭据变更才重新读，forbidden不触发自动GET。快照、数量、连续offset、顺序和UUID不得失配，失败保留原只读页；目录外固定版本明确GET，不选最新替代。私有结果仅内存，隐藏/身份变化取消迟到结果；原预览与输出确认语义保持。见[历史契约](../../contracts/workflow-history.md)。

质量阈值使用 WorkflowQualityAlertsSection 请求容器和 WorkflowQualityAlertsPanel 纯展示组件。首次展开活动页读取一次；规则默认未启用，明确保存后才评估。缺失、截断或同时间混合结果不能显示为未达阈值；本地排队按 dispatch UUID 去重。原 UUID 待确认先查询，明确404后才原样重发；未保存/待确认同时阻止内部折叠和外层关闭。403沿用全局会话清理，禁止保留私有结果或自动重试。见[阈值契约](../../contracts/workflow-quality-alerts.md)。

调度观察由 WorkflowDispatchDetail 纯展示，仍由原诊断容器读取。queueWaitMillis 必须与闭合 dispatch 的规范 UUID/时间和实际毫秒差一致；缺少实测证据为“—”，实测不足 1 ms 为“0 ms”。同次出队多个窗口不能累加排队时间，不将本地等待冒充端到端延迟。

来源读取诊断由 WorkflowSourceReadDetail 纯展示，不增加请求。闭合解析可选 sourceRead 的数量、时间、原因与父观察一致性；缺少实测字段显示“—”，旧公开记录不得回填零。只在有实测来源时显示“来源失败 / 尝试”，分母不是来源 HTTP 页数或转换记录数。排队时间没有观测点时保持为空。

单次样本终止确认使用共享 WorkflowSampleRecoverySection/Panel。容器持有原命令、精确证明、取消/会话/页面门禁，Panel只展示。选择UNKNOWN后首次读取一次终止元数据，禁止自动输出核验或重写。失效回执保留原UUID，明确404才允许原样重发/放弃本地确认；终止成功不改原UNKNOWN。

先遵循[仓库根AGENTS.md](../../AGENTS.md)，再读[前端开发规范](../../docs/development/frontend-guidelines.md)和[兼容性基线](../../docs/FRONTEND-COMPATIBILITY.md)。

- 沿用React/TypeScript/shadcn及固定依赖，不将视觉参考站点的能力宣称为本项目能力。
- 共用页头、内容区、查询工具栏、统计卡片优先使用 `src/components/PageLayout.tsx`；展示组件不得请求接口或改变业务门禁。
- 统一主题变量与间距；修改共享布局后核对1440/1024/390px、明暗主题、键盘焦点和真实数据状态。
- 保留可信会话、契约校验、Fixture/Mock、未保存/待确认状态和不可变发布边界；浏览器不能代替服务端授权。
- 实际执行TypeScript/build及相关浏览器回归；全站共享布局改动跑全量浏览器套件。根AGENTS.md规定的契约、领域和Rust检查仍适用。
- 更新使用说明、实现状态与验证报告。只报告实际检查，不提交凭据、业务正文、截图、缓存或构建产物。
- 工作流工具栏保持保存/预览/发布与更多菜单，禁用条件由页面持有；左侧节点库分类/搜索，拖入有点击替代，跨窄屏收起但不清空流程。节点库Esc只处理自身事件；节点编辑为贴右全高非模态抽屉，保留键盘与发布只读边界。
- 完整指标键和来源键不得截断或冒充当前采样。具体定义经MetricDefinitionsPage读取授权目录，MetricDefinitionDetail只展示返回内容；非法、缺失和失败分别保留，不自动重试。返回目录使用显式空选择，验证标准/多页签和浏览器历史。指标记录字段数不等于已实现指标种类数。
- 模型中心目录在活动页会话就绪后自动读取一次，后台/未授权/非法选择不读；失败与403保留错误，禁止自动重试循环。筛选、主题、尺寸及已加载页签返回不重复读。MetricDefinitionList只展示列表和定义链接，请求、身份和重试由页面持有；菜单隐藏不能替代权限检查。

- 不常驻重复操作教程、实现范围文案及已有侧栏的返回入口；保留错误、Fixture、认证、未知结果和实际能力边界。节点库输出入口与抽屉按类别呈现，实体模型仅在实体输出配置；沿用单一OUTPUT契约与切换可撤销边界，不声称复制了参考产品的执行能力。
