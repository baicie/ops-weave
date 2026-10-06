# ADR-082 受控工作流日志存储与确认

- 日期：2026-10-04
- 状态：已实现并通过本机手工有限批次验收；外部来源/持续采集和生产退出条件继续
- 依据：[v4存储与遥测分链路](../architecture/v4-design.md)、[固定输出格式](060-source-first-typed-workflow-output.md)

日志工作流已有确定性格式校验和预览，缺少持久输出。按既有架构的可选日志引擎落地 ClickHouse 适配与 `logs` 基础设施 profile；业务启动单元仍是 Java平台/采集、Rust runtime、TypeScript Web 四个，不增加执行服务、动态代码或通用SQL/HTTP节点。日志正文保存在业务日志库，PG只记录原命令摘要、计数、固定版本及确认状态，不逐日志行查PG或持久编排。

先交付既有 MANUAL_SAMPLE→LOG 的明确有界批次输出，沿用已发布固定工作流和最近只读RUN的输入摘要。每批最多5条、服务端24小时时间范围、当前授权及共享并发检查。其存储、回执和确认协议可被后续真实日志连接器复用；这一阶段不声称已接入外部日志来源或持续日志采集。实际来源连接器、连续批次/checkpoint、生产容量及保留期仍需继续。

增加独立 `log.read` / `log.write` 权限和固定工作流日志范围。写入需可信 source.sync、当前来源/工作流与log.write；正文读取另需log.read。命令的 tenant/subject/权限来自可信边界，输入正文仅为不可信文本。身份或权限变化须拒绝迟到结果，普通日志、数据库查询日志和回执不保留客户正文或凭据。

先在PG事务保存PENDING回执，再进行一次外部批写，精确回读原范围后才CONFIRMED。UUID与固定版本/输入摘要绑定，同键原内容返回原回执，不重写；不同内容409。响应丢失或部分未知保留UNKNOWN，确认只查询原batch，不自动重写或换键。仅当前进程内的一次受理会写；进程重建后的PENDING只能查询确认。公开回执不含样本或日志正文。

日志表以 tenant、owner_scope、request_id、row_index 等固定排序键使用 ReplacingMergeTree，查询显式FINAL，稳定行序号保留过滤缺口。无后台合并或HTTP200即可确认的假设，不宣称跨库exactly-once、多副本一致或物理掉电耐久性。查询语句固定，值经带类型参数传入；HTTP仅允许明确loopback地址、禁代理/重定向，有时限与正文上限；不接受客户端URL、SQL或表名。

Docker日志 profile需明确启用、loopback绑定、固定镜像digest及私有随机凭据，应用使用仅SELECT/INSERT角色。初始化DDL由开发部署步骤完成，应用不持有DDL权限、不自动创建库/用户。默认未配置时能力明确不可用，真实失败不回退Fixture。当前硬容量保留原回执，不以删历史绕过上限；日志保留/备份/删除治理仍需S7实现。

依据：[HTTP参数与确认限制](https://clickhouse.com/docs/concepts/features/interfaces/http)、[ReplacingMergeTree查询去重](https://clickhouse.com/docs/reference/engines/table-engines/mergetree-family/replacingmergetree)。正式依赖技术名与域名属于支持技术资料。

本机首次512MiB容器/384MiB服务上限导致实际写入MEMORY_LIMIT_EXCEEDED，回执保守保持UNKNOWN；没有补写。可选profile调整为1GiB/768MiB后，新批次完整确认。实际镜像版本25.8.33.6，受限账号DDL拒绝、PG受理重建和实际原文回读已验证；不能据此推断生产容量或失败批次为空。操作见[本机验收](../runbooks/workflow-log-output.md)，实际检查见验证报告§120。
