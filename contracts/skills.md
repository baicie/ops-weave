# Skill 发布登记协议

本协议描述 Java 平台已有登记接口的闭合 JSON，权威结构位于 `schemas/v2/skill-*.schema.json`，合成样例位于 `examples/v2/skill-*.json`，路由登记于 [OpenAPI](openapi/platform-draft.yaml)。架构决策见 [ADR-105](../docs/adr/105-controlled-skill-release-registry.md)。本协议不表示 M6 已验收。

## 身份与能力边界

租户、主体、权限及对象范围来自当前可信 Principal。请求和公开响应没有 tenant、author、reviewer 或 permissions 字段；公开 review 只含 `approvedAt`，可信作者及发布者另存服务器元数据。`skill.read` 必须同时通过当前 Skill 对象授权；登记草稿列表要求整个租户或显式 `skill: *` 范围，精确单 Skill 读权限不允许获取全租户列表。写操作要求当前 `skill.manage` 及对应对象范围。

登记不执行 Skill、模型、工具、通知或动作，不扩展执行器权限或预算。当前 AIRun 仍只接受已有打包 Skill 的固定 id/version/digest；登记 release pointer 不激活新配置，不替换打包 Skill。运行时消费登记配置、停用、完整评估/比较和 Builder 属后续工作。

## 接口

| 方法与路径 | 输入 | 输出 |
|---|---|---|
| `POST /api/v1/skills/drafts` | `skill-save-command` | `skill-save-receipt` |
| `GET /api/v1/skills/drafts` | 可选 `limit`、`after` | `skill-draft-page` |
| `GET /api/v1/skills/{draftId}/check` | UUID 路径 | `skill-check` |
| `POST /api/v1/skills/drafts/{draftId}/reviews` | `skill-review-command` | `skill-review-receipt` |
| `POST /api/v1/skills/drafts/{draftId}/publications` | `skill-publish-command` | `skill-publish-receipt` |
| `POST /api/v1/skills/{skillId}/rollbacks` | `skill-rollback-command` | `skill-rollback-receipt` |
| `GET /api/v1/skills/{skillId}/versions/{version}` | 固定坐标 | `skill-version` |
| `GET /api/v1/skills/{skillId}/versions` | 可选 `limit`、`after` | `skill-version-page` |
| `GET /api/v1/skills/{skillId}/release` | 固定 Skill | `skill-release-pointer` |

除两个分页接口外，任何查询字符串均拒绝。分页只接受一次 `limit` 和一次 `after`，页大小默认 20、范围 1..50。草稿按 `createdAt DESC, draftId DESC`，版本按 `publishedAt DESC, version DESC`；不是语义版本大小排序。游标是最后一条实际返回记录的 UUID / version，内部第 `limit+1` 条仅判定 `hasMore`，不返回。`hasMore` 与非空 `nextCursor` 配对，空尾页是完整空列表。游标没有快照、签名或授权含义；服务器在当前租户及固定 Skill 中重新定位锚点，按完整时间/标识排序元组严格续读。未知、其他租户或其他 Skill 的游标返回 404，不猜测位置或重置到第一页。没有独立按 draftId 读取草稿的 HTTP 端点。

## 生命周期与幂等

保存体固定为 `{requestId, skillId, version, editVersion, content}`。`editVersion=1` 建立新坐标并返回服务器草稿 UUID；后续保存以现有 editVersion 做 CAS，成功后加一。`IN_REVIEW` 可通过保存明确撤回为 `DRAFT`，原作者不变；当前其他有权管理者也可编辑，尚无逐修订贡献者分离规则。

审核提交和发布体均只有 `requestId`，draftId 来自路径。审核提交把 `DRAFT` 变为 `IN_REVIEW`，本步不执行结构检查。发布是批准行为：必须仍为 `IN_REVIEW`、确定性检查通过，且当前发布主体不同于草稿最初作者。发布追加固定内容版本、把草稿变为 `PUBLISHED` 并移动登记指针；任一步拒绝都不能移动指针。`incident.diagnose@1.0.0`、`incident.diagnose@2.0.0` 和 `incident.summary@1.0.0` 是保留打包坐标，不能登记覆盖。

发布版本没有业务更新/删除路径，固定 id/version/contentDigest；撤回审核、发布新版本和回退都不改原版本。回退体 `{requestId, toVersion, reason}` 只把指针移到另一个已发布版本，不要求目标早于当前版本，也不要求 SemVer 递减；理由非全空白，最多 256 个字符。运行已有 pin 不随指针漂移。

四类命令共享租户/requestId 命名空间；同键同种类同内容返回原不可变回执，不再保存/发布/移动指针。同键异种类或异内容返回 `409 SKILL_REQUEST_REUSED`。当前回执不绑定发起主体，另一名当前获权管理者也可重放同租户原命令；这不授予新权限。结果未知时保留原 UUID 和完整命令，在当前身份下明确原样重发来取得回执，不自动换键或重试。现有接口没有按 requestId 独立 GET 回执。

## 内联配置与确定性检查

`skill-content` 只接受内联 prompt、JSON 文本 outputSchema、固定模板、只读 Tool pin 白名单、固定 knowledge/model policy、预算和 1..20 个测试案例。常量 `knowledgeScope=current`、`modelPolicyRef=reasoning-structured-readonly`、`sideEffects=forbidden` 不允许作者替换。Tool pin 不授予工具调用权限，预算不形成独立预算池。

| executionTemplate | Tool pin 子集 | tool calls / evidence / context bytes / deadline ms 上限 |
|---|---|---|
| `readonly-current-incident-diagnosis-v1` | `incident.get@2.0.0`、`metric.summary@2.0.0`、`evidence.get@2.0.0` | 4 / 2 / 65536 / 55000 |
| `readonly-incident-diagnosis-v1` | `incident.get@1.0.0`、`metric.summary@1.0.0` | 8 / 32 / 65536 / 60000 |

外层 Shape 允许暂存超模板预算或不同合法格式的 Tool pin，确定性检查再拒绝。Schema 正例不等于可发布；时间先后、CAS、摘要一致、唯一案例 ID、当前权限、保留坐标及模板适配仍由应用校验。Java 对 outputSchema / expectedOutput 的长度使用 UTF-16 单元计数，比 JSON Schema 的 Unicode 字符计数更严格；持久正文另受既有表的 128 KiB 限制。

检查不调用模型。它筛查模板/预算/工具、有限凭据标记和 URL 协议标记，拒绝非本地 `$ref` / `$dynamicRef`；不解析路径、不下载 Schema。协议标记筛查不是完整秘密扫描或文件路径验证，也不能代替受控加载器和权限。

outputSchema 必须能解析为 JSON 对象；声明的顶层 type 若为字符串则必须是 object。当前检查只检验期望 JSON 对象的顶层 required、已声明 properties 和基本 type，未声明 properties 时不执行未知字段约束；不完整执行 JSON Schema 的嵌套结构、枚举、数值/字符串约束、组合或本地引用求值。测试 question 不进入任何模型。`result=PASSED` 只表示这些有限检查通过，`modelComparison` 永远是 `not-run`，不证明输出质量、真实根因、运行兼容性或模型安全性。

## 内容摘要

登记内容摘要为 `sha256:` + 小写十六进制；SHA-256 输入依次为每个 part 的 UTF-8 字节长度（8 字节无符号大端）及字节。parts 顺序：`opsweave-skill-content-v1`、executionTemplate、prompt、outputSchema、逗号连接的 allowedTools（原顺序）、knowledgeScope、modelPolicyRef、sideEffects、四个预算的十进制文本（maxToolCalls/maxEvidence/maxContextBytes/deadlineMs），然后每个测试的 id/question/expectedOutput（原顺序）。请求 UUID、坐标、身份、时间不进入内容摘要；空格和顺序变化可改变摘要。

此算法借用打包加载器的长度前缀方式，输入组成不同，登记 contentDigest **不能**冒充 Runtime 打包内容 digest。当前不能直接把登记版本摘要交给 AIRun 来启用新 Skill。

## 错误与证据

HTTP 400：闭合字段/格式/查询错误；401：未认证；403：当前权限、对象范围或作者发布者分离拒绝；404：当前租户找不到记录；503：存储不可用，无 Fixture fallback。HTTP 409 使用闭合 `{error}`，仅 `SKILL_REQUEST_REUSED`、`SKILL_STATE_CONFLICT`、`SKILL_VERSION_IMMUTABLE`、`SKILL_COORDINATE_RESERVED`、`SKILL_CHECK_FAILED`。

样例明确是 Synthetic Fixture。契约验证只证明结构正反例，不能替代 Java 领域、真实 PG/HTTP 并发/生命周期、真实模型、生产身份、浏览器和人工验收；实际运行结果由验证报告记录。
