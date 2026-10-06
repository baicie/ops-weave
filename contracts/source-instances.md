# 接入实例维护契约

实例维护使用`/api/v2/data-sources`，契约唯一源为`schemas/v2/source-instance*.schema.json`。来源创建回执仍保留v1接口与原始内容；维护不覆盖创建回执、已发布工作流或任务版本。

| 接口 | 行为 |
|---|---|
| GET 根路径 | 本人有权访问的最近20份实例，storage与truncated明确返回 |
| POST 根路径 | 以requestId确认初始实例；来源仅为平台登记的连接或手工样本，成功响应是初版快照 |
| GET /{id} | 当前维护状态；旧创建记录只读投影为初版 |
| PATCH /{id} | 显式CAS命令，成功返回原始command receipt |
| GET /{id}/configurations | 最多100份只追加配置版本，包含配置摘要、来源模式与创建时间 |
| GET /{id}/commands/{requestId} | 查询该实例的原始维护回执；不执行第二次修改 |

所有路径拒绝未知查询参数；请求与响应字段封闭。身份、租户与授权取自可信Principal，不接收浏览器tenant/user/权限。来源种类与物理来源ID创建后固定。新创建ID在租户内不得与另一个主体的创建记录重用；冲突不返回他人记录。实例及维护回执读取仍限制本人范围。

名称trim后1–80字符，说明最多500字符。expectedEditVersion为1–1000；实例配置版本为1–100，配置版本不得超过editVersion。工作流ID恒为source-{id}，时间有序；来源模式必须与来源种类一致。配置摘要使用sha256格式，不含密钥正文。

名称、说明或归档状态修改只增加editVersion。连接摘要改变时，服务端重新校验当前可信部署配置；只有显式保存才增加configurationRevision。每一配置版本固定保存摘要、模式与时间；读取核对版本完整性和当前引用，损坏时失败，不回退初版。历史只追加。当前实例连接仍引用部署环境凭据；独立[版本化凭据管理](source-credentials.md)尚未与该连接绑定，不能把env引用当成固定秘密版本。

维护命令摘要由UTF-8字节长度前缀的有序字段计算：source-instance-edit-v2、sourceId、requestId、expectedEditVersion、name、description、connectionDigest、state。相同主体/requestId/内容返回原回执；同键不同内容或过期editVersion返回409。客户端必须核对sourceId、原命令摘要与结果，不用当前GET代替原回执；网络失败或结果不明保留命令，不自动换键重试。

归档保留配置与历史，阻止继续维护内容，恢复归档为显式CAS。当前主体对应工作流有RUNNING任务时拒绝归档。归档不隐式停止其他任务、删除发布版本或撤销物理来源权限。任务绑定和实际连接测试/字段发现由相应执行契约定义，本接口不触发网络探测、采集、发布或运行。

容量：每主体200份创建记录、200份维护命令，实例最多100配置版本与1000次editVersion。容量满则拒绝，不自动清理证据。持久化沿用platform/integration事务及V035迁移，配置版本/命令回执运行角色仅SELECT/INSERT。真实PG失败不得回退memory；storage与Fixture来源模式明确展示。
