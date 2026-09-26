# AI 内容留存契约

唯一业务 Schema：`schemas/v1/ai-retention-{policies,preview,apply,receipt}.schema.json`；样例是显式合成数据。控制台手写边界解析器不替代 Schema。授权来自可信 Principal，不接收 tenant/user/权限覆盖。配置文件只由服务端运维路径提供，HTTP 不接收路径或策略变更。

1. `GET /api/v1/ai/retention`：当前租户策略及 120 秒预览。每类最多 batchSize 条（1–100），顺序 INSIGHT/EVIDENCE/AUDIT，ID 按原始时间与 UUID 排序；hasMore 只表示探测到后续行，不表示完整剩余数量。logicalBytes 是待清理 JSON 的逻辑大小，审计记为 0，不是磁盘容量承诺。
2. `POST /api/v1/ai/retention/runs`：只接受 requestId、policyDigest、asOf、previewDigest；asOf 必须是 UTC 整秒。服务器重算同一截止、候选、摘要并复查配置，allowPurge 必须为 true。执行仅限预览批次，候选变化或参数冲突为 409，过期为 410。
3. `GET /api/v1/ai/retention/runs/{requestId}`：当前管理权限与原操作者绑定的持久回执，不会执行清理。原请求重发返回原回执；其他 actor 查询为 404，同 ID 不同参数/actor 提交为 409。未知 404 不是在途请求未执行的证明。

全部入口要求 `ai.retention.manage` 与 tenant-wide scope；已验证的服务 JWT 和 Runtime 委托没有这些入口。Cookie POST 继续要求原有 Origin/CSRF。未知 query、额外字段、重复 JSON 字段拒绝；响应 no-store、关联 UUID 与错误码沿用平台边界。未配置策略、无当前 tenant、文件损坏或非 PG 返回 503；不静默换内存。

策略按每租户 version、三类 days（1–3650）、batchSize、精确 heldIncidents、allowPurge 计算摘要。正文创建与过期均满足截止才清除；配置下调不覆盖有效读取期。保留列表针对存储行的原始 Incident ID；合并目标的保留不会隐式加入历史来源。审计关联的会话与证据任一个原始 Incident 被保留都跳过。关联未过期会话也跳过。

清除正文后 PK/唯一会话/外键与最小权限范围仍保留，旧对象在重新授权后 410；旧 run ID 不能再次触发模型。并非根因证明、模型费率核销或全部元数据清理。配置检查与外部文件变更的并发边界、备份限制见 ADR-034。

摘要均为 UTF-8 / SHA-256，前缀 `sha256:`。策略原文以换行连接：`ai-retention-policy-v1`、tenantId、version、insightDays、evidenceDays、auditDays、batchSize、allowPurge（小写）、heldIncidents（排序，每 ID 后换行）。预览原文为 `ai-retention-preview-v1\n`，依次 tenantId、actor、policyDigest、asOf，每项后换行；每类追加 `kind:logicalBytes:hasMore\n`，再按候选顺序逐 ID 加换行。数字用十进制无前导零。回执不重新计算费用、不保存被清除的正文。
