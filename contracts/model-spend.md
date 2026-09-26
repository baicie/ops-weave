# 模型用量及费用准入 v1

唯一 wire 定义为 `schemas/v1/model-spend-{policy,usage,reserve,report,record,result}.schema.json`；OpenAPI 列出三条固定接口。金额单位 USD micro，所有数量为非负安全整数，服务端整数计算、Web 用 BigInt 复核乘积与进位。示例费率只用于测试，不是当前市场报价。

预留请求只含 runId/sessionId/inputDigest/inputBytes，不接收 tenant/subject/model/rates/budget。可信 Runtime 同时携带用户凭据（dev 或有界 OIDC 委托）与进程证明；不是模型 Tool。已有 run 永远不能重新得到许可。平台记录的 policy 是当次原始配置快照。

`RESERVED` 与 `UNCERTAIN` 的 usage/reportedAt/estimatedMicros 必须为 null，accountedMicros 等于 reservedMicros。UNCERTAIN 表示 deadline 已到仍未收到可信用量，并不证明调用失败或费用为零。已确认金额按预留发生的 UTC 日归属，未确认金额跨日继续占用直到有效报告；没有隐式退款。`REPORTED` 必须有用量、上报时间和金额；日期、费用公式、当前身份/资源和快照匹配由执行器补充校验，JSON Schema 不代替这些检查。

`provider-reported` 要求输入 Token 大于零、输出 0–2048、缓存输入不超过输入；输入最多81920。缓存按输入全费率估算。mock 必须固定 `mock-deterministic/mock-current-v1/mock-no-charge` 和全零金额/Token，usage.source=`mock-no-call`。未知或无效回包不能生成零费用回执。

一条报告用量不能改写；相同报告幂等返回原时间/原费率回执。OIDC 委托额外限制一次 report，客户端不会自动重试。读取支持诊断失败后的独立费用核对，但仍要求原主体及当前诊断/Incident/关联实体权限，不暴露其他主体的记录。HTTP 404 只表示当前身份查不到记录。

预留是配置费率估算而非外部计费保证；价格、输入 Token 上界假设与未提供的核销/对账/留存见 [ADR-033](../docs/adr/033-model-spend-admission.md)。日志不保存输入正文或提供方响应。Schema 不触发网络下载。
