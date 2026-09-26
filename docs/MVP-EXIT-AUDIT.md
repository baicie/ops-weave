# M0–M4 退出条件审计（本机工作区）

更新：2026-09-26。对象是当前 `main` 工作区（含未提交改动），不是远端部署，也不是生产就绪证明。

## 方法与口径

条件逐条取自 [ROADMAP.md](ROADMAP.md) 的 M0–M4「验收」小节与 [MVP-CHECKLIST.md](MVP-CHECKLIST.md) 的必交付行，
不改写、不缩小。每条只引用当前工作区**真实存在**的证据：测试类名、领域 smoke、完整链 PASS 行、实际产物与
runbook。判定分三档：

- **本机已验证**：有可复跑的检查覆盖该条件，且不依赖厂商/真实身份/真实模型。
- **仅环境阻塞**：实现与本地检查就绪，但条件本身要求真实来源、真实模型、真实 IdP/TLS 或人工审阅。
- **缺失**：本地也还没做（本节列出并说明为何仍缺）。

测试数量不折算进度；判定不因“测试写了”而变成“测试通过”。

## 汇总

| 阶段 | 条件数 | 本机已验证 | 仅环境阻塞 | 缺失 |
|---|---:|---:|---:|---:|
| M0 依赖与 Zeus 基线 | 6 | 6 | 0 | 0 |
| M1 可信身份与请求边界 | 5 | 4 | 1 | 0 |
| M2 数据接入 | 8 | 6 | 2 | 0 |
| M3 指标、告警与 Incident | 5 | 4 | 1 | 0 |
| M4 只读诊断 MVP | 10 | 8 | 2 | 0 |

结论：M3 的 counter reset 策略与用例（此前唯一的本地缺失项）已在第 52 节实现并验证：`CounterRateSmoke`
22 项、契约 14 项、真实 HTTP `MetricCounterRateHttpIT`、Web 变化率视图 8 项。**本机可验证条件至此全部有
证据**，汇总表不再有“缺失”行；剩下的全部是环境阻塞（真实 Zabbix、真实模型/账单、真实 IdP/TLS、人工抽样
审阅）。因此仍不能声称 M0–M4 已 100% 通过：环境阻塞项没有本地替代物。

## M0：依赖与 Zeus 基线

| 条件 | 证据 | 判定 |
|---|---|---|
| 干净工作树安装、类型检查、生产构建 | 历史：`b5404a8` 的四 job CI 记录（README/验证报告第 5–10 节）；本机当前：`apps/web-console` 的 `pnpm typecheck` + `pnpm build`（第 51 节） | 本机已验证（本轮未重做“无 node_modules”干净安装） |
| 诊断页输入/提交/重复提交/成功/失败/取消等待的浏览器测试 | `e2e/diagnose.spec.ts`、`e2e/http-client.spec.ts`（210 项 Playwright 全量中） | 本机已验证 |
| 无效凭据被拒绝；真实模式失败不回退 fixture | `EntityReadDeniedIT`、`SourceSyncDeniedIT`、`SourceScanRunDeniedIT`、`SourceConnectionCheckDeniedIT`、`ZabbixJsonRpcConnectorIT`、`ClosedZabbixConnector`（fetch 直接抛错）；Rust `unavailable_redirect_missing_usage_and_oversize_never_retry_or_fallback` | 本机已验证 |
| 路由切换不残留旧请求/监听器；卸载后完成的请求不覆盖新状态 | `e2e/view-restoration.spec.ts`、`e2e/platform-session.spec.ts`、`e2e/browser-session.spec.ts` | 本机已验证 |
| 外部文字不能注入可执行 HTML | `e2e/pipelines.spec.ts`（“host names are plain text”）、各 source 页面以纯文本渲染外部字段 | 本机已验证 |
| README/验证报告区分“源码已有/CI 已通过/浏览器已验收” | `README.md`、`docs/VALIDATION-REPORT.md`（每节都标注命令与边界）；中文 IME/完整键盘矩阵仍为已知残留 | 本机已验证（残留已声明） |

## M1：可信身份与请求边界

| 条件 | 证据 | 判定 |
|---|---|---|
| 同一请求在允许/拒绝主体下结果正确；改 tenant/entity/incident ID 不越权 | `EntityReadDeniedIT`、`SourceSyncDeniedIT`、`SourceScanRunDeniedIT`、`SourceConnectionCheckDeniedIT`、`IdentityAndZabbixHostIT`、`RequestBoundaryHttpIT`、领域 `IdentityAuthorizationSmoke`/`IdentityGrantSmoke` | 本机已验证 |
| 未登录/权限撤回/会话过期/资源不存在有确定响应；UI 错误不泄露不可见资源 | 上述 IT 的 401/403/404 断言；OIDC 链 10 个 PASS（撤权、Cookie/CSRF、退出）；`e2e/platform-session.spec.ts` | 本机已验证 |
| 资源筛选/时间范围可恢复、无自动请求、逐次重新授权 | `e2e/view-selection.spec.ts`、`e2e/view-restoration.spec.ts`、`EntityPageHttpIT`、`MetricSeriesHttpIT` | 本机已验证 |
| 旧响应/凭据清理与会话边界 | `e2e/browser-session.spec.ts`、`e2e/http-client.spec.ts`；链内 `credentialStorage: document-memory-only`（112 边界 0 失败） | 本机已验证 |
| 真实 IdP/HTTPS/反向代理与会话语义 | 只有协议 fixture 与同源 BFF 本机链 | **仅环境阻塞** |

## M2：数据接入

| 条件 | 证据 | 判定 |
|---|---|---|
| 已声明支持版本的来源 → 已发布映射 → Raw/Observation/Entity | `PostgresHostSyncIT`、`PostgresSourceScanIT`、`PostgresSourceScanRunIT`、`ZabbixHostMappingSmoke`、`ZabbixHostPageContractSmoke`；链内 host sync PASS | 本机已验证（fixture/协议桩） |
| 分页、游标、完整快照 | `PostgresHostScanBoundaryIT`、`HostScanBoundarySmoke`（33 项）、`ZabbixJsonRpcConnectorIT`；链内水位快照断言 | 本机已验证 |
| 重复同步不新增重复 Entity；失败扫描不误删除 | `PostgresHostSyncIT`、`ZabbixHostMappingSmoke`、`PostgresHostScanBoundaryIT`（位移/未验证不退休）、第 49 节强制规则 | 本机已验证 |
| 同 IP 不同租户不合并；字段冲突可追溯来源/映射版本/观测时间 | `PostgresSourceReviewIT`、`PostgresSourceSnapshotIT`、`PostgresSourceBindingCorrectionIT`、`SourceSnapshotHttpIT`、`SourceReviewHttpIT`；回执保存来源/摘要/观测时间与 actor | 本机已验证（只留存**决策**回执；被拒绝的尝试不落库，见下） |
| 资产服务端筛选/分页/详情、跨租户隔离 | `PostgresEntityPageIT`、`PostgresAssetIdentityIT`、`EntityLegacyLimitIT`、`e2e/inventory.spec.ts`、`e2e/observations.spec.ts` | 本机已验证 |
| Preview/Replay 无副作用；未实现 Copilot 时不假装有 | `PostgresPipelineIT`、`PostgresPipelineReplayIT`、`PostgresPipelineDraftIT`、`e2e/pipelines.spec.ts`、`e2e/pipeline-drafts.spec.ts` | 本机已验证 |
| 真实 Zabbix 实例（`host.get` 分页/排序/`countOutput`、水位假设、自报版本核对） | 只有 fixture 与本地协议桩；第 51 节 runbook 是第一步 | **仅环境阻塞** |
| 厂商 CMDB API、后台采集、已有 Entity 合并/alias、通用权威规则 | 未实现且**不在首个只读 MVP 范围**（后台采集属额外服务，路线图保留） | **仅环境阻塞**（范围外） |

## M3：指标、告警与 Incident

| 条件 | 证据 | 判定 |
|---|---|---|
| 指标目录/绑定、实际采集存储与查询，保留缺失/过期/来源 | `PostgresItemSyncIT`、`PostgresItemScanBoundaryIT`、`PostgresItemScanOwnershipIT`、`MetricSeriesHttpIT`、`MetricSeriesClosedIT`、`VictoriaMetricsQueryAdapterIT`、`HistoryServiceHttpIT`；OIDC/Worker 链 10 个 PASS | 本机已验证 |
| Incident 跳实体与同窗口指标；重复告警不重复新建；恢复语义；时区/缺失用例 | `PostgresIncidentIT`、`ProblemHistoryHttpIT`、`IncidentSmoke`、`ProblemHistorySmoke`、`MetricPointSmoke`、`e2e/incidents.spec.ts`、`e2e/problem-history.spec.ts` | 本机已验证 |
| counter reset 策略与用例 | 第 52 节：SUM 查询返回 `derivation = counter-rate` / `reset-counts-from-zero`，下降区间从零重计并带 `counterReset`，原始点保留；`CounterRateSmoke`（22 项）、`MetricCounterRateHttpIT`、`tests/contracts/test_metric_counter_rates.py`（14 项）、`e2e/metric-counter-rates.spec.ts`（8 项） | 本机已验证 |
| 人工合并/拆分与归属历史 | `PostgresIncidentIT`（归属）、`ReorganizationHttpIT`、`IncidentReorganizationSmoke`、`e2e/reorganizations.spec.ts` | 本机已验证 |
| 真实厂商采样/告警验收（含厂商原始报文） | 只有 fixture；原始报文不在留存范围 | **仅环境阻塞** |

## M4：只读诊断 MVP

| 条件 | 证据 | 判定 |
|---|---|---|
| Java 版本化 Tool、受权 Evidence、审计与预算 | `ToolGatewayHttpIT`、`PostgresToolReadIT`、`ToolGatewaySmoke`（46 项）、链内 Tool Gateway PASS（四次共享预算耗尽） | 本机已验证 |
| Rust 真实平台 HTTP + 单次模型摘要；共享预算/截止；前后证据复核 | `RuntimeDispatcherTest`、`ToolExecutorTest`、`apps/agent-runtime/tests/platform_http.rs`、`runtime_tests.rs`、链内 Runtime mock 诊断 PASS | 本机已验证（模型为显式 mock） |
| AIInsight 幂等持久化与查询、页面发起/结果/受权证据 | `PostgresAiInsightIT`、`InsightHttpIT`、`AiInsightSmoke`、`e2e/current-diagnosis.spec.ts`；链内 AIInsight 幂等重试/刷新回读 PASS | 本机已验证 |
| 输出能查到已保存结果，引用属于本次已授权 Evidence | 链内“保存→重试同键→刷新读取→证据重授权”PASS；`AiInsightSmoke` 引用校验 | 本机已验证 |
| 无法获取的数据不被猜测补全 | 链内缺失窗口保持 `NO_DATA`；`current_workflows.rs` 的 missing-data 保留测试 | 本机已验证 |
| 模型请求不携带原始密钥；外部日志/知识按不可信数据处理 | 第 50 节：请求头/体隔离 + 不可信字段框架端到端测试（Rust all-features 44） | 本机已验证（本地协议桩） |
| 既有负例集全部通过 | `invalid_model_output_and_revocation_never_save`、`unavailable_..._never_retry_or_fallback`、`AiRetentionSmoke`、`SourceScanRunQuerySmoke` 等负例；链内 429/404/403 边界 | 本机已验证 |
| 不开放生产写入工具 | `contracts/tools/*.tool.json` 全部只读；Tool Gateway 无动作端口 | 本机已验证 |
| 一条真实来源链 + 真实 Incident + 真实指标完成诊断 | 需要真实 Zabbix/模型/身份配置 | **仅环境阻塞** |
| 抽样结果有人审阅 | 需要人 | **仅环境阻塞** |

## 本地未做但**不是**退出条件

| 项 | 现状 | 为什么不算退出条件 |
|---|---|---|
| 运行记录/回执的全平台容量治理 | `source_sync_run` 无自动清理或总量配额；连接自检回执按 tenant/source 保留 100 份 | M2–M4 验收只要求可追溯与不误删；治理属运维硬化，已在 NEXT-TASKS 保留 |
| 被拒绝写尝试的审计日志 | 只留存**决策**回执（来源/摘要/观测时间/actor），409 冲突本身不落库 | 退出条件要求“字段冲突可追溯来源/映射版本/观测时间”，决策回执已满足；拒绝尝试日志属额外可观测性 |
| 中文 IME / 完整键盘矩阵 | 未测（M0 已声明不阻塞 M1） | 路线图明确列为不阻塞 |
| M5+（可恢复 Run、Skill Builder、试点交付） | 未开始 | 明确不在首个只读 MVP 范围 |

## 环境到位后的执行顺序

1. `docs/runbooks/source-connection-check.md`：先跑只读连接自检，记录 `checkId` 与来源自报版本。
   也可以直接用 `docs/runbooks/real-acceptance.md` 的执行包按顺序跑完 S1–S7 并留下报告。
2. `docs/runbooks/host-scan.md` / `source-scan-runs.md`：真实 `host.get` 分页，确认水位快照完成且标签为
   `hostid-watermark-snapshot`；失败时按 `failureCode` 排查，不要对账。
3. `docs/runbooks/source-snapshots.md` / `source-binding-corrections.md`：真实 CMDB 字段审核与人工更正。
4. 指标/告警：Item 同步（`itemid-watermark-snapshot`）与外部告警导入，确认 Incident 与同窗口指标。
5. `docs/runbooks/insight-local-acceptance.md`：真实模型（显式开启出网与预算）完成一次诊断，保存 AIInsight
   并核对证据引用；随后安排人工抽样审阅。

## 如何复核

```bash
node .tmp/domain-check.cjs                                  # 纯领域
node .tmp/pipeline-test-env.cjs full                        # Java + PG + 两个 bootJar
node .tmp/pipeline-test-env.cjs browser                     # 浏览器→Java→PG/VM 完整链
node .tmp/oidc-stack.cjs --history-service                  # OIDC/Worker 链
node .tmp/rust-check.cjs                                    # Rust 默认 + all-features + fmt
docker run --rm -v "$PWD":/workspace -w /workspace python:3.12 sh .tmp/check-connection-final.sh
docker run --rm -v "$PWD":/workspace -w /workspace python:3.12 sh .tmp/check-counter-final.sh
node apps/web-console/node_modules/@playwright/test/cli.js test --config .tmp/playwright-local.config.ts
```

上述命令最近一次全绿记录见 [VALIDATION-REPORT.md](VALIDATION-REPORT.md) 第 52 节（契约 642、领域 1008、
Java 226、Rust 40/44、Web 218、111 产物/65 类 Schema；完整链与 OIDC 记录见第 51 节）。
