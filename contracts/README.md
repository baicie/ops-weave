# Cross-language contracts

显式固定版本[质量阈值](workflow-quality-alerts.md)：本人配置、CAS、原UUID不可变回执与闭合评估使用独立契约，默认未启用。缺失、截断及顺序不明不能推断未触发；本地排队去重，当前任务与历史分开。元数据评估不执行来源、输出、通知或动作。

实测本地 FIFO 入队至出队观察见[调度协议](workflow-dispatch-diagnostics.md)：可选闭合 dispatch 与数值 queueWaitMillis 配对，旧未测量仍为空；同次出队两个窗口共享 UUID，不能累加为两次排队。

检查统计见[协议](workflow-diagnostics.md)：来源完整有界列表分母、部分处置/未检查、节点异常、不可用null；最近20次及原UUID只读查询，未增加执行接口。固定主机分页的私有逐实体见证使用独立stored Schema，不进入公开投影。

运行质量只读观察：[计数与协议](workflow-quality.md)。固定版本最近20批及原UUID查询，主机缺失分项保持null，指标重交和日志新增位置分开；统计不触发采集或输出。

完整日志窗口端口见[契约](workflow-log-windows.md)。持续日志控制、不可变窗口证明与确认后checkpoint见[持续日志契约](workflow-log-streams.md)：60秒、1,000条、独立纳秒位置、上一分钟补采及50条正文分页。

日志有限样本写入、元数据回执及授权正文回读见[契约](workflow-log-output.md)，固定来源见[来源契约](workflow-log-sources.md)。独立log.read/log.write不扩大默认开发权限；正文进入按需日志存储，未知结果只查询确认。样本与持续窗口入口保持独立。

固定指标连续窗口：[契约](workflow-metric-streams.md)，Task/批次/控制独立Schema，点值不进PG。

任务启动/停止使用原请求UUID与不可变受理回执，查询不改变当前任务，见[任务控制原回执](workflow-runtime-control.md)。控制请求/响应须成对升级，未知结果保留原键与输入。

工作流实体后台的公开授权摘要、私有持久引用及操作员许可分别定义，见[后台授权边界](workflow-background-authority.md)。公开引用不能替代可信身份或执行器授权；未开放来源不得回退。

这里是公开 JSON 契约的唯一手工维护源。`examples/` 全是合成数据。当前契约是起始子集，不是完整领域 Schema。

新增契约必须：Schema 验证、正反例、授权/租户测试、版本兼容评审。未来生成的 Java/Python/TypeScript 客户端放各应用 generated/；禁止手工修改生成代码。

来源采样的完整pin、兼容维护与原命令结果见[映射维护](metric-mapping-maintenance.md)及schemas/v2、examples/v2的metric-mapping-*文件。来源指标成员清单分页见[分页契约](source-metric-pages.md)；元数据发现与采样维护不等于工作流或后台运行。

工作流METRIC 1.1固定完整指标标识及映射语义，并在只读预览中执行转换、范围和固定维度，见[标准指标工作流](workflow-standard-metrics.md)。[固定指标来源](workflow-metric-sources.md)已接入原始数值采样与完整系列出处；实际有界样本输出见[指标输出](workflow-metric-output.md)，持续任务与checkpoint仍待验收。

`tools/*.tool.json` 的 implementationStatus 明确为 notImplemented，不代表可以调用真实系统。

第115节：已发布固定指标的实际样本写入、UNKNOWN原结果验证与当前点查询见[指标输出](workflow-metric-output.md)。发布和只读测试本身仍不写指标点；样本入口显式写入且最多5点，不推进持续采集checkpoint，也不创建资产绑定。

固定接入资产扫描的原批次、公开状态、STOP/RESUME及确认后checkpoint见[资产扫描契约](workflow-host-scans.md)。有限Host清单扫描与持续指标/日志任务分开验收。


周期主机采集：见[契约](workflow-host-schedules.md)，封闭周期状态、命令及原控制快照，复用现有扫描任务公开授权摘要。


第119节新增[模型影响契约](model-impact.md)：自己的固定草稿兼容性检查及当前授权的模型固定引用，封闭 schema 1.0；不提供自动迁移、任务重绑定或业务执行权限。

第122节新增[固定外部日志来源](workflow-log-sources.md)：复用配置/发现、LOG pin、四字段服务器样本输出；旧手工输出和摘要保持。最新最多5条明确为样本，持续日志检查点未提供。见[输出契约](workflow-log-output.md)。

注册连接的指标目录同步与只读扫描记录使用 schemas/v2/registered-item-* 契约：命令固定 source UUID、配置 revision 和已登记 host group 范围，不接受请求内的租户、地址、凭据或范围覆盖。仅完整且已验证的扫描会退休本次 host cohort 内缺失的绑定；范围收窄后离开新范围的旧绑定仍保留，需后续受控清理。
