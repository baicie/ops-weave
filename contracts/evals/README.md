# 当前知识诊断合成评估语料

current-diagnosis.json由contracts/schemas/v1/current-diagnosis-eval.schema.json定义，内部复用当前Context、Evidence、
Incident/Metric数据契约。10个案例覆盖ROADMAP M4的正常、缺失、冲突、过期、越权、注入性文本、模型超时、非法输出八类。

所有来源为labeled-fixture，模型响应为手写脚本；标注是待人工审阅的候选标签，不是已获人工认可的根因真值。
没有客户日志、模型密钥或可执行端点。恶意日志式文本放在告警标题中，用来检查来源数据边界；真实日志尚未接入，
LOGS_NOT_CONNECTED始终保留。

语料钉住现有incident.diagnose@2.0.0及包digest，不修改extensions中的已发布技能。需要改技能时另行发布版本并审查
语料迁移，不能覆盖原包来让检查通过。案件中固定的时间只用于合成重放：测试统一平移所有时间戳，保留窗口、相对时差和
过期关系；同一个确定性/脚本对照对使用相同输入，不拿旧时间戳假称刚采集的真实数据。

执行与边界见[评估说明](../../docs/runbooks/current-diagnosis-evaluation.md)。测试报告在显式指定的忽略目录内生成，
没有真实模型调用、费用或人工结论时必须保持not-run/pending/null，不能由边界测试的成功自动签署M4。
