# 只读筛选与时间窗口恢复验收

范围：[ADR-029](../adr/029-restorable-read-selections.md)、[契约](../../contracts/view-selection.md)。保持固定 loopback 开发模式，Token 仅保存在当前文档内存。无真实来源/模型配置时只能标注本机 fixture/mock 验证。

## 人工路径

1. 资产页输入名称/IP、生命周期，刷新列表并打开详情。刷新浏览器，确认筛选/选中 ID 保留、Token 和结果已清空；重新输入 Token，点击“读取选中资产”。详情必须重新通过 API 读取。分页后刷新应显示“恢复的游标页”，重置筛选回到首屏。
2. Incident 选择非默认状态，刷新列表并打开详情。浏览器刷新或回退后，状态下拉框应匹配地址；点击“读取选中 Incident”重新授权。地址不得自动触发导入、状态变更或重放写请求。
3. 指标目录选择非第一项指标及 Last 15m，点击查询。保存当前链接，刷新并重新输入 Token，再读目录、查询，资源/指标/from/till 应完全一致；点击 Last 15m 再查询才变成新的最近窗口。NO_DATA 仍明确显示，不改查有数据的窗口。
4. 先后应用两个筛选，浏览器后退/前进：表单恢复、旧结果清空、无自动请求。替换已有 Token、清除会话、401/403 或到期：当前选择和结果清空。旧浏览器历史仍可能保留非凭据选择，但不能恢复身份或权限。
5. 地址加入 `tenantId=other`、重复 status、非法 UUID、破损 UTF-8 或仅一个时间边界：错误可见，读取禁用，必须显式重置。目录已删除的 metricKey 不得被替换成第一项。

## 自动检查

- `pnpm --dir apps/web-console build`：TypeScript 与生产构建。
- `pnpm test:web -- view-selection.spec.ts view-restoration.spec.ts`：codec、canonical 样例和浏览器恢复/错误/会话用例。按本机 Chromium 环境配置 executablePath；完整回归使用 `pnpm test:web`。
- 安装 `requirements-dev.txt` 后运行 `python scripts/check_repo.py`、`python -m pytest tests/contracts -q`、`python scripts/check_release_inputs.py`。Python 仅用于开发检查。
- 使用专用 loopback PG/VM 的测试环境变量和已构建 Java/Rust 可执行文件，执行 `node scripts/check_metrics_stack.mjs --pipeline --runtime`。具体启动条件沿用 [指标查询验收](metric-query-acceptance.md)；不得输出 Token/数据库凭据。
- 完整链增加资产筛选+详情、Incident 状态+详情和指标固定窗口的刷新/重新读取；产生 `.tmp/metrics-acceptance/{inventory,incident,metrics}-view-selection.json` 与 `restored-metrics.png`。产物由 canonical Schema 检查，不提交临时目录。其来源仍是明确 fixture/mock，PG/VM 和浏览器/API 通路是真实本机进程。

不验证生产 OIDC/BFF、跨设备身份、浏览器历史擦除、线上部署、全历史子面板筛选或实时分页快照。实际执行数量和失败修正只写入 [VALIDATION-REPORT.md](../VALIDATION-REPORT.md)。
