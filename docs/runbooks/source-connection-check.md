# 本机来源连接自检

真实 Zabbix 到位后的**第一步**。只读、有界，不启动扫描、不取租约、不写库存、不调用模型；失败也不回退 fixture。

需现有 PG（或内存存储）与源级 `source.sync`。V026 只增加回执表与索引；不新增服务或密钥。

1. 打开“来源扫描”页面，填入平台开发 Token（或生产 OIDC 会话），找到“来源连接自检（只读）”一节。
2. 点击“来源连接自检”：页面会调用一次有界 probe。
   - fixture 模式：回执是 `reachable: true`、`statusCode: labeled-fixture`、`reportedVersion: 无版本声明`。
     这只说明**合成探针**可用，不证明真实厂商可用。
   - JSON-RPC 模式：回执是 `reachable: true`、`statusCode: ok`、`reportedVersion` 为来源自报的 `apiinfo.version`。
     这个版本是**来源的声明**，不是平台验证过的支持结论；请把它与团队声明的支持版本对照后再决定是否继续。
   - 不可达：回执是 `reachable: false`、`statusCode: unreachable`、`reportedVersion: null`。不要据此改配置重试，
     先检查来源地址/网络/TLS 与凭据引用。
3. 点击“读取自检回执”查看该来源最近的回执（每页 10/20/50，默认 20，倒序）。每个 tenant/source 只保留最近
   100 份，更旧的会被清理；回执不参与授权、不能被请求覆盖。
4. 把这次回执的 `checkId` 与时间记入验收记录：它只证明“某一刻这个来源这样回答过”，不替代后续的真实
   `host.get` 分页、完整快照与人工诊断审阅。
5. 刷新或清除会话后回执与列表都会消失，需要重新填入 Token 再读取。

自检不会对账、不会修复、不会改绑定；它也不写任何厂商原文（`statusCode` 是稳定码，`reportedVersion` 最多
32 个可打印字符）。实际本机合成数据验证见 [验证报告§51](../VALIDATION-REPORT.md)。
