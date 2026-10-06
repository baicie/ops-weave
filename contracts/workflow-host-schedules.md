# 周期主机采集契约

唯一 wire Schema 在 `schemas/v2/workflow-host-schedule*.schema.json`，示例为 `examples/v2/workflow-host-schedule-status.json`。

仅固定配置的已发布 ZABBIX_HOST→ENTITY 工作流支持周期采集。接口位于 `/api/v1/integrations/workflows/host-schedules`，拒绝所有查询参数；身份由认证边界提供，命令不接受tenant、权限、授权对象、来源行或任意地址。

| 请求 | 内容 |
| --- | --- |
| GET `/workflows/{id}` | 能力、周期记录和当前扫描任务；首次无周期时二者均为空 |
| POST `/start` | UUID requestId、id、revision、digest、settings、intervalSeconds、expectedGeneration |
| POST `/stop` | 原固定设置与预期周期代次；当前扫描停止后仍可取消下一轮 |
| POST `/resume` | 原版本、设置和间隔；明确恢复断点并重新授权 |
| GET `/commands/{requestId}` | 原始不可变受理快照，不替换当前状态 |

响应与状态封闭；客户端应拒绝额外字段、越界计数、未知错误及不匹配版本。扫描Task沿用运行契约，仅公开五字段授权摘要；周期记录和命令不含私有授权引用。重放同UUID和内容读取原结果；变更内容、过时代次或抢占当前周期返回409。原控制不存在返回404；未知响应不能自动换键重试。

`activeScanId`表示本轮；整轮确认后成为`accountedScanId`并只累计一次统计。`lastSuccessAt`是本轮确认完成时间；`nextRunAt`只在启用且等待下一轮时出现。`sessionBatches`成功或已尝试写入扣减，最多20；失败批次的原体及时间仍在私有扫描日志，不因扣减而推进游标。空健康扫描可以确认，但不删除资产。

开发能力限可信loopback环境。委托能力还需显式操作员许可、当前会话绑定和可撤销短时授权；最长15分钟和20批，自动调度共用原授权。关闭页面不停止服务器任务；停止按钮终止当前和后续扫描。多副本调度与长期委托未实现。
