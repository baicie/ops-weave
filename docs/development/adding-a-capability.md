# 新增一个能力

OpsWeave采用模块分层和组件组合。Web按页面与展示组件组织，Java按领域、应用和适配器组织，Rust负责受控Agent执行。第一版仍为Platform API、Ingestion Worker、Agent Runtime、Web Console四个启动单元。新增能力通常接入已有单元，不为每个页面启动一个服务。

这里的“组件”有不同含义：React组件是界面复用单元；Java模块是业务边界；Connector是外部来源适配器。当前页面注册和后端装配在构建时确定，没有运行时安装任意前后端插件的机制。只有页面、按钮或来源卡片，不能宣称对应业务已经接通。

一级菜单只是导航分组，共用NavGroup读取ROUTES；二级页面在page-registry登记。例如模型中心下的实体、关系与指标页面共用Java catalog模块和`GET /api/v1/catalog`，指标页再组合MetricDefinitionList和MetricDefinitionDetail。新增纯展示能力通常只需页面、组件和登记；新增业务能力仍要补契约、领域/应用用例、API与权限、存储和验证，不能只放一对前后端组件就视为完成。

## 按实际变化选择实现范围

| 新增需求 | 通常需要修改 |
|---|---|
| 已有API的新展示、筛选或详情 | Web页面/展示组件、API适配与响应校验、路由登记、浏览器验证 |
| 新业务规则或新的读写操作 | contracts中的Schema/样例/接口；Java领域与应用用例、端口与适配器；Web；权限、范围、幂等与测试 |
| 新来源，例如一种资产系统 | 来源契约与配置校验；integration里的Connector、映射与采集用例；Worker装配与可靠性；平台查询/配置接口；Web接入展示 |
| 新持久化数据 | 业务契约、存储端口、PG适配器和迁移，实际迁移/回执/隔离验证；不是另加一个数据库的理由 |
| AI诊断或Tool能力 | 平台受控API、服务端授权与预算、Rust端口/固定流程或适配器，以及证据时效和缺失数据处理 |
| 执行性动作 | Proposal → Policy → Approval → Executor、执行器权限与预算、幂等回执；不能由模型或页面按钮自行授权 |

## Web接入步骤

1. 在`apps/web-console/src/pages/<业务>/`编写页面。页面组织流程和状态；共用展示放进`components`，响应校验/传输放进`api`，身份与地址生命周期放进`state`，图形库放进`adapters`。遵循[前端规范](frontend-guidelines.md)。
2. 在`src/state/routes.ts`增加`RouteName`和`ROUTES`条目，使用已有分组或明确补充新的分组。名称、路径、描述、搜索关键词由这个目录统一提供；不要在侧栏、搜索和页签各写一份。
3. 在`src/app/page-registry.tsx`登记页面组件。`Record<RouteName, ComponentType>`检查每个路由都有页面；`PageWorkspace`自动提供标准/多页签容器、主题和关闭入口。普通页面不需要修改App中的条件分支。
4. 使用`PageHeader`、`PageBody`、`QueryToolbar`等共用布局，不给新页重复包一套页面外边距或全屏滚动容器。
5. 地址选择使用`useViewLocation`。有自定义地址监听或进入时自动读取的页面，使用`usePageActive`限制到当前页；切回同一地址不能清空未保存内容或再次自动读取覆盖草稿。显式发出的请求可以完成到自己的页面，不能跳转当前地址。
6. 编辑或回执页面使用`usePageCloseGuard`上报实际未保存状态。请求进行中、结果待确认时上报`blocked: true`，关闭操作不能替代原请求回执查询。不要根据DOM、按钮颜色或CSS推断业务状态。
   原生dialog的展示由页面外壳在离开/返回时暂停与恢复。`onClose`不要无条件丢弃草稿，业务清理由显式放弃或身份变化执行，避免浏览器返回时后台抽屉阻挡当前页面。
7. 使用可信会话和请求代际检查。多页签只是同一浏览器标签页内的页面缓存，不是新会话；身份变化会移除后台页面，当前页面也必须清空自己的数据并取消旧请求。所有新页都要验证迟到响应不能恢复旧主体数据。
8. 在标准和多页签布局、浅色和深色主题、1440/1024/390px检查。新增只读展示不必改Rust；涉及跨语言API时必须从contracts同步，不能手改生成客户端。

布局偏好只保存`opsweave.ui.layout`的`standard`或`tabs`。页签、查询地址、滚动位置和编辑正文在当前标签页内存中；刷新只保留布局偏好和浏览器当前地址。页面缓存每个已登记路由至多一份，当前上限为18个产品页面加1个开发演示页。

## Java接入步骤

先判断规则属于现有`modules/inventory`、`integration`、`incident`、`catalog`、`telemetry`等哪个领域。业务Schema先放`contracts`，纯规则进入模块`domain`，应用流程及所需端口进入`application`，厂商HTTP/JDBC等实现进入`infrastructure`或对应应用适配器。领域不能依赖Spring、JDBC、HTTP或厂商DTO。

Platform API在`apps/platform-api`装配HTTP、可信身份和存储；采集任务在`apps/ingestion-worker`装配。已有来源配置与工作流可参考`SourceSetupService`、`WorkflowController`、`SourceSetupController`以及`PlatformConfiguration`；这些是实现结构的例子，不意味着新来源可以仅改名称复用所有规则。新的控制器必须在服务端检查tenant、对象范围、时间、数量和费用预算。

存储变更补迁移与真实适配验证；采集变更补超时、有界并发、完整性、幂等与失败重放检查。上游失败不能当作完整空快照删除资源。新增微服务、数据库、任意动态代码执行或独立算法服务需需求/ADR。

## 验证与交付

修改相关契约、样例、文档、测试，并更新IMPLEMENTATION-STATUS、ROADMAP及VALIDATION-REPORT。根AGENTS要求至少实际运行契约、纯领域、Rust默认/all-features及TypeScript/build。共享页面容器或导航修改运行完整浏览器套件；新业务也要完成其真实接口、存储与权限检查，浏览器Fixture不能代替这些验证。只报告实际运行结果，未执行或失败的检查如实记录。
