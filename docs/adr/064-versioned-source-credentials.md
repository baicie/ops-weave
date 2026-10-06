# ADR-064：接入凭据加密与固定版本引用

2026-10-03，状态：采用。为已授权的受控连接配置实施凭据基础，不将此阶段视为完整多连接/运行能力。

沿用Java平台、现有PostgreSQL及四启动单元。凭据通过专用授权命令写入，领域层只处理元数据、不可变版本、撤销与回执，密文封装/解封装经业务端口进入JCA适配器。不增加密钥服务或数据库；不把env引用当成不可变秘密版本。

使用AES/GCM/NoPadding、256位派生密钥、随机96位nonce和128位认证标签。AAD固定tenant、owner、凭据ID、版本、版本ID与keyId，跨对象或租户复制密文必须失败。每次封装使用新的nonce；根密钥以HMAC-SHA256的不同固定标签派生加密和命令认证密钥。相关JDK行为见[Cipher](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/javax/crypto/Cipher.html)与[GCMParameterSpec](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/javax/crypto/spec/GCMParameterSpec.html)。没有使用无认证加密或对外返回秘密摘要。

根密钥环由可信部署配置指定本机文件，默认未启用，配置路径不接受浏览器/模型输入；只读有界本机文件、不下载。文件必须在仓库外、由运维控制访问权限；最多8个keyId，根密钥各32字节。activeKeyId只用于新封装；已有密文和秘密命令固定原keyId，保留旧根密钥才能读取和核对。缺失或不正确时失败，不生成临时主密钥或明文回退。文件加载不代表已完成生产KMS、备份/灾备、访问审计和轮换验收。

公开能力分别报告vaultAvailable、canConfigure和secretWriteAvailable。秘密提交仅允许Servlet可信TLS，或显式dev+bindLoopbackOnly且本端/对端都是loopback；不能只用X-Forwarded-Proto推断TLS。密钥文件必须为绝对本机路径、最多8192字节、规范Base64根密钥，额外字段/重复键/尾随内容拒绝。keyId绑定根密钥身份，不应把同一keyId重新用于另一根密钥；生产代理、文件ACL、备份、恢复和根密钥轮换须独立验收。

新增source.configure用于凭据写入，source.sync仅读取本人元数据及受控执行解封装。可信对象范围仍必须包含对应credential资源；不从请求授予权限，产品默认和自动启动不扩充开发/生产身份。部署管理员需在可信服务端配置中显式授予此权限，本机开发验证也必须显式配置并保留原配置记录。轮换只追加版本，旧任务/配置不自动选择最新版；撤销整体或指定版本为显式命令，历史保留、不可恢复，已有固定引用立即不可再解封装。没有GET秘密或删除密钥的HTTP接口。

创建、元数据修改、轮换、版本撤销使用主体范围内requestId和CAS；公开回执只含非秘密命令摘要，内部HMAC核对同键不同秘密。响应、普通日志、toString和前端待确认状态不含秘密、密文或内部HMAC。浏览器秘密字段一次提交后立即清空，未知结果只查询原请求，禁止自动重新提交。请求沿用现有64KiB读取边界；实际秘密限定1–4096个可见ASCII字符，不能夹带换行认证头。

本阶段与受控多连接、网络出口校验、固定配置检查/发布/任务pin逐步衔接；在这些绑定完成前，不能将新凭据列表称为可编辑多来源已完成。实际检查只按验证报告记录，Fixture和真实来源分别验收。
