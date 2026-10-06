# 本机日志批次输出验收

## 部署与身份

日志库是按需部署的基础设施，业务应用仍为Java平台、Java Worker、Rust Runtime和Web四个启动单元。使用 `deploy/compose/logs.yaml` 的 logs profile，镜像固定digest。HTTP端口仅绑定127.0.0.1:8123，容器内存1GiB、服务端768MiB；这是本机验证配置，不是生产容量声明。受控schema由部署初始化，不由业务请求执行DDL。

在仓库外或忽略的本机env中配置随机管理密码 `OPSWEAVE_LOGS_ADMIN_PASSWORD`。启动命令：

```powershell
docker compose --env-file <私有本机env路径> -f deploy/compose/logs.yaml --profile logs up -d
```

由管理员预先建立 `opsweave_log_io` 并仅授予 `opsweave_logs.workflow_records` 的 SELECT/INSERT。平台私有环境提供 `OPSWEAVE_LOGS_URL`、`OPSWEAVE_LOGS_USER`、`OPSWEAVE_LOGS_PASSWORD`，平台不会创建账号、建表或自动降级。仅接受明确loopback HTTP origin，不使用环境代理或跟随重定向。默认配置为空，默认开发权限不扩大。

本机验收身份额外显式授予 `log.read` 和 `log.write`，生产通过可信认证边界的授权配置发放，并满足相应log对象范围 `workflow.<id>`。Token/密码不得写入仓库、普通日志、截图或shell输出。

## 实际验收流程

1. 建立手工日志工作流，保留SOURCE→MAP→VALIDATE→OUTPUT，发布固定版本。先用最近时间的明确Fixture日志验证样本。预览/RUN只读，不写日志。
2. 展开“日志写入与回读”，检查实际可用性；未配置、表结构/引擎不符、不可读等保持不可用。首次展开只读取能力及历史。
3. 点击“写入日志样本”，检查CONFIRMED及确认数量。点击“回读日志记录”，核对原输入索引、canonical时间、空格/换行/Unicode/可选缺失值及正文原文；不只核对行数。
4. 同UUID/原内容再次提交只返回原回执；变更样本409；跨subject/tenant404；正文读取必须有log.read。工作流编辑权限不能代替log.write。
5. 对丢失响应/部分读到等故障检查UNKNOWN，原请求仍固定。点击“查询原回执/验证日志批次”只发GET。禁止生成新UUID、补偿POST或显示完整空数据。
6. 检查PG回执和工作流RUN trace没有样本/正文/权限/凭据。精确日志查询限定tenant/owner/request/workflow revision/digest，并显式FINAL处理查询去重。DDL必须被应用账号拒绝。
7. 重新建立PG适配器后，PENDING/UNKNOWN原回执、固定摘要和索引保持；只能通过实际查询确认。保留旧回执，容量达到上限后拒绝新命令。
8. 在1440/390及亮/暗主题检查正文转义、长ID、原文换行、键盘焦点及页面无横向溢出；清除身份后晚到响应不得恢复正文。使用独立浏览器上下文，不更改用户标签页。

任何存储写入尝试后的异常都按UNKNOWN保守处理，即使存储错误看似未落地。回执确认只代表当次固定批次回读匹配，当前回读仍可能不完整或不可用。单机存储、持久卷和引擎查询去重不证明高可用或跨故障exactly-once。固定来源的持续窗口、迟到补采和检查点使用独立入口，见[持续日志说明](workflow-log-streams.md)。

契约见 [日志输出契约](../../contracts/workflow-log-output.md)，架构决策见 [ADR-082](../adr/082-confirmed-workflow-log-storage.md)，实际运行结果只以 [验证报告](../VALIDATION-REPORT.md)为准。

## 固定外部日志样本

1. 在来源中心保存受控地址与秘密的固定连接版本，并显式执行字段/指标项发现。分页需当前 membership 验证，来源账号必须能读取该日志项所属主机；失败或不完整结果不能作为选择。
2. 创建日志工作流，在输入抽屉选择接入实例、固定连接版本及来源日志项。默认将 timestamp/body 映射到 eventTime/body；抽屉的“日志来源字段”列出原始事件时间、级别代码等可选输入。选择来源会重置基础映射，请重新核对自定义规则后保存。
3. 保存并预览，查看原文、缺失上下文和截断信息；固定算子后发布。预览/测试运行不写日志库。
4. 展开“日志写入与回读”，明确点击写入样本。服务器读取最近10分钟最多5条；本次输出时间仍需在执行前24小时以内。该操作没有持续任务和检查点。
5. 原回执 UNKNOWN/PENDING 时查询或只读验证原批次；只在原回执明确404后重发同一请求。不要换 UUID 或重新拉取日志来代替确认。完整回读匹配指本次批次，不能证明完整来源窗口。

验收授权还需要来源实例的 source.sync、对应 Host 的 entity.read、`log:source.<instanceId>.item.<itemId>` 的 log.read；目的读取/写入范围仍是 `log:workflow.<id>`。不扩大默认身份权限。

本机正式连接器服务、PG 和日志引擎可使用明确 Synthetic Fixture 的专属主机、日志项及正文联测。只修改自己的测试主机；若接入账号无法读取它，应核对测试主机组，不扩大来源令牌权限或修改其他主机。来源身份和原始验收证据保存在仓库外。报告实际服务版本与已执行检查，不以 Fixture 文本声称客户日志已接入。

见[来源协议](../../contracts/workflow-log-sources.md)和[ADR-084](../adr/084-fixed-log-workflow-sources.md)。持续日志去重、完整窗口、检查点、长期保留和 HA 继续。

## 完整日志窗口内部端口

第123节接通完整有界读取、内存算子与整窗存储端口。未增加公开连续任务或Web入口，仍按上述样本流程操作；不能将样本成功视为连续采集。

新安装的日志基础设施按001/002部署初始化表。既有持久卷不会因compose新增初始化挂载自动升级；由日志库管理员显式执行`db/migrations/logs/V002__workflow_log_windows.sql`，再仅授予应用账号新表`opsweave_logs.workflow_log_windows`的SELECT/INSERT。不要删除持久卷或修改旧样本表键以升级。业务账号不建表、不自动迁移。

内部验收使用专属Synthetic Fixture来源项：同秒1,000条不同ns、边界秒分页、多秒分页、失败页、超量、元数据漂移、总时间与响应字节上限。全部完成后一次输出，精确回读位置/索引/原文/缺失字段及摘要；源empty与失败分开，空输出不POST。原样本库、既有指标和平台元数据须重新只读核对。此项验收没有持久任务证明、停止/恢复、checkpoint或HA声明。

见[内部窗口契约](../../contracts/workflow-log-windows.md)和[ADR-085](../adr/085-bounded-complete-log-window-ports.md)。
