# 告警规范化观测历史本机验收

适用：ADR-028 / V016，仅本机开发。来源使用明确 fixture；本页不声称已连接真实 Zabbix。

## 前置

- Java21、仓库 Wrapper、锁定的 Rust/pnpm 依赖已安装。
- 专用 PostgreSQL/可选 VictoriaMetrics 仅绑定 loopback；凭据通过进程环境注入，不写入文档或普通日志。
- 平台配置显式 `auth.mode=dev`、随机开发 token、固定 tenant，具备 `incident.read` 和 `entity.read`。导入还需配置来源的 `source.sync`。
- 先同步 fixture Host，再导入 2026-09-21 12:00–13:00 UTC 告警。来源发生时间固定，observedAt 是当次读取时间。

## 验收路径

1. 在 Incident 列表读取详情，打开“告警观测历史”，查询最近 7 天。页面显示 labeled-fixture、当前 Incident 版本、知识截止、首次接收时间和首次映射。留存前历史/厂商报文缺口可见。
2. 同一规范化输入原样重投只留一条；新的 observedAt 形成新条目。恢复后再次输入遗漏恢复字段时，当前状态仍保持恢复，而当次历史保留输入中的 ACTIVE/缺口。此组合由领域/PG 测试构造，不将 fixture 普通重复读取冒充同时间重投。
3. 下一页复用 version、from/till、asOf；修改来源/事件筛选从首页开始。事件 ID 必须同时指定来源；窗口至最后整秒，刚接收的秒内数据可能需下一次读取才进入窗口。
4. 手工改变 Incident 状态/归属后，旧版本读取返回 409，页面要求刷新详情；历史只属于 occurrence 当前 owner，合并归档来源没有这些历史。
5. 限定实体范围只读到当时映射完整且授权的记录；历史映射缺失不能由后来映射补全追认。401/403、换身份和退出后不显示历史或迟到响应。

## 可重复检查

```text
python scripts/check_repo.py
python -m pytest tests/contracts -q
python scripts/check_java_domain.py
gradlew.bat :apps:platform-api:test :apps:ingestion-worker:test :apps:platform-api:bootJar :apps:ingestion-worker:bootJar --console=plain
cargo fmt --all -- --check
cargo test --workspace --locked -j 1
cargo test --workspace --all-features --locked -j 1
pnpm --dir apps/web-console build
pnpm test:web
node scripts/check_metrics_stack.mjs --pipeline --runtime
```

PG/VM 测试需要 `OPSWEAVE_TEST_JDBC_URL/USER/PASSWORD` 与 `OPSWEAVE_TEST_VM_URL`（脚本同时接受 `OPSWEAVE_TEST_VICTORIA_URL`），浏览器验收可指定 `OPSWEAVE_TEST_CHROMIUM_EXECUTABLE`。未提供这些变量的跳过不计通过。Windows 上先结束 Rust 测试，再启动验收 Runtime，避免正在运行的 exe 被链接器覆盖。受限的本机 Python 可用 Docker Python 3.12 执行开发检查，不作为运行后端。

新增测试：`ProblemHistorySmoke`、`PostgresIncidentIT` 历史/范围/回滚/重开/128 字符租户用例、`ProblemHistoryHttpIT`、`EntityReadDeniedIT`、`problem-history.spec.ts`。验收产物在 `.tmp/metrics-acceptance/problem-history.png` 与 `problem-observation*.json`；只包含测试 fixture，不提交凭据和临时产物。
