# 固定版本已记录检查历史

唯一结构源：[Page Schema](schemas/v2/workflow-history-page.schema.json)、[私有Cursor Schema](schemas/v2/workflow-history-cursor.schema.json)。公开条目复用[原诊断观察](schemas/v2/workflow-diagnostic-observation.schema.json)，旧缺少sourceRead/dispatch仍保持缺失。私有Cursor只封装进加密令牌，不接受为请求JSON或HTTP响应。

`GET /api/v1/integrations/workflows/quality/workflows/{id}/versions/{revision}/history`首读只接受空查询；后续仅单个`cursor=h1.<base64url>`。固定版本与范围由可信身份决定，每页重新检查；请求不能设置tenant、owner、页尺寸、时间、序号、数量或输出目标。POST/PUT/PATCH/DELETE返回405及Allow: GET。

Page十个字段：schemaVersion、asOf、snapshotAt、reference、coverage、offset、recordedCount、items、nextCursor、hasMore。coverage固定RECORDED_CHECKS；数量0..200，offset为0..180的20倍数；条目数量恰为min(20,recordedCount-offset)，有剩余才允许令牌和hasMore。空历史只允许offset0和无游标，不能用空尾页冒充完整结果。

客户端/领域层还校验：规范UTC时间、snapshotAt≤asOf且间隔小于600秒；每项父版本一致、完成时间不晚于快照；完成时间降序、同时间UUID升序且无重复。后续页必须沿用快照/数量，offset连续，asOf不倒退，前后页顺序连续且不重叠。结构Schema无法单独证明这些跨字段及跨页关系。

第一请求保存插入水位；新插入及回填较早时间的记录不改变该快照。每次请求重读当前范围并验证序号/body/索引标量和原锚点。到期或选择数量变化409；未知/其他范围的有效形状令牌404；格式/多重/额外查询400；缺少认证401、当前权限拒绝403；损坏数据/部署密钥不可用503。失败不得回退空列表或自动重试。

示例[首20条](examples/v2/workflow-history-page.json)、[最后一条](examples/v2/workflow-history-final-page.json)、[内部选择](examples/v2/workflow-history-cursor.json)均为Synthetic Fixture；示例令牌不是有效加密令牌。数量仅覆盖已保存检查，转换通过与输出确认保持独立，未保存历史不可补回。

见[决策](../docs/adr/097-scoped-recorded-workflow-history.md)及[实际检查](../docs/VALIDATION-REPORT.md)第135节。
