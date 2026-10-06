# 工作流来源读取诊断

这是现有只读诊断协议的可选扩展。唯一 wire schema 在 `schemas/v2/workflow-diagnostic-source.schema.json`，通过 Observation 的可选 `sourceRead` 引用。来源端口调用统计、记录转换统计及输出确认统计有不同的分母。

| 字段 | 约束与含义 |
| --- | --- |
| attempts | 整数 1，一次完整、有界来源端口调用；不是 HTTP 页数 |
| completed / failed | 各为整数 0 或 1，之和必须为 attempts |
| received | 成功时完整返回记录数量；失败时 null，不能补成 0 |
| failureCode | 成功时 null；失败时为闭合枚举 |
| startedAt / completedAt | 实测 UTC 时间，完成不得早于开始，并处于 Observation 时间内 |

成功返回的上限为主机分页 5、指标窗口 600、日志窗口 1,000。若转换分母已知，它必须与完整返回数量相同。主机私有实体范围见证的数量也必须相同。元数据或权限变化可能发生在来源成功返回之后；此时来源成功仍保留，转换统计按实际可用性保存，不倒推来源失败。

| 来源失败原因 | Observation.error |
| --- | --- |
| UNAVAILABLE | SOURCE_UNAVAILABLE |
| ACCESS_DENIED | FORBIDDEN、AUTHORIZATION_EXPIRED 或 AUTHORIZATION_REVOKED |
| INCOMPLETE_WINDOW | WINDOW_INCOMPLETE |
| INVALID_RESPONSE | INVALID_SAMPLE |
| METADATA_CHANGED、SOURCE_KEY_CHANGED、VALUE_TYPE_CHANGED、UNIT_CHANGED | SOURCE_CHANGED |
| OTHER_FAILURE | 原有闭合运行异常代码，不保存原异常正文 |

来源失败要求 Observation.state=SOURCE_FAILED，转换结果为 UNAVAILABLE、数量为 null。指标键、值类型及单位变化不适用于 HOST_SCAN。来源成功也可能伴随后续转换拒绝；例如完整返回 10 条、仅检查 5 条后首错中断时，来源失败为 0/1，转换覆盖为 PARTIAL，另外 5 条仍为未知。

旧记录缺少或为 null 的 sourceRead 表示未实测。旧公开 JSON 保持 13 字段并省略该字段，历史元数据不回填、不重写。新对象只允许表中的七个字段；tenant、权限、样本、端点和异常正文均拒绝。领域、存储解码与 Web 还校验相对时间、数量和父观察一致性；JSON Schema 不支持的跨字段算术不能省略服务器检查。

读取沿用现有诊断报告和 UUID GET，不增加采集、核验或写入入口。本人/tenant、固定发布 ref、当前来源/目标权限与主机实体范围均在服务器复核。最近 20 条展示不等于全部运行历史，truncated 必须保留。损坏元数据失败，不当作空成功。

sampleRate 仍为 null；queueWaitMillis 仅在有[实测本地调度证据](workflow-dispatch-diagnostics.md)时有数值。窗口迟到、端口读取耗时、采样率、排队时长各有不同定义，不互相替代。新增来源统计不改变原输出成功、拒绝、待确认、游标或终止事件。

示例：[来源成功](examples/v2/workflow-diagnostic-source.json)、[带来源统计的存储观察](examples/v2/workflow-diagnostic-source-stored.json)。见 [ADR-094](../docs/adr/094-measured-source-port-diagnostics.md)与[运行说明](../docs/runbooks/workflow-source-diagnostics.md)。
