# 真实环境候选验收执行包

本执行包按序检查 M2–M4 的平台读取链，并保存报告供人工核验。**七步通过也不等于 M0–M4 退出条件全部满足。**
它只经受控平台 API 调用；来源保持只读，平台内会写连接回执、同步资产/指标目录和诊断结果。
仅演练且未指定 Incident 时会执行一次平台告警导入；不会写回 Zabbix、执行修复、重试或回退。

## 前置

- 平台、Runtime、PostgreSQL、VictoriaMetrics 和采集 Worker 已按验收目标配置；平台来源是 `jsonrpc`，
  Runtime 使用 `rig-openai`，模型费用准入已配置。密钥只进入各服务自身配置，不进入报告或命令行参数。
- 在运行脚本的环境中设置 `OPSWEAVE_ACCEPTANCE_URL` 和 `OPSWEAVE_ACCEPTANCE_TOKEN`（平台开发 Bearer）。
  生产 OIDC 登录仍须执行 [OIDC 浏览器流程](oidc-bff.md)，本脚本不把开发 token 验证计为生产身份验收。
- 非演练模式必须明确指定 `OPSWEAVE_ACCEPTANCE_ENTITY`、`OPSWEAVE_ACCEPTANCE_INCIDENT`、
  `OPSWEAVE_ACCEPTANCE_METRIC` 和 `OPSWEAVE_ACCEPTANCE_ZABBIX_VERSION`（如 `7.0.0`，必须与目标实例自报版本一致）。
  指标必须是当前诊断技能实际查询的指标，并且所选资产属于该 Incident。脚本不启动历史采集 Worker；样本须已存储。
- 默认只允许 loopback 平台 origin，不能含路径、查询、片段或用户信息。
  远端必须使用 HTTPS，且显式设置 `OPSWEAVE_ACCEPTANCE_ALLOW_REMOTE=true`；HTTP 重定向一律拒绝。

## 执行

先通过已有安全配置方式设置上述环境变量，再执行：

```bash
node scripts/acceptance/real-acceptance.mjs --report=.tmp/acceptance/candidate-report.json
```

显式 fixture/mock 演练必须加 `--rehearsal`。演练允许省略资产和 Incident，但自动选择的二者若无关联仍失败。
指定资产直接读取其受权详情，不再限制为资产第一页前五条中的对象。

| 步骤 | 实际检查 | 不能由此推断 |
|---|---|---|
| S1 | 可达来源、闭集 dataMode、来源自报版本与指定版本一致 | 该版本所有厂商行为均受支持、目标环境真实性 |
| S2 | Host 完整水位快照、PG 库存、发布版本/digest 与持久扫描追溯一致 | 重复同步幂等和失败扫描不误删除 |
| S3 | Item 完整水位快照与持久扫描追溯一致 | History Worker 或指标存储成功 |
| S4 | 指定资产的受权详情可读 | 所有主体的授权负例均已验收 |
| S5 | 指定资产/指标/窗口返回有限、有效且非空样本，逐序列检查来源 | 无缺口或已完成厂商采样验收 |
| S6 | 同租户 Incident 非空告警实际关联所选资产，逐告警检查来源 | 自动根因或事件相关性正确 |
| S7 | 单次诊断的 PG 结果与原请求绑定、模型/来源标记、有效期、finding 引用、原样回读；逐条受权读取 incident/metric 证据，复核租户/会话/版本/资产/窗口/availableAt/asOf/当前到期时间及指标样本 | 证据支持结论、因果关系、真实模型账单或人工认可 |

## 报告 v2 与判定

契约唯一源：[mvp-acceptance-report.schema.json](../../contracts/schemas/v2/mvp-acceptance-report.schema.json)；
[样例](../../contracts/examples/v2/mvp-acceptance-report.json)是合成演练，不是运行记录。

- `mode: rehearsal`：调用者明确选择演练，成功或失败都保留此标记。
- `mode: unverified`：非演练运行尚未通过全部检查，包括配置缺失、来源/模型不符或任何中途失败。
- `mode: real-candidate`：全部探针通过，且各段均声明真实来源、模型声明 `rig-openai`。
  **仅为候选证据**：协议桩也能自报这些字段，因此仍必须独立核对目标环境及真实提供方。
- `status: passed/failed` 只描述探针结果。`milestonesSatisfied` 固定为 `false`；没有 `mode: real`。
  旧 schemaVersion 1.0 报告中 `real` 只检查了来源，不能据此追认 M4。
- 报告记录开始/结束时间、已完成步骤、请求 UUID、必要资源/版本标识和稳定失败码；
  不记录 URL/Token、资产名、错误正文、原始日志、提问或模型输出。stdout 也只输出步骤与稳定码。
- 配置失败也生成报告。失败退出码 1；未选择演练却遇到 fixture 时为 2。
  一次请求最多读取 256 KiB，普通请求 20 秒、诊断 90 秒；失败不重试、不跟随来源提供的任意链接。

保留五项独立未验证条件：目标环境/版本兼容、幂等/失败/授权负例、人工抽样审阅、模型账单与配额、
真实 IdP/HTTPS/代理和浏览器会话。必须分别提供证据，不能删除报告字段或仅凭脚本成功关闭里程碑。

## 本地回归

```bash
node --test tests/acceptance/*.test.mjs
python -X utf8 -m pytest tests/contracts/test_mvp_acceptance_report.py
```

Node 用例只连接 loopback 协议 fixture，不调用真实模型或来源。CI 和 `scripts/check.*` 已包含此探针回归以及
Rust `cargo test --workspace --all-features --locked`；全 features 编译不能替代运行该配置的测试。
本地 Python 检查入口显式使用 UTF-8，避免 Windows 的 GBK 默认编码误读中文契约样例。
浏览器可显式设置 `OPSWEAVE_TEST_CHROMIUM_EXECUTABLE` 指向本机已有 Chromium；未设置时沿用 Playwright 默认分发包，
失败不会自动换浏览器。实际检查结果见 [验证报告](../VALIDATION-REPORT.md)。
