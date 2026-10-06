# 完整日志窗口内部端口

固定ZABBIX_LOG来源、LOG目标及算子版本来自已发布计划。`RegisteredHostSourceReader.logWindow`使用已有受控地址和秘密端口，要求实例source.sync、对应Host entity.read及日志项log.read；不接受浏览器URL、正文、tenant或权限。

来源窗口为[from,till)，60秒，等待10秒；from不得早于当前24小时。总记录≤1,000、历史请求≤20、来源总耗时≤20秒，正式传输保留2MiB响应上限。clock/ns按升序且不重复，分页边界秒完整重读；单秒/全窗/字节/时间超限及读取失败整窗失败。只有两次固定项元数据匹配的健康空结果可作为空窗。来源七字段与[样本协议](workflow-log-sources.md)相同，正文不trim、不截断或填造上下文。

`WorkflowLogWindow.prepare`分组使用现有固定算子，最终批次一次验证：原输入索引0–999、独立来源position、canonical输出eventTime及规范LOG字段；输出时间在当前24小时内且不晚于现在。FILTER保持明确数量及索引空洞，任意REJECTED不能写成功前缀。来源与目的时间分开，原eventId不是全局去重键。输入摘要包含固定来源和连接摘要、窗口及完整原字段；输出摘要含可信范围、原批次UUID、窗口、数量和完整规范值，null与空文本区分。

`workflow-log-window-record`及`workflow-log-window-data`是服务端内部数据格式，无公开写命令或新前端客户端。Schema闭合字段、类型和数量；实际领域层另检查完整60秒窗口、settle、来源/计划/摘要、严格位置顺序、过滤守恒及时间预算。示例只为Synthetic Fixture，摘要不是已运行证明。

新`WorkflowLogWindowSink`一次POST完整非空批次、原范围SELECT回读，8MiB请求/响应与1,001行哨兵。固定列/表、typed query parameters和FINAL；body与秘密不进入SQL/普通日志。空输出不写存储。旧5条样本契约、摘要、表和字节预算保持。

V002在已有日志库建立专用窗口表，不自动执行DDL；部署账号建表，应用账号只有固定表SELECT/INSERT。未部署或结构/引擎不符时ready=false并在POST前拒绝。适配器不签授权、不持久证明、不自动确认任务或重试，持续任务、去重、迟到补采与确认后检查点仍待接线。见[ADR-085](../docs/adr/085-bounded-complete-log-window-ports.md)。
