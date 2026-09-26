# 真实验收执行包

真实 Zabbix 与模型到位后，用一条命令按顺序跑完 M2–M4 的关键退出条件，并留下可复核的 JSON 报告。
它只调用平台 HTTP API（不直连 Zabbix、模型或数据库），不写任何来源，也不重试失败步骤。

## 前置

- 平台已按真实来源运行：`OPSWEAVE_ZABBIX_MODE=jsonrpc`（或 `real`）指向真实 Zabbix，`OPSWEAVE_PROVIDER` 按验收目标配置，
  Runtime 已启动并指向平台；证据/预算/留存策略已按团队策略配置。
- 一个开发模式 Bearer Token（或等价的开发会话）：`OPSWEAVE_ACCEPTANCE_TOKEN`。
  生产 OIDC 部署请改用 [OIDC runbook](oidc-bff.md) 的浏览器流程；本执行包覆盖开发/平台开发模式。
- 指定必须有真实样本的指标与（可选）资产：`OPSWEAVE_ACCEPTANCE_METRIC`、`OPSWEAVE_ACCEPTANCE_ENTITY`。

## 执行

```bash
OPSWEAVE_ACCEPTANCE_URL=http://127.0.0.1:8080 \
OPSWEAVE_ACCEPTANCE_TOKEN=<dev token> \
OPSWEAVE_ACCEPTANCE_METRIC=host.cpu.usage.user \
node scripts/acceptance/real-acceptance.mjs --report=.tmp/acceptance/real-report.json
```

七步按顺序执行，任一步失败立即停止并写失败报告：

| 步骤 | 内容 | 退出条件 |
|---|---|---|
| S1 | 来源连接自检（reachable、dataMode、来源自报版本） | M2「已声明支持版本的来源」 |
| S2 | Host 同步并要求 `hostid-watermark-snapshot` + 落库标签回读 | M2 分页/游标/完整快照 |
| S3 | Item 同步并要求 `itemid-watermark-snapshot` | M2/M3 指标采集 |
| S4 | 受权资产读取 | M1/M2 资源授权 |
| S5 | 指定指标返回 `AVAILABLE` 且有样本（计数器同时记录 reset 数） | M3 指标与关联 |
| S6 | Incident 与关联告警可读（未指定则先导入一个窗口的外部告警） | M3 Incident 关联 |
| S7 | 一次只读诊断保存为 PG AIInsight 并回读（含证据引用数、provider） | M4 结果与证据 |

## 报告与判定

报告包含 `mode`、每步的证据、退出条件映射与固定未验证项。判定规则：

- `mode: "rehearsal"`：来源自检返回 `labeled-fixture`（或显式 `--rehearsal`）。**这只是演练**，脚本会打印
  “NOT real acceptance”，不得写入验收结论。
- `mode: "real"`：来源不是 fixture。仍要人工核对报告里的 `sourceDataMode`、`reportedVersion` 与 provider，
  确认它确实是目标环境；脚本不替人判断“这个来源是不是我要验收的那个”。
- 固定未验证项（脚本不能代替）：人工抽样审阅、真实模型提供方账单/配额对账、真实 IdP/HTTPS/反向代理验收。

失败时报告仍会写出（含 `failed: true`、已完成步骤与错误），退出码 1；若来源是 fixture 却没有 `--rehearsal`，
退出码 2 且不会产生“真实验收”报告。本机演练证据见 [验证报告§53](../VALIDATION-REPORT.md)。
