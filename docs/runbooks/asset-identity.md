# 资产强标识登记与定位的本机验收

前提：Java21、已构建 Web/两个 Java 启动包，显式本机开发身份，真实 loopback PostgreSQL/VM；数据源为 labeled fixture，模型 mock。没有连接客户资产登记系统或真实 CMDB API。

平台配置 `OPSWEAVE_IDENTITY_NAMESPACE`（如 `enterprise-assets`）和 `OPSWEAVE_CMDB_IMPORT_SOURCE`。登录用户必须有目标的 `entity.read`/`entity.manage` 及该来源 `source.sync`；tenant、actor 来自认证边界。不能把 demo Token、协议 fixture 或本机容器当生产配置。迁移由既有 InventoryWiring 执行至 V017，PG 凭据只通过本机配置注入，不在命令输出或文档记录。

## 页面流程

1. 资产页显式读取目标详情，再点“读取资产标识”。核对平台显示的命名空间、目标和版本。
2. 核对资产登记系统的 UUID，填写原因，点“登记已核对标识”。每个 UUID 在同租户/命名空间只能对应一个生效目标。
3. “按已登记强标识定位”输入 UUID 并显式读取。未登记或无目标权限时 404，不创建资产，不按名字/IP 回退。
4. 定位后的“补充来源字段审核”显示标识依据；暂存固定 CMDB 四字段导入，逐字段选择来源，再填写原因确认。UUID pin 会保留在历史记录中，接受时重新核对登记及实体版本。
5. 若需撤销，先撤销生效字段确认，恢复最新主来源，再重新读取资产标识并撤销。直接撤销被生效字段引用的登记会返回 409，数据库不产生部分写入。
6. 撤销后再按 UUID 定位返回 404。旧待审导入不能再接受；历史和原幂等回执仍可读。刷新/URL 恢复只保留资产选择，不保留 UUID、写命令或自动执行导入。

503/断线导致写入结果不明时，页面保留原请求并提供显式重试；不要自行生成新请求猜测结果。已知 400/401/403/404/409 清除待重试命令并要求重新读取核对。会话变动和退出清除 UUID、输入与迟到结果。

## 自动验收

按既有 [指标验收](metric-query-acceptance.md) 配置本机专用 `OPSWEAVE_TEST_JDBC_URL/USER/PASSWORD`、`OPSWEAVE_TEST_VM_URL`，以及可选 `OPSWEAVE_TEST_CHROMIUM_EXECUTABLE`，先完成 Rust 构建/检查再启动 Windows runtime。运行：

```text
node scripts/check_metrics_stack.mjs --pipeline --runtime
```

脚本给自建平台注入 `acceptance-assets` 命名空间和随机隔离租户；新增 `scripts/lib/check_asset_identity.mjs` 使用真实页面/Java/PostgreSQL，检查登记原始回执重试、定位、导入 pin、字段确认、依赖撤销冲突、显式回滚、撤销后 404 和稳定 hostId。产物在忽略的 `.tmp/metrics-acceptance/asset-identity*.json` 及两个带 `-with-identity` 后缀的 SourceReview JSON；截图为 `asset-identity-dependency.png`。不保存开发 Token。

补充层次：纯领域 `AssetIdentitySmoke`；真实 PG `PostgresAssetIdentityIT`（并发不同目标竞争、事务回滚/重开/原始回执）；真实 Spring HTTP `AssetIdentityHttpIT`（命名空间/身份覆盖、重复参数、撤销与旧导入）；浏览器 fixture `asset-identities.spec.ts`（响应校验、逐字文本、未知结果原请求重试、退出清理）。前端 fixture 测试不等于真实来源验收。具体实际命令和结果以 [验证报告](../VALIDATION-REPORT.md) 第 38 节为准。

上限：16 生效/1000 历史登记每实体、25 条分页、16 KiB 请求。超限需要明确的数据治理方案，当前不自动删除审计记录。跨 namespace 的变更不迁移旧登记；需按原配置撤销与新配置重新核对，不能在线随意改 namespace 来移动归属。
