# 安全说明

这是开发模板。Java `platform-api` 默认 `OPSWEAVE_AUTH_MODE=closed`；本地可显式打开 Dev Principal（loopback + 随机 token），不是生产 OIDC/RBAC。Rust 默认 closed。Demo 只能本机访问，不可转作公网认证。

不要把凭据、客户日志、Prompt或证据写入普通日志、Issue或Git。`.env`本地生成且忽略；组织应指定真实安全联系渠道，再公开项目。

发现越权、路径穿越、Schema远程解析、SSRF、未授权MCP或密钥泄露时，先停用相关能力并通过组织私有安全渠道报告，不在公开Issue粘贴复现所用真实秘密。

生产前必须完成：OIDC验证issuer/audience/expiry、每次资源授权、跨存储tenant边界、最小数据库角色、秘密管理、缓存ACL、模型出网白名单、事件重放策略、审批/幂等、备份恢复演练和供应链扫描。

日志标记untrusted、Rust内存安全、MCP协议、证据引用存在都不单独构成完整安全保证。详见架构文档安全章节。
