# ADR-045：只读的来源连接自检

状态：实现与实际验证见 [验证报告第51节](../VALIDATION-REPORT.md)。

## 问题与决定

M2 的退出条件要求“一条**已声明支持版本**的来源”经已发布流水线完成分页与完整快照。仓库里
`Connector.probe` 早已实现（fixture 返回显式标注，JSON-RPC 调 `apiinfo.version`），但没有任何入口调用它：
真实来源到位后第一步“它到底连不连得上、自报什么版本”无处执行，也没有回执可引用。

决定：增加**只读**的来源自检。POST 执行一次有界 probe 并写入回执，GET 读取该来源最近的回执（1–50，默认 20）。
权限沿用源级 `source.sync`；租户来自可信 Principal、来源来自配置。自检不启动扫描、不取租约、不写库存、
不调用模型、不改绑定，失败也不回退 fixture。

## 回执语义

- `dataMode` 是**配置模式**（`labeled-fixture`/`zabbix-jsonrpc`/`closed`）：fixture 回执永远带
  `labeled-fixture`，它不证明真实厂商可用。
- `statusCode` 是稳定码（`labeled-fixture`、`ok`、`unreachable`、`not-configured`），不是厂商原文。
- `reportedVersion` 是来源**自报**的版本（JSON-RPC 模式下取自 `apiinfo.version`，最多 32 个可打印字符），
  用于对照“已声明支持版本”，不是平台验证过的支持声明；不可达的回执必须为 `null`，连接器抛异常时回执是
  `reachable: false` + `unreachable`，绝不变成成功。
- 每个 tenant/source 保留最近 100 份回执（写新回执时清理更旧的），单页读取上限 50；回执不参与授权、
  不能被请求覆盖。

## 边界

真实 Zabbix 的 `apiinfo.version`、TLS、代理与厂商版本兼容矩阵仍未验收——自检只是把“能不能连、自报什么版本”
变成可引用的证据，不替代真实来源的完整验收，也不等于支持声明的核对结论。自检没有后台调度、没有重试、
没有跨来源汇总；它也不会把 fixture 结果写成真实来源的结论。真实模型/IdP/人工诊断审阅同样仍未完成。

真实环境到位后的操作步骤见 [来源连接自检 runbook](../runbooks/source-connection-check.md)。
