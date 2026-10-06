# 工作流算子目录与固定版本

唯一源：[目录](catalog/opsweave-transform-operators-1.0.0.json)，闭合响应：[Schema](schemas/v2/workflow-operator-catalog.schema.json)。GET `/api/v1/integrations/workflows` 在原可信身份、工作流/模型权限和`no-store`边界内附带`operatorCatalog`，无新执行API。字段包含`schemaVersion=2.0`、`catalogVersion=1.0.0`、`digest`和11个内置算子；公开参数和语义，不含秘密、任意执行代码或远程位置。

算子摘要按如下有序parts计算，每项编码为ASCII UTF-8字节长度+冒号+UTF-8正文后接SHA-256：

1. `opsweave-operator-v1`、type、version、implementation、configKind、参数个数。
2. 按目录顺序的每个parameter.name、parameter.kind。
3. `input`后接`none`，或port.id、port.kind、minimum、maximum；`output`同理。

目录摘要parts为`opsweave-operator-catalog-v1`、catalogVersion，随后按type/version排序的type、version、算子digest。显示文字和分组不参与执行摘要；固定实现标识是声明的内置语义版本，不能当作实际构建产物的哈希。

Definition节点可添加`operatorDigest: sha256:<64个小写十六进制>`。有pin时，原摘要节点的id/type/version/config.size之后、排序config键值之前插入`operator-pin-v2`和operatorDigest；无pin节点维持原摘要算法。Node闭合字段拒绝额外执行参数，版本1配置Schema由目录生成；业务约束（字段唯一目标、保留字段、数值/记录预算、图结构）仍在Java执行前验证。

新发布要求所有节点pin匹配静态注册实现，未固定返回409 `OPERATOR_PIN_REQUIRED`，不匹配返回409 `OPERATOR_CHANGED`。保存/预览/运行在有pin不匹配时同样拒绝，来源读取前拒绝。历史GET保持可读，旧pinless已发布定义仍按版本1兼容执行；不改写不可变版本或确认回执。新草稿升级改变Definition摘要，不能复用升级前的预览。

开发同步：`python scripts/generate_workflow_operators.py --write`；校验：`python scripts/generate_workflow_operators.py --check`。生成文件禁止手改，CI仅校验、不自动修复/升级；正式依赖与四个启动单元不变。

[固定定义示例](examples/v2/workflow-pinned-definition.json)由生成器维护，沿用原Fixture模型摘要占位值；实际执行需授权模型目录的固定摘要，不能把样例标为已连接。
