# 工作流条目与运行记录分页

唯一结构源：[条目页 Schema](schemas/v2/workflow-entry-page.schema.json)、[运行页 Schema](schemas/v2/workflow-run-page.schema.json)。示例[条目页](examples/v2/workflow-entry-page.json)与[运行页](examples/v2/workflow-run-page.json)均为Synthetic Fixture，摘要与时间是契约占位值，不代表真实发布或真实运行。

| API | 含义 |
|---|---|
| GET /api/v1/integrations/workflows/entries | 本人草稿与租户可读发布版本的有界游标分页；可选state=DRAFT\|PUBLISHED、workflowId过滤 |
| GET /api/v1/integrations/workflows/runs | 本人预览/版本测试运行回执摘要的有界游标分页 |

两接口都是只读：不保存、不预览、不发布、不执行，也不访问来源。tenant与身份来自可信认证边界，请求不接受tenant、owner、页尺寸以外的排序或时间参数；未知查询参数、重复参数、非法state/workflowId/after/limit一律400。limit为1..50、缺省20。

条目页顺序固定为（updatedAt降序，workflowId，revision），游标`<workflowId>:<revision>`；运行页顺序固定为（createdAt降序，runId降序），游标为运行回执UUID。`after`表示**严格续在其后**：新出现的条目不会落进后续页，续页不重叠、不乱序。游标形状非法400；形状合法但当前身份的保留窗口中不存在（条目已发布取代草稿、运行不可读等）时明确404，客户端清空选择并重新读取，不猜测位置、不退回首页。

每页返回`items`、`nextCursor`、`hasMore`且严格一致：`hasMore=true`当且仅当`nextCursor`非空且items非空，`hasMore=false`时`nextCursor`为null。空尾页是完整结果（items=[]、hasMore=false），不与"未读取"混淆；截断披露由`hasMore`承担，工作流目录响应原有的`truncated`标记保持不变。逐页覆盖的上限就是保留容量本身（50份活动草稿/用户、200份发布版本/租户、200份运行回执/用户），满额由保存/预览/发布侧显式报CAPACITY，本契约不新增删除或清理语义。

条目内容沿用[workflow-entry](schemas/v2/workflow-entry.schema.json)：不可变发布版本语义不变，布局不进入业务digest；运行摘要沿用既有`workflowId/revision/mode/receipt`形状，正文、样本与逐步输出仍不落历史。已记录检查历史（固定版本）继续使用[workflow-history](workflow-history.md)的加密游标契约，本契约不改变其快照、offset与数量语义。

版本差异（`POST .../comparisons`）与历史选择（固定版本历史读取）的选项来自这些分页：选项较旧时页面显示"读取更早版本"并披露是否还有更早条目；比较与历史读取仍需显式触发，打开抽屉、切换选项或翻页都不自动比较、不保存、不发布。

实现决策与发布门禁见[工作流比较](../docs/adr/069-workflow-version-comparisons.md)、[已记录检查历史](../docs/adr/097-scoped-recorded-workflow-history.md)与[工作流契约](workflows.md)。
