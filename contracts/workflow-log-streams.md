# 持续日志窗口契约

`/api/v1/integrations/workflows/log-streams` 只执行已发布的固定 `ZABBIX_LOG → LOG` 版本。来源实例、配置版本、发现日志项和算子版本由工作流固定；请求不接受正文、来源 URL、凭据、tenant、权限或任意执行参数。

- GET `/workflows/{id}`：当前身份自己的任务和最近 20 批证明，使用 [status Schema](schemas/v2/workflow-log-stream-status.schema.json)。
- POST `/start`、`/stop`、`/resume`：[控制请求](schemas/v2/workflow-log-stream-command.schema.json)。操作由路径确定，原 UUID 的回执绑定 `log-stream-control-v1`、操作、版本与 expectedGeneration；重读原回执不续期。GET `/commands/{requestId}` 返回 [原控制快照](schemas/v2/workflow-log-stream-receipt.schema.json)。
- POST `/workflows/{id}/verification`：[只核验原批次](schemas/v2/workflow-log-stream-verification.schema.json)。不重新读取来源、不重发输出、不发新授权。
- GET `/workflows/{id}/batches/{batchId}/records` 与 `/records/after/{afterIndex}`：[每页最多 50 条日志](schemas/v2/workflow-log-stream-data.schema.json)。afterIndex 必须是原证明中的输出索引，第一页为 -1，路径分页必须非负；不接受查询参数。每次读取校验整个原批次，complete 表示全批次匹配，不表示当前页包括全部记录。nextIndex 是本页最后索引，仅在还有记录时返回。

窗口为整秒、60 秒半开范围，延后 10 秒，最多 1000 条/20 次来源请求/20 秒来源预算。元数据漂移、来源失败、不完整或超限均不返回成功前缀。健康空窗口记录确认范围而不发输出 POST。批次只在完整输入校验后一次写出，独立完整回读摘要一致才推进 checkpoint；UNKNOWN 保留原 UUID、位置、输入/输出摘要和进度，禁止隐式重试或跳过。原批次匹配后任务保持停止，再明确恢复。

下一窗口稳定后回看上一已确认分钟。原所有采集位置（包括被过滤的记录）及原始输入摘要必须保持；旧记录缺失或变化暂停。仅新增且通过算子的采集位置写入新补采批次，旧接受记录计入 deduplicated。新记录只有被过滤时仍保存补采证明但不写正文。确认补采增加记录数，不推进窗口或 checkpoint。下一次执行再处理新窗口。确认依据按 tenant/owner/固定版本/窗口专门查询，不受页面最近20批显示范围影响。更早迟到或来源历史改写尚需受控修复。

[批次证明](schemas/v2/workflow-log-stream-batch.schema.json)保留全输入 positions、接受 indices、filtered/deduplicated 和摘要，不存正文、归一化值或密钥。positions 为规范 UTC Instant，严格按纳秒升序，indices 为原输入索引且允许空洞。事件时间与采集位置是独立字段。浏览器使用纳秒整数校验，正文以纯文本呈现。

可信会话独立检查 source.sync、来源对象范围、log.write/log.read。PG tenant/owner 隔离和固定输出作用域不可由请求替换。开发模式只在 loopback/PG/显式日志存储配置启用，非开发后台授权最多 15 分钟/20 批，批前重新解析当前可信授权；公开任务仅含五字段授权摘要，私有主体和 grant 摘要只进服务器证明。一个 owner 最多 20 任务、200 批证明，控制回执上限 200 且预留 20 个停止位置；共享输出并发为 2，无隐藏重试。

[任务存储](schemas/v2/workflow-log-stream-task-storage.schema.json)和[控制存储](schemas/v2/workflow-log-stream-receipt-storage.schema.json)对应既有 PostgreSQL 的 V048；正文使用原日志库 V002 的固定窗口表。当前执行器支持本机停止串行化与重开恢复，不构成分布式 fencing、生产 HA 或掉电耐久性验收。

## 写入后回读失败与旧记录

成功写入后任何回读失败都保持UNKNOWN/OUTPUT_UNCONFIRMED，不推进检查点、不变为明确拒绝。仅明确未写入的FAILED可以恢复。私有rejection_known默认false保留旧结果体，当前执行器在已证实拒绝的收尾事务中记录true；旧FAILED不凭历史标签获得恢复/版本替换能力。按版本状态可含resumeAllowed布尔字段，未知或非法类型失败，缺失不由客户端补造权限；服务器始终再查原固定批次和证据。历史不确定拒绝的完整质量投影/显式处置仍在后续范围。

旧结果观察（§130）：按版本状态可带 uncertainBatchIds，闭合规范小写UUID数组，最多20个、唯一且全部引用本响应中的 FAILED 批次。它标记缺少明确未写入证据的原结果，不改变 batches 的原状态、digest、位置或更新时间，也不是当前日志读取结果。关联任务的 resumeAllowed 必须false；未知身份或新增字段不能授予写入权限。前端显示结果待确认，保留日志查看，原 FAILED 不可调用 verification。质量报告独立投影 UNKNOWN；终止恢复复用原UUID协议，完整回读与终止均不将该原证明改为CONFIRMED。
