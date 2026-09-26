# Host PipelineVersion / Preview / Replay

这是 Zabbix Host 的确定性接入 API。本机验收见 `../VALIDATION-REPORT.md` 第 22–24 节。使用现有可信 Principal，来源由服务器 `opsweave.zabbix.source-instance-id` 配置确定；请求不接受 tenant、user、权限、来源 URL 或执行代码。

## 能力与存储

- `POST /api/v1/integrations/zabbix/hosts/pipeline/versions` 接收 `pipeline-definition.schema.json`，返回不可变版本。相同 id/revision/digest 重试幂等；相同 id/revision、不同内容返回 409。
- `GET /api/v1/integrations/zabbix/hosts/pipeline/versions/{id}/{revision}` 读取发布内容并校验摘要。
- `POST /api/v1/integrations/zabbix/hosts/pipeline/preview` 接收 `pipeline-preview-request.schema.json`，将未发布定义应用到指定历史同步的留存 Raw。不会发布该草稿。
- `POST /api/v1/integrations/zabbix/hosts/pipeline/replay` 接收 `pipeline-replay-request.schema.json`，目标必须已发布且摘要一致。`dryRun` 缺省为 true，false 返回 400；这是只读映射重放，不是数据修复作业。
- 现有 `POST /api/v1/integrations/zabbix/hosts/sync` 可接收 `{"pipelineVersion":{"id":"...","revision":2,"digest":"sha256:..."}}`。不传 body 时始终使用内置 `zabbix-host-default@1`，不会跟随最新版本。第一次受权同步将内置版本登记到当前 tenant/source 后锁定。

所有接口要求配置来源的 `source.sync`。预览和重放还对每个展示的实体检查 `entity.read` 和对象范围；菜单显示不决定权限。发布端点要求 `application/json`。请求体最多 16 KiB；客户端必须使用发布/预览响应中的实际 digest，不能编造摘要。契约 examples 只有合成数据。

PostgreSQL 模式自动应用 `V007__pipeline_version.sql`：`integration.pipeline_version` 保存内容，`integration.sync_pipeline_pin` 在抓取来源前记录不可变运行绑定。应用只插入发布记录，不提供覆盖/删除接口；同版本并发写入由唯一约束处理。数据库管理员直接修改内容会在下一次读取时导致校验失败，不回退旧版本或 fixture。内存模式只用于开发，重启丢失版本与绑定；Raw 全局最多保留 1000 条。

新增同步的 Observation ID 包含版本摘要，Observation 字段及 Entity attributes 含 `pipelineId` / `pipelineRevision` / `pipelineDigest`，可沿 rawReference 找回 Raw 与 SyncRun。旧数据没有版本绑定时返回 `LINEAGE_UNAVAILABLE`，不会根据 revision 猜测历史发布内容。

## 从现有同步预览到显式版本同步

控制台“接入流水线”（`#/integrations/pipelines`）提供相同流程：填写内存中的开发 Token，输入已有批次或点击“同步 Host 并取得批次”，设置版本号、名称字段和拒绝策略，预览后再显式发布。也可读取已有发布版本后只读重放。表单变化会作废之前的预览，Token 变化会取消请求并清理批次/发布内容/结果。超时不能证明服务端发布或同步已回滚，应读取版本核对。页面中的来源标签、缺失/超大 Raw、失败批次和样本截断均明确显示。

1. 使用已有开发启动方式运行平台；fixture 必须显式开启，响应保持 `labeled-fixture`。真实模式抓取失败不会回退。
2. 调用 Host `/sync`，保留响应中的 `syncRunId` 和 `pipelineVersion`。
3. 复制 `contracts/examples/pipeline-definition.json`，改为新 revision。可在 Map 节点加入 `"config":{"displayNameField":"host"}`，将显示名取值从 `name` 改为 `host`。只支持这两个字段及固定回退规则。
4. POST `/pipeline/preview`，body 为 `{"syncRunId":"步骤2的ID","definition":{...},"limit":100}`。检查 rows、changed、rejected、wouldFailFast 和数据缺口。
5. 确认定义后 POST `/pipeline/versions` 发布同一内容。该显式 API 操作会保存版本，预览本身不会保存。
6. POST `/pipeline/replay`，传入历史 syncRunId、发布版本的 `targetVersion` 和 `purpose: "COMPARE_VERSION"`，验证历史 Raw 上的结果。
7. 需要让后续同步使用该版本时，显式将其引用传给 `/sync`；响应会回显实际绑定。发布一个更高 revision 不会自动影响正在运行或默认的同步。

Preview / Replay 比较的是同一 Raw 在原版本和候选版本下的映射结果，**不是当前库存差异，也不承诺将创建/删除多少资产**。`previous=null` 且候选接受表示旧映射拒绝、新映射修复；候选拒绝保留安全错误码，不回显原始 payload 或异常文本。缺失 hostid 不猜测补齐。

节点必须恰好为 Source → Parse → Map → Validate → EntityResolve → WriteObservation。没有任意脚本、表达式、网络或 SQL 节点。`skipRecord` 跳过缺失 hostid 并单独计数，保留来源 presence；内置版本显式采用此策略以维持现有行为。`failFast` 遇到该拒绝立即终止，不能把剩余数据视为缺失资产并对账。其它运行异常仍使扫描失败。预览评估整个有界样本，并用 `wouldFailFast` 提示该策略是否会中止同步。

## 有界与缺失语义

每次最多 100 条 Raw、每条 PostgreSQL JSON UTF-8 内容最多 64 KiB、每个服务进程最多两个并发评估；数据库查询/发布语句限时 5 秒。超大 Raw 保持 `RAW_TOO_LARGE` 行，内存开发存储使用更保守的字符估计。留存条数超过 limit 时 `truncated=true`；`missingRaw=max(0,fetched-retained)` 明确保留已知留存缺口。fetched 是持久化的扫描计数；失败期间 checkpoint 本身不可用时它可能低估数据缺失，不能据此声称完整快照。`sourceRunStatus=FAILED` 的报告只覆盖失败前实际留存记录。

运行中的扫描返回 `RUN_NOT_READY`。并发预算耗尽返回 503 `REPLAY_BUSY`。Source、Raw 或版本存储失败不转换为成功空结果。重放不抓取来源、不写 Entity/Observation、不更新 checkpoint、不对账、不发通知、不执行动作，因此重复请求也没有业务写入副作用。

摘要规范：SHA-256，前缀 `sha256:`。输入按 Java DataOutputStream 大端编码，依次写 `writeUTF(engine)`、`writeUTF(id)`、`writeInt(revision)`、`writeUTF(sourceType)`、`writeUTF(objectType)`、`writeUTF(errorPolicy wire name)`；再按执行链逐节点写 `writeUTF(id)`、`writeUTF(type wire name)`、`writeInt(config.size)`、按 key 排序的 `writeUTF(key/value)`。v1 所有允许字符串均为 ASCII。engine 固定 `zabbix-host-mapper-v1`，映射语义改变必须变更 engine。声明数组顺序不影响摘要，节点顺序和配置内容由已验证的执行链确定。

## 保存与载入私有草稿

草稿 API 根路径为 `/api/v1/integrations/zabbix/hosts/pipeline/drafts`，要求配置来源的 source.sync。保存内容是固定 Host PipelineDefinition，格式仍受既有 allowlist 约束；身份、owner、source 不由 body 指定。

- `POST /drafts` 接收 `{"definition":{...},"expectedEditVersion":0}` 创建；已有草稿更新需用上次读取的 editVersion。服务端返回 DRAFT、定义、digest、editVersion、updatedAt 和 storage。编辑号与目标发布 revision 不同。
- `GET /drafts/{id}/{revision}` 读取本人在当前 tenant/source 下的草稿，并验证 digest。不存在或不属于本人返回 404。
- `GET /drafts?limit=20` 读取最近草稿头，最多 50 条；truncated=true 表示列表不完整。较早草稿可填写 ID 和版本号精确读取。
- 旧编辑号保存返回 409 DRAFT_CONFLICT，不覆盖新内容。页面保留本地选项，需先记下修改，再读取最新草稿比较。保存超时可能已成功；读取核对，不使用无条件覆盖。

页面“保存草稿”不会发布或改变同步；“读取当前版本草稿”/“载入草稿”会替换当前表单并清除先前预览。保存后需要重新预览才能通过页面发布。发布提交的是当前预览的完整固定定义，另一页修改服务器草稿不会偷偷改变本次发布内容。发布 API 本身仍允许授权调用方提交固定定义，未提供服务器端预览审批凭证。

PostgreSQL 模式自动应用 V009 持久化，采用原子 compare-and-swap 防止并发覆盖；memory 明示服务重启丢失。Token 切换清理草稿、列表、表单与迟到响应。草稿没有共享、删除、TTL、完整编辑审计历史或总容量配额，不替代发布审批。详见 [ADR-019](../adr/019-private-pipeline-drafts.md)。

## 持久记录、幂等与恢复

控制台“只读重放”现在调用 `POST /api/v1/integrations/zabbix/hosts/pipeline/replay-runs`。body 沿用 syncRunId、targetVersion、purpose、limit、dryRun，另带调用方生成的 UUID `requestKey`，契约见 `pipeline-replay-run-request.schema.json`。身份、来源和 owner 均由服务器确定。相同键与参数返回同一记录，参数变化返回 `REPLAY_KEY_CONFLICT`；成功记录不会重新求值。只有明确要创建新一轮比较才使用新键。

返回 `storage`、`run` 和可空的 `report`。`run.state` 为 RUNNING / SUCCEEDED / FAILED，带 attempt、leaseUntil、failureCode 和 canResume；只有成功才包含报告。活跃重复请求返回 HTTP 202，正常完成返回 200；新执行失败保留 FAILED 记录并返回相应安全错误。响应丢失后可从历史核对，不将超时视为服务端回滚。

- `GET /pipeline/replay-runs?limit=20&before=<上页nextCursor>`：只列本人、当前 tenant/source 的记录头；最大 50 条，按创建时间与 ID 倒序。首次不传 before。
- `GET /pipeline/replay-runs/{id}`：读取当前状态与原报告。读取历史报告仍逐实体校验当前权限；撤权后返回 403。GET 不触发执行。
- RUNNING 租约为 120 秒。失败或过期后可使用原 requestKey 和完全相同参数显式 POST 恢复；最多执行三次。第三次租约过期后，相同 POST 只确认 FAILED / REPLAY_ATTEMPTS_EXHAUSTED。没有自动重试或后台调度。
- 页面“按原请求重试”保留不确定响应的请求键；“读取重放记录”用于刷新后恢复查询；“刷新记录状态”只读取；“恢复或确认过期记录”才会提交恢复。Token 变化清空旧身份数据。

PostgreSQL 模式自动应用 V008，报告和请求信息随数据库保留。`storage=memory` 仅开发进程内保存，刷新页面可查但服务重启即丢失；页面明确提示。短事务 claim、attempt fencing 和未过期租约共同阻止旧执行覆盖结果。报告序列化上限 1 MiB，超限返回 `REPLAY_RESULT_TOO_LARGE`，不截断为成功；原始 payload 不复制进结果。历史记录暂未提供 TTL/删除和总容量配额，不能当作无限容量生产仓库。详见 [ADR-018](../adr/018-durable-host-replay.md)。

已保存成功报告是当时留存样本的结果，不随 Raw 清理重新计算。失败恢复则读取恢复时可用 Raw，可能出现新的缺口；没有写入修复或完整快照承诺。

## 验证与后续

运行 `python scripts/check_java_domain.py` 验证纯领域；`gradlew.bat :apps:platform-api:test :apps:ingestion-worker:test` 验证 HTTP 和存储。PostgreSQL 测试需显式设置 `OPSWEAVE_TEST_JDBC_URL`、`OPSWEAVE_TEST_JDBC_USER`、`OPSWEAVE_TEST_JDBC_PASSWORD`；时序集成测试需 `OPSWEAVE_TEST_VM_URL`。没有这些变量会跳过，不能声称存储验证通过。`python -m pytest tests/contracts` 校验契约。

真实浏览器/Java/PostgreSQL 验收复用 [指标查询验收](metric-query-acceptance.md) 的隔离环境与启动包，构建 Web 后执行 `node scripts/check_metrics_stack.mjs --pipeline`。它先验收指标查询，再通过控制台预览 Raw、发布随机命名版本并重放，比较前后库存完全一致。使用随机开发 Token，业务请求不拦截，所有来源明确为 fixture。脚本关闭自己启动的 Java、浏览器和预览服务，不关闭调用方提供的存储。

已有固定 Host 映射表单、私有持久草稿/并发保存保护、预览、持久只读重放记录与显式恢复；尚无发布审批、后台重放调度或写入型历史修复。也没有厂商 Zabbix 实例验收、生产认证、分布式预算或生产 RLS。后续在可达真实来源上验收；配置未具备期间可继续资产筛选/分页/详情。Copilot 仍暂缓。
