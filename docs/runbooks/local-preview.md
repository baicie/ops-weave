# 本地 OpsWeave 预览

2026-09-27：Web、Java Platform API、Java History Worker、Rust Runtime 四个应用单元在后台运行；PG/VM 使用独立容器和持久卷，无原生PG控制台。

- 页面：http://127.0.0.1:5173/#/inventory
- 会话：第79节起通过Go管理器启动Web自动建立本地会话，无需填写Token；实际开发凭据留在本机服务端。旧手动启动方式与显式手动模式仍需凭据，见[本地会话](local-session.md)。
- 指标：选择“OpsWeave 本地测试容器”，可查询CPU user、可用内存比例和系统运行时长；选择Last 15m后查询。
- Incident：刷新列表并打开 [LOCAL TEST] OpsWeave 只读告警闭环验证，可见实际Zabbix触发/恢复、资产关联和PG时间线。来源恢复不自动关闭Incident，当前人工状态仍OPEN。
- 第66节UI参考 [Shadcn Admin](https://github.com/satnaing/shadcn-admin) 的浅色侧栏、紧凑顶栏与卡片/表格层级，以原有Zeus实现。支持侧栏收起、移动端抽屉、明暗主题与Ctrl/⌘+K页面搜索；当前菜单已扩展到17个页面，搜索只跳转页面，不查询业务数据。
- 资产统计仅计算本次读取的当前页，不代表全租户总数；未读取时显示“—”。来源行直接展示dataMode，Fixture标记不隐藏。
- 已有标签页刷新后使用。只有主题偏好保存到本地存储；刷新清除旧会话与数据，再自动建立新会话，业务数据仍需显式读取。

## 菜单与页面定位

第69节将已有16个页面归为六组：数据接入、模型中心、资源观测、故障诊断、AI管理、开发演示。点击分组标题可收起/展开；默认展开接入、模型、观测及当前页面所在组。Fixture诊断仍保留明确标签，开发演示组默认收起，直接进入演示页会展开。

- Ctrl/⌘+K支持页面名、分类、路径和业务词。例如“技能”“Zabbix 采集”“模型中心 关系”；多个词同时匹配。
- 顶部当前位置跟随实际分组；通过搜索、直接链接或浏览器前进后退进入页面，目标分组会展开。
- 桌面图标模式保留全部17个入口与名称提示；移动抽屉保持自己的分组状态，关闭后焦点回到导航按钮。菜单区独立滚动。
- 分组状态只在当前页面内存中，不保存身份/业务数据。刷新使用最新构建并自动重新建立本地会话；菜单不是权限边界。
- 第70节新增“数据工作流”入口：模板、清洗节点画布、目标模型、草稿、预览、发布及只读测试运行已提供，见[工作流说明](workflows.md)。数据源中心配置抽屉和关系实例管理仍待实现。

## 来源与边界

真实厂商软件是本地Zabbix7.0.27，dataMode=zabbix-jsonrpc，环境为local-test。Agent读到的是容器可见Linux/WSL指标，不是Windows宿主机或客户资产。
主机10683有三项真实采样；另外两个 [LOCAL TEST] 分页验证 A/B 是无采集的测试对象，只用于跨页验证。
Host和Item分别以pageSize=1完成3页，重复同步无退役。Host映射固定zabbix-host-default@1与原digest，CPU/内存/uptime使用独立映射文档。
当前真实扫描最多1000个对象，使用ID清单和hostids/itemids，不发送Zabbix不支持的offset，见[扫描契约](../../contracts/host-scan.md)。

告警来自本地测试规则：实际uptime采样满足>0触发，再显式改成<0恢复，事件23→24；这是受控测试规则产生的厂商事件，不是业务故障或根因证明。规则目前正常，名称保留LOCAL TEST。
Incident 7dcf7c03-d3f3-3fae-97a2-e75ce731066c 经重复导入只有一条PROBLEM和一条RECOVERY，映射到10683；日志/变更缺口保留。
平台重启后记录一致；Worker重启后三个检查点沿原名称/原起点继续推进，没有删除或重置检查点。

固定开发身份tenant-demo/user-demo与本机开发DB账号不是生产身份/最小权限部署。Rust以platform-dev/rig-openai兼容适配运行，实际网关OpenCode Go，模型deepseek-v4.1-flash。
真实候选脚本7/7通过；AIInsight 86eb67ef-983f-4160-a04f-3194393adfc2与两类证据及费用已保存并通过重启回读。
入口：http://127.0.0.1:5173/#/incidents/current-diagnose?runId=86eb67ef-983f-4160-a04f-3194393adfc2，本地会话就绪后点击“读取已保存结果”，不会重复调用模型。
两次显式调用中，首次有有效用量但未形成结果，第二次成功；详细失败范围见验证报告§65，不能据此推断可靠性。
模型配置在忽略目录model-config.json，密钥单独在model-secret.json且只注入Runtime；凭据不进入报告、源码或普通日志。
真实IdP/代理与人工评估未验收；用户要求将TLS后置，本机继续HTTP开发联调，不追认生产TLS已通过。Fixture诊断页与协议fixture继续保留标签。

## 端口与配置

| 服务 | 本机地址 |
|---|---|
| Web | 127.0.0.1:5173 |
| Platform API | 127.0.0.1:8080 |
| History Worker | 127.0.0.1:8081 |
| Runtime | 127.0.0.1:8090 |
| 开发PG | 127.0.0.1:15439 |
| VictoriaMetrics | 127.0.0.1:18428 |
| Zabbix Web | 127.0.0.1:18088 |

配置、凭据、PID/日志和截图在忽略目录.tmp/local-preview；不要提交secrets.json/login.txt/.env。
当前使用Go管理器 `pnpm ops start/restart/stop/status`，见[管理器说明](dev-manager.md)。旧start.cjs保留为历史本机脚本，不与Go管理器混用。存储项目opsweave-local-preview，Zabbix为独立opsweave-zabbix-local。
Worker显式migrate仅用于本地开发库，部署默认verify。VM保持-dedup.minScrapeInterval=1ms。
多流配置OPSWEAVE_HISTORY_STREAMS固定每流itemId:streamName:initialFrom；不要在每次启动时生成新的initialFrom。当前三条流沿已保存起点续采。
本轮已实测Platform与Worker停止/启动、Zabbix Web断连/恢复；未测试整机重启或完整Compose停机恢复。

## 实测产物

.tmp/local-preview中的multi-page-verification.json、source-failure-verification.json、three-metrics-verification.json、alarm-active-verification.json、
alarm-recovery-verification.json、incident-restart-verification.json、worker-restart-verification.json、browser-complete-verification.json及截图记录本地实际来源链。
本轮测试数据库opsweave_checks_64与预览业务库opsweave分开；测试使用现有PG/VM容器，没有新启动原生PG。
真实模型产物real-candidate-65.json、model-validation-65.json、insight-65.json、insight-restart-65.json、real-browser-65.json保留在同一忽略目录。完整测试数及失败尝试见[验证报告§65](../VALIDATION-REPORT.md)。测试通过不签署M0–M4全部退出，保留后台服务供查看。
