# 接入凭据 v2

契约唯一源为`contracts/schemas/v2/source-credential*.schema.json`。样例中的根外凭据为显式合成Fixture，只供开发校验，不用于外部认证。Java领域聚合、HTTP适配器与TypeScript请求容器按同一封闭Schema实现；内部密文封装与命令HMAC不是公开契约。

| 接口 `/api/v2/data-sources/credentials` | 行为 |
|---|---|
| GET 根路径 | 当前主体最近20份元数据；明确返回截断、密钥可用、可信写权限与安全传输能力 |
| POST 根路径 | `{requestId,name,secret}`，以requestId创建初始固定版本 |
| GET /{id} | 本人当前元数据，始终不回显秘密 |
| PATCH /{id} | `{requestId,expectedEditVersion,name,state,secret}`；secret为null只改元数据，非null追加版本 |
| GET /{id}/versions | 最多100个固定pin、创建时间与撤销标记，不返回秘密、密文或内部认证摘要 |
| POST /{id}/versions/{revision}/revoke | `{requestId,expectedEditVersion}`，显式撤销一个版本并保留历史 |
| GET /{id}/commands/{requestId} | 原公开回执，不修改或再次执行命令 |

写入要求可信`source.configure`和credential对象范围；元数据读取允许`source.sync`或`source.configure`，解封装要求`source.sync`。所有行再限制tenant/owner，不接受浏览器tenant/user/权限。source.sync不授予配置能力；配置能力也不单独授予执行解封装。路径UUID为完整小写规范格式，拒绝查询参数、重复键、尾随正文和额外字段。请求最多64KiB，秘密为1–4096个可见ASCII字符；名称1–80字符，不能含控制字符或首尾空白。

公开元数据包含`id/name/revision/versionId/editVersion/state/createdAt/updatedAt`。状态只有ACTIVE与REVOKED；整体撤销为终态，不提供恢复和秘密GET。元数据修改只增加editVersion，轮换增加revision并把versionId固定为原requestId；旧版本不被覆盖。指定版本撤销仍保留整体ACTIVE和当前pin，解封装必须检查具体版本。引用是否可读取由服务端判断，ACTIVE或公开历史标记不是授权证明。

相同主体/原requestId/内容返回原回执；同键不同秘密用内部HMAC判断，409拒绝。公开命令摘要不包含秘密或其摘要，按UTF-8字节长度前缀连接：写入为`source-credential-write-v2/id/requestId/CREATE|EDIT/expectedEditVersion/name/state/changesSecret`，指定版本撤销为`source-credential-revoke-version-v2/id/requestId/expectedEditVersion/revision`。秘密HMAC另绑定可信tenant/owner及原keyId；更换活动根密钥不能丢弃仍在使用的旧keyId。

元数据、密文版本、撤销和原回执在现有integration事务提交，PG租户锁配合CAS保护并发；运行角色没有版本UPDATE/DELETE。读取核对索引、完整版本、历史pin和时间；密文认证绑定租户/主体/凭据/版本/keyId。密钥缺失、认证失败或损坏必须拒绝，无Fixture/明文回退。解封装仅供可信连接器端口，IO前后复核撤销，临时字符数组最终清空；不提供任意HTTP入口。

秘密提交要求Servlet可信TLS，或显式dev且强制loopback绑定、对端与本端均为loopback。未建立可信代理边界时X-Forwarded-Proto不能开通安全提交。部署密钥文件默认未设置；本地只读密钥环规则见ADR-064，不能把路径/内容作为HTTP参数。`vaultAvailable/secretWriteAvailable/canConfigure`三个条件分别描述实际状态，不能由菜单推定权限。

每主体最多100份凭据、200份原命令，每份最多100秘密版本、1000次editVersion。满容量拒绝，不自动清理或复用键。前端秘密仅在密码输入DOM和当前传输中短暂存在；提交后清空，不加入React待确认命令、地址、存储或日志。结果不明保持非秘密原命令并显式GET核对，禁止自动换键重发。403/身份变化清空输入、缓存和原命令，迟到响应不能恢复旧身份内容。

本阶段提供独立版本化凭据管理，尚未完成受控多连接配置、现有env连接绑定、发布/任务完整pin。因此不能据此把已有检查结果提升为可复用，不能声称多来源CRUD或生产密钥管理已完成。
