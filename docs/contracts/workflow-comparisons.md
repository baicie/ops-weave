# 工作流版本比较契约

契约唯一源：`contracts/schemas/v2/workflow-comparison-request.schema.json` 与 `workflow-comparison.schema.json`；对应examples/v2示例明确是Fixture。服务器路径：`POST /api/v1/integrations/workflows/comparisons`，响应no-store。

请求base/candidate各包含id、revision（1–10000）、state（DRAFT/PUBLISHED）、editVersion和完整sha256 digest；两者必须同一id。已发布editVersion=0，草稿1–1000000。没有定义正文、样本、来源地址、用户/租户字段或执行选项。服务器在同一事务读取授权历史和本人草稿，比较精确编辑号与digest；拒绝失配409、缺失404、越权403、缺认证401和非法query/闭合正文400。

响应schemaVersion=2.0、双方原引用、comparedAt和有界changes。每条固定section、nodeId、key、before、after五属性；NAME/SOURCE/TARGET/EDGE/ORDER的nodeId=null，NODE/OPERATOR/MAPPING/PARAMETER必须有合法节点ID。before/after可为null，含义是未设置；空字符串仍是存在的配置值。没有记录值、秘密、样本或回执正文。

最多1200变化，key最多96字符、值最多4096字符；服务端按节点/参数/连线稳定排序生成唯一变化标识。客户端除Schema边界外还核对双方引用与所选项精确一致、前后值不同及section/nodeId/key唯一，不接受伪造新编辑号、未知字段或重复行。

配置pin改变、模型pin改变、算子pin改变、映射、参数、节点和连线都可显示；不重新解析依赖或证明新版本兼容。不把revision、画布坐标和预览回执计作处理变化；布局保存仍改变编辑号，旧请求因此409。不同版本内容相同可返回空changes，比较也可选择同一保存版本。

打开抽屉/切换选项不请求比较。冲突后显式刷新最近版本选项并重新比较；选项消失置空，不自动换成最新。比较仅读取元数据，不写草稿/发布、增加运行历史、读上游或启用任务。实现决策见[ADR-069](../adr/069-workflow-version-comparisons.md)。
