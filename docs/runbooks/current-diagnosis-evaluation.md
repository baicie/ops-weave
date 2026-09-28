# 当前诊断评估与人工对照

M4需要小型标注集、确定性/模型摘要对照和人工抽查。本入口补齐可重复的合成流程评估与确定性摘要材料；它不调用真实
模型，也不把脚本输出当成模型质量证据。真实模型价值与人工审阅仍是未满足的退出条件。

## 运行

在仓库根目录执行 node scripts/check_diagnosis_evals.mjs；需要all-features编译路径时加 --all-features。
脚本运行一个Cargo语料测试，在新的.tmp/current-diagnosis-eval/时间戳随机目录生成report.json和review.md。
只有测试成功、语料digest与固定Skill引用吻合时才生成审阅文档，不复用旧成功报告。未知参数拒绝。

常规cargo test --workspace --locked和cargo test --workspace --all-features --locked也自动执行该语料；可设置
绝对路径OPSWEAVE_EVAL_REPORT保存合成报告，否则不输出文件。测试遵守已发布技能的四次读取、单次模型步骤及20秒模型
截止时间；Tokio虚拟时间只用于测试超时，不改变生产时钟/预算。测试模型调用计数表示模拟步骤，不是外部提供方请求数。

## 案例与机器检查

| 案例 | 机器检查 | 仍需人工审阅 |
|---|---|---|
| normal | 两份结构化证据保留、固定Skill、缺失披露、四次读取后保存 | 每句话的事实支持与是否增加可核验信息 |
| missing-metrics | NO_DATA原样进入上下文，模型删掉的缺失披露被恢复 | 不把缺数据说成零值或正常 |
| conflicting-observations | ACTIVE告警和低值指标同时保留，无过滤另一方 | 不擅自裁定误报/恢复，指出可核对的时间/阈值差异 |
| expired-at-access | 访问时已过期，模型和保存调用均为0 | 真实环境时间与证据保留策略是否正确 |
| resource-denied | 无Incident资源权限，在数据读取/模型前拒绝 | 目标环境主体与对象授权配置 |
| untrusted-injection | 恶意来源标题保留在不可信字段；脚本试图加actions被拒绝、不保存 | 真实模型是否抵抗注入、是否伪造事实；未声称接入真实日志 |
| model-timeout | 实际工作流20秒超时，无重试/回退/保存 | 真实提供方延迟和超时后的费用核对 |
| invalid-json | 非法JSON拒绝，费用报告发生但结果不保存 | 提供方实际输出约束和收费语义 |
| invented-reference | Schema合法但引用不属于本会话，拒绝保存 | 引用存在之外的事实支持/因果关系 |
| revoked-after-model | 模型后权限重查拒绝，旧结果不保存 | 真实身份撤权传播边界 |

读取、许可和保存端口均为显式内存fixture，成功的模拟结果会标注savedResultStorage=memory，不是PG持久性验证。PG/VM/真实HTTP/浏览器
整链的实际证据另见验证报告第60节。这里不启动PG，不访问外部Zabbix、IdP或模型，不给测试进程创建额外执行工具。

## 审阅纪律

report.json包含同一合成输入、既有MockModel生成的确定性结果、脚本边界结果、调用/保存计数及技能/语料摘要。
review.md展示候选事实、禁止推断、确定性结果与人工问题。以下字段不能自动升级：

- actualProviderCalls固定为0；realModelComparison为not-run。
- humanReview为pending；rootCauseAccuracy为null。
- allMachineChecksPassed只表示工作流及引用完整性检查，不是事实质量、RCA准确率或真实MVP签署。

真实模型评估必须另走可信平台会话和费用许可，保留相同输入版本/Skill/模型标识、实际usage及证据；不能绕过平台预算
直接将本脚本改成任意HTTP模型调用。逐案记录模型新增的可核验信息、错误事实、遗漏/冲突处理、假设边界、延迟/费用和
审阅人。越权/过期案例应保留失败，无权看到的内容不交给模型。真实提供方配置未具备时，该对照保持未执行。

语料中的referenceClock是合成重放基准；时间平移在报告中公开，不能拿平移后的样本冒充真实采集或用于真实验收报告。
原extensions/skills/incident-summary/evals/cases.jsonl是旧Demo的三条标签，本入口不覆盖它，也不把它视为当前M4已完成评估。
