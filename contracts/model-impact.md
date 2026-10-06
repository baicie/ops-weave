# 模型版本检查与引用契约 1.0

所有身份均来自既有可信平台边界。接口仅报告元数据，不读上游、不执行工作流、不写业务输出，不接受 tenant、subject、权限、原记录或授权覆盖。响应 `Cache-Control: no-store`。

| 接口 | 请求 | 响应 |
|---|---|---|
| POST `/api/v1/catalog/revisions/review` | [model-revision-review-request](schemas/v1/model-revision-review-request.schema.json)：custom 模型 ref、expectedEditVersion、digest | [model-revision-review](schemas/v1/model-revision-review.schema.json)：固定候选/基准 pin、差异和兼容性 |
| GET `/api/v1/catalog/versions/{id}/{revision}/references` | 明确 builtin/custom 模型版本；不接受查询参数 | [model-references](schemas/v1/model-references.schema.json)：固定目标 pin、当前授权引用、检查时间及截断 |

检查候选必须是本人已保存、未发布的草稿；编辑版本和摘要都匹配。新类型只允许首版，后续接续最新发布版本；当前只允许追加可选字段和修改显示文案。关系端点需要已发布实体类型。兼容检查不授予权限，发布时在原租户事务重新验证。

引用最多50项，仅包括已发布关系类型、可读取工作流版本和本人私有草稿。关系 `roles` 为 FROM/TO，工作流固定 OUTPUT；`fieldIds` 为目标模型字段，显示全称时使用 `target.id + '.' + fieldId`。任务仅含自己的固定工作流版本状态/代数，最多扫描与周期各一项。模型 revision 上限10000，工作流 revision 上限1000000，task generation 上限1000001。

无工作流资格时 `workflowsAvailable=false`，仍可展示可读取关系类型；这不代表全租户零引用。`truncated=true` 必须保留。消费者核对目标 ID/revision/digest、候选编辑版本、状态、种类、字段和数组边界，不把原报告视为迁移计划或当前执行授权。底层失败不得返回成功空引用。

错误：401 身份失效；403 范围/权限不足；404 固定定义或自己的候选不存在；409 编辑/摘要/发布状态冲突；400 不符合封闭请求或带查询参数；503 底层目录/引用不可读取。响应只保留错误码，不能回显草稿、原数据或凭据。

样例明确为 Fixture：[检查](examples/model-revision-review.json)、[引用](examples/model-references.json)。运行与边界见 [ADR-081](../docs/adr/081-model-revision-review-and-fixed-references.md)。
