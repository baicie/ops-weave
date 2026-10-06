# ADR-084：固定外部日志来源与受控样本写入

状态：已接受本机实现；连续日志与生产退出条件继续。日期：2026-10-04。

现有连接、秘密版本和发现清单已经能保存 LOG 类型的元数据；工作流过去只能以手工日志样本验证输出。现在复用这些服务，由服务器选择自己的有效发现记录，不新建连接体系或执行后端。

`ZABBIX_LOG` 固定配置 pin 和独立日志项 pin，包含发现 UUID、item/host ID、完整来源键、单位、LOG 类型及摘要。它只允许 LOG 输出。其他来源不允许附带日志 pin；旧定义不增加摘要部分，旧存储 JSON 不重写。固定版本的来源差异进入版本比较。

选择清单只列出自己已完成的真实发现元数据，接受完整 FIRST_PAGE_MATCH 或经服务端 membership 校验的 ITEMID_WATERMARK 页面。首次选择要求15分钟有效期；已绑定版本不依赖后来重新发现，而在执行前后检查原配置/秘密可用性及当前来源元数据。归档、撤销、漂移、缺失和失败均不替换为 Fixture 或成功空快照。

来源读需同时满足 source.sync、派生 Host 的 entity.read、`log:source.<instanceId>.item.<itemId>` 范围的 log.read，以及既有连接和凭据权限。目的日志写入和回读仍分别检查 `log:workflow.<workflowId>` 的 log.write/log.read。元数据 pin、按钮可见性和工作流编辑权限都不授予这些权限。

适配器对固定 item 调用 `item.get → history.get(history=2) → item.get`，最近600秒、最多5条样本加1条截断哨兵，共享20秒单次预算；不自动重试。顺序、item、clock/ns、正文2048字符和上下文字段有界。任何不合法输入拒绝整次读取，正文不隐式裁剪。默认 `timestamp` 是来源接收时间，映射 eventTime；原事件时间单独保存为可选 `logEventTime` 输入。原 severityCode/eventSource/eventId 不被当作标准级别、服务名或全局标识。

接收时间、纳秒与原始事件字段的语义依据正式连接器文档：[History object](https://www.zabbix.com/documentation/7.0/en/manual/api/reference/history/object)。字段缺失保留 null，业务规则可以显式选择事件时间或转换级别，输出仍经过时间/正文校验。

源样本命令只接受 requestId、固定工作流 id/revision/digest。Java 执行器读取来源并生成一次只读 RUN；结果留在内存，RUN 只持久化闭合计数和固定来源谱系。验证、权限复核后在 PG 持久 PENDING，再沿用已有日志端口尝试一次写入和精确回读。17字段回执与实际正文分开，正文只进入已有日志引擎。

重复 UUID 命中受理回执后，不再读取来源、不再输出；命令 namespace 区分手工与源样本请求，变更返回409。并发请求可以在受理前各自读取，但事务只受理一个批次、外部写入只由获得新受理回执的请求执行。来源读取失败发生在输出受理之前，不声明已写入。受理后未知状态只通过原 scope、索引和完整 batchDigest 读确认，禁止新来源读取或重写。每批最新5条是明确的样本；截断信息保留在关联 RUN，确认完整输出批次不代表完整来源窗口。

Web 请求容器与纯列表/字段/输出视图分开；来源抽屉默认一次清单读取，失败后显式刷新。固定来源不接受浏览器手工正文。晚到响应、身份切换、原 UUID 未知门禁、窄屏和主题沿用共用生命周期。默认开发权限保持原边界，验收身份的额外授权只在本机私有配置。

业务仍为四个启动单元，没有新增数据库、PG 原日志表、Python 后端或任意代码节点。本阶段是外部样本闭环，不是持续日志采集、去重检查点、保留策略或生产 HA。连续日志需独立有界完整窗口、稳定事件身份、确认后检查点和未知恢复设计；不能把重复获取最新5条样本当成连续任务。

见[来源契约](../../contracts/workflow-log-sources.md)、[输出契约](../../contracts/workflow-log-output.md)、[本机操作](../runbooks/workflow-log-output.md)及验证报告§122。
