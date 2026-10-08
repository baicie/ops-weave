# 本次交付验证报告 · OpsWeave v4

## 162. 2026-10-08 · 运行记录工作台视觉与交互复验

工作流运行历史和采集运行记录统一为紧凑的运维列表。工作流历史验证版本摘要、状态徽章、结果计数与节点明细入口；采集记录验证批次列表、状态/耗时/计数、保留边界、分页、运行 ID 回查和右侧只读详情抽屉。采集页的连接自检保持原能力但收纳到次级折叠区。详情抽屉使用非模态 `dialog`，因此身份撤销仍可从页面会话栏操作；会话变化清理列表、查询标识和详情。

| 实际检查 | 结果 |
|---|---|
| 契约 | `.tmp/mvp-check-venv/Scripts/python.exe -X utf8 -m pytest tests/contracts -o addopts= -q --tb=short`，1958 passed，退出0 |
| Java 纯领域 | `python -X utf8 scripts/check_java_domain.py` 通过；包含采集扫描、工作流历史和运行时相关 smoke |
| Rust 默认 | `CARGO_TARGET_DIR=.tmp/dev-checks/rust-current-default cargo test --workspace --locked -j 4`，41 项通过 |
| Rust all-features | `CARGO_TARGET_DIR=.tmp/dev-checks/rust-current-all cargo test --workspace --all-features --locked -j 4`，49 项通过 |
| Web | `pnpm --filter @opsweave/web-console typecheck` 与 `build` 均退出0；保留既有大 chunk warning |
| 浏览器 | `OPSWEAVE_TEST_CHROMIUM_EXECUTABLE=C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe pnpm --filter @opsweave/web-console exec playwright test e2e/source-scan-runs.spec.ts e2e/workflow-runs.spec.ts --project=chromium`，32/32 passed |
| 仓库与资料边界 | `scripts/check_repo.py` 通过（575个结构化文件、6个只读 Tool）；私有 `check_reference_boundary.py` 通过（2208个提交候选文件）；`git diff --check` 通过 |

浏览器场景使用契约 Fixture，不证明真实来源、生产身份/TLS、真实 PostgreSQL HTTP、多实例并发、生产容量或部署。默认 target 的并行 Rust 构建曾因运行中 Windows 可执行文件锁失败，随后使用隔离 target 顺序复验并通过；该环境失败不计为代码失败。

## 161. 2026-10-08 · 数据接入目录与回执交互复验

本轮保持数据源中心默认的已配置接入表，并把接入类型目录整理为可搜索、可分类的紧凑卡片；卡片显示已创建回执数量，查看任务进入独立回执页签。配置抽屉的指标页签统一为指标定义参考，完整指标键可进入定义页，并明确平台定义目录不等于当前来源已发现或启用采集。会话生命周期在浏览器缓存过渡期间不主动清除当前标签页凭据，真正卸载时仍清理。

| 实际检查 | 结果 |
|---|---|
| Web 类型检查 | `pnpm --filter @opsweave/web-console typecheck`，退出0 |
| Web 生产构建 | `pnpm --filter @opsweave/web-console build`，退出0；保留既有大 chunk warning |
| 数据接入列表 | 系统 Chrome 执行修正文案后的 `integration-lists.spec.ts`，8/8通过 |
| 来源中心 | 系统 Chrome 执行 `source-center.spec.ts`：19/21通过；失败项为浏览器历史导航下的既有会话/页面恢复用例，未把失败计为通过。内置 Playwright 浏览器路径缺失的运行也未计入结果 |
| 初始读取 | 系统 Chrome 执行 `integration-initial-read.spec.ts`：2/6通过；其余失败集中在多页签返回时标签节点被重建，未宣称自动读取回归通过 |
| 差异检查 | `git diff --check` 实际运行；未发现空白错误 |

测试使用 HTTP Fixture 和本机系统 Chrome，不代表真实来源、生产身份/TLS、数据库或部署验收。未修改跨语言业务契约或增加启动单元。

## 160. 2026-10-08 · 列表自动读取与多页签生命周期复验

本轮验证资产列表会在可信会话建立后自动读取一次；身份变化清理旧列表且不隐式重读。关系实例页仅在活动页签读取目录、资产和关系，离开页签会取消请求并阻止迟到响应填充隐藏页面。新增实体实例与关系实例 PostgreSQL 定向用例覆盖幂等、模型/端点版本 CAS、`asOf`、游标和租户边界。

| 实际检查 | 结果 |
|---|---|
| 契约 | `.tmp/mvp-check-venv/Scripts/python.exe -X utf8 -m pytest tests/contracts -o addopts='' -q`，1958 passed，退出0 |
| 仓库结构 | `.tmp/mvp-check-venv/Scripts/python.exe -X utf8 scripts/check_repo.py`，575 个结构化文件、6 个只读 Tool，退出0 |
| Java/Rust/Web | `python scripts/check_java_domain.py` 通过；`cargo test --workspace --all-features --locked` 49 项通过；`pnpm --filter @opsweave/web-console typecheck` 与 `build` 均退出0，保留既有大 chunk warning；平台 `compileTestJava` BUILD SUCCESSFUL |
| 浏览器回归 | 显式系统 Chrome 路径运行 `inventory.spec.ts` + `integration-initial-read.spec.ts` 共11项、`relation-instances.spec.ts` 6项、`workspace-layout.spec.ts` 14项，均通过；覆盖自动首次读取、隐藏页签取消、身份清理、关系分页、三种宽度和明暗主题 |
| PostgreSQL 定向 | `PostgresEntityInstanceIT` 2 tests/2 skipped、`PostgresRelationIT` 2 tests/2 skipped，0 failures/errors；Docker daemon、127.0.0.1:5432 和 `OPSWEAVE_TEST_JDBC_URL/USER/PASSWORD` 均不可用，不能计为 PostgreSQL 通过 |
| 差异 | `git diff --check` 退出0 |

以上浏览器响应使用契约 Fixture，服务端真实授权仍由应用边界负责；本轮未运行真实来源、生产身份/TLS、多实例并发、完整平台套件或部署验收。完整目标继续保持 active。

## 159. 2026-10-07 · 数据接入与运行能力最终定向复验

在前一轮因 shell 默认 JDK 不满足项目要求而中止后，本轮显式使用本机 JDK 21 完成数据接入后续阶段的编译、PostgreSQL 定向持久化和 HTTP 集成复验。注册实例仍固定绑定 endpoint、credential pin、tenant、host group scope 与 connection digest；发现快照对缺失配置、摘要漂移、过期和未来 `availableAt` fail-closed。旧版 Zabbix 全局入口的 fixture-only 边界保持不变。模型调用指标只按可信 tenant 聚合，工作流运行记录单条读取只读且按本人/tenant 隔离。

| 实际检查 | 结果 |
|---|---|
| Java 21 编译 | `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew :apps:platform-api:compileJava :apps:platform-api:compileTestJava --offline --console=plain`，`BUILD SUCCESSFUL` |
| PostgreSQL 定向集成 | 使用 `jdbc:postgresql://127.0.0.1:5432/opsweave_history_test`、本机用户和空密码实际执行 18 项，失败/错误/跳过均为 0：Item Sync 3、Source Connection 4、Source Inspection 6、Model Spend 4、Registered Sync Run Store 1；V061/V062 迁移实际加载 |
| HTTP 集成 | 同一 PostgreSQL 环境实际执行 `SourceConnectionCheckHttpIT` 2、`SourceInspectionHttpIT` 4、`SourceInstanceHttpIT` 3、`SourceScanRunHttpIT` 2、`SourceSnapshotHttpIT` 4、`WorkflowRuntimeHttpIT` 3、`ZabbixProblemHttpIT` 2，共 20 项；XML 汇总失败/错误/跳过均为 0，Gradle `BUILD SUCCESSFUL` |
| 契约与结构 | `.venv/bin/python -X utf8 -m pytest tests/contracts -o addopts='' -q`：1958 passed；`.venv/bin/python -X utf8 scripts/check_repo.py`：572 个结构化文件、6 个只读 Tool，均退出 0 |
| 领域、Rust 与 Web | `scripts/check_java_domain.py` 通过；Rust locked 默认 41、all-features 49，all-targets check 通过；Web typecheck/build 通过并保留既有大 chunk warning |
| 差异 | `git diff --check` 退出 0 |

以上数据使用隔离的本机测试 tenant、loopback 和明确标注的 Fixture/合成来源；不证明真实 Zabbix/VictoriaMetrics、生产身份/TLS、多实例并发、租约/fencing/HA、生产容量或部署。完整目标仍保持 active；未提交或推送。

## 158. 2026-10-07 · 旧版 Zabbix 全局入口边界收紧

四个 v1 兼容入口（Host Sync、Item Sync、History、Problem read/ingest）增加 fixture-only gate。配置不是明确 `fixture` 时，控制器不调用全局 connector/use case，返回 `503 source_unavailable` 并附 `X-OpsWeave-Legacy: fixture-only`；注册实例读取必须使用 v2 固定连接版本路径。OpenAPI、实现状态与路线图已同步 legacy 语义；成功响应 Schema 未增加未登记字段。

| 实际检查 | 结果 |
|---|---|
| 差异检查 | `git diff --check` 退出0 |
| 契约 | `.venv/bin/python -X utf8 -m pytest tests/contracts -o addopts='' -q`，1958 passed，退出0；OpenAPI 路径/请求边界定向7项也通过 |
| 仓库结构 | `.venv/bin/python -X utf8 scripts/check_repo.py`，572个结构化文件/6个只读 Tool，退出0 |
| Java 编译 | 本节首次尝试受当前 shell 的 JDK25 默认环境阻断；随后显式使用 JDK21 完成编译，见第159节。 |
| Spring HTTP/ PostgreSQL | 本节首次尝试未执行；随后在独立本机 PostgreSQL 环境完成 18 项 PG 与 20 项 HTTP 集成复验，见第159节。 |

## 157. 2026-10-07 · 数据接入后续阶段与运行能力定向验证

本轮将 V061/V062 注册连接范围迁移接入 `platform-api` 的资源复制任务，并在本机 PostgreSQL `opsweave_history_test` 上实际执行迁移和定向测试。注册 scan scope 的物理 `sourceInstanceId` 使用既有 `source_sync_run.source_instance_id` 持久化并在读取时恢复，因此没有新增迁移。注册实例继续要求固定 endpoint、credential pin、tenant、host group scope 与 connection digest；缺失固定配置的非 `fixture` 实例返回 `SOURCE_UNAVAILABLE`，不回退环境级来源。模型调用指标按可信 tenant 聚合；运行时单条执行查询按本人/tenant 隔离，只读且不重试。

| 实际检查 | 结果 |
|---|---|
| PostgreSQL 定向集成 | 上述首次定向记录为17项；最终显式补跑 Registered Sync Run Store 后为18项，失败/错误/跳过均0，详见第159节 |
| Java | `JAVA_HOME=<jdk21> ./gradlew :apps:platform-api:compileJava :apps:platform-api:compileTestJava --offline --console=plain`，`BUILD SUCCESSFUL`；`python3 -X utf8 scripts/check_java_domain.py` 通过，包含 WorkflowRuntimeSmoke 32 checks、WorkflowRuntimeControlSmoke 44 checks |
| 工作流运行时契约/HTTP | `.venv/bin/python -X utf8 -m pytest tests/contracts/test_workflow_openapi.py tests/contracts/test_workflow_runtime.py -o addopts='' -q`，15 passed；`WorkflowRuntimeHttpIT` 后续在独立 JDBC 环境实际3项通过，详见第159节 |
| 全量检查 | `.venv/bin/python -X utf8 -m pytest tests/contracts -o addopts='' -q`，1958 passed，退出0；`check_repo.py` 572个结构化文件/6个只读 Tool；Rust默认41/all-features49与all-targets check、Web typecheck/build；本轮新增改动 `git diff --check` 通过 |
| 平台完整 Gradle（现状记录） | 在共享本机 PostgreSQL 环境实际运行 `:apps:platform-api:test`，568项完成、36项失败、62项跳过；失败集中在既有共享数据库/迁移并发及多组历史 HTTP 集成断言，不能作为本轮全量通过依据；本轮首次独立定向为17项，最终复验为18项，另有20项定向 HTTP 与契约检查，详见第159节 |
| 尚未验收 | 真实 Zabbix/VictoriaMetrics、生产身份/TLS、多实例并发、持久 RunStore/lease/fencing/恢复引擎及生产可靠性；外部资料私有 policy 路径本轮仍未在仓库中发现，未伪造边界检查通过 |

## 156. 2026-10-07 · 注册连接兼容回退边界

注册实例读取现在要求已登记连接配置完整存在。非 `fixture` 实例缺少 endpoint、credential/configuration snapshot 或 connection digest 不再回退到环境变量来源，而是返回 `SOURCE_UNAVAILABLE`；只有明确标记为 `fixture` 的兼容实例仍可走旧 fixture 读取器。该边界同时覆盖工作流来源解析和注册连接发现读取，保持可信主体提供 tenant/权限，未增加启动单元或隐式重试。第149节中关于 Item Sync 范围未隔离的检查记录属于迁移前状态，当前范围语义以第150、152节的 `scopeDigest` fencing 为准。本轮还验证了租户范围的模型调用聚合 HTTP：只接受有界时间窗、模型和 limit，拒绝未知或重复参数，结果不含运行/主体/事件标识。

| 实际检查 | 结果 |
|---|---|
| 契约 | `.venv/bin/python -X utf8 -m pytest tests/contracts -o addopts='' -q`，1957 passed，退出0（首次运行补齐本机 `rfc3339-validator==0.1.4` 后重跑） |
| 仓库结构 | `.venv/bin/python -X utf8 scripts/check_repo.py`，572个结构化文件、6个只读 Tool，退出0 |
| Java | `python3 -X utf8 scripts/check_java_domain.py` 通过；JDK21 `./gradlew :apps:platform-api:compileJava :apps:platform-api:compileTestJava --offline --console=plain` BUILD SUCCESSFUL；定向 `SourceInspectionHttpIT`、`SourceConnectionCheckHttpIT` BUILD SUCCESSFUL |
| 模型指标 HTTP | `JAVA_HOME=<jdk21> ./gradlew :apps:platform-api:test --tests com.acme.opsweave.platform.ModelSpendHttpIT --offline --console=plain`，BUILD SUCCESSFUL；包含租户聚合、未知/重复 query、limit 边界 |
| Rust | `cargo test --workspace --locked` 41项通过；`cargo test --workspace --all-features --locked` 49项通过；`cargo check --workspace --all-targets --all-features --locked` 通过 |
| Web | `pnpm install --frozen-lockfile --ignore-scripts`、`pnpm --filter @opsweave/web-console typecheck`、`pnpm --filter @opsweave/web-console build` 均退出0，保留既有大 chunk warning |
| 差异 | `git diff --check` 退出0 |
| 未执行 | 未设置 `OPSWEAVE_TEST_JDBC_URL/USER/PASSWORD`，真实 PostgreSQL HTTP 用例由环境门禁跳过；未完成多实例并发、真实来源/身份/TLS和生产可靠性验收。外部资料边界本轮没有找到可用的仓库外私有 policy，未伪造该检查通过结果。 |

## 155. 2026-10-07 · 注册连接问题页交互与边界验证

在接入实例维护抽屉加入问题读取页签。首次打开自动读取最近一小时；开始/结束时间按本机时区编辑，查询转换为UTC秒，拒绝未来时间、逆序或超过24小时的范围。下一页保留已读取页的时间窗并只推进事件游标；改时间后从第一页重读。页面回显固定source UUID、配置revision、connection digest、host group和scope digest，严格校验页、事件及时间边界；503保持当前状态并要求用户显式刷新。

| 实际检查 | 结果 |
|---|---|
| 全量契约 | `.tmp/mvp-check-venv/Scripts/python.exe -X utf8 -m pytest tests/contracts -o addopts= -q --tb=short`，1955 passed，47.20秒，退出0 |
| Java边界 | `.\gradlew.bat :apps:platform-api:test --tests com.acme.opsweave.platform.workflow.RegisteredProblemBoundaryTest --rerun-tasks --offline --no-daemon --console=plain`，BUILD SUCCESSFUL，27 tasks executed |
| Web TypeScript与生产构建 | `pnpm --filter @opsweave/web-console typecheck` 退出0；`pnpm --filter @opsweave/web-console build` 退出0，保留Vite既有大chunk提示 |
| Chromium浏览器回归 | 显式本机Chromium运行 `registered-item-sync.spec.ts`，4项通过；包含问题页自动读取、翻页范围固定、编辑时间窗后重读、503不自动重试和既有指标同步回归 |
| 仓库结构与外部资料边界 | `scripts/check_repo.py` 通过，573份结构化文件/6个只读Tool；使用仓库外本机私有策略运行 `scripts/check_reference_boundary.py --policy <本机私有词表路径>`，2203个提交候选文件通过 |
| 差异 | `git diff --check` 退出0 |
| 未执行 | 真实PostgreSQL HTTP、多实例并发、真实来源/身份/TLS、完整平台集成测试和生产部署 |

首次浏览器回归为3/4通过：新增时间窗控件使用最近一小时默认值，测试Fixture沿用固定事件时间，页面按契约正确拒绝了窗口外事件。随后将测试Fixture事件时间改为响应当前请求的闭合窗口，复跑最终4/4通过；没有放宽产品校验。以上浏览器响应均为显式Fixture，不证明真实来源读取或数据库持久化。

## 154. 2026-10-07 · 注册问题 HTTP 路由与错误边界

修复问题分页 OpenAPI/Spring 路由占位符不一致和异常未接入统一 advice 的问题。公开路径和 `@GetMapping` 均使用 `{sourceId}`，控制器显式绑定同名 `@PathVariable`；`RegisteredProblemController` 加入 `WorkflowErrors`，`ProblemReadException` 映射为 FORBIDDEN=403、SOURCE_BUSY=429、其他来源失败=503，非法请求仍由既有 400 处理。

| 实际检查 | 结果 |
|---|---|
| Java 编译 | `./gradlew.bat :apps:platform-api:compileJava :modules:integration:compileJava :apps:platform-api:compileTestJava --offline --console=plain`，`BUILD SUCCESSFUL` |
| Java 边界测试 | 新增 `RegisteredProblemBoundaryTest`，随后随 `compileTestJava` 编译通过；本轮未宣称完整平台测试通过 |
| 定向契约 | `tests/contracts/test_registered_problem.py`、`test_data_source_openapi.py`、`test_request_boundary.py` 共26项通过 |
| 尚未执行 | 真实 PostgreSQL HTTP、多实例并发、完整平台 Gradle test、真实来源/身份/TLS、生产部署 |

## 153. 2026-10-07 · 注册连接问题契约样例与回归

为注册连接问题分页补充两份 v2 Fixture 和独立契约测试。样例固定 source UUID、configuration revision、connection/scope digest、host group 范围和只读问题结果；测试拒绝额外 endpoint/address/credential/权限字段，覆盖 revision、host group、scope digest、`afterEventId` 和页大小边界，并核对 OpenAPI 参数闭合、无 request body、唯一 operationId 与 200 响应 Schema。

| 实际检查 | 结果 |
|---|---|
| 定向契约回归 | `.tmp/mvp-check-venv/Scripts/python.exe -X utf8 -m pytest tests/contracts/test_registered_problem.py tests/contracts/test_registered_item_scan.py tests/contracts/test_data_source_openapi.py -q`，42项通过，退出0 |
| 全量契约回归 | `.tmp/mvp-check-venv/Scripts/python.exe -X utf8 -m pytest tests/contracts -q`，全量测试通过，退出0；同时发现并修复新增路径缺少全局 `clientRequestId` 参数的问题 |
| Fixture JSON | `python -m json.tool` 解析问题项和问题页样例均退出0 |
| 差异检查 | `git diff --check` 通过 |
| 尚未执行 | 真实 PostgreSQL HTTP、多实例并发、真实来源/身份/TLS、完整契约全集、完整 Playwright、生产部署；本节只证明契约和样例边界 |

## 152. 2026-10-07 · 注册连接问题分页与扫描范围 fencing

本节记录注册连接问题分页和扫描租约范围隔离的实际检查。问题页只使用路径中的 source UUID/configuration revision 固定连接；服务端从登记 revision 解析 endpoint、credential、tenant 和非空 host group scope，查询仅允许 `from`、`till`、`afterEventId`、`limit`。问题页为只读投影，不创建 Incident、通知或工作流动作。Item Sync 的物理 source 仍共享串行租约，租约比较和持久化额外绑定 `scopeDigest`，因此不同注册 revision 不能互相复用 scope 或把对方范围的缺失对象退休；新增 V062 为既有扫描表增加该列。

| 实际检查 | 结果 |
|---|---|
| Java 主代码/测试编译 | `./gradlew.bat :apps:platform-api:compileJava :modules:integration:compileJava :apps:platform-api:compileTestJava --offline --console=plain`，`BUILD SUCCESSFUL` |
| 问题 Schema 解析 | `python -m json.tool contracts/schemas/v2/registered-problem.schema.json` 与分页 Schema 均退出0 |
| 差异检查 | `git diff --check` 通过 |
| 注册问题专项契约 | `.tmp/mvp-check-venv/Scripts/python.exe -X utf8 -m pytest tests/contracts/test_registered_item_scan.py tests/contracts/test_data_source_openapi.py -q`，19项通过，退出0；问题 Schema/OpenAPI 形状包含在该专项回归中 |
| Java 纯领域边界 | `python scripts/check_java_domain.py` 退出0；包含 `source-scan-scope: 3 checks passed`、`Zabbix problem read smoke: 48 checks passed` 与 `Zabbix registered item connector smoke: 28 checks passed` |
| Web | `pnpm --filter @opsweave/web-console exec tsc --noEmit` 退出0；`pnpm --filter @opsweave/web-console build` 退出0，保留既有大 chunk warning |
| 结构化仓库检查 | `.tmp/mvp-check-venv/Scripts/python.exe -X utf8 scripts/check_repo.py` 退出0，571份结构化文件、6个只读 Tool 定义 |
| 尚未执行 | 真实 PostgreSQL HTTP、多实例并发、真实来源/身份/TLS、完整契约全集、完整 Playwright、Rust 和 Go 管理器回归；不得将本节 Java 编译或 Schema 解析扩大解释为这些验收 |

迁移、控制器、读取器、范围摘要和契约文件仍未提交；换机检查点已在 `docs/WORKSPACE-CHECKPOINT.md` 更新，提交后以新 commit 为恢复锚点。

## 150. 2026-10-06 · 注册连接指标目录同步与范围隔离

完成注册连接的 Item Sync 闭环：同步固定 source UUID、配置 revision 和 host group 范围；扫描回执记录 connection/scope digest 与退休数量；只读历史按同一 revision 分页，并在返回前复验租户、物理来源、对象类型、数据模式和摘要。仅已验证的 item 快照允许写入非零退休数；空清单、范围漂移、失租或失败不会退休绑定。前端实例抽屉增加“指标同步”页签，首次打开自动读取历史，手动同步期间禁用重复提交，未知结果提示刷新历史核对且不自动重试。

| 实际检查 | 结果 |
|---|---|
| UTF-8 Python 全量契约 | .tmp/mvp-check-venv/Scripts/python.exe -X utf8 -m pytest -q tests/contracts 退出0 |
| 新增注册 Item 契约 | 上述全量契约包含 test_registered_item_scan.py，退出0；默认 Python 3.11 未指定 UTF-8 时因 Windows GBK 解码现有中文样例退出1，不计为通过 |
| 结构化仓库检查 | .tmp/mvp-check-venv/Scripts/python.exe -X utf8 scripts/check_repo.py 退出0，568份结构化文件 |
| Java 纯领域 | python -X utf8 scripts/check_java_domain.py 退出0；包含 RegisteredItemScanRunQuerySmoke: 24 checks passed 与既有扫描 smoke |
| Java 平台定向测试 | ./gradlew.bat :apps:platform-api:test --tests com.acme.opsweave.platform.persistence.RegisteredSyncRunStoreTest --tests com.acme.opsweave.platform.persistence.PostgresItemSyncIT --offline --console=plain BUILD SUCCESSFUL；PostgreSQL 用例因 OPSWEAVE_TEST_JDBC_URL 未配置被环境门禁跳过 |
| Rust 默认/all-features | cargo test --workspace --locked -j 4 与 cargo test --workspace --all-features --locked -j 4 均通过，默认7+9+25项、全特性15+9+25项 |
| 差异检查 | git diff --check 退出0；保留既有 CRLF 规范化提示 |

未运行真实 PostgreSQL HTTP/多实例 Item Sync，也未运行本轮前端浏览器验收；完整目标继续 active。私有对标边界词表未在仓库内发现，未伪造检查结果。

## 149. 2026-10-06 · 注册连接范围下的指标/日志窗口回归

补齐现有受控工作流读取器的 `hostGroupIds` 协议回归：指标和日志分别覆盖范围内完整读取、主机已在范围外时不发 history 请求、以及窗口读取期间主机移出范围后拒绝完整结果。item 查询的组过滤与 `host.get` 独立成员核对均在 history 前后验证；fixture 返回仅用于协议测试。本轮没有调用外部来源、启动/重启服务或修改持久化行为。

窗口每次最多20个 history 分页 RPC。范围启用时，开始和结束各执行一次 item 元数据与 host 成员检查，所以总量上限为24个 JSON-RPC 请求；实际网络读取仍共享20秒期限。

| 实际检查 | 结果 |
|---|---|
| Python 3.14 contracts | `py -3.14 -X utf8 -m pytest -q tests/contracts` 退出0；quiet 配置未打印用例总数。默认 Python 3.11 与工作区依赖 Python 的 pytest 尝试因缺少 pytest 模块退出1，不计为通过 |
| Java 纯领域 | `python -X utf8 scripts/check_java_domain.py` 退出0；仅记录实际脚本退出状态，不由测试文件存在推定通过 |
| Java 定向窗口测试 | `./gradlew.bat :apps:platform-api:test --tests 'com.acme.opsweave.platform.workflow.ZabbixWorkflowMetricWindowTest' --tests 'com.acme.opsweave.platform.workflow.ZabbixWorkflowLogWindowTest' --offline` 通过；XML分别为9与8项，失败/错误/跳过均0 |
| Rust `--locked` | 默认41项与 `--all-features -j 1` 49项通过 |
| Web | `pnpm build:web` 通过；保留现有大 chunk warning。本轮未运行浏览器测试 |
| PostgreSQL HTTP/多实例 | 未运行，`OPSWEAVE_TEST_JDBC_URL` 未配置 |

未验证项：旧全局 History/Item Sync/Problem 入口仍读取环境级配置；本轮没有迁移这些入口。Item Sync 当前退休逻辑未按注册连接范围隔离，不能直接添加查询过滤后宣称安全。契约边界检查所需正式私有策略未找到，本轮未运行该检查。最终 `git diff --check` 退出0；保留既有 CRLF 规范化提示。完整目标保持 active。

## 148. 2026-10-06 · 关系实例工作台与授权分页

拓扑仍是只读投影；关系类型在独立工作台选择并创建。页面包含授权资产搜索/游标页、关系加载更多、租户已发布关系模型以及目录截断提示；关系分页后续请求复用首屏 `asOf`。抽屉以键盘圈闭焦点，Esc 可关闭并归还焦点；身份变化会清理当前资产和关系。内置端点类型在领域层按稳定键比较，不改写已发布模型的原值。后端扫描有界候选并按两端 `ENTITY_READ` 填充 limit+1 可见结果，返回游标锚定当前可见页末项；1000候选预算耗尽返回明确503。没有新增服务、表或迁移。

| 实际检查 | 结果 |
|---|---|
| Python 契约 | `& 'C:\Users\20555\AppData\Local\Programs\Python\Python314\python.exe' -X utf8 -m pytest -q` 全量通过。执行前按 `requirements-dev.txt` 安装缺失的 `rfc3339-validator==0.1.4`；修正 OpenAPI 路径测试以接受当前已登记的 v1/v2 API 路径，并继续逐路径断言 `clientRequestId` 可选参数 |
| Java 纯领域 | `python scripts/check_java_domain.py` 通过；关系 smoke 覆盖内置端点命名匹配、隐藏关系穿插、可见 lookahead、末页和1000候选扫描预算 |
| Java 定向测试 | `./gradlew.bat :apps:platform-api:test --tests com.acme.opsweave.platform.inventory.InventoryErrorsTest --offline --console=plain` 通过；验证扫描预算映射503且原授权拒绝仍403 |
| Web | 设置 `OPSWEAVE_TEST_CHROMIUM_EXECUTABLE` 为已安装 Chrome 路径后运行 `pnpm --filter @opsweave/web-console exec playwright test e2e/relation-instances.spec.ts --project=chromium`，6项通过；命令内的 `pnpm build` 执行 TypeScript `--noEmit` 与 Vite 构建。保留既有大于500KB的 chunk warning |
| Rust | `cargo test --locked` 默认41项通过；`cargo test --locked --all-features -j 1` 49项通过 |
| 平台完整 Gradle | `./gradlew.bat :apps:platform-api:test --offline --console=plain` Java 编译成功；547项中17项 PostgreSQL 集成测试在数据库初始化阶段失败，321项跳过。`OPSWEAVE_TEST_JDBC_URL` 未配置，真实 PostgreSQL HTTP/多实例分页验收未运行 |
| 差异检查 | `git diff --check` 退出0 |

一次 Playwright 复跑最初因默认缓存中没有 Playwright Chromium 而未启动测试；使用已安装 Chrome 的显式 executable path 后再次独占运行，6项全部通过。一次额外并发运行与另一个 preview 争用4173端口，导航失败；独占复跑结果为准。全站浏览器回归、真实 PostgreSQL、远程 CI 和生产部署未运行；完整目标保持 active。

## 147. 2026-10-06 · 模型约束实体实例写入

实体实例写入已接入可信主体、已发布实体模型和对象授权边界。服务端在持久化前执行字段清洗/校验，使用 expectedVersion 做 CAS，并以 requestId 保存原始回执；同一请求的服务端时间变化不会破坏稳定幂等匹配。来源扫描仍不能通过该接口绕过 fenced 写入。

实际通过：`./gradlew.bat :apps:platform-api:compileJava :apps:platform-api:compileTestJava --offline --console=plain`；`./gradlew.bat :apps:platform-api:test --tests com.acme.opsweave.platform.inventory.EntityInstanceStoreTest --offline --console=plain`（1 test，0 skipped/failure）；新增 `EntityInstanceSmoke` 随 `python scripts/check_java_domain.py` 执行；实体实例 command/receipt Schema 使用 Python 标准库解析；`git diff --check`。当前解释器缺少 pytest，契约回归未执行；缺少 `OPSWEAVE_TEST_JDBC_URL` 时未执行 PostgreSQL HTTP/多实例联测；本节不提高生产退出声明，完整目标继续 active。

为避免同一新 `requestId` 的并发事务同时观察到无回执，PostgreSQL 写入现以 tenant/requestId 获取事务级 advisory lock；新增六路相同创建的条件式集成用例，断言只写入一次且返回相同模型 pin。后续复验：`py -3.14 -X utf8 -m pytest -q tests/contracts` 全集达到100%并退出0（quiet配置没有打印用例总数）；`python -X utf8 scripts/check_java_domain.py` 退出0，全部领域 smoke 通过；`.\gradlew.bat :apps:platform-api:test --tests com.acme.opsweave.platform.inventory.EntityInstanceStoreTest --offline` 编译平台主代码和全部测试源码并通过内存存储测试；`pnpm build:web` 在本轮 Java 后端修改前通过，Web 源码未改。新增 PostgreSQL 并发集成测试已编译，但因 `OPSWEAVE_TEST_JDBC_URL` 未设置未连接数据库。

## 146. 2026-10-06 · 关系实例受控维护闭环

新增关系实例纯领域模型、发布关系类型与端点版本校验、双端实体授权和版本 CAS；`requestId` 作为稳定实例 ID/幂等键，重放返回原回执，改变请求内容返回冲突。V058 为既有关系投影增加请求键和关系版本，Java 平台提供关系实例有界分页与受控创建；读取要求 `entity.read`，写入要求 `entity.manage`，另一端未经授权时不会泄漏关系。`sourceRef` 只在服务端保存，响应与前端 adapter 不返回原始引用。拓扑页面保持只读，未把未验收的模型选择器和写入表单混入拓扑浏览。

实际通过：`./gradlew.bat :apps:platform-api:compileJava :apps:platform-api:compileTestJava --offline --console=plain`；`./gradlew.bat :apps:platform-api:test --tests com.acme.opsweave.platform.inventory.EntityRelationStoreTest --offline --console=plain`（1 test，0 skipped/failure）；`python scripts/check_java_domain.py`（包含 EntityRelationSmoke 6 checks）；关系四份 Schema JSON 解析；`pnpm --filter @opsweave/web-console build`；`git diff --check`。契约 pytest 因当前解释器缺少 pytest 未执行，PostgreSQL 关系 HTTP/真实多实例联测本轮未执行。Web build 保留既有大 chunk warning；本节不提高 M0–M4 生产退出声明。

## 145. 2026-10-06 · 工作流执行与输出 OpenAPI 契约对齐

平台 OpenAPI 草案登记已实现的工作流目录、草稿/版本、比较、发布、预览/运行、运行时执行与启停、主机调度、指标/日志持续流，以及指标/日志输出的能力、写入、回执、校验、历史和数据读取路径。草稿保存、发布、评估和指标流校验分别引用控制器实际接受的闭合请求 Schema；响应引用现有 v2 Schema，指标流校验补充 `{batchId}` Schema。契约描述保持可信身份、租户/对象范围、固定版本、CAS、预算和原回执边界，不把登记视为新增执行后端。

实际通过：工作流 OpenAPI 断言（通过）；文本结构检查 `153 paths / 163 operationIds`，重复 operationId 和缺失 Schema 均为 0，YAML 解析及外部 Schema 引用检查通过；`python -m py_compile tests/contracts/test_workflow_openapi.py tests/contracts/test_data_source_openapi.py`；`python scripts/check_java_domain.py`（运行时、持续指标/日志、输出及重放 smoke 通过）；`./gradlew.bat :apps:platform-api:compileJava --offline --console=plain`（BUILD SUCCESSFUL）；`pnpm --filter @opsweave/web-console build`（TypeScript 与 Vite build 通过，保留既有大 chunk warning）；`git diff --check`。完整 `python -m pytest` 因当前解释器缺少 pytest 未执行；Rust、PostgreSQL HTTP 和 Playwright 本轮未重跑。无新增数据库迁移、启动单元、权限或隐藏执行器，完整目标继续保持 active。

## 144. 2026-10-06 · 工作流来源绑定 OpenAPI 契约对齐

补齐平台 OpenAPI 草案中的工作流来源绑定路径：固定连接版本的指标/日志选择页，以及指标映射绑定的分页、详情、CAS维护和原回执查询。响应分别引用 `workflow-metric-source-page`、`workflow-log-source-page`、`metric-mapping-*` v2 闭合 Schema；描述明确发现选择只读取已保存快照，绑定维护由服务端检查租户/主体/来源范围、版本和请求幂等，不接受身份、地址或秘密覆盖。

实际通过：工作流来源与映射 OpenAPI 登记断言（通过）；`python -m py_compile tests/contracts/test_data_source_openapi.py`；`pnpm --filter @opsweave/web-console build`（Vite 1.22s，保留既有大 chunk warning）；`git diff --check`；OpenAPI 文本结构统计为 85 paths、94 个 operationId，重复均为 0。完整 pytest 因当前解释器缺少 pytest 未执行；Node 全局缺少 `js-yaml`，YAML 解析未执行；本轮未重跑 Java/Rust/PostgreSQL HTTP/Playwright。没有新增数据库迁移、启动单元、权限或采集动作，完整目标继续保持 active。

## 143. 2026-10-06 · 数据接入 v2 OpenAPI 登记与前端类型收敛

平台 OpenAPI 草案登记了数据接入 v2 的实例列表/详情/配置历史、连接创建与维护/历史、凭据创建与版本、实例级连接测试、连接检查历史、字段发现、指标发现及指标分页和原始检查回执路径。新增 `tests/contracts/test_data_source_openapi.py`，逐路径核对 HTTP 方法、operation 响应 Schema 和 Schema 文件存在性，并检查关键 operationId 唯一。实例维护抽屉统一兼容旧来源连接快照与版本化连接配置：空 endpoint 安全显示，连接摘要字段按类型收窄。

实际通过：`pnpm --filter @opsweave/web-console build`（Vite 1.27s，保留既有大 chunk warning）；使用仓库外显式 Chromium 路径运行 `pnpm --filter @opsweave/web-console exec playwright test e2e/source-instances.spec.ts --project=chromium`，13 passed；`python -m py_compile tests/contracts/test_data_source_openapi.py tests/contracts/test_workflow_quality_alerts.py`；`git diff --check`。`python -m pytest tests/contracts/test_data_source_openapi.py -q` 因当前解释器缺少 pytest 未执行；Node 全局没有 `js-yaml`，本轮 OpenAPI YAML 解析未执行；本轮未重跑 Java/Rust/PostgreSQL HTTP。没有新增数据库迁移、启动单元、权限或采集动作，完整目标继续保持 active。

## 142. 2026-10-06 · 质量阈值 OpenAPI 契约对齐

平台 OpenAPI 草案补上已实现的质量阈值状态、配置和原回执三条路径，三条路径均引用现有 `contracts/schemas/v2` 闭合 Schema，并在描述中明确只保存/评估元数据，不发送通知或执行动作。

实际通过：Node `js-yaml` 解析 `contracts/openapi/platform-draft.yaml`（58 paths，3 条质量阈值路径）；Node 路径存在性与 Schema 引用断言（3 条）；`python -m py_compile tests/contracts/test_workflow_quality_alerts.py`。`git diff --check` 通过。完整 `python -m pytest tests/contracts/test_workflow_quality_alerts.py -q` 因当前解释器缺少 pytest 未执行；`scripts/check_repo.py` 的 PyYAML 检查也未执行，不能以本节替代完整契约/结构检查。没有新增数据库迁移、启动单元或业务动作端口，目标继续保持 active。

## 141. 2026-10-06 · 质量阈值只读边界提示

质量阈值面板增加“只保存阈值并进行只读评估，不发送通知或执行动作”的明确提示。请求容器、规则默认停用、原 UUID 回执与权限门禁保持不变。

实际通过：`pnpm --filter @opsweave/web-console build`、`git diff --check`；设置仓库外已有的 `OPSWEAVE_TEST_CHROMIUM_EXECUTABLE` 后运行 `pnpm --filter @opsweave/web-console exec playwright test e2e/workflow-quality-alerts.spec.ts --project=chromium`，25 passed（53.6s），覆盖三种宽度、明暗主题、首次读取、保存/CAS、待确认回执、非法响应和身份清理。此前未设置浏览器路径的启动失败不计入通过。Rust、契约及 PostgreSQL HTTP 本轮未重跑；目标继续保持 active。

## 140. 2026-10-06 · 实例维护页切换到实例级连接检查

实例维护抽屉中的“测试保存的连接”现在调用 `/api/v2/data-sources/{id}/connection-check`，并继续提交原 `requestId`、`configurationRevision` 与 `connectionDigest`。回执仍由来源检查契约校验，配置变化、过期、UNKNOWN、越权和会话清理语义保持；历史从同一实例的 `/inspections` 读取。兼容的旧来源扫描页继续使用 v1 全局自检入口，避免把它解释为当前实例状态。

实际通过：`pnpm --filter @opsweave/web-console build`；`pnpm --filter @opsweave/web-console exec playwright test e2e/source-inspections.spec.ts --config=playwright.config.ts`（32 passed）；`./gradlew.bat :apps:platform-api:test --tests com.acme.opsweave.platform.PipelineHttpIT --offline --console=plain`（BUILD SUCCESSFUL，包含重复键和尾随 JSON 两项400负向）；`git diff --check`。本轮未重新执行 Rust、契约或 PostgreSQL HTTP 检查，不能用本节替代第139节的结果；目标继续保持 active。

资产旧版 Host 只读重放仍复用既有租约、幂等、本人/tenant隔离和无写入门禁；本轮审计发现其 JSON 边界允许重复键或尾随对象，已启用严格重复键与尾随 token 拒绝，并补充 HTTP 负向覆盖。

## 139. 2026-10-06 · 实例级连接检查历史按类型取数

本轮将实例级 `GET /api/v2/data-sources/{id}/connection-checks` 的历史查询下沉到存储层，先按 `TEST` 类型取最近 20 条，再返回结果；不会因较新的字段发现记录挤占连接检查历史。新增 `POST /connection-check` 仍复用现有 `TEST` 的固定配置版本、连接摘要、可信身份、凭据 pin、受控来源读取和 inspection 回执，不增加权限、迁移、数据库或启动单元。契约与连接检查操作说明已同步。

实际通过：`./gradlew :apps:platform-api:compileTestJava --no-daemon --console=plain` BUILD SUCCESSFUL（34s）；`python scripts/check_java_domain.py` 通过，包含新增混合历史断言，Source inspection smoke 38 checks passed；`pnpm --filter @opsweave/web-console build` 通过（Vite 仅保留既有大 chunk warning）；`cargo test --workspace --locked` 41 tests passed；`cargo check --workspace --all-targets --all-features --locked` 完成；`node --check scripts/acceptance/workflow-replay-provider.mjs` 通过；`git diff --check` 通过。前端关联回归由本轮协作代理实际执行，38 passed，覆盖接入初读、权限/503、实例维护、指标列表、多页签、三种宽度和明暗主题。

未通过或未执行项如实保留：`SourceInspectionHttpIT` 共 4 项因未设置 `OPSWEAVE_TEST_JDBC_URL/USER/PASSWORD` 被环境门禁跳过，不能算 HTTP/PG 通过；`python -m pytest tests/contracts -o addopts='' -q` 因当前解释器缺少 pytest 未执行；`python scripts/check_repo.py` 因当前解释器缺少 PyYAML 未执行。契约、仓库结构和真实 PostgreSQL HTTP 结果不能由本轮编译或 smoke 检查替代。目标继续保持 active。

最新本机追加记录见第 138 节。以下早期交付包的“未执行/未实现”按当时状态保留，不代表后续本机增量状态。

日期：2026-09-21。此报告区分实际执行、只提供源码及未具备执行条件三种状态。**未验证 Rust 编译，不声明模板可直接用于生产。**

## 1. 实际执行并通过

| 检查 | 命令 / 方法 | 结果 | 不代表什么 |
|---|---|---|---|
| 仓库结构与声明式契约 | `python scripts/check_repo.py` | 41 个 JSON/YAML 文件解析/检查，3 个只读 Tool 定义通过 | 不是全功能业务实现或安全认证 |
| Java 纯领域层 | `python scripts/check_java_domain.py` | javac 编译纯领域/接口代码，7 个 smoke 检查通过 | 不是 Gradle/Spring Boot 完整构建 |
| Python 契约测试 | `python -m pytest tests/contracts -o addopts='' -q` | 20 passed | 不等于 Rust 运行时测试 |
| Java 架构负例 | 临时在 domain 注入 `java.sql.Connection` import | 静态门禁正确退出1并报告违规；负例文件已移除 | 不是覆盖所有可能依赖的架构验证 |
| 发布前置项门禁 | `python scripts/check_release_inputs.py` | 按预期退出1，指出真实锁文件/Wrapper尚未生成 | 这是未完成初始化，不是构建通过 |
| 环境生成脚本 | 在临时目录运行两次 `init_env.py` | 首次生成随机开发凭据，二次拒绝覆盖 | 不是生产 Secret Manager；凭据未进入交付包 |
| TypeScript 语法 | 全局 TypeScript5.8.3 `transpileModule` | 2 个 TS/TSX 文件，0 个语法转换错误 | 未解析 React/Vite 类型，未进行 tsc typecheck 或 Vite build |
| 本地 Markdown 链接 | 检查仓库内 Markdown 的相对路径 | 打包前校验所有文档链接存在 | 不是外部网站永远可访问的承诺 |
| 交付完整性 | ZIP 文件清单与 SHA-256 manifest | 打包时逐文件核验 | 不替代签名、SBOM或安全扫描 |

Python用于开发契约检查，**没有部署Python Agent**。

## 2. 已编写但没有执行

`apps/agent-runtime/tests/runtime_tests.rs` 含 **25 项 Rust 测试**，涵盖：

- tenant/incident scope、权限、无身份覆盖；
- 时间窗口、历史asOf、证据availableAt、当前访问过期与模型返回后的再次检查；
- 重复/伪造证据、缺口披露、共享并发调用预算；
- Skill加载、输出JSON限制、未知动作字段拒绝；
- Axum端点的健康/就绪分离、认证、closed与demo行为。

**本交付环境没有 cargo、rustc、rustfmt、clippy。**容器无法连接外部依赖/工具链下载地址，未能补装。所以这些测试只是源码，不是通过记录；默认feature、Rig/MCP可选feature、Rust格式化和Clippy都需要初始化时执行。

## 3. 未验证或未实现

| 项目 | 原因 / 后续动作 |
|---|---|
| Cargo.lock与工具链精确锁 | 需在可信联网/内部镜像环境真实生成、编译、固定版本 |
| npm package-lock、tsc、Vite build | 未下载完整依赖；需本地npm初始化与完整构建 |
| Gradle Wrapper、依赖锁、Spring Boot启动 | 缺Gradle及完整外部依赖；不能由纯javac检查替代 |
| Docker / Compose / SQL | 无Docker运行环境；迁移、RLS、HA、备份未执行 |
| Rig实际模型调用 | 无调用凭据、未编译适配；单独开关并按提供商测试 |
| MCP server联调 | 仅probe源码，无在线MCP服务；生产授权尚未实现 |
| Java Tool Gateway / Rust真实HTTP Port | 待开发；当前严格标明fixture，不能冒充生产数据 |
| 持久AIRun / 任务恢复 / 自动化 | 只有设计与部分类型/SQL原型，不能宣称高可用或exactly-once |
| 吞吐 / 延迟 / 费用 | 没有压测数据。代码预算是本地demo上限，不是性能SLO |

## 4. 接手后必须跑

```bash
cargo generate-lockfile
cargo fmt --all
cargo test --workspace --locked
cargo check --workspace --all-targets --all-features --locked
cargo clippy --workspace --all-targets --all-features --locked -- -D warnings
pnpm install --lockfile-only
pnpm install --frozen-lockfile
pnpm build:web
# 首次用可信Gradle生成Wrapper，再完整构建Java应用。
```

依赖或SDK API出现差异时，以固定发布文档与真实编译错误为准修复adapter，再提交锁和验证结果。不要删除权限、预算、证据校验来“让demo跑通”。

完整功能状态参见 `IMPLEMENTATION-STATUS.md`。本报告只描述本次交付；后续测试结果应追加日期、命令、环境和原始输出，不覆盖成一个模糊的“全部已通过”。

## 5. 2026-09-21 本地初始化（追加）

环境：macOS aarch64；rustc 1.98.1（48a229cea 2026-09-01）；JDK Temurin 21.0.12.1+1；Gradle Wrapper 8.14.3；Node v26.9.0 / npm 11.19.1；Python 3.12。crates.io CDN 超时后，本机 Cargo 通过 USTC sparse 镜像拉取 Rig/MCP 依赖；该镜像配置在用户 `~/.cargo/config.toml`，未写入仓库。Docker daemon 当时不可用，未拉取/固定生产镜像 digest，也未启动 Compose。

| 检查 | 命令 | 结果 | 不代表什么 |
|---|---|---|---|
| 仓库结构与契约 | `python scripts/check_repo.py` | 41 个结构化文件；3 个只读 Tool 定义 | 不是业务实现或安全认证 |
| Java 纯领域 | `python scripts/check_java_domain.py` | 7 个 smoke 通过 | 不是运行中的业务 API |
| Python 契约测试 | `python -m pytest tests/contracts -q` | 20 passed | 不等于集成/E2E |
| Cargo 锁 | `cargo generate-lockfile` | 生成 `Cargo.lock`（673 packages） | 不是 SBOM 或漏洞扫描 |
| Rust 格式化 | `cargo fmt --all` 后 `cargo fmt --all -- --check` | 通过 | 不是 API 稳定性承诺 |
| Rust 默认测试 | `cargo test --workspace --locked` | 25 passed / 0 failed | 使用 fixture/Mock，不是真实 RCA |
| 全 feature 编译 | `cargo check --workspace --all-targets --all-features --locked` | Finished `dev`（含 `rig-provider`、`mcp-client`） | 未对真实模型或 MCP server 发请求 |
| Clippy | `cargo clippy --workspace --all-targets --all-features --locked -- -D warnings` | 通过 | 不是安全审计 |
| npm 锁与前端 | `npm install --package-lock-only --ignore-scripts`；`npm ci`；`npm run build:web` | lock 已生成；Vite 8.3.0 构建成功。解析版本：React 19.3.0、TypeScript 5.9.3、plugin-react 6.1.1 | 未接生产 OIDC/BFF |
| Gradle Wrapper | `gradle wrapper --gradle-version 8.14.3 --distribution-type bin` | `gradlew` / `gradle-wrapper.jar` 已生成 | 发行包来自官方 8.14.3 |
| Java 锁与 bootJar | `./gradlew :apps:platform-api:dependencies :apps:ingestion-worker:dependencies --write-locks`；`./gradlew :apps:platform-api:bootJar :apps:ingestion-worker:bootJar` | BUILD SUCCESSFUL；两个 lockfile 已写入 | 业务接口默认 deny-all；未连 PostgreSQL |
| 发布前置项 | `python scripts/check_release_inputs.py` | Bootstrap files exist | 仍需安全审查、SBOM、集成测试 |
| 本地 `.env` | `python scripts/init_env.py` | 已生成且 gitignore | 不是生产 Secret Manager |

工具链已写入 `rust-toolchain.toml` channel `1.98.1`；CI rustup 与 `deploy/docker/agent.Dockerfile` 标签改为 `rust:1.98.1-slim-bookworm`。**未记录镜像 digest。未跑 demo 进程、未调用付费模型、未联调 MCP。**

## 6. 2026-09-21 前端改为 pnpm（追加）

将根 workspace 从 npm 切换为 pnpm 10.34.3：增加 `pnpm-workspace.yaml`、`.npmrc`（`ignore-scripts=true`、`engine-strict=true`），`package.json` 写入 `packageManager`，发布门禁改为要求 `pnpm-lock.yaml`。删除 `package-lock.json`。CI / Dockerfile / Jenkinsfile 改为 `pnpm install --frozen-lockfile`。

| 检查 | 命令 | 结果 | 不代表什么 |
|---|---|---|---|
| pnpm 锁 | `pnpm install --lockfile-only` | 生成根 `pnpm-lock.yaml`（lockfileVersion 9.0；React 19.3.0、Vite 8.3.0、TypeScript 5.9.3、plugin-react 6.1.1） | 不是 SBOM |
| frozen 安装与构建 | `pnpm install --frozen-lockfile`；`pnpm typecheck:web`；`pnpm build:web` | 通过；Vite 8.3.0 生产构建成功 | 未接生产 OIDC/BFF |
| 发布前置项 | `python scripts/check_release_inputs.py` | 要求 `pnpm-lock.yaml` 与 `pnpm-workspace.yaml`；通过 | 仍需安全审查 |
| 仓库结构 | `python scripts/check_repo.py` | 43 个结构化文件 | 计数含 pnpm YAML 锁 |

## 7. 2026-09-21 Web Console 改为 Zeus + Zeus UI（追加）

将 `apps/web-console` 从 React 换为 Zeus 0.1.1-beta.2 + Zeus UI 0.1.0-beta.4 原生 Web Components（`@zeus-web/ui/button`、`@zeus-web/ui/input`）。未加入 `@zeus-web/chat` / `@zeus-web/agent-console`。Java 与 Rust 源码未改。资产/指标/Skill/Agent 页仍为未实现占位。

Vite 插件 `@zeus-js/vite-plugin` 0.0.4 使用已发布的 `@zeus-js/compiler` 0.1.0。该编译器会把 `{props.children}` 和 `array.map(...)` 编成文本绑定，且 `<For>` 运行时传入 accessor；控制台用 DOM 子节点、`<Show>`/`<For>` 和 `forItem` 适配，不把这当成 Zeus 源码仓库已修好。

| 检查 | 命令 / 方法 | 结果 | 不代表什么 |
|---|---|---|---|
| 依赖安装 | `pnpm install` | 解析 `@zeus-js/zeus` 0.1.1-beta.2、`@zeus-web/ui` 0.1.0-beta.4、`@zeus-js/vite-plugin` 0.0.4、Vite 8.3.0、TypeScript 5.9.3 | lock 仍记录 Zeus UI 包的可选 React peer；不是把 React 当控制台运行时 |
| typecheck / 生产构建 | `pnpm typecheck:web`；`pnpm build:web` | 通过。产物约 35 kB JS / gzip 13.6 kB | 未做包体积基线或性能对比 |
| 仓库结构 / 发布门禁 | `python scripts/check_repo.py`；`python scripts/check_release_inputs.py` | 43 个结构化文件；Bootstrap files exist | 不是安全审查 |
| 本机诊断 UI | `bash scripts/demo.sh`（127.0.0.1:8090）+ `pnpm dev:web`（127.0.0.1:5173）；浏览器打开诊断页，内存中填写开发 Token 后点「运行只读诊断」 | 出现合成摘要、证据引用、缺失数据与限制；hash 切到资产/Agent 占位页 | 不是 OIDC、真实 Zabbix，也不是 chat/agent-console 联调 |

**未接入、未宣称：** `@zeus-web/chat`、`@zeus-web/agent-console`、data-grid、生产 BFF。未测量长列表性能。该节对应的源码已提交为 `e5b5827`。

## 8. 2026-09-21 路线图与兼容性清单（追加）

将规划写入 `docs/ROADMAP.md`，将已验证前端组合写入 `docs/FRONTEND-COMPATIBILITY.md`。当时 M0 清单未勾选；第 9 节之后更新了本地验收项。

| 检查 | 命令 | 结果 | 不代表什么 |
|---|---|---|---|
| frozen 安装与构建 | `pnpm install --frozen-lockfile`；`pnpm typecheck:web`；`pnpm build:web` | 工作树已有 `node_modules` 时通过；Vite 8.3.0，`index-nEEgEZ7B.js` 35.22 kB | 不是干净 clone；不是 CI Node 22 job |
| 产品源码 React | 搜索 `apps/web-console/src` 与直接依赖 | 无 `react` / `react-dom` import | lock 仍含 Zeus UI 可选 React peer |

## 9. 2026-09-21 M0 本地安装与诊断页 Playwright（追加）

环境：macOS aarch64；Node v26.9.0；pnpm 10.34.3；Playwright 1.55.1 Chromium 140.0.7339.186（build v1193）。`ignore-scripts` 下浏览器需单独 `playwright install chromium`。本机 `http_proxy` 会使 Playwright 的 URL 探活误判，webServer 改为检查 TCP `4173`。

`@zeus-web/ui` 的 JS 入口未列入 package `sideEffects`，生产包原先不含 `customElements.define`。控制台改为导入 `@zeus-web/button/wc/auto` 与 `@zeus-web/input/wc/auto`，CSS 仍来自 `@zeus-web/ui`。相邻 JSX 文本插值改为单个模板字符串。

| 检查 | 命令 / 方法 | 结果 | 不代表什么 |
|---|---|---|---|
| 无 node_modules 拷贝 | `rsync` 排除 `node_modules`/`dist`/`target` 后 `pnpm install --frozen-lockfile`；`pnpm typecheck:web`；`pnpm build:web` | 通过。当时产物 `index-CUkaLau-.js` 35.32 kB（尚未含 WC 注册） | 不是 `git clone`；不是 CI runner |
| 含 WC 注册的生产构建 | `pnpm build:web` | Vite 8.3.0；`index-DbhRecI-.js` 58.72 kB / gzip 20.55 kB；`zw-button`/`zw-input` 懒加载 chunk；CSS 7.61 kB | 不是包体积基线 |
| 诊断页 E2E | `pnpm test:web`（`page.route` 拦截 `POST /agent/api/v1/diagnoses`） | **7 passed**（注册 WC、token 长度、提交与证据文本、401、503、取消、卸载后不写回） | 不需要本机 Rust Demo；不是 OIDC；未测 IME |
| 仓库结构 / 发布门禁 | `python3 scripts/check_repo.py`；`python3 scripts/check_java_domain.py`；`python3 -m pytest tests/contracts -q`；`python3 scripts/check_release_inputs.py` | 44 个结构化文件；7 项 Java smoke；20 passed；Bootstrap files exist | 不是安全审查；本节未复跑 Rust / Gradle |

**未接入、未宣称：** chat/agent-console 已接入、生产 BFF。未把 Playwright 的 JS unzip 卡死当作上游缺陷（本机用 `ditto` 解开已下载的 zip）。GitHub Actions 当时尚未作为本节证据。

## 10. 2026-09-21 GitHub Actions（`b5404a8`）

查阅命令：`gh run list --branch main`；`gh run view 35600475743`。

Run：https://github.com/baicie/ops-weave/actions/runs/35600475743  
Head：`b5404a817e832cfa13c9b52a005477a453ef031d`。conclusion = **success**。

| Job | 结果 |
|---|---|
| contracts | success |
| rust | success |
| java | success |
| web | success |

web job 含 Node 22.18、`pnpm install --frozen-lockfile`、typecheck、生产构建、Playwright Chromium 与 `pnpm test:web`。这证明 ubuntu checkout 可按锁文件安装并跑通诊断页 E2E，不等于生产 OIDC、真实 Zabbix 或 chat/agent-console 已接入。

## 11. 2026-09-22 M1 身份与 Zabbix Host 切片（追加）

环境：macOS aarch64；JDK Homebrew OpenJDK 21.0.12.1（Gradle daemon）；`javac` 默认仍可能是本机 Temurin 25，领域检查使用 `--release 21`。未对厂商 Zabbix 或 Keycloak 发请求。

| 检查 | 命令 | 结果 | 不代表什么 |
|---|---|---|---|
| 仓库结构与契约 | `python3 scripts/check_repo.py` | 47 个结构化文件；3 个只读 Tool 定义 | 不是生产认证 |
| Java 领域 smoke | `python3 scripts/check_java_domain.py` | `DomainSmoke` 7；`IdentityAuthorizationSmoke` 11；`ZabbixHostMappingSmoke` 12 | 不是可达 Zabbix |
| Python 契约 | `python3 -m pytest tests/contracts -q` | 22 passed | 不是 E2E 接入 |
| Gradle | `JAVA_HOME=<jdk21> ./gradlew :apps:platform-api:test :apps:platform-api:bootJar :apps:ingestion-worker:bootJar` | **10 tests, 0 failures**；两个 bootJar 成功 | 未跑 Rust / pnpm / Playwright；内存库存不是 PostgreSQL |
| 身份行为 | 上述 Gradle 测试 | 无 token → 401；query `tenantId` → 400；缺 `entity.read` / `source.sync` → 403；OIDC 模式拒绝启动 | 不是 Keycloak |
| Host 链 | 上述 Gradle 测试 + 领域 smoke | `labeled-fixture` 写入 2 个 host Entity；JSON-RPC 客户端打本机协议桩 | 不是厂商 `host.get` |

本节记录 `bb9a7e2`。PostgreSQL、分页对账和资产页见第 12 节。当时未宣称生产 OIDC、Item/Trigger 或 Integration Copilot。

## 12. 2026-09-22 Host 分页同步与 PostgreSQL（追加）

环境：macOS aarch64；Gradle 使用 Homebrew OpenJDK 21.0.12.1。本机 5432 上已有 PostgreSQL，角色 `opsweave_dev` 不存在，因此 JDBC 测试使用当前系统角色新建的库 `opsweave_host_sync`。未对厂商 Zabbix 发请求。

| 检查 | 命令 | 结果 | 不代表什么 |
|---|---|---|---|
| Java 领域 smoke | `python3 scripts/check_java_domain.py` | 7 + 11 + 20 项通过。含两页 fixture、完整快照才 retire、第二页失败不 retire | 不是厂商 Zabbix |
| 契约 | `python3 scripts/check_repo.py`；`python3 -m pytest tests/contracts -q` | 47 个结构化文件；22 passed | 不是安全审查 |
| Gradle | `JAVA_HOME=<jdk21> ./gradlew :apps:platform-api:test --offline`，环境变量指向本机 `opsweave_host_sync` | 11 tests，0 failures，0 skipped。其中 `PostgresHostSyncIT` 实际连上 PostgreSQL | 这次没有重跑 bootJar。更早一次未设 JDBC 时，该测试被跳过，两个 bootJar 已成功 |
| PostgreSQL | 同上测试类 `PostgresHostSyncIT`，`OPSWEAVE_TEST_JDBC_URL` 指向本机 `opsweave_host_sync` | 1 test 通过：两页写入、缺席 Host 变为 INACTIVE、后续失败扫描不把已同步 Host 标失活 | 不是 Compose 里的 `opsweave_dev`，也不是厂商实例 |
| 资产页 | `pnpm --filter @opsweave/web-console test:e2e` | 8 passed。库存用例：同步后显示 Host 字段，503 后表格仍在，请求不带 tenant。诊断页卸载后进入资产页能看到标题「资产」 | 浏览器请求被 Playwright 拦截，不是连着真实 platform-api |

CI java job 增加了 `postgres:17` 服务，并设置 `OPSWEAVE_TEST_JDBC_URL`。本轮还没有新的 GitHub Actions 结果。

## 13. 2026-09-22 同步错误码与 host.get 游标（追加）

环境：macOS aarch64；Gradle 使用 Homebrew OpenJDK 21.0.12.1。`OPSWEAVE_ZABBIX_URL` 未设置。未对厂商 Zabbix 发请求。

| 检查 | 命令 | 结果 | 不代表什么 |
|---|---|---|---|
| Java 领域 smoke | `python3 scripts/check_java_domain.py` | 7 + 11 + 32 + 15。含空快照才 retire、写库失败 / 游标不前进 / Raw 失败不 retire，以及 `host.get` 的 hostid 排序与 offset | 不是厂商 Zabbix，也不是 1000 台 Host |
| Gradle | `JAVA_HOME=<jdk21> ./gradlew :apps:platform-api:test --offline` | 11 tests，0 failures，0 skipped。协议桩要求请求含 `sortfield=hostid` 与 `offset=0`。`PostgresHostSyncIT` 仍连本机 `opsweave_host_sync`，失败码为 `SOURCE_FETCH_FAILED` | 不是厂商实例 |
| 资产页 | `pnpm --filter @opsweave/web-console test:e2e` | 8 passed。第二次同步 503 显示 `SOURCE_FETCH_FAILED`，已有行仍在 | 请求被 Playwright 拦截 |

## 14. 2026-09-22 Host presence 与 MetricDefinition（追加）

环境：macOS aarch64；Gradle 使用 Homebrew OpenJDK 21。`OPSWEAVE_ZABBIX_URL` 未设置。未对厂商 Zabbix 发请求。没有把采样点写入 PostgreSQL。

| 检查 | 命令 | 结果 | 不代表什么 |
|---|---|---|---|
| Java 领域 smoke | `python3 scripts/check_java_domain.py` | 7 + 11 + 46 + 15 + 30。含映射拒绝仍保留 presence、offset 漂移会漏掉中途变化的 Host、失败响应保留页数，以及 `system.cpu.util[,user]` → `host.cpu.usage.user` | 不是厂商 Zabbix，也不是 1000 台 Host |
| Gradle | `JAVA_HOME=<jdk21> ./gradlew :apps:platform-api:test --offline` | 13 tests，0 failures，2 skipped。跳过的是两个 PostgreSQL 测试。`ZabbixItemSyncIT` 通过 | 该次没有 JDBC |
| PostgreSQL | 同上，仅 `PostgresHostSyncIT` 与 `PostgresItemSyncIT`，JDBC 指向本机 `opsweave_host_sync` | 2 tests，0 skipped，0 failures。Item 定义写入，缺席 item 变为 INACTIVE | 不是厂商实例 |
| 资产页与结构 | `pnpm --filter @opsweave/web-console test:e2e`；`python3 scripts/check_repo.py` | Playwright 8 passed，失败提示含已扫描页数。47 个结构化文件 | 浏览器请求被拦截 |

## 15. 2026-09-22 指标目录与来源绑定（追加）

环境：macOS aarch64；Gradle 使用 Homebrew OpenJDK 21。`OPSWEAVE_ZABBIX_URL` 未设置。未对厂商 Zabbix 发请求。没有把采样点写入 PostgreSQL。本轮没有改页面，没有重跑 Playwright。

| 检查 | 命令 | 结果 | 不代表什么 |
|---|---|---|---|
| Java 领域 smoke | `python3 scripts/check_java_domain.py` | 7 + 11 + 46 + 15 + 41。两台主机共用一条目录、映射来自 YAML、映射器源码不含该 item key 与 metric key、缺席 item 只失活绑定 | 不是厂商 Zabbix |
| Gradle | `JAVA_HOME=<jdk21> ./gradlew :apps:platform-api:test --offline` | 13 tests，0 failures，0 skipped。环境里已有 `OPSWEAVE_TEST_JDBC_URL=jdbc:postgresql://127.0.0.1:5432/opsweave_host_sync`，因此 `PostgresHostSyncIT` 与 `PostgresItemSyncIT` 都实际执行 | 不是厂商实例，也不是一次没有 JDBC 的运行 |
| 结构 | `python3 scripts/check_repo.py` | 47 个结构化文件，3 个只读工具定义 | 未跑 Web |

## 16. 2026-09-22 有界 History 读取（追加）

环境：macOS aarch64；Gradle 使用 Homebrew OpenJDK 21.0.12.1；领域检查 `javac --release 21`；rustc 1.98.1；Node v26.9.0；pnpm 10.34.3；Python 3.14.6。`OPSWEAVE_ZABBIX_URL` 和 `OPSWEAVE_TEST_JDBC_URL` 均未配置。本次修改在本地分支 `codex/bounded-history-read`，未推送或部署。

实现：`GET /api/v1/integrations/zabbix/items/{itemId}/history`；活动绑定、来源/实体/指标授权、完整秒窗口、纳秒游标、十进制字符串、配置换算及 min/max 校验。来源实际 value_type 来自逐批 `item.get`，不用目录 DOUBLE 推断 history=0。响应明确 `persistence=not-persisted`。详见 ADR-016。

| 检查 | 命令 / 方法 | 结果 | 不代表什么 |
|---|---|---|---|
| 仓库结构与契约 | `python3 scripts/check_repo.py`；`python3 -m pytest tests/contracts -q` | 50 个结构化文件，3 个只读工具定义；30 项契约测试通过 | 不是厂商实例验收 |
| Java 纯领域 | `python3 scripts/check_java_domain.py` | 7 + 13 + 11 + 16 + 46 + 15 + 41 = **149 项 smoke 检查通过**；含同秒续读、截断边界、密集秒拒绝、时间范围、uint64 精度、映射 min/max | 不等于已采集落库 |
| Java 测试与启动包 | `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew :apps:platform-api:test :apps:platform-api:bootJar :apps:ingestion-worker:bootJar --offline` | **23 tests，0 failures，0 errors，2 skipped**；两个 bootJar 成功。新增 History 相关 10 tests 均执行 | 两个 PostgreSQL IT 因无 JDBC 配置跳过；本轮未验证数据库连接 |
| 实际 HTTP 响应契约 | `ZabbixHistoryIT` 写出合成响应到 `apps/platform-api/build/test-results/history-page.json`，Python `Draft202012Validator` + `FormatChecker` 对照 `contracts/schemas/v1/metric-history-page.schema.json` 校验 | 通过；构建目录产物不提交 | 不是手写样例替代实际响应；仍为显式 fixture |
| JSON-RPC 协议与边界 | 上述 Gradle 中 `ZabbixHistoryReaderIT`、`HistoryAuthorizationTest`、`ZabbixHistoryIT` | 本机 HttpServer 验证 item.get/history.get、0/3 类型、无 offset、纳秒排序、单位换算；非法值/单位范围/对象/次序/超大响应/上游失败拒绝；租户/对象/权限隔离、4 并发预算与无身份覆盖通过 | 不是厂商 Zabbix，也不证明真实数据完整性 |
| Rust 默认 | `cargo test --workspace --locked` | **25 passed，0 failed** | fixture/Mock，无真实模型调用 |
| Rust 全 feature | `cargo test --workspace --all-features --locked` | **25 passed，0 failed**；Rig/MCP 适配已编译 | 无真实模型/MCP 服务调用 |
| Rust 格式与静态检查 | `cargo fmt --all -- --check`；`cargo clippy --workspace --all-targets --all-features --locked -- -D warnings` | 通过；Clippy 曾等待另一项目的 Cargo 缓存锁，随后正常完成 | 不是安全审计 |
| TypeScript / Web | `pnpm typecheck:web`；`pnpm build:web`；`pnpm test:web` | 类型检查、Vite 构建通过；**8 Playwright passed** | 页面未增加指标曲线；浏览器请求仍被测试拦截 |
| 发布文件门禁 | `python3 scripts/check_release_inputs.py`；`git diff --check` | 通过 | 不是生产发布验收 |

最终运行输出摘录（测试总数从 Gradle XML 汇总，包含跳过项）：

```text
HistoryPageSmoke: 13 checks passed
MetricPointSmoke: 16 checks passed
BUILD SUCCESSFUL in 1m 46s
{'tests': 23, 'failures': 0, 'errors': 0, 'skipped': 2}
Actual HTTP fixture response conforms to metric-history-page v1
test result: ok. 25 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out
8 passed (22.2s)
```

当前基线进度另外实查：`gh run list --limit 5 --json databaseId,workflowName,headSha,status,conclusion,url` 与 `gh run view 35680270637 --json jobs,headSha,conclusion`。提交 `f0bcc32e7e81c2a72cb41bd98f19a285ba9f1e2f` 的 [GitHub Actions](https://github.com/baicie/ops-weave/actions/runs/35680270637) 中 contracts/web/rust/java/deploy 五个 job 均 success。**这是上一提交的 CI/部署记录，不是本次 History 修改的 CI 结果；未独立检查远端服务运行态。**

尚未验证/实现：厂商 `history.get`、VictoriaMetrics 写入/查询、毫秒精度冲突策略、持久采集 checkpoint、迟到点重叠/幂等、Worker 定时采集、生产 OIDC。History 游标不代表持久写入完成；空结果/扫描完成不代表以后不会有迟到数据。

## 17. 2026-09-22 Worker → VictoriaMetrics → checkpoint（追加）

先将第 16 节提交 `3651112` 推送到 `origin/codex/bounded-history-read`。[该提交的 CI](https://github.com/baicie/ops-weave/actions/runs/35693183287) 实查 conclusion=success。随后在同一分支实现默认关闭的单流采集、批写前/后回读、毫秒冲突与 float64 精度校验、PostgreSQL 事务锁及 checkpoint。

环境：macOS aarch64；Homebrew OpenJDK 21.0.12.1；PostgreSQL 17.10（本机系统角色，新建隔离测试库 `opsweave_history_test`）；VictoriaMetrics v1.152.0 官方 darwin-arm64 二进制，监听 `127.0.0.1:18428`，1ms 去重、非流式 Influx。Docker daemon 不可用，实际存储联调用的是原生二进制。VM 压缩包和二进制分别对照官方 checksums 校验，SHA-256 为 `2867ec3ce6f190be6c391a77d116a0bcde6a06a304799b3d248dbf97bac1f5fd`、`e82c9346d97420c2dd5d331052495531ed574c1689086595e0cf29b2ae349710`。临时二进制/数据位于 gitignore 的 `.tmp`，不提交。

| 检查 | 命令 / 方法 | 最终结果 | 不代表什么 |
|---|---|---|---|
| 仓库/契约 | `python3 scripts/check_repo.py`；`python3 -m pytest tests/contracts -q` | 50 个结构化文件、3 个只读 Tool；30 项契约测试通过。结构检查排除 `.tmp` 运行时缓存 | 未增加任何 Agent 写入 Tool |
| 纯领域 | `python3 scripts/check_java_domain.py` | **179 项 smoke 检查通过**。含重叠窗口、先写后推进、失败保留游标、目标变更拒绝、同毫秒冲突、数值精度与未声明适配身份拒绝 | 不代表生产吞吐 |
| Java 全套与启动包 | 配置 `OPSWEAVE_TEST_JDBC_URL/USER`、`OPSWEAVE_TEST_VM_URL`，`JAVA_HOME=<jdk21> ./gradlew :apps:platform-api:test :apps:ingestion-worker:test :apps:platform-api:bootJar :apps:ingestion-worker:bootJar --offline` | **Platform 23 + Worker 12 = 35 tests，0 failures，0 errors，0 skipped**；两个 bootJar 通过 | 来源是 fixture/协议桩，未连接厂商 Zabbix |
| PostgreSQL | 上述测试的 Host/Item IT 与 `PostgresHistoryCheckpointIT` | 实际连本机测试库；checkpoint 回滚、重建 Store 后恢复、tenant/source/item/stream 键隔离、并发锁通过 | 不是租约/fencing 或 HA 验收 |
| VictoriaMetrics | `VictoriaHistoryIngestionIT` 实连 v1.152.0 | 迟到点补入、重复轮询、确认后中断/重放、已有值冲突、保留期外点不可视作已确认写入通过 | 查询可见不是跨库事务或断电持久化证明 |
| 实际 Java 进程链 | 设置上述测试存储变量与 `JAVA_HOME`，`python3 scripts/check_history_stack.py` | **PASS**：临时 Token、真实 platform-api、定时 ingestion-worker、真实 VM 与 PG；fixture 的 `1789992001` / `0.40` 点可查询且 checkpoint 提交。检查后关闭两个 Java 进程 | Python 仅开发检查，不是执行后端；仍是合成来源 |
| Rust 默认/全 feature | `cargo test --workspace --locked`；`cargo test --workspace --all-features --locked` | **25 + 25 passed** | 未调用真实模型/MCP |
| Rust 格式/Clippy | `cargo fmt --all -- --check`；`cargo clippy --workspace --all-targets --all-features --locked -- -D warnings` | 通过 | 不是安全审计 |
| Web | `pnpm typecheck:web`；`pnpm build:web`；`pnpm test:web` | 类型/构建通过；**8 Playwright passed** | 本轮未增加指标页面 |
| 依赖与交付配置 | `./gradlew :apps:ingestion-worker:dependencies --write-locks --offline`；`docker manifest inspect victoriametrics/victoria-metrics:v1.152.0`；`docker compose ... --profile metrics config --quiet`；`python3 scripts/check_release_inputs.py`；`git diff --check` | Worker JDBC 锁由 Gradle 生成；镜像 manifest 可取得、Compose 配置及文件门禁通过 | 未运行本机 Docker Compose、未固定生产镜像 digest |

回归过程曾因真实 VM 新序列可见延迟超出初始确认预算而失败，checkpoint 未提交。独立探测观察到完整标签查询约 10 秒才可见，因此最终采用最多 11 次、间隔 2 秒的回读预算；未确认仍返回失败。表中 Java 总数来自最后一次完整成功运行的 XML，不使用早先失败运行作通过证据。

最终输出摘录：

```text
HistoryIngestionPolicySmoke: 14 checks passed
HistoryIngestionSmoke: 16 checks passed
BUILD SUCCESSFUL in 27s
platform-api {'tests': 23, 'failures': 0, 'errors': 0, 'skipped': 0}
ingestion-worker {'tests': 12, 'failures': 0, 'errors': 0, 'skipped': 0}
Java platform -> scheduled Java worker -> real VictoriaMetrics + PostgreSQL: PASS (labeled fixture source)
8 passed (8.8s)
```

CI java job 已加入 Worker tests 和独立 VictoriaMetrics 容器；本节本地结果不替代随后推送的 CI 状态。远端部署配置仍默认关闭采集。本次不声明厂商接入、生产鉴权、自动回补所有迟到点、分布式采集或物理 exactly-once。下一步是有资源授权与点数限制的时序查询 API、指标页与真实来源验收。

## 18. 2026-09-24 checkpoint 租约与序列互斥（追加）

把采集锁从「一个 PostgreSQL 事务包住平台读取和 VictoriaMetrics 回读」改成两段短事务：先提交 fencing token 与 300 秒租约，网络工作结束后再用 `revision + fencing_token` 条件更新。主键改为 tenant/source/item。`stream_name` 只保留为任务名；同一 item 的另一个任务名返回 `CONFIGURATION_INVALID`。

运行前清空了本机测试库 `opsweave_history_test` 里的旧 checkpoint 行。那些行来自旧主键（含 stream_name）的重复测试数据，V002 遇到同一 item 的多行会拒绝迁移。

| 检查 | 命令 / 方法 | 最终结果 | 不代表什么 |
|---|---|---|---|
| 纯领域 | `python3 scripts/check_java_domain.py` | Java domain smoke 7 项、HistoryIngestionPolicySmoke 14、HistoryIngestionSmoke 16、HistoryPageSmoke 13、Identity 11、MetricPointSmoke 16、Zabbix host mapping 46、host page 15、item mapping 41，均通过 | 不覆盖 PostgreSQL 租约 |
| Worker 测试 | `OPSWEAVE_TEST_JDBC_URL=jdbc:postgresql://127.0.0.1:5432/opsweave_history_test`、`OPSWEAVE_TEST_JDBC_USER=liuzhiwei`，`JAVA_HOME=<jdk21> ./gradlew :apps:ingestion-worker:test --offline` | 15 tests：12 通过，0 失败；`VictoriaHistoryIngestionIT` 3 项因未设置 `OPSWEAVE_TEST_VM_URL` 跳过 | 未重跑平台测试、VictoriaMetrics、`check_history_stack.py`、Rust 或 Web |
| PostgreSQL | `PostgresHistoryCheckpointIT` 6 项 | 失败不推进、重建后恢复、tenant/source/item 隔离、第二 streamName 拒绝、失败释放租约、租约占用返回 `CHECKPOINT_BUSY`、替换 fencing token 后不能提交 | 不是多绑定调度或 HA 验收 |

远端 Compose 仍未启用 History。本次不声明查询 API、指标页或生产部署已经完成。

## 19. 2026-09-24 lease 后的存储回归与查询适配器（追加）

使用本机 PostgreSQL 库 `opsweave_history_test`（系统角色 `liuzhiwei`）和已有的 VictoriaMetrics v1.152.0 二进制，监听 `127.0.0.1:18428`，参数为 `-dedup.minScrapeInterval=1ms -influx.forceStreamMode=false -retentionPeriod=1y`。数据目录在 gitignore 的 `.tmp`，不提交。

| 检查 | 命令 / 方法 | 最终结果 | 不代表什么 |
|---|---|---|---|
| Worker 全量 | `OPSWEAVE_TEST_JDBC_URL=jdbc:postgresql://127.0.0.1:5432/opsweave_history_test`、`OPSWEAVE_TEST_JDBC_USER=liuzhiwei`、`OPSWEAVE_TEST_VM_URL=http://127.0.0.1:18428`，`JAVA_HOME=<jdk21> ./gradlew :apps:ingestion-worker:test --offline --no-build-cache --rerun-tasks` | 15 tests，0 failures，0 errors，0 skipped。含 checkpoint 6 项与 `VictoriaHistoryIngestionIT` 3 项 | 未重跑平台全套、Rust、Web 或 `check_history_stack.py` |
| 查询适配器 | 同一 VM，`./gradlew :apps:platform-api:test --tests com.acme.opsweave.platform.telemetry.VictoriaMetricsQueryAdapterIT --offline --no-build-cache --rerun-tasks` | 1 test 通过：两个来源保持两条 series，空指标为 `NO_DATA`，端口不可达为 `SOURCE_UNAVAILABLE` | 不是查询 HTTP API，也没有权限用例或指标页 |
| 纯领域 | `python3 scripts/check_java_domain.py` | 原有 smoke 通过，另加 `MetricSeriesQuerySmoke` 6 项 | 不覆盖真实 VM |

尚未提供 `QueryMetricSeriesUseCase`、`GET /api/v1/entities/{entityId}/metrics/{metricKey}/series` 和 Zeus MetricsPage。查询适配器只生成固定 export 选择器，不接受 PromQL/MetricsQL。

## 20. 2026-09-24 指标查询授权、HTTP 与指标页（追加）

VictoriaMetrics 导出缺少 `source_instance_id`、`data_mode`、`external_item_id`、`unit` 或 `mapping_revision` 时返回 `INVALID_RESPONSE`，不再抛出空指针。响应体用有界订阅者在超过 2MB 时取消读取。`QueryMetricSeriesUseCase` 只检查 `entity.read` 和 `metric.read`，再确认实体与指标目录存在。未配置 `OPSWEAVE_VICTORIAMETRICS_URL` 时查询端口是关闭实现。指标页提供资产、指标和 Last 15m/30m/1h；`partial` 时显示数据不完整。曲线绘制放在可替换 SVG 适配器中，没有引入图表库。

| 检查 | 命令 / 方法 | 最终结果 | 不代表什么 |
|---|---|---|---|
| 适配器负例 | `JAVA_HOME=<jdk21> ./gradlew :apps:platform-api:test --tests com.acme.opsweave.platform.telemetry.VictoriaMetricsQueryContractTest --offline --no-build-cache --rerun-tasks` | 2 tests，0 failures。缺身份标签与超过 2MB 的导出都是 `INVALID_RESPONSE`，大响应在写完前被取消 | 不是厂商 Zabbix，也没有重跑真实 VM 双来源 IT |
| 纯领域 | `python3 scripts/check_java_domain.py` | 原有 smoke 通过，`QueryMetricSeriesSmoke` 6 项通过：授权、缺实体、缺指标、关闭存储、未来窗口 | 不覆盖 Spring MVC |
| 契约结构 | `python3 scripts/check_repo.py` | 50 个结构化文件通过 | 不是运行中的 HTTP 调用 |
| Web 类型 | `pnpm --dir apps/web-console typecheck` | `tsc --noEmit` 通过 | 未跑 Playwright，未在浏览器里对真实平台完成一次查询 |

没有重跑 Worker 的 PostgreSQL / VictoriaMetrics 全量，也没有把指标页接到生产图表库。

## 21. 2026-09-25 指标查询完整回归与真实存储浏览器验收（追加）

在 `main` 的 `febfc53` 基线上直接修改工作区，没有新建分支。本节记录本机运行结果，不表示已经推送、通过新的 GitHub Actions 或部署。

修复：`application.yml` 的 `zabbix` 缩进曾使 `secretRef` 无法绑定、平台启动失败；指标 HTTP 非数字参数原先经过错误分派返回 401，改为明确 400；存储值数组/负时间校验和返回对象/单位检查；扫描耗尽且窗口内无点时保留 `PARTIAL`，不返回已确认的 `NO_DATA`。前端修复 Zeus 编译器未渲染块体 `For` 回调的问题，统一使用现有表达式组件写法；SVG 使用正确命名空间、单点有可见标记。Token 或筛选变化会清理旧结果并取消请求，每条来源独立展示最新值与 `dataMode`。

新增 `metric-series-page` Schema/样例、HTTP 与浏览器测试，以及 `scripts/check_metrics_stack.mjs`。脚本用随机开发 Token 启动真实 Java 进程和生产前端预览；浏览器业务请求不拦截，来源仍为明确标注的 fixture。复现步骤见 [指标查询验收](runbooks/metric-query-acceptance.md)。

环境：Windows x64；Temurin Java 21.0.11；Gradle Wrapper 8.14.3；Node 24.16.0；pnpm 10.34.3；Rust 1.98.1；Playwright 1.55.1 / Chromium 140（build 1193）。隔离 Docker PostgreSQL `postgres:17`、VictoriaMetrics `v1.152.0`，随机端口仅发布至 `127.0.0.1`，显式配置 JDBC/VM 测试环境变量。未读取或使用厂商 Zabbix 凭据。

| 检查 | 实际命令 / 方法 | 结果 | 边界 |
|---|---|---|---|
| 依赖安装 | `pnpm install --frozen-lockfile` | 通过，锁文件未修改 | 未升级依赖 |
| 仓库 / 契约 | 临时 `python:3.12-slim` 开发容器中安装 `requirements-dev.txt`，运行 `python scripts/check_repo.py`、`python -m pytest tests/contracts -q` | **52 个结构化文件、3 个只读 Tool；42 项通过** | Python 仅为开发检查 |
| 纯 Java 领域 | 本机 Node 临时 runner 按 `check_java_domain.py` 相同源码集合调用 `javac --release 21 -encoding UTF-8`，逐一执行 11 个 smoke main | **195 项检查通过** | 本次未将该结果冒充 Python 脚本执行；新增查询范围与空部分结果负例 |
| Java 全量与启动包 | `gradlew.bat :apps:platform-api:test :apps:ingestion-worker:test :apps:platform-api:bootJar :apps:ingestion-worker:bootJar --console=plain --rerun-tasks` | **Platform 36 + Worker 15 = 51 tests；0 failures、0 errors、0 skipped；两个 bootJar 成功** | 真实连接本机隔离 PG/VM；HTTP 存储协议负例使用标注的协议桩 |
| HTTP 响应契约 | `MetricSeriesHttpIT` 输出的 `build/test-results/metric-series-page.json` 用 `Draft202012Validator` + `FormatChecker` 对照新 Schema 校验 | 通过 | 实际 Java HTTP 响应；数据仍为 fixture |
| Rust 默认 | `cargo test --workspace --locked` | **25 passed** | 无真实模型 / MCP 调用 |
| Rust 全 feature | `cargo test --workspace --all-features --locked -j 2` | **25 passed** | 第一次默认并发编译出现标准库 metadata 错误；最终此命令完整成功，未修改工具链版本或锁 |
| Rust 格式 | `cargo fmt --all -- --check` | 通过 | 本轮未另跑 Clippy |
| Web 类型 / 构建 | `pnpm typecheck:web`；修复后 `pnpm build:web`（含 `tsc --noEmit`） | 通过，Vite 8.3.0 | 使用固定 Zeus 依赖，无新图表库 |
| Web 浏览器回归 | `node apps/web-console/node_modules/@playwright/test/cli.js test --config .tmp/playwright-local.config.ts --max-failures=1` | **18 passed**，含新增 10 项指标用例 | 临时配置只指定本地解压的同版 Chromium、测试目录与 cwd，继承仓库配置；业务 HTTP 被测试拦截 |
| 真实存储浏览器链 | 配置 loopback 测试环境变量及本地 Chromium 路径，`node scripts/check_metrics_stack.mjs` | **PASS**：Chromium → 生产构建预览代理 → Java 授权 HTTP → PostgreSQL 元数据 / VictoriaMetrics 采样点；已查看截图 | 查询全链路，无请求拦截；合成 Zabbix 来源，采样点由开发验收脚本插入；不替代厂商或 Worker 采集验收 |
| 发布文件门禁 | `python scripts/check_release_inputs.py` | 通过 | 不是生产发布批准 |

本机 Python 包安装和 Playwright 的 JS 解压曾停滞，最终使用一次性 Python 开发容器检查契约，用 Windows `tar` 解压 Playwright 已下载的同版官方 Chromium ZIP。早先环境失败及修复前的用例失败均未计为通过。截图保存在 gitignore 的 `.tmp/metrics-acceptance/metrics.png`。

仍未验收：厂商 Zabbix、生产 OIDC、PipelineVersion/Preview/Replay、告警/Incident 到真实 AI 的闭环、生产容量/HA/备份恢复。M2/M3 不能因此宣告全部退出；本节关闭的是受控指标查询及页面的本机验收缺口。

## 22. 2026-09-25 Host 版本发布、预览与只读重放（追加）

继续直接在 `main` 工作区开发，保留第 21 节未提交修改，没有创建分支或推送。新增固定 Host 映射表单和 API：PipelineVersion 发布内容/digest 不可覆盖，同步运行在抓取前固定版本；无 body 仍选内置 revision 1，不随最新版本变化。历史 Raw 按 tenant/source/run 读取，Preview 接收未发布定义，Replay 要求已发布版本及 digest，默认且仅支持 dry-run。旧运行缺少版本绑定时不捏造血缘。同步与 Observation 记录具体版本，失败不对账。

修复旧定义校验允许错误节点顺序的问题，并让缺失 hostid 时的 `failFast` 生效；内置流水线改为明确的 `skipRecord`，维持先前跳过拒绝记录的行为。只允许固定六节点和 name/host 显示名选择，不执行脚本。预览可展示旧映射拒绝、新映射修复的结果，保留缺失/截断/超大 Raw 与失败批次标记。服务没有库存写入、来源抓取、通知或动作端口。表单、Token 或页面变化会取消/作废旧请求；发布或同步超时不宣称服务端回滚。

环境延续第 21 节的 Windows/JDK/Gradle/Node/pnpm/Rust/Chromium。另启隔离 `postgres:17` 与 VictoriaMetrics `v1.152.0`，端口只发布到 loopback，随机临时数据库密码不进入报告。自动应用平台迁移 `V007__pipeline_version.sql`。操作和摘要规范见 [Host 流水线](runbooks/host-pipeline.md)。

| 检查 | 实际命令 / 方法 | 最终结果 | 边界 |
|---|---|---|---|
| 仓库与契约 | 临时 `python:3.12-slim` 中 `python scripts/check_repo.py`；`python -m pytest tests/contracts -o addopts= -q` | **62 个结构化文件、3 个只读 Tool；63 passed** | 新增发布/预览/重放/结果 Schema、样例，摘要规范交叉校验与身份/预算/脚本负例；Python 仅为开发检查 |
| 纯 Java 领域 | 本机临时 Node runner 以 `javac --release 21 -encoding UTF-8` 编译 modules 和 tests/domain，执行全部 12 个 smoke main | **232 项检查通过**，新增 PipelineVersionSmoke 37 项 | 同 `check_java_domain.py` 源码集合；实际使用 Node runner，不冒称运行了 Python 脚本 |
| Java 全量与启动包 | `gradlew.bat :apps:platform-api:test :apps:ingestion-worker:test :apps:platform-api:bootJar :apps:ingestion-worker:bootJar --console=plain --rerun-tasks` | **Platform 41 + Worker 15 = 56 tests，0 failures/errors/skipped；两个 bootJar 成功** | 实际连接隔离 PG/VM；包括发布并发冲突、存储适配器重建后读取 pin/Raw、保留缺口、超大 Raw；不冒称 OS 进程重启或 HA 验收 |
| 实际 HTTP 契约 | `PipelineHttpIT` 保存的 fixture 发布/重放 JSON 用 Draft202012Validator + FormatChecker 对照 contracts 校验 | **通过** | HTTP 覆盖发布幂等/冲突、未发布版本同步拒绝、digest、dryRun false/越界/身份覆盖拒绝与库存未变化 |
| Rust 默认 / 全 feature | `cargo test --workspace --locked`；`cargo test --workspace --all-features --locked -j 2` | **各 25 passed** | 没有真实模型/MCP 调用 |
| Rust 格式 | `cargo fmt --all -- --check` | **通过** | 没有额外运行 Clippy |
| Web 类型 / 构建 | `pnpm typecheck:web`；浏览器测试 webServer 执行 `tsc --noEmit && vite build` | **通过** | 表单模板直接消费 contracts 样例，未增加依赖或修改锁 |
| 浏览器回归 | `node apps/web-console/node_modules/@playwright/test/cli.js test --config .tmp/playwright-local.config.ts --max-failures=1` | **25 passed**，新增 7 项流水线用例 | 此组业务请求按契约拦截：流程、已有版本读取、Token 变化、缺失标记、纯文本主机名、错误批次/写入标志/503 拒绝。已查看截图 |
| 浏览器真实存储链 | 配置 `OPSWEAVE_TEST_JDBC_*`、`OPSWEAVE_TEST_VM_URL` 和本地 Chromium，运行 `node scripts/check_metrics_stack.mjs --pipeline` | **PASS**：真实指标查询链；浏览器 Preview → 发布 → Replay → PostgreSQL Raw，重放前后库存完全一致 | 无浏览器业务请求拦截；来源明确为 labeled fixture。截图 `.tmp/metrics-acceptance/pipeline.png`；不替代厂商来源验收 |
| 文件门禁 | `python scripts/check_release_inputs.py`；`git diff --check` | **通过** | 无提交、推送、新 CI 或部署 |

验证期间第一次完整 Java 运行因临时脚本写成 `OPSWEAVE_TEST_VICTORIA_URL` 而跳过 4 项时序存储测试；改为测试实际读取的 `OPSWEAVE_TEST_VM_URL` 后，以上最终 56 项全部执行通过。浏览器首轮有一项新用例未选择契约样例的 failFast 策略，修正测试输入后最终 25 项全部通过；未把此前失败/跳过记为成功。

本轮交付的是 Host 固定映射、不可变版本、预览/只读重放及其控制台操作闭环。写入型历史修复、持久草稿/重放作业、厂商 Zabbix、生产 OIDC、跨进程预算和生产 RLS 仍未实现或未验收。M2/M3 不因此整体退出，Copilot 继续暂缓。

## 23. 2026-09-25 持久只读重放记录、幂等与显式恢复（追加）

继续在 `main` 工作区开发，没有创建分支、提交、推送或触发部署。保留前两节修改，补齐 `/pipeline/replay-runs` 创建/读取/分页接口与控制台历史记录。记录按可信 tenant、配置 source 和当前 subject 隔离；相同 requestKey 必须对应完全相同参数，成功返回原报告。120 秒租约、最多三次显式执行和 attempt fencing 防止旧执行覆盖；GET 不恢复任务，活跃重复 POST 返回 202。第三次租约过期后，相同 POST 只确认终止。契约、错误码、V008 迁移、操作说明与 [ADR-018](adr/018-durable-host-replay.md) 同步提供。

存储的是有界映射摘要与请求元数据，不复制原始 payload。历史列表不加载报告；读取报告和成功幂等返回重新校验每个旧/新实体的当前权限。失败保存安全错误码，不把异常文本写入报告。库存、Observation、来源扫描、通知和动作均不由重放触发；`writesPerformed=false` 指业务写入，不否认保存重放记录本身。开发内存模式在页面明确标注重启丢失。

环境仍为 Windows/JDK 21、Gradle 8.14.3、Node 24、pnpm 10、Rust 1.98.1 与本地 Chromium；临时 `postgres:17` 和 VictoriaMetrics `v1.152.0` 仅发布 loopback 端口，随机数据库密码不写报告。

| 检查 | 实际命令 / 方法 | 最终结果与范围 |
|---|---|---|
| 仓库与契约 | `python:3.12-slim` 内执行 `python scripts/check_repo.py`、`python -m pytest tests/contracts -o addopts= -q` | **70 个结构化文件、3 个只读 Tool；79 passed**。包含请求键/身份覆盖/预算/状态与报告一致性负例 |
| 纯 Java 领域 | 临时 Node runner 用 `javac --release 21 -encoding UTF-8` 编译 modules 与 tests/domain 并执行全部 13 个 smoke main | **263 项通过**；新增重放 31 项，包括去重、作用域、撤权、租约/fencing、三次上限、显式失败恢复、稳定分页 |
| Java 全量及启动包 | `gradlew.bat :apps:platform-api:test :apps:ingestion-worker:test :apps:platform-api:bootJar :apps:ingestion-worker:bootJar --console=plain --rerun-tasks`，配置真实隔离 PG/VM | **Platform 46 + Worker 15 = 61 tests，0 failures/errors/skipped；两个 bootJar 成功**。PG 八路并发 claim 只有一次取得执行权、过期/旧执行写回拒绝、适配器重建后读取报告、撤权和同时间戳游标测试通过 |
| HTTP 契约 | `PipelineHttpIT` 保存的实际 fixture JSON，经 Draft202012Validator + FormatChecker 对照 contracts | **发布版本、评估、持久记录、历史列表四类响应通过**；HTTP 幂等冲突/失败状态/非法 UUID/身份覆盖和只读限制测试通过 |
| Rust 默认 / 全 feature / 格式 | `cargo test --workspace --locked`、`cargo test --workspace --all-features --locked -j 2`、`cargo fmt --all -- --check` | **各 25 passed，格式通过**；没有真实模型或 MCP 调用 |
| Web 类型与构建 | `pnpm --dir apps/web-console typecheck`；Playwright webServer 执行 `tsc --noEmit && vite build` | **通过**，未增加依赖或修改锁 |
| 浏览器回归 | `node apps/web-console/node_modules/@playwright/test/cli.js test --config=.tmp/playwright-local.config.ts --max-failures=1` | **32 passed**。新增刷新找回记录、换 Token 清理、不确定响应同键重试、显式恢复、分页、撤权清理和畸形报告拒绝；此组业务请求按契约拦截 |
| 浏览器真实存储链 | 设置 `OPSWEAVE_TEST_JDBC_*`、`OPSWEAVE_TEST_VM_URL` 与本地 Chromium，执行 `node scripts/check_metrics_stack.mjs --pipeline` | **PASS**。真实指标查询；Preview→发布→持久只读 Replay→刷新页面→从 PG 找回原记录，前后库存完全一致。没有业务请求拦截，来源明确是 labeled fixture；已查看 `.tmp/metrics-acceptance/pipeline.png` |
| 文件门禁 | `python scripts/check_release_inputs.py`、`git diff --check` | **通过**；没有新 CI、发布或部署声明 |

浏览器测试首轮因新增测试中的多余括号停止；修正后发现 Playwright glob 未覆盖详情子路径，改成覆盖明确 replay-runs 路径的正则后全量 32 项通过。上述是最终实际结果，没有将失败首轮记为通过。Python 容器安装依赖发生一次 PyPI 超时重试，最终安装及检查完成。

恢复测试使用可控时钟模拟中断/租约到期，并重建存储适配器验证持久结果；没有做 OS 杀进程、跨主机时钟漂移或 HA 演练。仍无厂商 Zabbix 实例验收、后台重放 Worker、持久草稿、写入型修复、历史总容量配额/TTL、生产 OIDC/RLS 或跨进程并发预算。M2/M3 不整体宣告完成，Integration Copilot 继续暂缓。

## 24. 2026-09-25 私有草稿持久化、并发保存与进度口径（追加）

在当前 `main` 工作区继续，没有新建分支、提交或推送。增加 Host 草稿保存、按 id/revision 读取和有界最近列表；可信 tenant/source/subject 隔离，expectedEditVersion 的原子比较更新阻止旧页面覆盖新草稿。草稿独立于不可变发布版本，保存不改变来源采集或库存；发布仍提交当前预览的固定定义，不读取最新草稿。补齐 V009、契约/样例、操作说明和 [ADR-019](adr/019-private-pipeline-drafts.md)。

控制台提供保存、读取当前草稿、最近草稿与载入；冲突保留本地选项，提示读取后比较；Token 变化清理工作副本、列表、表单和迟到响应。保存/载入作废旧预览。修复原生 select 的值只写入 HTML attribute、未同步 DOM property 的缺陷，使载入草稿/发布版本的下拉框显示与内部定义一致。

| 检查 | 实际命令 / 方法 | 最终结果与边界 |
|---|---|---|
| 结构 / 契约 | 临时 `python:3.12-slim` 内运行 `python scripts/check_repo.py`、`python -m pytest tests/contracts -o addopts= -q` | **75 个结构化文件、3 个只读 Tool；92 passed**。新增草稿 Schema/样例、身份覆盖、编辑号越界、发布状态和列表上限负例 |
| 纯领域 | 临时 Node runner 以 `javac --release 21 -encoding UTF-8` 编译 modules 与 tests/domain，运行全部 14 个 smoke main | **289 项通过**，新增 PipelineDraftSmoke 26 项；不是冒称执行 Python runner |
| Java / 启动包 | 设置隔离 PG/VM 后，`gradlew.bat :apps:platform-api:test :apps:ingestion-worker:test :apps:platform-api:bootJar :apps:ingestion-worker:bootJar --console=plain --rerun-tasks` | **Platform 49 + Worker 15 = 64 tests，0 failures/errors/skipped；两个 bootJar 成功**。真实 PG 并发更新只有一个成功、另一方冲突；适配器重建后恢复草稿、owner/source/tenant 隔离、摘要篡改拒绝、已发布内容不变 |
| 实际 HTTP 契约 | Draft202012Validator + FormatChecker 校验 `PipelineHttpIT` 保存的实际 fixture 响应 | **六类响应通过**：版本、评估、重放记录、重放历史、草稿、草稿列表 |
| Rust 默认 / 全 feature / 格式 | `cargo test --workspace --locked`、`cargo test --workspace --all-features --locked -j 2`、`cargo fmt --all -- --check` | **各 25 passed，格式通过**；没有真实模型/MCP 调用 |
| Web 类型 / 构建 | `pnpm --dir apps/web-console typecheck`；最终 Playwright webServer 执行 `tsc --noEmit && vite build` | **通过**；沿用固定依赖、无锁更新 |
| 浏览器回归 | `node apps/web-console/node_modules/@playwright/test/cli.js test --config=.tmp/playwright-local.config.ts --max-failures=1` | **38 passed**；新增 6 项草稿用例：刷新载入到显式发布、旧编辑号冲突/保留本地修改、换身份作废加载、错误状态/ID/503 拒绝。此组 HTTP 按契约拦截，已查看草稿页面截图 |
| 真实存储浏览器 | 同一隔离存储和本地 Chromium，`node scripts/check_metrics_stack.mjs --pipeline` | **PASS**：指标查询；保存 PG 草稿→刷新/列出/载入→预览→固定发布→持久只读重放→刷新找回报告。无业务请求拦截，前后库存完全一致；Zabbix 来源仍是 labeled fixture |
| 文件门禁 | `python scripts/check_release_inputs.py`、`git diff --check` | **通过**；无新 CI 或部署声明 |

首次领域编译因新测试误写 Principal accessor 为 scope() 失败，修正为 resourceScope() 后全部通过。首次浏览器草稿用例发现 select 显示旧值，修复 property 绑定后重新运行全量 38 项通过；没有把首次失败计入成功。环境沿用第 23 节；临时测试容器仅暴露 loopback，测试完成后停止。

新增 [PROGRESS.md](PROGRESS.md) 记录整体约 35%（±5 个百分点）、首个只读 MVP 约 55% 的粗估依据：M0–M7 等权、能力证据而非测试数；严格完整里程碑退出只有 M0。此估算不是工时或生产就绪率。仍缺厂商 Zabbix 验收、外部告警/Incident、真实 Tool/模型/AIInsight 闭环、持久 AIRun、Skill 管理与生产试点。草稿没有审批、共享、删除/TTL、完整编辑审计、总容量配额；页面 Preview 流程不作为服务端授权门禁。

## 25. 2026-09-25 OW-R08 资产筛选、分页与详情（追加）

继续在 `main` 工作区实现。新增 `/api/v1/entities/page` 和独立 Schema/样例，支持名称/IP 字面包含、类型、生命周期和 UUID 游标，默认 25、最大 100 条。可信主体的 tenant/对象范围先于数据库 LIMIT 生效；显式范围最多 1000 个实体，返回结果再次检查授权与顺序。无任意表达式、未知/重复参数、客户端 tenant 或自定义排序。单条属性使用保守 16 KiB/深度 8 读预算，PG 查询最多 5 秒；过大记录返回失败，不伪装为空。游标是实时顺序位置，不授予权限，也不承诺跨页快照一致性。

详情每次重新执行授权读取，显示来源实例、采集模式、观测时间、Raw 引用和映射版本/digest。新增采集模式来自已配置 Connector，不能被来源 payload 覆盖；历史数据缺失标记不猜测补全。前端筛选作废游标，切换 Token/401/403 清除旧资产与详情，并丢弃迟到响应。

| 检查 | 实际命令 / 方法 | 最终结果与边界 |
|---|---|---|
| 结构 / 契约 | 临时 Python 容器运行 `scripts/check_repo.py`、`pytest tests/contracts -o addopts= -q` | **79 个结构化文件、3 个只读 Tool；104 passed** |
| 纯领域 | Node runner 编译 modules/tests/domain 并运行全部 15 个 smoke main | **315 项通过**；新增 26 项分页/对象范围/游标/字面搜索/属性预算负例 |
| Java / 启动包 | 隔离 PG/VM，`gradlew.bat :apps:platform-api:test :apps:ingestion-worker:test :apps:platform-api:bootJar :apps:ingestion-worker:bootJar --console=plain --rerun-tasks` | **Platform 54 + Worker 15 = 69 tests，0 failures/errors/skipped；两个 bootJar 成功**。真实 PG 多页不重不漏、无符号 UUID 顺序、范围先于分页、租户隔离、超限失败 |
| 实际 HTTP 契约 | Draft202012Validator + FormatChecker 检查保存的实际 HTTP 响应 | **8 类通过**；新增 entity-page 与 entity，沿用 6 类 Pipeline 响应 |
| Rust 默认 / 全 feature / 格式 | `cargo test --locked`、`cargo test --locked --all-features`、`cargo fmt --all -- --check` | **各 25 passed，格式通过** |
| Web 类型 / 构建 | `pnpm --dir apps/web-console typecheck`、`pnpm --dir apps/web-console build` | **通过** |
| 浏览器回归 | 仓库根目录 `node apps/web-console/node_modules/@playwright/test/cli.js test --config .tmp/playwright-local.config.ts` | **42 passed**；新增分页筛选、重新读取详情与撤权清理、Token 切换丢弃迟到响应、响应超限拒绝。此组 HTTP 按契约拦截 |
| 真实存储浏览器 | 隔离 PG/VM + 本地 Chromium，`node scripts/check_metrics_stack.mjs --pipeline` | **PASS**：指标查询、资产 IP 筛选/PG 详情/来源标记/身份清理、草稿/预览/发布/持久重放刷新恢复；无业务请求拦截。已查看 inventory.png，布局完整 |
| 发布输入静态检查 | `python scripts/check_release_inputs.py` | **通过**；不代表新 CI、提交或部署 |

首次新增 HTTP 测试因测试 Token 少于开发认证最小长度而启动失败，修正后重跑全量 69 项通过；首次 Playwright 从 Web 子目录启动造成临时配置工作目录错误，改从仓库根目录执行后 42 项通过。没有把失败首轮记为成功。临时 PG/VM 仅 loopback，仍供后续 MVP 验证使用。

限制：厂商 Zabbix、真实模型及真实登录提供方仍未配置/联调。旧 `/entities` 全量兼容接口仍用于指标选择器，本轮只对新分页接口保证总行数有界；多源字段冲突历史/人工确认未实现。没有容量/HA/完整 IME 与键盘矩阵验收。已建立 [MVP-CHECKLIST.md](MVP-CHECKLIST.md)，目标进行中，当前粗估仍约 55%，不以新增测试数冒充 MVP 100%。

## 26. 2026-09-25 外部问题与恢复的有界读取（追加）

在同一工作区继续 M3，新增 `ExternalProblem`、发生/恢复/抑制和缺失状态、乱序/重复观测合并规则。提供 Zabbix 7.0 event.get 读取适配和 `GET /api/v1/integrations/zabbix/problems`；需要配置来源的 source.sync。使用问题活动窗口和数值 event ID 游标，批量查询明确恢复 ID，每页最多 100、窗口最多 24h、单实例并发 2、最多两次来源调用，沿用单次 HTTP 10s/2MiB 上限。恢复记录不可读时保留缺失；不支持的全局关联关闭整页拒绝。没有持久化、创建 Incident、通知或动作，响应明确 `not-persisted`。契约与限制见 [ADR-020](adr/020-external-problem-read.md)。

| 检查 | 实际命令 / 方法 | 最终结果 |
|---|---|---|
| 结构 / 契约 | 临时 Python 容器执行 `scripts/check_repo.py`、`pytest tests/contracts -o addopts= -q` | **84 个结构化文件、3 个只读 Tool；122 passed** |
| 纯领域 | Node runner 编译 modules/tests/domain，运行全部 16 个 smoke main | **363 项通过**；新增读取/恢复/分页/错误/身份/合并 48 项 |
| Java / PG / VM / 启动包 | 同一隔离存储，重新执行 Platform/Worker 全量 test 与两个 bootJar（`--rerun-tasks`） | **Platform 58 + Worker 15 = 73 tests，0 failures/errors/skipped；两个 bootJar 成功** |
| 本地来源协议 | `ZabbixProblemContractTest` 使用 loopback HttpServer + 实际 JacksonZabbixTransport | **2 项通过**；检查有界参数、Authorization 不进入 JSON、精确恢复 ID、JSON-RPC 错误和超大响应。是协议桩，不是厂商实例 |
| HTTP 契约 | 实际 `ZabbixProblemHttpIT` 响应 + 原 8 类响应，Draft202012Validator / FormatChecker | **10 类响应通过**；新增 external-problem-page/external-problem |
| 静态门禁 | `scripts/check_release_inputs.py`、`git diff --check` | **通过** |

日期负例首次运行发现 `jsonschema` 未安装可选的日期格式实现，FormatChecker 静默放过非法 date-time。添加固定开发依赖 `rfc3339-validator==0.1.4` 及校验器可用性负例后，重新运行全部 122 项和 10 类实际 HTTP 响应均通过。此前记录的结构/UUID/其他校验结果仍然成立，但当时 date-time 格式未得到有效检查，本次已补齐。容器安装依赖时出现一次 PyPI 超时重试，最终成功。没有通过删掉日期负例规避失败。

本切片未改 Rust 或 Web，沿用同次推进第 25 节已实际运行的 Rust 默认/全 feature 各 25、fmt、TypeScript/build、42 项 Playwright 和真实存储浏览器结果，没有宣称为新告警页面验证。告警页面、持久导入/Incident 原子关联/人工流转与真实来源验收仍待实现；合并规则目前仅是领域代码，尚未接事务。单实例并发配置不是分布式预算或 HA 证明。

用户已确认外部环境“暂未准备，先推进本地实现”。MVP 100% 目标保持进行中；继续本地告警/Incident 与诊断链路，真实来源/模型/登录验收保持未完成，不将 fixture 替代真实验收。

## 27. 2026-09-25 告警持久化、Incident 工作台与同窗口指标（追加）

在 `main` 原工作区继续，未新建分支、提交、推送或部署。新增独立 problem ingest POST、V010 和 Incident 领域/应用/内存/PG 适配。每页按 tenant/source/problem event ID 原子保存问题观测、Incident 和实体关联；行锁内合并，空页不关闭、来源失败不写入、重复导入不重复建 Incident。问题/恢复事实和人工状态分别保存，明确的恢复事实不会自动推进人工状态。时间线区分发生与平台可见时间，纳秒保留。人工状态请求使用 expectedVersion 和绑定操作者/参数的 requestKey，返回稳定幂等结果。

受权列表先应用 tenant、Incident allow-list 和全部关联实体范围，再做 UUID 游标 LIMIT。未映射保留 gap，不通过名称/IP 猜实体；部分实体范围用户不能读取范围不完整的 Incident。详情、变更、关联资产分别重新授权。Web 增加导入/状态筛选/分页/详情/来源/时间线/缺口/人工状态与同键重试；Token 变化或 401/403 清空旧数据。指标链接保持一小时 UTC 窗口并重新读取资产权限；指标 Host 选择器使用服务端分页。旧 GET `/entities` 已改为有界兼容查询，100 条以内保持旧响应，更多返回 422/PAGED_READ_REQUIRED，不伪装完整列表。

| 检查 | 实际命令 / 方法 | 最终结果与边界 |
|---|---|---|
| 结构 / 契约 | Docker Python 3.12 临时容器执行 `scripts/check_repo.py`、`pytest tests/contracts -o addopts= -q` | **98 个结构化文件、3 个只读 Tool；141 passed**，包括 7 类新增示例及非法状态/窗口/来源/时间线/身份字段等负例 |
| 纯领域 | Node runner 用 `javac --release 21` 编译 modules/tests/domain，执行全部 17 个 smoke main | **408 项通过**；Incident 新增 45 项，覆盖恢复不回退、人工状态、幂等/版本、范围过滤、整页回滚与未映射补齐 |
| Java / 启动包 | 专用 loopback PG/VM，重跑 `gradlew.bat :apps:platform-api:test :apps:ingestion-worker:test :apps:platform-api:bootJar :apps:ingestion-worker:bootJar --console=plain --rerun-tasks` | **Platform 65 + Worker 15 = 80 tests，0 failures/errors/skipped；两个 bootJar 成功** |
| PG Incident | 4 个 `PostgresIncidentIT` | **通过**：并发导入只建一次、整页失败回滚、来源恢复与纳秒、租户/对象范围先于分页、未映射补齐、并发状态只有一个成功、适配器重建后幂等结果可查；不是 OS 崩溃/HA 演练 |
| Java HTTP | `IncidentHttpIT` 2 项 + `EntityLegacyLimitIT` 1 项 | **通过**：导入/重复/分页/详情/状态重试、401/400/404/409、越界整数和身份覆盖；旧实体接口 100 条返回完整结果、101 条明确 422，新分页仍可用 |
| 实际 HTTP 契约 | Draft202012Validator + 有效 FormatChecker 检查实际测试保存的响应 | **14 类响应通过**；新增 problem-ingest-result、incident-page、incident-detail、incident-transition-result |
| Rust 默认 / 全 feature / 格式 | `cargo fmt --all -- --check`、`cargo test --workspace --locked`、`cargo test --workspace --all-features --locked` | **各 25 passed，格式通过**；本轮未实现 Rust 平台 HTTP 适配或真实模型调用 |
| Web 类型 / 构建 | `pnpm --dir apps/web-console typecheck`、`pnpm --dir apps/web-console build`；最终 Playwright webServer 再构建包含最终改动 | **通过** |
| Web 回归 | 根目录 `node apps/web-console/node_modules/@playwright/test/cli.js test --config .tmp/playwright-local.config.ts` | **54 passed**；显式 HTTP 拦截 fixture。新增 Incident 导入/分页/详情/跨范围响应拒绝、状态不确定响应同键重试/冲突、撤权/迟到响应清理、资产重读/窗口跳转；新增指标分页/撤权/非法窗口拒绝 |
| 真实本机存储浏览器 | 环境包装器执行 `node scripts/check_metrics_stack.mjs --pipeline`，无业务请求拦截 | **4 个 PASS**：指标曲线、资产分页详情、告警原子导入→重复不新建→人工流转→页面刷新找回时间线→重新授权资产→同窗口 VM 查询、流水线草稿/发布/重放恢复。固定本次进程开发租户、随机 Token、fixture 来源；历史窗口未写采样，明确 No data。已查看 `incident.png`，来源/缺口/时间线/资产区完整 |
| 静态门禁 | `scripts/check_release_inputs.py`、`git diff --check` | **通过**；不等于新 CI 或部署 |

前端首次类型检查发现 unknown 值在闭包内未收窄，修正后通过。首次新增 Playwright 的路由 glob 没覆盖详情子路径，导致测试请求发到未启动的代理目标；中断该轮、修正拦截范围，最终全量 54 项通过，没有增加应用 fallback。最后一次临时容器安装出现 PyPI 超时重试，随后成功完成全部契约与实际响应校验。临时 PG/VM 容器仍仅 loopback，留供后续目标验证；自动验收启动的 Java/Vite/Chromium 已关闭。

限制：来源是明确 fixture，不是厂商验收；真实 Zabbix、模型、登录提供方仍无配置。当前一条来源发生对应一个初始 Incident，人工合并/拆分尚未提供；保存的是最新规范化观测和问题/恢复时间线，未提供完整原始问题历史和任意 asOf 回放；快照限制 50 个问题、100 条时间线、100 个实体，超限拒绝，时间线分页/留存未实现。Java Tool Gateway、Rust HTTP 与 AIInsight 持久化/证据页面仍缺。MVP 粗估约 **60%（±5 个百分点）**，目标保持进行中，M3/M4 不标完成。设计和复现步骤见 [ADR-021](adr/021-external-problem-incident.md)、[本机验收手册](runbooks/incident-local-acceptance.md)。

## 28. 2026-09-25 受控 Tool Gateway、持久证据与 Rust HTTP（追加）

继续在 `main` 原工作区实现，未新建分支、提交、推送或部署。Java 新增当前知识读取会话，固定 Incident 版本、关联实体和一小时内整秒采样窗口；三个 v2 Tool 提供 Incident 概要、分来源 Gauge 统计和 Evidence 复核。V011 在现有平台 PG 保存会话、不可变证据、仅含元数据的审计。四次调用预算由 PG 行锁共享；证据插入与完成审计原子提交。每主体最多四个未过期会话，执行器四并发/十五秒；超时工作真正退出前不释放名额。输入版本和当前权限在读取、复核前后检查，指标目录变化拒绝混合版本结果。

证据明确 `knowledgeMode: current`：查询窗口是采样范围，`availableAt` 是实际采集时刻，24 小时到期。缺失历史 ingestion time、日志、变更、映射和采样等保留 warnings，不回写历史时间以通过旧 asOf 检查。Rust 新增独立 `PlatformHttp::loopback`，逐请求向固定 Java origin 转发凭据，由会话响应构造 Principal。禁用代理/重定向/重试，限制连接/请求/剩余会话期限、并发和流式响应字节数；只从内置 canonical contracts 构建验证器，拒绝替换 tenant/Incident/实体、版本、时间、统计数量/单位和证据源地址。原 demo 和已发布 Skill 包未修改。

| 检查 | 实际命令 / 方法 | 最终结果与边界 |
|---|---|---|
| 结构 / 契约 | Docker Python 3.12 执行 `scripts/check_repo.py`、`pytest tests/contracts -o addopts= -q` | **118 个结构化文件、6 个只读 Tool 定义；169 passed**。包含三个新增 v2 实现定义、当前知识会话/证据/请求 Schema 及身份/期限/预算等负例；原 v1 prototype 仍标未实现 |
| 纯领域 | Node runner 使用 `javac --release 21` 编译 modules/tests/domain，运行全部 18 个 smoke main | **449 项通过**。新增 Tool smoke 41 项，覆盖权限、所有者、共享并发预算、存储失败、过期、输出限制、指标目录变化和复核期间 Incident 变化 |
| Java / 两个启动包 | 专用 loopback PG/VM；`gradlew.bat :apps:platform-api:test :apps:ingestion-worker:test :apps:platform-api:bootJar :apps:ingestion-worker:bootJar --console=plain --rerun-tasks` | **Platform 72 + Worker 15 = 87 tests，0 failures/errors/skipped；两个 bootJar 成功** |
| PG 会话 / 证据 | `PostgresToolReadIT` 3 项，包含在上述 87 项内 | **通过**：十二个并发消费只有四个成功、跨租户/所有者隔离、适配器重建保留额度和纳秒证据；故意不存在的完成审计导致整个证据事务回滚；过期/超时不生成证据 |
| Java HTTP / 执行器 | `ToolGatewayHttpIT` 2 项、`ToolExecutorTest` 2 项，包含在上述 87 项内 | **通过**：可信会话/固定版本工具/受权回读/共享额度、缺权限/身份覆盖/非法窗口与参数、VM 关闭返回失败；忽略中断的底层工作在退出前持续占用名额 |
| 实际响应契约 | Draft202012Validator + 有效 FormatChecker 检查测试 HTTP 输出 | **20 份响应、17 类 Schema 通过**。新增 memory HTTP 的 Incident 证据和真实 PG/VM 的 Metric 证据，两组各含读取会话/证据/ToolResult |
| Rust 默认 / 全 feature | `cargo test --workspace --locked -j 1`；`cargo test --workspace --all-features --locked -j 1` | **各 32 passed（既有 25 + HTTP 7），0 failed/ignored**。HTTP 测试涵盖固定来源、可信会话、共享额度、跨范围/未来/过期/伪造输入、复核撤权/变化、非 JSON/超长/重定向/错误端点拒绝；未调用真实模型或 MCP |
| Rust 格式 / probe | `cargo fmt --all -- --check`；`cargo build -p opsweave-agent-runtime --example platform_read_probe --locked -j 1` | **通过**。新增直接 reqwest 依赖固定为锁中实际版本 0.12.28，Cargo 实际更新锁，仅新增根包依赖关系，没有手造锁内容 |
| Web 类型 / 构建 | `pnpm --dir apps/web-console build`（执行 `tsc --noEmit` + Vite） | **通过**。本轮未修改 Web 源码；未重跑第 27 节的 54 项拦截式 Playwright，不将其记作本轮结果 |
| 真实本机链路 | 环境包装器执行 `node scripts/check_metrics_stack.mjs --pipeline --runtime`，无业务请求拦截 | **6 个 PASS**：原指标、资产、Incident、流水线四条浏览器链；新增 Java Tool→PG/VM→证据复核/第五次 429；Rust HTTP→Java 可信会话→PG 证据/VM 采样→两次授权复核。Gauge 均值 0.355、单位 1，来源明确 labeled-fixture；没有调用模型 |
| 静态门禁 | `scripts/check_release_inputs.py`、`git diff --check` | **通过**，不是新 CI、发布或部署 |

失败与修正：PG JSON 解码的整数类型与内存结果不一致，统一为 Long 后通过回读相等性检查。新增目录变化 smoke 初次编译时缺接口方法，补齐委托后全领域通过。Rust 一次并行全量编译遇 Windows 页面文件不足（OS 1455 / rlib mmap），改为单任务编译后通过；关闭端口在 Windows 可能即时拒绝或连接超时，负例修正为两种明确失败均可接受，未增加 fallback、重试或放宽成功响应校验。最终默认/全 feature 全量均成功。

范围：新 Rust HTTP 适配器目前由独立 probe 使用，现有诊断端点仍是固定 demo，尚未接入新当前知识模型流程。AIInsight 幂等持久化/结果查询/证据页面、生产委托身份、真实 Zabbix/模型/登录、人工合并拆分、历史观测/留存和租户存储总额限制仍未完成。本轮证明本机授权读取与保存证据，不能宣称真实诊断或 RCA 准确率。MVP 粗估更新至 **65%（±5 个百分点）**，目标仍进行中。设计和复现见 [ADR-022](adr/022-current-knowledge-tool-evidence.md)、[Tool 本机验收](runbooks/tool-read-local-acceptance.md)。

## 29. 2026-09-25 当前知识模型流程、AIInsight 与证据页面（追加）

在现有 `main` 工作区继续，无新分支/提交/推送/部署。新增 current 请求/上下文与结果 canonical contracts，新建 Skill 2.0.0 包而不覆盖已发布 1.0.0。Rust 读取两个结构化证据后捕获 asOf、单次有界模型、校验输出/引用、两次证据重新授权复核，再向 Java 受控保存 API 提交。Java 校验可信身份、固定 Skill digest/模型配置、预算完成与复核记录、Incident 当前版本/实体、证据时间及引用；缺口和 mock/当前知识限制不可由模型删掉。V012 原子保存 AIInsight 与两条证据外键关联，保存失败不返回成功。

Web 同源 Java 入口使用当前调用者授权和固定 loopback Runtime origin；未配置即关闭，不给浏览器 Runtime key。Runtime key 是本机进程证明，不是生产身份。结果保存后重新从 Java Store 读取返回，页面保留 runId 支持超时后查询/刷新回读，证据点击时重新授权。模型为显式 mock，真实 Rig 未调用。

| 检查 | 实际命令 / 方法 | 最终结果与边界 |
|---|---|---|
| 结构 / 契约 | Docker Python 3.12 运行 `scripts/check_repo.py`、`pytest tests/contracts -o addopts= -q` | **130 个结构化文件、6 个只读 Tool；198 passed**，含 current 请求/上下文和结果样例、身份/历史截止覆盖、动作/模型/Skill/引用等负例 |
| 纯领域 | `.tmp/domain-check.cjs` 使用 `javac --release 21` 编译 modules/tests/domain 并执行全部 19 个 main | **467 项通过**，新增 AIInsight 18 项，含无复核不保存、scope/权限、幂等/同会话多结果、输入更改、过期和伪造引用 |
| Java / 两个启动包 | 专用 loopback PG/VM；`gradlew.bat :apps:platform-api:test :apps:ingestion-worker:test :apps:platform-api:bootJar :apps:ingestion-worker:bootJar --console=plain --rerun-tasks` | **Platform 80 + Worker 15 = 95 tests，0 failures/errors/skipped；两个 bootJar 成功** |
| PG AIInsight | `PostgresAiInsightIT` 3 项，包含在上述 95 项内 | **通过**：六个并发相同请求只保存一份结果/两条关联；重建适配器保持纳秒与幂等；跨租户/所有者隔离；故意不存在证据导致整笔回滚；最终事务拒绝版本变化/过期会话 |
| HTTP / Runtime 转发 | `InsightHttpIT` 2 项、`RuntimeDispatcherTest` 3 项，包含在上述 95 项内 | **通过**：结果保存/读取/幂等、缺 Runtime key/用户权限与身份覆盖拒绝、错误 digest、默认关闭诊断、固定 loopback/不转发 Runtime key、不跟随重定向/不重试、超长成功响应拒绝 |
| 实际响应契约 | Draft202012Validator + FormatChecker 验证 HTTP 输出 | **24 份响应、19 类 Schema 通过**。新增 memory HTTP 和真实 PG/VM/Rust 链各两份 AIInsight/结果响应 |
| Rust 默认 / 全 feature | `cargo test --workspace --locked -j 1`；`cargo test --workspace --all-features --locked -j 1` | **各 40 passed（领域流程 6 + HTTP 9 + 原回归 25），0 failed/ignored**。验证结构化上下文、一次模型/四读、20 秒模型超时（可控时钟）、保存失败/伪造回执/跨租户/过期/动作拒绝、同键只读回查；未调用真实模型或 MCP |
| Web 类型 / 构建 | `pnpm --filter @opsweave/web-console build`，最终 Playwright webServer 再执行 `tsc --noEmit` 与 Vite | **通过** |
| Web 回归 | `node apps/web-console/node_modules/@playwright/test/cli.js test --config .tmp/playwright-local.config.ts` | **62 passed**，显式 HTTP fixture。新增 8 项覆盖发起/刷新/证据、XSS 文本、身份切换迟到响应、失败待确认不自动重试、伪造引用/动作/错 run/过期/越权证据 |
| 真实本机完整链路 | 环境包装器执行 `node scripts/check_metrics_stack.mjs --pipeline --runtime`，无业务请求拦截 | **7 个 PASS**。新增浏览器→Java→Rust current+mock→两次证据复核→PG AIInsight→同键 POST 返回同一结果→刷新 GET→授权证据；VM 两点均值 0.355，来源为 labeled-fixture。原指标/库存/Incident/Tool/probe/流水线链回归通过 |
| 静态 / 格式 | `scripts/check_release_inputs.py`、`git diff --check`、`cargo fmt --all -- --check` | **通过**；不是远端 CI 或部署 |

修复与复跑：Java 新控制器的 API/domain Permission 通配导入冲突，改为明确导入后编译及全量 Java 通过；TypeScript 闭包中的 unknown 引用列表未收窄，绑定局部常量后通过。首次全量 Playwright 61 成功/1 失败，原因是旧 demo 导航文案已改为“Fixture 诊断演示”而测试仍找旧标签；更新选择器后该文件 7 项通过，最终完整 62 项通过。查看真实链路截图发现 textarea 的 value 属性未设置 Zeus 原生 property，补 `prop:value`、可读配色和结果回读时表单恢复，并加入输入值断言后通过最终回归。`.tmp/metrics-acceptance/insight.png` 包含合成数据，已查看，不包含明文凭据。

限制：结果最多随证据有效 24 小时，过期返回 410；未实现留存清理/租户总存储额、真实费用计量、持久 AIRun/跨副本一次模型执行/HA。连接中断后可以按 runId 查询已保存结果，不能保证未保存运行恢复。Java/Rust/PG/VM 是真实本机实现，但来源/模型仍为 fixture/mock；真实 Zabbix、模型、OIDC 与人工抽样审阅未验收。MVP 约 **70%（±5 个百分点）**，目标保持进行中；接下来继续 Incident 人工合并/拆分、统一会话及多源冲突治理，不把外部环境尚缺当成所有本地工作的阻塞。设计和复现见 [ADR-023](adr/023-current-diagnosis-insight.md)、[AIInsight 本机验收](runbooks/insight-local-acceptance.md)。

## 30. 2026-09-25 Incident 人工合并/拆分、后续归属与证据失效（追加）

继续在原 `main` 工作区推进，无新分支、提交、推送或部署。Java 纯领域新增人工关联规则，V013 在同一 PG 保存合并目标和幂等回执。来源合并后保留原历史快照和人工状态，默认列表只返回活动 Incident；拆分保留至少一个问题，将部分问题及事实时间线移到新 OPEN Incident。问题索引记录当前归属，后续恢复和重复导入不能写回初始 ID。双方记录、归属索引与回执同事务提交，检查双方权限/版本与问题数量/字节/时间预算。

Web 新增预览、人工确认、同键重试、按请求标识回读、刷新恢复和授权历史分页。来源/实体范围在分页 LIMIT 前过滤；身份切换清除结果与迟到响应。关联版本变化后，即使实体集合不变，旧证据与关联 AIInsight 也不能继续读取，返回 409；原诊断历史 asOf 规则未放宽。

| 检查 | 实际命令 / 方法 | 最终结果与边界 |
|---|---|---|
| 结构 / 契约 | `docker run --rm -v D:/workspace/git-code/ops-weave:/workspace -w /workspace python:3.12 sh .tmp/pipeline-check.sh`；执行 check_repo、pytest、发布输入与实际响应校验 | **138 个结构化文件、6 个只读 Tool；216 passed**。新增关联请求/回执/结果/分页样例、身份覆盖/种类/版本/子集字段等负例；关联接口不是模型 Tool |
| 纯领域 | `node .tmp/domain-check.cjs`，`javac --release 21` 编译并执行全部 20 个 main | **501 项通过**。关联 smoke 29 项；Tool smoke 46 项包含同实体关联变化仍失效、合并归档拒绝、新证据过期 |
| Java / 启动包 | 既有专用 loopback PG/VM；环境包装器运行 `gradlew.bat :apps:platform-api:test :apps:ingestion-worker:test :apps:platform-api:bootJar :apps:ingestion-worker:bootJar --console=plain --rerun-tasks` | **Platform 86 + Worker 15 = 101 tests，0 failures/errors/skipped；两个 bootJar 成功** |
| PG 原子归属 | `PostgresIncidentIT` 7 项，含新增 3 项，包含在上述 101 项内 | **通过**：合并/拆分后恢复归属、一个导入页更新同一聚合的统计、适配器重建/并发同键回执、范围先于历史分页、归属索引故意缺失时两份聚合与回执整体回滚 |
| HTTP 与迁移 | `ReorganizationHttpIT` 2 项、`SchemaMigratorAtomicIT` 1 项，包含在上述 101 项内 | **通过**：HTTP 预期版本/重试/历史/归档/证据失效与非法身份/query；测试迁移先创建专用表再故意执行失败语句，确认表与迁移标记均未留下 |
| 实际响应契约 | Draft202012Validator + FormatChecker 检查 `.tmp` 中真实 HTTP 输出 | **32 份响应、22 类 Schema 通过**。新增 memory HTTP 和真实 PG 两组关联回执/结果/分页/归档详情；旧结构兼容 |
| Rust 默认 / 全 feature / 格式 | `cargo test --locked -j 1`、`cargo test --locked --all-features -j 1`、`cargo fmt --all -- --check` | **各 40 passed，0 failed/ignored；格式通过**，未调用真实模型或 MCP |
| Web 类型 / 构建 / 回归 | 根目录 `node apps/web-console/node_modules/@playwright/test/cli.js test --config .tmp/playwright-local.config.ts`，webServer 先运行 `tsc --noEmit` + Vite build | **构建通过，68 passed**。新增 6 项覆盖先预览后写入、503 同键重试/刷新回读、真子集拆分、冲突丢弃旧版本、合并归档、身份变化迟到响应、历史游标/跨对象拒绝与文本渲染；此组使用显式 HTTP fixture |
| 真实本机完整链 | `node .tmp/pipeline-test-env.cjs browser` 包装执行 `node scripts/check_metrics_stack.mjs --pipeline --runtime`，无业务请求拦截 | **8 个 PASS**：原 7 条链回归，加上网页预览（PG 未变）→合并→同键回执/刷新→旧证据/AIInsight 409→拆分 OPEN→重复导入跟随新归属→历史/身份清理。PG/VM 为真实本机存储，来源/模型为 fixture/mock |
| 静态门禁 | `scripts/check_release_inputs.py`、`git diff --check` | **通过**，不代表远端 CI、发布或生产验收 |

失败与修正：第一次全量 Java 新增 PG 用例后耗尽连接，实际专用 PG 报 too many clients；追踪到测试重建 InventoryWiring 后没有关闭连接池。为 Wiring 增加生命周期关闭和初始化失败清理，PG 测试用统一 AfterEach 释放，最终全量 101 项通过。迁移同时增加事务/启动锁/锁后重查，故意失败的 DDL 回滚用例通过。新增 Tool 负例曾把已因关联变化失效的证据当成单纯过期证据，改为先捕获新的当前证据再推进时间验证 410；关联变化仍保持 409。TypeScript 初次编译的 unknown 列表收窄与页面 history 命名遮蔽已修正，构建和完整浏览器回归通过。

已查看 `.tmp/metrics-acceptance/reorganization.png`，结果、版本、actor、原因和两条历史记录完整，Token 为密码显示。自动验收启动的 Java/Rust/Vite/Chromium 已关闭；专用 PG/VM 仍仅 loopback，供后续目标检查。临时凭据和产物未提交。

限制：不是自动相关性、完整问题 Observation 历史或任意 asOf 重建；快照仍有 50 问题/100 时间线/100 实体上限，未提供时间线分页或撤销工作流。历史回执是双方当前授权范围下的 UUID 游标视图，不是按时间排序或跨页一致快照。没有进行 OS 崩溃、HA/容量或生产迁移回退演练。统一会话、完整多源追溯、留存/总量预算/真实费用以及真实 Zabbix/模型/OIDC 和人工审阅仍未完成。MVP 粗估 **75%（±5 个百分点）**，目标继续进行；设计与复现见 [ADR-024](adr/024-incident-reorganization.md)、[本机验收](runbooks/incident-reorganization.md)。

## 31. 2026-09-25 公共 HTTP、开发凭据生命周期与响应边界（追加）

继续在 `main` 原工作区实施，没有新分支、提交、推送或部署。六个业务页面移到同源公共请求层，API 函数不再接收页面传入的 Token。开发凭据仅保存在当前文档内存，hash 导航共享；清除、替换、30 分钟绝对保留到期、pagehide、401/403 会取消在途请求并清理旧数据与客户编辑内容。401 删除凭据，403 保留凭据供其他受权资源使用。旧请求不能影响新身份，历史 Runtime fixture 使用独立凭据与路径。

公共层固定 API 路径、禁重定向/隐式重试/持久凭据；6 个共享在途名额、64 KiB 请求上限、2 MiB 流式响应上限和完整读取期限，AIInsight 仍为 128 KiB。失败正文只取安全代码/有界元数据，不显示原文。Java 新增可选 UUID 请求标识、非法/重复 400、成功和失败响应 no-store/nosniff；标识仅用于关联，不替代可信主体或幂等键。OpenAPI 所有平台路径声明该参数，没有变更领域 Schema 或增加生产认证。

| 检查 | 实际命令 / 方法 | 最终结果与边界 |
|---|---|---|
| 结构 / 契约 | `docker run --rm -v D:/workspace/git-code/ops-weave:/workspace -w /workspace python:3.12 sh .tmp/pipeline-check.sh`，执行结构、契约、发布输入与实际响应检查 | **138 个结构化文件、6 个只读 Tool；217 passed**。新增 OpenAPI 请求标识声明与 UUID 样例负例；Python 只用于开发检查 |
| 纯领域 | Node runner 以 `javac --release 21` 编译所有 modules/tests/domain，运行全部 20 个 main | **501 项通过**；本轮不修改领域业务规则 |
| Java / 两个启动包 | 专用 loopback PG/VM；`gradlew.bat :apps:platform-api:test :apps:ingestion-worker:test :apps:platform-api:bootJar :apps:ingestion-worker:bootJar --console=plain --rerun-tasks` | **Platform 88 + Worker 15 = 103 tests，0 failures/errors/skipped；两个 bootJar 成功** |
| 请求边界 HTTP | `RequestBoundaryHttpIT` 2 项，包含在上述 103 项内 | **通过**：200/401 请求 UUID 回传与缺省生成、no-store/nosniff、重复/非法标识和身份覆盖拒绝；请求标识无法授予身份 |
| 实际响应契约 | Draft202012Validator + FormatChecker 检查实际 Java HTTP 输出和本机验收产物 | **32 份响应、22 类 Schema 通过**，含关联回执/历史、AIInsight、Tool/Evidence；不是额外 32 个业务 E2E |
| Rust 默认 / 全 feature / 格式 | `cargo test --locked -j 1`、`cargo test --locked --all-features -j 1`、`cargo fmt --all -- --check` | **各 40 passed，0 failed/ignored；格式通过**。未调用真实模型或 MCP |
| Web 类型 / 构建 / 回归 | 根目录 `node apps/web-console/node_modules/@playwright/test/cli.js test --config .tmp/playwright-local.config.ts`；webServer 运行 `tsc --noEmit` 与 Vite build | **构建通过，84 passed**：73 项浏览器显式 fixture 回归，11 项直接运行 CredentialSession/JsonClient 测试。新增共享/清除/401/403、草稿清理、旧响应、到期、pagehide、fixture 隔离、大小/编码/超时/并发/地址约束；不将直接测试算作浏览器联调 |
| 真实本机链 | `node .tmp/pipeline-test-env.cjs browser` 包装执行 `node scripts/check_metrics_stack.mjs --pipeline --runtime` | **9 个 PASS**：原 8 条业务链回归，加上 hash 导航共享凭据、清除会话和无持久存储；**36 个浏览器 API 响应** UUID/no-store/nosniff 均通过。Java/PG/VM 为真实本机进程，来源 fixture、模型 mock |
| 静态门禁 | `scripts/check_release_inputs.py`、`git diff --check` | **通过**，不代表远端 CI 或生产验收 |

失败与修正：首次公共客户端把原生 fetch 作为对象方法调用，浏览器出现非法接收者问题；改为显式闭包调用后通过。原回归对跨页面空 Token、403 后保留历史的预期与新公共生命周期不一致，调整为导航共享、失权全页清理；分页测试仍单独验证有效历史翻页，没有删掉用例。

真实链曾在 Playwright `response.json()` 报 `Network.getResponseBody` 无数据。成功流消费后不再追加 abort 或 cancel；随后独立本机页面仍复现 Chromium 的流式正文调试读取问题：200 次 reader/release 中 1 次 CDP 失败，但页面 200 次均完整解析 JSON，同轮 `response.json()` 消费方式 200 次无失败。验收脚本改为被动记录同一次 Vite 代理响应，以请求 UUID、路径、状态匹配，限制单份/总量/条数并要求响应完整，继续检查 UI 结果；不改写业务响应、补发请求或重试。最终完整链 9 个 PASS。

已查看 `.tmp/metrics-acceptance/reorganization.png`：公共凭据栏、清除入口、关联回执/版本与两条历史可读，Token 密码显示；统计产物为 `request-boundary.json`（36/0）。自动验收进程已关闭，专用 PG/VM 仍仅 loopback。临时凭据与产物不提交。

限制：这是开发凭据生命周期，30 分钟不是后端 Token 撤销；清除本标签页不影响其他独立标签页。pagehide/pageshow 使用事件模拟，未完成所有浏览器 BFCache 矩阵。生产 OIDC/BFF Cookie/CSRF、后端撤销与委托主体、完整筛选 URL 恢复、多源冲突/完整观测、留存/费用以及真实来源/模型/人工审阅仍缺。M1 粗估从 50% 到 60%，MVP 取约后仍 **75%（±5 个百分点）**，整体约 50%；目标继续进行。设计与复现见 [ADR-025](adr/025-web-request-session-boundary.md)、[本机验收](runbooks/web-session-boundary.md)。

## 32. 2026-09-25 资产不可变观测与授权历史（追加）

在现有 `main` 工作区推进，没有新分支、提交、推送或部署。Observation 以 tenant/ID 不可变保存、相同记录重复无副作用、不同内容拒绝，字段深复制；Entity/Observation/ExternalLink 的身份和观测时间必须一致。迟到旧观测留存但不回退当前投影，未经显式治理的跨来源写入或 Link 重分配拒绝，不能靠同名/IP 合并。V014 保存纳秒时间，已有 PG 记录按其原微秒精度回填并显式标记；新映射保留当时名称/类型/生命周期，历史缺失不从现值补造。

新增 canonical observation/query/page 和授权只读端点。按最多 31 天的 observedAt 窗口、固定 ingestedAt 截止和来源筛选，租户/实体范围先于分页，每页最多 50 条、字段 16 KiB、PG 查询 5 秒。页面默认 25 条，复用截止时间与窗口翻页，按纳秒拒绝未来接收数据；身份变化、撤权或关闭详情时清理。来源/名称/字段和 Raw 仅显示文本，不解引用任意地址。

| 检查 | 实际命令 / 方法 | 最终结果与边界 |
|---|---|---|
| 结构 / 契约 | Docker Python 3.12 执行 `.tmp/pipeline-check.sh`（check_repo、pytest、发布输入、实际响应校验） | **144 个结构化文件、6 个只读 Tool；235 passed**。新增三个 Schema/样例和观测分页/非法身份/时间/数量/精度/缺口负例；Python 仅用于开发检查 |
| 纯领域 | `node .tmp/domain-check.cjs`，Java21 编译所有 modules/tests/domain，执行全部 main | **529 项通过，21 个 main**。新增观测 28 项：深不可变、同 ID/跨租户、旧数据不回退、接收截止/来源分页、撤权前置、异常存储输出拒绝和隐式跨源写入拒绝 |
| Java / 启动包 | 专用 loopback PG/VM；`node .tmp/pipeline-test-env.cjs full` 执行两应用 test/bootJar，`--rerun-tasks` | **95 平台 + 15 Worker = 110 tests，0 failures/errors/skipped；两个 bootJar 成功** |
| PG 原子写入与时间 | `PostgresObservationIT` 3 项，包含在上述 110 项内 | **通过**：六并发同键只产生一条记录/版本 1，适配器重开保留纳秒和值；迟到历史/接收截止/来源和租户过滤；模拟旧精度记录的读取标记；冲突 Link 导致新 Entity/Observation 回滚，跨源写入不更改现值。旧精度用例是解码验证，不是生产迁移回退演练 |
| HTTP | `ObservationHttpIT` 3 项、`EntityReadDeniedIT` 新增 1 项，包含在上述 110 项内 | **通过**：导入/历史/固定 cutoff/下一页、401/403/404、非法/重复/越界参数 400；超大历史明确 503，不成功返回空页。测试产物含真实 memory HTTP 观测与分页 |
| 实际响应契约 | Draft202012Validator + FormatChecker 校验 Java HTTP 与真实 PG/VM 浏览器产物 | **36 份响应、24 类 Schema 通过**，新增 memory/PG 两组 observation/page；不是 36 条独立 E2E |
| Rust 默认 / 全 feature / 格式 | `cargo test --workspace --locked -j 1`、`cargo test --workspace --all-features --locked -j 1`、`cargo fmt --all -- --check` | **各 40 passed，0 failed/ignored；格式通过**。没有新增模型 Tool 或真实模型调用 |
| Web 类型 / 构建 / 回归 | 根目录 Playwright `.tmp/playwright-local.config.ts`，webServer 运行 `tsc --noEmit` 与 Vite build | **构建通过，92 passed**：81 项浏览器显式 fixture + 11 项请求层直接测试。新增 8 项覆盖窗口/cutoff/来源分页、文本注入、跨 tenant/entity、超过 cutoff 1 纳秒、坏游标、撤权与迟到响应、旧精度/缺口 |
| 真实本机完整链 | `node .tmp/pipeline-test-env.cjs browser` → `scripts/check_metrics_stack.mjs --pipeline --runtime`，同一次代理响应被动记录，无业务响应替换 | **10 个 PASS**：原九条链回归，加上受权资产→真实 PG 观测→当时名称/映射 digest/Raw/双时间。37 个浏览器 API 响应 UUID/no-store/nosniff 均通过；来源 labeled-fixture、模型 mock |
| 静态门禁 | `scripts/check_release_inputs.py`、`git diff --check` | **通过**；不是远端 CI、发布或部署 |

失败与修正：新端点最初未加入仅作用于 EntityController 的 InventoryErrors，非法参数进入错误派发后返回 401；将 ObservationController 接入同一错误边界后，新增负例和全量 Java 通过。增加超大观测测试后，将 HTTP 导入用例的资产读取改为明确筛选 fixture，避免测试执行顺序影响选择。Rust 首轮和真实链同时使用目标 exe，Windows 拒绝覆盖在运行的 Runtime；验收进程结束后顺序执行默认/全 feature，均通过，没有杀死其他任务或改用 mock fallback。

已查看 `.tmp/metrics-acceptance/observations.png`，授权详情、来源实例/范围、历史卡片、当时名称、映射修订、原始引用和双时间可读；Token 为密码显示。自动验收进程已关闭，专用 PG/VM 保持 loopback，临时凭据/产物不提交。

限制：retained-observations-only，不是完整来源事件日志、Entity 任意 asOf 投影或完整告警观测历史。ID 顺序实时分页不是时间顺序或事务快照。当前拒绝隐式多来源写入只是边界保护，跨源 Resolver、字段权威、冲突确认/撤销仍待实现；没有留存清理/总量预算。V014 非空新增列要求配套 writer，不宣称旧二进制回退、在线大表升级、HA 或生产容量已验收。真实来源/模型/身份和人工审阅仍未完成，MVP 粗估保持 **75%（±5 个百分点）**，目标继续。设计与复现见 [ADR-026](adr/026-retained-observation-history.md)、[观测历史验收](runbooks/observation-history.md)。

## 33. 2026-09-25 补充来源字段人工审核、权威与撤销（追加）

继续在原 `main` 工作区推进，没有新建分支、提交、推送或部署。用户已明确真实环境暂未准备，先推进本地实现。本次实现固定 CMDB 导入记录补充现有 Host：仅 name/ip/owner/environment，明确 `import` 模式、七天有效期、独立固定映射 engine/digest。暂存保留原始输入与当时主来源值，不修改资产；逐字段选择 PRIMARY/SUPPLEMENTAL 后接受，也可拒绝。撤销仅作用于当前生效记录，恢复最新主来源字段，保留原始记录、Observation、可信 actor/原因和幂等回执。

V015 使用现有 PG，按租户/资产共享事务锁序列化采集与决策，唯一索引约束一个资产和一个补充外部对象各最多一个生效绑定。再次同步会保存未覆盖的主来源 Observation/快照并投影已确认的字段，迟到主来源不回退投影；撤销不搬移既有 Zabbix EntityId、ExternalLink、指标或 Incident/Evidence 引用。导入不声明实时 presence，主来源完整扫描仍管理生命周期；撤销不能复活已 INACTIVE 的资产。所有读写和重试都检查 `entity.read`、新增 `entity.manage` 与配置来源的 `source.sync`；默认不配置导入、不添加管理权限。

| 检查 | 实际命令 / 方法 | 最终结果与边界 |
|---|---|---|
| 结构 / 契约 | Docker Python 3.12 运行 `.tmp/pipeline-check.sh`，包含 check_repo、pytest、发布输入、实际产物校验 | **154 个结构化文件、6 个只读 Tool；259 passed**。新增五类 Schema/样例和身份/非法字段/选择/版本/时间/数量/固定映射负例；Python 仅作开发检查 |
| 纯领域 | `node .tmp/domain-check.cjs`，Java21 编译全部 modules/tests/domain 并运行 main | **577 项通过，22 个 main**。新增 48 项覆盖不可变输入、原样幂等/异内容冲突、逐字段权威、主来源新旧观测、撤销/拒绝、七天精确边界、分页、ENTITY_MANAGE 与来源/对象范围、生命周期 |
| Java / 两个启动包 | 专用 loopback PG/VM；`node .tmp/pipeline-test-env.cjs full` 执行两应用 test/bootJar，`--rerun-tasks` | **103 平台 + 15 Worker = 118 tests，0 failures/errors/skipped；两个 bootJar 成功** |
| PG 并发 / 回滚 / 重开 | `PostgresSourceReviewIT` 5 项，包含在上述 118 项内 | **通过**：六并发同键只确认一次；两资产竞争同一来源对象只有一方成功且另一方投影不变；重开恢复纳秒、原始值/历史/生效状态；同步保留权威、迟到不回退、撤销恢复最新值；旧请求返回原回执，失效版本与跨来源读取拒绝，完整扫描/撤销保持 INACTIVE；故意损坏主快照中的对象 ID 后撤销被拒绝，两个资产与生效记录不变 |
| HTTP 权限 / 流程 | `SourceReviewHttpIT` 2 项和 `EntityReadDeniedIT` 新增 1 项，包含在上述 118 项内 | **通过**：真实 memory HTTP 暂存/接受/再同步/撤销/原回执；可信 actor、401/403/404/409；非法 identity/字段、超大正文和重复/无界 query 拒绝 |
| 实际产物契约 | Draft202012Validator + FormatChecker 检查 Java HTTP、PG 重开与浏览器链产物 | **47 份请求/响应/持久记录，29 类 Schema 通过**。新增两组导入/决策请求、review/page/固定 mapping 响应和一份 PG 重开记录；不是 47 条独立 E2E |
| Rust 默认 / 全 feature / 格式 | `cargo test --workspace --locked -j 1`、`cargo test --workspace --all-features --locked -j 1`、`cargo fmt --all -- --check` | **各 40 passed，0 failed/ignored；fmt 通过**。没有新增模型 Tool，也未调用真实模型/MCP |
| Web 类型 / 构建 / 回归 | 根目录 Playwright `.tmp/playwright-local.config.ts`，webServer 运行 `tsc --noEmit` 与 Vite build | **全量 99 passed**（88 项浏览器显式 fixture + 11 项请求层直接测试）。新增 7 项覆盖逐字段明确选择/纯文本、tenant/source/cursor 拒绝、过期保留、原请求重试和退出后的迟到响应；之后修正已确认字段展示，**再次构建并运行这 7 项，全部通过** |
| 真实本机完整链 | `node .tmp/pipeline-test-env.cjs browser` → `scripts/check_metrics_stack.mjs --pipeline --runtime`，被动捕获同一次代理响应，无业务响应替换 | **11 个 PASS**：原 10 条链回归，加上浏览器暂存（资产未变）→PG 确认→主来源再次同步→已确认字段和同一指标绑定继续可用→撤销恢复主来源→PG 历史。**50 个浏览器 API 响应**请求 UUID/no-store/nosniff 通过；主来源 fixture、模型 mock、补充来源为明确的合成手工导入 |
| 静态门禁 | `scripts/check_release_inputs.py`、`git diff --check` | **通过**；不是远端 CI 或部署 |

失败与修正：纯领域新测试初次给无参 `ResourceScope.tenantWide()` 传入 tenant 导致编译失败，改为实际 API 后全部 main 通过。HTTP 新测试初始化把 Host 同步空正文误写成 `{}`，触发现有契约 400；修正测试输入后完整流程及全量 Java 通过，没有放宽同步接口。浏览器首轮新增两个用例超时，页面实际没有渲染 For 块体回调中的记录和字段；改用项目现有的“表达式返回子组件 + forItem”写法后新 7 项、全量 99 项通过。截图核查时发现已确认记录仍显示禁用的“请选择”，改为直接显示历史决定中的来源选择，再次执行构建和 7 项回归通过。最终复查新增主快照 scope 校验，故意将快照 ID 改成另一资产的 PG 负例通过，之后全量 Java 118 项与两个 bootJar 重跑通过。

已查看 `.tmp/metrics-acceptance/source-review.png`，目标资产版本、主来源/补充值、映射 digest、actor/原因/时间/原始引用、生效及撤销区域可读；Token 为密码显示。验收脚本自动关闭其 Java/Rust/Vite/Chromium，专用 PG/VM 继续仅 loopback；临时凭据/产物不提交。

限制：固定补充字段导入不是通用已有资产合并、强标识自动 Resolver、持续二来源 presence 融合或厂商 CMDB API。生效确认需先撤销再替换；过期最后已知字段保留，不自动恢复或宣称新鲜。分页按 UUID，不是时间排序或跨页事务快照。V015 需配套 writer，未测试旧二进制并行写入、生产迁移/回退、OS 崩溃或 HA。留存清理/总量预算、完整告警观测、剩余身份/筛选恢复、真实 Zabbix/模型/登录与人工诊断评估仍待完成。M2 粗估 80%，MVP 等权均值 76%、取约 **75%（±5 个百分点）**，100% 目标继续进行。设计及复现见 [ADR-027](adr/027-supplemental-source-field-review.md)、[本机验收](runbooks/source-review.md)。

## 34. 2026-09-25 不可变规范化告警历史与当前归属查询（追加）

继续原 `main` 工作区，没有新建分支、提交、推送或部署。V016 在现有 PG 保存恢复合并前的规范化输入、首次接收纳秒、显式模式/来源契约和当时 Host→Entity 映射。稳定 UUID 由 tenant/source/event/observedAt 派生；相同输入重投不改写历史，迟到输入仍留存，同身份不同内容拒绝整批并回滚投影。旧 Incident 合并快照不能重建输入，因此不回填、不把当前值冒充历史。

新增历史 API 和 canonical record/query/page。查询核对当前 Incident 版本、最多 31 天 observedAt 闭区间、固定 firstReceivedAt cutoff；来源/事件 ID/UUID 分页最多 25 条。当前 Incident 及实体授权、历史映射范围先于 LIMIT；历史映射缺失不会被之后补齐的资产映射追认。PG 在同一只读 repeatable-read 事务核对当前版本和 occurrence 归属，合并归档来源不再拥有转移告警的历史，旧版本返回 409。页面显示原始规范化状态、双时间/当时映射与未留存缺口，409 要求刷新，撤权和退出清除数据。没有新增模型 Tool。

| 检查 | 实际命令 / 方法 | 最终结果与边界 |
|---|---|---|
| 结构 / 契约 | Docker Python 3.12 执行 `.tmp/check-history-contracts.sh`：check_repo、pytest contracts、check_release_inputs | **160 个结构化文件、6 个只读 Tool；281 passed**。新增 3 类 Schema/样例、查询身份/数量/时间/事件来源组合、模式/记录字段/缺口负例 |
| 纯领域 | `node .tmp/domain-check.cjs`：Java21 编译所有 modules/tests/domain，执行全部 main | **607 项通过，23 个 main**。新增历史 30 项：原样重投、首次映射不改写、迟到、恢复遗漏保留、纳秒 cutoff、全批回滚、游标/版本/授权、异常端口输出和合并归属 |
| Java / 启动包 | `node .tmp/pipeline-test-env.cjs full` → 两应用 test/bootJar `--console=plain --rerun-tasks`，专用 loopback PG/VM | **111 平台 + 15 Worker = 126 tests，0 failures/errors/skipped；两个 bootJar 成功** |
| PG 历史验证 | `PostgresIncidentIT` 新增 5 项并扩展并发重投断言，包含在上述总数内 | **通过**：并发原样投递一条历史、重开保留纳秒、恢复遗漏/迟到原输入、全批回滚、历史映射过滤先于分页、当前归属/旧版本、损坏行元数据拒绝、128 字符 tenant 边界 |
| HTTP 边界 | `ProblemHistoryHttpIT` 2 项与权限拒绝 1 项，包含在上述总数内 | **通过**：知识截止 1 纳秒边界、分页、来源/事件筛选、401/403/404/409、非法/重复/无界参数、UUID 回传/no-store；保存真实 memory HTTP 输出 |
| 实际产物契约 | Docker Python 执行 `.tmp/check-pipeline-response.py`，Draft202012Validator + FormatChecker | **53 份请求/响应/持久记录，32 类 Schema 通过**。新增 memory HTTP 和 PG 浏览器两组 record/query/page；不是 53 条独立 E2E |
| Rust 默认 / 全特性 / 格式 | `node .tmp/rust-check.cjs` → fmt、`cargo test --workspace --locked -j 1` 与 `--all-features` | **各 40 passed，0 failed/ignored；fmt 通过**。未调用真实模型/MCP |
| Web 类型 / 构建 / 回归 | Playwright `.tmp/playwright-local.config.ts`；webServer 执行 tsc/Vite build | **全量 108 passed**（97 项浏览器显式 fixture + 11 项请求层直接测试）。新增 9 项验证原状态/文本、固定 cutoff/version 翻页、来源事件筛选、越 tenant/occurrence/历史映射、未来 1 纳秒、游标、409 手工刷新、撤权/迟到清理；截图修正后再次构建并运行新 9 项，全部通过 |
| 真实本机完整链 | `node .tmp/pipeline-test-env.cjs browser` → `scripts/check_metrics_stack.mjs --pipeline --runtime` | **12 个 PASS**；新增网页告警历史→PG 输入/版本/留存缺口。**51 个浏览器 API 响应** UUID/no-store/nosniff 通过，0 边界失败。真实 Java/PG/VM/Rust，本机来源 fixture、模型 mock；显示修正后完整链再次通过 |
| 静态检查 | `git diff --check`、发布输入检查 | **通过**；不代表远端 CI、发布或生产验收 |

失败与修正：最初 3 项 AIInsight PG 回归因独立迁移清单遗漏 V016 而失败；将使用 PostgresIncidentStore 的两个独立测试初始化加入新迁移后通过。新增 HTTP 测试误用 `X-Request-Id`，断言拿不到项目现有的 `X-OpsWeave-Request-Id`；修正测试使用既有协议，没有修改请求边界。最后复核发现 V016 tenant 长度最初误写 64，改为领域和现有表的 128，并增加真实 PG 长 tenant 用例；仅调整本任务专用 fixture 库中刚创建的表结构，无数据删除，之后全量 Java 126 项和两个启动包通过。截图中空筛选框缺少可见边界，添加局部边框/提示和长字段换行，构建、新 9 项页面回归与完整本机链复测通过。

已查看最终 `.tmp/metrics-acceptance/problem-history.png`：两条同事件不同观测时间的记录、模式/来源、首次接收/映射、当前版本/cutoff、恢复/抑制与缺口可读；输入框边界和提示可见。验收脚本自动关闭其 Java/Rust/Vite/Chromium，专用 PG/VM 继续仅 loopback；临时凭据/产物不提交。

限制：这是留存的规范化来源输入，不是完整厂商 JSON/事件日志、Incident 任意 asOf 状态或留存前回填。按 UUID 排序，每页事务一致；固定 cutoff/version 不等于跨页数据库快照。尚无留存清理/存储总量预算、时间线分页或生产迁移/回退/HA 容量验收。真实 Zabbix/模型/登录和人工评估仍待环境，继续本地实现。M3 粗估由 80% 到 85%，MVP 等权约 **77%（±5 个百分点）**，100% 目标继续；下一步筛选 URL 恢复、生产身份适配和通用跨源治理。设计与复现见 [ADR-028](adr/028-normalized-problem-history.md)、[本机验收](runbooks/problem-history.md)。

## 35. 2026-09-25 只读筛选、资源与实际指标窗口的 URL 恢复（追加）

继续原 `main` 工作区，没有新建分支、提交、推送或部署。资产页恢复已应用名称/IP、类型、生命周期、游标和选中 ID；Incident 恢复状态/游标/选中 ID；指标恢复 Host 选择、metricKey、Last 范围及实际查询的 UTC from/till。三类 canonical Schema/样例和 URL 规则是只读选择契约，不包含身份、凭据、结果缓存、导入/操作正文或幂等键。

同路径 popstate/hashchange 去重并使旧响应失效，刷新/前进后退只还原表单，必须显式重新读取。指标固定窗口不能退回最近值，目录已无选中指标时明确失败；实际 select 展示与读取参数一致。刷新只保留 URL，Token 仍仅在内存；初始逐字输入到首次读取前保持链接，已有会话换凭据/注销/拒绝/到期清理选择。非法、重复、未知或超限参数显示错误并禁用读取，显式重置恢复。没有新增 Java API、存储迁移、服务或模型 Tool。

| 检查 | 实际命令 / 方法 | 最终结果与边界 |
|---|---|---|
| 结构 / 契约 | Docker Python 3.12 执行 `.tmp/check-history-contracts.sh`：check_repo、pytest contracts、check_release_inputs | **166 个结构化文件、6 个只读 Tool；319 项通过**。新增 3 类 selection Schema/样例及 35 项身份/命令/缓存/长度/枚举/字符/UUID/时间边界负例与未选窗口正例 |
| 纯领域 | `node .tmp/domain-check.cjs`，Java21 编译全部 modules/tests/domain 并运行 main | **607 项通过，23 个 main**；本次没有改变领域行为 |
| Java / 两个启动包 | `node .tmp/pipeline-test-env.cjs full`，专用 loopback PG/VM；两个 test/bootJar，`--rerun-tasks` | **111 平台 + 15 Worker = 126 tests，0 failures/errors/skipped；两个 bootJar 成功** |
| Rust 默认 / 全 feature / 格式 | `node .tmp/rust-check.cjs`：fmt、workspace default/all-features locked，`-j 1` | **各 40 passed，0 failed/ignored；fmt 通过**。先结束 Rust 测试，再启动完整验收 Runtime，没有真实模型调用 |
| Web 类型 / 构建 / 回归 | Playwright `.tmp/playwright-local.config.ts`，webServer 运行 `tsc --noEmit` 与 Vite build | **全量 124 passed**（110 项浏览器显式 fixture + 14 项请求层/codec 直接测试）。新增 13 项浏览器与 3 项 codec；最后补齐逐字输入初始 Token 的边界后，**再次构建及相关 48 项全部通过**（45 浏览器 + 3 codec） |
| 真实本机完整链 | `node .tmp/pipeline-test-env.cjs browser` → `scripts/check_metrics_stack.mjs --pipeline --runtime` | **13 个 PASS**，含刷新无自动请求/无恢复凭据、相同资产/Incident/指标及实际窗口的 Java 重授权读取。此前历史/审核/诊断/幂等保存/重放/人工归属链继续通过。**56 个浏览器 API 响应** UUID/no-store/nosniff 通过、0 边界失败。数据来源 fixture、模型 mock，Java/PG/VM/Rust 是真实本机进程 |
| 实际产物契约 | Docker Python `.tmp/check-history-artifacts.sh`，Draft202012Validator + FormatChecker | **56 份实际产物、35 类 Schema 通过**；新增三份由真实浏览器 URL 提取的规范化选择，其余是现有 Java HTTP/PG/浏览器链产物。这不是 56 条独立 E2E |
| 静态检查 | `git diff --check`、发布输入检查 | **通过**；不代表远端 CI 或部署 |

失败与修正：首轮 TypeScript 编译发现局部 range 变量遮蔽 selection 函数，改名后通过。新增浏览器恢复用例发现 Incident/Metric 动态 option 的初始选中值未跟随内部状态，显式绑定 option.selected 后通过。契约测试首次误用 Python `from` 关键字参数导致收集失败，改为字典更新后通过，并明确正则尾部不能接受换行。全量 Web 首次 123/124，其中凭据替换后的 URL 同步断言早于浏览器地址事件，页面已清空；改为等待 URL 后全量 124 通过。最终复查初始 Token 逐字输入时不应按长度阈值误判身份替换，改为首次读取/已有会话边界，三个页面逐字输入与替换用例及相关 48 项再次通过。

已查看 `.tmp/metrics-acceptance/restored-metrics.png`：实际固定 UTC 窗口、资源/metricKey、目录下拉框、曲线和 fixture 来源可读，开发 Token 为密码显示。验收脚本退出并关闭自建 Java/Rust/Vite/Chromium；专用 PG/VM 保持 loopback，临时凭据/产物不提交。

限制：浏览器旧历史仍可能含查询词和对象 ID，不存储凭据但也不承诺擦除历史；所有链接始终重新授权。UUID 游标是实时视图，恢复后不虚构总页数。历史子面板筛选、写操作和诊断执行输入不纳入恢复协议；没有生产 OIDC/BFF/CSRF/撤销验收。真实来源/模型/身份、通用 Resolver/presence、费用/留存与人工评估继续保留在退出清单。M1 粗估由 60% 到 65%，MVP 等权约 **78%（±5 个百分点）**，100% 目标继续。设计和复现见 [ADR-029](adr/029-restorable-read-selections.md)、[本机验收](runbooks/view-selection.md)。

## 36. 2026-09-25 OIDC BFF、会话撤销与有界 Runtime 委托（追加）

继续原 `main` 工作区，没有新建分支、提交、推送或部署。用户明确真实环境暂未准备，先推进本地实现。本次增加显式 OIDC authorization-code BFF：固定 issuer/端点/回调，state/nonce/S256 PKCE、RS256/issuer/audience/时间验证；IdP 只提供经过验证的 issuer/sub，平台租户、用户、权限、资源范围由操作员授权文件决定，忽略 Token 内额外 tenant/permissions。文件逐请求重新读取与 digest 校验，禁用/改动/损坏不会沿用旧授权。

浏览器只使用 HttpOnly Cookie 和内存中的 masked CSRF。普通配置要求 HTTPS、Secure/Host Cookie；本机协议模式明确 loopback 与 `oidc-protocol-test`，不是生产认证验收。单进程最多 1000 个 session，登录准备 180 秒、登录后配置 60–1800 秒且不超过 ID Token 到期，不随读取续期；成功登录旋转 cookie ID/CSRF，退出、到期和授权变更撤销会话。Web 只自动读取登录元数据，业务选择恢复继续要求显式读取；退出清除双标签页与迟到结果。

Java 为一次已授权诊断签发最多 4 个并发、65 秒、8 次请求的 opaque Runtime 委托，固定原始 session、incident/window/runId/question/read-session 与允许端点，禁止浏览器 Cookie/Origin、非 loopback、来源写入或任意 API。每次使用复核登录/授权，任务结束删除；原有 Tool 四调用预算、对象授权、证据、Runtime 提交证明和幂等保存继续有效。IdP Token 不返回 Web，也不传给 Rust。没有新增部署单元、数据库或模型 Tool；依赖锁由 Gradle `dependencies --write-locks` 实际生成。

| 检查 | 实际命令 / 方法 | 最终结果与边界 |
|---|---|---|
| 结构 / 契约 | Docker Python 3.12 `.tmp/check-history-contracts.sh`：check_repo、pytest contracts、check_release_inputs | **350 项通过，6 个只读 Tool**。新增会话/退出/操作员授权 3 类 Schema 和样例，拒绝提供方凭据/非法身份/范围/额外字段；末次静态扫描 171 个结构化文件 |
| 纯领域 | `node .tmp/domain-check.cjs`，Java21 编译全部 modules/tests/domain，运行所有 main | **619 项、24 个 main 通过**。新增 IdentityGrant 12 项：必须匹配已验证 issuer/sub、禁用拒绝、到期纳秒边界、非法外部身份/版本等；领域无 Spring/JDBC/HTTP 依赖 |
| Java / 两个启动包 | `node .tmp/pipeline-test-env.cjs full`，专用 loopback PG/VM，两个 test/bootJar、`--rerun-tasks` | **127 平台 + 15 Worker = 142 tests，0 failures/errors/skipped；两个 bootJar 成功**。最终补充旧 CSRF 和缺 Origin 断言后再次运行 OIDC 相关 **18 项通过** |
| OIDC HTTP / 会话 / 委托 | `OidcLoginIT` 6、`OidcBoundaryTest` 7、`OidcHttpTest` 3，以及原有 raw-bearer 边界 2 项，含于上述总数 | **通过**：真实 Spring HTTP + 本机 RSA/JWKS/token 服务；错误 state/重放、issuer/audience/nonce/iat/exp/签名/sub、64 KiB advertised/chunked、503 与慢正文总时限；映射/权限/范围/撤权/文件失败；cookie 旋转、旧/缺 CSRF、缺/外 Origin；内部委托同会话/固定端点/read-session、65 秒精确期限、8 调用/4 并发、关闭/注销/新登录/改授权失效。生产 Secure/HttpOnly/Path/SameSite 是配置测试，未宣称真实 TLS 浏览器验收 |
| Rust 默认 / 全 feature / 格式 | `node .tmp/rust-check.cjs`：fmt、workspace default/all-features locked，`-j 1` | **各 40 passed，0 failed/ignored；fmt 通过**。先结束编译/测试再启动验收 Runtime；未调用真实模型/MCP |
| Web 类型 / 构建 / 回归 | Playwright `.tmp/playwright-local.config.ts`，webServer 执行 tsc/Vite build | **全量 128 passed**（110 项浏览器 fixture + 18 项请求层/codec 直接测试）。新增 4 项 Cookie/bootstrap/CSRF/无 Bearer/期限/401/403/迟到响应/严格元数据测试；最终收紧恢复时旧请求处理与文案后，**再次构建及相关 33 项通过** |
| OIDC 本机完整链 | `node scripts/check_oidc_stack.mjs`（`.tmp/oidc-stack.cjs` 仅注入专用 PG/VM 环境） | **5 个 PASS、17 个浏览器响应 UUID/no-store/nosniff 检查通过，0 边界失败**。真实浏览器授权码跳转→Java→操作员 Principal→Cookie/CSRF→有界委托→Rust mock→真实 PG/VM Tool→PG AIInsight；刷新只读元数据，显式回读结果/证据；双标签退出、401、授权文件撤销、无凭据持久化。最终 Web 修正后整链再次通过 |
| 原开发模式完整链 | `node .tmp/pipeline-test-env.cjs browser` → `scripts/check_metrics_stack.mjs --pipeline --runtime` | **13 个 PASS、56 个浏览器响应边界通过、0 失败**。资产/历史/字段审核/指标/Incident/归属/流水线/诊断与 URL 恢复继续通过，来源 fixture、模型 mock；没有把 OIDC Cookie 默默替换为 dev 凭据 |
| 产物契约 | Docker Python `.tmp/check-oidc-artifacts.sh`，Draft202012Validator + FormatChecker | **60 份产物、38 类 Schema 通过**。原 56 份加 OIDC session、logout、合成授权文件和 PG AIInsight；session 仅 CSRF 字段脱敏，其余保留实际值。不是 60 条独立 E2E |
| 静态门禁 | `git diff --check`、发布输入检查 | **通过**；没有远端 CI 或部署 |

失败与修正：最初通用 Spring session-management 策略把每次重新构造的请求身份当成新认证，反复重置 CSRF，退出收到 403；改为应用自有会话，成功登录时显式旋转 ID/CSRF，后续请求只重验证授权，旧 Token/Origin 负例和退出通过。旧占位测试仍断言 OIDC 无法启动，更新为 raw dev/provider Bearer 不得在 OIDC 模式解析，缺失/非法配置仍由启动边界拒绝。前端一项测试两次生成到期时间相差 1ms，改为同一固定样例后全量通过。OpenAPI 新端点遗漏公共请求 ID 声明，补齐后契约通过。新整链脚本曾遗漏告警导入 JSON 正文；修正测试输入，并发现 Servlet 错误 dispatch 被鉴权链改写为 401，允许框架 ERROR dispatch 后保留 415 且不会误清会话，真实 HTTP 断言通过。首次图表读取早于 VM 写入可见，验收脚本增加有界可见性等待后通过；产品没有增加重试。

已查看 `.tmp/oidc-acceptance/oidc-insight.png`：协议测试/用户租户/期限、mock 与 fixture 标记、问题/窗口/Skill、结果、两份证据和缺口均可读，无 Token 输入或凭据内容。脚本关闭自建 IdP/Java/Rust/Vite/浏览器，专用 PG/VM 保持 loopback，产物和临时凭据不提交。

限制：尚无真实 IdP HTTPS/密钥轮换/反向代理部署验收，无 IdP back-channel logout、Token 自动刷新、共享 session/HA；平台退出不代表 IdP 退出。Runtime 仍是同机 `platform-dev` 传输加 Java 委托边界，不宣称跨主机服务认证。Worker 独立服务身份、通用 Resolver/presence、费用/留存、真实来源/模型与人工评估继续未完成。M1 粗估从 65% 到 85%，MVP 等权约 **82%（±5 个百分点）**，整体约 53%，100% 目标继续。设计与配置见 [ADR-030](adr/030-oidc-bff-and-bounded-runtime-delegation.md)、[本机验收](runbooks/oidc-bff.md)。

## 37. 2026-09-25 Worker 独立服务身份与受限历史采集（追加）

继续用户指定的 `main` 工作区，不新建分支、提交、推送或部署。真实提供方配置暂未准备，按用户选择先推进本地实现。本次新增默认关闭的 OAuth client_credentials Worker profile 与独立 GET history 入口，复用原有有权历史读取、VM 确认及 PG checkpoint。浏览器/dev/Runtime 凭据不能混用；没有新启动单元、数据库、模型 Tool 或依赖升级。

平台仅接受固定 issuer/JWKS、RS256 at+jwt、单一 audience/scope、client_id/sub/jti/iat/exp 的短期 access token。运维文件决定 tenant/subject、source/item/entity/metric、有效期、可读数据范围、窗口/点数/每分钟预算；JWT 的 tenant/权限 claim 不授予权限，Worker 不发送身份字段覆盖它。文件每请求复核，禁用、损坏或消失不使用旧授权；4 个共享并发，单 client 上限 120 次/分钟。普通模式要求直接 TLS，本次实际使用显式 loopback 协议 fixture，不能替代真实 TLS 验收。

Worker 固定 token endpoint/basic/form，正文 32 KiB/完整 5 秒，无跳转或应用重试，令牌仅内存单调期限缓存；401 清缓存并结束本次读取。失败不推进 completedThrough/revision。token endpoint/client ID/认证模式进入系列 fingerprint，secret 不进入，同 client 的密钥轮换可续采。原 dev profile 保持固定 tenant-demo，VM/PG 仍仅 loopback。

| 检查 | 实际命令 / 方法 | 最终结果与边界 |
|---|---|---|
| 结构 / 契约 | Docker Python 3.12 `.tmp/check-history-contracts.sh`：check_repo、pytest contracts、check_release_inputs | **384 项通过；173 个结构化文件、6 个只读 Tool**。新增服务授权 Schema/样例、字段/身份/范围/预算负例及独立 OpenAPI 安全声明 |
| 纯领域 | `node .tmp/domain-check.cjs`，Java21 编译全部 modules/tests/domain 并运行 main | **649 项、25 个 main 通过**。新增 HistoryServiceGrant 30 项：issuer/client/sub、令牌/授权期限、source/item、闭合过去窗口、点数/速率、显式资源权限、90 天/31 天总范围 |
| Java / 两个启动包 | `node .tmp/pipeline-test-env.cjs full`，专用 loopback PG/VM，两个 test/bootJar、`--rerun-tasks` | **135 平台 + 20 Worker = 155 tests，0 failures/errors/skipped；两个 bootJar 成功** |
| 服务 HTTP / Worker 边界 | 新增 HistoryServiceHttpIT 5、HistoryServiceConfigurationTest 3、ClientCredentialsTest 5，含于 Java 总数 | **13 项通过**。真实 HTTP JWT 签名/算法/类型/issuer/audience/scope/时间/subject/client 负例，dev/普通用户路径隔离、Cookie/Origin/转发头/身份覆盖/重复参数拒绝；撤权/过期/换资源/文件故障、速率/并发；文件严格 Schema/唯一身份/预算；交换缓存/401不重试、provider 503/302/非JSON/超量/慢正文及 secret 脱敏 |
| Rust 默认 / 全 feature / 格式 | `node .tmp/rust-check.cjs`，fmt、workspace default/all-features locked，`-j 1` | **各 40 passed，0 failed/ignored；fmt 通过**。先结束检查再启动 Runtime，没有真实模型调用 |
| Web 类型 / 生产构建 / 回归 | `pnpm --filter @opsweave/web-console build`；Playwright `.tmp/playwright-local.config.ts` | **构建通过；全量 128 passed**。本轮未修改页面，OIDC/开发会话与读取流程继续回归 |
| 服务身份与 OIDC 完整链 | `node scripts/check_oidc_stack.mjs --history-service`（本机 wrapper 仅注入专用 PG/VM/Chromium/PG 容器配置） | **9 个 PASS、17 个浏览器响应边界通过**。独立 service client → 已验证 JWT/运维范围 → Java history → 真实定时 Worker → VM fixture 0.4 采样及标签 → PG checkpoint；在线撤权使多次轮询 403，游标和 revision 不变；token 服务 503 的重启 Worker 不读 history、不回退；同 client 轮换 secret 后续采。随后原 OIDC/CSRF → Rust mock → PG AIInsight、刷新回读、双标签退出、文件撤权均通过 |
| 原开发模式完整链 | `node .tmp/pipeline-test-env.cjs browser` → `scripts/check_metrics_stack.mjs --pipeline --runtime` | **13 个 PASS、56 个 API 响应 UUID/no-store/nosniff，0 边界失败**。既有资产/观测/审核/指标/Incident/人工归属/流水线/诊断/选择恢复继续通过 |
| 实际产物契约 | Docker Python `.tmp/check-oidc-artifacts.sh`，Draft202012Validator + FormatChecker | **62 份产物、40 类 Schema 通过**。原 60 份追加服务授权文件和实际 service metric-history-page；不含任何 client secret/access token。checkpoint 测试报告为事实记录，不冒充业务 Schema 或独立 E2E |
| 静态门禁 | `git diff --check`、Node 两个验收脚本 `--check`、发布输入检查 | **通过**；没有远端 CI 或部署 |

实际末次服务链中，撤权后 checkpoint 为 `completedThrough=1789992120/revision=2`，至少连续三轮拒绝后保持一致；恢复及密钥轮换后为 `1789992180/revision=3`。这是随机测试租户的合成来源数据，不是客户或真实厂商采样。脚本期间观察到 7 次 403、2 次成功 token 交换；503 只在计划轮询重新尝试，产品未添加隐式重试。

失败与修正：慢正文测试发现仅设置 Java HttpRequest.timeout 不会在响应头已到达后结束停滞正文，改为 sendAsync future 的完整期限并取消后测试通过。配置文件测试最初未将 canonical 样例加入 test resources，补上仅测试资源映射后全量通过。完整链首次通过，最终复跑暴露 Windows 强制 kill 与下一轮租约取得的竞争：新 Worker 正确等待原 300 秒 lease，脚本 60 秒期限失败。验收脚本改为仅观察白名单的批次终止日志、在持久化/释放 lease 后停止 Worker，再次完整 9 项通过；未缩短或跳过产品租约/fencing。Docker mount 首次命令引号被 cmd 解释为路径内容，未执行检查；修正参数后结构/契约与产物校验均成功。

脚本关闭自建 Worker/Java/Rust/IdP/Vite/Chromium，专用 PG/VM 保持 loopback；完整进程日志不保存，观察到的其他 Worker 日志丢弃。临时配置和产物留在忽略的 `.tmp`，没有提交。授权文件需由运维控制权限并原子替换；有限历史范围用尽后不会自动扩大。

限制：真实 IdP/HTTPS/证书/代理/厂商 Zabbix/模型与人工评估仍未验收；没有 opaque token introspection、分布式配额、共享会话、多流调度或远程 PG/VM。Runtime 仍为同机受限委托。通用跨源 Resolver/presence、费用/留存继续是下一步本地工作。M1 粗估由 85% 到 90%，MVP 等权约 **83%（±5 个百分点）**，整体约 53%，目标继续进行，不标记 100%。设计和复现见 [ADR-031](adr/031-history-worker-service-identity.md)、[服务身份验收](runbooks/history-service-identity.md)。

## 38. 2026-09-25 资产 UUID 登记、精确定位与导入身份依据（追加）

继续用户指定的 `main` 工作区，不新建分支、提交、推送或部署。用户暂未准备真实来源/模型/登录配置，本轮推进可独立验收的本地资产治理切片。新增人工核对的 canonical asset UUID 登记与撤销、租户+运维命名空间内的唯一生效目标、受权精确定位，以及固定 CMDB 导入可选身份 pin。没有更改已发布的四字段映射/digest、新增模型 Tool 或拆分启动单元。

页面先明确读取资产、命名空间和版本，核对 UUID 与原因后登记。tenant/actor/来源/权限来自可信平台；请求的 `expectedNamespace` 只作为配置前置条件，不能选任意命名空间。登记/撤销与 Entity version、原始幂等回执同事务；与来源采集/字段审核共享实体锁，跨实体同键并发由唯一索引和 scoped advisory lock 保证一个赢家。Entity/ExternalLink/指标/Incident ID 不迁移。

定位后的导入记录保留 registry ID/namespace/value/version；暂存与 ACCEPT 在实体锁内复核当前生效状态。登记撤销后旧待审记录不能生效。生效字段仍引用登记时先返回 409，必须显式撤销字段确认，再撤销标识。历史记录/原回执保持可读，相同登记请求在后续撤销后仍返回原始回执，不能据此推断当前生效状态。最多 16 生效/1000 历史登记每实体、25 条分页、16 KiB 请求；没有自动清理审计。

| 检查 | 实际命令 / 方法 | 最终结果与边界 |
|---|---|---|
| 结构 / 契约 | Docker Python 3.12 `.tmp/check-identity-final.sh`：check_repo、pytest contracts、check_release_inputs | **423 passed；189 个结构化文件、6 个只读 Tool**。8 类新 Schema/样例、4 个 HTTP 操作的 OpenAPI；UUID/namespace/隐藏身份覆盖/安全整数/状态一致性/撤销/pin/请求前置条件负例。两个 SourceReview Schema 增加可选 identity |
| 纯领域 | `node .tmp/domain-check.cjs`，Java21 编译全部 modules/tests/domain 并运行 main | **698 项、26 个 main 通过**。新增 AssetIdentity 49 项：租户/命名空间隔离、原回执、同键并发、权限/隐藏目标、pin/撤销/字段依赖、分页/16 个生效预算、非法 UUID/namespace。领域无框架/HTTP/JDBC 依赖 |
| Java / 两个启动包 | `node .tmp/pipeline-test-env.cjs full`，专用真实 loopback PG/VM，两个 test/bootJar、`--rerun-tasks` | **143 平台 + 20 Worker = 163 tests，0 failures/errors/skipped；两个 bootJar 成功** |
| 新 HTTP / PG | AssetIdentityHttpIT 3、PostgresAssetIdentityIT 5，含于 Java 总数 | **8 项通过**。真实 Spring HTTP 登记/重试/定位/导入/撤销、401/身份覆盖/重复参数/namespace 变更；真实 PG 同键同目标幂等、跨目标竞争与失败回滚、连接重开/原始回执、actor 冲突与租户/namespace 隔离、旧 pin 失效、先撤字段后撤标识、预算和分页 |
| Rust 默认 / 全 feature / 格式 | `node .tmp/rust-check.cjs`，fmt、workspace default/all-features locked，`-j 1` | **各 40 passed，0 failed/ignored；fmt 通过**；检查完成后才启动本机 Runtime，没有真实模型调用 |
| Web 类型 / 构建 / 回归 | Playwright `.tmp/playwright-local.config.ts`，webServer 实际运行 tsc/Vite build | **全量 139 passed、构建通过**。新增 11 项登记/定位/导入 pin、逐字文本、未知结果原请求重试、撤销按钮原因状态/命名空间和版本、非法响应与换行后缀、未映射 404、退出/迟到结果；原 128 项继续通过 |
| 开发模式真实存储完整链 | `node .tmp/pipeline-test-env.cjs browser` → `scripts/check_metrics_stack.mjs --pipeline --runtime`，新增 `lib/check_asset_identity.mjs` | **14 个 PASS、85 个浏览器响应 UUID/no-store/nosniff，0 边界失败**。真实浏览器→Java→PG 登记原始回执重试→定位→带 pin 导入/字段接受→依赖撤销 409 且版本不变→字段显式回滚→标识撤销/再定位 404；hostId/主来源字段保持可追溯。既有指标、Incident/归属、重放/草稿、Rust mock/AIInsight 和选择恢复回归通过 |
| Worker / OIDC 完整链 | `node .tmp/oidc-stack.cjs --history-service` → checked-in OIDC/service 脚本 | **9 个 PASS、17 个浏览器响应边界通过**。独立 service JWT/受限历史/真实 Worker/PG/VM、撤权游标不变、token 503 无回退、同 client 密钥轮换，以及 OIDC Cookie/CSRF/Runtime 委托、PG AIInsight、刷新与双标签退出继续通过 |
| 实际产物契约 | Docker `.tmp/check-identity-final.sh`，Draft202012Validator + FormatChecker | **73 份请求/响应产物、48 类 Schema 通过**。原 62 份加 registry 的 8 类产物、额外撤销回执、带 pin 的 SourceReview/import，共 11 份。只保存合成业务数据，CSRF 脱敏，不保存 Token/secret；不是 73 条独立 E2E |
| 静态门禁 | `git diff --check`、两个新增/调整整链脚本 `node --check`、发布输入检查 | **通过**；没有远端 CI 或部署 |

失败与修正：新真实浏览器链在“撤销标识”定位处超时；查看可访问性快照发现 JSX 文本与表达式拼接去掉了预期空格，改为完整模板字符串后，新增原因输入/清空/提交撤销测试与整链通过。脚本对预先建立的 response promise 附加即时拒绝处理，使后续点击失败能正常进入已有进程清理，不增加任何产品重试。最终复核还收紧 UUID/namespace/time codec 的字符串结束锚点，拒绝末尾换行，新增三个浏览器负例通过；写入 schema 的版本上限调整到安全递增范围，与领域约束一致。

已查看 `asset-identity-dependency.png`：撤销冲突说明、命名空间/UUID 定位依据、import/postgres 来源、有效期、字段撤销入口和历史记录可读，无密钥。截图只覆盖当前滚动区域，完整交互由浏览器断言覆盖。脚本退出关闭自建平台/Runtime/Worker/IdP/Vite/Chromium，专用 PG/VM 仍保留 loopback；未修改其他应用进程。

限制：这只是人工核对登记→授权精确定位→固定 CMDB 导入 pin，不是通用来源自动 Resolver、连续多来源 presence、已有资产合并/alias/标识迁移或真实 CMDB API。尚无 tenant 总量/时间清理、生产迁移/容量验收；真实来源/模型/IdP/TLS/代理和人工评估仍缺。M2 粗估从 80% 调整为 85%，MVP 等权约 **84%（±5 个百分点）**，整体约 54%；100% 目标继续，未关闭 M1–M4。设计、协议与复现见 [ADR-032](adr/032-registered-asset-identity.md)、[资产身份契约](../contracts/asset-identity.md)、[本机验收](runbooks/asset-identity.md)。

## 39. 2026-09-26 持久模型费用准入、提供方用量与独立查询（追加）

继续当前 `main` 工作区，未分支、提交、推送或部署。按用户“暂未准备，先推进本地实现”，本轮补齐诊断调用前的费用预留、调用后 Token 用量、未知费用状态与独立查询。Java ai-control 保持纯领域；新增 V018 PostgreSQL 账本、固定三条 API、六类 canonical Schema/样例，Rust 使用受控平台接口，仍为四个启动单元。没有修改已发布 Skill 或添加模型工具权限。

平台按可信主体/租户、当前 Incident/实体授权、read-session 和运维策略签发一次许可。租户 advisory lock 保证多连接并发的单次/每日估算限额；runId 不可重复获得调用许可。费率版本/金额为原始策略快照，缓存输入保守按完整输入费率。未收到合法用量的记录到期后为 UNCERTAIN，跨 UTC 日和进程重开继续占用预留；不自动重试、退款或把未知记零。最多10000条/租户，暂无时间清理或核销。原8次OIDC读取/保存预算、4并发、65秒不变，额外各一次绑定当前run/session的reserve/report。

Rig适配器改用锁定0.42.0的raw Responses单次请求，并在业务输出解析/证据复核/保存前上报input/output/cached Token。即使输出无效也先记录可核验用量；提供方无用量、传输失败或上报失败保留预留。输入已计费、输出0的情况可上报。真实提供方仅允许经平台预算的platform-dev；旧demo不再允许付费模型。HTTP无重试/重定向/代理，3秒连接、18秒整体请求、256 KiB响应、输出2048 Token；SDK日志subscriber关闭以避免trace输出内容。付费接入必须PG及显式费率，真实HTTPS地址仅在运维配置中提供。

金额是配置估算，**不是提供方账单/严格第三方计费保证**。81920输入Token上界为当前73728字节固定文本请求的保守准入假设，尚无提供方精确预检；真实网关、税费、缓存折扣、服务等级、价格变更和账单对账仍需验收。协议桩使用虚构模型/密钥/费用，不进行任何外部付费调用。

| 检查 | 实际命令 / 方法 | 最终结果与边界 |
|---|---|---|
| 结构 / 契约 | Docker Python3.12 `.tmp/check-model-spend-final.sh`：静态检查、pytest contracts、发布输入和实际产物校验 | **451 passed；201结构化文件、6只读Tool**。6新Schema/样例、3操作OpenAPI，字段/身份/金额/状态/Token/末尾换行负例；静态扫描改为提前剪枝生成目录，避免遍历target/node_modules |
| 纯领域 | `node .tmp/domain-check.cjs`，Java21编译所有modules/tests/domain并运行main | **731项、27个main通过**。新ModelSpend33项覆盖整数进位、准入/存储总数上限、并发唯一许可、重复run、跨日未知、actor/session/tenant、不可变报告及原始时间 |
| Java / 两启动包 | `node .tmp/pipeline-test-env.cjs full`，专用真实loopback PG/VM、两个test/bootJar、`--rerun-tasks` | **149平台+20Worker=169tests，0 failures/errors/skipped；两个bootJar成功**。新增HTTP2、PG3、OIDC委托1；严格body/重复键/整数溢出、进程证明、mock预留/报告/GET、PG跨连接竞争/重开/跨日/原回执、固定两次费用委托 |
| Rust 默认 / 全feature / fmt | `node .tmp/rust-check.cjs`，workspace locked、`-j 1`；最终Schema收紧后复跑 | **默认40、all-features43通过，0 failed/ignored；fmt通过**。新增Rig真实loopback HTTP协议桩3项；扩展原workflow/adapter负例，覆盖无额度不调模型、报告先于无效输出/撤权、报告失败不保存、错误范围/费率/金额/输入回执、重定向/503/缺用量/超量无重试 |
| Web 类型 / 构建 / 全量回归 | Playwright `.tmp/playwright-local.config.ts`，webServer实际执行tsc/Vite生产构建 | **151 passed、构建通过**。新增12项用量/配置估算、未知预留、禁止把404当零、错金额/缓存/主体运行/字段换行、无自动模型调用、身份清除/迟到结果；原139项继续通过 |
| 开发模式完整链 | `node .tmp/pipeline-test-env.cjs browser` → checked-in `check_metrics_stack.mjs --pipeline --runtime` | **15个PASS、87个浏览器响应UUID/no-store/nosniff，0边界失败**。真实Java→Rust mock→PG费用预留/报告→AIInsight→网页读取费用/刷新原回执；资产标识/字段审核、指标、Incident、人工归属、流水线、选择恢复继续通过 |
| OIDC / 服务Worker完整链 | `node .tmp/oidc-stack.cjs --history-service` → checked-in `check_oidc_stack.mjs --history-service` | **10个PASS、18个浏览器响应边界通过**。新费用委托与Cookie受权GET通过；原服务JWT→Worker→PG/VM、撤权游标不动、Token503无回退、同client密钥轮换，以及BFF/CSRF/AIInsight/刷新/双标签退出通过 |
| 实际产物契约 | Docker末次脚本 Draft202012Validator+FormatChecker | **77份实际产物、49类顶层Schema通过**。原73份+HTTP预留/报告、dev完整链回执、OIDC完整链回执4份；都为合成数据。不把产物数当成独立E2E数 |
| 静态门禁 | `git diff --check`、两个整链脚本`node --check`、发布输入检查 | **通过**；仍在main、无远端CI/部署。Cargo通过offline本地锁文件解析增加已锁定bytes/reqwest0.13.5的直接依赖，未升级任何crate版本，随后locked验证 |

失败与修正：新Rig测试先因fixture输入字节数写错被模型调用前校验拦截；修正后协议测试通过。原平台HTTP测试桩未提供费用路由、随后费用回执storage与会话不一致，严格适配器正确拒绝；补齐桩并断言费用失败停在两次读取、正常保存经过四次读取后全量通过。最后收紧新Schema/Web的字符串结束锚点，拒绝digest/费率版本的末尾换行，并在最终全量契约/Rust/Web检查通过后记录本节。未把这些中间失败写成成功。

已查看完整`model-spend.png`：独立用量按钮、Token/费率版本/预留/当前占用、配置估算非账单、mock零调用，以及下方诊断和证据缺口均可读。开发Token输入为掩码，截图未暴露密钥。临时配置和产物留在忽略的.tmp，没有提交；脚本退出关闭自建平台/Runtime/Worker/IdP/Vite/浏览器，专用PG/VM保持loopback，未触碰其他应用。

M4粗估60%→70%，MVP等权84%→**86%（±5个百分点）**，整体约55%。100%目标继续；真实Zabbix、模型、IdP/TLS/代理及人工审阅尚缺，通用持续来源Resolver/presence、已有资产合并、保留数据生命周期、费用核销/账单对账/按Skill聚合仍未完成。见[ADR-033](adr/033-model-spend-admission.md)、[费用契约](../contracts/model-spend.md)、[费用验收runbook](runbooks/model-spend.md)。

## 40. 2026-09-26 AI 正文留存、显式分批清理与不可重用的旧运行（追加）

按用户选择继续当前 `main`，未新建分支、提交、推送或部署。新增默认关闭的可信租户留存策略、独立 `ai.retention.manage` 与全租户范围检查、120秒预览、人工确认清理和回执查询。V019 在现有 PostgreSQL 表清除已过期 AIInsight/Evidence 的正文，保留原主键、会话唯一、引用关系和最小授权元数据；超过策略期限的 Tool 读取审计可分批删除。成本账本不清理，旧 run 不能重新获得模型许可；清除后重新授权返回 410，改输入/主体复用旧 ID 冲突。四个启动单元和六个只读 Tool 不变。

策略文件有 256 KiB/100租户/每租户1000保留Incident上限，天数1–3650，每类一批最多100条。正文创建时间及过期时间均须严格早于截止，审计开始/最后完成时间及活跃会话也检查；保留列表按原始Incident精确排除。预览摘要绑定租户、actor、策略和实际候选；PG事务锁中重算、清除、写原回执，提交前重读策略并检查时间。配置文件与数据库没有共同锁；在途请求采用提交前检查时的策略，紧急保留需停入口并等待在途结束。回执原actor同键同请求可回读，最多10000条/租户；未知结果不自动重试或继续下一批。

| 检查 | 实际命令 / 方法 | 最终结果与范围 |
|---|---|---|
| 结构 / 契约 | Docker Python3.12 `.tmp/check-retention-final.sh`，静态/pytest contracts/发布输入/实际产物 | **475 passed；209个结构化文件、6个只读Tool**。4新Schema/样例、3条OpenAPI、权限枚举同步；额外身份/执行字段、日期/digest、天数/批量、保留列表和响应负例 |
| 纯领域 | `node .tmp/domain-check.cjs`，Java21编译全部modules/tests/domain并运行main | **755项、28个main通过**。新24项覆盖保留与严格截止、预览时限、actor/tenant/策略摘要、非法预算、全租户权限及保留标记 |
| Java / 两个启动包 | `node .tmp/pipeline-test-env.cjs full`，自有loopback PG/VM，两个test/bootJar，`--rerun-tasks` | **159平台+20Worker=179 tests，0 failures/errors/skipped，两个bootJar成功**。新增PG5、HTTP3、文件1、普通诊断权限拒绝1 |
| 真实 PG 生命周期 | `PostgresAiRetentionIT`，包含于Java总数 | **5项通过**。过期合成正文置空、审计删除、原引用/会话/成本保留，原run拒绝再调用，撤销指标权限仍拒读；保留Incident/预览模式/配置改变整批回滚，两个连接并发同键只一回执，批量/hasMore/过期和边界、先清证据后清结果、跨租户隐藏 |
| HTTP 与配置 | `AiRetentionHttpIT`、`FileAiRetentionPoliciesTest`及原`ModelSpendHttpIT`增量 | **5项通过**。真实Spring/PG预览→显式空批→原回执，配置关闭后原结果回读，策略变化/摘要篡改/身份覆盖/重复键/未知query拒绝；缺授权、预览专用策略、缺失/损坏/过量配置失败关闭；普通诊断权限不授予清理 |
| Rust 默认 / 全features / fmt | `node .tmp/rust-check.cjs`，workspace locked、`-j 1` | **40/43通过，0 failed/ignored；fmt通过**。未改Rust或新增动作Tool；真实模型未调用 |
| Web 类型 / 构建 / 回归 | Playwright `.tmp/playwright-local.config.ts`，webServer实际执行tsc/Vite生产构建 | **最终全量160 passed，构建通过**。新9项：显式预览/勾选/一次提交、三类数量、预览专用策略、错误摘要/tenant/预算/字段/过期、未知结果原回执查询和退出/迟到清理。修正展示后另跑9项专项，再跑最终全量 |
| 开发模式完整链 | `node .tmp/pipeline-test-env.cjs browser` → `check_metrics_stack.mjs --pipeline --runtime` | **最终16个PASS、90个响应UUID/no-store/nosniff，0边界失败**。真实浏览器→Java/PG预览→显式空批回执→刷新重授权回读；新数据不清除、三类数量必须可见。实际老正文清除由PG测试覆盖，不把空批冒充删除验收；既有全链继续通过 |
| Worker / OIDC完整链 | `node .tmp/oidc-stack.cjs --history-service` | **10个PASS、18个浏览器响应边界通过**。原service JWT/受限Worker/PG/VM、撤权游标不动、token故障不回退、密钥轮换，以及BFF/CSRF/Runtime费用委托/诊断/退出全部回归。新留存管理使用独立权限，不加入Runtime委托 |
| 实际产物契约 | Draft202012Validator + FormatChecker，末次Docker脚本 | **83份实际产物、51类顶层Schema通过**。原77份追加PG实际删除、HTTP空批及浏览器空批的preview/receipt各一对；无密钥/真实客户载荷，不等于83条独立E2E |
| 静态门禁 | `git diff --check`、两个整链脚本`node --check`、发布输入检查 | **通过**；仍为main，无staged变更，无远端CI/部署、依赖升级或生成目录入库 |

中间失败与修正均保留：最初领域导入`java.security.*`使Principal/Permission名字冲突，改为明确的散列类型导入后编译通过。PG负例最初预期ToolFailure，但缺少entity.read时Incident层会先拒绝；补齐该权限以单独验证旧元数据的metric授权。OpenAPI最初遗漏关联头声明，契约检查失败后补齐。浏览器桩glob未匹配子路径，改为完整路径正则。截图发现清理数量为空，追加断言复现：当前Zeus编译器的For子项使用block-bodied renderer未输出行，改为与既有页面一致的表达式renderer后专项9项、全量160项和整链通过；未改框架或跳过断言。一次整链失败在待响应promise上超时，补即时拒绝处理以让主流程按既有finally清理，并在前端构建完成后串行复跑成功。产品未加自动重试。

已查看最终完整`ai-retention.png`：管理说明、原请求标识、完成时间及三类数量/字节均可读，开发凭据为掩码。验收数据是自有随机租户和明确合成来源；脚本关闭自建平台/Runtime/Worker/IdP/Vite/Chromium，专用PG/VM继续仅loopback；忽略的.tmp保留报告/临时文件，未提交敏感材料。

本轮不清除其他业务数据、会话/保留标记/引用/费用元数据，也没有生产定时调度、tenant总容量限制、WAL/备份/磁盘物理擦除验收。逻辑字节不等于释放磁盘空间。真实Zabbix、模型、IdP/TLS/代理与人工诊断审阅仍缺；通用持续来源Resolver/presence、已有资产合并和费用账单核销继续未完成。M4粗估70%→80%，MVP86%→**88%（±5个百分点）**，整体约56%；100%目标继续，未关闭M1–M4。见[ADR-034](adr/034-ai-content-retention.md)、[契约](../contracts/ai-retention.md)、[本机操作说明](runbooks/ai-content-retention.md)。

## 41. 2026-09-26 Host 扫描来源租约、过期接管与旧写入隔离（追加）

继续用户指定的当前 `main` 工作区，无新分支、提交、推送或部署。检查持续来源接入基础时发现：原实体锁不能阻止较旧完整扫描在新扫描后执行过时的缺失对账。本轮为 Host 库存写入增加 V020 持久来源租约，按可信 tenant/source/type 隔离；30秒续租、5分钟不可延长截止、单调 fence。PG 来源锁/数据库时间和库存写入在同一短事务，所有阻塞写完成后再检查时效，过期则回滚实体/观测/下线变化。旧扫描也不能释放新持有者。

Host 用例在来源读取前后、记录处理前续租，通过受保护的 upsert/finish 写库存；不持有数据库连接等待来源 HTTP，没有隐藏重试或自动恢复。租约不经过请求或模型，原权限、固定映射版本和明确 fixture 标记保留。接口新增四个稳定失败码，并修正 CHECKPOINT_FAILED 摘要：状态失败可能发生在库存已提交后，不能保证已提交对账被撤销。

| 检查 | 实际命令 / 方法 | 最终结果与边界 |
|---|---|---|
| 结构 / 契约 | Docker Python3.12 `.tmp/check-source-scan-final.sh`，静态/pytest contracts/发布输入/实际产物 | **491 passed；212个结构化文件、6个只读Tool**。新增Host失败Schema/样例与15项负例/范围测试，禁止额外身份/fence/租约、伪完整快照、无运行的阶段失败、计数越界和非法码 |
| 纯领域 | `node .tmp/domain-check.cjs`，Java21编译全部modules/tests/domain并运行main | **788项、29个main通过**。新SourceScan33项：来源占用无连接器调用、tenant/source/type隔离、30秒边界/续租/5分钟截止、fence上限、旧释放不影响新持有者；实际暂停旧线程后让新扫描完成，再恢复旧线程，旧非空页和空完成页都不能覆盖/下线新资产 |
| Java / 两个启动包 | `node .tmp/pipeline-test-env.cjs full`，自有真实loopback PG/VM，两个test/bootJar，`--rerun-tasks` | **165平台+20Worker=185 tests，0 failures/errors/skipped；两个bootJar成功**。最终HTTP/摘要修改后重跑全量 |
| 真实 PG 所有权 | `PostgresSourceScanIT`，包含于Java总数 | **5项通过**。独立适配器/连接竞争只有一个持有者，重建适配器仍读到租约；测试专用租约过期后接管，旧写入/对账/释放无效，独立写接口不能绕过；tenant/source/type隔离；持有真实实体行锁使写入/下线等待，再让租约过期，释放行锁后整事务回滚，实体仍ACTIVE、原版本与单条Observation保留 |
| HTTP | `PipelineHttpIT`新增1项，包含于Java总数 | **通过**。持有来源租约时POST返回503/SOURCE_SCAN_BUSY/零进展/失败SyncRun，无tenant/fence/lease字段；释放后新的显式请求成功；客户端提交fence/leaseUntil返回400。输出实际失败JSON供Schema验证 |
| Rust 默认 / 全features / fmt | `node .tmp/rust-check.cjs`，workspace locked、`-j 1` | **40/43通过，0 failed/ignored；fmt通过**。没有Rust产品改动或真实模型调用 |
| Web 类型 / 构建 / 回归 | Playwright `.tmp/playwright-local.config.ts`，webServer实际执行tsc/Vite生产构建 | **160 passed，构建通过**。本轮无新页面或浏览器测试；既有同步/字段审核/身份/费用/留存等继续回归 |
| 开发模式完整链 | `node .tmp/pipeline-test-env.cjs browser` → checked-in `check_metrics_stack.mjs --pipeline --runtime` | **16个PASS、90个响应UUID/no-store/nosniff，0边界失败**。真实Java/PG的多次Host同步使用新租约，固定字段审核后主来源重采/撤销、指标/告警/Runtime mock/AIInsight/费用/留存/流水线继续通过 |
| OIDC / Worker完整链 | `node .tmp/oidc-stack.cjs --history-service` | **10个PASS、18个浏览器响应边界通过**。身份协议fixture→服务JWT/Worker→PG/VM、撤权/故障游标保持、密钥轮换，以及Cookie/CSRF/受限Runtime/诊断/退出回归 |
| 实际产物 | 最终整链后 Draft202012Validator + FormatChecker | **84份产物、52类顶层Schema通过**。原83份加1份真实HTTP占用失败，都是自有合成数据，不等于84条独立E2E |
| 静态门禁 | `git diff --check`、发布输入检查、当前分支/暂存/生成目录检查 | **通过**；main、无staged变更，无新依赖升级或生成目录入库 |

首次领域编译、Java/PG回归即通过；随后补HTTP实际产物、失败契约和更准确的状态失败摘要，再执行最终全量检查。未跳过不稳定测试，也未把尚未运行项写为成功。PG到期测试仅缩短随机测试租户的 lease_until，保留真实数据库时间与 token 的不可变开始/截止；用真实阻塞行锁验证提交前过期，不依赖只检查方法调用的Mock。两个完整链结束关闭各自平台/Runtime/Worker/IdP/Vite/浏览器，自有PG/VM继续只在loopback；无真实客户或外部提供方调用。

保留边界：该切片仅覆盖 Host 库存扫描，不能替代 Item/Problem 所有权或全平台HA。上游 offset 漂移仍可能漏项，完成标记仍是offset-scan-attempt；已经提交的部分记录不全局回滚。进程终止可能遗留RUNNING元数据，过期接管不自动修复旧状态。迁移需要停止旧版本写进程，生产迁移/多主机故障切换尚未验收。通用持续来源Resolver/presence、已有实体合并、数据总量、真实Zabbix/模型/IdP/TLS/人工审阅仍未完成。

这是原同步正确性修复，**MVP保持约88%（±5个百分点），整体约56%**，不按测试数量提高进度；100%目标继续。见 [ADR-035](adr/035-host-scan-fencing.md)、[Host契约](../contracts/host-scan.md)、[操作说明](runbooks/host-scan.md)。

## 42. 2026-09-26 已登记来源快照、原子观测与第二来源确认（追加）

继续当前 `main` 工作区，无新分支、提交、推送或部署。遵循用户“暂未准备，先推进本地实现”：在已有 PostgreSQL/资产 UUID 登记/字段审核基础上新增显式 CMDB 快照导入。固定来源和命名空间由可信配置给定，只允许全租户 entity.read/entity.manage/source.sync；请求不能覆盖 tenant、actor、source、namespace、权限或租约。最多100条、HTTP128KiB、浏览器64KiB，观测须在过去7天内且同来源新请求时间严格递增。

固定引擎按已登记 UUID 定位已有资产；一笔有界PG事务保存不可变输入回执、Observation、PENDING字段审核与来源确认。未知标识、绑定冲突或失败整批回滚；不按名字/IP合并，不自动采用字段。完整快照才对该来源已知绑定标缺失，连续缺失刷新确认时间，部分批次不对账。仍新鲜且登记有效的第二来源可保护主来源已缺失的资产；到期/登记撤销在读取筛选和分页前撤销保护，不依赖后台清理。人工字段接受与新快照共同检查两方向绑定冲突。

| 检查 | 实际命令 / 方法 | 最终结果与边界 |
|---|---|---|
| 结构 / 契约 | Docker Python3.12 `.tmp/check-snapshot-final.sh`，静态/pytest contracts/发布输入/实际产物 | **519 passed；220个结构化文件、6个只读Tool**。新增四类Schema/样例及24项测试；未知身份上下文、非法UUID/字段、越界数量、伪live模式、引擎/摘要等拒绝 |
| 纯领域 | `node .tmp/domain-check.cjs`，Java21编译全部modules/tests/domain并运行main | **804项、30个main通过**。新SourceSnapshot16项：重复标识/外部对象、数量与字段限制、新鲜时间边界、回执对应和缺失/过期/撤销状态；过期的缺失确认也明确为STALE |
| Java / 两个启动包 | `node .tmp/pipeline-test-env.cjs full`，自有真实loopback PG/VM，两个test/bootJar，`--rerun-tasks` | **175平台+20Worker=195 tests，0 failures/errors/skipped；两个bootJar成功**。最后绑定检查/连续缺失修正后再次全量执行 |
| 真实 PG 来源快照 | `PostgresSourceSnapshotIT`，包含于Java总数 | **6项通过**。登记解析、字段仅待审、主Host缺失但第二来源新鲜时仍ACTIVE；部分空批保持、完整空批缺失、连续缺失更新时间、重新出现及人工字段接受；未知/旧批不部分写；同请求并发与重建适配器回执一致；身份撤销/过期在生命周期筛选前生效；新旧入口均拒绝外部对象或实体绑定冲突 |
| HTTP / 权限 | `SourceSnapshotHttpIT`两项、`SourceSnapshotBoundaryTest`一项及`SourceSyncDeniedIT`新增一项，包含于Java总数 | **通过**。实际配置/提交/同键回执/来源状态、未自动改字段、401/404及无额外身份参数；重复JSON键/尾随JSON/数组/128KiB越界/未知字段拒绝；对象范围受限的Principal即使具备三项权限也不得整源管理 |
| Rust 默认 / 全features / fmt | `node .tmp/rust-check.cjs`，workspace locked、`-j 1` | **40/43通过，0 failed/ignored；fmt通过**。本轮没有Rust产品改动或真实模型调用 |
| Web 类型 / 构建 / 回归 | Playwright `.tmp/playwright-local.config.ts`，webServer实际执行tsc/Vite生产构建 | **170 passed，构建通过**。新10项涵盖显式提交与会话清理、回执tenant/解析目标/时效/模式复核、未知结果404不重发、重复身份输入、显式来源读取、迟到配置丢弃；本地时间校验失败后表单仍可编辑 |
| 开发模式完整链 | `node .tmp/pipeline-test-env.cjs browser` → checked-in `check_metrics_stack.mjs --pipeline --runtime` 及 `lib/check_source_snapshot.mjs` | **17个PASS、96个响应UUID/no-store/nosniff，0边界失败**。实际浏览器→Java→PG登记解析/快照→待审字段/来源状态；刷新后显式重授权查询原回执；凭据清除使状态消失。原指标/Incident/流水线/Runtime mock/AIInsight/费用/留存链继续通过 |
| OIDC / Worker完整链 | `node .tmp/oidc-stack.cjs --history-service` | **10个PASS、18个浏览器响应边界通过**。协议fixture→独立服务JWT/Worker→PG/VM，撤权/故障游标保持、密钥轮换；Cookie/CSRF/有界Runtime/诊断/退出回归。最后Java修正后重新执行 |
| 实际产物 | 最终整链后 Draft202012Validator + FormatChecker | **93份产物、56类顶层Schema通过**。原84份加PG回执1份、真实HTTP4份、实际浏览器4份；输入/配置/回执/来源状态均为自有合成数据，不等于93条独立E2E |
| 静态门禁 | `git diff --check`、发布输入检查、当前分支/暂存/生成目录检查 | **通过**；main、无staged变更，无本轮依赖升级或生成目录入库 |

中间修正：最初Controller的Permission通配导入歧义导致编译失败，改为明确API导入；首次PG同键回执解码暴露Jackson数字节点类型不一致，改用相同JSON解析路径；首次HTTP测试因合成dev token不足32字符未启动，修正测试配置后运行。追加范围/尾随JSON/表单预校验负例并重跑。完整链后交叉检查发现人工字段入口可能与快照绑定矛盾，以及重复缺失未刷新确认时间，补齐共享绑定检查和真实PG断言，再重跑Java/bootJar及两条完整链。最终没有失败或跳过，不将编译或测试编写当作通过。

快照和来源状态截图已实际查看：输入、明确import/未连接说明、待审记录、来源状态与判断/到期时间均可读，凭据保持掩码。到期PG测试只调整自有随机租户的测试行，不修改全局数据库时钟；浏览器链只提交合成输入。测试日志分别保存在忽略的 `.tmp/snapshot-java-binding-verified.log`、`snapshot-domain-verified.log`、`snapshot-web-verified.log`、`snapshot-rust.log`、`snapshot-chain-final.log`、`snapshot-oidc-final.log` 和 `snapshot-contracts-verified.log`。完整链已关闭自建平台/Runtime/Worker/IdP/Vite/浏览器，自有PG/VM继续仅loopback；没有实际客户或外部提供方调用。

保留边界：这是显式人工导入适配，未连接真实CMDB API、未后台轮询，complete是有整源权限操作者的声明，不证明上游一致快照。每tenant/source保留绑定100个、回执1000份，无自动清理/全平台总容量治理；原输入为规范化字段，不声称保留厂商原始字节。绑定更正、已有Entity合并/alias、自动字段批准、主来源TTL及通用多源权威策略未实现。查询到期不伪造来源事件或增加实体版本；主Zabbix offset漂移、Item/Problem所有权、生产迁移/HA仍未验收。迁移需停止旧writer。真实Zabbix/模型/IdP/TLS/人工诊断审阅仍缺。

M2工程估算85%→90%，**MVP约89%（±5个百分点），整体约57%**；依据是补齐固定来源解析与生命周期保护能力，不是测试数量。M1–M4退出门槛仍未全部通过，MVP100%目标继续。下一步继续来源冲突追溯与受审计的绑定更正。见 [ADR-036](adr/036-registered-source-snapshots.md)、[契约](../contracts/source-snapshots.md)、[操作说明](runbooks/source-snapshots.md)。

## 43. 2026-09-26 来源绑定更正、历史归属保留与有界更正历史（追加）

继续当前 `main` 工作区，无新分支、提交、推送或部署。遵循用户“暂未准备，先推进本地实现”：第42节的已登记来源快照只允许新增观测，一旦人工发现绑定错位，就没有在保留历史归属的前提下纠正当前绑定的办法。本节补充受审的人工绑定更正。固定来源与命名空间仍由可信配置给定，只允许全租户 entity.read/entity.manage/source.sync；请求不能覆盖 tenant、actor、来源、命名空间或权限。

更正必须同时给出原 snapshotId、目标已登记 UUID、双方实体版本、晚于原快照的观测时间和原因；字段只允许 name/ip/owner/environment，至少一项且一律先进入 PENDING 审核。一笔有界 PG 事务对来源、双方实体与绑定加锁并复核版本、pin 与来源关系：原绑定存在生效字段时必须先撤销（否则 409），未知结果、版本或 pin 不符、目标非法或事务中任一失败都整笔回滚。历史 Observation、快照、指标与 Incident 引用不迁移，原快照仍归属原实体。更正回执按原 actor 幂等，最多保留 1000 份并共享快照预算；查询按原请求取回原结果，查询与更正历史分页都需要整源管理权限。Web 提供原/目标预览、明确确认勾选、未知结果回执查询与有界更正历史。

| 检查 | 实际命令 / 方法 | 最终结果与边界 |
|---|---|---|
| 结构 / 契约 | Docker Python3.12 `.tmp/check-correction-final.sh`（静态检查 + pytest contracts + 发布输入 + 实际产物校验） | **542 passed；226 个结构化文件、6 个只读 Tool**。较第42节新增 3 类 Schema（输入/回执/页面）与样例共 6 个结构化文件、23 项契约测试；V022 发布输入检查通过 |
| 纯领域 | `node .tmp/domain-check.cjs`，Java21 编译全部 modules/tests/domain 并逐个运行 main | **819 项、31 个 main 通过**。新增 `SourceBindingCorrectionSmoke` 15 项：新观测晚于原快照、原 snapshotId/双方版本/目标 pin、字段与原因限制、未知结果与原 actor 幂等 |
| Java / 两个启动包 | `node .tmp/pipeline-test-env.cjs full`，自有真实 loopback PG/VM，两个 test 与 bootJar，`--rerun-tasks` | **183 平台 + 20 Worker = 203 tests，0 failures/errors/skipped；两个 bootJar 成功**。完整链使用该次构建的 `platform-api-0.1.0-SNAPSHOT.jar` |
| 真实 PG 绑定更正 | `PostgresSourceBindingCorrectionIT` 6 项；`SourceSnapshotHttpIT` 4 项与 `SourceSnapshotBoundaryTest` 1 项回归（均含于 Java 总数） | **全部通过**。事务内来源/实体/绑定锁与版本复核、原生效字段未撤销拒绝、失败整笔回滚、原快照归属不变、同 actor 幂等回执、未知与越权查询拒绝 |
| Rust 默认 / 全 features / fmt | `node .tmp/rust-check.cjs`，workspace locked、`-j 1` | **40/43 通过，0 failed/ignored；fmt 通过**。本轮没有 Rust 产品改动或真实模型调用 |
| Web 类型 / 构建 / 回归 | `apps/web-console` 的 `npm run build`（tsc --noEmit + Vite 生产构建）与 Playwright `.tmp/playwright-local.config.ts` | **179 passed，构建通过（78 modules，dist 产物生成）**。新增 9 项更正页测试：预览/明确确认/回执查询/有界历史、会话清理与非法输入 |
| 开发模式完整链 | `node .tmp/pipeline-test-env.cjs browser` → checked-in `check_metrics_stack.mjs --pipeline --runtime` 与 `lib/check_source_binding_correction.mjs` | **18 个 PASS；106 个响应边界检查 0 失败**（`.tmp/metrics-acceptance/request-boundary.json` 记录 `checkedResponses: 106, failures: 0`）。实际浏览器→Java→PG：更正 + 新待审观测 + 原快照归属不变 + 回执/历史刷新；凭据仅在内存 |
| OIDC / Worker 完整链 | `node .tmp/oidc-stack.cjs --history-service` | **10 个 PASS、18 个浏览器响应边界通过**。撤权/故障游标/密钥轮换与既有诊断、委托、Cookie/CSRF 回归 |
| 实际产物 | 最终整链后 Draft202012Validator + FormatChecker | **100 份产物、59 类顶层 Schema 通过**。新增 7 份：PG 回执 1、真实 HTTP 3、实际浏览器 3；均为自有合成数据，不等于 100 条独立 E2E |
| 静态门禁 | `git diff --check`、当前分支与暂存检查 | **通过**；`main`、无 staged 变更，无本轮依赖升级或生成目录入库 |

环境与检查工具修正：首次整链在 `check_metrics_stack.mjs` 的 Runtime readiness 处报 `Runtime exited before readiness`，而该脚本用 `stdio: 'ignore'` 丢弃了 Runtime 的 stderr。先让脚本把 Runtime stderr 带进断言信息，再用两个探针确认同一二进制在平台 URL 不可达与可达两种情况下都能 `/readyz` 就绪，排除“平台在线导致退出”的假设。最终定位为本机 Docker Desktop 停止后遗留的 AF_UNIX socket 无法删除，导致引擎自身启动失败（`backend.error.json` 记录 `remove …/dockerInference: The file cannot be accessed by the system`）。把两个残留目录改名留档、重启引擎并重建 loopback PG/VM 后，完整链一次通过。这是本机开发环境修复，不改变产品行为；stderr 捕获保留在脚本里，便于下次直接定位。

更正与历史截图已实际查看：`.tmp/metrics-acceptance/source-correction-preview.png` 与 `source-correction-history.png` 显示原资产/原快照/目标 UUID/观测时间/字段 JSON/原因、预览摘要（原确认 PRESENT、原资产版本 10、目标版本 3）、未勾选确认时按钮禁用，以及更正历史（原快照及其历史归属保留、目标版本 4、新待审字段记录、原 actor 与时间）。凭据保持掩码。日志保存在忽略的 `.tmp/correction-java-verified.log`、`correction-domain.log`、`correction-contracts-final.log`、`correction-ts-final.log`、`correction-web-verified.log`、`correction-rust.log`、`correction-chain.log` 和 `correction-oidc.log`。自有 PG/VM 仅 loopback，本轮没有真实厂商或外部提供方调用。

保留边界：这是显式人工更正，不自动纠错、不自动批准字段、不做已有 Entity 合并/alias 或历史诊断重算。原生效字段必须先撤销，原 Observation/指标/Incident 归属不迁移，因此“当前绑定”与历史归属长期不一致是有意设计，而不是未清理的脏数据。没有厂商 CMDB/Zabbix API、后台轮询、主来源 TTL 或通用多源权威策略；每 tenant/source 保留 1000 份回执且无自动清理与全平台容量治理，迁移需停止旧 writer。真实 Zabbix/模型/IdP/TLS/代理与人工诊断抽样审阅仍未验收；Runtime 仍是同机 `platform-dev` 受限委托，模型为显式 mock。查询到期不伪造来源事件或增加实体版本。

M2工程估算保持90%，**MVP约89%（±5个百分点），整体约57%**；本节补齐来源链的可审计更正能力，不据此提高阶段估算。M1–M4退出门槛仍未全部通过，MVP100%目标继续。下一步核对 M0–M4 本地退出缺口，继续来源失败/冲突追溯与只读诊断异常场景评估。见 [ADR-037](adr/037-source-binding-correction.md)、[契约](../contracts/source-binding-corrections.md)、[操作说明](runbooks/source-binding-correction.md)。

## 44. 2026-09-26 来源扫描运行的可追溯只读查询（追加）

继续当前 `main` 工作区，无新分支、提交、推送或部署。遵循用户“暂未准备，先推进本地实现”：第41–43节让扫描具备租约、fencing、快照与更正能力，但同步失败只把 `syncRunId` 写进响应——运行记录（状态、游标、页数与计数、失败码、启动时钉住的映射版本）虽已持久化，却没有任何读取入口，操作员事后无法回答“上次扫描停在哪、为什么停、有没有对账”。本节补齐**只读**追溯，不新增服务、队列、调度或动作工具。

Host 与 Item 各两个 GET：最近运行分页与按 `syncRunId` 单条读取。租户来自可信 Principal，来源来自配置，权限沿用源级 `source.sync`（只有 `entity.read` 会被 403）；跨租户、跨来源、跨对象类型与未知标识一律 404，未知查询参数、越界 `limit`、客户端构造的游标一律 400。运行按 `started_at DESC, id DESC` 排序，`limit` 1–50（默认 20），`after` 为服务端签发的不透明游标；存储层取 `limit + 1` 行以区分“恰好取满”与“还有下一页”，V023 只增加一条 `(tenant_id, source_instance_id, object_type, started_at DESC, id DESC)` 读取索引，不新增表、列或数据。

| 检查 | 实际命令 / 方法 | 最终结果与边界 |
|---|---|---|
| 结构 / 契约 | Docker Python3.12 `.tmp/check-scanrun-final.sh`（静态检查 + pytest contracts + 发布输入 + 实际产物） | **590 passed；232 个结构化文件、6 个只读 Tool**。较第43节新增 3 类 Schema（运行/列表/单条）与样例共 6 个结构化文件、48 项契约测试；用例覆盖信封拒绝未知字段与 live/import 模式、limit 与游标边界、items 上限、状态与完成度不变量、失败码闭集、多行失败摘要拒绝、钉住版本引用形状 |
| 纯领域 | `node .tmp/domain-check.cjs`，Java21 编译全部 modules/tests/domain 并逐个运行 main | **856 项、32 个 main 通过**。新增 `SourceScanRunQuerySmoke` 37 项：无 `source.sync` 与空来源拒绝、对象类型与 limit/游标边界、租户/来源/对象类型隔离、`limit + 1` 分页与游标续读不重复、失败码只从稳定前缀还原、未知文本不回显、钉住版本只在存在时出现、游标保留微秒精度 |
| Java / 两个启动包 | `node .tmp/pipeline-test-env.cjs full`，自有真实 loopback PG/VM，两个 test 与 bootJar，`--rerun-tasks` | **188 平台 + 20 Worker = 208 tests，0 failures/errors/skipped；两个 bootJar 成功**。完整链使用该次构建的 `platform-api-0.1.0-SNAPSHOT.jar`（13:38:39） |
| 真实 PG 追溯 | `PostgresSourceScanRunIT` 2 项（含于 Java 总数） | **通过**。真实 PG 上的倒序/游标分页与租户/来源隔离；成功扫描 `SUCCEEDED`+`snapshotComplete` 且带钉住版本；随后一次真实失败的 Host 同步可被读回为 `FAILED`、0 页 0 抓取、失败码为 `SOURCE_FETCH_FAILED` 且摘要为固定文案，租户 ACTIVE 实体数不变（失败不对账、不删除） |
| HTTP / 权限 | `SourceScanRunHttpIT` 2 项与 `SourceScanRunDeniedIT` 1 项（含于 Java 总数） | **通过**。实际列表/单条读取、`no-store`/`nosniff`、item 与 host 互不可见；401 未认证、403 仅 `entity.read`、400（`limit=0/51/abc`、`after=***`、未知参数、重复参数、非法 UUID、单条读取带分页参数）、404（未知标识、跨类型标识）；存储中出现 `CUSTOM: …` 等未知失败文本时响应不含 `failureCode` 且不回显原文 |
| Rust 默认 / 全 features / fmt | `node .tmp/rust-check.cjs`，workspace locked、`-j 1` | **40/43 通过，0 failed/ignored；fmt 通过**。本轮没有 Rust 产品改动 |
| Web 类型 / 构建 / 回归 | `apps/web-console` 的 `pnpm typecheck`、`pnpm build` 与 Playwright 全量 | **190 passed（新增 11 项），构建通过（80 modules）**。新页面 `来源扫描` 支持 Host/Item、10/20/50、显式读取、服务端游标翻页、按标识查询、空结果与 400/403/404 提示、身份变更清空；客户端拒绝未知枚举、`SUCCEEDED` 缺完成度、`RUNNING` 带完成时间与未知失败码等非法响应 |
| 开发模式完整链 | `node .tmp/pipeline-test-env.cjs browser` → checked-in `check_metrics_stack.mjs --pipeline --runtime` 与 `lib/check_source_scan_runs.mjs` | **19 个 PASS；110 个响应边界检查 0 失败**（`.tmp/metrics-acceptance/request-boundary.json` 记录 `checkedResponses: 110, failures: 0`）。实际浏览器→Java→PG 读取该次真实 Host 同步的运行（含钉住版本），item 追溯单独成立且不混入 host 运行，凭据清除后结果消失 |
| OIDC / Worker 完整链 | `node .tmp/oidc-stack.cjs --history-service` | **10 个 PASS、18 个浏览器响应边界通过**。撤权/故障游标/密钥轮换与既有诊断、委托、Cookie/CSRF 回归 |
| 实际产物 | 最终整链后 Draft202012Validator + FormatChecker | **105 份产物、62 类顶层 Schema 通过**。新增 5 份：真实 HTTP 3（列表/单条/未知失败文本）、实际浏览器 2（列表/单条）；均为自有合成数据，不等于 105 条独立 E2E |
| 静态门禁 | `git diff --check`、当前分支与暂存检查 | **通过**；`main`、无 staged 变更，无本轮依赖升级或生成目录入库 |

中间修正：首次链路检查假设 item 追溯为空，但同一次验收本来就会做 item 同步，因此改为断言“item 运行单独成立、host 运行不出现在 item 追溯里”，并让浏览器断言 item 行存在且 host 行缺席；`.tmp/check-scanrun-final.sh` 最初把失败运行产物按不存在的 schema 校验，实际它是单条读取信封，改为按 `source-scan-run-read` 校验。前端首轮 e2e 与类型检查暴露的非法响应处理（未知枚举、完成度不一致、未知失败码）改为拒绝渲染而不是显示可疑数据。两次失败都是检查脚本/测试自身的问题，修正后完整链与契约链一次通过。

追溯截图已实际查看：`.tmp/metrics-acceptance/source-scan-runs.png` 显示 `acceptance-… · zabbix-1 · host · postgres · 本页 2 条`、两条 `SUCCEEDED` 运行的时间/游标/页数/抓取/采纳/拒绝/完整快照与 `labeled-fixture`、钉住映射版本 `zabbix-host-default` 修订 1 与摘要、禁用的“下一页扫描运行”，以及按标识读取面板。凭据保持掩码。日志保存在忽略的 `.tmp/scanrun-java-full.log`、`scanrun-domain.log`、`scanrun-contracts-final.log`、`scanrun-web-full.log`、`scanrun-web-focused.log`、`scanrun-rust.log`、`scanrun-chain.log` 与 `scanrun-oidc.log`。

保留边界：追溯不是修复——它不重试、不续租、不对账、不清理，也不会把遗留的 `RUNNING` 元数据改成失败或成功；第41节记录的进程终止遗留状态仍然存在。它不返回厂商原始报文或 Raw 载荷，不提供跨租户/跨来源的全局运行检索，运行表本身没有自动清理或总量配额（只有单次读取上限 50）。真实链总会有运行记录，“空追溯”状态只由 mock 的 Web 用例覆盖。真实 Zabbix/模型/IdP/TLS/代理与人工诊断抽样审阅仍未验收；Runtime 仍是同机 `platform-dev` 受限委托，模型为显式 mock。

M2工程估算保持90%，**MVP约89%（±5个百分点），整体约57%**；本节补齐来源扫描链的可观测性，属可追溯性补强而不是退出门槛本身，因此不据此提高阶段估算。M1–M4退出门槛仍未全部通过，MVP100%目标继续。下一步继续来源失败/冲突追溯与只读诊断异常场景评估，并在真实环境可用时先跑连接自检。见 [ADR-038](adr/038-source-scan-run-trace.md)、[契约](../contracts/source-scan-runs.md)、[操作说明](runbooks/source-scan-runs.md)。

## 45. 2026-09-26 Item 扫描来源租约与受围栏对账（追加）

继续当前 `main` 工作区，无新分支、提交、推送或部署。遵循用户“暂未准备，先推进本地实现”：第41节给 Host 扫描加了持久来源租约、单调 fence 与事务内对账，但 Item 扫描一直缺这一层——它会 `retireMissing` 把本来源缺失的指标绑定置为 `INACTIVE`，却既不取租约也不复核所有权。两个并发的 Item 扫描可以交错写入，一个已过期或被接管的旧扫描还能按自己那份更旧的 presence 对账，把新扫描刚观测到的绑定错误下线；这类错误只会在指标静默消失时才被发现。

本节让 Item 扫描复用同一套来源租约（scope 的 `externalType` 为 `item`），不新增表、服务、队列或调度：取到租约才发第一个来源请求，每页开始前续租，目录/绑定写入与缺失对账都经新的 `SourceItemWritePort` 携带租约提交，结束时在 `finally` 中只释放与自己 token 完全一致的租约。PostgreSQL 适配器在同一事务里按 scope 取咨询锁、用数据库时钟校验租约、写目录/绑定或执行对账、结束前复查时间并续租，任何一步失败整笔回滚；租约失败映射为既有的稳定码 `SOURCE_SCAN_BUSY/LOST/DEADLINE/LIMIT`。内存适配器执行真实租约校验但显式标注为非事务性（开发实现）。

| 检查 | 实际命令 / 方法 | 最终结果与边界 |
|---|---|---|
| 结构 / 契约 | Docker Python3.12 `.tmp/check-scanrun-final.sh`（静态检查 + pytest contracts + 发布输入 + 实际产物） | **590 passed；232 个结构化文件、6 个只读 Tool**。本节不新增 Schema：Item 失败响应沿用既有失败形状与封闭失败码集合，实际产物仍为 105 份/62 类 |
| 纯领域 | `node .tmp/domain-check.cjs`，Java21 编译全部 modules/tests/domain 并逐个运行 main | **885 项、33 个 main 通过**。新增 `ItemScanOwnershipSmoke` 29 项：外部持有者使扫描返回 `SOURCE_SCAN_BUSY` 且不写不删；租约在分页中途过期时受围栏写入被拒绝、失败为 `SOURCE_SCAN_LOST` 且不对账；接管后旧扫描既不能对账也不能清掉新持有者；健康扫描只下线真正缺失的项并释放 scope；已过期 token 不能写也不能对账 |
| Java / 两个启动包 | `node .tmp/pipeline-test-env.cjs full`，自有真实 loopback PG/VM，两个 test 与 bootJar，`--rerun-tasks` | **193 平台 + 20 Worker = 213 tests，0 failures/errors/skipped；两个 bootJar 成功**。完整链使用该次构建的 `platform-api-0.1.0-SNAPSHOT.jar`（14:27:10） |
| 真实 PG 所有权 | `PostgresItemScanOwnershipIT` 4 项与既有 `PostgresItemSyncIT`（均含于 Java 总数） | **通过**。外部持有租约时扫描返回 `SOURCE_SCAN_BUSY`、0 页 0 采纳、目录未变、运行记录为 `FAILED` 且原因为固定文案、持有者租约仍有效，释放后再次扫描可完成并只下线缺失项；过期租约可被接管恢复，但被取代的 token 对账抛 `LOST` 且目录前后完全一致；过期 token 的受围栏写入与对账都被拒绝；成功扫描把 scope 交还给下一个 fence |
| HTTP / 权限 | `ZabbixItemSyncIT` 新增 1 项（含于 Java 总数） | **通过**。外部持有租约时 `POST /items/sync` 返回 503，`error=source_unavailable`、`failureCode=SOURCE_SCAN_BUSY`、摘要等于固定文案、`snapshotComplete=false`、0 页 0 采纳、带 `syncRunId`；释放后同一入口返回 200 |
| Rust 默认 / 全 features / fmt | `node .tmp/rust-check.cjs`，workspace locked、`-j 1` | **40/43 通过，0 failed/ignored；fmt 通过**。本轮没有 Rust 产品改动 |
| Web 类型 / 构建 / 回归 | `apps/web-console` 的 `pnpm typecheck`、`pnpm build` 与 Playwright 全量 | **190 passed，构建通过**。本节不改前端；页面/凭据边界回归通过 |
| 开发模式完整链 | `node .tmp/pipeline-test-env.cjs browser` → checked-in `check_metrics_stack.mjs --pipeline --runtime` | **19 个 PASS；110 个响应边界检查 0 失败**。链内真实 Item 同步在租约+受围栏写入下继续完成，来源扫描追溯、Host 流水线、Tool/Runtime/AIInsight 回归不变 |
| OIDC / Worker 完整链 | `node .tmp/oidc-stack.cjs --history-service` | **10 个 PASS、18 个浏览器响应边界通过**。撤权/故障游标/密钥轮换与既有诊断回归 |
| 实际产物 | 最终整链后 Draft202012Validator + FormatChecker | **105 份产物、62 类顶层 Schema 通过**（数量与第44节一致，产物已按新语义重新生成） |
| 静态门禁 | `git diff --check`、当前分支与暂存检查 | **通过**；`main`、无 staged 变更，无本轮依赖升级或生成目录入库 |

同时修正第44节留下的两处实现缺口：V023 读取索引此前既没有登记进 `InventoryWiring` 的迁移列表，也没有登记进 `processResources`，因此**从未真正创建**——本轮在 Item PG 测试报 `Missing inventory migration` 时暴露，两处已补齐；`SOURCE_SCAN_*` 的固定摘要原先写作“host scan”，在 Item 扫描也会返回这些码之后改成作用域中性表述（`SourceScanRunQuerySmoke` 的断言同步更新）。中间还修正了一处测试假设：租约在页内失效时，受围栏写入必须先被拒绝，因此该次扫描的 `accepted` 为 0 而不是 1。

保留边界：所有权解决的是“谁能写”，不是“上游是否一致”。Item 扫描完成态仍是 `offset-scan-attempt`，offset 分页期间的上游漂移不修复；已提交页的目录/绑定保留，只有缺失对账被围栏拒绝；遗留 `RUNNING` 元数据仍不自动修复。Host 与 Item 是彼此独立的 scope（可并发，无全局所有权），Problem/历史采集继续使用 ingestion 自有 checkpoint 租约且没有缺失对账。内存适配器不是事务性的，生产语义以 PostgreSQL 为准。真实 Zabbix/模型/IdP/TLS 与人工诊断抽样审阅仍未验收。

M2工程估算保持90%，**MVP约89%（±5个百分点），整体约57%**；本节是与第41节同类的同步正确性修复，不据此提高阶段估算。M1–M4退出门槛仍未全部通过，MVP100%目标继续。下一步继续来源失败/冲突追溯与只读诊断异常场景评估，并在真实环境可用时先跑连接自检。见 [ADR-039](adr/039-item-scan-ownership.md)、[集成说明](../modules/integration/README.md)、[操作说明](runbooks/source-scan-runs.md)。

## 46. 2026-09-26 Host 扫描的 hostid 水位快照与漂移拒绝（追加）

继续当前 `main` 工作区，无新分支、提交、推送或部署。遵循用户“暂未准备，先推进本地实现”：M2 退出条件要求一条来源“完成分页、游标、完整快照”，而此前 Host 扫描固定标注 `offset-scan-attempt`——按 `hostid` 升序的 limit/offset 分页在上游新增时会让后续页漂移，删除更会让 offset 跳过一行，而漂移之后仍执行缺失对账，就会把只是被跳过的资产错误置为 `INACTIVE`。

本节让默认 Host 来源在第一个分页请求之前先捕获两个边界（当前最高 `hostid` 与当前总行数），在该水位内升序分页；只有“观测行数等于捕获计数”且“确实看到水位行”时才把 `snapshotComplete` 置为 true 并标注 `hostid-watermark-snapshot`，否则以 `SOURCE_SCAN_UNVERIFIED` 失败且不对账。游标携带边界（offset、水位、计数、上一行、已观测数），服务端不从请求推断边界、客户端也不得构造；水位之后新建的 host 不属于本次快照；边界请求失败按 `SOURCE_FETCH_FAILED` 处理，绝不退化成空快照。

| 检查 | 实际命令 / 方法 | 最终结果与边界 |
|---|---|---|
| 结构 / 契约 | Docker Python3.12 `.tmp/check-scanrun-final.sh`（静态检查 + pytest contracts + 发布输入 + 实际产物） | **594 passed；232 个结构化文件、6 个只读 Tool**。失败契约的 `scanConsistency` 由常量放宽为 `offset-scan-attempt`/`hostid-watermark-snapshot` 闭集，失败码集合新增 `SOURCE_SCAN_UNVERIFIED`；新增 4 项契约用例覆盖两种标签、新失败码与非法标签拒绝。不新增 Schema 类型，产物仍为 105 份/62 类 |
| 纯领域 | `node .tmp/domain-check.cjs`，Java21 编译全部 modules/tests/domain 并逐个运行 main | **919 项、34 个 main 通过**。新增 `HostScanBoundarySmoke` 25 项：已验证水位快照才退休、删除导致行数不符时 `SOURCE_SCAN_UNVERIFIED` 且不退休、水位后新增不进快照、分页请求失败不伪装空快照、缺少边界的游标被拒绝；`ZabbixHostPageContractSmoke` 从 15 项扩到 24 项（水位/计数请求形状、跨页游标携带边界、越界新增、位移、空来源、缺失水位行） |
| Java / 两个启动包 | `node .tmp/pipeline-test-env.cjs full`，自有真实 loopback PG/VM，两个 test 与 bootJar，`--rerun-tasks` | **195 平台 + 20 Worker = 215 tests，0 failures/errors/skipped；两个 bootJar 成功**。完整链使用该次构建的 `platform-api-0.1.0-SNAPSHOT.jar`（15:00:03） |
| 真实 PG 边界 | `PostgresHostScanBoundaryIT` 2 项（含于 Java 总数） | **通过**。真实 PG 上已验证水位 walk 完成、标注 `hostid-watermark-snapshot`、退休缺失资产；位移 walk 返回 `SOURCE_SCAN_UNVERIFIED`、`snapshotComplete=false`、运行记录为 `FAILED` 且原因为固定文案、缺失资产保持 ACTIVE，被跳过的行从未落库 |
| HTTP / 协议桩 | `ZabbixJsonRpcConnectorIT` 改为三类请求（水位/计数/分页）并含于 Java 总数；`IdentityAndZabbixHostIT`、`SourceScanRunHttpIT` 断言新标签 | **通过**。真实 HTTP 到本地协议桩：水位用 `output:["hostid"]`+DESC，计数只读一次，分页 ASC offset 0；Host 同步响应标签为 `hostid-watermark-snapshot`，Item 同步仍为 `offset-scan-attempt` |
| Rust 默认 / 全 features / fmt | `node .tmp/rust-check.cjs`，workspace locked、`-j 1` | **40/43 通过，0 failed/ignored；fmt 通过**。本轮没有 Rust 产品改动 |
| Web 类型 / 构建 / 回归 | `apps/web-console` 的 `pnpm typecheck`、`pnpm build` 与 Playwright 全量 | **190 passed，构建通过**。本节不改前端；页面/凭据边界回归通过 |
| 开发模式完整链 | `node .tmp/pipeline-test-env.cjs browser` → checked-in `check_metrics_stack.mjs --pipeline --runtime` | **19 个 PASS；110 个响应边界检查 0 失败**。链内真实 Host 同步走水位 walk 完成并继续对账，来源扫描追溯、Item 同步、Tool/Runtime/AIInsight 回归不变 |
| OIDC / Worker 完整链 | `node .tmp/oidc-stack.cjs --history-service` | **10 个 PASS、18 个浏览器响应边界通过** |
| 实际产物 | 最终整链后 Draft202012Validator + FormatChecker | **105 份产物、62 类顶层 Schema 通过**（数量不变，产物按新语义重新生成） |
| 静态门禁 | `git diff --check`、当前分支与暂存检查 | **通过**；`main`、无 staged 变更，无本轮依赖升级或生成目录入库 |

中间修正：walk 结束却没有快照证明，原先落在 `PAGE_NOT_ADVANCED`（“游标没有前进”），语义不符——现在改为 `SOURCE_SCAN_UNVERIFIED`，而游标真的重复时仍是 `PAGE_NOT_ADVANCED`；`ZabbixHostMappingSmoke` 的“stalled cursor”用例随之改名并断言不对账。边界 smoke 的第一版断言也写错了：位移 walk 会把**实际读到的行**全部落库（3 行）而只是拒绝对账，断言已改为“已读页保留 + 被跳过的行从未落库”。`ZabbixJsonRpcConnectorIT` 的本地协议桩原来只应答旧分页请求，已扩成三类请求并断言计数只读一次。

保留边界：水位快照假定 `hostid` 单调递增（Zabbix 由服务端分配主机 ID），它是对本次 walk 的边界证明，不是数据库级一致快照，也不代表厂商实例已验收——真实 Zabbix 的 `hostid` 分配、`countOutput` 与排序行为仍需真实环境验证。Item 采集与 Problem/历史采集本轮不变（前者仍是 `offset-scan-attempt`，后者用 ingestion 自有 checkpoint 租约）。未实现边界的自定义连接器仍可能完成并触发对账（生产 Host 连接器都已实现边界）；“只有已验证快照才允许对账”的强规则与把一致性标签持久化到运行记录（供追溯页显示）都留作后续工作。运行记录仍不保存方法标签。

M2工程估算90%→93%（补齐本地可实现的“完整快照”边界与漂移拒绝），**MVP约90%（±5个百分点），整体约57%（四舍五入不变）**。M1–M4退出门槛仍未全部通过，MVP100%目标继续；下一步继续来源失败/冲突追溯、把一致性标签写入运行记录，并在真实环境可用时先跑连接自检。见 [ADR-040](adr/040-host-watermark-snapshot.md)、[Host 扫描契约](../contracts/host-scan.md)、[操作说明](runbooks/source-scan-runs.md)。

## 47. 2026-09-26 扫描边界标签持久化与追溯展示（追加）

继续当前 `main` 工作区，无新分支、提交、推送或部署。第46节让 Host 扫描按 hostid 水位完成“已验证快照”，但结论只出现在同步响应里：运行记录不保存 `scanConsistency`，追溯页只能看到状态与 `snapshotComplete`，真实来源验收后无法回答“这条已完成的运行到底是水位快照还是 offset 尝试”，而两者的对账含义完全不同。本节把该标签持久化并暴露到追溯链路。

`integration.source_sync_run` 新增 `scan_consistency`（V024，默认 `offset-scan-attempt`，闭集约束两种标签），由 `succeed`/`fail` 在结束时写入当时的边界标签；追溯接口与页面把它作为运行的一部分返回并展示。旧行保留默认值——它们确实是在水位 walk 出现之前写入的 offset 尝试。V024 同时登记进迁移列表与 `processResources`（第45节曾因漏登记导致 V023 从未创建）。标签不参与授权、不能被请求覆盖，`RUNNING` 期间保持默认值。

| 检查 | 实际命令 / 方法 | 最终结果与边界 |
|---|---|---|
| 结构 / 契约 | Docker Python3.12 `.tmp/check-scanrun-final.sh`（静态检查 + pytest contracts + 发布输入 + 实际产物） | **599 passed；232 个结构化文件、6 个只读 Tool**。`source-scan-run` 增加必填 `scanConsistency` 闭集字段，样例覆盖两种标签与历史 offset 行；新增 5 项契约用例（两种标签合法、未知标签拒绝、字段必填、多行文本拒绝） |
| 纯领域 | `node .tmp/domain-check.cjs`，Java21 编译全部 modules/tests/domain 并逐个运行 main | **921 项、34 个 main 通过**。追溯 smoke 增至 39 项，新增“已完成 walk 保留边界标签”“失败 walk 保留自己的方法标签”两项 |
| Java / 两个启动包 | `node .tmp/pipeline-test-env.cjs full`，自有真实 loopback PG/VM，两个 test 与 bootJar，`--rerun-tasks` | **195 平台 + 20 Worker = 215 tests，0 failures/errors/skipped；两个 bootJar 成功**。完整链使用该次构建的 `platform-api-0.1.0-SNAPSHOT.jar`（15:19:28） |
| 真实 PG 标签 | `PostgresSourceScanRunIT`（标签往返）与 `PostgresHostScanBoundaryIT`（已验证/未验证 walk 的落库标签），含于 Java 总数 | **通过**。直接 `succeed`/`fail` 写入的两种标签都能按租户读回；已验证水位 walk 的落库标签是 `hostid-watermark-snapshot`；`SOURCE_SCAN_UNVERIFIED` 的失败运行同样保留该标签且状态为 `FAILED` |
| HTTP / 追溯读取 | `SourceScanRunHttpIT`（含于 Java 总数） | **通过**。真实 HTTP 同步后的运行，按 `syncRunId` 读取返回 `scanConsistency: hostid-watermark-snapshot`；未知失败文本仍不回显 |
| Web 类型 / 构建 / 回归 | `apps/web-console` 的 `pnpm typecheck`、`pnpm build` 与 Playwright 全量 | **192 passed（新增 2 项拒绝用例），构建通过（80 modules）**。页面把标签渲染为“边界 已验证 hostid 水位快照 / offset 尝试（无快照证明）”；客户端拒绝未知标签与缺失标签的响应 |
| 开发模式完整链 | `node .tmp/pipeline-test-env.cjs browser` → checked-in `check_metrics_stack.mjs --pipeline --runtime` 与 `lib/check_source_scan_runs.mjs` | **19 个 PASS；110 个响应边界检查 0 失败**。链内在真实浏览器→Java→PG 上断言落库标签为水位快照、Item 运行仍为 offset 标签，并断言页面显示对应文案 |
| OIDC / Worker 完整链 | `node .tmp/oidc-stack.cjs --history-service` | **10 个 PASS、18 个浏览器响应边界通过** |
| 实际产物 | 最终整链后 Draft202012Validator + FormatChecker | **105 份产物、62 类顶层 Schema 通过**（数量不变，运行产物带上了新字段） |
| 静态门禁 | `git diff --check`、当前分支与暂存检查 | **通过**；`main`、无 staged 变更，无本轮依赖升级或生成目录入库 |

同时修正一处真实缺口：前端 `FAILURE_SUMMARY` 映射仍停留在第45节之前的 host-only 文案（`SOURCE_SCAN_BUSY/LOST/DEADLINE` 的三条摘要与 Java 端不一致），且缺少第46节新增的 `SOURCE_SCAN_UNVERIFIED`。由于客户端只渲染与固定摘要完全一致的失败码，这类运行在追溯页会被整体拒绝——即“有记录但读不出来”。现在映射与 Java 枚举逐条对齐，并新增 e2e 拒绝用例（未知边界、缺失边界）与渲染断言把它钉住。

保留边界：标签描述方法而不是结果，`hostid-watermark-snapshot` 的失败运行仍可能是“没有快照证明”的失败，只有 `snapshotComplete: true` 才代表边界被验证；标签不参与授权、不能被请求覆盖。Item 采集仍写 `offset-scan-attempt`（尚未实现水位边界），Problem/历史采集不写运行记录。运行记录仍没有自动清理或总量配额，也没有把标签用于自动决策（“只允许水位快照触发对账”的强规则尚未实现）。真实 Zabbix/模型/IdP/TLS 与人工诊断抽样审阅仍未验收。

M2工程估算保持93%，**MVP约90%（±5个百分点），整体约57%**；本节只是把第46节的保证变成可追溯的产品事实，不据此提高阶段估算。M1–M4退出门槛仍未全部通过，MVP100%目标继续。下一步给 Item/Problem 补水位边界、继续来源失败/冲突追溯与只读诊断异常场景评估，并在真实环境可用时先跑连接自检。见 [ADR-041](adr/041-persisted-scan-consistency.md)、[扫描运行契约](../contracts/source-scan-runs.md)、[操作说明](runbooks/source-scan-runs.md)。

## 48. 2026-09-26 Item 采集的 itemid 水位快照（追加）

继续当前 `main` 工作区，无新分支、提交、推送或部署。第45节让 Item 扫描取得来源 scope 租约，解决了“谁能写”；但那次 walk 仍是 `offset-scan-attempt`——按 `itemid` 升序的 limit/offset 分页在上游删除一行后会让 offset 跳过另一行，而扫描仍然完成并执行 `retireMissing`，把只是被跳过的指标绑定错误置为 `INACTIVE`。这与第46节修掉的 Host 缺口是同一类问题，只是后果落在指标绑定上。

本节把同一套水位边界用于 Item 采集：默认 Item 来源在第一个分页请求前读取当前最高 `itemid` 与总行数，在该水位内升序分页；只有观测行数等于捕获计数且确实看到水位行时才 `snapshotComplete=true` 并标注 `itemid-watermark-snapshot`，否则以 `SOURCE_SCAN_UNVERIFIED` 失败且不对账。水位之后新增的 item 不属于本次快照；边界请求失败按 `SOURCE_FETCH_FAILED` 处理。水位算法抽成两个连接器共享的 `JsonRpcWatermarkBounds`（捕获、游标编解码、验证判定），V025 把运行记录的标签闭集扩展为三种且约束名不变。

| 检查 | 实际命令 / 方法 | 最终结果与边界 |
|---|---|---|
| 结构 / 契约 | Docker Python3.12 `.tmp/check-scanrun-final.sh`（静态检查 + pytest contracts + 发布输入 + 实际产物） | **602 passed；232 个结构化文件、6 个只读 Tool**。`source-scan-run` 与失败契约的标签闭集扩为三种（含 `itemid-watermark-snapshot`），新增 3 项契约用例（item 运行保留自身边界、非法标签拒绝、失败 walk 说明其边界）。不新增 Schema 类型，产物仍为 105 份/62 类 |
| 纯领域 | `node .tmp/domain-check.cjs`，Java21 编译全部 modules/tests/domain 并逐个运行 main | **945 项、35 个 main 通过**。新增 `ItemScanBoundarySmoke` 24 项：已验证 itemid walk 才退休、位移 walk 报 `SOURCE_SCAN_UNVERIFIED` 且不退休、水位后新增不进快照、分页失败不伪装空快照、缺少边界的游标被拒绝、失败运行保留方法标签；`ZabbixItemMappingSmoke` 的完成态断言改为 `itemid-watermark-snapshot` |
| Java / 两个启动包 | `node .tmp/pipeline-test-env.cjs full`，自有真实 loopback PG/VM，两个 test 与 bootJar，`--rerun-tasks` | **197 平台 + 20 Worker = 217 tests，0 failures/errors/skipped；两个 bootJar 成功**。完整链使用该次构建的 `platform-api-0.1.0-SNAPSHOT.jar`（15:39:42） |
| 真实 PG 边界 | `PostgresItemScanBoundaryIT` 2 项（含于 Java 总数） | **通过**。真实 PG 上 fixture item walk 完成、标注 `itemid-watermark-snapshot`、只退休缺失绑定（1 采纳/1 拒绝/1 退休），落库标签一致；位移 walk 返回 `SOURCE_SCAN_UNVERIFIED`、`snapshotComplete=false`、运行 `FAILED` 且原因为固定文案、标签保留，缺失绑定保持 ACTIVE，被跳过的绑定从未落库 |
| HTTP / 标签 | `ZabbixItemSyncIT` 2 项（含于 Java 总数） | **通过**。fixture item 同步响应标签为 `itemid-watermark-snapshot`；来源被占用时（未读任何页）仍为默认 `offset-scan-attempt`，即“没读过页就不对方法作声明” |
| Web 类型 / 构建 / 回归 | `apps/web-console` 的 `pnpm typecheck`、`pnpm build` 与 Playwright 全量 | **192 passed，构建通过**。客户端接受三种边界标签并渲染“已验证 itemid 水位快照”；item 用例断言该文案，未知标签仍被拒绝 |
| 开发模式完整链 | `node .tmp/pipeline-test-env.cjs browser` → checked-in `check_metrics_stack.mjs --pipeline --runtime` 与 `lib/check_source_scan_runs.mjs` | **19 个 PASS；110 个响应边界检查 0 失败**。链内真实 Item 同步走 itemid 水位 walk 完成，追溯断言 item 运行携带自身边界标签、Host 运行携带 hostid 标签 |
| OIDC / Worker 完整链 | `node .tmp/oidc-stack.cjs --history-service` | **10 个 PASS、18 个浏览器响应边界通过** |
| 实际产物 | 最终整链后 Draft202012Validator + FormatChecker | **105 份产物、62 类顶层 Schema 通过** |
| 静态门禁 | `git diff --check`、当前分支与暂存检查 | **通过**；`main`、无 staged 变更，无本轮依赖升级或生成目录入库 |

中间修正：契约链两次失败都来自“每个样例必须有同名 schema”的仓库约定——新增的 `source-scan-run-item` 变体样例没有同名 schema。最终不新增变体样例，改由契约用例从基础样例派生 item 运行（去掉 host 专属的 `pipelineVersion`）并覆盖三种标签，避免为样例发明一个只做窄化的 schema。`ZabbixItemSyncIT` 的占用用例也纠正过一次：未读任何页的失败扫描不应声称水位方法。

保留边界：水位快照假定 `itemid` 单调递增（Zabbix 由服务端分配），它是对本次 walk 的边界证明，不是数据库级一致快照，也不代表厂商实例已验收。Item 完成态仍是 offset 分页的**边界**（Zabbix 没有 keyset 过滤），因此上游删除只能被“拒绝”而不能被“修复”。Problem/历史采集继续使用 ingestion 自有 checkpoint 租约，本轮不变；“只允许已验证快照触发对账”的强规则仍未实现（未实现边界的自定义连接器仍可完成对账）。运行记录没有自动清理或总量配额。真实 Zabbix/模型/IdP/TLS 与人工诊断抽样审阅仍未验收。

M2工程估算保持93%，M3保持85%，**MVP约90%（±5个百分点），整体约57%**；本节是与第41/45节同类的同步正确性修复，不据此提高阶段估算。M1–M4退出门槛仍未全部通过，MVP100%目标继续。下一步继续来源失败/冲突追溯与只读诊断异常场景评估，把标签用于“只有已验证快照才允许对账”的强规则，并在真实环境可用时先跑连接自检。见 [ADR-042](adr/042-item-watermark-snapshot.md)、[集成说明](../modules/integration/README.md)、[操作说明](runbooks/source-scan-runs.md)。

## 49. 2026-09-26 只有已验证快照才允许对账（追加）

继续当前 `main` 工作区，无新分支、提交、推送或部署。第40/42节让默认 Host/Item 连接器按水位完成“已验证快照”，但这条规则此前只存在于连接器的自觉里：两个采集用例仍只看 `Page.snapshotComplete()`，因此一个未实现边界的连接器（自定义、未来新增，或声明完成却只给 `offset-scan-attempt` 的适配器）仍可触发 `retireMissing`，把只是没被观测到的对象错误下线。这与第41/45节修掉的“谁能写”不同，是“凭什么能退休”。

本节把规则提升为系统级不变量：`SyncScan.verified(label)` 只承认 `hostid-watermark-snapshot` 与 `itemid-watermark-snapshot`；用例在 `snapshotComplete` 为真时仍复核该标签，不满足就以 `SOURCE_SCAN_UNVERIFIED` 失败、退休数为 0，已提交页与 Raw 保留。默认连接器不受影响（本来就给出水位标签），而任何“声明完成但无法证明边界”的适配器从此无法退休对象。同时先做了缺口审计：Problem/历史采集路径没有任何 `retire`/`reconcile`（只有幂等入库与显式缺口），因此不存在同类缺口，本轮不做“Problem 水位边界”。

| 检查 | 实际命令 / 方法 | 最终结果与边界 |
|---|---|---|
| 结构 / 契约 | Docker Python3.12 `.tmp/check-scanrun-final.sh`（静态检查 + pytest contracts + 发布输入 + 实际产物） | **602 passed；232 个结构化文件、6 个只读 Tool**。本节是行为约束，不新增/修改 Schema：失败码与标签闭集沿用第46–48节，产物仍为 105 份/62 类；[Host 扫描契约](../contracts/host-scan.md) 明确写出“未实现边界的连接器不能通过声明完成来退休任何对象” |
| 纯领域 | `node .tmp/domain-check.cjs`，Java21 编译全部 modules/tests/domain 并逐个运行 main | **963 项、35 个 main 通过**。`HostScanBoundarySmoke` 增至 33 项、`ItemScanBoundarySmoke` 增至 32 项：新增“有界空来源是已验证快照且可以退休”“未实现边界却声明完成的连接器报 `SOURCE_SCAN_UNVERIFIED` 且退休数为 0”；`ZabbixHostMappingSmoke` 的两个旧断言改为新语义（未验证 walk 不再退休，位移跳过的 host 保持 ACTIVE），并把该 smoke 的硬编码计数改成真实计数（此前它会误报 46 而实际执行 48 项） |
| Java / 两个启动包 | `node .tmp/pipeline-test-env.cjs full`，自有真实 loopback PG/VM，两个 test 与 bootJar，`--rerun-tasks` | **199 平台 + 20 Worker = 219 tests，0 failures/errors/skipped；两个 bootJar 成功**。完整链使用该次构建的 `platform-api-0.1.0-SNAPSHOT.jar`（16:06:31） |
| 真实 PG 强制规则 | `PostgresHostScanBoundaryIT` 与 `PostgresItemScanBoundaryIT` 各新增 1 项（含于 Java 总数） | **通过**。真实 PG 上“声明完成但无边界”的连接器返回 `SOURCE_SCAN_UNVERIFIED`、退休数为 0、缺失对象保持 ACTIVE、运行记录为 `FAILED` 且标签保持默认 `offset-scan-attempt`（没读过页就不声明方法） |
| HTTP / 协议 | 既有 `ZabbixJsonRpcConnectorIT`、`ZabbixItemSyncIT`、`SourceScanRunHttpIT` 全部回归（含于 Java 总数） | **通过**。生产 Host/Item 连接器仍走水位边界，同步响应标签与落库标签不变；本节没有改变任何 HTTP 形状 |
| Rust 默认 / 全 features / fmt | `node .tmp/rust-check.cjs`，workspace locked、`-j 1` | **40/43 通过，0 failed/ignored；fmt 通过**。本轮没有 Rust 产品改动 |
| Web 类型 / 构建 / 回归 | `apps/web-console` 的 `pnpm typecheck`、`pnpm build` 与 Playwright 全量 | **192 passed，构建通过**。本节不改前端 |
| 开发模式完整链 | `node .tmp/pipeline-test-env.cjs browser` → checked-in `check_metrics_stack.mjs --pipeline --runtime` | **19 个 PASS；110 个响应边界检查 0 失败**。默认连接器都是水位边界，因此强规则不改变正常路径，链内 Host/Item 同步、追溯标签与既有回归全部保持 |
| OIDC / Worker 完整链 | `node .tmp/oidc-stack.cjs --history-service` | **10 个 PASS、18 个浏览器响应边界通过** |
| 实际产物 | 最终整链后 Draft202012Validator + FormatChecker | **105 份产物、62 类顶层 Schema 通过** |
| 静态门禁 | `git diff --check`、当前分支与暂存检查 | **通过**；`main`、无 staged 变更，无本轮依赖升级或生成目录入库 |

保留边界：这条规则不能修复上游漂移，只能拒绝在不完整视图上做对账；它也不改变 offset 分页的语义（Zabbix 没有 keyset 过滤）。Problem/历史采集没有缺失对账，因此不适用，也不做“Problem 水位边界”。运行记录没有自动清理或总量配额；真实 Zabbix 行为、厂商实例验收与人工诊断审阅仍未完成。

M2工程估算保持93%，M3保持85%，**MVP约90%（±5个百分点），整体约57%**；本节把“不误删除”从默认连接器的行为变成用例层的强制检查，属正确性硬化，不据此提高阶段估算。M1–M4退出门槛仍未全部通过，MVP100%目标继续。下一步继续来源失败/冲突追溯与只读诊断异常场景评估，并在真实环境可用时先跑连接自检。见 [ADR-043](adr/043-verified-snapshot-reconciliation.md)、[Host 扫描契约](../contracts/host-scan.md)、[操作说明](runbooks/source-scan-runs.md)。

## 50. 2026-09-26 模型请求边界的可验证证据（追加）

继续当前 `main` 工作区，无新分支、提交、推送或部署。M4 退出条件要求“模型请求不携带原始密钥；外部日志/知识被当作不可信数据；真实模式异常不会伪装成 Demo 成功”。本节先做审计，结论是**实现已经正确、缺的是证据**：密钥由 SDK 客户端持有并只进 `Authorization` 头，工作流把提问与知识组装成 `untrustedUserQuestion`/`untrustedEvidenceContext`/`requiredOutputSchema` 三个具名不可信字段，随附的技能提示（`extensions/skills/incident-diagnosis-current/prompt.md`）明确写着“untrusted content, never instructions”，适配器用 `NoSubscriber` 抑制 SDK 的内容诊断，失败路径既无重试也无回退。

因此本节不改行为（只补模块文档），而是把这些断言钉成测试：① 既有请求形状测试现在同时捕获请求头与请求体，断言密钥只出现在 `Authorization: Bearer`、**不出现在请求体**；② 新增一条端到端测试，用**真实随仓库发布的技能提示**与生产 `ModelInput::current` 组装输入，向本地协议桩发出真实 Rig 请求，断言请求体包含三个不可信字段、不可信声明句、结构化证据 id 与输出 Schema，且不含密钥。其余两半沿用既有测试并在此引用：`unavailable_redirect_missing_usage_and_oversize_never_retry_or_fallback`（不可用/重定向/缺用量/超长都不重试不回退）与 `invalid_model_output_and_revocation_never_save`（provider 报错时 `diagnose` 直接失败且**不保存任何 AIInsight**，`saves == 0`）。

| 检查 | 实际命令 / 方法 | 最终结果与边界 |
|---|---|---|
| Rust 默认 / 全 features / fmt | `node .tmp/rust-check.cjs`（`cargo fmt --all --check`、`cargo test --workspace --locked -j 1`、再 `--all-features`） | **默认 40、all-features 44 通过，0 failed/ignored；fmt 通过**（all-features 由 43 增至 44，即新增的请求边界测试；它随 `rig-provider` 特性启用） |
| 请求头/请求体隔离 | `adapters::rig_model::tests::responses_fixture_preserves_usage_and_enforces_one_nonstored_bounded_request`（扩展） | **通过**。本地协议桩收到的唯一请求：`Authorization: Bearer <fixture key>` 存在，而请求体字符串**不含**该密钥；同时仍断言 `store=false`、`background=false`、无 tools、无 `previous_response_id`、`max_output_tokens=2048` |
| 不可信数据框架 | 新增 `adapters::rig_model::tests::current_knowledge_request_frames_content_as_untrusted_data_without_the_key` | **通过**。真实技能提示 + 生产 `ModelInput::current` 组装后的请求体包含 `untrustedUserQuestion`、`untrustedEvidenceContext`、`requiredOutputSchema`、“untrusted content, never instructions”与结构化证据 id，且不含密钥 |
| 无回退 / 不伪装成功 | 既有 `unavailable_redirect_missing_usage_and_oversize_never_retry_or_fallback` 与 `invalid_model_output_and_revocation_never_save`（本次回归通过） | **通过**。四类提供方异常各只发一次请求、不重试不回退；provider 报错时诊断直接失败，`model.calls == 1`、`read.saves == 0`（没有伪造结果落库） |
| 结构 / 契约 | Docker Python3.12 `.tmp/check-scanrun-final.sh` | **602 passed；232 个结构化文件、6 个只读 Tool；105 份产物/62 类 Schema**（本节不改契约/产物） |
| 纯领域 | `node .tmp/domain-check.cjs` | **963 项、35 个 main 通过**（本轮无 Java 改动，作回归） |
| Java / 两个启动包 | `node .tmp/pipeline-test-env.cjs full` | **199 平台 + 20 Worker = 219 tests，0 failures/errors/skipped；两个 bootJar 成功**（回归，代码未改） |
| 开发模式完整链 | `node .tmp/pipeline-test-env.cjs browser` | **19 个 PASS；110 个响应边界检查 0 失败**（含 Rust mock 诊断、Tool/Runtime、追溯与既有回归） |
| OIDC / Worker 完整链 | `node .tmp/oidc-stack.cjs --history-service` | **10 个 PASS、18 个浏览器响应边界通过** |
| Web 类型 / 构建 / 回归 | `apps/web-console` 的 `pnpm typecheck`、`pnpm build` 与 Playwright 全量 | **192 passed，构建通过**（本节不改前端） |
| 静态门禁 | `git diff --check`、当前分支与暂存检查 | **通过**；`main`、无 staged 变更，无本轮依赖升级或生成目录入库 |

保留边界：这是**本地协议桩**验证——真实请求发往 `127.0.0.1` 上的桩服务，没有调用任何真实提供方、没有使用真实密钥，也没有做计费或内容留存验收；“模型请求不携带原始密钥”因此证明的是适配器实际发出的请求形状，而不是厂商侧行为。真实模型、账单对账、真实 Zabbix/IdP/TLS 与人工诊断抽样审阅仍未完成。Rust 侧仍未提供生产 RunStore/取消/恢复，也没有把提示或知识写入普通日志的路径（这是设计约束，本节只做了内容诊断抑制的既有断言）。

M2保持93%、M3保持85%、M4保持80%，**MVP约90%（±5个百分点），整体约57%**；本节只把既有实现钉成可复核证据，不据此提高阶段估算。M1–M4退出门槛仍未全部通过，MVP100%目标继续。下一步继续来源失败/冲突追溯、只读诊断异常场景评估与运行记录治理，并在真实环境可用时先跑连接自检。见 [Rig 适配器](../apps/agent-runtime/src/adapters/rig_model.rs)、[模型费用准入](adr/033-model-spend-admission.md)、[当前诊断与 AIInsight](adr/023-current-diagnosis-insight.md)、[工具证据边界](adr/022-current-knowledge-tool-evidence.md)。

## 51. 2026-09-26 只读的来源连接自检（追加）

继续当前 `main` 工作区，无新分支、提交、推送或部署。M2 退出条件要求“一条**已声明支持版本**的来源”完成分页与完整快照，但 `Connector.probe` 虽然早已实现（fixture 显式标注、JSON-RPC 调 `apiinfo.version`），却没有任何入口调用它：真实来源到位后第一步“连不连得上、自报什么版本”无处执行，也没有回执可引用。本节补齐这一步。

POST `/api/v1/integrations/zabbix/connection-checks` 执行一次有界 probe 并写入回执，GET 同路径读取该来源最近回执（1–50，默认 20，倒序）。权限沿用源级 `source.sync`；租户来自可信 Principal、来源来自配置；自检不启动扫描、不取租约、不写库存、不调用模型，失败也不回退 fixture。回执记录配置模式、稳定 `statusCode`、来源自报版本（最多 32 个可打印字符）、actor 与时间，每个 tenant/source 保留最近 100 份（写新回执时清理更旧的），读取单页上限 50。不可达的回执必须 `reportedVersion: null`，连接器抛异常时回执是 `unreachable`，绝不变成成功。

| 检查 | 实际命令 / 方法 | 最终结果与边界 |
|---|---|---|
| 结构 / 契约 | Docker Python3.12 `.tmp/check-connection-final.sh`（静态检查 + pytest contracts + 发布输入 + 实际产物） | **628 passed；238 个结构化文件、6 个只读 Tool**。新增 3 类 Schema（回执/信封/列表）与样例，用例覆盖未知字段拒绝、`connection-check` 之外的 dataMode 拒绝、不可达却带版本拒绝、状态码换行/超长拒绝、列表上限 |
| 纯领域 | `node .tmp/domain-check.cjs`，Java21 编译全部 modules/tests/domain 并逐个运行 main | **986 项、36 个 main 通过**。新增 `SourceConnectionCheckSmoke` 23 项：无 `source.sync`/空来源拒绝、fixture 回执保持标注且不声明版本、自报版本按原样保存、不可达不带版本、连接器抛异常仍失败关闭、留存上限 100、倒序与租户/来源隔离、limit 边界 |
| Java / 两个启动包 | `node .tmp/pipeline-test-env.cjs full`，自有真实 loopback PG/VM，两个 test 与 bootJar，`--rerun-tasks` | **204 平台 + 20 Worker = 224 tests，0 failures/errors/skipped；两个 bootJar 成功**。完整链使用该次构建的 `platform-api-0.1.0-SNAPSHOT.jar`（16:59:48） |
| 真实 PG / HTTP | `PostgresSourceConnectionCheckIT`、`SourceConnectionCheckHttpIT`、`SourceConnectionCheckDeniedIT`（含于 Java 总数） | **通过**。PG 上回执往返、倒序、limit 与租户/来源隔离、留存上限（写 102 份后 `kept == 100` 且最旧两份消失）；真实 HTTP 上 fixture 自检返回 `reachable: true` + `labeled-fixture` + `reportedVersion: null` + `no-store`/`nosniff`，列表读回同一 `checkId`，`?limit=0/51/abc`、未知参数与重复参数一律 400，POST 带任何查询参数 400，未认证 401，只有 `entity.read` 403 |
| Web 类型 / 构建 / 回归 | `apps/web-console` 的 `pnpm typecheck`、`pnpm build` 与 Playwright 全量 | **210 passed（新增 18 项），构建通过**。面板只读，显示 fixture 与“无版本声明”，客户端拒绝未知字段、未知 dataMode、不可达却带版本、未知 `statusCode` 等非法响应，身份切换清空回执与列表 |
| 开发模式完整链 | `node .tmp/pipeline-test-env.cjs browser` → checked-in `check_metrics_stack.mjs --pipeline --runtime` 与 `lib/check_source_connection.mjs` | **20 个 PASS；112 个响应边界检查 0 失败**。链内真实浏览器→Java→PG：点击自检得到 labeled-fixture 回执、列表读回、凭据清除后回执消失；既有 19 条链（Host/Item 水位、追溯、Tool/Runtime、AIInsight、留存、OIDC 回归）继续通过 |
| OIDC / Worker 完整链 | `node .tmp/oidc-stack.cjs --history-service` | **10 个 PASS、18 个浏览器响应边界通过** |
| 开发模式完整链 | `node .tmp/pipeline-test-env.cjs browser` → `check_metrics_stack.mjs --pipeline --runtime` | **20 个 PASS，退出码 0**。链内真实浏览器→Java→PG/VM 的指标页仍走 GAUGE 原始视图（`host.cpu.usage.user`，映射 `kind: gauge`），计数器变化率由 HTTP 与浏览器 fixture 覆盖；Host/Item 水位、追溯、Tool/Runtime、AIInsight、留存、绑定更正、扫描追溯与连接自检等既有链路继续通过 |
| OIDC / Worker 完整链 | `node .tmp/oidc-stack.cjs --history-service` | **10 个 PASS、18 个浏览器响应边界通过**；无提供方回退（本轮复跑 `.tmp/counter-web-oidc.log`） |
| Rust 默认 / 全 features / fmt | `node .tmp/rust-check.cjs` | **40/44 通过，0 failed/ignored；fmt 通过**（本轮无 Rust 改动） |
| 实际产物 | 最终整链后 Draft202012Validator + FormatChecker | **109 份产物、65 类顶层 Schema 通过**。新增 4 份：真实 HTTP 2（回执/列表）+ 实际浏览器 2；均为自有合成数据 |
| 静态门禁 | `git diff --check`、当前分支与暂存检查 | **通过**；`main`、无 staged 变更，无本轮依赖升级或生成目录入库 |

中间修正：新增契约用例第一次运行就抓到一个真实缺陷——`statusCode`/`reportedVersion` 的 `^[ -~]{1,64}$` 在 Python 正则里允许结尾换行，因此 `"unreachable\n"` 会通过校验。已按仓库惯例改成 `^[ -~]{1,64}(?![\\s\\S])`（版本字段同理）并重跑，628 项全绿。另把 POST 端点收紧为不接受任何查询参数（GET 只接受 `limit`），并补上 `entity.read` 不足以自检的 403 用例。

保留边界：`reportedVersion` 是**来源自报**的版本，用于对照“已声明支持版本”，不是平台验证过的支持结论；fixture 回执永远带 `labeled-fixture`，不证明真实厂商可用。自检没有后台调度、重试或跨来源汇总，也不替代真实 `host.get` 分页、完整快照与人工诊断审阅。真实 Zabbix/模型/IdP/TLS 与人工抽样审阅仍未验收；运行记录与回执表都没有全平台容量治理（回执按 tenant/source 保留 100 份）。真实环境到位后的第一步见 [runbook](runbooks/source-connection-check.md)。

M2工程估算保持93%，M3保持85%，M4保持80%，**MVP约90%（±5个百分点），整体约57%**；本节补齐“已声明支持版本”的第一步骤与回执，但真实来源验收仍未发生，不据此提高阶段估算。M1–M4退出门槛仍未全部通过，MVP100%目标继续。下一步继续来源失败/冲突追溯、只读诊断异常场景评估与运行记录治理。见 [ADR-045](adr/045-source-connection-check.md)、[契约](../contracts/source-connection-checks.md)、[操作说明](runbooks/source-connection-check.md)。

## 52. 2026-09-26 计数器变化率、reset 标记与 Web 变化率视图（追加）

M3 退出条件要求“counter reset 等有用例”，而 [MVP-EXIT-AUDIT.md](MVP-EXIT-AUDIT.md) 逐条核对时指出全仓只有设计文档提到它：`MetricType` 只有 GAUGE/SUM/HISTOGRAM，查询返回原始点，**没有任何 reset 策略、变化率推导或测试**。本节补齐这唯一的本地缺失项，并把“计数器只能看原始累计值”的页面改成显式的变化率视图。

规则固定为 `reset-counts-from-zero`：只有 SUM（计数器）查询得到派生视图；每个区间按秒计算变化率，出现下降即视为计数器重启，该区间以当前值从零起算并带 `counterReset = true`；非正区间与负值输入直接跳过而不是编造数据；原始点、单位、来源、时间窗口与状态一律不变。契约把 `derivation` 收紧为闭集（`kind = counter-rate`、`resetPolicy = reset-counts-from-zero`），并要求带派生视图的每个序列都必须给出 `counterRates`，`rate` 只能是非负十进制、`counterReset` 必须是布尔。Web 端默认画变化率曲线并在图上标出 reset 区间，可切换回原始累计值；没有 `derivation` 却带 rate、或带 `derivation` 却缺 rate 的响应一律拒绝。

| 检查 | 实际命令 / 方法 | 最终结果与边界 |
|---|---|---|
| 结构 / 契约 | Docker Python3.12 `.tmp/check-counter-final.sh`（沿用连接自检整链 + pytest contracts + 实际产物） | **642 passed；238 个结构化文件、6 个只读 Tool**。新增 `tests/contracts/test_metric_counter_rates.py` 14 项：原始页无派生仍合法、派生页必须带 reset 策略、`kind`/`resetPolicy` 闭集、缺 `counterRates` 拒绝、负值/科学计数/空串/前后空格 rate 拒绝、非布尔 reset 拒绝、缺时间拒绝 |
| 纯领域 | `node .tmp/domain-check.cjs` | **1008 项、37 个 main 通过**。新增 `CounterRateSmoke` 22 项：单调计数器无 reset、下降区间从零重算且只标该区间、重复时间点不产生区间、负值输入跳过不编造、SUM 查询返回 `derivation = counter-rate` 且原始点保留、GAUGE 页 `counterRates` 为空 |
| Java / 两个启动包 | `node .tmp/pipeline-test-env.cjs full`（自有 loopback PG/VM，`--rerun-tasks`） | **206 平台 + 20 Worker = 226 tests，0 failures/errors/skipped；两个 bootJar 成功**（本轮复跑 `.tmp/counter-web-java-full.log`：BUILD SUCCESSFUL in 1m 38s，bootJar 18:02 生成） |
| 真实 HTTP | `MetricCounterRateHttpIT`（含在上述 Java 总数内） | **通过**。真实 HTTP 计数器页返回 `derivation.kind = counter-rate` 与 `resetPolicy`，四个原始点保留、三个区间派生、重置区间 `counterReset = true` 且 rate 非负；GAUGE 页 `counterRates` 为空。产物写入 `.tmp/metric-counter-http/` |
| Web 类型 / 构建 / 回归 | `apps/web-console` 的 `pnpm typecheck` 与 Playwright 全量（`.tmp/playwright-local.config.ts`） | **218 passed（新增 8 项），TypeScript 与生产构建通过**。新 `e2e/metric-counter-rates.spec.ts`：默认变化率视图带 reset 竖线与清单、切回原始累计值后仍是 4 个原始点与末值 40、6 种非法响应（错策略、无策略却有 rate、缺 rate、负 rate、rate 落在首点、非布尔 reset）被拒绝且不画图、GAUGE 页没有变化率入口 |
| Rust 默认 / 全 features / fmt | `node .tmp/rust-check.cjs` | **40/44 通过，0 failed/ignored；fmt 通过**（本轮无 Rust 改动） |
| 实际产物 | 最终整链后 Draft202012Validator + FormatChecker | **111 份产物、65 类顶层 Schema 通过**。新增 2 份：真实 HTTP 计数器页与原始页；均为自有合成数据 |
| 静态门禁 | `git diff --check`、当前分支与暂存检查 | **通过**；`main`、无 staged 变更，无本轮依赖升级或生成目录入库 |

中间环境观察：完整链在同一版本上先失败两次——第一次与 Docker 契约链并行运行，在扫描追溯检查处等待响应超时；第二次紧随其后单跑，在留存预览处按钮仍处于未启用状态。两次都发生在重负载/前次运行清理期间，失败点互不相同；确认无残留进程后单独重跑得到 **20 个 PASS / 112 边界 0 失败**，同版本另有一次子代理运行同样通过。这里如实记录为**本机 E2E 的抖动**，不把失败归因于被测代码，也不把成功单次当作稳定性证明：链路目前没有重试或超时兜底，重负载下仍可能抖动。

保留边界：变化率是平台按固定策略推导的**视图**，不是厂商上报的速率，也没有做单位换算、降采样或百分位合并；reset 只看同一序列相邻点下降，不跨来源/跨序列推断。原始点始终保留，切回原始值即可看到回绕。真实 Zabbix 采样、真实模型与人工审阅仍未验收，本节的 fixture 与协议桩不构成厂商验收。

M2 保持93%、M3 从85%调整为90%（counter reset 策略与用例是 M3 此前唯一的本地缺失项，补齐后 M3 的本机可验证条件全部有证据），M4 保持80%，**MVP约91%（±5个百分点），整体约58%**；真实来源/模型/身份与人工抽样审阅仍未通过，MVP100%目标继续。下一步继续来源失败/冲突追溯、只读诊断异常场景评估与运行记录治理。见 [ADR-010](adr/010-metrics-semantics.md)、[指标查询验收](runbooks/metric-query-acceptance.md)、[指标页](../apps/web-console/src/pages/metrics/MetricsPage.tsx)。

## 53. 2026-09-26 真实验收执行包（只读、可留证）（追加）

继续当前 `main` 工作区，无新分支、提交、推送或部署。第52节的审计确认本机可验证的退出条件已全部有证据，剩下的只有环境阻塞（真实 Zabbix、真实模型/账单、真实 IdP/TLS、人工审阅）。本节把“环境到位后怎么验收”变成可执行、可留证的一步：新增 checked-in 的只读验收执行包，按顺序调用平台 HTTP API，逐步断言 M2–M4 的关键退出条件，并写出 JSON 报告。

`scripts/acceptance/real-acceptance.mjs` 的七步：S1 来源连接自检（reachable + dataMode + 来源自报版本）→ S2 Host 同步并要求 `hostid-watermark-snapshot` 且**落库标签回读一致** → S3 Item 同步并要求 `itemid-watermark-snapshot` → S4 受权资产读取 → S5 指定指标必须 `AVAILABLE` 且有样本（计数器同时记录 reset 数）→ S6 Incident 与关联告警可读（未指定则先导入一个外部告警窗口）→ S7 一次只读诊断保存为 PG AIInsight 并回读（证据引用数 + provider）。任一步失败立即停止、写失败报告、退出码 1。它只调用平台 API：不直连 Zabbix/模型/数据库，不写来源，不重试。

**诚实性守卫**：来源自检返回 `labeled-fixture` 时，除非显式 `--rehearsal`，脚本以退出码 2 拒绝运行并打印“fixture 运行绝不是真实验收”；即使带 `--rehearsal`，报告的 `mode` 也只写 `rehearsal`。报告固定列出脚本不能替代的三项未验证项：人工抽样审阅、真实模型提供方账单/配额对账、真实 IdP/HTTPS/反向代理验收。

| 检查 | 实际命令 / 方法 | 最终结果与边界 |
|---|---|---|
| 验收执行包演练（通过路径） | `node .tmp/acceptance-local.cjs`（本机启动真实平台 + Runtime + loopback PG/VM，写入显式 `labeled-fixture` 样本后调用执行包 `--rehearsal`） | **7/7 步通过**：S1 `labeled-fixture`+无版本声明、S2 `accepted=2/pages=1`、S3 `accepted=1/rejected=1`、S4 资产、S5 `AVAILABLE` 且有样本、S6 Incident+1 条关联告警、S7 AIInsight 落库并回读（`evidence=2`、`provider=mock-deterministic`）；报告 `mode=rehearsal` 并打印“NOT real acceptance” |
| 诚实性守卫 | 同一 fixture 来源去掉 `--rehearsal` | **退出码 2 且不产生真实报告**；报告 `mode=rehearsal`，步骤为空 |
| 失败路径 | 同一执行包用错误 Token | **退出码 1**，报告 `failed=true`、`steps=0`，未把任何步骤记为通过 |
| 结构 / 契约 | Docker Python3.12 `.tmp/check-counter-final.sh` | **642 passed；238 个结构化文件、6 个只读 Tool；111 份产物/65 类 Schema**（本节只新增脚本与文档，不改契约与产物） |
| 静态门禁 | `git diff --check`、`node --check`（执行包与演练脚本）、当前分支与暂存检查 | **通过**；`main`、无 staged 变更 |
| 回归（本轮无产品代码改动） | 沿用第52节全套：领域 1008/37、Java 226（206+20）、浏览器链 20 PASS/112 边界、OIDC 10 PASS/18 边界、Rust 40/44 + fmt、Web 218 + typecheck/build | **未重跑**；本节只新增 `scripts/acceptance/real-acceptance.mjs` 与文档，不改动 Java/Rust/TypeScript/契约 |

演练过程中修掉三处真实缺陷：① 演练脚本最初只给一台资产写样本，而执行包会挑另一台资产 → 现在为每个受权资产都准备样本；② 执行包在样本尚未被平台可见时就查询 → 演练脚本先等待“样本通过平台可见”；③ 执行包最初把诊断时间窗写成 epoch 秒，而 Runtime 要求 RFC3339 → 已改为 ISO 字符串。第 3 条是**执行包自身的真实缺陷**，被这次演练抓到并修复，而不是等真实环境才发现。

保留边界：执行包用开发模式 Bearer Token 驱动平台 API，覆盖“真实来源 + 真实模型”在开发/平台开发模式下的验收；生产 OIDC 部署仍按 OIDC runbook 用浏览器流程验收。报告里的 `mode: "real"` 只说明来源不是 fixture，**不替人判断这个来源是不是目标环境**——`sourceDataMode`、`reportedVersion` 与 provider 仍需人工核对。人工抽样审阅、账单对账与 IdP/TLS 验收固定列为未验证项。

M2 保持93%、M3 保持90%、M4 保持80%，**MVP约91%（±5个百分点），整体约58%**；本节是验收工具与文档，不是产品能力，因此不调整估算。真实环境一旦可用，按 [real-acceptance runbook](runbooks/real-acceptance.md) 执行并把报告附到验收记录；M1–M4 退出门槛在此之前仍未全部通过。

## 54. 2026-09-26 扫描运行记录的有界保留与配额（追加）

本轮与前 53 节不同：用户明确要求**先提交并推送**，再继续推进 MVP。因此本节记录的是已提交、已推送的变更，验证一部分在本机执行，另一部分由 GitHub Actions 执行（本机没有 PostgreSQL/Playwright 运行环境）。

第43/49/51/53 节都把同一件事写成已知缺口：`integration.source_sync_run` 只增不减，没有自动清理、没有每来源上限、没有租户总量配额。第44节的追溯依赖这张表，“最近运行”这个读取语义也本来不需要无限历史。本节把它变成**有界存储**：预算写在领域（`ScanRunRetention`），两个存储适配器同样执行。

规则：每个 `tenant + sourceInstanceId + objectType` 范围最多 `maxRunsPerScope = 1000` 行，每个租户最多 `maxRunsPerTenant = 5000` 行；配置（`opsweave.scan-run-retention.*`，环境变量 `OPSWEAVE_SCAN_RUN_MAX_PER_SCOPE/PER_TENANT`）只能收紧，越界或不一致（租户上限小于范围上限）一律按 `INVALID_REQUEST` 拒绝。清理发生在**打开一次扫描的同一事务里，且在新行写入之后**：超预算时从最旧的“可删除”行删起。仍在 `RUNNING` 的运行与被 `sync_pipeline_pin` 钉住的运行永不删除——钉子是外键，删掉它要么破坏引用、要么抹掉“这次运行按哪个映射版本走”的答案。追溯页新增 `retention`（预算与当前条数），读取本身不触发清理。

| 检查 | 实际命令 / 方法 | 最终结果与边界 |
|---|---|---|
| 纯领域 | `node .tmp/domain-check.cjs`，Java21 编译全部 modules/tests/domain 并逐个运行 main | **1071 项、39 个 main 通过**，连续三次复跑均通过（无抖动）。新增 `ScanRunRetentionSmoke` 50 项与 `ScanRunRetentionConfigSmoke` 11 项：预算默认值/收紧/越界拒绝、范围与租户预算、六个扫描后只剩预算行、刚结束的运行不会被自己的 sweep 删掉、打开中的运行与未结束运行受保护、超过预算的范围收敛、读取不清理、内存适配器与游标分页同一全序；`SourceScanRunQuerySmoke` 改为断言“同一毫秒的运行顺序不属于契约”，改用覆盖性/不重复/时间单调断言（41 项） |
| Web 类型 | `apps/web-console` 的 `pnpm typecheck` | **通过**。`source-scan-runs` 解析新增必填 `retention`，并拒绝越界预算、范围上限大于租户上限、负数、超过范围预算的条数、比本页还小的条数、缺字段与额外字段 |
| 静态门禁 | `git diff --check`、`git status`、提交内容复核 | **通过**；`main`，工作区干净，未提交 `.tmp/`、`node_modules`、`target` 或凭据（`.gitignore` 覆盖） |
| 契约 / Java / Rust / Playwright | GitHub Actions `opsweave-template`（push `86884af` 触发：contracts / rust / web / java 四个 job） | **四个 job 全绿**：`contracts` 28s、`rust` 1m45s、`web` 2m21s（含 Playwright 全量）、`java` 2m33s（PostgreSQL 17 + VictoriaMetrics，**210 tests, 0 failed**，含本节新增的 `PostgresScanRunRetentionIT`）。本机没有 pytest 依赖、PostgreSQL 与 Playwright 运行环境（Docker Desktop 未运行、pip 与直连网络不可用），因此这四项**不在本机执行**，只按 CI 的真实结果记录 |
| 真实 PG 保留 | `PostgresScanRunRetentionIT`（新增 4 项，随上面的 java job 执行） | **通过**。真实 PG 上：六个扫描后本范围真实行数为 3（不只是报告值）、读取两次不改行数、被映射版本钉住的运行与其 pin 在多次 sweep 后仍可解析（受保护行**在预算之外**，因此范围是预算 + 1 行，已按此断言）、预算按范围/来源/租户隔离 |
| 首次 CI 失败与修正 | push `86884af` 的 java job | **1 项失败，是本轮新增测试的期望写错**：`retentionNeverBreaksAPinnedMappingVersion` 断言“含被钉住运行的范围不超过预算”，而受保护行本来就在预算之外。产品逻辑未改，只修断言并补齐“预算 + 1 行”的真实期望 |

中间修正（都是本轮真实抓到的缺陷，不是测试写法）：

1. **sweep 与插入的顺序**：最初在插入新行之前清理，于是“预算”实际是“预算 + 一个刚结束的运行”，范围永远比预算多一行。改为**先写入新行、再在同一事务内清理**，并让**正在打开的运行计入预算**，范围才会收敛到预算。
2. **可删除集合的语义**：第一版把“受保护行”也算进预算分母，导致一个含 `RUNNING` 行的范围反而更早开始删除历史。现在预算只数“可以删的行”，受保护行不算分母也不被删。
3. **同毫秒运行的顺序**：为了让淘汰确定，曾把内存适配器的排序改成“插入序号”，但追溯的游标分页按 `started_at DESC, id DESC` 过滤，两种顺序一旦不一致就会出现“游标跳过行/重复行”——`SourceScanRunQuerySmoke` 立刻抓到。最终保留与分页一致的 `startedAt + id` 全序，改为让内存适配器**分配单调递增的开始时间**（同刻则加 1 微秒），并把“同一毫秒谁先被淘汰”从契约里剔除：契约是行数上限与受保护行。
4. **测试自身的错误假设**：`ScanRunRetentionSmoke` 初版有 5 处断言写错了语义（把“刚结束的运行”当成会被立即淘汰、把未结束运行当成会占预算分母、把 `retained()` 当成预算值）。这些都被真实运行暴露并改写为不变量断言。

保留边界：保留策略**不是修复路径**——它不改写任何存储结果、不退休对象、不补做缺失对账，失败的运行和成功的运行一样按时间淘汰；未结束的 `RUNNING` 行受保护，所以崩溃留下的遗留运行需要独立的租约/状态修复才能回收，本轮不提供自动修复。没有后台清理任务、跨租户总量治理、备份或物理擦除，也没有把预算用于自动决策。`source_sync_run` 之外的业务/授权元数据生命周期仍按 [ADR-048](adr/048-metadata-retention-backup-lifecycle.md) 保持开放。真实 Zabbix、真实模型、真实 IdP/TLS 与人工抽样审阅仍未验收。

M2 保持93%、M3 保持90%、M4 保持80%，**MVP约91%（±5个百分点），整体约58%**；本节补齐的是第43–53节反复记录的运维硬化缺口（不属于 M2–M4 退出门槛本身），因此不据此提高阶段估算。见 [ADR-047](adr/047-scan-run-retention.md)、[扫描运行契约](../contracts/source-scan-runs.md)、[操作说明](runbooks/source-scan-runs.md)。

## 55. 2026-09-26 推送后暴露的两个真实缺陷（部署与测试）（追加）

用户要求“先推送”，于是 `main` 上的 CI 与 deploy job 第一次真正执行了第34–54节这批未推送的改动。两个缺陷只有在推送后才可能暴露，因此单独记录。

**一、web 镜像从未构建成功。** deploy job 的 `docker build -f deploy/docker/web.Dockerfile` 在 `pnpm build:web` 处失败：

```text
src/api/pipelines.ts(2,22): error TS2307: Cannot find module '../../../../contracts/examples/pipeline-definition.json'
```

`pipelines.ts` 直接 import 已发布的 `contracts/examples/pipeline-definition.json`，而 web 镜像只 `COPY apps/web-console`，构建上下文里没有 `contracts/`；agent 镜像早就 `COPY contracts contracts`，web 镜像漏了。**web 的 CI job 为什么没抓到**：它 `pnpm install` 后构建整个仓库（含 `contracts/`），只有镜像构建才暴露缺文件。修正是给 `web.Dockerfile` 加一行 `COPY contracts contracts`，并在注释里写明原因。这一缺陷在第一次推送前就存在（`f183b47` 的 deploy 也因它失败），不是本轮引入。

**二、`ToolExecutorTest` 的既有断言在 CI 上抖动。** push `c4833c2`（只改了 Dockerfile，没有 Java 改动）的 java job 出现 1 项失败：

```text
ToolExecutorTest > ordinaryFailuresReleaseCapacityAndPreserveSanitizedFailureType() FAILED
210 tests completed, 1 failed
```

读 `ToolExecutor.run` 可以确认这是**测试断言而非产品缺陷**：许可由 worker 线程在 `finally` 中释放，而失败通过 `CompletableFuture` 先返回给调用方，所以“失败之后立刻再取一次”实际在断言线程调度顺序，而不是“失败会释放容量”这个不变量。同文件另一个用例本来就用带重试的循环等待恢复。修正为最多 200 次、每次 5ms 的重试并在失败信息里带上最后一次异常类型，不变量不变。

| 检查 | 实际命令 / 方法 | 最终结果与边界 |
|---|---|---|
| 四个 job（`c2739b8`） | GitHub Actions `opsweave-template` | **contracts 28s、rust 1m45s、web 2m28s、java 2m36s 全绿**；java 210 tests 0 failed（含新增 `PostgresScanRunRetentionIT` 4 项），web 含 Playwright 全量 |
| deploy job | 同一 run 的 `deploy`（四 job 全绿后才启动） | 镜像构建阶段**已通过**（Dockerfile 修正生效）；主机部署步骤在 `Streaming images to 82.156.234.84` 之后停住，直到 job 的 90 分钟上限被取消（`in 1h30m15s`，`conclusion: cancelled`）。因此本轮**没有完成部署**，也不声称部署成功：卡点在 `docker save 四个镜像 \| gzip \| ssh "gunzip \| docker load"` 这一段，属于部署通道本身（镜像体积/带宽/远端加载），与本轮代码无关，改动 Dockerfile 前它甚至到不了这一步 |
| 本机 | 无 PostgreSQL/Playwright 环境（Docker Desktop 未运行、pip 与直连网络不可用） | 只跑了纯领域与 Web typecheck；契约/Java/Playwright 全部按 CI 真实结果记录，未在本机复跑 |

M2–M4 与 MVP 估算不变（两处都是构建/测试缺陷，不改变产品能力）。这两个缺陷说明“本机全绿”不等于“可交付”：CI 与镜像构建覆盖了本机无法执行的部分，本轮把它们的真实结果留在这里而不是留在口头结论里。部署通道的卡点（第55节 deploy 记录）需要单独处理：它是镜像分发方式的问题，不属于 M0–M4 退出门槛，也不改变本次四个 job 的结论。

## 56. 2026-09-26 被拒写尝试的审计留存（追加）

继续目标里的第 (2) 项：第43/53 节把“被拒绝的写尝试不落库，只有决策回执”记为已知缺口。补充来源链的两类人工写
（字段审核、绑定更正）在**成功**时都有完整回执，但被拒时只返回 409 与稳定错误码，不留任何痕迹——"谁在何时试图
改什么、为什么被拒"事后无法回答，而这恰恰是最需要看到的信号。

本轮新增 `integration.rejected_write_attempt`（V027）与两个存储装饰器：字段审核（`stage`/`decide`）与绑定更正
（`correct`/`ingest`）在内存与 PostgreSQL 两条路径上都被记录，**原请求原样重新抛出**。记录内容刻意收窄：稳定
`reasonCode`、被拒操作的白名单标签、actor、尝试过的**字段名**、时间与 tenant/source；**不记录字段值、厂商报文、
异常消息**。每 tenant/source 保留最近 500 条（`OPSWEAVE_REJECTED_WRITE_MAX_PER_SOURCE`，只能收紧），写入与清理在
同一事务。读取入口 `GET /api/v1/integrations/cmdb/rejected-writes` 只读且不重放，权限沿用写入所需的三项（仅
`source.sync` 会 403），装饰器只写"配置来源"的记录——读不回来的行不写。

| 检查 | 实际命令 / 方法 | 最终结果与边界 |
|---|---|---|
| 纯领域 | `node .tmp/domain-check.cjs`，Java21 编译全部 modules/tests/domain 并逐个运行 main | **1108 项、40 个 main 通过**。新增 `RejectedWriteAuditSmoke` 37 项：预算默认/收紧/越界拒绝、记录往返（码/操作/actor/字段名/时间）、**拒绝原样重抛且不改写消息**、成功写不产生审计、字段名白名单丢弃未知名、actor 坏输入记为 `unknown` 且不回显、非配置来源不落库、每 scope 上限与淘汰顺序、tenant/source 隔离、记录自身拒绝重复/非法/超量字段名 |
| Web 类型 | `apps/web-console` 的 `pnpm typecheck` | **通过**（本轮不改前端，作回归） |
| 契约 | `contracts/schemas/v1/rejected-write-audit.schema.json` + 样例 + `tests/contracts/test_rejected_write_audit.py` | 新增 1 类 Schema、1 份样例与 40 项用例：信封闭合与必填、kind/method/reasonCode 闭集、每码对应固定摘要、摘要单行长度、actor 无控制字符/无首尾空白（允许内部空格）、**字段名只能是 `name/ip/owner/environment` 且不重复**、不允许 `fieldValues`/`vendorMessage`/`receiptId` 等额外声明、时间必须是绝对时间戳。本机无 pytest 依赖，**未在本机执行**；CI 的 contracts job 实测通过（下面记录迭代过程） |
| Java / 真实 PG | `PostgresRejectedWriteAttemptIT`（新增 5 项）与 `RejectedWriteAuditHttpIT`（3 项 + 嵌套 1 项） | 本机无 PostgreSQL 与完整 Gradle 依赖缓存（`~/.gradle` 只有 211 个 jar，缺 Spring/JDBC/Servlet），**未在本机执行**；CI 的 java job 实测 **219 tests, 0 failed**（含新增 9 项），覆盖：记录往返与固定摘要、六个拒绝后真实行数为 3 且读取不清理、tenant/source 隔离、**真实拒绝路径**（装饰器记录后原样重抛调用方自己的异常、字段名稳定排序）、HTTP 读回字段名与 `no-store`/`nosniff`、limit/未知参数 400、未认证 401 且不泄漏行内容、只有 `source.sync` 时 403 |
| 真实环境 / 人工 | — | 未运行；真实 Zabbix/模型/IdP/TLS 与人工抽样审阅仍为环境阻塞项 |

CI 迭代过程如实记录（本机只能跑领域与 typecheck，因此这些缺陷只能由 CI 暴露）：① `Properties.Inventory` 上没有 `cmdbImportSource()`——改为把配置来源作为 `InventoryWiring.open(properties, cmdbImportSource)` 的参数并从 `PlatformConfiguration` 传入；② 把审计包装器误放进构造函数第 2 个位置（那是 `InventoryWritePort`）——构造函数参数顺序改为显式传入 `sourceSnapshotsWired`/`sourceReviewsWired`；③ 契约 `reasonSummary` 的首版正则允许纯空白，随后一版又拒绝了内部空格——最终采用仓库既有的可打印文本模式 `^\S(?:[^控制字符]*\S)?$`（拒绝首尾空白与换行，允许内部空格）；④ `Jackson 3` 的 `JsonNode` 没有 `propertyNames()`——改用 `properties()`；⑤ 审计字段名顺序原本取决于调用方 Map 的迭代顺序——改为稳定排序后再落库。两次契约失败与两次编译失败都是本轮新代码/新用例的问题，没有放宽任何既有断言。

保留边界：审计**不重放、不授权、不重试**，被拒的请求仍然被拒，绑定与字段状态不因记录而变化；没有 Web 页面
（接口与契约已就绪）、没有按时间窗口的清理入口、没有跨来源汇总或拒绝次数告警；读取入口只服务配置的导入来源。
schema 使用仓库既有的可打印文本模式，未对孤立 DEL 字符另做特例（摘要是服务端生成的固定文案，不是调用方文本）。
`source_sync_run` 之外的其他业务/授权元数据生命周期仍按 [ADR-048](adr/048-metadata-retention-backup-lifecycle.md)
保持开放。M2–M4 与 MVP 估算不变（补齐的是治理缺口，不是退出门槛本身）。见
[ADR-049](adr/049-rejected-write-audit.md)、[契约](../contracts/schemas/v1/rejected-write-audit.schema.json)。

## 57. 2026-09-26 来源收据容量的只读视图（追加）

继续目标里的第 (3) 项（其他业务/授权元数据的留存与备份生命周期）。审计这一项时确认了 ADR-048 里的一条真实缺口：
来源快照与绑定更正的**回执**按 tenant/source 各保留最多 1000 份，而且**达到上限就 fail closed**——`ingest` 与 `correct`
直接以 `SNAPSHOT_LIMIT`/`CORRECTION_LIMIT` 拒绝写入。也就是说，一旦某个来源满了，导入会开始静默失败，而平台
**没有任何地方显示这个计数**。ADR-048 同时明确：删除回执会让旧请求的幂等回执不可再查（ADR-037 的"原请求原样取回"），
所以清理必须是人工作业而不是后台任务。

本轮先做**可见性**（清理入口仍待设计，ADR-048 的"后续工作"里保留）：新增只读
`GET /api/v1/integrations/cmdb/receipt-capacity`，报告两类回执各自的 `kept`、发布上限与
`OK/NEAR_LIMIT/AT_LIMIT` 状态（80% 起告警），每行带固定文案说明"还能写 / 该安排了 / 已经写不进去了"。它**只计数**：
不删除任何回执、不放宽上限、不重放任何导入或更正；权限沿用写入所需的三项（仅 `source.sync` 为 403，未配置为 503，
未认证为 401），且不接受任何查询参数。

| 检查 | 实际命令 / 方法 | 最终结果与边界 |
|---|---|---|
| 纯领域 | `node .tmp/domain-check.cjs`，Java21 编译全部 modules/tests/domain 并逐个运行 main | **1136 项、41 个 main 通过**。新增 `ReceiptCapacitySmoke` 28 项：两类上限取自写入路径真正使用的常量（`SourceSnapshot.MAX_RECEIPTS`/`SourceBindingCorrection.MAX_RECEIPTS`，防止视图与写入漂移）、状态边界（0/799/800/999/1000、cap=1）、固定文案且**不声称发生了清理**、记录拒绝负数/超上限/max=0/非法来源、三类权限缺失各自 403、计数只读（计数次数可观测）、`max` 是发布常量 |
| Web 类型 | `apps/web-console` 的 `pnpm typecheck` | 本轮不改前端；由 CI 的 web job 回归通过 |
| 契约 | `contracts/schemas/v1/source-receipt-capacity.schema.json` + 样例 + `tests/contracts/test_source_receipt_capacity.py` | 新增 1 类 Schema、1 份样例与 27 项用例：信封必填与闭合、**恰好两行**且 kind 为 `snapshot`/`binding-correction`、`max` 必须是发布的 1000、`kept` 不越界、status 闭集与五个边界值、摘要单行、不允许 `removed`/`raisedTo` 等额外声明。本机无 pytest 依赖，**未在本机执行**；CI 的 contracts job 实测通过 |
| Java / 真实 PG | `PostgresReceiptCapacityIT`（3 项）与 `SourceReceiptCapacityHttpIT`（2 项 + 独立只读权限类 1 项） | 本机无 PostgreSQL 与完整 Gradle 缓存，**未在本机执行**；CI 的 java job 实测 **225 tests, 0 failed**（含新增 6 项），覆盖：空来源报 0/OK、真实落库 1000 行后计数为 1000 且**读两次不会删掉任何回执**、状态为 AT_LIMIT、另一类仍为 0、跨租户/跨来源不计入、HTTP 返回两行与固定文案、无 `removed`/`raised` 字样、带任何查询参数 400、未认证 401 且不泄漏行、只有 `source.sync` 时 403 |
| 真实环境 / 人工 | — | 未运行；真实 Zabbix/模型/IdP/TLS 与人工抽样审阅仍为环境阻塞项 |

CI 迭代：① 首次 java 失败是本轮新用例把 `Set` 与排序后的 `List` 直接比较（`names()` 返回 List），改为按集合比较后全绿。
这一处是本轮新代码的问题，没有放宽任何既有断言。

保留边界：这是**可见性**而不是治理本身——没有清理入口、没有扩容路径、没有跨来源汇总或后台调度；容量视图不删除任何回执，
也不改变 fail-closed 语义（满了仍然拒绝写入）。资产观测**刻意不进入清理候选**：已存储的工具证据引用观测 id，删除观测会
留下悬空引用（记录在 ADR-048）。备份/恢复/物理擦除仍无任何能力或承诺。M2–M4 与 MVP 估算不变。见
[ADR-048](adr/048-metadata-retention-backup-lifecycle.md)、[契约](../contracts/schemas/v1/source-receipt-capacity.schema.json)。

## 58. 2026-09-27 验收报告防误判、真实证据回读与当前环境复验（追加）

用户再次要求在当前 main 持续推进 M0–M4 至 100%，不新建分支。本节变更留在 main 工作区，未提交、推送或部署。
核对退出条件时发现第53节执行包本身会误判：仅凭来源自检不是 fixture 即输出 `mode: real`，并未拒绝 mock 模型；
S5 与 S6 可选择互不关联的资产和 Incident；S7 只读结果，不实际回读所列 Evidence；一个成功扫描还被映射为
“重复同步与失败不误删”的证明。故先修复验证入口，不能用这个入口宣布 100%。

修正后的执行包只生成 v2 的 `rehearsal`、`unverified` 或 `real-candidate`，`milestonesSatisfied` 永远为 false。
非演练要求明确资产/Incident/指标/预期来源版本；每一段复核来源，mock、fixture、unknown 不能通过非演练。
Host 发布版本/digest 与持久扫描追溯一致；Item 也回读持久扫描；指标资产与 Incident 告警必须真实关联。
AIInsight 必须匹配本次 run/租户/Incident/版本/资产/窗口，原样回读；随后经固定平台路径逐条读取两类 Evidence，
检查会话、资源范围、版本、查询窗口、availableAt/asOf 与当前过期时间，metric 证据必须包含所选资产的样本。
引用只表示完整性，不表示因果支持；契约允许的空 findings 保持有效，不要求模型编造发现。

网络与留证收紧：不跟随 HTTP 重定向，不跟随上游 sourceRef；远端显式启用且必须 HTTPS；单次响应上限 256 KiB，
普通请求20秒、诊断90秒；无重试。报告与 stdout 不记录 Token、URL、资产名称、异常正文、提问或模型输出，
只留必要标识、模式、请求 UUID、时间、稳定失败码。配置失败也能落报告，开始时间不再使用结束时间。
真实目标环境、版本兼容、幂等/失败/授权负例、真实 IdP/TLS/浏览器、账单与人工审阅仍须独立证据。

来源失败日志审计还发现 Item 同步直接记录原始异常（包括嵌套厂商/存储内容）；现与 Host 一致，只记录稳定失败码。
回归用合成凭据/厂商正文构造两层异常，捕获实际 LogRecord，检查固定文案、WARNING、无 Throwable/参数；
保留原 SOURCE_FETCH_FAILED、已写页及失败不误删除的行为，不把上游失败变为空快照。

| 检查 | 本轮实际命令 / 方法 | 结果与边界 |
|---|---|---|
| 验收入口回归 | `node --test tests/acceptance/*.test.mjs` | **44 passed，0 failed/skipped**。loopback 协议 fixture；覆盖 mock/fixture/unknown、版本/关联/引用/会话/时间、实际证据403、空 findings、合法大写流水线 ID、末尾换行、同 ID 内容变更、重定向、错误正文不泄漏、大小上限及缺配置 CLI。生成43份协议 fixture 报告用于跨校验，不算真实验收 |
| 结构 / 锁与 Wrapper | `.tmp/mvp-check-venv/Scripts/python.exe scripts/check_repo.py`、同解释器 `scripts/check_release_inputs.py` | **244个结构化文件、6个只读 Tool 通过**；Bootstrap 文件存在。没有生成或升级产品锁文件、Wrapper |
| 契约全量与报告交叉校验 | `.tmp/mvp-check-venv/Scripts/python.exe -m pytest tests/contracts -q`；随后加 `-X utf8 -o addopts=` 复跑 | 首次**收集失败**：既有 `test_incident.py` 使用默认GBK读取UTF-8中文样例，出现 `UnicodeDecodeError`。本地 `scripts/check.ps1`/`check.sh` 已显式启用UTF-8；UTF-8全量复跑通过；扫描计数样例修正后最终 **800 passed（7.29s），退出码0**，输出在 `.tmp/mvp-contracts-final.log`。包含实际CLI失败报告和43份协议fixture报告的Schema交叉校验 |
| 纯领域 | `node .tmp/domain-check.cjs`，Java21 编译全部 modules/tests/domain 并运行 main | **1141项、41个 main 通过**。新增5项失败日志边界断言；先运行回归，旧实现按预期因原始 Throwable 泄漏失败，修复后全量通过。部分页面已保存且失败不误退休的既有断言仍通过 |
| Java / 启动包 | `gradlew.bat :apps:platform-api:test :apps:ingestion-worker:test :apps:platform-api:bootJar :apps:ingestion-worker:bootJar --offline --console=plain --rerun-tasks` | 首次无存储配置为124通过/122跳过。随后用隔离本机 PG/VM 配置同命令强制复跑：**BUILD SUCCESSFUL（3m33s），两个 bootJar 成功，36任务执行**；XML 平台225、Worker20，合计 **245通过，0失败/错误/跳过** |
| PostgreSQL / VictoriaMetrics | `docker info`、`docker desktop start --timeout 30`，核对测试环境配置 | Docker Desktop 启动超时且 Linux engine named pipe 不存在。改用校验过的官方 Windows 二进制：**PostgreSQL 17.11、VictoriaMetrics v1.152.0**，独立 `.tmp/mvp-native` 数据、随机PG凭据、仅127.0.0.1；245项Java测试全量零跳过通过。属于真实存储引擎上的合成数据测试，外部来源/模型/IdP仍是未验收 |
| Rust | `node .tmp/rust-check.cjs`：fmt、默认 `cargo test --workspace --locked -j 1`、`cargo test --workspace --all-features --locked -j 1` | **fmt通过，默认40 / all-features44通过，0 failed/ignored**。Rig用例仍是明确协议桩，无真实提供方请求 |
| TypeScript / Web build | `pnpm typecheck:web`、`pnpm build:web`；浏览器配置改动后 Playwright 的 build 前置再次执行 | **通过**；Vite生产构建82模块。未升级 pnpm/Zeus/TypeScript 等依赖 |
| 浏览器 | 显式设置 `OPSWEAVE_TEST_CHROMIUM_EXECUTABLE` 为既有 `.tmp/chromium-1193/chrome-win/chrome.exe`，运行 `pnpm --filter @opsweave/web-console exec playwright test --max-failures=1` | 初轮229项通过；实际整链发现扫描计数误判并修复后最终 **230 passed（2.1m），退出码0**；实际浏览器版本 **140.0.7339.186**。页面/HTTP fixture 回归，不是真实身份/来源/模型验收 |
| 扫描追溯 HTTP 回归 | 同一 PG 环境 `gradlew.bat :apps:platform-api:test --tests com.acme.opsweave.platform.SourceScanRunHttpIT --offline --console=plain --rerun-tasks` | **2项通过、0跳过（26s）**；新增断言确认被钉住 Host 仍在列表，但 retained=0。全量245项已在新增断言前通过，本次只重跑受影响类 |
| 实际本机整链 | 显式 PG/VM/Chromium 配置，`node scripts/check_metrics_stack.mjs --pipeline --runtime`；先 `cargo build --workspace --all-features --locked -j 1` | **21组 PASS，退出0**；浏览器→Java→Rust mock→PG/VM、持久AIInsight及证据、旧关联拒绝、页面清理、扫描/连接追溯；112个浏览器响应的UUID/no-store/nosniff检查零失败。新版验收器7步演练通过且非演练按预期拒绝fixture；两个报告均不签署里程碑 |
| OIDC / Worker 服务身份整链 | 同一专属存储，显式 native psql 路径，`node scripts/check_oidc_stack.mjs --history-service` | **10组 PASS，退出0**；PKCE/nonce、HttpOnly Cookie/CSRF、有界Runtime委托、PG结果刷新、双标签退出/撤权，以及Worker独立服务JWT、VM样本/PG检查点、撤权和提供方故障不推进、轮换后续采。18个浏览器响应边界检查通过；IdP仍为协议fixture、来源fixture、模型mock |
| 本轮产物契约/截图 | `.tmp/mvp-check-venv/Scripts/python.exe -X utf8 .tmp/validate-mvp-native.py`；查看实际扫描和AIInsight/证据页截图 | **53份本轮整链产物符合50类已发布Schema**，包含请求/配置/响应/两个验收报告，不能全部称HTTP响应；按文件时间排除旧产物。截图确认钉住记录正常显示、计数语义和fixture/mock/证据局限明确 |
| 缺真实配置的入口实跑 | `node scripts/acceptance/real-acceptance.mjs --report=.tmp/acceptance/mvp-blocked-20260927.json` | **按预期退出1**：`failedStep=CONFIG`、`errorCode=PLATFORM_URL_REQUIRED`、`mode=unverified`、0步骤。当前进程没有 OPSWEAVE/模型配置，根目录仅 `.env.example`；真实来源/模型/IdP及人工审阅未完成 |
| 工作区与CI | `git diff --check`、`git status --short --branch`；读取并修改CI | **本机检查通过**，仍在main；新增 CI all-features测试执行和探针回归（原CI只编译全features）。**未推送，未运行新CI**，不能把配置变更称为CI通过 |

实际整链还暴露了扫描追溯页的语义错误：PG retained 排除了已钉住和 RUNNING 记录，页面却要求它至少等于本页条数。
两条受保护记录、retained=0 的真实响应因此被拒绝。现移除这条错误的大小关系，保留其它闭合/权限/时间/预算检查；
契约说明、合成样例、页面文案和正向回归统一为“已结束且未钉住的可清理记录计数”，不伪称全部存储的硬上限。
这不改变清理规则；被钉住和 RUNNING 记录仍受保护，其总量治理仍待完成，修正§54的泛化表述。
原浏览器成功状态断言保留，修复后完整链通过。随后新增演练一度触发共享四会话上限（HTTP429），
脚本改为等待原读取会话已发布的60秒截止时间后才执行额外诊断；无放宽上限、隐藏重试或自动fallback。
OIDC/Worker初轮因脚本要求容器名失败；补充显式native psql模式后全链通过，SQL错误不再伪装未存检查点。

环境恢复过程：初次契约调用缺 pytest，旧检查 venv 也缺 pip。新建隔离环境后 pip 的模块导入持续迟缓，随后使用本机
已有 uv 将 `requirements-dev.txt` 的15项开发依赖安装到 `.tmp/mvp-check-venv`，不作为应用后端或提交产物。
首次契约收集暴露Windows默认GBK问题，按UTF-8复跑；另一个Python3.14隔离启动尝试已中止，没有计为验证结果。
Docker 不可用后，[PostgreSQL 官方 Windows 下载入口](https://www.postgresql.org/download/windows/) 指向 EDB，
下载 17.11-4 ZIP 并核对 [EDB 发布者校验值](https://github.com/EnterpriseDB/edb-installers/issues/706)：
`b9424ee7bc60b52450ff910a3630225df32e633f3cb29c1d126d9299d59aea28`；
[VictoriaMetrics v1.152.0 官方 Release](https://github.com/VictoriaMetrics/VictoriaMetrics/releases/tag/v1.152.0)
Windows ZIP 核对发布资产 SHA-256 `bee2228ba012881c1867a5bf4cb49aa3fc24d7af8b752602ed77f95b93c7cde5`。
归档路径检查后只解压所需运行目录；未安装系统服务、未改变产品依赖或新增应用启动单元。
检查完成后已用本轮数据目录停止PG，并核对本轮VM进程标识后停止；保留忽略目录中的数据与验证产物。
初次浏览器运行因 Playwright 默认 headless_shell 不存在而失败（单例确认后停止），下载 Chromium 未完成；改为显式
复用已存在的同版本 Chromium，最终完整复跑230项。配置只接受显式路径，没有失败后自动切换浏览器。

本节不提高阶段估算：M1约90%、M2约93%、M3约90%、M4约80%、MVP约91%；只有M0已完整退出。
未新增服务、Copilot、动作工具或自动修复。真实配置与人工验收未具备时继续保持目标进行中，不将候选或协议 fixture
报告签署为真实验收。见 [新版执行说明](runbooks/real-acceptance.md)、[报告契约](../contracts/schemas/v2/mvp-acceptance-report.schema.json)。

## 59. 2026-09-27 M2 Raw 写入容量与原引用保护（追加）

重新按ROADMAP核对M2退出条件，发现Raw“有界保留”此前只在读取侧限量：PG retain直接插入、没有写入总量；
内存fixture按全局1000条逐出，可能使已保存的Raw引用不可查。此项可以独立于真实来源配置修复，故继续实现。

新增纯领域RawRetention准入：每tenant/source 1000条、每tenant 5000条；Host/Item及不同run共享额度，
新记录的序列化UTF-8 JSON与PG规范化JSONB文本各不超过65536字节。PG在同一事务内持有租户级advisory lock，
读取有上限的容量计数并插入；跨连接无法重复使用剩余额度。内存adapter同步检查、使用更保守的大小界，
不再逐出旧记录；单次Raw读取与端口统一为100条。满额/超大沿用RAW_PERSIST_FAILED，已写页和原引用保留，
不变成完整空快照、不退休资产。使用现有索引，不增加服务、数据库、后台清理、写入动作或新的HTTP权限。

旧库超额不自动删数据，新准入不是对旧库达标或磁盘物理字节的承诺。受保护扫描总量、Raw显式清理、扩容配置
与生产最小权限部署仍有边界；不能通过删除原引用使验收变绿。见[ADR-050](adr/050-raw-retention-admission.md)、
[契约与失败样例](../contracts/source-scan-runs.md)、[运行说明](runbooks/host-pipeline.md)。

| 检查 | 本轮实际执行 | 结果与范围 |
|---|---|---|
| 纯领域 | node .tmp/domain-check.cjs | **1161项、42个main通过**；新增Raw容量15项及Host失败5项。覆盖按来源/租户准入、并发争抢、拒绝超大、引用不逐出、第二页容量耗尽仍保留首条和既有资产 |
| 契约 | .tmp/mvp-check-venv/Scripts/python.exe -X utf8 -m pytest -o addopts= tests/contracts -q | **801 passed（7.46s）**，包含Raw容量失败案例和验收报告交叉校验 |
| Java / PG / VM / bootJar | 显式自有PG17.11/VM1.152.0配置，gradlew.bat :apps:platform-api:test :apps:ingestion-worker:test :apps:platform-api:bootJar :apps:ingestion-worker:bootJar --offline --console=plain --rerun-tasks | **249通过，0失败/错误/跳过；平台229 + Worker20；BUILD SUCCESSFUL（3m32s）**，两个bootJar构建成功。新增PG4项实际证明跨连接来源/租户共享预算、旧引用重开后可读、容量失败回滚，以及UTF-8/JSONB规范化边界和最大可读payload |
| Rust | node .tmp/rust-check.cjs：fmt、cargo test --workspace --locked -j 1、cargo test --workspace --all-features --locked -j 1 | **fmt通过，默认40 / all-features44通过，0失败/忽略**；仍为显式模型协议桩 |
| TypeScript / build | pnpm typecheck:web、pnpm build:web | **通过**，Vite82模块。本轮没有前端产品代码改动；230项页面fixture套件的实际结果仍见§58，本轮未重复该套件 |
| 实际浏览器整链 | node scripts/check_metrics_stack.mjs --pipeline --runtime；随后完整node scripts/check_oidc_stack.mjs --history-service；均显式配置PG/VM/Chromium/native psql | **21组 / 10组PASS，均退出0**。覆盖原引用、版本化同步、指标/Incident、Java→Rust mock、PG AIInsight及证据、七步演练、fixture拒绝真实模式、OIDC/Worker撤权/故障/轮换。IdP与来源为fixture、模型mock，不称真实外部验收 |
| 本轮产物 | .tmp/mvp-check-venv/Scripts/python.exe -X utf8 .tmp/validate-mvp-native.py，截止时间取本轮日志创建时间 | **53份产物符合50类Schema**；包含配置/请求/响应/报告，不全部称HTTP响应 |
| 静态 / 工作区 | check_repo.py、git diff --check | **245个结构化文件、6个只读Tool通过；diff检查通过**。仍在main，未提交/推送/部署；没有新CI运行 |

失败与修正如实记录：①新领域测试最初把“抓取到第二页、Raw写入失败”的pages误写成1；实际语义为pages=2、
fetched=2、accepted=1，修正该预期后通过，没有改动原扫描计数逻辑。②额外契约案例最初放在examples根目录，
根目录规则要求同名Schema，导致1 failed/801 passed；移到examples/cases并显式使用既有source-scan-run Schema，
复跑801全部通过，没有添加伪Schema或放宽契约。③Rust首次复跑与本机整链同时使用同一个exe，Windows拒绝删除
运行中的文件（os error 5）；确认两个整链进程正常退出后，重新执行fmt和默认/all-features测试全部通过。

本轮测试进程已停止，忽略目录中的验证产物保留。再次检查当前进程没有OPSWEAVE/模型环境变量，根目录仅.env.example；
真实Zabbix、模型、IdP/TLS与人工审阅仍未具备，M0–M4目标继续进行中，MVP估算不因本地测试条数提高。

## 60. 2026-09-27 M1 数据库运行角色与 Worker 只读结构校验（追加）

按ROADMAP M1复核数据库最小权限时，发现Worker每次启动都重放V001/V002 DDL，受限角色不能启动。
现新增OPSWEAVE_HISTORY_SCHEMA_MODE，默认verify：只读检查checkpoint列和有效、非延迟的tenant/source/item主键；
缺失fencing列、旧四字段stream主键或无读取权限失败关闭。只有显式migrate才执行迁移，未知模式拒绝，无隐式回退。
平台已有schema_migration账本时本来就跳过DDL，本次不改平台迁移行为；账本没有checksum，不把它称为完整漂移检测。

新增[逐表授权清单](../db/security/runtime-grants.sql)、[ADR-051](adr/051-runtime-database-roles.md)与
[运行说明](runbooks/database-runtime-roles.md)：迁移所有者、平台、Worker独立；平台只获业务表所需DML与迁移账本SELECT，
Worker仅获checkpoint SELECT/INSERT/UPDATE，双方不可访问对方模块。清单拒绝共享、提权、有成员关系或对象所有权的角色，
不自动撤销PUBLIC或既有授权。没有增加服务/数据库、RLS、任意SQL Tool或动作功能；目标部署权限仍需单独审计。

| 检查 | 本轮实际执行 | 结果与范围 |
|---|---|---|
| 纯领域 | node .tmp/domain-check.cjs | **1161项、42个main通过** |
| 契约 | .tmp/mvp-check-venv/Scripts/python.exe -X utf8 -m pytest -o addopts= tests/contracts -q | **801 passed（4.02s）** |
| Java / PG / VM / bootJar | 显式自有PG/VM配置，gradlew.bat :apps:platform-api:test :apps:ingestion-worker:test :apps:platform-api:bootJar :apps:ingestion-worker:bootJar --offline --console=plain --rerun-tasks | **250通过、0失败/错误/跳过（平台229、Worker21）；BUILD SUCCESSFUL（2m5s），36任务执行，两个bootJar成功**。新增PG用例在回滚事务中验证旧主键/缺列拒绝及正常结构恢复 |
| 数据库角色 | node scripts/check_database_roles.mjs，显式native psql及自有测试库管理员 | **两个随机LOGIN账号，无特权标志/成员关系；15次实际操作以SQLSTATE 42501拒绝**，包括跨模块读取、切换所有者、写账本、删除Raw/检查点、业务表DDL/TRUNCATE；同时查询全部当前表的跨模块权限为0。拒绝探针均在事务中，意外成功也回滚，不破坏业务数据 |
| 受限角色浏览器整链 | 上述脚本顺序执行check_metrics_stack.mjs --pipeline --runtime与check_oidc_stack.mjs --history-service | **21组 / 10组PASS，脚本退出0**；平台和Worker使用各自受限账号，Worker明确schema-mode=verify。真实PG/VM承载合成fixture，Rust模型mock，OIDC/client_credentials为协议fixture。包含持久AIInsight/证据、撤权、故障不推进、轮换续采；不称真实外部验收 |
| 角色清理 | 脚本finally撤销并删除精确的本轮随机角色；随后独立psql只读复核 | **ow_platform_/ow_history_临时角色计数为0**，业务表仍归原所有者且数据保留 |
| Rust | node .tmp/rust-check.cjs：fmt、默认cargo test及all-features测试，均--locked -j 1 | **fmt通过，默认40 / all-features44通过，0失败/忽略**；没有真实模型调用 |
| TypeScript / build | pnpm typecheck:web、pnpm build:web | **通过，Vite82模块**；OIDC脚本另构建测试前端成功。230项页面fixture套件结果仍引用§58，本轮未重复该套件 |
| 本轮产物 | .tmp/mvp-check-venv/Scripts/python.exe -X utf8 .tmp/validate-mvp-native.py；文件时间下界2026-09-27T03:46:49.223Z | **53份新产物符合50类Schema**，含配置/请求/响应/报告，不全部称HTTP响应 |
| 静态 | check_repo.py、node --check scripts/check_database_roles.mjs、git diff --check | **245个结构化文件、6个只读Tool通过；脚本语法与diff检查通过**；仍在main，没有新CI、提交、推送或部署 |

本轮失败与环境处置：最初编译因漏传schemaMode失败，补齐注入后编译通过。一次PG验证期间出现4项平台测试失败，
该轮不是成功证据；同时用户指出本机postgres子进程弹出多个窗口，随即停止自有PG/VM并确认无残留postgres.exe。
原因是临时启动器把postgres.exe以detached方式启动，仅隐藏父进程不足以让子进程继承隐藏控制台。
先用短生命周期父/子进程实测隐藏控制台继承，再改本机忽略目录内启动器，确认PG主进程及活动客户端/后台进程共享
隐藏控制台后才恢复验证。首个窗口探针虽已检查隐藏，但退出时因AttachConsole/FreeConsole改变stdio句柄返回120，
启动器按失败停止了自有存储；改为私有文件报告并避免刷新失效句柄后，启动与负载期间的窗口检查均退出0。
上述250项最终全量复跑和两条受限角色整链才是本节通过证据。角色检查脚本另有一次SQL字符串引号语法错误，
node --check发现并修复，未执行数据库动作；正式运行退出0。测试完成后已停止本轮PG/VM，数据与产物保留。

本机最小权限证据不替代目标部署：PUBLIC TEMPORARY等权限需要单独审计，租户/对象边界仍由平台执行器检查，
没有宣称PG自动隔离租户或所有临时DDL都被禁止。真实Zabbix、模型、IdP/TLS和人工证据审阅仍缺，M0–M4未达100%，
阶段估算保持不变。没有扩大到Copilot、自动修复或额外服务。

## 61. 2026-09-27 M4 当前诊断的合成标注集与可审阅评估产物（追加）

按ROADMAP M4核查，旧incident-summary/evals/cases.jsonl只有三个标签，不能作为当前知识诊断的八类评估证据。
新增contracts/evals/current-diagnosis.json及唯一Schema，10个合成案例覆盖正常、缺失、冲突、访问时过期、越权、
注入性文本、模型超时、非法输出，并单列伪造引用和模型后撤权。候选事实/禁止推断/人工问题均明确pending-human-review。
语料钉住现有incident.diagnose@2.0.0及digest：sha256:8c849b1e7829dbff55e47adc101d313cd2400e3568dbbfed815bcad2f1f7d7ed；
没有覆盖已发布Skill。JSON语料摘要为sha256:93df31126365a7dc050de7080ecfd5a5090f8e08aea56781315fe980a6562261
（与Skill相同的长度前缀hash方法，不是裸文件hash）。

Rust测试调用实际current_workflows::diagnose，每个案例分别运行既有MockModel与脚本模型。读取/费用许可/保存均为
内存fixture，外部模型调用为0；报告明确savedResultStorage=memory或null。两种运行使用同一组平移后的合成时间，
保留原相对时差/查询窗口/过期关系；时间策略在报告公开，不能当成真实采集。恶意日志式文本置于不可信告警标题中，
原样保留在上下文，LOGS_NOT_CONNECTED仍在；不声称已接入真实日志或已证明真实模型抵抗注入。

实际结果：正常/缺指标/冲突三个脚本案例通过四次读取和权限复核后模拟保存，缺失项由流程强制保留；过期和无资源
权限的模型步骤/保存均为0；注入动作字段、非法JSON、伪造引用、模型20秒超时、模型后撤权均不保存。超时用Tokio虚拟
时间触发生产工作流中的实际截止分支，不改生产预算；没有重试或fallback。确定性结果可查看，但不计算或宣称RCA准确率。

| 检查 | 本轮实际执行 | 结果与范围 |
|---|---|---|
| 语料契约 | pytest tests/contracts/test_current_diagnosis_eval.py | **8 passed**；原生Context/Evidence契约、八类覆盖/唯一标识、技能摘要、缺失/时间、fixture和人工状态约束 |
| 全量契约 | .tmp/mvp-check-venv/Scripts/python.exe -X utf8 -m pytest -o addopts= tests/contracts -q | **809 passed（6.55s）** |
| 纯领域 | node .tmp/domain-check.cjs | **1161项、42个main通过** |
| Rust默认/all-features | node .tmp/eval-rust-check.cjs：fmt、cargo test --workspace --locked -j 1、cargo test --workspace --all-features --locked -j 1 | **fmt通过；默认41/all-features45，0失败/忽略**。新增一个语料测试包含10个案例×2种明确fixture运行；不是20次真实模型调用 |
| Rust lint | cargo clippy --workspace --all-targets --all-features --locked -- -D warnings | **通过**，最终全量检查退出0 |
| 独立评估入口 | node scripts/check_diagnosis_evals.mjs --all-features | **退出0，10案例断言通过**；生成新的report.json与759行review.md，核对语料/Skill摘要、0实际提供方调用、memory模拟保存、realModelComparison=not-run、humanReview=pending、rootCauseAccuracy=null |
| TypeScript / Web build | pnpm typecheck:web、pnpm build:web | **通过，Vite82模块**；没有Web产品改动 |
| 静态 | check_repo.py、node --check scripts/check_diagnosis_evals.mjs、git diff --check | **247个结构化文件、6个只读Tool通过**，脚本语法与diff检查通过 |
| Java / PG / VM / 浏览器 | 本轮不启动存储、不重跑这些检查 | 最近的250项Java零跳过、受限角色21组/10组真实本机整链证据见§60；230项页面fixture套件见§58。不能把历史结果写成本轮重跑 |
| 真实验收入口 | node scripts/acceptance/real-acceptance.mjs --report=.tmp/acceptance/mvp-blocked-after-evals.json | **按预期退出1**：CONFIG / PLATFORM_URL_REQUIRED、0步骤、mode=unverified、milestonesSatisfied=false；当前进程无OPSWEAVE/OPENAI/AZURE_OPENAI配置变量，根目录只有.env.example |

最终评估产物位于本机忽略目录.tmp/current-diagnosis-eval/2026-09-27T04-13-07-883Z-3134c49a/，report.json为155863字节；
审阅文档和机器结果均已读取核对。首次Rust编译漏传共享anchor参数，修复后完成上述全量复跑；生成JS入口时一次工具
字符串解析失败、未写出文件，重新保存并通过语法/实际运行检查。未把失败尝试计入通过结果。

独立工作已补齐本轮发现的M4语料/审阅材料缺口，真实模型对照与人工审阅仍未执行。继续审计M0–M4后，剩余关闭门槛
需要外部状态：真实受权Zabbix及版本/目标资源、真实指标/告警/Incident、真实模型与费用政策、IdP/TLS/代理及人工证据
审阅；当前缺配置导致真实验收入口连第一步也不能执行。不能通过增加fixture、放宽真实性判定、扩展M5–M7或模拟人工
结论替代它们。工作区仍为main，未提交/推送/部署；本轮未启动PG，进程复核postgres.exe为0。

详见[评估执行说明](runbooks/current-diagnosis-evaluation.md)和[MVP退出清单](MVP-CHECKLIST.md)。目标未达到100%，
本轮不因测试条数或语料数量提高百分比，也不签署任何真实里程碑。

阻塞审计：§59 Raw准入、§60独立数据库角色、§61评估语料三个连续目标增量都确认同一真实环境配置/人工验收缺口；
其间先完成了可独立推进的工作。当前没有可用真实配置，也不能由代理代替人工签署，目标记录为阻塞而非完成或缩减范围。

## 62. 2026-09-27 本地真实 Zabbix 启动与只读接入凭据（追加）

用户明确请求本地启动Zabbix。WSL uname成功，Docker两处遗留Unix套接字导致启动崩溃；
完整停止本次失败进程、保留通信目录并重建后，Docker Desktop4.77.0/Engine29.5.3 Linux API恢复。
没有重置Docker数据或启动原生postgres.exe。已有官方镜像复用并钉住四个RepoDigest，版本Zabbix7.0.27/PG17.10。

Compose项目opsweave-zabbix-local的Server/Web/Agent/PG四容器实际启动，只发布127.0.0.1:18088。
apiinfo.version返回7.0.27，Web/PG healthcheck healthy。默认Admin密码已改为随机私密值，换密后实际登录成功。
创建一个local-test主机和三项10秒采集（CPU user、可用内存百分比、系统uptime）；只读token通过item.get/history.get
读回三个正常采集项，每项至少三条实际历史点。采样是容器Agent可见的Linux/WSL值，不是Windows宿主机或客户资产。

专用opsweave_readonly只允许host.get/item.get/history.get/event.get，只有测试主机组22读权限。
实测host.get只见一个测试主机，host.update明确返回No permissions；token到期2026-10-27T05:30:35Z，凭据不入报告/仓库。
Headless Chromium在05:32:35Z完成管理员登录、打开Latest data、应用Container:筛选、看到三个指标，pageErrors=0。
截图已查看，三行均有最新值和采集时间；node .tmp/zabbix-local/control.cjs status退出0，四容器持续运行。

配置/密码/token/锁定摘要/verification.json/browser-verification.json/截图位于忽略目录.tmp/zabbix-local。
git check-ignore确认凭据和.env被排除。首次浏览器require路径错误退出1，修正为现有@playwright/test后退出0。
未把失败启动或失败测试计为通过。启停说明见[local-zabbix](runbooks/local-zabbix.md)。

本轮仅本机环境与文档，没有运行契约、领域、Java、OpsWeave PG/VM、Rust默认/all-features、TS/build全量检查，
历史结果沿用§60/61范围。本轮未执行OpsWeave连接自检/版本化映射/资产指标入库/Incident关联，
未制造告警、调用模型、接真实IdP/TLS或代替人工审阅。原fixture标记不变，本地厂商服务已可用于后续联调。
M0–M4仍未全部退出，MVP约91%的估算不提高，工作区main未新建分支。

## 63. 2026-09-27 本地 OpsWeave 预览与真实 Zabbix CPU 链（追加）

用户请求启动服务查看。创建忽略目录.tmp/local-preview中的专用配置和随机开发凭据，后台启动四个应用单元与独立PG/VM容器。
Web5173/API8080/Worker8081/Runtime8090/PG15439/VM18428均只监听127.0.0.1，Zabbix沿用§62的18088。
启动时两个无独立后台进程的尝试随启动终端退出，修正后健康检查返回200；原生postgres.exe没有运行。
四应用AttachConsole均返回ERROR_INVALID_HANDLE（6），目标未分配控制台；未启动原生PG控制台。

真实连接暴露了此前协议桩遗漏：apiinfo.version带Authorization被Zabbix7.0.27以-32602拒绝，host.get countOutput实际返回字符串。
修复两类Connector版本探针为匿名请求；配置的secret仍先校验存在。Jackson传输器只允许apiinfo.version匿名，业务匿名请求在发送前拒绝。
countOutput接受规范非负十进制字符串或整型，并拒绝负数、浮点、符号、空白、前导零和Long溢出；既有信封/字节预算和无重试边界保留。
探针只证明端点可达及自报版本，不代替带凭据的业务读取权限验证。官方说明：https://www.zabbix.com/documentation/7.0/en/manual/api/reference/apiinfo/version

修复后自检reachable=true/version7.0.27；Host同步fetched1/accepted1、snapshotComplete=true、hostid-watermark-snapshot，
绑定zabbix-host-default@1及摘要sha256:d18f5f7c5ff7e847eefacaf1e03dc98b30d5046a3b3af7ca9294c66e028f0e06。
Item同步fetched3/accepted1/rejected2、snapshotComplete=true/itemid-watermark-snapshot；只映射CPU user，未映射的内存百分比/uptime保留拒绝。
本机tenant-demo/source zabbix-local实际资产和观测存PG。Worker只读Item50740历史，标注zabbix-jsonrpc，写VM并持久化checkpoint。
Worker首次因VM未设置1ms去重而CONFIGURATION_INVALID，补齐项目既定配置后启动成功；通过平台读回18个实际CPU点，AVAILABLE/fresh=true。
没有直写模拟指标、改写已有fixture标签或创建伪造告警。以上仅一个本地测试主机和一项CPU指标，不涵盖完整多页厂商扫描或M3告警链。

| 检查 | 本轮实测结果 |
|---|---|
| Java定向回归 | ZabbixJsonRpcConnectorIT 3项，0失败/错误/跳过；两类版本探针无认证头、业务匿名拒绝、计数字符串/无损边界、带认证Host同步 |
| Java打包 | platform-api与ingestion-worker bootJar通过，平台使用修复后Jar重启 |
| 契约 | 809 passed（24.69s） |
| 纯领域 | 1161断言、42个main，退出0 |
| Rust | fmt、默认41/all-features45，0失败/忽略；检查期间停止自有Runtime以释放Windows文件锁，结束后恢复 |
| Web | typecheck通过，Vite build82模块通过 |
| 实际浏览器 | 05:48:17Z Chromium→Vite→Java→PG/VM：资产详情含zabbix-jsonrpc、CPU曲线、刷新清Token、pageErrors=0；两张截图已遮蔽凭据并查看 |
| 本轮未执行 | Java/PG/VM全量测试、全部页面fixture套件、OIDC整链与真实模型诊断 |

运行产物见.tmp/local-preview/source-verification.json、series-verification.json和browser-verification.json，均为本机忽略文件。
环境是固定开发身份和本机开发数据库账号，模型显式mock；没有生产身份/TLS或模型费用验收。M0–M4未全部退出，MVP估算保持不变。
运行入口见[预览说明](runbooks/local-preview.md)。保留后台服务供用户查看，main未新建分支、未提交/推送。

## 64. 2026-09-27 真实本地 Zabbix 分页、三指标与告警闭环，以及控制台整理（追加）

用户要求按接入→告警→模型→身份的顺序继续，并改善UI；当前main工作区继续，未新建分支/提交/推送。
本地来源已可用；模型指定OpenCode/ds4.1flash，但API地址/正式模型ID尚待确认，未向猜测地址发送密钥、未调用真实模型。
真实IdP/TLS、部署代理与人工审阅仍未执行，不宣称100%。

### 真实来源发现与修正

实际item.get以limit=1、offset=0/1两次均返回50740，证明旧协议桩模拟的offset不受厂商支持。
Host/Item改为有界ID清单：count≤1000、ID-only limit1001、严格递增/无重复；每页核对方法和成员SHA-256/计数/位置，
使用hostids/itemids精确取最多500项，逐项核对，末页再读清单。一致才允许缺失对账，漏项/重复/等量成员替换拒绝。
旧游标格式拒绝；wire标签兼容，但不重写旧记录或将其追认新证明。不是字段事务快照，容量上限不是性能承诺。
规则与官方链接见[扫描契约](../contracts/host-scan.md)和[ADR-052](adr/052-zabbix-bounded-manifest.md)。

实际本地pageSize=1：原Agent主机10683加两个明确LOCAL TEST、无采集的分页测试对象10684/10685，
Host 3页/3项/accepted3/retired0，Item 3页/3项/accepted3/retired0，重复两类同步仍通过。
临时停止自有Zabbix Web容器，平台返回503/SOURCE_FETCH_FAILED/snapshotComplete=false，前后资产完全相同；finally恢复容器。
同数量成员替换、重复/漏项、非法/超限清单、非法游标、上游失败的负例为协议fixture与真实PG测试，未伪称厂商并发故障实测。

新增内存可用比例（0.01归一化，unit1/gauge）和uptime（seconds/gauge）映射文档1.0.0，保留CPU映射与发布Skill不变。
单Worker最多8流串行，每流沿用窗口/页数/超时/lease/fence，1流失败不阻止后续流，无轮内重试。
运维配置可固定每流streamName和initialFrom，保持已存检查点身份。开发启动器的动态initialFrom导致CONFIGURATION_INVALID，
依照实际已存名称/起点修正可信启动配置并补齐每流配置支持；未清除、改写或跳过旧检查点。
最终三项均由真实Agent→Zabbix历史→Java→Worker→VM→受权API读取，AVAILABLE/fresh=true/partial=false；
浏览器最后一次CPU86点、内存87点、uptime87点。数据仅为容器可见Linux/WSL值。

### 告警与持久化

创建名称带[LOCAL TEST]的规则，实际uptime>0触发，再把条件改为<0恢复；厂商事件23关联24。
不是业务故障或根因样例。平台首次导入创建1个Incident，重复导入created0/changed0；恢复导入changed1，再重复为0/0。
Incident 7dcf7c03-d3f3-3fae-97a2-e75ce731066c只有PROBLEM/RECOVERY各一条，关联资产5d1c2667-ead8-3995-8cd4-334da6347145；
日志/变更缺口保留，人工状态仍OPEN，不因来源恢复自动关闭。
平台进程重启后回读记录内容一致；Worker进程重启后3条检查点保留原名称/起点且completedThrough/revision均增加。
同轮移除Worker无用的默认交互用户，避免Spring Boot生成密码进入日志；最终启动日志复核无生成密码行。

### UI 与实际检查

控制台改为分组侧栏、图标/当前页指示、统一色彩/原生Zeus控件/表格/留白，窄屏采用可横向滚动的导航。
提供键盘跳到主要内容，不改变hash路由、凭据保存方式、显式读取或Fixture标记。1440px/390px截图已查看。
实际浏览器验证真实来源Incident恢复、三个指标曲线及刷新清Token；窄屏无整页水平溢出。

| 检查 | 本轮实际结果 |
|---|---|
| 契约 | 809 passed |
| 纯领域 | 1195断言、43个main通过，含Host/Item分页50项与新指标语义8项 |
| Java/PG/VM | 平台231、Worker24，合计255；0失败/错误/跳过。隔离数据库opsweave_checks_64，现有容器PG17.10/VM1.152.0 |
| Java打包 | 两个bootJar成功；Worker最终修改后24项及bootJar复跑成功 |
| Rust | fmt、默认41/all-features45，0失败/忽略；没有真实模型调用 |
| TypeScript / build | 类型检查与Vite build通过，82模块 |
| 页面测试 | 最终232 passed（2.2m），包含1440/390宽度导航、当前页、键盘聚焦与保留hash |
| 浏览器整链 | check_metrics_stack --pipeline --runtime：21组PASS，Java/PG/VM/Rust真实运行，来源labeled-fixture、模型mock |
| 身份整链 | check_oidc_stack --history-service：10组PASS，含独立Worker、撤权/失败不推进、轮换续采、双标签退出；IdP/来源仍协议fixture |
| 本地真实来源 | 多页/重复同步、来源断连保护、三项真实历史与浏览器查询、LOCAL TEST告警恢复/幂等/关联、PG回读与重启续采通过 |

失败尝试：第一次Java因VM变量名写错跳过4项，不计为全量通过，修正后零跳过复跑；首次Windows测试启动命令格式错误未执行，
改用Wrapper Main直接启动Java。UI首次类型检查失败已修正；初次侧栏用数组map导致Zeus只渲染文本，实际截图与5项页面失败发现，
改用For后232项复跑通过。旧预览脚本假定只有一个资产，新增测试对象后严格定位失败，改为目标行再通过。
初次OIDC缺显式PG容器参数失败，补齐自有容器后完整通过；最终Worker关闭默认用户后该整链再次复跑。
重启记录比较曾受Node REPL跨realm对象原型差异影响，改为递归键排序的内容比较后通过；未改变业务数据。
上述失败不算成功证据。

产物位于忽略目录.tmp/local-preview，详见[预览说明](runbooks/local-preview.md)；
完整模拟整链分别在脚本自有.tmp产物目录。预览业务数据与测试DB分开，所有应用/存储均loopback，未启动原生postgres.exe。
本地厂商接口实测不替代客户环境、真实模型、IdP/TLS和人工签署，原Fixture/Mock标记保持；不提前实施Copilot或自动修复。

收尾复核：check_repo.py通过（249个结构化文件、6个只读Tool定义），git diff --check通过；
Web、Platform、Worker与Runtime正确健康端点均HTTP 200。4个后台应用进程均无可见控制台窗口，
Windows原生postgres进程数为0；当前分支main，开发凭据文件仍被Git忽略。

## 65. 2026-09-27 OpenCode Go真实模型与持久诊断候选整链（追加）

用户给出https://opencode.ai/zen/go/v1并指定ds4.1flash；按官方接口表确认模型deepseek-v4.1-flash、Chat Completions协议。用户要求TLS后置，本机继续HTTP；外部模型连接仍使用提供方HTTPS。main未新建分支、未提交/推送。

### 实现

Runtime保持锁定Rig0.42.0，以OPSWEAVE_MODEL_API显式选择responses（默认）或chat-completions，无自动协议或模型fallback。新增Chat有界请求、平台会话UUID头、自己的User-Agent、JSON输出和关闭thinking。协议测试明确标记fixture，不修改发布Skill2.0.0/digest或业务Schema。

接收必须有可核验Token计数及相符total。在SDK归一化前检查原始响应，避免非法工具调用被SDK移除后留下表面合法内容。截断/拒绝/不支持输出先保留有效用量再拒绝草稿；契约、引用、时间/范围、费用、只读工具和保存流程不放宽。新增固定错误阶段/码，不记录模型正文。详见ADR-053。

### 两次真实调用与结果

| 显式调用 | 输入/输出Token | 平台估算USD | 结果 |
|---|---:|---:|---|
| f85d0d56-01f3-4ab4-9364-2b6a4439293b | 1894 / 454 | 0.001113 | S7 HTTP503、无AIInsight；账本REPORTED保留，工具会话used_calls=2 |
| 86eb67ef-983f-4160-a04f-3194393adfc2 | 1887 / 1272 | 0.002093 | S1–S7通过，PostgreSQL保存及回读成功，used_calls=4 |

首次调用返回有效用量，但未完成输出/引用之后的处理。当时缺少分段码，不能确认具体失败项，也不能声明已定位根因。补充不含正文的固定失败阶段诊断后，显式新候选验收成功；没有自动重试、清理费用或改变校验规则。两次共0.003206 USD为可信配置费率估算，并非提供方账单。单次准入0.03 USD、租户每日0.10 USD；峰值费率快照与实际Go额度/峰谷/缓存可能不同。不能由一个成功样本推断模型可靠性。

成功结果model={rig-openai,deepseek-v4.1-flash}，其中rig-openai表示现有兼容适配；实际调用网关是OpenCode Go。来源只有zabbix-jsonrpc，两类证据来自incident.get@2.0.0和metric.summary@2.0.0。CPU用户态指标357个真实样本，模型保留LOCAL TEST告警、OPEN与RECOVERED区别、日志/变更/历史摄入时间缺口，没有调用动作。数据是本地测试容器和受控测试规则，不是客户业务故障。

AIInsight、两类Evidence及费用在Platform/Runtime重启后内容一致。实际Chromium1440/390px页面可读取结果、两类证据和用量；刷新清Token后重新授权可回读，页面读取过程模型POST=0、pageErrors=0、手机无整页水平溢出。截图已遮蔽凭据并查看。

未认证诊断401、请求附加tenantId400、未认证结果读取401；对应探测runId没有费用预留（404）。这是开发身份边界实测，非真实IdP验收。

### 本轮实际检查

| 检查 | 结果 |
|---|---|
| 契约 | pytest tests/contracts：809通过，退出0 |
| 静态 | check_repo：249个结构化文件、6个只读Tool定义通过 |
| 纯领域 | 1195断言/43个main通过 |
| Java/PG/VM | Platform231+Worker24=255，0失败/错误/跳过；沿用隔离opsweave_checks_64和现有PG17.10/VM1.152.0容器 |
| Rust | 最终fmt、默认41/all-features49、clippy -D warnings、all-features build通过；含新增4个Chat协议边界测试 |
| TypeScript/build | tsc --noEmit与Vite82模块通过 |
| 浏览器fixture回归 | 232 passed（2.6m），不冒称真实提供方测试 |
| 实际本地来源+真实模型 | 候选执行包7/7，通过AIInsight/两类证据/用量回读及重启/浏览器验证 |
| 本轮未复跑 | 21组mock指标整链与10组OIDC协议fixture整链沿用§64，不标成本轮通过；真实IdP/代理、人审语料和提供方账单未验收 |

中间失败：首次Chat协议测试因Rig将文本序列化为数组而失败，修正纯文本数组解析后8个模型适配测试通过，最终Rust全量通过。一次构建因Runtime提前启动占用exe失败，核对PID/可执行路径停止自有进程后重建成功。首次真实验收失败单独保留，不计成功。

模型配置/密钥仅在忽略目录.tmp/local-preview，密钥只注入Runtime；四个应用日志均未出现该密钥或本次模型摘要。Windows原生postgres进程数0，四个后台应用无可见控制台。产物real-candidate-65-attempt1.json、real-candidate-65.json、model-validation-65.json、insight-65.json、insight-restart-65.json、real-browser-65.json、authorization-65.json、java-verification-65.json和截图均留在忽略目录。

报告模式仍为real-candidate，milestonesSatisfied=false；本机真实厂商与模型联调不等于生产M0–M4签署。真实IdP/资源部署授权、人工质量评估与账单核对继续未关闭，TLS按用户要求后置。原fixture/mock测试与页面标记严格保留，无Copilot、修复动作或新增生产服务。


## 66. 2026-09-27 参考 Shadcn Admin 的控制台视觉与导航改造（追加）

用户指定参考 [Shadcn Admin](https://github.com/satnaing/shadcn-admin)。已查看仓库与在线演示，参考其浅色侧栏、紧凑顶栏、卡片和表格层级，以现有Zeus及原生Web Components实现；没有引入React、shadcn运行库、额外服务或模板演示业务数据。main继续开发，未新建分支、未提交/推送。

### 本轮变化与边界

- 统一浅色/深色主题、细边框和控件层级；指标曲线有独立明暗配色，图表数据与计算不变。
- 桌面侧栏可收起，保留13个可访问名称和当前页指示；390px采用原生dialog导航抽屉，支持Esc、关闭后焦点回到触发按钮，窗口变宽时关闭抽屉。
- Ctrl/⌘+K页面搜索支持名称/路径筛选、方向键/Enter跳转、无结果状态和Esc关闭。仅搜索静态路由，不查询业务数据或触发模型。
- 资产页增加当前页总数、ACTIVE数、已记录来源实例数和其他生命周期数。未读取显示“—”，切换筛选/凭据清空统计；不标为全租户资产总数或健康统计。整理筛选、表格、分页与强标识定位/查询说明，来源行直接展示dataMode，Raw完整值保留于DOM/标题与详情。
- 仅主题偏好写入localStorage；开发Token继续仅保存在当前标签页内存。刷新/失权清空、显式读取、URL选择恢复、来源Fixture/Mock标记和服务器授权边界保留。

### 实际执行的检查

| 检查 | 本轮结果 |
|---|---|
| 契约 | pytest tests/contracts，809项，退出0 |
| 纯领域 | 43个main、1195断言通过 |
| Rust | fmt --check、默认41/all-features49通过，0失败/忽略；最终all-features build通过 |
| TypeScript/build | tsc --noEmit与Vite build通过，82模块 |
| 页面fixture回归 | 全量235 passed（2.3m）；最终仅图表配色调整后，指标/变化率/资产/导航31项再通过（26.9s） |
| 静态 | check_repo：249个结构化文件、6个只读Tool定义；git diff --check通过 |
| 浏览器布局 | 13路由×1440/390px共26次检查，无页面异常、无整页横向溢出；有数据的窄屏表格只在自身容器内滚动 |
| 实际本地数据页面 | PG读取3个已保存Zabbix资产（1 ACTIVE、2其他、1来源实例）；VM中CPU user曲线91个真实采样点；已有AIInsight、Incident/Metric两类证据和用量可回读，手机页面可浏览；针对持久读取的5次API请求均为GET、诊断POST为0。不是重新执行真实模型验收 |
| 服务 | Web、Platform、Worker、Runtime health/ready均200；四个后台应用MainWindowHandle为0，原生postgres.exe数量0 |
| 本轮未复跑 | Java/PG/VM集成测试、21组指标整链、10组OIDC协议fixture整链和真实模型候选执行包；既有结果见§64–65，不记为本轮执行。真实IdP/人工评估仍未关闭，TLS继续后置 |

初次类型检查发现dialog回调缺类型，修正后通过。Zeus的For内使用带局部语句的JSX回调未渲染搜索项，实际浏览器发现后改为独立组件。首轮全量226通过/9失败：7项由隐藏空alert导致、1项为布局改变后的直接子节点测试定位、1项为测试运行中重建dist造成的短暂HTTP失败；保留可访问alert、更新定位，停止并发重建后235项全量通过。
Rust首次因自有Runtime占用exe失败，核对PID和可执行路径后停止该进程，再跑默认/all-features及最终build通过，随后后台恢复；未停止其他服务。最后31项检查首次未取得浏览器路径，浏览器启动失败，改用显式环境的隐藏子进程后31项通过，未下载或更换浏览器版本。失败尝试不计成功。

截图、完整日志和ui-66-routes.json/ui-66-browser.json位于忽略目录.tmp/local-preview，截图遮蔽开发Token并已查看。公开文档不记录凭据、模型正文或客户内容。预览入口与操作见[本地预览](runbooks/local-preview.md)。本轮UI改造不提高M0–M4验收比例，不宣称100%。


## 67. 2026-09-27 接入工作台与内置/自定义模型的首版需求落档（追加）

用户明确要求数据源中心→配置抽屉→清洗转换画布→平台数据模型、实体关系管理及后续AI扩展位，并进一步确认首版支持自定义实体类型/字段/关系类型，同时先提供内置实体和指标。

本轮只修改设计与计划文档：新增[接入工作台设计](architecture/integration-studio.md)、[ADR-054](adr/054-integration-studio-model-catalog.md)，同步总体架构、ROADMAP、IMPLEMENTATION-STATUS、PROGRESS与MVP-CHECKLIST。定义内置Host/应用/服务/数据库/网络接口模型规划、已有三指标基线、自定义/租户扩展、来源实例配置、v2受限转换/画布、模型版本/预览发布、关系历史/授权，以及AI的后续建议Patch边界。OW-ST01–06均待开发，AI实际实现OW-ST07后置。

代码核查确认：当前PipelineDefinition v1及Java域固定6节点/5边；Entity的类型字符串和attributes不构成可配置模型校验；catalog为模块骨架，Relation仅有概念/表原型；来源仍由可信环境配置。新需求没有被记为已经实现。

实际检查：check_repo.py通过，249个结构化文件、6个只读Tool定义；7份设计/计划文件的55处本地Markdown链接均存在；git diff --check通过。本轮没有修改应用/领域代码、运行契约或数据库迁移，没有调用模型、重启服务或新建分支。契约/领域/Java/PG/VM/Rust/TypeScript/build/浏览器测试未复跑，最近代码验证仍见§64–66，不能计为此次新功能的通过证据。

原M0–M4真实身份/人工评估等退出项保持未完成；新增首版范围另列待办。历史约91%估算只对应旧范围，扩大后的首版未重新估算，本次文档不提高完成比例。


## 68. 2026-09-27 模型中心、默认清洗预览与来源版本策略（追加）

用户要求开始实现首版内置/自定义模型，并明确默认清洗规则与Zabbix版本适配。继续在main工作区开发，没有新建分支、提交或推送，保留此前未提交修改。

### 实现范围

- contracts/catalog中的opsweave-core@1.0.0提供5类实体、4类关系和3项已有指标定义；定义不冒充已采集对象。模型Wire契约、私有草稿/发布版本、清洗结果Schema与样例已补充。
- Java catalog纯领域提供六种标量字段、稳定字段标识/保留字段校验、关系端点固定版本、兼容修订和digest。第一次发布从revision=1开始；后续只允许连续版本、追加可选字段和修改展示说明；不覆盖已发布内容，不悄悄迁移旧资产。
- 默认safe-scalars-v1提供文本strip、严格数值/布尔/带时区时间转换、必填/null/空串区分、枚举/长度/数值范围与未知字段报告。仅有界手工样本预览，失败时保留问题说明；没有任意脚本、HTTP/SQL或AI执行。
- Platform新增/api/v1/catalog及V028模型草稿/版本表。读写复用entity.read/entity.manage并要求catalog:*资源范围；实体对象范围本身不能管理目录。tenant/subject来自可信Principal。草稿私有、CAS冲突409；发布绑定已保存editVersion/digest，在PG事务/租户锁内完成版本与端点复核。读取验证digest，PG异常无memory回退。64KiB请求、16KiB单定义、32字段、双列表各50项和truncated；SQL五秒超时。
- Zeus新增实体模型/指标定义/关系模型三个路由、内置卡片、字段/关系编辑抽屉、私有草稿、发布/下一版本、清洗样本和明确存储模式。16个路由保留原明暗主题与手机导航；会话变化清空私有定义、样本及旧响应。
- Zabbix现有apiinfo.version探测与Bearer Header保持不变。目录只记录先前本地实测7.0.27，其余版本标为UNVERIFIED；查看官方7.2变更确认auth属性移除。此次没有声称完成6.x/7.2/7.4兼容、运行新版本Zabbix或加入新的可执行版本门禁。

### 实际执行

| 检查 | 本轮结果 |
|---|---|
| 契约 | pytest tests/contracts：830项通过，退出0 |
| 纯领域 | 最终44个main、1250断言通过，含模型55项 |
| Java/PG/VM | 最终Platform238+Worker24=262，0失败/错误/跳过；真实本机既有PG17.10与VM1.152.0，隔离测试库opsweave_checks_64 |
| Rust | fmt --check、默认41/all-features49及最终all-features build通过；随后后台恢复Runtime；无新真实模型调用 |
| TypeScript/build | tsc --noEmit与Vite84模块通过 |
| 浏览器fixture回归 | 全量241项通过（2.4m）；最终仅样式间距调整后，模型/导航11项通过（17s） |
| 实际浏览器→Java→PG | 建立明确LOCAL TEST实体模型v1、关系类型v1；手工样本name去空格、port字符串转整数；私有保存→发布→刷新重新授权回读；实体追加可选字段发布v2，原关系仍指向实体v1；平台重启后三个版本及digest一致。页面错误0、诊断调用0 |
| 布局/截图 | 实体目录1440px与手机390px无整页水平溢出；手机编辑抽屉是真实modal、844px视口内滚动，截图已查看并遮蔽凭据。全页截图的固定层位置会受滚动偏移影响，使用实际视口截图复核 |
| 静态 | check_repo：256个结构化文件、6个只读Tool通过；实际目录响应通过契约Schema校验，9份文档68处本地链接存在；git diff --check通过 |
| 本轮未复跑/未完成 | 21组指标Mock整链、10组OIDC协议fixture整链、数据库角色脚本和真实模型候选执行包未复跑；真实IdP、人审语料、其他Zabbix版本仍未验收 |

初次领域检查有BigDecimal scale比较的测试断言错误，改为数值比较后通过；首次TS检查textarea rows类型错误已修复。浏览器首轮4失败/2通过来自fixture拦截glob未覆盖子路径，POST意外到真实API并被401拒绝；改为匹配固定catalog路径的regex后6项通过，再跑全量241通过。

初次Java/PG/VM执行时Docker未启动，真实存储连接失败，该次中止且不计通过。Docker启动又遇到两个残留AF_UNIX socket无法访问；核对Docker自有已崩溃进程及只含socket的临时目录，将Docker/run和docker-secrets-engine临时目录保留为.stale-20260927-68（run另有68b）再恢复，未重置Docker、删除卷或修改客户数据库。原有PG/VM/Zabbix容器恢复后重跑。第二次完整262项有1项失败，发现首次发布纳秒时间与PG微秒回读不一致；统一写入前时间精度，7项模型HTTP/PG复验通过，最后完整262项全通过。四个应用最终health/ready均200，Windows原生postgres进程数0。

OW-ST01仅关闭“模型定义/版本/样本清洗”子项。内置租户字段overlay、已发布类型约束下的实体实例写入/持久详情、来源中心配置抽屉、v2画布、关系实例历史/拓扑继续待实现；自定义指标编辑与规则编辑也未交付。模型中心不是完整接入工作台，不提高原M0–M4的100%退出声明。AI协助/自动修复均未实施，四个启动单元不变。

结果摘要与截图保存在忽略目录.tmp/local-preview/model-68-*；公开文档不记录凭据或模型正文。操作见[模型中心](runbooks/model-catalog.md)，API语义见[模型契约](../contracts/model-catalog.md)。


## 69. 2026-09-27 菜单信息架构与导航交互优化（追加）

用户要求继续优化菜单。在main现有工作区修改前端导航、路由元信息、样式及相关浏览器检查，未新建分支、提交或推送；没有新增业务API、契约Schema或数据库迁移。

现有16页按“数据接入→模型中心→资源观测→故障诊断→AI管理→开发演示”组织。分组标题可用鼠标/键盘收起，aria-expanded明确为字符串true/false；默认展开接入、模型、观测及当前组。搜索、直接链接和浏览器前进后退切换页面时自动展开目标组，顶栏显示实际分类。桌面图标模式保留全部16个带名称/提示的入口，手机抽屉使用独立分组状态；菜单滚动区与品牌/底部说明分离。组状态只在内存中，主题仍是唯一持久UI偏好。

Ctrl/⌘+K支持页面名、现有路径、分类与业务关键词，多个词同时匹配；结果附分类和用途说明。例如“技能”“Zabbix 采集”“模型中心 关系”。菜单与搜索共用路由元信息。Fixture演示独立成组且保留合成数据标签；菜单整理不构成授权，不新增来源中心、v2画布或关系实例的空入口。

| 检查 | 本轮结果 |
|---|---|
| 契约 | 现有Python3.11开发venv执行pytest tests/contracts，830项全部通过，退出0 |
| 纯领域 | javac21使用Windows参数文件编译，44个main/1250断言通过，含模型55项 |
| Rust | fmt --check、默认41/all-features49、最终all-features build通过；核对自有Runtime路径/PID后停下并后台恢复，无外部模型调用 |
| TypeScript/build | tsc --noEmit与Vite84模块构建通过 |
| 浏览器fixture回归 | 完整244项通过（2.8m）；最后分组标题样式调整后8项导航复验通过（12.5s） |
| 页面与截图 | 16路由×1440/390px共32次当前页/布局检查，无整页水平溢出、pageErrors=0、API请求=0；最终构建另经新页面加载并查看桌面浅色、手机深色截图 |
| 静态 | check_repo：256个结构化文件、6个只读Tool通过 |
| 预览服务 | Web、Platform、Worker及Runtime healthz/readyz最终均200 |
| 未复跑 | Java/PG/VM集成、指标Mock整链、OIDC协议fixture整链、数据库角色脚本及真实模型候选执行包，最近相应结果见§64–68，不计为本次执行 |

中间问题：首次构建因Zeus不支持组件展开属性而失败，改为显式属性后构建通过。首轮针对性浏览器31通过/2失败，定位到框架将false布尔aria属性移除，改用true/false字符串后8项导航和244项全量通过。初始全局Python无pytest；已有3.14环境启动等待被中止，未认定通过；改用已有3.11环境执行全部830项通过。领域原Python脚本超过Windows命令行长度，沿用参数文件编译和逐个main执行，全部通过。未安装新依赖或升级锁文件。

实际布局与健康摘要、截图保留在忽略目录.tmp/local-preview/menu-69-*，不含凭据。操作说明见[本地预览](runbooks/local-preview.md)。本次只改善菜单，不改变模型/来源能力、Fixture标识、可信会话边界或M0–M4退出状态。


## 70. 2026-09-27 原生数据工作流、受限转换画布与服务端发布门禁（追加）

用户确认“可以实现工作流”。按原生数据接入工作流第一段实现，继续在main当前工作区，保留已有修改；未新建分支、提交或推送。没有部署Dify、引入图形依赖或新增启动单元。范围和取舍见[ADR-055](adr/055-native-transform-workflows.md)，协议见[工作流契约](../contracts/workflows.md)。

### 已实现和未启用的边界

- Java纯领域v2定义及执行器：4到16节点单条主链，SOURCE/MAP/六种可配置清洗/VALIDATE/OUTPUT。TRIM、EMPTY_TO_NULL、DEFAULT、ENUM_MAP、SCALE、FILTER均为确定性内置操作；模型校验复用safe-scalars-v1，固定内置或已发布自定义ENTITY模型id/revision/digest。非法图、版本、目标、字段、数值、样本及超预算失败关闭。
- V029新增PG工作流草稿、不可变版本和运行回执表，现有连接池/事务所有权不变。可信tenant/subject隔离、source.sync/workflow:*、entity.read/catalog:*及已有来源/每实体范围检查；CAS、租户事务锁、私有草稿、连续发布、模型pin和服务端预览15分钟门禁。布局不进入语义digest，语义变更清除预览；发布后版本从活动草稿列表排除，原内部草稿用于幂等发布确认。
- Web新增第17个路由#/integrations/workflows。三栏画布、节点增删/排序、拖动及键盘移动、缩放/排列/撤销重做、目标模型/字段映射、样本输入、逐节点值/问题、草稿/固定版本/运行记录、手机布局及AI禁用入口。会话改变立即中止请求、清空私有数据和样本，不把草稿或样本写浏览器持久存储。
- 来源支持手工样本与当前配置Zabbix已有Host保留批次；后者经过v1映射和每实体授权，只提供name/ip/lifecycle/entity_id。未增加实时采集、任意厂商JSON访问或其他Zabbix版本兼容声明。origin、失败批次、missingRaw、truncated均保留；fixture没有改称真实来源。
- 所有预览和版本测试均dryRun=true、writesPerformed=false；运行记录只存定义/输入摘要、来源、计数与时间，不保存样本正文或逐步输出。来源配置抽屉、多实例、实例/关系写入、自动启用绑定、分支/循环/脚本、模型协助和修复动作仍未实现；本段不是完整Dify替代或持久调度引擎。

### 实际运行结果

| 检查 | 本轮结果 |
|---|---|
| 契约 | 已有Python3.11开发venv运行pytest tests/contracts：849项，退出0；新增19项闭集定义/算子/预算负例 |
| 纯领域 | javac21参数文件编译，最终45个main/1314断言通过，其中WorkflowSmoke64项；覆盖缺失/null、过滤与拒绝、转换、版本/身份隔离、过期/未来回执、并发修改使旧预览失效、字节预算 |
| Java/PG/VM | 最终完整Platform244+Worker24=268，85个suite，0失败/错误/跳过；既有本机PG17.10、VM1.152.0和隔离库opsweave_checks_64。新增6项HTTP/PG工作流集成，含真实持久化、重开、并发CAS单赢家、回滚、损坏拒绝、请求身份覆盖/重复键/尾随JSON/超限/不可写/来源错配 |
| Rust | fmt --check、默认41/all-features49通过，最终all-features build通过；核对自有Runtime路径/PID后停止并后台恢复，未调用真实模型 |
| TypeScript/build | tsc --noEmit与Vite86模块构建通过 |
| 浏览器fixture | 全量250项通过（2.7m）；最终来源状态校验/发布后草稿列表调整后14项工作流与导航复验通过（30.2s） |
| 实际Java/PG浏览器 | 内置Service手工Fixture去空格/port转整数，保存→预览→发布→版本测试；自定义custom.local_web_service@2字段service_name→name映射、默认清洗→发布→刷新模型pin/空样本/活动草稿排除均通过。LOCALTEST标记明确 |
| 本地真实来源保留批次 | 原本地Zabbix7.0.27的Host批次df87fe48-d2c5-4fa0-aed2-7ac4ccd32699，3条转换通过，origin=zabbix-jsonrpc、SUCCEEDED、无缺Raw；浏览器保存/预览/发布/版本测试/刷新回读通过。是既有Raw批次转换，没有新调用Zabbix API或创建新来源数据，不冒称新厂商采集验收 |
| 页面与Schema | 桌面1500px、手机390px交互和无整页横向溢出通过；SVG连线及目标模型真实选中复核，截图已查看。3类实际HTTP响应page/entry/result经v2 Schema校验通过 |
| 静态 | check_repo：262个结构化文件、6个只读Tool；git diff --check通过 |
| 本轮未复跑 | 独立数据库运行角色脚本、21组指标Mock整链、10组OIDC协议fixture整链、真实模型候选执行包；真实IdP、人审评估、其他Zabbix版本和生产/分布式部署仍未验收 |

实际命令入口：node .tmp/local-preview/menu-69-checks.cjs contracts（复用既有开发venv）、node .tmp/local-preview/menu-69-domain.cjs（Windows javac参数文件）、node .tmp/local-preview/check.cjs java（含--rerun-tasks）、node .tmp/rust-check.cjs、cargo build --workspace --all-features --locked -j 1、pnpm --filter @opsweave/web-console build，以及Playwright全量/最终workflows.spec.ts navigation.spec.ts。领域与契约代码、Java/浏览器测试均在仓库；本机凭据读取包装脚本留在忽略目录，不提交秘密。

中间问题均未计为成功：最初Java Result缺少missingRaw参数、TS运行模式推导为string，修正后构建通过；领域测试枚举字段遗漏maxLength，按真实模型约束补齐后通过。实际浏览器发现Zeus混合动态文本生成错误导致运行记录DOM崩溃、目标select先于选项赋值显示错误、独立path未进入SVG命名空间；合并文本表达式、选项prop:selected与SVG包装修复后，实际链与完整回归通过。首次平台重启期间读取返回502，等待健康恢复后显式重新读取成功，没有业务静默重试。自定义浏览器脚本在已完成发布后的刷新读取阶段遇到Chromium Network.getResponseBody回执读取错误；先核对PG已经发布，避免重复创建，再用现有版本独立复核刷新/模型选择/草稿排除成功。该脚本失败不冒充一次完整通过。

实际结果/截图保存于忽略目录.tmp/workflow-*，内容仅本地测试数据，Token在截图中为密码掩码；没有客户模型正文、凭据或Authorization进入公共文档。受限运行角色授权SQL已增加三表权限，但本轮只在所有者迁移/本机开发连接及测试库验证，角色脚本未执行，不宣称新的角色验收。

本次推进OW-ST03/04的只读工作流子项。OW-ST01实例存储/租户扩展、OW-ST02来源中心/抽屉、OW-ST05关系实例和OW-ST06新首版整链仍缺，不标为100%；原M0–M4真实身份和人工评估退出状态不变。操作见[工作流说明](runbooks/workflows.md)。

最终服务核对：Web/Platform/Worker/Runtime healthz及readyz均200；四个应用进程MainWindowHandle均0，Windows原生postgres进程数0。10份相关文档的187处本地链接均存在。Codex工作流浏览器面板打开请求已入队。

## 71. 2026-09-28 数据源类型选择、配置抽屉与画布衔接（追加）

用户确认补做数据源选择入口。继续main当前工作区，未新建分支/提交/推送，保留已有修改。范围见[ADR-056](adr/056-source-center-onboarding.md)、[数据源配置契约](../contracts/source-setups.md)与[操作说明](runbooks/source-center.md)。

### 实现范围

- 新增第18个页面#/integrations/sources，置于数据接入侧栏首项。Zabbix、JSON手工样本类型卡片，CMDB卡片明确跳转已有快照导入，尚无画布配置。配置抽屉设置名称/说明/固定初始实体模型，Zabbix展示经来源授权的当前平台连接地址及凭据引用，并提供显式连接自检。
- 确认由Java纯领域SourceSetupService在同一个WorkflowStore事务保存私有接入回执和第一版草稿；V030新表，租户/主体隔离、请求UUID幂等、配置摘要和模型pin校验、每主体200份配置/50份活动草稿上限、最近20份列表截断。既有模型、连接失败无Mock回退。
- 保存创建时fixture/zabbix-jsonrpc/MANUAL_SAMPLE标记并计入摘要，避免平台配置变化掩盖旧Fixture。默认SOURCE→MAP→TRIM→VALIDATE→OUTPUT；抽屉确认不采集、不执行工作流/模型、不写实体。
- 画布URL仅携带id/revision/state，读取仍受可信会话控制；异常参数拒绝。配置的继续编排打开初版草稿或其固定发布版本，后续版本显式管理。会话切换中止请求并清空私有数据；未知确认结果保留原命令，供按UUID查询/显式原样重试。
- 这是一条平台已配置Zabbix连接的选择入口；多个接入方案不等于多个连接。多实例新URL/凭据编辑、来源配置版本生命周期、自动采集绑定、模型实例/关系输出和AI协助尚缺。回执是不可变创建快照，不自动随之后的工作流编辑更新。凭据引用摘要不覆盖密钥值轮换。

### 实际运行结果

| 检查 | 本轮实际结果 |
|---|---|
| 契约 | 已有Python3.11 venv执行pytest tests/contracts，864项全部通过；独立收集复核数量864，退出0 |
| 纯领域 | javac21参数文件编译与46个main全部通过；SourceSetupSmoke验证初稿、幂等冲突、主体/租户/范围隔离、配置变化、模型pin、发布后回读及截断 |
| Java/PG/VM | 最终完整Platform250+Worker24=274，87个suite，0失败/错误/跳过；本机PG17.10、VM1.152.0和隔离测试库opsweave_checks_64。新增HTTP/PG共6项，含重开查询、4路并发幂等、两表共同回滚、请求身份/连接覆盖、重复键/尾随JSON/超限、Fixture描述符 |
| Rust | fmt --check、默认41/all-features49、最终all-features build均通过；自有Runtime按PID/路径核对后后台恢复，没有模型调用 |
| TypeScript/build | tsc --noEmit及Vite89模块生产构建通过 |
| 浏览器fixture | 初次来源中心/工作流12项通过；最终全量256项通过（3.1m），包括18路由、桌面/手机、明确测试连接、Fixture标记、固定UUID重试、私有配置清理与异常深链接 |
| 实际Java/PG浏览器 | 创建并回读两份LOCALTEST配置：MANUAL_SAMPLE→custom.local_web_service@2、zabbix-jsonrpc→builtin.host@1；抽屉确认直接打开对应5节点已保存草稿，刷新后重新授权读取同一工作流，preview仍null，无隐式执行 |
| 本地来源 | 页面显式调用既有connection-check接口，本地Zabbix实际报告7.0.27、zabbix-jsonrpc/ok；这是连通性探测，不冒称新Host/Item采集、版本全覆盖或生产验收 |
| Schema/视觉 | 3份实际page/confirmed HTTP响应通过v1/v2 Schema校验；1500px桌面、390px手机与抽屉交互，无整页横向溢出，截图已查看，零页面JS错误 |
| 静态 | check_repo：267个结构化文件、6个只读Tool；git diff --check通过 |
| 本轮未复跑 | 数据库受限角色脚本、独立指标Mock整链、OIDC协议fixture整链、真实模型候选执行包。真实IdP、人审评估、其他Zabbix版本、生产/分布式部署仍未验收 |

实际命令入口：node .tmp/local-preview/menu-69-checks.cjs contracts、node .tmp/local-preview/menu-69-domain.cjs、node .tmp/local-preview/check.cjs java（--rerun-tasks）、node .tmp/rust-check.cjs、cargo build --workspace --all-features --locked -j 1、Web build与Playwright全量。来源专项Java/PG、浏览器脚本和实际响应Schema校验也已执行。开发凭据仍只由忽略目录本地包装读取，不打印/提交。

中间失败如实记录：新增PG测试首次编译因assertTrue泛型重载推导失败，显式booleanValue后修正；首轮6项Java测试因V030未打包进processResources而失败，补入迁移资源后专项及最终完整274项通过。中间失败不计为通过。

实际页面产物、响应和截图保留在忽略目录.tmp/source-center-*，仅LOCALTEST数据，Token截图为密码掩码；没有客户模型内容或凭据写入文档。接入配置ID分别为f6af23e8-3e15-4db7-bd86-94d8a1cf8842（手工）和f1c45546-cf3b-497d-ab5c-1804dd9493a7（本地Zabbix），可从我的接入配置继续编排。

最终服务Web/Platform/Worker/Runtime healthz及readyz均200。四个自有应用MainWindowHandle均0，Windows原生postgres进程数0。受限平台运行角色的新表SELECT/INSERT授权SQL已提供，但本轮未执行角色复验脚本。原M0–M4和扩大首版范围不标为100%；当前完成OW-ST02的选择/确认/画布衔接子项。

## 72. 2026-09-28 创建入口会话引导与本地服务恢复（追加）

实际浏览器发现平台开发Token为空，旧来源中心同时依赖会话和先手动读取目录，置灰没有说明。来源卡片现在可点击：缺少会话时说明原因、滚动并聚焦现有Token输入；有会话时有界GET读取授权目录后打开配置抽屉，无需额外手动读取。工作流页增加同样的缺少会话说明。请求仍经原可信会话边界；未认证不发API请求，会话变化仍中断请求并清空私有目录/抽屉，没有持久保存Token或放宽服务端权限。

| 检查 | 本轮实际结果 |
|---|---|
| 契约/纯领域 | 864项契约通过；javac21编译与46个main通过 |
| Rust | fmt --check、默认41/all-features49以及all-features build --locked通过 |
| TypeScript/build | tsc --noEmit、Vite89模块生产构建通过 |
| 浏览器fixture | source-center、workflows、platform-session共19项通过（1.1m）；新增首次直接点击只GET不POST、缺少Token不发API并显示引导的回归 |
| 真实本地API | GET /api/v1/integrations/sources返回200、storage=postgres、7个模型、2份既有接入配置；Zabbix AVAILABLE/zabbix-jsonrpc、手工样本AVAILABLE/MANUAL_SAMPLE，CMDB LEGACY_IMPORT |
| 内置浏览器 | 缺少会话点击配置后聚焦Token并说明原因；填入本机原有devToken后，首次点击Zabbix直接打开配置抽屉、builtin.host@1选中、确认按钮可用；未点击确认创建或连接测试 |
| 环境 | 原Docker容器恢复；Web5173、Platform8080、Worker8081、Runtime8090 healthz/readyz、Zabbix18088、VM18428健康HTTP均200，PG容器healthy |
| 静态 | check_repo通过：267个结构化文件、6个只读Tool；git diff --check通过，当前分支main |
| 未复跑 | Java/PG/VM集成测试、全量浏览器、独立OIDC/模型验收；第71节274项Java和256项浏览器属于前轮结果，不计作本轮 |

开发检查入口与第71节相同，浏览器仅执行三个相关spec。新增交互不修改契约/领域/服务端逻辑。没有新建接入记录、没有显式来源扫描/连接探测、没有模型调用；恢复原Worker后已有配置可按原调度采样，本轮未对其新采样结果作验收。

环境中间失败：初始本地应用与Docker引擎停止。后台启动Web后，平台因PG不可用未健康；Docker4.77.0先后因run/dockerInference与docker-secrets-engine/engine.sock报Windows错误1920，正常重启及单套接字重命名失败，均未计成功。确认本次失败进程路径后结束，保留并改名两个仅含临时套接字的目录，再建立空运行目录，引擎及原容器恢复。保留目录为本机AppData/Local/Docker/run.stale-20260928-0131、run.stale-20260928-0134及AppData/Local/docker-secrets-engine.stale-20260928-0134；未工厂重置、未删除镜像/卷/数据库或更改权限。故障现象与[Docker官方仓库问题625](https://github.com/docker/desktop-feedback/issues/625)一致，本机恢复结果以上述实测为准，不声称永久修复Windows套接字问题。

旧内置浏览器页在服务停止时刷新进入连接失败页；浏览器接口无法操作该data URL，使用同一浏览器新标签验证本地页面，原标签未关闭。截图保留.tmp/source-create-ready-72.png，无密钥明文。继续main当前工作区，未新建分支/提交/推送；MVP真实身份/人工评估等退出条件不改变。

## 73. 2026-09-28 工作流逐条运行记录与节点失败原因（追加）

用户要求流水线能够保留哪些数据解析成功、哪些失败的记录。继续main当前工作区并保留原修改，未新建分支、提交或推送。范围见[ADR-057](adr/057-workflow-run-traces.md)、[工作流契约](../contracts/workflows.md)与[运行记录说明](runbooks/workflows.md)。

### 实现范围

- Java纯领域新增有界WorkflowTrace：每条输入的ACCEPTED/REJECTED/FILTERED、各节点OK/ERROR/FILTERED/SKIPPED、字段/封闭错误码、来源和目标模型pin、开始时间/耗时、来源批次及完整性标记。最多5条样本、16节点；校验输入顺序、相同拓扑、停止后的跳过状态及与回执数量一致性。
- 运行明细与回执沿用原PG事务原子保存，存于现有JSON，不新增表或迁移。GET /api/v1/integrations/workflows/runs/{runId}按可信tenant/subject及现有权限读取，跨主体/租户或未知记录返回404；禁用任意查询参数。原列表只返回原摘要字段，不泄露完整trace。旧JSON没有trace时返回null，明确未留存，不编造明细或重新执行。
- 新增第19个页面#/integrations/workflows/runs，侧栏入口“数据接入→工作流运行记录”。最近20条摘要、结果筛选、运行UUID回查、节点统计与逐条折叠明细；失败行默认展开，字段错误码翻译中文，后续未执行节点显示原因。画布回执提供直接链接。每主体原200条上限保持。切换会话立即清除私有数据并中止旧请求。
- 持久trace只保存元数据：不保存原始样本、输出正文、节点配置、工作流名称或任意异常消息。fixture/MANUAL_SAMPLE/zabbix-jsonrpc标记保留；转换全部成功不等于来源完整、实体落库或生产采集成功，来源失败/截断/缺Raw单独提示。
- 本次覆盖v2只读预览和发布版本测试。旧Host/CMDB历史继续使用原页面；未实现统一来源执行日志、后台调度、实体写入或AI协助。认证/参数拒绝、采样端口异常、容量拒绝及事务失败仍按原HTTP失败返回，未新增其持久失败审计。

### 实际运行结果

| 检查 | 本轮实际结果 |
|---|---|
| 契约 | pytest tests/contracts共874项通过；独立collect-only复核数量，新增明细样例/旧trace=null及非法状态、错误字段和额外值拒绝 |
| 纯领域 | javac21编译与46个main全部通过；WorkflowSmoke最终77项，含混合结果、元数据剥离、身份隔离及trace一致性 |
| Java/PG/VM | 完整Platform252+Worker24=276，87个suite，0失败/错误/跳过；既有本机PG17.10、VM1.152.0与隔离库opsweave_checks_64，--rerun-tasks，BUILD SUCCESSFUL。新增PG重开持久回查、跨主体/租户隔离、回滚、旧记录及损坏计数拒绝；HTTP详情/无缓存/不含正文、未认证、未知UUID与身份查询覆盖拒绝 |
| Rust | fmt --check、默认41/all-features49全部通过；all-features build --workspace --locked -j 1通过 |
| TypeScript/build | tsc --noEmit与Vite91模块生产构建通过 |
| 浏览器fixture | workflow-runs、workflows、navigation、source-center、platform-session最终33项通过（33.6s）；包含1500px/390px、1成功/1失败/1过滤、旧回执、损坏明细拒绝、来源完整性警示、刷新只GET、会话变化丢弃迟到响应；本轮没有重跑全量浏览器 |
| 实际本地Java/PG | 新建一份明确标记[LOCAL TEST / Fixture]的手工样本草稿local-test-run-records并预览，3条输入得到1成功/1失败/1过滤；运行ID57e35df8-053e-489a-8529-6a5d70099336，MANUAL_SAMPLE、dryRun=true、writesPerformed=false；详情实际从PG回读，未保存无效端口样本文字或配置 |
| 实际浏览器/Schema | 内置浏览器查看该回执，port字段TYPE_MISMATCH、输出SKIPPED及过滤节点统计可见；真实刷新后重新授权GET仍能回查同一记录。实际result/detail响应均通过仓库v2 Schema校验；截图已查看 |
| 静态与服务 | check_repo：269个结构化文件、6个只读Tool；git diff --check通过。更新Platform jar并后台恢复自有Runtime，Web/Platform/Worker/Runtime healthz及readyz均200；复用原Docker依赖，无新PG窗口/服务 |
| 本轮未复跑 | 全量浏览器、数据库受限运行角色脚本、独立指标Mock整链、OIDC协议fixture整链、真实模型候选执行包；真实IdP、人审评估、其他Zabbix版本及生产部署仍未验收 |

实际命令入口：node .tmp/local-preview/menu-69-checks.cjs contracts、node .tmp/local-preview/menu-69-domain.cjs、node .tmp/local-preview/check.cjs java、node .tmp/rust-check.cjs、cargo build --workspace --all-features --locked -j 1、pnpm --filter @opsweave/web-console build，以及node .tmp/local-preview/menu-69-checks.cjs browser workflow-runs.spec.ts workflows.spec.ts navigation.spec.ts source-center.spec.ts platform-session.spec.ts。实际HTTP响应通过.tmp/workflow-run-73-schema.py校验。凭据仍由忽略目录包装读取，不打印或提交。

中间失败如实记录：Rust首次构建因运行中的本项目Runtime锁定exe报Windows os error 5，核实PID/路径后停止该进程，最终检查通过并隐藏恢复。浏览器首轮27通过/6失败，原因是新增mock glob没有匹配嵌套明细URL，修正后31通过/2失败；随后定位Zeus For的块体回调未渲染节点组件，提取NodeStat/StepRow组件后最终33项全部通过。中间结果不计为通过。

本轮只有上述LOCALTEST手工转换，没有触发外部模型、Zabbix连接探测或手动来源扫描；原Worker继续已有配置的只读采样，其本轮新采样没有另作验收。实际样例响应与截图保留在忽略目录.tmp/workflow-run-73-{detail,result}.json、.tmp/workflow-run-73-ui.png，无凭据明文或客户原始日志。M0–M4真实身份/人工评估退出项不改变，不声称MVP100%或统一全量流水线审计已完成。

## 74. 2026-09-28 X6工作流、G6关系图与任务导航（追加）

按用户要求在main当前工作区完成画布引擎替换、关系展示、页面排版和菜单用途说明；保留既有修改，未新建分支、提交或推送。范围与边界见[ADR-058](adr/058-antv-workspace-navigation.md)、[操作说明](runbooks/graphs-and-navigation.md)和[只读关系契约](../contracts/entity-topology.md)。

### 本轮实现

- 固定安装AntV X6 3.1.8与G6 5.1.1，使用真实pnpm锁文件。引擎动态导入并隔离在TypeScript适配层，不把引擎JSON作为跨语言协议，不增加启动单元。X6提供节点拖动/键盘移动、空白平移、缩放、对齐、适应；沿用原布局、撤销/保存/预览/发布/只读版本测试与运行记录。普通滚轮滚动页面，Ctrl/⌘+滚轮缩放。执行仍为受控单链，没有分支、并行、循环或任意连线。
- G6分别展示授权模型目录中的关系类型及真实平台中已保存的一跳实例关系。新GET /api/v1/entities/{id}/topology使用可信身份、entity.read、tenant及双端点对象权限，在过滤之后限制50条关系/51节点，服务器当前asOf覆盖有效区间。PG只读REPEATABLE READ快照；无权限/未知中心404、参数覆盖400、存储失败503，no-store。无可用关系返回明确空图，memory模式不可用，无Mock回退。
- V031补入正式迁移链与打包资源，创建原先仅在未应用原型SQL中的inventory.entity_relation并补data_mode；不插入模拟关系，不实现关系写入接口或采集器。受限运行角色SQL只增加该表SELECT。Fixture、Zabbix、导入、unknown及截断独立标示；source_ref不出API。
- 默认首页为开始使用，提供接入→编排→运行结果和任务入口；21条路径保留导航/搜索。Host采集维护、CMDB快照导入、资产绑定纠错移入接入维护；Incident/Skill/Agent改用中文任务名称。当前分组自动展开，非当前低频分组默认收起，每页都有用途、步骤和相关入口。调整侧栏、标题、间距、卡片、表格、画布与手机/明暗主题。

### 实际验证

| 检查 | 本轮实际结果 |
|---|---|
| 契约 | pytest tests/contracts退出0；独立collect-only核对885项。新增一跳图样例和非法tenant/模式/数量/额外字段等拒绝 |
| 纯领域 | javac21编译与47个main通过；新增EntityTopologySmoke15项，含对象权限、跨租户、端点、边界与有效时间 |
| Java/PG/VM | 完整Platform255+Worker24=279，89个suite，0失败/错误/跳过，--rerun-tasks，BUILD SUCCESSFUL；复用本机PG/VM及隔离库opsweave_checks_64。新增3项HTTP/PG，验证55条隐藏边不占可见上限、50条截断、空邻域、过期/未来关系、无缓存和请求边界 |
| Rust | fmt --check、默认41与all-features49全部通过；all-features build --workspace --locked -j 1通过，自有Runtime隐藏恢复 |
| TypeScript/build | tsc --noEmit、Vite2339模块生产构建通过。X6约586KB、G6约1.4MB的独立懒加载块仍有大包警告；没有隐藏告警或宣称首载/大图性能达标 |
| 浏览器fixture | 全量269项通过（3.2m）；人工发现普通滚轮误平移后修正，最终workflows/graph-workspace/navigation共19项再通过（37.3s），包含1500/390px、拖动及键盘布局保存、普通滚轮不改变图内坐标、明暗模式、会话清理、重读目录/离开页面清理、损坏端点拒绝与旧导航 |
| 实际本地API | 更新平台bootJar并隐藏重启，GET资产列表200/storage=postgres/3项；新关系GET200/no-store/1节点0边/Zabbix来源，实际响应通过v1 Schema。本轮不新增资产或关系，不推断连线 |
| 实际内置浏览器 | 读取既有LOCALTEST草稿local-test-run-records，5节点X6渲染并适应视图；读取实际PG目录，G6显示7个实体版本节点/5个关系定义（含原LOCALTEST定义）；选择OpsWeave本地测试容器，显示0关系和缺失说明。均为读取，没有触发预览、采集、发布或模型 |
| 静态 | check_repo通过：270个结构化文件、6个只读Tool；git diff --check通过 |
| 未复跑 | 数据库受限运行角色脚本、独立OIDC协议fixture整链、独立指标Mock整链、真实模型候选验收包。真实IdP/生产TLS/人工评估、其他Zabbix版本和生产/分布式部署仍未验收 |

命令入口：node .tmp/local-preview/menu-69-checks.cjs contracts、.tmp/mvp-check-venv/Scripts/python.exe -X utf8 .tmp/local-preview/contracts-74-count.py、node .tmp/local-preview/menu-69-domain.cjs、node .tmp/local-preview/check.cjs java、node .tmp/rust-check.cjs、cargo build --workspace --all-features --locked -j 1、node .tmp/local-preview/menu-69-checks.cjs browser以及最终三个spec；浏览器配置执行tsc/Vite生产构建。实际响应由.tmp/workspace-74-schema.py校验。开发凭据从忽略目录读取，不进入普通日志/文档或URL。

中间失败如实保留：初次TypeScript检查因G6联合事件类型直接取target失败，按类型缩窄后通过；新增PG样例首次漏必填last_seen_epoch_nanos，补齐后又发现投影视图重复别名，修正后完整279项通过。首轮相关浏览器26通过/7失败，包含根路由仍指旧演示、旧标题/选择器和资产图清空时读null；修复后27项通过。首次全量266通过/3失败均为旧菜单/未限定summary定位，更新为实际新导航后269通过。人工检查另发现关系模型图清空catalog时残留旧页，补空值处理与重读/会话/导航回归；最终专项也通过。上述中间结果不计作最终通过。

本轮不使用真实模型，不手动触发来源扫描；原Worker继续已有配置，其新采样未另作验收。截图与实际JSON位于忽略目录.tmp/workspace-74-*，仅本地开发/LOCALTEST记录，凭据为密码掩码。关系实例创建/编辑/导入、来源工作流自动启用、AI协助、分支并行仍未实现；不能将关系类型图当成真实资产连线，不能把本轮UI与本机数据库验证计为M0–M4全部退出或生产验收。

最终服务检查：Web5173、Platform8080、Worker8081、Runtime8090 healthz/readyz均HTTP200；四个自有应用MainWindowHandle均0，Windows原生postgres进程数0。继续复用Docker数据库，没有新建PG窗口。

## 75. 2026-09-28 React 与 shadcn/ui 控制台（追加）

按用户要求把 Web 控制台从 Zeus 迁到 React 与 shadcn/ui，依赖使用 2026-09-28 查询到的当前稳定版本。未新建分支、未提交、未推送。边界见 [ADR-059](adr/059-react-shadcn-console.md)。ADR-012 标记为已被替代。

### 本轮实现

- 移除 `@zeus-js/zeus` 与 Zeus UI。运行时为 React 19.3.0 / react-dom 19.3.0；类型来自 `@types/react` 与 `@types/react-dom` 19.3.0。构建为 Vite 8.3.1、`@vitejs/plugin-react` 6.1.1、TypeScript 7.0.2（路径别名不再使用已删除的 `baseUrl`）。样式为 Tailwind CSS 4.3.3 与 `@tailwindcss/vite` 4.3.3，shadcn 4.21.0 的 `base-nova` 令牌写在 `src/styles/globals.css`。组件依赖 `radix-ui` 1.6.7、`class-variance-authority` 0.7.1、`cn` 0.4.0、`tw-animate-css` 1.4.0、`lucide-react` 1.48.0。Playwright 为 `@playwright/test` 1.63.0。`@antv/x6` 3.1.8 与 `@antv/g6` 5.1.1 版本未改。
- 按钮、单行输入、多行文本和徽章复制为 `src/components/ui`。页面按钮走 `data-slot="button"`，原有页面样式不再压平这些按钮。下拉框、复选框和 `<dialog>` 保持原生，以便既有选择、取值和无障碍名称继续可用。
- 原布局变量改为 `--ow-*`，与 shadcn 的 `--primary` / `--muted` / `--accent` 分开。明暗主题同时写 `documentElement.dataset.theme` 和 `dark` class。哈希路由、内存开发 Token、分组导航、页面搜索和 X6/G6 适配层保留；图引擎不包成 React 节点。

### 实际验证

| 检查 | 本轮实际结果 |
|---|---|
| 依赖安装 | 仓库根目录 `pnpm install` 通过，`pnpm-lock.yaml` 已更新 |
| TypeScript | `apps/web-console` 中 `pnpm exec tsc --noEmit` 退出 0 |
| 生产构建 | `pnpm exec vite build` 退出 0。Vite 8.3.1，2434 个模块；CSS 约 84.94 kB，主包约 590.71 kB。构建警告仍指出存在超过 500 kB 的块（含 X6/G6 动态块量级）。不把该体积当作性能达标 |
| 内置浏览器 | `vite --host 127.0.0.1 --port 5173`。`#/start` 显示开始使用。从开始页进入 `#/inventory`：原生生命周期/类型下拉仍在；填入 32 位无空白开发 Token 后「清除开发会话」「刷新列表」「同步 Zabbix Host」变为可用，清除后再次禁用。主题按钮在深色/浅色间切换，深色时 `dataset.theme=dark` 且根节点有 `dark`。搜索「数据工作流」只保留一条并打开 `#/integrations/workflows`。桌面「切换导航」收起后再展开，分组按钮恢复。数据源中心在平台 8080 未监听时显示「数据源请求失败（HTTP 502）」，Vite 日志为 `ECONNREFUSED 127.0.0.1:8080`，页面没有当成成功。`#/modeling/relations` 显示空目录说明。390×844 下桌面导航隐藏，「切换导航」打开「关闭导航」抽屉，当前项为关系模型，关闭后回到页面。页面没有 Vite 错误遮罩 |
| 未复跑 | Playwright、契约 pytest、Java 领域/PG/VM、Rust 默认与 all-features。未启动平台，因此没有用真实草稿渲染 X6，也没有用真实目录渲染 G6。拖动、缩放和键盘移动画布本轮未操作 |

原 M0–M4 退出项不改变。Agent 控制台、聊天组件和生产 OIDC 仍未接入。开发预览只在 127.0.0.1。

## 76. 2026-10-01 Go 跨平台开发服务管理器（追加）

按用户要求实现交互式前后端管理，并将实现语言从最初的 TypeScript 草案改为 Go。Go 是开发工具，不加入平台领域层、不替代 Java/Rust/TypeScript、不增加生产业务启动单元。未提交或推送。操作入口和配置边界见[开发服务管理器](runbooks/dev-manager.md)。

环境：Windows amd64，Go 1.26.5、Java 21.0.11、Rust/Cargo 1.98.1、Node 24.16.0；复用既有前端 node_modules 与 Gradle 缓存。本轮没有执行依赖升级或生成伪造锁文件；Go 只依赖标准库，无需 go.sum。

### 本轮实现

- `scripts/devctl` 提供菜单及 start/stop/restart/status/logs，管理固定 platform/runtime/worker/web。支持全部与单组件，菜单退出后进程继续运行，再次打开可管理。状态区分排队、构建、启动、运行、失败与未受管端口，并展示真实 HTTP 存活/就绪结果。
- 使用固定应用构建入口：Java 直接调用仓库 Wrapper 的 Java 主类，Rust cargo build --locked，Web 直接调用安装的 Vite；无 shell 字符串拼接，Java/Rust/Vite 启动绑定 loopback，Web strictPort。所有终端共享一个构建名额，构建5分钟/存活探针90秒上限，失败不重试/回退。
- 管理进程的随机 loopback 控制端口由独立随机凭据鉴权；普通状态/日志不含环境与控制请求。只停止实际持有的子进程对象，拒绝接管外部端口，不按磁盘旧PID杀进程。Windows隐藏进程并停止进程树；Unix使用独立进程组。
- 字面dotenv配置支持 .env、.env.dev、组件覆盖及系统环境；新增可提交样例 .env.dev.example。显式--demo强制memory/Fixture/Mock、关闭Worker采集与模型出网、固定开发身份，生成复用随机Token且不打印；local失败不自动使用Fixture。公开占位Token被拒绝。切换已有local/demo服务须显式restart。
- 写入组件日志前脱敏已知凭据与Authorization，处理跨写入分片并省略超长行。源码不包含真实凭据。增加固定Go1.26.5的Windows/Linux/macOS CI矩阵，本轮未运行远程CI。

### 实际运行结果

| 检查 | 本轮实际结果 |
|---|---|
| Go 测试 | `go -C scripts/devctl test -v ./...`：最终12个Test入口通过（11个行为检查及1个子进程helper入口），包括dotenv字面值、安全错误、显式演示覆盖/稳定Token/占位拒绝、模式切换拒绝、无shell启动命令、日志分片脱敏、控制鉴权、实际fixture子进程启动/重启/停止、子进程树、共享构建/排队取消、外部端口拒绝与构建失败关闭 |
| Go vet/build | `go -C scripts/devctl vet ./...`、`go -C scripts/devctl build -o ../../.tmp/bin/ .`通过，生成Windows启动器；后台执行自身副本，使运行中可重建CLI，产物均在忽略目录 |
| 交叉编译 | 最终源码设置GOOS=linux/GOARCH=amd64，以及GOOS=darwin/GOARCH=arm64，CGO_ENABLED=0，go build均退出0；仅编译，不宣称目标机器运行或Unix信号路径已实测 |
| 实际四组件生命周期 | Windows二进制`start all --demo`与`restart all --demo`最终退出0，Java API、Rust Runtime、关闭采集的Java Worker及Vite均running，四组件存活/就绪各200/200；Web单独restart与logs也实际执行通过。未使用真实来源、数据库或模型 |
| 菜单/清理 | 通过标准输入`4`→`0`检查实际菜单与状态，退出码0，退出后服务继续运行；随后stop all退出0，再次status显示四组件stopped/PID0/端口均未占用，管理状态文件移除。本轮临时服务未留后台 |
| 契约 | `.tmp/mvp-check-venv/Scripts/python.exe -X utf8 -m pytest tests/contracts -q`退出0，885项通过；不等于业务整链 |
| Java纯领域 | 原`python -X utf8 scripts/check_java_domain.py`因Windows命令长度限制失败；随后使用既有`.tmp/local-preview/menu-69-domain.cjs`以javac --release21与@参数文件编译同一modules/领域源码并逐个运行47个main，退出0；不是Gradle集成测试 |
| Rust | `cargo test --workspace --locked -j 1`最终41项通过；`cargo test --workspace --all-features --locked -j 1`49项通过；`cargo fmt --all -- --check`退出0；未触发外部模型/MCP调用 |
| TypeScript/build | Node直接执行已安装TypeScript的tsc --noEmit -p apps/web-console/tsconfig.json，退出0；apps/web-console目录执行vite build退出0，保留超过500KB块的既有警告；未执行Playwright或重新安装依赖 |
| 静态 | check_repo与git diff --check通过；未提交.env、凭据、二进制、缓存或target，源码变更没有跨语言业务契约修改 |

中间失败如实保留：Go首次构建的工具进程缺少GOCACHE/LocalAppData，补齐本机缓存环境后通过；Rust首次检查缺少MSVC link.exe环境，配置既有MSVC/Windows SDK后两类测试最终通过。首次Vite调用误在仓库根目录构建，改在Web目录后通过。Java首次启动使用错误Gradle缓存位置而下载等待，另一个终端的Worker构建遇到Wrapper排他锁超时；修正本机GRADLE_USER_HOME并增加跨终端共享构建名额后，Java构建和启动通过。第一次四组件启动的Runtime与all-features检查争用Cargo锁超过5分钟，按预期被停止并保留失败，无隐藏重试；检查完成后显式重跑，最终四组件启动、全部重启和清理均通过。

本轮未运行：完整Java/PG/VM集成、Playwright、真实Zabbix/模型/IdP/TLS及人工评估、Go race检查、macOS/Linux实机生命周期、整机重启和生产部署。新增CI仅提供配置，不报告CI通过。既有生产身份、业务持久性和M0–M4退出项保持原状态。

本机原始输出位于忽略目录`.tmp/dev-checks`（go-final、contracts、domain-argfile、rust-default-final、rust-all-features、web-build、vite-build-final、smoke-final、restart-all-final等日志），组件日志与随机开发凭据在`.tmp/dev`；不提交这些开发产物。

## 77. 2026-10-01 shadcn-admin 风格运维控制台（追加）

按用户指定的shadcn-admin参考其布局、分组导航、卡片及标签页层级，保留现有React/shadcn/ui、hash路由、可信会话与21个页面。没有引入上游的演示业务数据、Clerk/TanStack Router，也没有升级依赖或修改跨语言业务契约。用法见[运维控制台](runbooks/admin-console.md)。未提交或推送。

### 本轮实现

- 统一中性色与蓝色重点色、亮暗主题变量、侧栏层级、顶栏搜索/面包屑、统计卡片、表格/筛选表单和图形工作区。开发凭据区收紧，仍保留完整可访问名称与内存生命周期；使用说明移到业务内容下方。
- 运维工作台显式调用既有资产/故障分页GET，各最多25条，最多两条并发、15秒预算，无自动重试。卡片、事件表和资产生命周期分布均只代表当前页；事件表按本页创建时间排序取前5条，点击只恢复选择，不自动读详情。后续页、Fixture资产、开发内存均明示。
- 一项接口503不被解释为空数据，另一项成功可展示；401/403和会话变化取消请求并清空旧结果，迟到响应不能回填。没有新增后端聚合、模型调用或假造全平台指标。
- 新增本地Card/Radix Tabs组件，接入流程在独立键盘可用的指南标签中。更新旧Zeus控件回归入口与首页导航断言。
- 完整回归发现X6在clearCells后立即复用节点ID时旧HTML视图残留，固定小图同步更新后消除；恢复普通滚轮的页面滚动链。图适配不改变工作流定义、布局或执行协议。

### 实际运行结果

环境：Windows，Node24.16.0、Java21.0.11、Rust/Cargo1.98.1，复用已有node_modules、契约虚拟环境及本地工具链；浏览器明确指定本机Chrome，不静默替换。

| 检查 | 实际结果 |
|---|---|
| 类型检查 | Node直接执行已安装TypeScript的tsc --noEmit -p apps/web-console/tsconfig.json，最终退出0 |
| 生产构建 | Web目录执行vite build，最终退出0。保留超过500KB块警告：主块约624.5KB，X6约585.8KB，G6约1.4MB；不宣称性能达标 |
| 全量浏览器 | 在4173生产预览执行Playwright Chromium --workers=2，最终276项通过（1.6分钟），包含新增7项工作台检查。均为显式HTTP Fixture，不等于真实平台联调 |
| 相关修复复验 | 首页、图工作区、工作流的18项先单独通过；最终全量又验证X6编辑/撤销/重做/保存/预览/发布/测试运行/下一版、拖动与键盘移动、普通滚轮、G6明细/缩放、导航/搜索/主题和权限清理 |
| 视觉与响应式 | 实际浏览器截图查看1440桌面工作台/资产/数据源及390手机工作台/抽屉、亮暗主题。390×844逐个打开21页，documentElement.scrollWidth均不大于视口，未捕获pageerror。另以显式契约Fixture读取并截图工作台表格与生命周期分布，未注入真实系统数据 |
| 契约 | .tmp/mvp-check-venv/Scripts/python.exe -X utf8 -m pytest tests/contracts -q退出0，885项通过 |
| Java纯领域 | 复用既有忽略目录menu-69-domain.cjs，以javac --release21/@参数文件编译同一modules领域源码并运行47个main，退出0；不是完整Gradle/PG集成 |
| Rust | Runtime目录cargo test --locked共41项通过；cargo test --locked --all-features共49项通过；cargo fmt --all -- --check退出0，未调用外部模型或MCP |
| 静态 | check_repo退出0，271个结构化文件、6个只读Tool检查通过；git diff --check通过 |

中间失败：首次契约入口误指向不存在的scripts/check_contracts.py，改用仓库实际pytest入口后通过。第一次完整浏览器结果为271通过/5失败，其中两项为改版后的首页断言差异，三项暴露X6节点残留与滚轮问题；修正断言和适配层后相关18项及最终全量276项通过。没有把编写测试视为通过。

预览清理后由既有Go管理器restart web启动，Vite存活/就绪200/200，HTTP根页200，保留5173供界面查看；平台/Runtime/Worker仍stopped。第一次start web报告端口占用并拒绝启动，确认5173无监听后显式restart通过，没有接管外部进程。4173测试预览及本轮浏览器已关闭。

本轮未跑完整Java/PG/VM集成、真实Zabbix/模型/IdP/TLS或人工诊断评估、远程CI和生产部署。尚未接入的Agent控制台和M0–M4业务验收状态不改变。原始输出与预览截图在忽略目录.tmp/dev-checks/ui77-*与.tmp/ui77-*，不提交本地凭据、浏览器数据、产物或缓存。

## 78. 2026-10-01 Go 管理器二进制分发与 npm 产物调用（追加）

按用户要求保留Go源码，将日常入口从go run改为编译产物。package.json增加ops以及dev:start/dev:restart/dev:logs等快捷脚本，dev和状态/停止也直接调用二进制。Node适配器仅选择本机平台/架构并转发参数、标准输入输出、信号和退出码，进程管理仍由Go负责。缺少产物不隐式编译或下载；单文件管理器可直接运行，无需Go或Node适配器。业务应用仍需原有仓库和Java/Rust/Node环境。未提交或推送。

产物生成在忽略目录dist/devctl，Go源码位于scripts/devctl。构建显式CGO_ENABLED=0、-trimpath、-ldflags=-s -w；提供Windows/Linux/macOS的amd64/arm64六份二进制、README.txt和SHA256SUMS，不复制.env或凭据。CI三系统矩阵改为同一构建入口并上传本机产物附件；本轮未执行远程CI。用法见[开发服务管理器](runbooks/dev-manager.md)。

### 实际运行结果

环境：Windows amd64、Go1.26.5、Node24.16.0、Java21.0.11、Rust/Cargo1.98.1。复用已安装依赖，不修改依赖锁。

| 检查 | 本轮实际结果 |
|---|---|
| 本机构建 | pnpm dev:build退出0，生成dist/devctl/windows-amd64/opsweave-dev.exe，大小7,203,840字节；帮助说明已区分源码构建与二进制运行所需环境 |
| 分发构建 | pnpm dev:package退出0，六个目标均成功编译；独立读取每个文件并重新计算SHA256，六项均匹配SHA256SUMS，Windows PE/Linux ELF/macOS Mach-O文件头已读取核对。仅Windows amd64实机运行，其余五个目标仅交叉编译 |
| npm调用 | pnpm ops status和pnpm ops --help退出0；pnpm ops restart web退出0，Vite PID由22508变为28560，存活/就绪200/200，其他三个组件保持原PID和200/200 |
| 菜单 | 实际pnpm ops标准输入4→0，状态/菜单显示正常并退出0；退出菜单后四组件继续运行 |
| 运行无需Go | 从PATH移除Go后pnpm ops status仍退出0。另从仓库.tmp子目录直接运行Windows二进制，仅保留Windows系统PATH（无Go/Node），正确定位仓库并查询四组件，退出0 |
| 失败行为 | pnpm ops invalid-command原样返回退出1。隔离空目录复制适配器后运行status，退出1并明确提示缺少当前平台产物；没有编译、下载或启动服务 |
| Go | go -C scripts/devctl test -v ./...的12个Test入口通过（含子进程helper）；vet和fmt退出0；三个.mjs文件Node语法检查通过 |
| 契约/领域 | pytest tests/contracts -q退出0，885项通过；复用javac参数文件入口编译纯领域并运行47个main，退出0，不等于Gradle/PG集成测试 |
| Rust | fmt检查退出0；独立CARGO_TARGET_DIR执行cargo test --workspace --locked -j 4，41项通过；同一隔离目录执行cargo test --workspace --all-features --locked -j 4，49项通过 |
| TypeScript/build | Node调用已安装tsc --noEmit退出0，Web目录vite build退出0；保留主包/X6/G6超过500KB的既有警告 |
| 静态 | check_repo退出0，272个结构化文件、6个只读Tool检查通过；git diff --check及dist产物Git忽略检查通过 |

中间失败如实保留：最初在默认target目录执行Rust复验，Cargo需要替换正在运行的Runtime.exe，Windows拒绝访问而退出101；没有停止现有服务，改为忽略目录.tmp/dev-checks/rust78-target进行独立构建和检查。此失败不计为测试通过，也未通过删除运行中产物规避。

本轮未执行完整Java/PG/VM集成、Playwright、非Windows amd64实机运行、真实模型/IdP/TLS/人工评估、远程CI或生产部署。只改开发工具交付方式，不提高M0–M4业务验收状态。原始输出和二进制核对记录位于忽略目录.tmp/dev-checks/pack78-*，产物不提交到Git。


## 79. 2026-10-01 本地预览自动会话与移除平台 Token 输入（追加）

用户明确选择本地预览自动建立会话、打开即用。本轮为开发访问体验调整，不实施附件中的正式IAM方案，不增加业务启动单元或变更Java领域/跨语言业务Schema。开发随机Token、固定开发身份、Java逐请求tenant/对象/权限/预算检查仍保留；并未关闭后端认证。

Go客户端在启动Web前读取平台实际配置，以私有环境变量给Vite派生开发凭据；该变量不使用VITE前缀，不进入浏览器或产物。serve模式的loopback桥接提供固定GET开发握手，返回短时随机nonce与有效期；业务请求只携带内存nonce，由Vite验证同源、Host、Fetch Metadata及有效期后在固定/api/v1代理中添加后端Bearer，不向独立Fixture Runtime注入。最多64份30分钟会话，满额拒绝新会话。平台页面自动会话就绪后完全隐藏会话输入区；过期/401明确重新连接，失败不隐藏重试。手动开发模式和OIDC可显式使用，生产构建不启用本地桥接。使用见[本地会话](runbooks/local-session.md)和[Go管理器](runbooks/dev-manager.md)。

### 实际运行结果

环境为Windows amd64、Node24.16.0、Go1.26.5、JDK21.0.11、Rust1.98.1。沿用现有锁、依赖和本地四服务，无提交或推送。

| 检查 | 本轮实际结果 |
|---|---|
| 本地桥接/传输协议 | 新增7项Node测试通过：随机nonce/不返回后端Token、无凭据401、Origin/Host/Fetch Metadata与Bearer覆盖403、固定GET/路径限制、30分钟过期、64容量机制（隔离测试缩为2）、非loopback配置拒绝、仅nonce无Cookie/Bearer、Runtime隔离、手动身份替换拒绝、会话失效取消迟到响应；与既有44项验收协议Fixture合计51项零失败 |
| 自动会话浏览器 | 独立4175 Vite Fixture的4项Playwright通过：打开与刷新无Token栏/不持久存储nonce、握手失败显式重连、401清数据且无自动重试、确定性pagehide/pageshow恢复。最后一项是事件Fixture，不声称原生bfcache覆盖 |
| 已有浏览器回归 | pnpm test:web实际276项全量通过，包含手动开发、OIDC协议、来源、工作流、X6/G6和控制台；与新增4项合计280项，通过不代表生产IAM验收 |
| 实际本地浏览器 | 5173来源页面自动会话就绪，无平台Token输入。只发起GET读取既有PostgreSQL接入目录，HTTP200，读到5份已有配置、7个模型；刷新重新建立会话。浏览器请求无Authorization、携带nonce；直接Java无Bearer401、Vite无nonce401、跨Origin握手403。未确认/新建配置、未触发来源自检/手动采集或模型诊断 |
| 凭据/构建隔离 | 使用显式fixture私有Token执行生产vite build退出0，扫描产物未包含该值；实际读取开发会话/HTTP/Vite客户端模块，未包含本机后端Token。只记录布尔结果，不输出凭据。生产构建保留既有大chunk警告 |
| Go | 15个Test入口通过（含helper及新增配置/演示凭据/启动状态快照测试），vet和gofmt成功；同时修正queued/starting的PID0快照误标unmanaged竞态，外部进程仍不接管 |
| 产物 | 最终重新编译Windows/Linux/macOS各amd64/arm64六份管理器，重新计算六项SHA256均匹配；仅Windows amd64实机运行，其余仅交叉编译 |
| 契约/领域 | Python显式UTF-8执行885项契约通过；参数文件javac入口编译并实际运行47个纯领域main，退出0，未将其当成Gradle/PG集成 |
| Rust | fmt退出0；隔离CARGO_TARGET_DIR执行默认41项与all-features49项，均通过，不覆盖运行中Runtime.exe |
| TypeScript/静态 | 最终tsc --noEmit、vite build、check_repo与git diff --check退出0；272个结构化文件、6个只读Tool检查通过 |
| 服务状态 | pnpm ops status与最终幂等pnpm ops start web均退出0；platform27844/runtime14620/worker28152保持原PID，Web14756，四单元存活/就绪均200/200，保留5173供使用 |

中间失败保留：首次Node Host负例使用fetch，其Host被客户端规范化而未发送伪造值，改用真实HTTP request后7项通过。新增浏览器测试首次异步response监听未完成就断言会话数，改为等待观察结果后4项通过。初次契约收集受Windows默认GBK影响退出2，显式-X utf8后885项通过。实际浏览器初次因状态文本在已关闭dialog内也存在而触发locator严格匹配错误，限定可见外层匹配后通过。首次Web restart端口检查失败返回1；随后start的异步状态快照误标unmanaged返回1，但已受管Web最终完成启动，后续status/start退出0，未杀未知进程。新快照修复通过隔离测试并进入重新构建的产物；本轮保留原后台supervisor，不为替换它停止其他三个业务服务，新建supervisor时使用新版本。上述失败均不计为成功。

本轮未执行完整Java/PG/VM集成、真实模型调用/IdP/TLS/人工评估、非Windows实机或远程CI、生产部署；不提高M0–M4退出状态。现有Worker继续原授权流采集，本轮未修改其配置或起点。原始输出、无Token页面截图和脱敏布尔/数量核对记录位于忽略目录.tmp/dev-checks/session79-*；凭据、浏览器业务数据、缓存和产物不提交。


## 80. 2026-10-01 来源优先、日志/指标独立输出与工作流体验（追加）

本轮完成来源先配置、工作流选择输出及相关界面优化。来源确认的target可以省略，初始模型pin与工作流为空；进入画布后显式选择ENTITY、METRIC或LOG并保存。旧实体确认及工作流JSON/摘要保持兼容。遥测格式与示例以contracts为权威，不为日志或指标伪造实体模型；实体仍要求目录权限，Zabbix Host批次不能冒充遥测输入。

LOG保留正文首尾空白并校验带时区的eventTime、body、可选级别/服务/Trace字段；METRIC固定timestamp/key/value/type/unit，首版只接受GAUGE，数值有界、单位不猜测。继续复用有界样本、单链白名单算子及预览发布门禁。界面沿用React/shadcn-ui及shadcn-admin风格，增加来源/处理/输出阶段、选择卡、格式校验、节点输入输出和字段错误；无目录权限时不可选择实体模板。来源列表不从不可变回执猜测当前工作流状态。X6独立响应式viewport解决引擎inline尺寸在窗口变窄后遮挡节点的问题。

环境：Windows amd64，JDK21.0.11、Node24.16.0、Rust1.98.1；复用锁与依赖。Java集成使用既有本地Docker端口和隔离数据库opsweave_checks_64，不清空运行中的平台数据库。本轮未新建服务、数据库、迁移或依赖，没有提交或推送。

### 实际运行结果

| 检查 | 本轮实际结果 |
|---|---|
| 契约 | Python显式UTF-8执行pytest tests/contracts -o addopts= -q，904项通过；覆盖来源空target/空工作流、新旧输出联合、日志/指标记录、非法字段/格式/Host组合及历史trace联合 |
| 纯领域 | javac --release 21参数文件编译后逐一运行48个main，1391断言通过；新增WorkflowOutputSmoke27项实际覆盖source.sync-only、来源幂等、无自动草稿、日志/指标保存预览发布、UTC、正文空白、非法时间/类型及Host组合拒绝 |
| Java/PG/VM | 先实际运行TelemetryWorkflowHttpIT、SourceSetupHttpIT、WorkflowHttpIT及两项PG工作流类；再运行platform-api与ingestion-worker完整test。XML合计258+24=282项，失败/错误/跳过均0；新HTTP测试3项包含无entity.read时遥测可用、实体仍403、Host错误400、持久trace无正文及旧确认兼容 |
| Rust | 独立CARGO_TARGET_DIR=.tmp/dev-checks/rust78-target执行默认41项和all-features49项，均通过；cargo fmt --all --check通过，未覆盖运行中Runtime产物 |
| TypeScript/build | 最终tsc --noEmit和vite build退出0；保留主包/X6/G6大于500KB的既有chunk警告，没有依赖升级 |
| 浏览器Fixture | 全量281项通过（276既有+5输出测试）。随后新增尺寸切换检查、修正viewport及页面能力提示，最终25项来源/工作流/输出/X6/G6关联测试通过，其中6项输出覆盖1440/390px、无实体目录、文本渲染、旧预览失效、发布后创建独立模板及所有节点在父viewport内。全量281之后未再次运行全部套件；增量由上述关联检查覆盖 |
| 本地自动会话 | 独立Vite Fixture的4项Playwright及7项Node桥接/传输协议检查通过；未重跑未修改的Go单元测试或全部既有Node协议文件 |
| 实际本地流程 | 无开发Token栏的5173浏览器经真实Java/PG确认一份[LOCALTEST]手工日志来源：200、initialTarget=null、workflow=null，抽屉无模型字段。显式保存4节点LOG草稿，1条Fixture样本通过、0拒绝、dryRun=true/writesPerformed=false；正文空白保留、UTC规范化。发布200，来源与运行明细回读成功，刷新后显式读取恢复不可变版本；持久运行详情无样本values/正文 |
| 旧版本/布局 | 实际读取用户原source-26b50d02-ebf2-4588-a44e-6eb13dc4a783草稿：editVersion仍1、builtin.host@1、target仍digest/id/revision三字段、来源仍zabbix-local、节点仍SOURCE/MAP/TRIM/VALIDATE/OUTPUT。未保存或更改此草稿。实际390px截图及父容器边界核对：四节点完整可见、无横向溢出和pageerror；桌面与输出选择卡已视觉核对 |
| 静态/私有资料 | check_repo及git diff --check退出0，6个只读Tool检查通过。私有审计报告仍在仓库外，实际重算SHA256与交付时一致；未复制参考代码、资源或私有报告到公开仓库 |
| 服务 | 实际pnpm ops restart platform及status退出0；platform29984、runtime14620、worker28152、web14756，四组件存活/就绪200/200，5173保留供使用 |

中间失败如实保留：首次TS检查发现JSX分支引用被缩窄为never，提取sourceKind后通过。首次契约有2项因新样例名称找不到同名Schema失败，改为匹配现有契约的样例名后904项通过。首次新增浏览器用toBeDisabled检查原生option，虽已有disabled属性仍得到enabled，改为属性检查；随后全量通过。初始尺寸测试只按X6宿主自身边界核对，实际截图发现宿主inline宽度仍超出父容器；增加独立viewport、监听实际容器及父容器边界断言后复验通过。实际页面探针首次ESM导入Playwright失败，改用已安装包的CommonJS入口；初次直接等待旧深链表单时尚未显式读取，点击读取后回查成功。这些失败均未计为通过。

本轮只证明有界平面样本转换、版本管理与只读预览。没有连续日志采集/Grok/多行解析、指标写入、日志索引、存储目的地启用、可选实体关联、关联聚合、后台吞吐量/背压/游标恢复或完整IAM建设，不将Fixture样本称为真实来源接入。真实模型/IdP/TLS、非Windows实机、远程CI、生产部署及参考项目运行/性能均未验收；M0–M4退出状态不变。实际LOCALTEST来源/版本/回执保留在本地开发数据库。原始日志、脱敏核对及截图位于忽略目录.tmp/dev-checks/outputs80-*，不提交浏览器数据、凭据、缓存或构建产物。


## 81. 2026-10-01 操作收敛与接入/流程/版本分层（追加）

按用户提供的界面参考和易用性要求重做来源、工作流的操作层次，保留React/shadcn-admin风格。来源中心分为可搜索/分类的接入类型卡片和本人接入表；工作流先列流程，再列所选流程版本，最后进入编辑。只展示现有Zabbix、手工JSON和CMDB导入能力，没有添加虚构厂商连接、后台启停、吞吐量或存储按钮。默认处理区为可点击的编号步骤，X6仍可按需切换；添加算子、样本与结果、历史及说明默认收起。已发布版本隐藏保存/发布/添加步骤按钮，保留显式测试与创建下一版。字段映射显示来源字段到中文输出字段；保存不跳走当前步骤，修改测试数据要求重新预览。

删除未接入的AI执行/技能占位页及公共占位组件；Fixture诊断保留开发直链但退出产品导航和搜索，当前18个可导航业务页面。AI留存策略与UUID查询按需展开，提交结果不明时仍恢复原查询入口，预览/确认/摘要/有效期边界保持不变。来源和工作流在本地自动会话就绪后读取目录；严格校验task选择深链，旧id/revision/state及sourceSetup深链保留。开发热更新重新挂载时重置页面存活标记，避免忽略新请求结果。没有改本轮业务后端、数据库、依赖、身份构造或服务端执行权限。

环境为Windows amd64、Node24.16.0、JDK21.0.11、Rust1.98.1；沿用当前本地四服务、锁和已安装依赖，无提交或推送。相关说明见[来源中心](runbooks/source-center.md)、[工作流](runbooks/workflows.md)与[控制台](runbooks/admin-console.md)。

| 检查 | 本轮实际结果 |
|---|---|
| 契约 | .tmp/mvp-check-venv/Scripts/python.exe -X utf8 -m pytest tests/contracts -o addopts= -q，904项通过；最终目录分层后重跑通过 |
| 纯领域 | 参数文件javac --release 21编译，48个纯领域main实际运行，1391断言通过。未将编译或main检查当作Gradle/PG集成 |
| Rust | 隔离CARGO_TARGET_DIR=.tmp/dev-checks/rust78-target执行默认41项与all-features49项，均通过；cargo fmt --all --check退出0，未覆盖运行中Runtime产物 |
| TypeScript/build | 直接使用已安装TypeScript/Vite CLI执行最终tsc --noEmit及vite build，均退出0；保留既有主包/X6/G6大chunk警告，无依赖升级 |
| 浏览器全量 | Playwright 292项全量通过，包含来源先确认、ENTITY/METRIC/LOG、旧发布门禁、X6/G6、导航/留存/会话及6项新增目录分层检查。1440/390px核对搜索、类型筛选、版本到只读编辑及返回路径、模板取消恢复焦点、非法/重复/混合task深链不发请求；列表/筛选/视图操作仅GET，无保存/执行或隐藏连接探测。全量后调整目录按钮样式与创建文案，最终29项来源/目录/工作流/输出关联复验通过；此后未再重跑全部292项 |
| 自动会话 | 显式4175 Vite Fixture执行6项Playwright通过：打开/刷新无Token、失败显式重连、401清理、确定性pagehide/pageshow恢复、深链就绪自动读取、来源目录自动读取。生命周期事件Fixture不等于原生bfcache验收；临时配置只关闭重复启动并保持原testDir/用例 |
| 实际本地页面 | 5173真实Java/PG目录读取及已有LOCALTEST发布日志版本回读成功，来源/流程/版本/编辑桌面及390px截图已视觉检查，无横向页面溢出或pageerror；编号步骤点击能切换字段配置，发布页没有保存/发布按钮。原有业务数据未因查看而执行、保存、确认来源、测试连接、采集、发布或清理 |
| 静态/服务 | check_repo退出0，281个结构化文件和6个只读Tool检查通过；git diff --check退出0。Go产物管理器status退出0，platform29984/runtime14620/worker28152/web14756四单元存活/就绪均200/200，5173保持运行 |
| 参考站点 | 首次参考链接显示登录页。用户随后登录后，浏览器初次绑定/读取/状态发现连续超时；恢复连接后实际核对接入搜索、创建说明面板、任务列表、任务详情及草稿/发布数据流版本表，并捕获页面截图。仅使用搜索和只读导航，没有保存、发布、启停或删除远端任务。结合用户9张截图落实交互层次，没有导出站点源码、资源或业务数据 |

中间失败如实记录：删除公共占位组件后首次TS检查发现技能页仍引用，按用户删无用元素范围移除该占位页后通过。初次步骤样式被旧X6按钮规则覆盖导致卡片重叠，将旧规则限制在画布内后桌面/手机点击及顺序断言通过。首次新增本地深链用例传入不完整的state参数而被严格解析器拒绝，改用合法task目录选择后通过。首次all-features命令因PATH选择GNU link失败，显式配置已安装MSVC/SDK后49项通过。目录分层后的首轮41项关联测试中4项仍按旧展开库路径寻找新建按钮而超时，改为返回版本表→全部流程→新建后12项专项及最终292项通过。实际长时间开发浏览器受热更新已销毁标记影响，修正重新挂载标记并刷新后版本回读成功。pnpm子脚本找不到tsc shim时改为调用已安装CLI，未声称该失败命令通过。上述失败均不计为成功。

本轮未执行完整Java/PG/VM集成（上轮§80的282项不计入本轮）、真实来源/模型/IdP/TLS/人工评估、非Windows实机、远程CI或生产部署；不提高M0–M4退出状态。当前仍为有界样本预览和版本管理，不提供Grok、多行解析、连续日志/指标消费或真实存储写入。私有审计文件继续在仓库外且SHA256与交付值相同。原始检查输出及本地截图仅位于忽略目录.tmp/dev-checks/studio81-*与studio82-*，不提交凭据、浏览器数据、缓存或产物。


## 82. 2026-10-02 接入目录与流程编辑区视觉调整（追加）

按用户要求调整数据源和工作流界面，采用紧凑字号、细边框、灰白工作区和蓝色主操作。继续使用仓库现有React/shadcn-admin。相关研究原件保存在仓库外，不作为本项目业务接通证明。

本轮修改范围为两个页面的局部布局、样式及相关浏览器断言。来源卡片去掉重复标题，将图标、名称、已保存数量放在顶部。流程定义与主要动作合并为工具栏，编号步骤与配置区共用一个容器；紧凑步骤、明确蓝色选中标记及手机上下排列。保留原保存/预览/发布禁用条件、不可变发布只读、Fixture标记和能力边界；没有修改业务后端、契约、身份、依赖或数据库。相关说明见[控制台](runbooks/admin-console.md)、[来源中心](runbooks/source-center.md)和[工作流](runbooks/workflows.md)。

| 检查 | 本轮实际结果 |
|---|---|
| 契约 | 显式UTF-8执行pytest tests/contracts -o addopts= -q，904项通过 |
| 纯领域 | javac --release 21参数文件编译，实际运行48个main，1391断言通过；未将其当作Gradle/PG集成 |
| Rust | 隔离CARGO_TARGET_DIR=.tmp/dev-checks/rust78-target执行cargo test --workspace --locked及--all-features --locked，默认41项/all-features49项通过，未覆盖运行中Runtime产物 |
| TypeScript/build | 最终使用已安装CLI执行tsc --noEmit及vite build，退出0；保留既有大于500KB的chunk警告，无依赖升级 |
| 浏览器关联回归 | 显式OPSWEAVE_TEST_CHROMIUM_EXECUTABLE指定本机Chrome，最终44项通过（38.3秒）；覆盖来源、目录、工作流、ENTITY/METRIC/LOG、X6/G6与导航。目录新增1024px，与1440/390px一起检查配置区容器边界、发布版本字段禁用、搜索和版本返回、列表操作仅GET；最终搜索/选中/手机状态样式调整后复验通过 |
| 实际本地界面 | 独立后台标签页经5173真实本地Java/PG读取来源/流程/版本目录，打开已有LOCALTEST日志发布版本并切换到字段映射；字段保持禁用。桌面、390px、明暗主题截图已视觉检查，手机容器边界及桌面无横向页面溢出。没有保存、确认来源、测试连接、预览执行或发布本地业务数据 |
| 原标签页 | HMR后只读核对原工作流名称仍为Zabbix主机接入、五个步骤及未保存标记仍在；未刷新、重新连接、放弃或保存该标签页 |
| 静态 | scripts/check_repo.py退出0，281个结构化文件与6个只读Tool定义检查通过；git diff --check退出0 |
| 服务 | 实际pnpm ops start all后最终pnpm ops status退出0，platform20060/runtime30464/worker19896/web27048四组件存活/就绪均200/200；保留5173供使用 |

中间失败保留：首次纯领域javac参数文件使用反斜杠，路径被参数解析合并，改为正斜杠后48个main通过。首次44项浏览器命令因预期的Playwright Chromium不存在而全部在启动前失败，显式指定本机Chrome后44项通过，没有将缺失浏览器当成测试成功。实际视觉核对发现旧原生按钮规则覆盖步骤选中背景，修正局部选择样式后明暗主题截图确认标记可见。深色主题恢复时第一次使用不匹配的按钮名称而未点击，重新读取页面后用实际“切换到浅色模式”入口成功恢复。

本轮未重跑完整Java/PG/VM集成、全量浏览器套件、自动会话专项、Go检查、Rust fmt、真实来源/模型/IdP/TLS、非Windows实机、远程CI或生产部署；既有章节的结果不计入本轮。保持原M0–M4退出状态，仅调整现有管理界面。原始日志与截图位于忽略目录.tmp/dev-checks/ui83-*，未提交或推送，也不提交凭据、浏览器数据、缓存和构建产物。

## 83. 2026-10-02 全站界面与前端规范（追加）

继续用户要求的其他页面改造，并按明确请求设置目标。将既有18个产品入口的工作区、页头、表单、目录、图形工作区和记录容器统一为紧凑后台层次。新增 `components/PageLayout.tsx` 的PageHeader、PageBody、QueryToolbar、SummaryGrid、SummaryCard；统计组有可访问名称，查询按钮组可换行。展示组件不读取会话、不发请求、不保存业务状态，原标签、处理函数、禁用条件及统计范围保留。模型、资产、指标、故障、工作流/采集记录和维护页面接入共享布局；来源和编排保留已有专用组件。没有修改依赖、业务后端、契约或数据库结构。

新增[前端开发规范](development/frontend-guidelines.md)，覆盖分层、组件抽离、主题、视觉层次、状态/身份/操作门禁、可访问性、响应式、实际验证和文档交付；由Web目录AGENTS.md引用，兼容性基线与[控制台说明](runbooks/admin-console.md)同步更新。

| 检查 | 本轮实际结果 |
|---|---|
| 契约 | `.tmp/mvp-check-venv/Scripts/python.exe -X utf8 -m pytest tests/contracts -o addopts= -q`，904项通过 |
| 纯领域 | `javac --release 21 -encoding UTF-8`参数文件编译，并实际运行48个领域main，1391断言通过；不是Gradle/PG集成 |
| Rust | 隔离CARGO_TARGET_DIR为`.tmp/dev-checks/rust78-target`，`cargo test --workspace --locked`默认41项、`cargo test --workspace --all-features --locked`49项通过；不覆盖运行中Runtime产物 |
| TypeScript/build | 已安装CLI执行`tsc --noEmit`与`vite build`退出0；保留既有大于500KB的chunk警告，没有升级依赖 |
| 全量浏览器 | 显式指定本机Chrome执行完整Playwright套件，297项通过；新增3项遍历全部18个产品页面，分别1440/1024/390px检查明暗主题、主内容可见、整页不溢出、匿名不请求业务。既有加载数据、错误、身份变更、不可变发布、待确认回执、键盘/焦点、X6/G6行为全部回归 |
| 实际界面 | 独立后台标签页核对18页桌面布局，及模型/资产/诊断/采集的390px、关系示意的1024px和模型明暗主题。真实Java/PG读取模型三类目录、资产列表、工作流目录和7条历史扫描记录；模型固定版本字段保持禁用。实际时序查询返回No data，保留缺失，不声称已看到新曲线。浏览器未保存、发布、导入、触发采集、执行诊断或清理现有业务 |
| 原标签页 | 本轮开始时可用的原标签已是连接拒绝页，无法再次核对此前未保存草稿的内存状态；未刷新、导航、重新连接、保存或放弃该标签，仅操作新建检查页 |
| 静态 | `scripts/check_repo.py`退出0，281个结构化文件、6个只读Tool定义通过；`git diff --check`退出0 |
| 服务 | 最终`pnpm ops start all`及`pnpm ops status`退出0：platform19564/runtime15784/worker10012/web5156，存活/就绪均200/200，5173保留运行。健康检查不证明真实模型和持续采集业务成功 |

中间失败保留：第一次抽离内容区时Incidents JSX闭合位置错误，TS失败，修正后通过。首轮完整浏览器290通过/7失败，都因隐藏空alert/status的样式使已有提示区域不可访问；移除该规则后297项通过。随后采集/自检按钮组接入QueryToolbar以及统计组可访问语义完善后，重新构建并再次完整回归。失败命令不计为成功。

本轮服务首次启动时平台因PG迁移连接失败退出，Worker因VM不可达退出。Docker Desktop自身启动失败，实际日志定位到残留AF_UNIX运行socket，与[Docker问题记录](https://github.com/docker/desktop-feedback/issues/460)相符。停止本轮启动的失败实例后，将只含零字节运行socket的`Docker/run`、`docker-secrets-engine`目录分别保留为`*.opsweave83-stale`/`*.opsweave83-stale-b`备份，再启动成功；未执行工厂重置、删除数据卷、迁移容器数据或改配置。原有PG/VM/Zabbix容器恢复运行，再由管理器启动平台与Worker。第一次仅替换run目录时仍在secrets-engine失败，后续两个目录一起处理后恢复，未将早期超时探针或失败启动计为就绪。

本轮未执行完整Java/PG/VM集成套件、本地自动会话专项、Go测试/vet、Rust fmt、真实模型/IdP/TLS、非Windows实机、远程CI或生产部署；已有章节结果不计入本轮。不提高M0–M4退出状态，持续日志/指标消费、关系实例写入等原待办仍以路线图为准。原始输出与截图仅在忽略目录`.tmp/dev-checks/console83-*`，不提交凭据、浏览器数据、缓存或构建产物；本轮未提交或推送。


## 84. 2026-10-02 接入工作流编辑器调整

本轮按用户要求调整接入、任务版本和工作流编辑器。研究原件保存在仓库外；以下仅记录本项目实现和实际检查。

来源保存后直接进入X6画布并准备未保存的本地流程，移除“选择要生成的数据”前置卡片；默认画布、浅灰工作区、紧凑节点、细连线与当前节点配置面板，输出类型和固定模型版本由输出节点配置。日志/指标模板保留，Zabbix Host仍仅实体，Fixture来源明确标注。新增WorkflowEditorToolbar与WorkflowOutputConfig展示组件，清除旧X6节点定位/尺寸样式，保留名称编辑、撤销/重做、拖动、步骤视图、输入/输出与逐条校验、保存/预览/发布、只读版本及原鉴权/契约/并发门禁。没有修改后端、依赖、数据库或跨语言契约，没有复制厂商运行流量、启停或额外接入能力。

| 检查 | 本轮实际结果 |
|---|---|
| 契约 | 显式UTF-8执行pytest tests/contracts -o addopts= -q，904项通过 |
| 纯领域 | javac --release 21参数文件编译，实际运行48个main，1391断言通过；不是Gradle/PG集成 |
| Rust | 隔离CARGO_TARGET_DIR=.tmp/dev-checks/rust78-target执行cargo test --workspace --locked -j 4及--all-features --locked -j 4，默认41/all-features49项通过 |
| TypeScript/build | pnpm typecheck:web与pnpm build:web退出0；最终浏览器入口再执行tsc/Vite构建。保留既有大于500KB的chunk警告，无依赖升级 |
| 浏览器 | 显式指定本机Chrome；全量298项通过（3.3分钟）。后续1024px布局修正、说明文案更新及面板纵向位置断言后，导航/来源/流程列表/工作流/输出/X6/G6最终48项通过（52.5秒）。新增来源跳转用例确认只发送显式来源确认，不自动保存或执行工作流，实体限制与Fixture标记保持 |
| 实际界面 | 独立检查页经5173真实本地Java/PG读取已有Zabbix来源与模型，直接打开五节点本地草稿；核对1440/1024/390px、明暗主题、节点/字段映射/输出设置，窄屏不横向溢出。未保存、预览、发布、触发采集或写入现有业务 |
| 热更新 | 在独立检查页修改本地名称与映射，热更新画布模块后实际回读名称UI REVIEW 热更新检查、映射review_hostname、ready与5个节点均保留；未提交草稿，随后恢复检查页原字段。未导航、刷新、保存或放弃用户原标签页 |
| 静态 | scripts/check_repo.py退出0，281个结构化文件/6个只读Tool定义通过；git diff --check退出0 |
| 服务 | pnpm ops status退出0：platform19564、runtime15784、worker10012、web5156，存活/就绪均200/200，5173继续运行 |

中间失败保留：第一次关联套件28通过/3失败，三个仍断言默认步骤卡的用例与新的默认画布不符；更新为实际画布及只读节点操作后通过。实际截图发现旧原生按钮选择器覆盖节点尺寸、造成内容截断，移除冲突规则；热更新重建图实例时refs保留但新图无节点，触发null.position并使页面空白，改为缺少cell时重建并同步尺寸后恢复，未通过刷新用户标签页绕过。1024px实际截图还发现旧grid-column使配置区落到下一行，显式恢复当前组件列位置并加入桌面并排/手机上下的边界断言，最终48项通过。独立检查页本地会话过期后按现有入口显式重新连接，没有绕过认证或静默回退。

本轮未执行完整Java/PG/VM集成、本地自动会话专项、Go测试/vet、Rust fmt、真实模型/IdP/TLS、远程CI或生产部署，不提高M0–M4退出状态。前端规范、操作说明、ADR-060的UI修订、实现状态与路线图同步更新。原始输出/截图只在仓库外本机历史验证材料，不提交凭据、浏览器数据、缓存或构建产物；未提交或推送。


## 85. 2026-10-02 接入组件收尾与本地预览恢复

继续用户要求的界面实现，在第84节画布改版基础上抽离SourceCatalog、SourceTaskList和IntegrationSearchField；来源页与工作流列表共用搜索框。静态名称/分类/图标放在source-catalog.ts，与React组件导出分开。保持原DOM类名、标签、Fixture/来源标记、事件、禁用条件和列表范围；页面继续管理请求取消、可信身份、不可变创建快照、待确认回执、确认与跳转。来源快照说明统一为输出节点配置，没有增加前置输出选择、业务后端、依赖或契约。前端规范与来源使用说明同步更新。

| 检查 | 本轮实际结果 |
|---|---|
| 契约 | 显式UTF-8运行pytest tests/contracts -o addopts= -q，904项通过（5.72秒） |
| 纯领域 | javac --release 21 -encoding UTF-8参数文件编译，实际运行48个领域main；日志汇总1391断言通过，不等于Gradle/PG集成测试 |
| Rust | 隔离CARGO_TARGET_DIR=.tmp/dev-checks/rust78-target，cargo test --workspace --locked -j 4默认41项，--all-features --locked -j 4共49项通过 |
| TypeScript/build | pnpm typecheck:web、pnpm build:web退出0；最终浏览器命令也实际执行tsc --noEmit与Vite构建。保留既有大于500KB的chunk警告，无依赖升级 |
| 浏览器 | 显式本机Chrome，导航/来源/列表/工作流/输出/图形48项通过（54.9秒）；静态分类模块分离后，来源和列表最终17项通过（23.6秒）。覆盖来源确认不隐式保存/执行流程、身份清理、同请求重试、发布只读、搜索/分类、焦点和1440/1024/390px容器边界，未新增镜像实现的测试 |
| 实际界面 | 独立检查页通过5173真实Java/PG读取原12份接入配置、日志来源创建快照、已发布v1及当前Zabbix来源本地画布。创建快照名称/说明和发布输出保持禁用；未点击保存、确认新来源、预览、发布、版本测试或手动采集。1440/1024/390px及明暗主题截图核对；1024配置区与画布并排，390配置区在画布下方，任务宽表在自身容器滚动，整页不横向溢出。尺寸和浅色主题恢复，保留独立可用预览；未刷新或导航用户原连接失败标签页 |
| 静态 | scripts/check_repo.py退出0，281个结构化文件及6个只读Tool定义通过；git diff --check退出0 |
| 服务 | pnpm ops start all最终退出0，platform24176/runtime20908/worker25396/web2540存活/就绪200/200，保留5173。使用原dev/postgres/jsonrpc、platform-dev/rig-openai及history=true配置，未调用模型。Worker按原已启用流恢复轮询并确认批次，不将健康状态当作全链验收 |

本轮开始实际pnpm ops status显示四组件停止，Docker API不可用；首次start all中Runtime/Web启动，平台因PG迁移连接失败退出，Worker依赖不可用退出，均未计为成功或切到Mock。docker desktop start进入后端异常状态，实际日志先定位Docker/run/dockerInference残留AF_UNIX socket，再定位LOCALAPPDATA/docker-secrets-engine/engine.sock。仅停止本轮启动的Docker进程；每次移动前验证绝对路径和目录中仅有零字节socket，使用原生PowerShell Move-Item保留run.opsweave85-stale、run.opsweave85-stale-b及docker-secrets-engine.opsweave85-stale备份。没有删除卷、重置Docker、迁移容器数据或更改配置。单次合并修复命令返回-1且无输出，核对原路径与进程均未改变后拆开执行成功；未将该命令计为成功。依赖原容器恢复后，显式重新启动失败应用，最终四组件就绪；独立浏览器最初的502通过服务恢复及显式读取解决，没有隐藏重试。

本轮未执行全量浏览器套件、完整Java/PG/VM集成、Go专项、本地自动会话专项、Rust fmt、真实模型/IdP/TLS、非Windows实机、远程CI或生产部署。第84节298项结果仅属于前轮，不计入本轮。保持原M0–M4退出状态，不新增连续日志/指标写入、关系实例或厂商启停能力。原始输出和截图仅在仓库外本机历史验证材料；未提交或推送，不提交凭据、业务正文、缓存或构建产物。

## 86. 2026-10-02 全站导航优化

本轮按用户要求优化分组、选中页、页头与折叠布局。研究原件保存在仓库外；以下仅记录本项目实现和实际检查。

侧栏采用浅蓝灰背景、36px一级图标/文字、32px纯文字子菜单与实心蓝色当前页；桌面宽200px，窄桌面192px，折叠64px。收起按钮移到底部，折叠后七个分类通过浮层访问原18个页面，支持悬停/点击、方向键进入、Tab访问、Esc关闭并返回分类、离开/外部关闭，以及实际浮层高度和窗口边界；尺寸变化关闭浮层。手机使用独立抽屉，不继承桌面折叠状态。页头收紧为48px，保留语义当前位置、页面搜索、主题和实际环境标记。抽离SidebarFooter展示组件，所有导航规则统一到navigation.css并清除app/admin/workspace中的旧冲突样式；前端规范、导航说明、实现状态与路线图同步更新。没有修改依赖、后端、契约、认证或执行权限。

| 检查 | 本轮实际结果 |
|---|---|
| 契约 | 显式UTF-8运行pytest tests/contracts -o addopts= -q，904项通过（5.55秒） |
| 纯领域 | javac --release 21 -encoding UTF-8参数文件编译，实际运行48个main，1391断言通过；不是Gradle/PG集成测试 |
| Rust | 隔离CARGO_TARGET_DIR=.tmp/dev-checks/rust78-target，cargo test --workspace --locked -j 4默认41项，--all-features --locked -j 4共49项通过 |
| TypeScript/build | 最终pnpm typecheck:web与pnpm build:web退出0；浏览器入口另实际运行tsc/Vite构建。保留既有大于500KB的chunk警告，没有升级依赖 |
| 浏览器 | 显式指定本机Chrome；最终导航14项通过（21.6秒），随后完整pnpm test:web共301项通过（3.3分钟）。覆盖18页1440/1024/390px明暗主题、导航/搜索匿名不请求业务、折叠分类全部入口、短视口边界、浮层键盘/关闭/焦点、独立手机抽屉及原有权限、只读、身份清理和保存/预览/发布门禁 |
| 实际界面 | 独立检查页经5173真实Java/PG读取原12份接入配置与当前Zabbix来源，准备本地未保存的五节点流程。核对1440/1024/390px、明暗主题、展开/折叠/手机抽屉及键盘焦点；手机整页宽390px、抽屉272px，无整页横向溢出。未保存、预览、发布、确认新来源或手动采集 |
| 编辑保留 | 独立检查页折叠、键盘打开/关闭分类及展开后，工作流名称、name/ip/空os映射、5个节点均保留；导航模块热更新后再次读回相同字段和节点。未刷新、导航或修改用户原标签页。恢复浅色主题与临时尺寸，保留独立工作流预览 |
| 静态 | scripts/check_repo.py结构化文件与6个只读Tool定义检查退出0；git diff --check退出0 |
| 服务 | pnpm ops status退出0，platform24176/runtime20908/worker25396/web2540存活/就绪200/200，保留原dev/postgres/jsonrpc、platform-dev/rig-openai及history=true配置。本轮未重启服务、改Docker或调用模型 |

中间失败保留：第一次导航13通过/1失败，测试写成“指标模型”而实际页面为“指标定义”，修正名称。第一次全量300通过/1失败，外部关闭用例点击的标题被浮层覆盖，改用右上角实际主题按钮验证外部关闭，未强制点击遮挡元素。后续导航13通过/1失败，方向键打开菜单后异步requestAnimationFrame焦点设置与连续Tab存在时序竞争；改为渲染完成后的useLayoutEffect同步聚焦，已打开菜单立即聚焦，并明确等待实际首项焦点后验证Tab离开。最终14项和完整301项均退出0；失败运行不计为通过。

本轮未执行完整Java/PG/VM集成、本地自动会话专项、Go专项、Rust fmt、真实模型/IdP/TLS、非Windows实机、远程CI或生产部署；前轮结果不计入本轮。保持原M0–M4退出状态，不新增持续日志/指标写入、关系实例或厂商后台能力。原始输出和截图仅在忽略目录.tmp/dev-checks/nav86-*，未提交或推送，不提交凭据、业务正文、缓存或构建产物。

## 87. 页面容器、标准/多页签布局与新增能力规范（2026-10-02）

用户要求继续优化页面容器，提供浏览器式多页面布局与主题组合，并说明新增能力的组件架构。沿用既有导航与ETL操作层次；视觉布局不构成业务接通证明。

实现：PageWorkspace管理统一外壳、地址和内存页面；WorkspaceTabs、LayoutSettings独立展示；page-registry按RouteName完整登记组件，删除App里的逐页条件分支。固定48px页头、40px页签（手机38px）与底栏，内容视口独立滚动；统一外/内间距，去掉正文重复外边框与接入目录旧最小高度。标准与多页签可切换，均支持既有浅/深主题。新标签页默认标准；启用页签后每个已登记路由最多一份，切回标准保留已有页面；刷新只从当前地址重新打开一页。仅持久化布局枚举与既有主题偏好，不保存页签列表、查询地址、Token或业务正文。

非活动页面hidden/inert，保留身份订阅；身份变化释放后台实例并执行当前页原清理协议。活动上下文限制地址恢复、自动读取与后台请求跳转，切回同一地址不自动覆盖草稿或未应用筛选。业务页显式上报dirty、处理中和待确认回执；关闭、关闭其他、关闭所有使用同一门禁，默认保留未保存页，待确认回执不能通过关闭丢弃。最后关闭回到新工作台；支持方向键/Home/End/Delete与关闭后焦点。

[新增能力规范](development/adding-a-capability.md)区分React组件、Java领域/应用模块、Connector和Rust受控执行。已存在API的新展示可只扩展Web；新业务需契约、服务端规则/权限/适配器及实际验证，四启动单元不变，没有新增数据库、微服务、任意动态插件或代码执行。

| 实际检查 | 结果 |
|---|---|
| 契约 | `.tmp/mvp-check-venv/Scripts/python.exe -X utf8 -m pytest tests/contracts -o addopts= -q`，904通过，6.61s，退出0 |
| Java纯领域 | `javac @.tmp/dev-checks/ui83-domain.args`，随后实际运行48个main，共1391个checks passed，退出0；编译有既有unchecked提示，领域失败路径的预期日志不是运行失败 |
| Rust默认 | `CARGO_TARGET_DIR=.tmp/dev-checks/rust78-target cargo test --workspace --locked -j4`，7+9+25=41通过，退出0 |
| Rust all-features | 同目标目录`cargo test --workspace --all-features --locked -j4`，15+9+25=49通过，退出0 |
| Web | 最终`pnpm typecheck:web`与`pnpm build:web`实际退出0；保留Vite已有大chunk提示，不据此宣称完成分包优化 |
| 浏览器专项 | 初轮导航与布局26通过；新增隐藏页迟到响应和待确认清理回执后，最终完整套件包含13项workspace-layout、1项新增retention关闭回执及1项source-center抽屉/浏览器历史检查；显式输出配置位置复验19项通过，抽屉专项1项通过 |
| 完整浏览器 | 显式使用本机Chrome，`pnpm test:web`315通过，3.8m；移除旧接入页最小高度后再次执行，第二次315通过，3.7m；对话框居中与抽屉生命周期修正后的最终完整套件316通过，3.7m，退出0。覆盖标准/多页签、18产品页、1440/1024/390px、浅深主题、草稿与布局切换、查询/历史、滚动、键盘/关闭门禁、身份与迟到响应。原X6拖动检查改测实际main滚动容器，继续验证节点相对坐标和保存布局 |
| 本地自动会话 | `pnpm --filter @opsweave/web-console exec playwright test -c playwright.local.config.ts`，初次6通过，12.8s；最终抽屉生命周期修正后再次6通过，11.6s，退出0。工作流深链和来源自动读取仍受真实Vite会话中间件检查，业务响应为显式Fixture |
| 实际视觉 | 独立浏览器预览页核对1440/1024/390px、两种布局与浅深主题；读取现有PostgreSQL资产3条和已有来源配置12条。只读导航/GET，未保存、发布、采集、清理或调用模型。修改独立预览中的本地工作流名称，跨页面、布局、主题与CSS热更新后读回相同名称、name/ip/空os映射及5节点，关闭提示默认保留；原用户标签页未通过浏览器刷新、导航或输入；开发代码由Vite热更新分发。1440标准目录实际main高度822/scrollHeight822，无横向溢出；后续实际观察短时本地会话到期后旧草稿和隐藏页被清理，显式重新连接只重新准备默认草稿；补验配置抽屉中的本地名称跨浏览器返回/前进恢复，未保存。恢复浅色主题及临时尺寸，保留独立多页签预览 |
| 静态 | `scripts/check_repo.py`初次退出0，280结构化文件；文档收尾后实际复验退出0，281结构化文件及6只读Tool定义；`git diff --check`退出0 |
| 服务 | `pnpm ops status`退出0，platform24176/runtime20908/worker25396/web2540均200/200，保持dev/postgres/jsonrpc、platform-dev/rig-openai与history=true。未重启服务或修改Docker |

中间失败如实保留：关闭guard首次放在SourceSnapshots的pending声明前，TypeScript报告声明/赋值顺序错误，移到状态声明后修复。第二组专项23通过/1失败，新增清理回执Fixture误匹配`/requests/`而实际契约是`/runs/`，修正Fixture路径；没有以空回执、隐藏重试或修改业务门禁解决，最终完整315通过。去除目录旧最小高度后再次完整315通过。截图发现新对话框受基础样式影响位于左上，修正为居中并补几何断言；随后完整311通过/4失败，旧输出用例假定发布后仍选中输出节点，实际页面回到输入节点。改为等待工作流名称不可编辑并显式打开输出配置，继续验证输出类型不可修改，定点19通过。实际浏览器返回发现隐藏原生modal仍使文档inert；外壳暂停/恢复dialog展示并监测后台新打开的modal，保留字段，抽屉专项1通过；最终316全量通过。所有失败未计为通过。

本轮未执行完整Java/PG/VM集成、Go专项、Rust fmt、真实模型/IdP/TLS、非Windows实机、远程CI或生产部署；纯领域与浏览器Fixture不替代这些检查。保持原M0–M4退出状态及业务边界。本轮日志/截图在忽略目录`.tmp/dev-checks/layout87-*`，Playwright错误上下文在忽略的test-results目录，不提交业务正文、凭据、缓存、截图或构建产物；未提交或推送。

## 88. 页签与面包屑层级优化（2026-10-02）

按用户确认的顺序调整为顶部工具栏→页签→面包屑→页面内容；标准布局省略页签。搜索移到工具栏左侧，主题、布局设置和实际会话模式保留右侧。PageBreadcrumb为独立展示组件，使用现有路由目录、nav/ol与aria-current；面包屑桌面36px、手机32px，11px正常字重，与内容容器左边缘对齐。删除旧导航样式的面包屑覆盖，内容顶部间距由面包屑承担。正文独立滚动时位置仍可见；未修改依赖、后端、契约、身份或关闭门禁。

| 实际检查 | 本轮结果 |
|---|---|
| 契约 | `.tmp/mvp-check-venv/Scripts/python.exe -X utf8 -m pytest tests/contracts -o addopts= -q`，904通过，12.35s，退出0 |
| Java纯领域 | `javac @.tmp/dev-checks/ui83-domain.args`，随后实际运行rg定位的48个main，1391 checks passed，退出0；既有unchecked编译提示与失败路径预期日志保留，不等于完整Java/PG集成 |
| Rust | 隔离`CARGO_TARGET_DIR=.tmp/dev-checks/rust78-target`，`cargo test --workspace --locked -j 4`默认7+9+25=41通过，`--all-features`15+9+25=49通过，均退出0 |
| TypeScript/构建 | `pnpm typecheck:web`退出0；`pnpm test:web`入口实际运行`pnpm build`（tsc --noEmit + Vite生产构建）成功后启动4173预览，保留既有大chunk警告，无依赖升级 |
| 完整浏览器 | 显式本机Chrome路径，`pnpm test:web`316通过，4.1m，退出0。增强原导航与布局用例，验证18产品页、标准/多页签、浅深主题及1440/1024/390px的工具栏/页签/面包屑/内容几何顺序、左边缘、紧凑高度和当前页语义；滚动与切回草稿时面包屑仍可见。原草稿、查询、历史、键盘、关闭、身份、待确认及业务门禁回归均通过 |
| 本地自动会话 | 显式本机Chrome，`pnpm test:web-local`6通过，11.6s，退出0；业务响应为显式Fixture，不替代真实集成验收 |
| 实际界面 | 复用独立预览页，经真实5173本地会话读取已有12份来源配置；切到资产再用页签返回，当前位置同步。保存并核对两种布局、三种宽度、浅深主题截图，无整页横向溢出；桌面实际工具栏48px、页签40px、面包屑36px，手机标准面包屑32px、内容起点80px。只读/GET及界面偏好检查，没有保存、发布、采集、清理或调用模型。原用户页未通过浏览器刷新、导航或输入，Vite正常分发热更新；不据此宣称未保存字段跨代码热更新验证。恢复浅色多页签与临时视口，保留预览 |
| 服务 | `pnpm ops status`退出0，platform24176/runtime20908/worker25396/web2540均存活/就绪200/200，原dev/postgres/jsonrpc、platform-dev/rig-openai、history=true配置不变；未重启服务或改Docker |
| 静态 | 文档同步后实际执行`scripts/check_repo.py`，281个结构化文件及6个只读Tool定义通过；`git diff --check`退出0 |

本轮未执行完整Java/PG/VM集成、Go专项、Rust fmt、真实模型/IdP/TLS、非Windows实机、远程CI或生产部署，不提高M0–M4退出状态。前端规范、控制台说明、实现状态及路线图同步。日志/截图仅在忽略目录`.tmp/dev-checks/layout88-*`；不提交业务正文、凭据、缓存、截图或构建产物，未提交或推送。

## 89. 可编辑DAG画布、实体执行与任务启停（2026-10-02）

按用户要求继续实现可拖入节点、连线、分支合流和运行管理。编辑、发布、运行分别管理；没有新增消息集群或流处理基础设施。研究原件保存在仓库外。

图定义兼容旧单链，支持最多16节点、32边，固定SOURCE→MAP与VALIDATE→OUTPUT；普通算子单输入、多输出，MERGE显式合流。过滤分支跳过，字段冲突或任意分支错误拒绝整条记录；服务端检查端点、环、重复、可达性及预算。前端统一定义/布局状态，X6只显示和上报事件；抽离WorkflowOperatorPalette、WorkflowConnections、WorkflowRuntimePanel及WorkflowRuntimeSection。拖入、按钮添加、端口连线、可访问连接表单、删除、撤销和重排共用状态，发布版本只读，修改需创建下一版。

Java平台提供固定ENTITY版本运行，先取得只读RUN回执，再显式写入实体。服务端核对15分钟有效期、版本/模型/输入摘要、完整批次、来源及每个实体的权限；原回执UUID为幂等键，改输入/设置拒绝。独立workflow来源按稳定输入标识构造实体ID，不按名称/IP合并Zabbix资产。资产记录source、sourceInstanceId、明确dataMode、lastSeen、固定流程pin及执行引用。库存与执行回执事务独立，部分失败只记录已确认写入，超时保留原键供显式确认，禁止隐藏重试。

任务仅loopback/dev/PostgreSQL启用，调度器通过配置的可信PrincipalResolver重新授权，不存浏览器Token或从任务构造身份。ZABBIX_HOST发布版本可显式启停，每5秒消费启动后新完成的已有批次，按completedAt/id游标最老优先；不发起采集、不回放历史。超过5条、不完整、截断、Raw缺失、来源失败或授权撤销持久标记FAILED；重启恢复RUNNING，失败需显式重启。CAS generation与租户事务锁覆盖控制/执行；停止等待已开始批次完成后返回。共享并发2、每用户20任务/200回执和50待处理批次上限。V032–034依次迁移并提供人工逆序回滚，受限运行角色只获必要表权限；回滚不截断超限trace。不是分布式租约或HA工作流引擎。

| 实际检查 | 本轮结果 |
|---|---|
| 契约 | `.tmp/mvp-check-venv/Scripts/python.exe -X utf8 -m pytest tests/contracts -o addopts= -q`，最终916通过，5.22s，退出0；覆盖MERGE、DAG及闭集运行请求/任务/执行契约 |
| Java纯领域 | `scripts/check_java_domain.py`使用Java21参数文件编译并实际运行50个main，1440 checks passed，退出0；新增WorkflowGraphSmoke 19项、WorkflowRuntimeSmoke 30项。修复Windows过长javac命令，未添加业务Python后端 |
| 完整Java/PG/VM | 原本机PG15439、VictoriaMetrics18428就绪后，`gradlew.bat :apps:platform-api:test :apps:ingestion-worker:test :apps:platform-api:bootJar :apps:ingestion-worker:bootJar --offline --console=plain --rerun-tasks`最终成功，2m14s。实际XML汇总平台261+Worker24=285，failures/errors/skipped均0；包含3项新WorkflowRuntimeHttpIT，实际写入/来源元数据、租户隔离、完成游标、任务启动/停止/下一批不执行。HTTP来源批次为显式合成Fixture，不冒充真实厂商任务整链 |
| Rust | 隔离`CARGO_TARGET_DIR=.tmp/dev-checks/rust78-target`，`cargo test --workspace --locked -j 4`默认7+9+25=41通过；`--all-features --locked -j 4`共15+9+25=49通过，均退出0。其后仅修改Java/TS与文档，未修改Rust |
| TypeScript/生产构建 | 最终`pnpm typecheck:web`退出0；`pnpm test:web`入口实际执行tsc/Vite生产构建后测试。保留既有大chunk提示，未升级依赖 |
| 完整浏览器 | 显式本机Chrome，最终`pnpm test:web`322通过，4.4m，退出0。覆盖原316项与拖入算子、实际X6端口鼠标连接、分支配置/保存/预览/发布、执行回执、任务CAS启停、待确认原键及身份迟到响应清理；接口响应为显式Fixture。此前全量322通过后，最终路由/来源标签/迟到响应修正又全量322通过，后一次为交付结果 |
| 真实本地执行 | 浏览器经5173真实会话→Java→PG，保存、预览、发布`[LOCALTEST] 可编辑画布与实体运行`六节点/六边的分支合流v1（flow-f35a4e06），手工执行确认写入1条Service。服务恢复后发布图及第一份执行回执仍可读；补齐来源字段后再次显式执行，资产页按标记名称查询只有1条，稳定ID `536897e3-67c4-3b0e-97b7-013213b856e1`、ACTIVE、版本2、workflow.flow-f35a4e06/MANUAL_SAMPLE、固定版本摘要及新执行引用均读回。测试记录明确LOCALTEST，没有执行用户原流程或调用模型 |
| 视觉/组件 | 独立检查页核对1440/1024/390px与浅深主题，实际body宽与视口一致，main无横向溢出；沿用页签在面包屑之上的布局。资产查询、图与运行面板跨页签字段/结果保留，恢复浅色主题和默认视口，保留独立预览。截图仅放忽略目录；应用重启期间旧浏览器标签消失，后续新建独立检查页，不宣称原未保存页跨应用重启恢复 |
| 服务 | Docker原容器恢复后`pnpm ops start all`退出0；交付前`pnpm ops status`再次退出0，platform8308/runtime15556/worker19428/web26000均存活/就绪200/200。保留dev/postgres/jsonrpc、platform-dev/rig-openai和history=true；Worker恢复原启用流，不将探针就绪等同真实模型/任务整链验收 |
| 静态 | 文档同步后`scripts/check_repo.py`退出0，287个结构化文件及6个只读Tool定义通过；`git diff --check`退出0，只有既有CRLF转换提示 |

中间失败保留：领域编译曾缺TenantId import，修正后通过；游标测试在Windows同一时钟刻度构造旧批次导致失败，明确旧批次完成后再启动，并以最大UUID排除启动时刻旧记录。实际UI发现旧`.workflow-connections`全覆盖样式与连接编辑区重名，改用独立组件类名；早期相关浏览器用例沿用旧默认节点/只读状态假设，修正操作顺序并完成最终322项。早期完整Java运行误用VictoriaMetrics8428导致失败，改为原18428后通过；续作时Docker与四应用均停止，Java依赖检查失败5m13s，不计为通过。恢复依赖后强制重跑全部Java测试得到上述285项零跳过结果。

环境恢复：官方Docker Desktop启动日志定位run/dockerInference与docker-secrets-engine/engine.sock残留零字节AF_UNIX socket。只停止本轮失败启动的进程；原生PowerShell移动前检查两个绝对目录、无目录链接且仅零字节socket，保留`run.opsweave89-stale`和`docker-secrets-engine.opsweave89-stale`备份。合并修复命令返回-1且无输出，核对未改变后拆开执行成功；没有重置Docker、删除卷、迁移容器数据或更换凭据。原PG/VM/Zabbix容器恢复，随后启动四应用，未静默回退Mock。

本轮未执行迁移人工回滚、受限数据库角色专项拒绝测试、Go专项、本地自动会话专项、Rust fmt、真实Zabbix连续任务整链、模型/IdP/TLS、非Windows实机、远程CI、生产部署或压测。仅本地开发实体运行完成；持续日志/指标写入、关系输出、生产身份委托和分布式租约仍待实现，不提高M0–M4退出状态。契约、ADR-061、前端规范、操作说明、架构、实现状态与路线图同步。原始日志/截图在忽略目录`.tmp/dev-checks/workflow89-*`，不提交凭据、业务正文、缓存或构建产物；未提交或推送。

## 90. 默认节点间距与端口连线修正（2026-10-02）

用户反馈默认线挤在一起。实际读取当前来源草稿和SVG路径，发现96px节点使用116px行距，20px空隙小于两侧端口圆点及路由padding的膨胀范围，orth路由生成短回折；默认boundary连接点还从圆点侧边裁切。改为160px行距、64px空隙，分支行围绕主干居中；连接对齐端口中心，输入端留6px箭头空间，旧单链padding降至4px避免20px间距回折。DAG Manhattan限定下出上入、关闭perpendicular偏移，实际绕过中间节点。默认排列不会自动替换已保存或手动坐标，显式排列仍可撤销；定义、权限、数据请求与固定版本门禁保持。

| 实际检查 | 本轮结果 |
|---|---|
| 契约 | `.tmp/mvp-check-venv/Scripts/python.exe -X utf8 -m pytest tests/contracts -o addopts= -q`，916通过，11.66s，退出0；本轮没有契约改动 |
| Java纯领域 | `scripts/check_java_domain.py`实际编译运行50个main，1440 checks passed，退出0；不是完整Java/PG测试 |
| Rust | 隔离`CARGO_TARGET_DIR=.tmp/dev-checks/rust78-target`运行默认及all-features `cargo test --workspace --locked -j 4`，默认7+9+25=41、all-features15+9+25=49通过，均退出0 |
| TypeScript/生产构建 | 最终`pnpm typecheck:web`退出0；关联浏览器入口实际运行tsc/Vite生产构建，保留已有大chunk警告，未升级依赖 |
| 关联浏览器 | 显式本机Chrome，`pnpm test:web workflows.spec.ts workflow-outputs.spec.ts graph-workspace.spec.ts`最终30通过，35.0s，退出0。新增1440/1024/390px明暗主题的实际节点空隙、直线路径、页面宽度及无隐式请求检查，另检验合流旁路按显示端点定位，采样真实SVG路径不穿过中间节点；原拖入/端口连接、坐标保存、键盘、撤销、发布只读、执行/任务/身份及输出回归通过。接口为显式Fixture |
| 真实界面 | 原用户页只读核对，热更新后名称“Zabbix 主机接入”、五节点及手动坐标逐项保持，没有刷新、导航、重排、保存或执行。独立来源预览实际读取同一PG来源，核对新默认五节点的四条SVG路径均垂直无回折，1440/1024/390px与浅深主题无整页横向溢出；手机实际空隙约34px。独立页添加未保存MERGE/旁路，仅内存编辑，SVG外侧绕线可见。恢复浅色主题与原视口并关闭检查页，不产生来源/工作流/资产写入或模型调用 |
| 静态/服务 | 文档同步后`scripts/check_repo.py`退出0，287个结构化文件/6个只读Tool定义通过；`git diff --check`退出0，只有既有CRLF提示。`pnpm ops status`退出0，platform8308/runtime15556/worker19428/web26000均存活/就绪200/200；本轮未重启服务或修改Docker |

中间失败如实保留：第一轮26通过/3失败，新增检查引用Fixture调用记录中不存在的method字段，改查实际唯一目录请求后通过。随后两轮各29通过/1失败，旁路检查先用SVG最后一条、再假定edge-5；实际orderGraph按拓扑排序，旁路为较前边。改为通过连接表中显示的端点定位相应SVG路径，保留原不穿节点及外侧绕行的几何断言，最终30项通过。CUA只读DOM包装不提供getBBox，用可用的getBoundingClientRect读取实际直线路径宽度；浏览器回归中仍使用原生SVG几何API。失败运行不计为通过。

本轮没有后端、契约或依赖改动，未执行完整Java/PG/VM、全量浏览器套件、本地会话专项、Go专项、Rust fmt、实际采集/模型/IdP/TLS、非Windows实机、远程CI或生产部署，不把第89节结果计作本轮检查。同步前端规范、工作流说明、实现状态与路线图，M0–M4退出状态不变。输出和截图仅在忽略目录`.tmp/dev-checks/workflow90-*`；未提交或推送。

## 91. 画布底部精简与响应式配置面板（2026-10-02）

用户反馈画布下方内容堆积。移除编辑器里的重复工作流库、重复输出摘要与全局执行说明；列表与版本继续由顶部返回入口访问，真实能力限制仍在输出节点/运行操作附近。添加步骤和连接管理移至工具栏，测试区保留紧凑折叠入口；发布ENTITY后的运行管理保留。新增WorkflowInspector展示组件：桌面内联右侧，760px及以下用原生非模态Popover侧面板，点击节点或添加算子打开；Esc/关闭及外部点击收起，字段由页面持有。连接管理用独立Popover，切离页签关闭顶层浮层。X6重建HTML节点后按节点ID返回当前按钮焦点。HMR同地址、已有读取或dirty草稿不再自动发送加载请求。

| 实际检查 | 本轮结果 |
|---|---|
| 契约 | `.tmp/mvp-check-venv/Scripts/python.exe -X utf8 -m pytest tests/contracts -o addopts= -q`，916通过，9.01s，退出0；没有契约修改 |
| Java纯领域 | `scripts/check_java_domain.py`实际运行50个main，1440 checks passed，退出0；不是完整Java/PG集成 |
| Rust | 隔离`CARGO_TARGET_DIR=.tmp/dev-checks/rust78-target`；`cargo test --workspace --locked -j 4`默认7+9+25=41，`--all-features --locked -j 4`15+9+25=49通过，均退出0 |
| TypeScript/生产构建 | 最终`pnpm typecheck:web`退出0；最终浏览器复验入口实际执行tsc/Vite生产构建后测试，保留既有大chunk警告，未升级依赖 |
| 关联浏览器 | 显式本机Chrome，`pnpm test:web workflows.spec.ts workflow-outputs.spec.ts integration-lists.spec.ts graph-workspace.spec.ts workspace-layout.spec.ts source-center.spec.ts`最终65通过，1.4m，退出0；包含三个尺寸、明暗主题、配置/样本保留、Esc/焦点、添加节点、图操作、来源、列表/版本、保存/预览/发布只读与运行/任务/身份门禁、页签/迟到响应及非活动Popover关闭。响应明确Fixture |
| 最终样式复验 | 紧凑测试/运行行及手机连接浮层边距调整后，`pnpm test:web workflows.spec.ts workspace-layout.spec.ts`加`--grep`选择配置、跨页浮层、实体执行及来源任务启停，实际7通过，14.2s，退出0；重新生产构建成功 |
| 实际界面 | 独立本地来源检查页通过真实5173→Java→PG只读载入原Zabbix来源，核对1440/1024/390px、浅深主题、右侧/窄屏配置与连接浮层、Esc返回焦点、紧凑测试入口；实际页面宽等于视口，重复库/输出摘要为0。没有保存、预览、发布、采集、实体写入或模型调用；未把Fixture回归当真实运行验收。原用户标签页未刷新/导航，本轮初次Hook结构调整触发热更新重建来源本地模板，因此不宣称既有手工编辑跨该次HMR保留；后续修复同地址Effect的重复加载。独立检查页恢复浅色与默认视口并关闭 |
| 服务 | `pnpm ops status`退出0，platform8308/runtime15556/worker19428/web26000存活/就绪200/200，原dev/PG/jsonrpc、rig-openai及history=true保持；本轮没有重启服务或操作Docker |
| 静态 | 文档同步后`scripts/check_repo.py`退出0，287个结构化文件/6个只读Tool定义通过；`git diff --check`退出0，仅有既有CRLF提示 |

中间失败如实保留：首轮64项中60通过/4失败，发现X6字段变更重绘后旧触发按钮已脱离DOM，以及窄屏新增节点未打开配置；已修复。旧回归仍在抽屉覆盖时直接点击保存、在已移除的编辑内历史区查版本测试，调整为收起配置再操作，并到原版本列表核验历史。随后64项通过；新增跨页Popover关闭/字段保留及Effect加载保护后65项通过，最终样式7项复验通过。CUA检查阶段一次关闭控件点击因热更新已收起面板而未匹配，读取新状态后继续检查，未强制刷新用户页。交付前原用户页的本地会话到期，身份订阅按设计清理了本地画布；显式点击“重新连接”后，原来源地址重新读取并准备Zabbix主机五节点本地草稿，保存按钮可用，未执行工作流写入。

前端规范、工作流说明、实现状态与路线图同步。未改后端/契约/依赖，未运行完整Java/PG/VM、全量浏览器、本地会话专项、Rust fmt、Go专项、真实持续采集/写入/模型/IdP/TLS、非Windows实机、远程CI或生产部署；不把旧检查计作本轮，不改变M0–M4退出状态。日志/截图仅在忽略目录`.tmp/dev-checks/workflow91-*`，未提交或推送。

## 92. 节点按需配置抽屉与右键菜单（2026-10-02）

用户要求右侧配置在点击节点时打开，或由右键菜单选择编辑后打开。WorkflowInspector移除桌面常驻列及窄屏专用判断，所有尺寸默认关闭；显式节点/输出/添加操作打开原生非模态Popover抽屉，Esc、关闭与外部点击收起，页面持有字段状态。抽屉宽度受视口约束，内部配置独立滚动。抽离WorkflowNodeMenu供X6画布与步骤视图共用：右键仅打开菜单，不直接打开配置；Shift+F10/ContextMenu键和Enter可完成编辑，发布/忙碌状态显示查看，既有字段锁定不变。菜单位置限制在视口内，切离缓存页关闭浮层；配置关闭后返回当前节点按钮，兼容X6字段修改后重建HTML与已隐藏的菜单触发项。不新增业务请求、执行类型、契约或依赖。

| 实际检查 | 本轮结果 |
|---|---|
| 契约 | `.tmp/mvp-check-venv/Scripts/python.exe -X utf8 -m pytest tests/contracts -o addopts= -q`，916通过，7.62s，退出0；没有契约修改 |
| Java纯领域 | `scripts/check_java_domain.py`实际运行50个main，合计1440 checks passed，退出0；不是完整Java/PG集成 |
| Rust | 隔离`CARGO_TARGET_DIR=.tmp/dev-checks/rust78-target`；`cargo test --workspace --locked -j 4`默认7+9+25=41，`--all-features --locked -j 4`15+9+25=49通过，均退出0 |
| TypeScript/生产构建 | `pnpm typecheck:web`退出0；最终浏览器入口实际执行tsc/Vite生产构建，保留既有大chunk警告，没有依赖升级 |
| 关联浏览器 | 显式本机Chrome，`pnpm test:web workflows.spec.ts workflow-outputs.spec.ts integration-lists.spec.ts graph-workspace.spec.ts workspace-layout.spec.ts source-center.spec.ts`最终71通过，1.6m，退出0。新增1440/1024/390px×画布/步骤共6项，均覆盖明暗主题、左键/右键/键盘、默认关闭、字段修改保留、Esc返回新节点按钮、视口与无隐式请求；原发布只读、拖拽/连线/保存、执行/任务/身份门禁、来源/版本列表与缓存页回归通过。接口为显式Fixture |
| 实际界面 | 独立检查页实际读取5173→Java→PG的来源配置，核对桌面完整画布、右键编辑抽屉、Esc返回节点焦点、1024px步骤视图及390px明暗主题；面板边界均在视口内，整页无横向溢出。未保存、预览、发布、启动采集、写入实体或调用模型；没有把Fixture执行回归称为真实运行验收。原用户缓存草稿未刷新、导航或替换；其已有“请先保存当前修改”提示保留，未绕过保护。检查页恢复浅色与原视口并关闭 |
| 服务 | `pnpm ops status`退出0，platform8308/runtime15556/worker19428/web26000均存活/就绪200/200，原模式保持；本轮没有重启服务或操作Docker |
| 静态 | 文档同步后`scripts/check_repo.py`退出0，287个结构化文件/6个只读Tool定义通过；`git diff --check`退出0，仅有既有CRLF提示 |

中间失败如实保留：首轮71项中69通过、2失败。两条旧用例在新抽屉/既有连接浮层仍打开时点击被遮挡的底层按钮，分别补上显式关闭配置、Esc收起连接管理；保留日志输出/XSS与旁路绕线的原断言，最终71项通过。不计失败运行为通过。

同步前端规范、工作流说明、实现状态及路线图。本轮未修改后端/契约/依赖，未执行完整Java/PG/VM、全量浏览器、本地会话专项、Rust fmt、Go专项、真实持续采集/写入/模型/IdP/TLS、非Windows实机、远程CI或生产部署；不把旧检查计作本轮，不改变M0–M4退出状态。日志/截图仅在忽略目录`.tmp/dev-checks/workflow92-*`，未提交或推送。

## 93. 创建接入的配置、字段、指标目录与教程（2026-10-02）

用户要求优化创建接入中的指标字段与教程。来源介绍、配置、指标表与分步说明分别管理；研究原件保存在仓库外，不能把设计核对作为本项目采集验收。

新增`SourceSetupDrawer`，默认配置，另设数据字段、监控指标和接入教程；桌面基本信息/来源双列，窄屏单列，头部和操作底栏固定、正文独立滚动。名称、说明及来源确认仍由页面持有，切页签不清空，方向键/Home/End及Esc/焦点返回保留。`SourceFieldReference`复用现有模型与telemetryFields，Host只列当前后端提供的name/ip/lifecycle/entity_id，手工样本可查实体/指标/日志格式、要求与Fixture例子，不改变确认命令或流程输出。`SourceSetupGuide`按来源能力给出保存、映射、预览、发布步骤及常见问题。输出选择仍在画布输出节点。

`SourceMetricCatalog`是页面请求容器，经现有可信会话和`readCatalog`显式读取授权目录，无新API。展示指标名称/键、来源字段、单位、类型和固定映射版本，支持搜索；区分未读取、失败、空目录与无匹配。关闭/切离/身份变化取消请求，迟到响应不恢复旧主体；没有自动重试或Mock回退。该目录为定义，不能当当前来源已启用的指标、在线状态或连续采样验收。样式集中`source-setup.css`，不改变共享页面外壳。

| 实际检查 | 本轮结果 |
|---|---|
| 契约 | `.tmp/mvp-check-venv/Scripts/python.exe -X utf8 -m pytest tests/contracts -o addopts= -q`，916通过，9.13s，退出0；本轮无契约改动 |
| Java纯领域 | `scripts/check_java_domain.py`实际编译运行50个main，合计1440 checks passed，退出0；不是完整Java/PG集成 |
| Rust | 隔离`CARGO_TARGET_DIR=.tmp/dev-checks/rust78-target`，`cargo test --workspace --locked -j 4`默认7+9+25=41，`--all-features --locked -j 4`15+9+25=49通过，均退出0 |
| TypeScript/生产构建 | 最终`pnpm typecheck:web`退出0；关联浏览器入口实际执行tsc/Vite生产构建，保留已有大chunk警告，未升级依赖 |
| 关联浏览器 | 显式本机Chrome，`pnpm test:web source-center.spec.ts integration-lists.spec.ts workflows.spec.ts workflow-outputs.spec.ts graph-workspace.spec.ts workspace-layout.spec.ts model-catalog.spec.ts`实际85通过，2.0m，退出0。覆盖三种尺寸、明暗主题、字段/样本/名称保留、键盘、保存恢复、只读/发布/身份和图操作门禁，以及指标目录失败、显式重试、关闭后迟到响应取消；接口明确Fixture |
| 最终复验 | 最后调整页签/字段格式按钮样式并增加选中背景断言后，`pnpm test:web source-center.spec.ts integration-lists.spec.ts`实际26通过，32.4s，退出0；入口重新生产构建。包含1440/1024/390px×Host/JSON的明暗主题、四页签、格式/搜索、无隐式目录请求、错误及焦点保留。没有重跑全量浏览器 |
| 真实界面 | 原用户来源页未刷新或导航。独立检查页读取真实5173→Java→PG来源目录，创建抽屉仅内存查看，实际读取平台目录的3项指标定义；核对1440/1024/390px、浅深主题、双列配置、字段表、教程和固定底栏，无整页横向溢出。未保存、测试连接、预览、发布、启停采集、写入实体或调用模型。原始截图在忽略目录source93-config.png/source93-fields.png |
| 服务 | 首次状态退出0、四服务200/200；续作复查时均已停止。依赖恢复后第二次`pnpm ops start all`退出0，最终`pnpm ops status`退出0，platform18224/runtime11420/worker24076/web7708均存活/就绪200/200，原dev/PG/jsonrpc、rig-openai及history=true配置保持。恢复过程另记下文，不把探针当完整运行验收 |
| 静态 | 文档同步后`scripts/check_repo.py`退出0，287个结构化文件和6个只读Tool定义通过；`git diff --check`退出0，仅有既有CRLF提示 |

实际视觉检查发现遗留接入页通用按钮规则覆盖专用页签和格式按钮，造成块状页签及弱选中态；专用原生按钮添加data-slot标记避开该规则，改为紧凑下划线页签与清晰的格式选中背景，真实界面复核和最终26项通过。两轮浏览器检查均通过，没有将编写用例等同测试通过。远端绑定曾两次超时，恢复后只读确认登录/验证码，不用工具超时推断其业务页面布局。

续作服务恢复：四单元及Docker均未运行，第一次`pnpm ops start all`退出1，平台报数据库迁移连接失败、Worker报VictoriaMetrics的SINK_FAILED，Runtime和Web启动成功；该失败不计为就绪。现有Docker Desktop隐藏启动后，官方日志依次定位`AppData/Local/Docker/run/dockerInference`与`AppData/Local/docker-secrets-engine/engine.sock`残留运行socket。通过官方`docker desktop stop --force --timeout 10`停止本轮启动的Docker，检查绝对目录无目录链接、内容仅已核对的零字节运行socket，以原生PowerShell移动同级目录并保留`run.opsweave93-stale`、`run.opsweave93-retry`、`docker-secrets-engine.opsweave93-stale`备份；没有删除卷、重置Docker或修改配置。首次合并命令返回-1且无输出，复核未改变后拆开执行退出0；fsutil查询返回1920，不将查询失败当检查通过。重新启动后，原PG/VM及Zabbix六个容器恢复，`docker ps -a`退出0，PG与Zabbix Web健康。

浏览器中断后本轮临时检查页已自动关闭，视口reset成功后读取原错误页被浏览器URL协议策略拒绝；没有通过其他浏览器或API绕过该读取限制，也未强制刷新原用户页。真实界面截图和上述交互检查来自服务恢复前的实际页面，不将状态探针当作完整浏览器/采集验收。

同步前端规范、来源操作说明、实现状态与路线图。没有后端、契约或依赖变更，未执行完整Java/PG/VM、全量浏览器、本地会话专项、Go专项、Rust fmt、真实连续采集/存储/模型/IdP/TLS专项验收、非Windows实机、远程CI或生产部署，不提高M0–M4退出状态。服务恢复后未重跑85项浏览器，不将恢复前检查称为恢复后的重验。日志/截图仅在忽略目录`.tmp/dev-checks/source93-*`，未提交或推送。

## 94. 左侧节点库、精简操作与完整指标定义（2026-10-02–03）

按用户要求浏览[Node-RED官方节点库说明](https://nodered.org/docs/user-guide/editor/palette/)与[React Flow官方侧栏拖入示例](https://reactflow.dev/examples/interaction/drag-and-drop)，采用分类/搜索的左侧节点库和拖入/点击两种添加方式，继续使用已有X6适配器。WorkflowOperatorPalette只列7个已实现算子；桌面默认展开210px，≤1000px按需浮层，跨入窄屏收起，步骤视图共用。搜索中文名称、说明或算子ID，Esc和关闭返回添加入口；节点库事件不抢节点菜单/连接面板焦点。拖入创建待连接节点，点击沿用原插入和16节点门禁。

编辑工具栏仅保留保存、预览、发布，次要操作抽离WorkflowMoreActions；菜单按触发按钮及视口定位，禁用条件和业务事件继续由页面持有。WorkflowInspector贴右、全高、无居中圆角窗，仍为按需非模态Popover；默认关闭、节点点击/右键编辑、发布只读和字段保留不变。显示节点标识/算子版本，说明TRIM全部文本且无额外参数、MERGE冲突拒绝；实体字段完整显示模型ID+字段ID，实际短字段ID和命令不变。

SourceMetricCatalog完整指标键链接到MetricDefinitionsPage，通过既有可信会话/readCatalog读取授权元数据，MetricDefinitionDetail只展示8项返回定义属性，支持搜索、精确选择、非法/缺失/失败状态及身份/非活动请求取消；特定定义链接只自动读取一次，失败显式重试。返回目录采用显式空选择，兼容多页签地址记忆和浏览器历史；没有修改共享页面外壳。5项指标记录格式与指标种类数明确区分，当前内置目录实际3项，不虚构更多定义或已启用连续采样。

| 实际检查 | 本轮结果 |
|---|---|
| 契约 | `.tmp/mvp-check-venv/Scripts/python.exe -X utf8 -m pytest tests/contracts -o addopts= -q`，916通过，5.90s，退出0 |
| Java纯领域 | `scripts/check_java_domain.py`实际编译运行50个main，输出合计1440 checks passed，退出0；未计作完整Java/PG集成 |
| Rust | 隔离`CARGO_TARGET_DIR=.tmp/dev-checks/rust78-target`，`cargo test --workspace --locked -j 4`默认7+9+25=41，`--all-features --locked -j 4`15+9+25=49通过，均退出0 |
| TypeScript/生产构建 | 最后`pnpm typecheck:web`退出0；最终浏览器入口重新实际执行`tsc --noEmit && vite build`，已有大chunk警告保留，无依赖升级 |
| 最终关联浏览器 | 显式本机Chrome，`pnpm test:web workflows.spec.ts workspace-layout.spec.ts model-catalog.spec.ts source-center.spec.ts integration-lists.spec.ts workflow-outputs.spec.ts graph-workspace.spec.ts`实际95通过，2.0m，退出0。覆盖1440/1024/390px、明暗主题、搜索/清除/关闭焦点、拖入/连接/插入、全高抽屉、保存/发布/执行/身份门禁、完整指标链接/精确定义/缺失/无效/503显式重试及标准/多页签历史返回；接口明确Fixture |
| 复验过程 | 修正Esc及跨断点遮挡后94项通过；指标多页签返回修复后45项通过，1.0m；删除旧节点库冲突样式后最终95项重验通过。测试源码存在不计作通过 |
| 实际界面 | 独立5173检查页经Java/PG只读来源与工作流目录，核对三种宽度和浅深主题的左侧节点库、搜索、更多菜单、映射全高抽屉及完整字段名。实际指标链接显示CPU定义的host.cpu.usage.user、system.cpu.util[,user]、percent-to-ratio和固定映射版本，返回真实3项目录成功。未保存、预览、发布、探测连接、启停/采集、写入或调用模型。本地检查会话到期后显式重新连接恢复只读来源画布；未刷新/导航原用户页。截图仅忽略目录workflow94-*.png，临时视口与主题恢复、检查页关闭 |
| 服务 | `pnpm dev:status`退出0，platform18224/runtime11420/worker24076/web7708均存活/就绪200/200；本轮没有重启服务或操作Docker |
| 静态 | 文档同步后`scripts/check_repo.py`退出0，287个结构化文件/6个只读Tool定义通过；`git diff --check`退出0，仅既有CRLF提示。收尾跨本地日期，§94状态记录以2026-10-03为完成日 |

中间失败如实保留：首轮90项84通过/6失败，修正新增测试的textbox角色与顶层工具按钮选择；第二轮89通过/1失败，更新旧的桌面节点库隐藏断言。补焦点后94项88通过/6失败，发现整段流程的Esc处理抢走节点菜单/连接浮层焦点，限制到节点库自身；随后92通过/2失败，桌面展开库在切手机时遮挡节点，补跨窄屏自动收起。真实多页签检查另外发现返回目录被缓存地址拦截，使用显式空选择并补浏览器历史测试。上述失败不计通过，最终完整95项通过。

同步Web AGENTS、前端规范、工作流/来源说明、实现状态及路线图。本轮无后端、契约或依赖变更，未执行完整Java/PG/VM、全量浏览器、本地会话专项、Go专项、Rust fmt、真实连续采集/存储/模型/IdP/TLS、非Windows实机、远程CI或生产部署；不提高M0–M4退出状态。原始输出/截图仅`.tmp/dev-checks/workflow94-*`，未提交或推送。


## 95. 删除重复文案、规范输出节点与图标对齐（2026-10-03）

输出节点按实际类别和配置参数展示，不给没有参数的算子添加通用表单。输出类型与目标模型由本项目契约约束；研究原件保存在仓库外。

删除流程页常驻点击/拖拽说明、节点库实现范围说明、模板成功后的重复教程、重复保存/预览提示和已有侧栏的来源返回入口；保留实际请求回执、错误、Fixture、未保存、只读发布与执行能力边界。悬浮字符加号改为画布工具行的SVG节点库入口，缩放及默认值加号使用现有图标库。修正高特异性通用按钮样式覆盖，实际矩形中心偏移由2px修正为0px；节点库收起/清除图标居中，输出选中状态在明暗主题可区分。

WorkflowOperatorPalette显示3类输出入口；当前类别再次点击打开配置，另一类别调用既有chooseOutput，重建默认映射/处理链并记录撤销。WorkflowOutputConfig仅实体显示完整模型ID及固定revision，日志/指标展示实际记录格式；Canvas/Sequence/Inspector统一具体输出名称。沿用契约中单一固定OUTPUT，不增加独立输出端点；Zabbix Host日志/指标禁用、实体目录缺失禁用实体、发布版本禁用编辑，保存/有效预览/发布与来源认证边界不变。

| 实际检查 | 本轮结果 |
|---|---|
| 契约 | `.tmp/mvp-check-venv/Scripts/python.exe -X utf8 -m pytest tests/contracts -o addopts= -q`，916通过，7.27s，退出0 |
| Java纯领域 | `scripts/check_java_domain.py`实际编译运行50个main，输出合计1440 checks passed，退出0；不是完整Java/PG集成 |
| Rust | 复用隔离`CARGO_TARGET_DIR=.tmp/dev-checks/rust78-target`，`cargo test --workspace --locked -j 4`默认7+9+25=41、`--all-features --locked -j 4`15+9+25=49，均退出0 |
| TypeScript/生产构建 | 最后`pnpm typecheck:web`退出0；最终浏览器入口实际执行`tsc --noEmit && vite build`，退出0，已有大chunk警告保留 |
| 关联浏览器 | 显式本机Chrome，`pnpm test:web workflows.spec.ts workspace-layout.spec.ts model-catalog.spec.ts source-center.spec.ts integration-lists.spec.ts workflow-outputs.spec.ts graph-workspace.spec.ts`95通过，2.0m，退出0；1440/1024/390px和明暗主题，清理文案/入口、图标中心、节点分类搜索/焦点、实体目录缺失、Zabbix限制、单一输出切换/撤销且不额外写请求、过期预览清除、发布只读和来源/指标/多页签行为；接口明确Fixture |
| 最终样式复验 | 95项通过后修正输出选中背景及节点库图标被基础按钮样式覆盖，执行`pnpm test:web workflows.spec.ts workflow-outputs.spec.ts --grep 'node library\|typed .*workflow\|changing output\|resizing the canvas'`，实际9项通过，19.8s，退出0；重新构建，包含三种宽度及明暗主题的输出选中背景差异和图标中心、输出切换与撤销。此后未再跑完整95项 |
| 实际页面 | 独立5173检查页只读实际Java/PG工作流目录，再准备未保存的模板。核对桌面1440px与手机390px、浅深主题、输出节点库、指标抽屉、无通用输出选择和手机无横向溢出；未点击保存、预览、发布、连接探测、启停、采集/执行或模型调用。原用户页未刷新或导航；截图仅忽略目录workflow95-*.png，检查视口/主题恢复、临时页关闭 |
| 服务 | `pnpm dev:status`退出0，platform18224/runtime11420/worker24076/web7708存活/就绪200/200，本轮未重启服务或操作Docker |
| 静态 | 文档同步后`scripts/check_repo.py`退出0，287个结构化文件及6个只读Tool定义通过；`git diff --check`退出0，既有CRLF提示保留 |

中间失败如实保留：首轮95项91通过/4失败，手机端旧返回路径改用侧栏后缺少展开步骤；三个尺寸的图标中心断言均发现通用按钮样式覆盖造成2px垂直偏移。加入输出撤销检查后第二轮仍91通过/4失败，前述修复未进入该轮产物。修正CSS选择器及手机导航后完整95项通过，再对最终输出选中背景/节点库小图标完成9项复验；失败不计作通过。

同步Web AGENTS、前端规范、工作流/来源说明、实现状态及路线图。本轮没有后端、契约、数据库或依赖变更，未执行完整Java/PG/VM、全量浏览器、本地会话专项、Go专项、Rust fmt、真实连续采集/存储/模型/IdP/TLS、非Windows实机、远程CI或生产部署；不提高M0–M4退出状态。日志和截图仅`.tmp/dev-checks/workflow95-*`，未提交或推送。

## 96. 指标定义列表、模型目录自动读取与隐藏接入维护（2026-10-03）

用户要求先隐藏接入维护、指标改列表并默认读取，还询问一级菜单是否对应前后端组件。采用表格/详情组合和授权后初始请求，不增加未接通的收藏、编辑、分页或错误转空行为。研究原件保存在仓库外。

三个旧维护路由设置navigation=false，导航/搜索共用过滤，无可见子页的接入维护分组不显示，保留原直接地址和权限。MetricDefinitionList只展示表格：名称/完整指标标识、实体类型、单位、类型、完整来源键；名称和指标键链接原授权详情。搜索仅筛选已返回目录，表格自身滚动；只有当前3项固定映射定义，不生成额外指标或样本。

useInitialPageRead在实体、关系和指标目录的活动页会话就绪后读取一次；未授权、后台和非法指标地址不请求。成功缓存切回不重读，身份变化清理旧结果并允许新有效会话读取；初次请求取消后返回可以完成读取，失败保留错误且需显式重试。403事件不会重置首次尝试门禁，避免凭据仍有效时循环重试。其余页面保持既有读取策略，保存/发布/采集/执行不自动触发。扩展指南明确一级菜单只是导航分组，React页面在静态注册表登记，Java业务模块可支撑多个页面；新增业务仍须契约/用例/API/权限等，四启动单元不变。

| 实际检查 | 本轮结果 |
|---|---|
| 契约 | `.tmp/mvp-check-venv/Scripts/python.exe -X utf8 -m pytest tests/contracts -o addopts= -q`，916通过，8.41s，退出0 |
| Java纯领域 | `scripts/check_java_domain.py`实际编译运行50个main，输出合计1440 checks passed，退出0；不是完整Java/PG集成 |
| Rust | 隔离`CARGO_TARGET_DIR=.tmp/dev-checks/rust78-target`，`cargo test --workspace --locked -j 4`默认7+9+25=41，`--all-features --locked -j 4`15+9+25=49，均退出0 |
| TypeScript/生产构建 | `pnpm typecheck:web`退出0；全量及最终浏览器入口实际执行`tsc --noEmit && vite build`，成功，既有大chunk警告保留 |
| 最终全量浏览器 | 显式本机Chrome，`pnpm test:web`，360通过，4.5m，退出0；包含三种宽度、明暗主题、15页/6分组、隐藏菜单直接地址、自动读取/本地筛选/缓存返回/身份变化/503和403显式重试/空目录/XSS/非法链接，以及全站原业务门禁；接口为显式Fixture |
| 帮助文案后复验 | 360项通过后仅修改模型中心PageGuide自动加载/点击定义说明，`pnpm test:web model-catalog.spec.ts metric-definition-list.spec.ts`，19通过，20.0s，退出0，重新构建；此后未再跑完整360项 |
| 实际页面 | 独立5173检查页使用已有本地会话，只读真实Java/PG；指标列表进入自动显示3项，点击CPU名称、内存完整键、运行时间名称并返回，实体和关系目录进入自动显示已存目录；核对1440/1024/390px与明暗主题，手机整页scrollWidth=390，默认桌面恢复1280且无整页溢出，控制台error列表为空。一次模型卡片等待器报告不可见，随后DOM快照明确显示授权目录；未把等待器失败计作通过。最终截图仅忽略目录catalog96-list-final.png，临时检查页关闭，用户原页未导航或刷新 |
| 服务 | `pnpm dev:status`退出0，platform18224/runtime11420/worker24076/web7708存活/就绪200/200，无重启/Docker操作 |
| 静态 | 文档同步后`scripts/check_repo.py`退出0，287个结构化文件及6个只读Tool定义通过；`git diff --check`退出0，既有CRLF提示保留 |

中间失败分开记录：第一轮关联浏览器38通过/4失败，均为导航仍断言18入口/7分组；修正为15/6。首轮全量356通过/4失败：新403用例错误期待数字状态码，而产品显示明确权限提示，另三项页签布局仍按旧18页断言；修正断言后完整360项通过。失败不计作通过。

同步Web AGENTS、前端规范、能力扩展与模型/导航/控制台说明、实现状态及路线图。没有后端、契约、数据库或依赖变更，未执行完整Java/PG/VM、本地会话专项、Go专项、Rust fmt、真实连续采集/存储/模型/IdP/TLS、非Windows实机、远程CI或生产部署；不提高M0–M4退出状态。日志和截图仅`.tmp/dev-checks/catalog96-*`，未提交或推送。

## 97. 本机产品审查资料与通用来源隔离规则（2026-10-03）

按用户要求完成仓库外本机产品审查稿与合成交互设计稿，内容覆盖对象/范围、UI/交互、组件分层、契约/版本/运行、验收、实施顺序和审查决策。资料、原始证据、截图、日志与私有词表均不进入仓库；待用户审查后再实施目标功能。仓库记录只保留本项目实现、通用规则和实际检查；历史逐项研究记录移到仓库外，历史检查数量与结果保留。

根AGENTS增加通用来源隔离要求；前端规范改为本项目布局、组件和能力边界。新增`scripts/check_reference_boundary.py`：私有词表必须在仓库外，检查Git跟踪/未忽略提交候选的文件路径及内容，规范化大小写和兼容字符；路径越界、大文件或私有策略错误使检查失败，不输出命中正文或词表。11项测试覆盖词表约束、规范化、忽略/强制暂存、路径保护和扫描上限。没有编码隐藏来源身份、改写Git历史或扫描第三方缓存；CI尚未接入私有词表，不能声称已在CI强制。

| 实际检查 | 本轮结果 |
|---|---|
| 契约 | `.tmp/mvp-check-venv/Scripts/python.exe -X utf8 -m pytest tests/contracts -o addopts= -q`，927通过，20.60s，退出0，包含11项检查器测试 |
| Java纯领域 | `scripts/check_java_domain.py`实际编译运行50个main，合计1440 checks passed，退出0；不是完整Java/PG集成 |
| Rust | 隔离`CARGO_TARGET_DIR=.tmp/dev-checks/rust78-target`，`cargo test --workspace --locked -j 4`默认7+9+25=41，`--all-features --locked -j 4`15+9+25=49，均退出0 |
| TypeScript / 生产构建 | `pnpm typecheck:web`和`pnpm build:web`退出0，构建1.68s；保留既有大chunk警告，无依赖升级 |
| 静态结构 | `scripts/check_repo.py`，289结构化文件/6只读Tool定义通过，退出0 |
| 差异 / 来源隔离 | `git diff --check`退出0；私有策略扫描最终2057个候选通过，退出0；原始输出只在仓库外 |
| 合成设计稿 / 本机文档 | 实际浏览器检查1440/1024/390px、浅深主题、标准/多页签、创建字段/指标/教程与必填、节点非模态抽屉、预览、完整指标定义和任务/质量展示；没有真实服务连接。25项文档资产、205处本机链接及原型JS语法检查通过 |

首次扩展私有词表误命中无关的通用技术术语，来源检查退出1；收窄词表后实际重跑通过，失败输出保留在仓库外，不计通过。文档展示与合成设计稿不是当前产品全量浏览器E2E或真实执行验收。

本轮无新产品运行能力、依赖/数据库/业务契约变更，未执行完整Java/PG/VM、全量项目浏览器、本地会话专项、Go专项、Rust fmt、真实连续采集/存储/模型/IdP/TLS、多副本故障、非Windows实机、远程CI或生产部署。未重启项目四服务，没有远端保存/发布/启停业务，未提交或推送；M0–M4退出状态保持。


## 98. 接入列表、可见目录与最新版本导航（2026-10-03）

按开始实施指令落地配置与预览体验：未设置偏好默认多页签，保留明确标准偏好；数据源默认已配置表，类型目录为第二页签，紧凑卡片、键盘分类导航、不同列表状态、Fixture/样本与截断标记。SourceSetupDrawer收窄至760px；指标参考实际可见后自动读一次，切换页签保留筛选和结果，关闭/身份变化取消。工作流列表也在活动授权会话首次自动读取，失败/403/缓存返回不重复，dirty与非法深链接门禁保留。

新增source-setup-continuation Schema/样例/受控只读接口，先授权并读取本人创建记录，再选择本人草稿与租户可读发布版中的最高revision，同版优先发布；不含他人私有草稿，不修改旧初版查询/不可变回执。客户端校验setupId与工作流绑定，跳转固定id/revision/state并重新读取。服务端旧式初版坐标同步160px行距。来源确认结果成功或原请求查询核对一致后更新有界列表并解除待确认门禁；不一致保留原命令，禁止自动写重试。

| 实际运行检查 | 结果与范围 |
|---|---|
| 契约 | `.tmp/mvp-check-venv/Scripts/python.exe -X utf8 -m pytest tests/contracts -o addopts= -q`，934通过，23.16s，退出0；包含新响应Schema、样例和拒绝身份/远端字段 |
| Java纯领域 | `scripts/check_java_domain.py`编译运行50个main，1449 checks passed，退出0；SourceSetupSmoke 31项覆盖新草稿/发布选择、保持初版、跨主体/租户、权限、他人私有草稿、无版本 |
| 实际HTTP | `gradlew :apps:platform-api:test --tests '*SourceContinuationHttpIT' --no-daemon --console=plain`，1项、0失败/跳过，44s，退出0；真实loopback Spring HTTP，明确memory/Fixture，验证v2草稿选择、旧回执未变、401/404/未知查询400/no-store/无版本。不是PG验证 |
| Rust | `CARGO_TARGET_DIR=.tmp/dev-checks/rust78-target`下默认与all-features locked测试，分别41/49通过，退出0 |
| TypeScript / 生产构建 | 独立`pnpm typecheck:web`、`pnpm build:web`与浏览器套件前构建实际通过，固定依赖；既有大chunk警告保留 |
| 全量浏览器 | 本机Chrome，`pnpm test:web`，365通过，4.7m，退出0；1440/1024/390px、浅深主题、标准/多页签、缓存/键盘/隐藏身份与现有功能。该次在最后确认收尾及新增2项恢复测试之前 |
| 最终相关浏览器 | 确认缓存/门禁、响应核对和表格宽度收尾后，source-center/integration-initial-read/integration-lists三文件33通过，36.8s，退出0；含确认未知原回执恢复与不一致拒绝，仅一次POST，无隐藏探测/保存/执行，最新版本精确跳转 |
| 静态 / 候选边界 | 291结构化文件/6只读Tool静态检查通过；git diff --check退出0；仓库外私有词表扫描2062个提交候选通过，退出0 |

首次相关46项：42通过/4失败（重复按钮可访问名称、默认偏好存储断言、关闭页后重新打开自动GET断言）；修正后全量首轮362通过/3失败（原先单页卸载测试、隐藏帮助定位、标准偏好存储断言），再修正后全量365通过。上述失败不计通过。最终33项新增两种原请求回执核对恢复测试；没有把编写测试计作通过。

本机浏览器实际查看5173页面的多页签/面包屑与接入目录，截图保存在仓库外。真实四服务启动尝试失败：Docker Desktop临时运行套接字异常，PG不可达，platform迁移初始化与worker存储初始化失败；web和runtime启动。自动审批拒绝清理Docker临时套接字文件，仅返回blocked by policy，没有绕过、重置数据或静默切成memory/Fixture。最终dev:status：web(5173)、runtime(8090)为200/200，platform(8080)、worker(8081)为failed；相关PG测试未在本轮运行。停止了本轮无响应的Docker只读清单命令，没有停止其他容器或删除数据。

无实例地址/凭据CRUD、生产后台身份委托/调度、连续日志/指标运行或多副本可靠性新增；现有运行权限/固定版本/幂等与unknown保留。未执行完整Java/PG/VM、真实来源持续写入/模型调用/IdP/TLS、非Windows实机、远程CI或生产部署。没有提交、推送或增加启动单元/数据库。前端规范和操作说明同步，后端实质缺口仍在实施路线中。

### Docker恢复后的补验（2026-10-03）

用户重启Docker后，实际容器清单显示原本机PostgreSQL健康，遥测存储及既有来源容器运行。读取原本机配置，仅启动先前失败的platform与worker；未改存储模式、重置数据、创建容器/数据库或清理被拒绝的临时文件。原web/runtime保持运行。

| 实际运行检查 | 结果与范围 |
|---|---|
| 四启动单元 | `pnpm ops start platform`、`pnpm ops start worker`及`pnpm dev:status`退出0。platform(8080，PID28128，dev/postgres/jsonrpc)、runtime(8090，PID9152，platform-dev/rig-openai)、worker(8081，PID21872，history=true)、web(5173，PID21120)均存活/就绪200/200 |
| PostgreSQL与实际HTTP | 从已有私有本机配置传入loopback JDBC测试环境，未输出凭据。`gradlew.bat :apps:platform-api:test --tests '*SourceSetupHttpIT' --tests '*PostgresSourceSetupIT' --tests '*WorkflowHttpIT' --tests '*PostgresWorkflowIT' --no-daemon --console=plain`退出0，41s；JUnit XML核对SourceSetupHttpIT 4、PostgresSourceSetupIT 3、WorkflowHttpIT 3、TelemetryWorkflowHttpIT 3（由通配符选中）、PostgresWorkflowIT 5，共18项，0失败/错误/跳过。覆盖来源确认/回执与继续编排授权、持久化/隔离/幂等/回滚、草稿CAS、预览/发布/运行回执和显式日志/指标样本输出；使用随机测试tenant和明确Fixture来源，不自动执行真实采集或模型 |
| 实际浏览器 | 新建后台5173检查页，授权后自动显示18份已有配置；没有点击刷新列表。核对页签返回列表和继续编排：没有保存版本的来源准备本地未保存流程，既有样本来源定位`revision=1&state=PUBLISHED`并显示4个节点、发布只读与6个映射字段。未保存/发布/运行/探测连接/调用模型；仅丢弃本轮检查页生成的未保存模板。检查页控制台error为空，截图留在仓库外；临时页关闭，原用户页未刷新或导航 |
| 静态与来源边界 | 恢复记录同步后，`scripts/check_repo.py`退出0，291结构化文件/6只读Tool定义通过；`git diff --check`退出0，保留既有CRLF提示；仓库外私有词表检查2062个提交候选通过，退出0 |

本次仅恢复原服务及补验并更新状态文档，没有新的产品代码、契约、依赖或数据库变更；不将此前契约/领域/Rust/TypeScript和浏览器套件称为本次重跑。未重跑完整Java/PG/VM、全量浏览器、真实来源持续写入/模型调用/IdP/TLS、非Windows实机、远程CI或生产部署。测试输出仅`.tmp/dev-checks/config98-pg-recovery.log`和既有JUnit报告目录；未提交或推送，M0–M4退出状态保持。

## 99. 接入实例维护与配置历史基础（2026-10-03）

后续目标已建立并保持active。落地S2的实例维护基础，不将本切片等同完整S2–S7：SourceInstance与v2封闭契约/API、V035及受限运行授权、元数据CAS、配置历史、归档恢复、原维护命令回执查询。旧SourceSetup初版只读投影，首次修改事务保存初始配置、当前状态与回执；名称变更不增加配置版本，显式采用当前可信连接摘要才增加配置版本；不覆盖旧回执或工作流。创建ID租户内跨主体冲突拒绝，当前配置/完整历史不一致时失败。UI独立实例页签及请求容器、表格、抽屉；未知结果核对原命令，冲突保留本地输入，读取不暗中探测或运行。

| 实际运行检查 | 结果与范围 |
|---|---|
| 契约 | `.tmp/mvp-check-venv/Scripts/python.exe -X utf8 -m pytest tests/contracts -o addopts= -q`，971通过，21.89s；新增37项封闭字段、来源模式与容量检查 |
| Java纯领域 | `scripts/check_java_domain.py`，最终51个main实际编译运行，1480 checks passed，退出0；包括30项实例维护和32项来源确认，不把main检查算作PG集成 |
| PostgreSQL / 实际HTTP | 原私有本机配置仅传入loopback JDBC测试环境。`gradlew.bat :apps:platform-api:test --tests '*SourceInstanceHttpIT' --tests '*PostgresSourceInstanceIT' --tests '*SourceSetupHttpIT' --tests '*PostgresSourceSetupIT' --tests '*SourceContinuationHttpIT' --no-daemon --console=plain --max-workers=2`最终退出0，47s。JUnit XML：4+3+3+4+1共15项，0失败/错误/跳过。验证4写者CAS一胜、重建适配器历史/原回执、配置损坏拒绝、跨主体/租户/ID冲突、回滚、闭合请求、真实loopback HTTP授权与归档恢复。随机测试租户、明确Fixture来源，未调用实际来源采集或模型 |
| Rust | 隔离`.tmp/dev-checks/rust78-target`，默认和all-features的workspace locked测试使用-j2，实际41/49通过，退出0 |
| TypeScript / build | `pnpm typecheck:web`与`pnpm build:web`退出0，最终生产构建1.20s；既有大chunk警告保留，未升级依赖 |
| 全量浏览器 | 本机Chrome，`pnpm test:web`，最终377项全量通过，6.1m，退出0；新实例维护10项覆盖1440/1024/390px、浅深主题、历史/归档/恢复、未知回执不重复PATCH、错配拒绝、CAS显式重读及403不循环。共享传输仅新增受控v2路径及PATCH，保留其他路径拒绝 |
| 本地会话桥接 | `pnpm test:dev-session`，7项通过，386ms；仅v2 data-sources白名单，其他v2路径拒绝，bootstrap不允许PATCH；浏览器不能提交可信身份 |
| 静态 / 来源边界 | `scripts/check_repo.py`退出0，303结构化文件/6只读Tool定义通过；`git diff --check`退出0，保留既有CRLF提示；仓库外私有词表检查2091个提交候选通过，退出0 |
| 四启动单元 / 实际UI | `pnpm ops restart platform`退出0，平台加载V035；platform29480/runtime9152/worker21872/web21120存活/就绪均200/200。新建后台5173页自动读取18份本人实例，旧初版配置历史与当前部署地址/凭据引用显示；无凭据正文。1440/1024/390px检查无整页溢出，浅深主题可读，页面console error为空，截图在仓库外。只读操作，没有修改已有实例/工作流、探测/采集/发布/运行；临时页关闭、尺寸和主题恢复，原用户页未导航或刷新 |

中间失败保留：首次PG测试编译因泛型Boolean断言重载歧义失败，修正后14项通过；增加完整性与跨主体ID测试后15项首轮14通过/1失败，测试使用已失效的旧连接摘要先触发SOURCE_UNAVAILABLE，改用当前摘要验证ID冲突后最终15通过。浏览器首次相关运行提前结束，无完成报告不算通过；第二轮34通过/1失败为旧抽屉选择器同时命中两个dialog，精确定位后全量377通过。首次Rust启动因Windows提交内存资源不足失败，没有执行测试；之后-j2成功。失败不计通过。

完整地址/凭据配置、凭据轮换固定版本、配置版本绑定的连接测试和真实发现快照尚未实现；新历史仅保存摘要/模式/时间，当前部署env引用不是版本化密钥。实例归档不隐式停止其他任务或删除发布版本，现有任务绑定仍需后续pin完善。实例管理当前为独立页签，旧创建表保持兼容，不能宣称可编辑多条真实连接已完成。

未运行完整Java/PG/VM套件、运行角色最小权限实测、真实来源持续写入/模型/IdP/TLS、生产后台委托、多副本故障/容量、非Windows实机、远程CI或生产部署，Rust fmt本轮未跑。四启动单元和数据库数不变，没有提交推送；M0–M4退出状态不提升。相关契约、ADR、运行说明与前端规范同步。

## 100. 固定配置检查与有界字段发现（2026-10-03）

完成S2的检查/发现切片：SourceInspection纯业务Schema、封闭v2命令/读取契约、持久化PENDING先认领与终态条件更新、V036、原请求幂等回读、版本变更/归档/期限与未知保护。来源IO在事务外，仅调用已有登记Host连接器，TEST匿名版本探测后必须授权读取；DISCOVER最多读取首个5条主机，保存固定5个原始字段的类型、缺失与指纹，不存字段值。记录只读校验原始配置历史，损坏失败。结果接受期限65秒、完成记录有效15分钟、每主体最多200回执、最近20份读取；进程并发2不等同多副本预算/接管。真实env凭据未版本化时仍UNVERIFIED，即使本次READ_VERIFIED也不冒充可复用发布pin。

SourceInstancePanel管理请求/身份/未知结果，SourceInspectionResults纯展示，维护抽屉增加「连接与字段」。历史首次可见读一次，检查均显式POST；失败显式刷新、403清空缓存并关闭抽屉、未知结果保持原请求且无轮询/重复POST，结果到期仅更新标签。不同实例的页签状态按实例ID匹配，打开第二实例默认配置页，不因旧页签多读隐藏历史。原创建/发布内容未覆盖，发现不自动改映射或执行工作流。

| 实际运行检查 | 结果与范围 |
|---|---|
| 契约 | `.tmp/mvp-check-venv/Scripts/python.exe -X utf8 -m pytest tests/contracts -o addopts= -q`，1001通过，21.00s，退出0；新增30项封闭命令、模式/完整性/状态与容量校验 |
| Java纯领域 | `scripts/check_java_domain.py`最终编译运行52个main，1518 checks passed，退出0；SourceInspectionSmoke 38项包含原请求/类型冲突、权限/所有权、历史配置、过期、UNKNOWN、归档及200容量。不是PG测试 |
| PostgreSQL / 实际HTTP | 私有本机配置仅读取并传入loopback JDBC。`gradlew.bat :apps:platform-api:test --tests '*SourceInspectionHttpIT' --tests '*PostgresSourceInspectionIT' --tests '*SourceInstanceHttpIT' --tests '*PostgresSourceInstanceIT' --no-daemon --console=plain --max-workers=2`退出0，41s；JUnit XML 3+3+3+4共13项，无失败/错误/跳过。随机测试租户、明确Fixture来源，覆盖实际Spring HTTP闭合请求、授权/归档/原键读取、PG重建历史/租户所有权隔离、4适配器同原键仅1次Reader、丢失PENDING与损坏pin；没有真实来源或模型调用 |
| Host读取适配器 | `gradlew.bat :apps:platform-api:test --tests '*HostSourceInspectionReaderTest' --no-daemon --console=plain --max-workers=2`退出0，24s；7项无失败/错误/跳过。Connector显式Fixture验证匿名版本不能替代授权读取、失败不继续读取、offset不冒充验证集合、部分/混合/缺失类型、无客户值、空清单、超限不截断成成功、Fixture与权限边界。不是实际来源验收 |
| Rust | `CARGO_TARGET_DIR=.tmp/dev-checks/rust78-target`，用现有MSVC环境运行workspace默认及all-features locked测试、-j2，实际41/49通过，退出0 |
| TypeScript / build | `pnpm typecheck:web`、`pnpm build:web`初次及最终抽屉收尾后均退出0，最终构建1.22s；浏览器套件前构建也通过，原大chunk警告保留。未升级依赖 |
| 全量浏览器 | 本机Chrome，`pnpm test:web`，393通过，5.0m，退出0；包括16项新检查用例、1440/1024/390px和浅深主题、partial、UNKNOWN/原回执错配、无轮询/重复POST、403清理、15分钟标签到期无请求、缓存页面切换 |
| 最终相关浏览器 | 全量后修正不同实例的旧页签状态，`pnpm test:web source-inspections.spec.ts source-instances.spec.ts source-center.spec.ts`，47通过，54.6s，退出0；17项检查用例含打开第二实例不读隐藏历史。此后未再重跑全量393项 |
| 本地会话 | `pnpm test:dev-session`，7项通过，373ms，退出0；v2固定路径桥接和身份清理保持，未扩大网络出口 |
| 静态 | `scripts/check_repo.py`，311结构化文件/6只读Tool定义通过，退出0 |
| 四启动单元 / 实际UI | `pnpm ops restart platform`退出0，新平台加载V036；platform20584/runtime9152/worker21872/web21120存活/就绪均200/200。独立后台5173页自动读18份实例，仅选择已有本机验收Host实例执行1次TEST与1次DISCOVER。真实授权读取通过，来源报告7.0.27；发现当前3条主机、5个原始字段，interfaces.ip实际存在缺失，HOSTID_WATERMARK完成清单；真实凭据仍显示未确认可复用。没有填写/修改地址、凭据、实例或工作流，没有触发采集、发布、运行、库存写入或模型。1440/1024/390px无整页横向溢出，浅深主题和字段表已视觉核对，console error为空；截图留仓库外，临时页关闭，主题/视口恢复，用户原页未导航或刷新 |

中间失败如实保留：关联43项首轮42通过/1失败，403会清空全局授权缓存并关闭抽屉，原用例错误地断言抽屉内仍保留错误；修正为权限清理并独立测试普通失败。关联46项45通过/1失败为断言普通错误包含“来源”而实际固定文案使用“连接配置”，修正后全量393通过。最后不同实例隐藏页签收尾后47项通过。失败不计通过，未通过放宽权限或Schema解决。

完整受控地址/多连接CRUD、版本化凭据配置及轮换、发布配置/凭据/算子完整pin、生产后台委托和持续任务、质量/重放/容量/多副本可靠性仍需推进，完整目标保持active，不能以本切片关闭S2–S7。未运行完整Java/PG/VM套件、运行角色最小权限实测、真实来源持续写入/模型/IdP/TLS、生产授权/多副本故障、非Windows实机、远程CI或生产部署，Rust fmt本轮未跑。四启动单元与数据库数量不变，无提交推送。相关契约、ADR-063、操作说明和前端规范同步；仓库外私有词表扫描2115个提交候选通过，git diff --check退出0、保留既有CRLF提示。实际输出仅在忽略检查目录，本机实施进度与UI证据留仓库外。

## 101. 固定版本凭据管理（2026-10-03）

新增纯领域SourceCredential、配置权限source.configure、CredentialProtector端口、JCA适配器和V037的元数据/密文版本/撤销/原回执表。AES-GCM认证绑定tenant、owner、ID、秘密版本、版本ID和keyId，内部命令HMAC核对同键不同秘密；公开回执只有非秘密命令摘要和元数据。版本只追加、名称编辑保留pin、撤销不可恢复。CAS与主体容量分别为100份凭据、200份回执、100个秘密版本、1000次编辑；读取校验索引、历史版本/时间/回执引用，可信连接器解封装前后复核撤销并清空临时字符数组。没有秘密GET、任意URL/密钥上传或明文/Fixture失败回退。

密钥环为仓库外绝对本机文件，最多8192字节/8个32字节根密钥，规范Base64、闭合JSON、重复键与尾随拒绝；未设置明确不可用，设置无效拒绝启动。公开vaultAvailable、canConfigure、secretWriteAvailable分别反映密钥、权限和安全传输；秘密写入只允许可信Servlet TLS，或显式dev+强制loopback绑定且本端/对端均loopback，不采信普通X-Forwarded-Proto。默认部署不授予新权限或创建临时根密钥。本轮为了实际本机开发验证，显式在私有部署配置启用source.configure并设置持久随机密钥环，目录和文件ACL限制当前用户，原非秘密配置另留仓库外；没有复制/修改现有真实来源凭据。

数据源中心新增凭据视图；SourceCredentialPanel承担请求/可信身份/原命令，Table/Drawer纯展示。列表和历史首次可见读取，失败显式刷新；秘密采用非受控密码输入，提交后立即清空，React待确认命令、浏览器存储与回执均不含秘密。未知结果锁定并只GET原回执；403清空缓存和DOM输入，保留现有开发会话的显式重试规则，不自动轮询。未提交新输入有继续/放弃入口，空名称也属于未保存状态。最终收敛历史页无关保存操作、修改输入取消旧撤销确认、已读取历史复用缓存。

| 本轮实际检查 | 结果与范围 |
|---|---|
| 契约 | `.tmp/mvp-check-venv/Scripts/python.exe -X utf8 -m pytest tests/contracts -o addopts= -q`最终1048通过，28.63s，退出0；11份新v2Schema/显式Fixture样例，公开秘密隔离、身份/地址/密钥注入、可见ASCII、控制字符、状态、pin与容量。早期1045检查不冒充最终数量 |
| Java纯领域 | `scripts/check_java_domain.py`最终53个main/1584 checks passed，退出0；SourceCredentialSmoke 66项含原键同/异秘密、授权/主体/租户、保留旧根密钥、轮换旧pin、撤销前后IO、临时数组清空、密文篡改/跨scope、容量、坏回执与内存跨租户nonce重复回滚 |
| 实际Java HTTP/PG与密钥适配器 | 私有loopback JDBC，`gradlew.bat :apps:platform-api:test --tests '*PostgresSourceCredentialIT' --tests '*SourceCredentialHttpIT' --tests '*SourceCredentialReadOnlyHttpIT' --tests '*SourceCredentialVaultTest' --tests '*SourceInstanceHttpIT' --tests '*SourceInspectionHttpIT' --tests '*PostgresSourceInstanceIT' --tests '*PostgresSourceInspectionIT' --no-daemon --console=plain --max-workers=2`最终退出0，1m6s；JUnit XML共23项、零失败/错误/跳过。包括4项新PG、2项新HTTP写入、1项只读身份、3项密钥文件/传输边界及原实例/检查13项。PG实际重建版本/原回执、4适配器CAS只追加1份、密文不含Fixture正文、nonce冲突和异常事务回滚、损坏索引/密文拒绝；测试根密钥/秘密明确合成，没有调用真实来源 |
| Rust | 现有MSVC和隔离`.tmp/dev-checks/rust78-target`，`cargo test --workspace --locked -j2`及`--all-features -j2`，41/49通过，均退出0；未覆盖运行中Runtime产物，fmt本轮未跑 |
| TypeScript / build | `pnpm typecheck:web`与最终独立`pnpm build:web`均退出0，最终构建1.02s；全量及最终专项浏览器启动前的生产构建也实际通过，沿用锁和依赖，大chunk提示保留 |
| 全量浏览器 | 本机Chrome，`pnpm test:web`最终411通过，5.8m，退出0；含17项新凭据、三宽度/两主题、会话/权限/未知结果、声明的公开Schema及全站既有导航/画布/列表回归 |
| 最终凭据专项 | 全量后收敛历史页保存按钮、缓存与撤销确认，`pnpm test:web source-credentials.spec.ts`最终18通过，38.5s，退出0；新增编辑取消撤销确认。此后没有再重跑全量412项。此前来源四组关联64项通过，1.1m |
| 本地会话 | `pnpm test:dev-session`，7通过，318.6481ms，退出0；未扩大本地代理出口或更换开发Token |
| 静态与来源边界 | `scripts/check_repo.py`333结构化文件/6只读Tool通过；私有仓库外策略检查2159候选通过。最终文档收尾后再执行边界与diff，结果按末尾记录 |
| 四单元 / 实际本机API和UI | `pnpm ops restart platform`退出0，平台27036加载V037；runtime9152/worker21872/web21120与平台存活/就绪均200/200。仅新增明确Fixture本机凭据，实际POST创建和原键重放相同、同键异秘密409、PATCH轮换v2、撤销v1、原创建回执保持v1；元数据与版本读取不含秘密/密文/内部HMAC。独立5173页面读取列表和历史，v1已撤销、v2可引用，密码输入为空；1440/1024/390px整页无横向溢出，表格局部滚动，浅深主题实际截图已视觉检查。浏览器没有输入新凭据、撤销或保存动作；临时页面关闭，主题与视口恢复，用户原页未导航/刷新 |

中间失败保留：首次新JUnit编译因泛型返回Boolean与JUnit重载歧义失败，显式取booleanValue后编译；首轮10项集成5失败，发现回执INSERT少一个占位符导致503，修正后23项通过并最终再跑通过。前端首轮17项15通过/2失败，历史失败后切页签重复GET为实际缺陷，新增请求尝试状态后修正；403用例误断言隐藏控件，调整后全量411中410通过/1失败，仍把保留开发凭据的显式刷新误认为应禁用，改为检查缓存清空、零自动请求与显式刷新才第二次GET。最终411及18专项均通过。没有用放宽Schema、权限或增加重试解决这些失败。

完整目标仍active：版本化凭据管理已完成此切片，现有env连接尚未绑定新pin；受控地址/多连接配置、固定配置与凭据绑定检查、发布配置/算子/模型完整pin和差异、可信后台委托/真实写入闭环、指标追溯、质量/重放/恢复/容量仍待推进。实际Fixture凭据CRUD不等于真实来源认证、KMS或生产密钥管理验收，不提升M0–M4退出状态。未运行完整Java/PG/VM套件、最小运行角色实测、实际TLS/IdP/KMS/灾备、多副本故障或吞吐、非Windows实机、远程CI/生产部署；没有新数据库/启动单元、提交或推送。相关契约、ADR-064、规范和本机实施进度同步；原始日志在忽略检查目录，截图与私有密钥/配置记录均在仓库外。

最终文档与UI收尾后，私有边界检查2159候选通过、git diff --check退出0，四服务仍200/200；没有新增未记录的凭据或业务执行。临时浏览器页已关闭，最终四张历史截图已经视觉检查，恢复原浅色主题与默认视口。

## 102. 受控地址目录与固定pin内部读取（2026-10-03）

完整后续目标保持active。本轮完成S2新增连接的地址与读取边界：纯领域SourceEndpoint/Pin、SourceEndpointCatalog端口、授权元数据服务与固定地址/凭据读服务；平台加载仓库外绝对路径部署目录，最多64KiB/32项/每项1–32个明确租户，重复/未知/非法内容拒绝启动，未配置为空。封闭v2 Schema/Fixture样例和授权GET目录已实际加载；浏览器请求不提供URL、租户、秘密或代理信息。操作员身份文件与identity-grants同步支持source-endpoint/credential范围，实际IO还需source对象和source.sync允许，默认身份和权限未扩大。

数字IPv4/IPv6、规范端口与固定根路径在服务端校验；HTTPS默认，回环地址和回环HTTP仅显式dev+bindLoopbackOnly允许。拒绝DNS主机名、URL凭据/query/fragment/编码、歧义数字、危险地址范围与区号。新登记传输绑定精确URI，显式关闭代理与跳转；既有5秒连接/10秒单请求/2MiB单响应、1000成员清单、TEST1条/DISCOVER5条、最多6/5次HTTP请求和进程内2并发保持有界。内部RegisteredHostSourceReader真实调用已有JSON-RPC适配器，地址pin和固定秘密版本在IO前后复核，秘密字符数组最终清空；短暂String限制如实保留。没有公开新增连接探测或任意HTTP接口，也没有把旧env来源自动改成固定秘密版本。

| 实际检查 | 结果 |
|---|---|
| 契约 | `python -X utf8 -m pytest tests/contracts -q --junitxml=…`退出0；JUnit元数据1084项、失败/错误/跳过0，12.273秒。新增地址/固定摘要/目录/部署配置正例与未知身份/凭据/网络字段、容量及操作员scope负例 |
| 纯Java | `scripts/check_java_domain.py`退出0，54个main、1625检查；SourceEndpointSmoke40项验证范围/租户、错误摘要、轮换仍用旧版本、读中改地址/撤销、操作失败清空临时秘密、无IO拒绝和名称变更不改变语义pin |
| Java适配器及实际HTTP/PG | Gradle `:apps:platform-api:test --max-workers=2`执行8套、28项，失败/错误/跳过0，最终29秒。包含RegisteredSourceEndpointsTest5、JacksonRegisteredTransportTest4、SourceEndpointHttpIT2、FileSourceConnectionGrantsTest2，以及Host读取/旧检查/PG检查/凭据HTTP回归。实际回环HttpServer验证无代理、跳转无二次发送、目的地不匹配不发送、过大正文取消；实际PG/HTTP固定旧秘密在轮换后读取通过，撤销后无上游IO，发现仅5字段类型与缺失。授权GET零网络副作用、其他租户隐藏、注入查询400、缺认证401、no-store；操作员范围撤销重新读取生效 |
| Rust | MSVC环境和现有隔离target，`cargo test --workspace --locked -j2`与`--all-features`均退出0，41/49通过，未增加Rust执行能力 |
| Web | `pnpm typecheck:web`与独立`pnpm build:web`退出0，构建1.07秒；已有大chunk提示保留。本轮无前端UI或依赖改动 |
| 关联浏览器 | Chrome显式路径、一个worker，`source-instances.spec.ts source-inspections.spec.ts source-credentials.spec.ts`45通过，1.1分钟；未跑新的全量浏览器套件，不将§101全量411计入本轮 |
| 本地会话 | `pnpm test:dev-session`7通过，283.0049ms |
| 静态/私有资料边界 | `scripts/check_repo.py`342结构化文件/6只读Tool通过；私有外部词表检查2250候选通过，检查器未输出命中正文或词表内容。`git diff --check`退出0，既有CRLF提示不是内容错误 |
| 四单元/实际本机目录 | `pnpm ops restart platform`退出0，平台31288实际加载新GET目录；runtime9152、worker21872、web21120与平台均200/200。显式在仓库外私有目录登记已有回环Host地址一项，保护目录/文件ACL，文件仅当前用户FullControl且owner匹配。保留原配置非秘密备份，既有平台Token、真实来源秘密和身份权限未改。可信只读API核对目录、精确摘要、单项读取和注入查询400；原18份接入、已有Host实例cfg1及上一节Fixture凭据v2/edit3未变，无业务写入或上游读取 |

中间失败：首轮26项中25通过/1失败，测试预期不支持的POST返回405，而当前受保护错误分派实际返回401；保持认证实现，修正为拒绝与零网络副作用验证，复验26通过。新增独立scope文件测试首次编译引用不存在的ENTITY_WRITE枚举，改用实际ENTITY_MANAGE后最终28通过；没有扩展权限、放宽契约或增加重试来处理失败。编译与原有废弃API提示保留。

当前交付是目录和内部固定pin读取边界。实例创建/更新的完整连接配置快照、地址与秘密版本选择UI、公开检查回执接入新适配器仍待完成；旧env连接的凭据语义仍UNVERIFIED。工作流配置/算子/模型完整pin、版本差异、可信后台委托和真实输出闭环、指标追溯、质量/重放/恢复/容量仍继续推进，不能用内部适配器Fixture测试替代这些验收。没有运行完整Java/PG/VM套件、实际新连接TLS/IdP/KMS/备份恢复/最小运行角色、多副本/吞吐/远程CI/非Windows实机检查；未增加启动单元或数据库，未提交推送，M0–M4退出门槛未提升。实际证据在忽略的`.tmp/dev-checks/endpoint102-*`，部署文件及本机产品实施记录在仓库外；没有新截图或操作用户原浏览器页面。


## 103. 实例完整连接配置、固定凭据与创建编辑抽屉（2026-10-03）

完整后续目标保持active。新增SourceConnectionConfiguration/Service、独立控制器及V038完整非秘密快照；创建/更新只接受登记地址pin和固定凭据版本pin，实例/通用历史/完整历史/原回执同事务写入。新实例物理ID独立，旧Host显式绑定保留原物理ID和创建回执；MANUAL不能绑定。语义变化追加配置，名称/说明只更新编辑版本。数据损坏、跨主体/租户、对象范围、CAS、容量、撤销与密钥不可用明确拒绝；旧回执复核原实例/快照摘要，元数据维护回执不能冒充连接命令。操作员文件补充workflow范围支持现有根门禁，默认身份/权限未扩大。

公开TEST/DISCOVER已按被认领实例完整快照调用登记地址/固定秘密适配器，目录pin与凭据元数据在同一持有事务内验证，不开启嵌套PG事务。IO在事务外，精确原版本在前后复核；读中配置变化UNKNOWN，固定成功结果才CURRENT，旧env真实读取仍UNVERIFIED。轮换不自动换pin，旧检查改版STALE、撤销STALE、到期EXPIRED；查回执和重复原请求没有再次IO。发现保持Host首分页最多5条/5个原始字段类型及缺失，无原始字段值持久化。

Web新增严格API契约/摘要校验、SourceConnectionPanel请求容器和SourceConnectionDrawer纯展示。实例管理提供新增连接，维护抽屉提供地址与凭据；分别选择地址、凭据、具体版本，被撤销版本可见且禁止提交，完整ID换行。未知结果固定原命令并查询原回执，普通关闭保留有效或无效草稿，继续/放弃入口、Esc焦点和403/身份变化清空隐藏目标与缓存。历史首次可见只读一次，失败仅显式刷新。新连接创建记录导向实例维护，不提供尚未接通的工作流版本跳转；旧工作流保留原绑定。

| 实际检查 | 结果 |
|---|---|
| 契约 | pytest contracts退出0；JUnit记录1131项、失败/错误/跳过0，15.508秒。6个新Schema/Fixture样例，原回执正确引用内层Schema，未知身份/网络/秘密字段、pin、版本和容量负例通过 |
| 纯Java | scripts/check_java_domain.py退出0；55个main、1673检查。SourceConnectionSmoke49检查：创建/重放、旧回执、元数据与连接版本分离、原秘密轮换后不自动换pin、范围/租户拒绝、归档、并发CAS、事务回滚、旧Host绑定、读中改版UNKNOWN、100配置版本容量及超额无写入 |
| Java实际HTTP/PG与适配器 | Gradle平台test、11套/41项，失败/错误/跳过0，最终41秒。新增PostgresSourceConnectionIT4验证完整历史/撤销后原回执、并发唯一胜者、两类快照回滚和损坏正文拒绝；SourceEndpointHttpIT4包含真实回环HttpServer→公开创建/配置→PG→TEST/DISCOVER→轮换/修改/撤销→原回执，仅合成上游与秘密，禁止将Fixture作为真实来源验收。其余实例/检查/凭据HTTP与PG、出口限制、文件scope及读取适配器回归通过 |
| Rust | MSVC环境，隔离现有target，workspace locked -j2默认/all-features退出0，41/49通过；未增加运行能力或更新锁 |
| Web | typecheck退出0；独立build退出0，最终1.07秒，已有大chunk提示保留。React/TypeScript/shadcn与依赖版本未更换 |
| 关联浏览器 | 显式Chrome、一个worker，source-connections/instances/inspections/credentials/center/connection-checks及integration-initial-read/lists共111通过，最终2.1分钟。新15项覆盖三宽度/浅深、固定选择、原回执未知/不匹配、CAS、无效草稿恢复、403清理、撤销选项、维护更新保持cfg版本、历史失败不自动重试和Esc焦点；未运行本轮全量浏览器，不把§101全量411计入本轮 |
| 本地会话 | pnpm test:dev-session 7通过、失败/跳过0，8656.5539ms |
| 静态/私有资料边界 | scripts/check_repo.py 355结构化文件/6只读Tool通过；私有外部词表2294候选通过，未输出命中正文/词表内容。git diff --check退出0，既有CRLF提示保留 |
| 实际部署/页面 | 首次平台重启4520实际加载V038，四单元200/200。续跑发现四单元均已停止后，按原配置pnpm ops start恢复，Runtime实际构建3m27s未中断/重复启动；最终platform23008/runtime30548/worker12524/web29096均200/200。可信实际GET新配置/history保持旧Host LEGACY且完整历史为空，目录摘要和原18份创建/旧Host cfg1/Fixture凭据v2 edit3不变，注入查询400与no-store核对。实际页面选择既有地址及明确Fixture凭据v2、保留v1撤销，1440/1024/390与明暗截图已查看；界面草稿未提交、未读上游、未执行采集/发布/运行，临时草稿明确放弃、临时页关闭、浅色偏好与视口恢复；用户原页未导航或刷新 |

中间失败：新浏览器首轮误引用不存在的凭据版本样例路径，12项新用例失败而原45通过；修正实际version-page路径后11/12通过，余一项为Playwright对原生option disabled的判断不支持（DOM已有disabled）。改用disabled属性及保存禁止共同验证，最终111通过。后续细节修改后再跑111通过，不放宽权限、契约或隐藏失败。最初只执行编译和旧领域检查不计入最终feature通过，旧输出仅作为过程证据。

实现记录、闭合契约、ADR-066、runbook与前端规范同步。完整模型/指标发现和映射重验、实例成为主列表、工作流来源/算子/模型/映射全pin、差异及升级恢复、可信可撤销后台委托、真实来源测试到查询输出闭环、质量/重放/恢复/容量、多副本故障及生产环境门槛仍未完成。没有进行完整Java/PG/VM套件、新连接生产TLS/IdP/KMS/备份恢复/最小角色、多副本/吞吐/远程CI/非Windows实机验收；未迁移实际秘密或增加启动单元/数据库，未提交推送。证据在忽略的.tmp/dev-checks/connection103-*与仓库外本机checks；旧外部产品研究/身份/截图仍不进入仓库。


## 104. 工作流固定接入版本与即时只读预览（2026-10-03）

source.configuration增加封闭sourceId/revision/digest，不复制地址或秘密到工作流，不接受latest、身份或权限字段。新pin进入语义摘要；旧定义的序列、存储解析、批次预览与发布摘要保持兼容。服务器在调用者租户事务中验证完整不可变连接历史、物理来源及摘要；可用性另核对实例归档、登记地址和固定秘密版本/密钥。连接轮换不改已有流程pin。保存、预览前后及发布校验来源；读取中撤销不能保存成功回执，历史版本及审计在权限范围内仍可读取。

固定Host来源经已登记出口与秘密读取边界即时预览最多5条，沿用有界manifest及Host映射，逐实体检查ENTITY_READ；外部IO在工作流PG事务之外。请求不提交samples或旧syncRunId，来源只接受zabbix-jsonrpc；不回退Fixture。trace带固定配置，syncRunId为null，计数表示实际读入样本；截断不冒充完整采集，运行元数据不保存值。只读预览不能替代持久采集谱系，旧LOCAL_DEV_ENTITY运行器遇到固定来源显式SOURCE_UNAVAILABLE。四启动单元及数据库不变。

WorkflowSourcePanel持有可信会话、实例/版本读请求、取消、缓存与失败；WorkflowSourceConfig纯展示。在来源节点显式打开选择器才读实例目录，版本明确选择后应用到定义，不自动用latest。失败不因抽屉或主题切换而重试，403清理隐藏状态。完整实例/摘要/凭据标识可换行，发布来源只读。

| 实际检查 | 最终结果 |
|---|---|
| 契约 | pytest tests/contracts退出0；JUnit1149项、失败/错误/跳过0，15.772秒。新增18项固定pin正反例，保留旧定义兼容；未知身份/地址/秘密/权限、非法UUID/digest/版本、manual配置pin和null拒绝 |
| 纯Java | check_java_domain.py退出0，56个main、1705检查；新绑定32项覆盖摘要、固定历史、伪造来源/摘要、租户/scope、禁止注入批次/手工样本、即时trace、轮换不迁移、归档/恢复、读取中撤销不存回执及旧运行器拒绝 |
| Java实际HTTP/PG及适配器 | Gradle平台test、显式选择18套64项，失败/错误/跳过0，最终1m27s。新增实际回环HttpServer与PG：公开凭据/实例→固定工作流保存→预览→trace→发布→连接/秘密轮换仍读原版本→撤销原秘密无上游IO→历史版本可读。旧Workflow/SourceSetup/Continuation/Runtime及其PG、连接/凭据/检查、出口/对象范围适配器回归通过；没有以纯领域Fixture代替HTTP/PG证明 |
| Rust | MSVC环境、现有隔离target，workspace locked -j2默认/all-features均退出0，41/49通过；无新增Rust运行能力或锁更新 |
| Web | typecheck退出0，独立生产build退出0，1.10秒；现有大chunk提示保留。固定来源解析/运行明细复用封闭pin校验 |
| 关联浏览器 | 显式Chrome、一个worker，workflows/workflow-outputs/source-connections/graph-workspace共62通过，1.6分钟。新增5项验证1440/1024/390、明暗、显式版本、完整pin、无旧批次预览、固定来源不误开旧运行器、失败显式重试与403清理；未跑本轮全量浏览器 |
| 本地会话 | pnpm test:dev-session 7通过，失败/跳过0，327.9437ms |
| 静态/资料边界 | check_repo.py通过357结构化文件/6只读Tool；私有外部词表检查通过且不输出命中正文/词表。git diff --check退出0；最终候选数以忽略的workflow104-boundary.log为准 |
| 服务与实际页面 | pnpm ops restart platform退出0，平台17516/runtime30548/worker12524/web29096均200/200。可信GET核对原18份接入、旧Host cfg1 LEGACY/完整历史空、Fixture凭据v2/edit3及目录pin、no-store/query400未变。实际临时页显示来源选择器和旧实例尚无固定地址/秘密配置；1440浅色与390深色截图在仓库外，已查看。没有保存草稿/配置、预览/发布实际来源或写业务数据；临时草稿明确放弃、页关闭，主题/视口恢复，用户原页未操作 |

过程失败如实保留：新纯领域测试首次误用已有接口签名，随后漏完整layout；修正测试后32项通过。新契约注册器首次包含无$id文件导致收集失败，改为注册实际有$id Schema后1149通过。平台首次编译将UUID传入字符串资源构造器，修正为ResourceRef.entity并输出规范entity_id字符串，最终实际64项通过。新浏览器先因定位同时命中配置/预览两区而3失败，收窄定位后又因打开抽屉覆盖顶部主题控件而3次超时；改为关闭抽屉切换主题再打开，最终62通过。未通过force点击、放宽契约或修改认证来绕过失败。

当前来源仍按创建者私有隔离，跨创建者共享来源尚未设计。完整算子/模型/映射依赖pin、差异与升级、固定配置的持久采集批次、受信可撤销后台委托和真实输出闭环、完整模型/指标发现、主列表统一、质量/重放/恢复/容量与多副本故障及生产环境门槛继续实施。未运行完整Java/PG/VM或全量浏览器、生产TLS/IdP/KMS/备份恢复/最小角色、持续采集/真实写入、吞吐/多副本/远程CI/非Windows实机验收。没有新服务/数据库、秘密迁移、提交或推送；完整目标保持active，不因本切片通过提升M0–M4状态。


## 105. 静态算子目录、固定摘要与执行前计划（2026-10-03）

算子唯一元数据源移入contracts/catalog，当前11个内置版本1类型；开发/CI生成纯Java静态描述、TypeScript元数据、Node配置Schema和固定示例，禁止手改生成文件、动态代码或远程下载。摘要覆盖声明的语义版本、参数与端口，显示文字不进入执行摘要；声明实现ID不是构建文件或源码内容哈希。领域层不读取JSON/框架文件，Node参数按已生成目录验证。每次评估预编译最多16节点/32边的不可变内存计划，复用已解析父节点与类型端口；来源读取前拒绝摘要不匹配，不增加逐点PG/持久编排或服务。

operatorDigest存在时进入Definition语义摘要；旧无pin摘要和不可变JSON不改写。新发布必须全部pin匹配，未固定/失配返回409；旧已发布版本仍可读和按版本1执行，PG回查/执行后证明没有写入新属性。旧草稿明确使用更多菜单固定版本，支持撤销/重做；保存清除旧预览，重新预览后沿用原15分钟、CAS、输出模型与来源复核门禁。旧实际运行器在读取批次和输出前检查pin，失配任务停止并保存闭合OPERATOR_CHANGED元数据，停止旧任务仍可操作。

授权工作区同次响应提供目录，保留首次自动读取一次。节点库分类/搜索文字来自目录，纯展示组件不请求网络；新模板/节点固定读取到的摘要，旧草稿不自动升级。目录缺失保留历史查看并禁用新建/固定/发布；不兼容目录保留错误，不回退或轮询。右侧抽屉展示完整类型/版本/digest，现有主题、身份清理、未保存页签和发布只读边界继续生效。

| 实际检查 | 最终结果 |
|---|---|
| 契约与生成 | pytest tests/contracts最终退出0，1165通过，失败/错误/跳过0，固定示例补充后最终复验13.97秒。新目录闭合/代码地址/未知类型/参数/端口/摘要/重复/兼容定义/运行错误正反例，生成器--write及--check实际执行；CI只校验，不自动升级 |
| 纯Java | 最终check_java_domain.py退出0，57个main、1736检查。新算子31项覆盖固定摘要、旧定义等价执行、不可变计划、新发布门禁、预览失效、旧不可变版本不迁移、错误pin不读来源/批次或写输出、任务停止/错误和允许显式停止 |
| 实际Java HTTP/PG及适配器 | 18套66项，失败/错误/跳过0，最终Gradle55秒；实际授权根目录、pinless保存/预览/禁止新发布→显式升级CAS→旧预览无效→重新预览/发布→PG回读完整pin；错误摘要409、无认证401和伪造query400。独立PG旧不可变定义执行后未新增operatorDigest；固定来源、来源确认原回执、连接/凭据/检查/出口/对象范围及旧运行回归通过 |
| Rust | 现有MSVC与隔离target，workspace locked -j2默认41、all-features49均退出0，无Rust运行能力或锁升级 |
| TypeScript/构建 | 独立typecheck退出0；浏览器准备实际执行tsc及生产build，初次1.01秒，最终932ms；已有大chunk提示保留，没有增加依赖或手改客户端 |
| 浏览器 | 明确本机Chrome、单worker，9个关联文件105通过，2.7分钟。新增升级/撤销重做/完整摘要/预览门禁/缺失目录历史查看/不兼容无回退，1440/1024/390和明暗；运行错误/解析小修后workflows+workflow-operators最终42通过，1.3分钟。未跑本轮全量浏览器 |
| 本地会话 | pnpm test:dev-session退出0，7通过、失败/跳过0，497.3616ms |
| 静态与私有资料 | check_repo.py、私有词表边界、生成器--check和git diff --check实际通过；最终360结构化文件/6只读Tool，私有边界2330候选通过（忽略operators105-boundary日志），不输出词表或命中正文。仅既有CRLF提示 |
| 服务与实际页面 | pnpm ops restart platform退出0，platform10284/runtime30548/worker12524/web29096均200/200。可信GET目录与contracts完全相等，11算子；no-store、query400、无认证401。原18份接入、旧Host cfg1 LEGACY/history空、Fixture凭据v2/edit3未变；当前页8草稿/5已发布。实际临时页的新模板显示完整TRIM pin与目录节点库，1440浅色与390深色已查看、DOM无全页横溢；截图留在仓库外checks/operators105。没有在实际页面保存/预览/发布或读上游/写业务数据，临时草稿明确放弃、页关闭，主题/视口复原，用户原页未操作 |

过程失败保留：最初领域回归的旧测试新发布夹具未固定摘要，OPERATOR_PIN_REQUIRED使检查退出1；升级新发布夹具并新增独立旧历史兼容用例，最终全部通过，不放宽服务器门禁。初次启动Java测试帮助脚本时生成脚本尚在运行，Node因文件尚不存在退出1；等待原生成命令完成后重新执行，最终66项通过。首张实际截图紧随视口变化，捕获旧尺寸帧；DOM核对确认新尺寸、抽屉与全文无横溢后重拍，最终图片替换旧帧。没有通过强制点击、篡改身份或放宽权限绕过失败。

实现状态、ADR-068、契约/固定示例、操作说明和前端规范同步。S1实例主列表、完整模型/指标发现与标准映射pin、依赖差异/升级冲突、固定来源持久采集批次与可信可撤销后台委托、真实输出查询闭环、质量/重放/恢复/容量和多副本故障及生产环境门槛继续实施。未运行完整Java/PG/VM、全量浏览器、生产TLS/IdP/KMS/备份恢复/最小角色、连续采集/实际写入、吞吐/多副本/远程CI/非Windows实机验收。没有新服务/数据库、秘密迁移、提交推送；完整目标active，M0–M4退出门槛不改变。

## 106. 当前接入实例主列表与固定配置首版编排（2026-10-03）

默认主列表使用v2实例API的当前授权元数据；不可变创建回执移入独立入口，不再由浏览器补成可维护/配置v1实例。按名称/说明/来源、类型与归档状态筛选，统计仅限当前最近20份实例。目录与创建回执首次可见读取一次，失败显式刷新，切离首次目录读取会取消并拒绝迟到响应；已确认创建返回后读取实例列表，不把原创建记录当作当前行。原回执恢复、维护CAS/未知结果、凭据/检查门禁和身份清理保留。

新连接主列表链接使用封闭的实例UUID、配置revision和完整digest三元组，非法/重复/混合/身份参数在工作区读取前拒绝。授权实例与不可变连接历史精确匹配后，只准备未保存首版草稿，固定来源、主机模型及算子摘要；轮换到v2仍保持明确选择的v1。归档/缺失/不匹配/普通失败/403不回退到其他版本、旧批次或Fixture。已有工作流从版本入口显式创建下一版再选配置，保存以服务器CAS为准；最近列表无法证明不存在历史版本，不能替代服务器冲突校验。跳转不保存/预览/发布，不读取上游；旧运行管理对固定来源仍关闭。

来源展示表、请求容器与URL选择分层保留，纯组件不发请求。手机搜索独占一行，筛选与两项操作分层，页签单行本地滚动、支持键盘，避免长标题折成竖排；明暗、1440/1024/390及局部表格滚动核对。

| 实际检查 | 最终结果 |
|---|---|
| 契约/生成 | pytest tests/contracts退出0，1165通过、失败/错误/跳过0，9.38秒；算子生成器--check核对11个目录项。没有手改生成客户端或改变已有服务端业务Schema |
| 纯Java | check_java_domain.py实际退出0，57个main、1736检查；没有Java领域或适配器功能修改 |
| Rust | 现有MSVC、隔离target、workspace locked -j2默认41/all-features49均退出0，失败0；未增加Rust功能、依赖或锁升级 |
| TypeScript/生产构建 | 修正配置字段名后独立typecheck退出0；最终浏览器准备实际执行tsc及生产build成功，保留既有大chunk提示，没有升级依赖 |
| 浏览器 | 明确本机Chrome、单worker；9个关联文件118通过，2.4分钟，覆盖首次自动读取/缓存/失败/取消/迟到/原回执恢复、配置历史pin、同配置轮换不自动改版、缺失/归档/403/已有版本拒绝、闭合URL与无隐藏写入。工作流编辑/算子/输出3文件48通过，1.6分钟；移动样式最后6文件90通过，1.8分钟，含手机搜索几何、三宽度和明暗。90项与118项有重叠，不能相加为独立用例总数；未跑本轮全量浏览器 |
| 本地会话 | pnpm test:dev-session退出0，7通过、失败/跳过0，396.79ms |
| 静态/私有资料 | check_repo.py实际通过359结构化文件/6只读Tool，生成器、私有边界实际通过2332提交候选；词表/正文不输出。最终文档后边界与git diff --check实际退出0，仅既有CRLF提示，不提交私有原始证据 |
| 实际服务与UI | 信任本机开发身份的只读GET核对18份实例/18份创建回执，旧Host cfg1 ACTIVE且LEGACY未绑定；no-store、伪造query400、缺认证401。8080/8090/8081的实际存活/就绪及5173页面均200/200，无重启或配置写入。临时实际页面展示18份实例，1440/1024/390与明暗核对，无全页横溢；手机搜索294px覆盖整行，检查图在仓库外checks/source106，已查看。没有保存实际草稿/连接、读取上游或写业务数据；临时页关闭并恢复主题/视口，用户原页未操作 |

过程失败保留：首次typecheck把固定来源摘要字段写成connectionDigest，按已有digest契约修正后通过。第一次115项浏览器有6失败：新节点定位使用旧名称，历史用例在认证前选页签而认证后恢复默认；修正定位和授权后的明确选择，118项最终通过。新增手机几何定位同时命中隐藏凭据工具栏，第一次响应式复验2失败，限定实际实例区域后最终90通过，没有放宽几何标准或强制点击。首次健康脚本使用错误路径退出1，按启动器实际端点修正后四单元200/200。文档批量补丁因锚点不全被拒绝，未写入部分文件；重新核对后一次更新，旧记录保留。

实现状态、路线图、固定来源契约说明、来源操作与前端规范同步。完整模型/指标发现、标准映射pin、发布依赖差异/升级冲突、固定来源持久批次与可信可撤销后台委托、真实输出查询闭环、质量/重放/恢复/容量和多副本/生产门槛继续实施。未运行本轮Java HTTP/PG适配器或完整Java/PG/VM、生产TLS/IdP/KMS/备份恢复/最小角色、实际持续采集/写入、吞吐/多副本/远程CI/非Windows实机。沿用§105既有服务端实际66项证据，不把本轮浏览器HTTP Fixture当作新的真实连接验收。没有新服务/数据库、秘密迁移、提交或推送；完整目标保持active，M0–M4退出门槛不提升。


## 107. 已保存版本的只读配置比较与并发核对（2026-10-03）

新增封闭的comparison请求/响应契约、明确Fixture样例、纯Java比较器、应用层精确保存引用核对与只读HTTP接口。双方均固定id/revision/state/editVersion/digest，服务器在同一Store事务应用可信身份、租户、本人草稿和来源对象范围；布局保存改变编辑号也使旧请求409。不同流程拒绝400，缺失/他人草稿404，不绕过权限。比较固定来源/模型/算子摘要、映射、参数、节点和连线增删及执行顺序；不同revision自身、画布位置和预览回执不列为处理变化。null表示未设置，空字符串保留。最多1200变化、键96/值4096/节点32字符，有稳定顺序和不可变列表；不执行代码、解析当前模型、读秘密或上游、不写草稿/发布/预览/运行历史。

版本列表和编辑器更多菜单接入非模态贴右全高抽屉，未保存变化禁用；请求容器/纯展示/API契约分离。打开或选项变化不发请求，冲突后显式刷新并重新比较；选项消失置空仍可刷新。取消/切页/迟到/403与缓存页面状态清理、完整键/摘要、空值区别、Esc焦点恢复均验证。客户端拒绝伪造引用、未知字段、非法值和重复变化，不在浏览器重新计算差异或自动升级依赖。

| 实际检查 | 最终结果 |
|---|---|
| 契约 | pytest tests/contracts实际退出0，1191通过、失败/错误/跳过0；新增26项闭合引用、身份/参数注入、预算、节点范围、时间/重复正反例及空变化。控制台双quiet没有汇总，按实际通过的进度点计数，并保留日志；不是collect-only结果 |
| 纯Java | check_java_domain.py最终退出0，58个main/1768检查；新比较32项验证revision不计变化、名称/来源/模型/算子/映射/参数/节点/边/序、不可变结果、无模型/上游I/O、双方来源范围、SOURCE_SYNC、本人的草稿与租户隔离、过期编辑号和不写历史 |
| 实际Java HTTP/PG及适配器 | 18套67项，失败/错误/跳过0，Gradle实际1分7秒；新测试真实PG保存→预览→不可变发布→第二版草稿→精确引用比较，no-store与null字段、原发布JSON和运行数量未变、query/未知字段/重复JSON键/无认证/错摘要/错编辑号/异流程/缺失/布局CAS更新后旧请求拒绝。其他来源/连接/凭据/出口/固定来源和旧运行回归通过 |
| Rust | 现有MSVC、隔离target、workspace locked -j2默认41/all-features49均退出0，失败/忽略0；未修改Rust运行或依赖锁 |
| TypeScript/生产构建 | 独立typecheck最终退出0；每轮浏览器准备实际执行tsc及生产build成功，既有大chunk提示保留，没有依赖升级或手改生成客户端 |
| 浏览器 | 明确本机Chrome、单worker，4关联文件60通过、1.5分钟。补充编辑器dirty与缓存页签后专项14通过、28.3秒；实际视觉修正关闭按钮后最终14通过、32.4秒，含三宽度/明暗、关闭图标几何居中。只读网络错误提示修正后最后15通过、27.5秒，保留选择并仅显式重试。专项与60有重叠不能直接相加；未跑本轮全量浏览器 |
| 本地会话 | test:dev-session实际7通过、失败/取消/跳过0，3751.9451ms |
| 静态与私有资料 | check_repo.py实际通过364结构化文件/6只读Tool；生成器--check核对11目录项；最终文档后私有边界2351候选及git diff --check均实际退出0，仅既有CRLF提示；不输出私有词表或命中正文 |
| 实际服务/页面 | 平台重启后29292/runtime30548/worker12524/web29096全部200/200。实际比较有no-store、伪造query400、无认证401、错摘要409；现有单发布版本比较自身返回空changes，原发布JSON与运行数量未变。原18实例/18创建回执、旧Host cfg1 ACTIVE且LEGACY未绑定保持。实际1440/1024/390及明暗无全页横溢；最终关闭图标横/纵偏移均0，截图仓库外checks/comparison107且已查看。当前本机没有同流程多revision，实际页面仅验证自身比较；不同版本差异由独立HTTP/PG测试及明确HTTP Fixture浏览器验证。没有实际保存/预览/发布或读取上游/写业务数据；临时页8和9均关闭，主题/视口复原，用户原页未操作 |

过程失败保留：首次纯Java夹具修改映射时同时移除了另一个字段，实际3条映射差异与预期2不符；保持无关字段后验证正确的删除/新增2条，最终全部通过，没有改比较语义。实现核对时发现原容器可能阻止空选项刷新，刷新到新编辑号后与陈旧父列表绝对核对也可能误清结果；测试前已按固定流程ID刷新和仅实际父列表变化失效修正，专项覆盖通过，这两项不是曾执行失败的浏览器用例。截图发现旧页面按钮样式覆盖关闭图标，改为共用Button并增加居中几何复验；最终图替换初图。一次实际复验脚本混用了TypeScript非空断言，工具在解析阶段拒绝、未执行任何动作；改为JavaScript后继续实际检查。没有放宽权限、伪造生产连接或强制点击绕过失败。

ADR-069、比较契约、前端规范、操作说明、实现状态与路线图同步。完整模型/指标发现、标准映射独立pin、显式依赖升级兼容与发布冲突、固定来源持久采集批次/可信可撤销后台委托、真实输出查询、质量/重放/恢复/容量和多副本/生产门槛继续。未运行完整Java/PG/VM、全量浏览器、生产TLS/IdP/KMS/备份恢复/最小角色、实际持续采集写入、吞吐/多副本/远程CI/非Windows实机。没有新服务/数据库、秘密迁移、提交推送；完整目标active，M0–M4门槛不提升。

## 108. 固定来源的有界指标元数据发现（2026-10-03）

本轮继续完整接入目标，完成S2首分页指标发现及有效回执修正。契约新增DISCOVER_METRICS/metricDiscovery，纯领域验证来源项顺序、指纹、结果互斥、预算和固定配置。真实来源通过既有数字地址登记、版本化凭据与读前后撤销检查，仅两次固定item.get，按itemid升序读取21项含哨兵，保留20项；不读取lastvalue/history/脚本/密码。两次一致且无哨兵为完整的本次授权可见清单，不能作为事务快照或全租户资源证明；部分、变化、失败及未映射分别保留。

映射来自已有精确来源键配置，包含id/revision/完整配置digest、完整标准指标键、单位/值类型/转换。来源原键、空单位、未知类型与类型不匹配没有被静默换成已支持定义。此处的映射引用仍是观察依据，尚未成为工作流独立执行pin；没有新增绑定、模型、指标点或采集checkpoint。Fixture仅使用既有合成来源并明确标注，真实失败不回退。

V039将同一检查回执JSON预算扩展为256KiB，仍最多200份/主体与最近20份读取；新迁移同时进入Gradle资源和启动迁移器。旧Host JSON可缺少新投影，实际PG去掉新属性后由新适配器读回保持原结果。新完成投影互斥，原请求重放不重复网络读取。旧未版本化真实连接在新指标操作前拒绝，历史连接测试依然明确UNVERIFIED。

| 实际检查 | 最终结果 |
|---|---|
| 契约 | pytest tests/contracts最终实际退出0，1211通过，16.17秒；20新增用例覆盖闭合结果、身份/值/秘密字段注入、预算、文本控制字符、类型和结果互斥，旧Host兼容 |
| 纯Java | check_java_domain.py最终退出0，59个main/1802检查；指标发现新增34项，验证不可变结果、数值ID顺序/重复、预算、指纹、权限/来源范围、配置和凭据pin、原请求无重复读、部分/失败无Fixture回退 |
| 实际Java HTTP/PG及适配器 | 19套73项，失败/错误/跳过0，Gradle最终51秒；真实HTTP/PG与明确合成上游验证两个固定元数据请求、no-store、固定连接/凭据、轮换不改变旧pin、撤销后旧回执STALE及新请求无网络503、原请求不重复读取；20项超过旧8KiB预算的JSON实际持久化后新适配器可读，旧投影兼容。固定工作流和原来源等既有回归通过 |
| Rust | 既有MSVC与隔离target，workspace locked -j2默认41/all-features49均实际退出0；未修改Rust或依赖锁 |
| TypeScript/生产构建 | 独立typecheck实际退出0；每轮浏览器准备实际执行tsc与生产build成功，保留既有chunk提示，无依赖升级或手改生成客户端 |
| 浏览器 | 明确本机Chrome、单worker，3关联文件最终48通过、57.6秒；最后配置提示修正后来源专项27通过、43.3秒，与48重叠不能相加。三宽度1440/1024/390与明暗验证完整来源/标准键、定义链接、无自动POST、历史首次一次、显式发现与原请求查询、不完整、损坏指纹门禁及会话清理。加入每个td和链接自身溢出及关闭图标横纵居中检查，不能只用全页宽度判布局通过。未跑本轮全量浏览器 |
| 本地会话与静态 | test:dev-session实际7通过，失败/取消/跳过0、3644.8605ms；check_repo.py实际通过368结构化文件/6只读Tool；生成器--check核对11目录项 |
| 实际来源/服务 | 复用本机配置的来源Token，经受控API保存一个独立本地凭据与“本地指标接入”，配置v1/凭据v1。真实本机Zabbix数字地址18088的item.get返回3项，FIRST_PAGE_MATCH/READ_VERIFIED/complete=true/CURRENT，精确映射3项。原请求GET保持同一结果；无认证401、query400、同键换种类409。逐项摘要核对原18实例/18不可变创建回执及原凭据不变，共19实例/2凭据；未改旧配置或写模型/工作流/遥测输出。Docker来源Web为已固定digest的正式连接器镜像。平台19612/runtime30548/worker12524/web29096全部200/200 |
| 实际页面 | 独立临时页10实际默认读取19实例，打开新实例→连接与字段读取已有指标回执；CURRENT被前端接受，3项完整来源/映射键实际可见。点击host.memory.available.ratio进入对应定义，完整来源vm.memory.size[pavailable]与比例单位/转换/版本正确。此浏览器核对只读，没有再次发现或写操作；截图仓库外checks/discovery108，默认视口截图已查看；明暗三宽度截图为明确HTTP Fixture浏览器产物且已查看最终390深色与1440浅色。没有把Fixture截图当作实际上游证明。临时页10关闭，未操作用户原页，未设置浏览器视口或主题 |

过程失败如实保留：初契约测试1210通过/1失败，字符串Schema的末尾美元锚可匹配末尾换行；加入绝对末尾检查后1211全部通过。首Java测试73项有1个PG失败，新JSON超出旧预算，V039尚未接入启动迁移器；接入后下一次又发现迁移未进入Gradle资源，导致Missing inventory migration。补齐两处后最终73全部通过，没有减小夹具、手改旧迁移或绕过数据库约束。初浏览器48通过但截图发现末列旧nowrap/链接样式造成手机裁切；修正局部样式后加入单元格/链接几何检查并再次48通过。关闭改共用Button后居中检查通过。实际配置页核对发现缺少目录投影被误写为凭据不可用，改为查看独立地址/凭据入口，最终27专项通过。一次文档编辑工具补丁格式错误未写该文档，修正后再写。

ADR-070、来源指标/检查契约、前端规范、来源中心操作说明、实现状态、路线图及本机实施进展已同步。提交前的私有资料边界和git diff --check以本节最终附记的实际结果为准；不输出私有词表或命中正文。

后续仍需完整指标清单分页/模型字段发现、标准映射独立执行pin、显式依赖升级兼容与发布冲突、固定来源持久批次/可信可撤销后台委托、真实输出查询、质量/重放/恢复/容量及生产门槛。未运行完整Java/PG/VM、全量浏览器、生产TLS/IdP/KMS/备份恢复/最小角色、多副本/吞吐/远程CI/非Windows实机。没有新增启动单元或数据库、提交推送；完整目标active，M0–M4门槛不提升。

最终附记：私有资料边界检查实际通过2371候选文件，git diff --check实际退出0，仅既有CRLF提示；本附记写入后再次运行同两项检查，结果以discovery108-boundary-last.log与discovery108-diff-last.log保存。

## 109. 来源指标清单的受控分页与真实接入核对（2026-10-03）

本节实际完成：每页20项、容量1000项的来源指标成员清单分页；服务器私有ID清单、公开引用及父/根回执谱系；固定连接/来源/可信身份核对；最多五次固定元数据请求、原请求恢复与15分钟根有效期；独立公开/私有编解码、旧记录兼容；请求容器与共用指标表/分页展示组件。CURRENT表示本页及配置有效，终页complete才表示连续成员范围覆盖。ITEMID_WATERMARK不是所有可变元数据的同一时刻原子快照，不读值/history，不写绑定、指标点、实体或工作流，不推进checkpoint。

| 实际检查 | 结果与范围 |
|---|---|
| 契约 | pytest tests/contracts，1239通过，16.04秒，实际退出0；5个分页/私有存储Schema与明确Fixture样例，拒绝公开私有清单、额外cursor/offset/来源/身份，失败与成功空结果区别、结果互斥及旧兼容 |
| 纯Java领域 | check_java_domain.py，60个main、1852检查，实际退出0；新SourceMetricPageSmoke 50检查，45项三页、精确切片/有序清单、父根/摘要/时间、原请求无重复读取、跨来源/身份/租户、终页/失效/失败/迟到与有效期不续期 |
| Java HTTP/PG及适配器 | 最终20套84项，failures/errors/skipped均0，Gradle实际成功1分5秒；实际JUnit XML汇总。新适配器8项核对最多5次固定请求、45项三页、清单前后变化、容量、空成功、缺项/多项/乱序/count失配、大于long的ID及私有Codec防篡改 |
| 实际HTTP/PG分页 | SourceEndpointHttpIT真实loopback HTTP和PG、明确合成上游45项，三页20/20/5，每页5次来源交换；固定根/父、公共无私有ID清单、GET/同键重放不读来源、拒绝额外字段/查询/匿名；凭据撤销后原页STALE且下一页503无来源I/O。PostgresSourceInspectionIT实际1000私有ID/长键跨适配器持久读取、篡改fail closed；四并发适配器同键仅一次来源读取 |
| Rust | cargo test --workspace --locked -j2，默认41与all-features 49，均实际退出0；沿用MSVC与既有目标目录，没有更改锁文件 |
| TypeScript及构建 | 独立typecheck实际退出0；独立生产build实际退出0，840毫秒，保留既有大chunk提示；局部样式修改后专项浏览器server再次实际执行build并成功 |
| 浏览器 | source-inspections/source-connections/metric-definition-list三文件53通过，1.3分；收紧映射摘要后source-inspections最终32通过，50.4秒（与53重叠，不相加）。Chrome单worker，明确HTTP Fixture；包含45项三页、父命令四字段、上一页GET/缓存下一页无POST、未知继续查询原请求、变化/容量不伪空、根到期不续期；三宽度/明暗完整键、单元格和链接溢出、映射定义跳转与旧Host行为 |
| 本地会话与静态 | dev-session实际7通过、0失败/取消/跳过，292.2211毫秒；check_repo实际378结构化文件/6只读Tool通过；算子生成检查11项通过 |
| 实际本机接入 | 重启平台20296，既有runtime30548、worker12524、web29096均200/200；仅对第108节保存的固定来源cfg1发起一次新分页发现，真实Zabbix三项，READ_VERIFIED/ITEMID_WATERMARK/complete=true/CURRENT、精确映射3项。原回执GET一致、公共无metricMembership/itemIds、匿名401/查询400/不同种类同键409。19实例/19创建回执/两凭据逐项哈希保持，未创建或改连接/秘密、未写指标/工作流，无Fixture回退 |
| 实际网页 | 临时页11默认1280×720读已持久回执，三项完整来源键、标准映射与1–3/3分页可见，终页双按钮禁用；只读DOM几何确认3行无单元格溢出，映射披露约20.4px且无边框/内距。点击host.memory.available.ratio进入实际完整指标定义，完整来源键、比例单位、转换与版本吻合。没有再次通过UI发现POST、设置主题/视口或操作用户原页；临时页11关闭，截图仅在本机仓库外 |

过程错误保留：初契约生成开发脚本括号错误，未运行生成；修正后1239全部通过。初Java编译因新增PG篡改测试的字符串转义错误失败；改为jsonb_build_array后实际全套通过，追加并发和领域时序检查后最终84通过。一次多文件补丁因上下文不匹配未修改文件，修正后执行成功。一次文档补写引用不存在的目录，已写入部分保留，剩余使用实际contracts路径完成；分页说明移动到contracts。一条错误标题补丁未写入内容。首实际UI核对发现通用details样式让行高过大，局部收紧后32专项复验、实际3项与分页同屏截图已查看。

证据在.tmp/dev-checks/page109-{contracts-final,domain-final,java-final,types-final,build-final,browser-final,browser-compact,rust-default,rust-all,dev-session,repo,generator,restart}.log及page109-{counts,live}.json。JUnit统计来自实际XML，源命令恢复状态与全资源哈希在仓库外私有验收文件；截图在仓库外checks/page109，实际接入截图与明暗三宽度Fixture截图区分。私有资料边界与git diff --check以最终附记的实际结果为准，不输出命中正文或词表。

ADR-071、分页契约、来源检查/首分页兼容说明、前端规范、来源中心操作说明、实现状态及路线图同步；本机产品文档实施进展同步。后续仍需模型字段、独立标准映射执行pin、显式依赖升级兼容及发布冲突、固定来源持久批次/可信可撤销后台委托、真实输出查询、质量/重放/恢复/容量及生产门槛。未运行完整Java/PG/VM、全量浏览器、生产TLS/IdP/KMS/备份恢复/最小角色、多副本/吞吐/远程CI/非Windows实机。无新数据库/启动单元、无提交推送，完整目标active，M0–M4门槛不提升。

最终附记：私有资料边界检查实际通过2398候选文件，git diff --check实际退出0，仅既有CRLF提示。已查看最终390深色和1440浅色Fixture截图与实际默认视口完整三项/分页截图；本附记写入后再次运行两项边界检查，结果保存在page109-boundary-last.log与page109-diff-last.log。

## 110. 来源采样映射固定与显式维护（2026-10-03至2026-10-04）

新增完整MetricMappingPin、兼容维护应用服务、封闭v2 Schema/合成样例/API及V040。旧绑定迁移为未固定，刷新不采用pin、不为相同元数据增版本；新绑定持完整登记摘要，已固定pin不能被普通同步替换，引用中的单位/类型/维度不能被普通定义更新改变。维护只选择服务器登记的完整定义，检查可信tenant/owner、metric/entity/source对象范围、source.sync和source.configure、版本CAS及200份回执上限；租户事务原子保存绑定和原结果，旧结果可在目录变化后回读。同版本改摘要、降级、跨id或语义不兼容拒绝。

真实历史读在取秘密或网络前检查完整pin，返回后比较完整绑定和指标定义；变化拒绝整页及游标。列表按SQL授权范围过滤后扫描最多201候选、展示20份。Web抽离MetricMappingPanel请求容器与MetricMappingDetail，沿用右侧抽屉、主题变量及完整键换行；首次打开一次GET、缓存返回不重读，未知结果只查原请求，原回执完整语义不一致继续锁定，明确409后显式读取再维护。身份变化清空私有内容。前端HTTP及本地预览代理仅追加受控metric-bindings v2族，没有增加任意HTTP代理。

| 实际执行 | 结果与证据 |
|---|---|
| 契约 | `.tmp/mvp-check-venv/Scripts/python.exe -X utf8 -m pytest tests/contracts -o addopts='' -q`最终1271通过，9.49s，退出0；新增32个维护契约检查，覆盖封闭命令/身份/规则拒绝、pin与版本、定义范围、合成样例及未固定历史 |
| 纯Java领域 | `scripts/check_java_domain.py`最终61个smoke main、1896检查，退出0；新MetricMappingSmoke44项验证完整摘要、旧同步不采用、固定/升级/CAS/原回执、目录改版、主体/租户/对象范围、同版本失配、降级拒绝、来源I/O前无pin/错误pin拒绝及退役保留pin |
| Java HTTP/PG/适配器 | `gradlew.bat :apps:platform-api:test`选择25套相关测试（维护HTTP/PG、历史、来源/凭据/连接/出口/分页、工作流及旧运行），最终1m8s，退出0；实际XML汇总101项，失败/错误/跳过0。新PostgresMetricMappingIT4项、MetricMappingHttpIT2项；四独立适配器并发只生效一次，205份范围外绑定不挤掉授权结果，损坏回执拒绝，旧记录回读、重复命令、封闭JSON、no-store及网络后绑定变化丢弃通过。没有将纯领域或合成上游HTTP服务器当成真实厂商验收 |
| Rust | workspace locked默认41、all-features49，失败/忽略0，均退出0。第一次-j2链接出现LNK1102内存不足，降为-j1后两种实际通过；没有改Rust运行、依赖或锁文件 |
| TypeScript/build | `pnpm typecheck:web`最终退出0；抽离纯表格后`pnpm build:web`最终成功954ms，退出0，保留既有大chunk提示。未升级依赖、手改生成客户端或引入新执行后端 |
| 相关浏览器 | metric-mappings/model-catalog/platform-session三文件28通过，22.3s，实际退出0；抽离纯表格及收紧活动页提交门禁后同28项最终18.2s、退出0。使用已安装本机隔离Chromium、单worker、受控生产预览4179；10项新维护用例覆盖1440/390明暗、单次写、原请求GET、错误原语义持续锁定、409无重试、403清空、伪范围摘要拒绝、只读没有提交。两轮重叠不能相加，合成Fixture明确标记；没有本轮全量浏览器回归 |
| 本地预览会话 | `pnpm test:dev-session`最终7项通过，失败/取消/跳过0，279.965ms，退出0；受控新增GET/POST/原回执路径可代理，近似路径metric-bindings-other拒绝 |
| 生成/静态 | `generate_workflow_operators.py --check`11算子验证、`check_repo.py`396结构化文件/6只读Tool均退出0；不将静态检查视为权限或生产验收 |
| 真实本机 | 私有受控验收脚本只对原三项绑定显式提交固定映射，各保存一份原回执；原GET与同命令重复POST精确一致，绑定版本各加一，其他身份/实体/指标/单位/转换/维度保持。CPU、内存、运行时长各返回20个真实Zabbix历史点，模式zabbix-jsonrpc、persistence=not-persisted、无Fixture回退；401及伪造查询400通过。维护本身无来源I/O、指标点或工作流写入，历史读取是后续独立显式GET |
| 原资源与服务 | 原19份接入实例、19份创建回执、两份凭据的完整公开值保持。旧私有基线采用JSON属性顺序摘要，重启后Java不可变Map顺序改变；仅枚举键顺序的循环/反向组合，所有值必须重新产生原SHA256才能通过，再保存排序键的私有基线，未覆盖失败基线或改业务值。管理器最终platform31128/runtime30548/worker12524/web29096运行，四单元200/200；没有新增永久服务或数据库 |
| 实际界面 | 独立5173检查页读取三项真实绑定、查看CPU完整规则与摘要，准确跳转host.cpu.usage.user定义，默认1280px页面无横向溢出；查看390深色、1440浅色Fixture截图。实际与合成截图分开保存仓库外，没有操作原用户标签页、切换用户主题或视口；临时检查页关闭，4179验证服务器关闭 |

日志保留在忽略目录`.tmp/dev-checks/mapping110-*`：contract-last、domain-last、java-final、rust-default-final、rust-all-final、ts-last、build-last、browser-complete、session-final、generated-last、static-last、services-final及live.json。实际验收命令/回执与截图在本机仓库外；没有把凭据、厂商值、原始截图或研究记录提交到仓库。

中间失败如实保留：首次TS检查对象类型的includes不合法，收紧键数组类型后最终通过。首次浏览器因前端v2族白名单缺失失败，修正后28项逐项通过但原Google Chrome运行收尾挂起，不能记作完整成功；错误复用5173本地预览模式的Token测试也已中止，不记成功。最后由持有独立进程句柄的临时检查器启动正常生产预览，使用已安装隔离Chromium，28项及浏览器正常退出0，服务关闭。实际来源绑定初次经本地预览代理403，追加唯一允许族并由7项会话测试复验后恢复，没有放宽身份或服务器权限。平台管理器首次restart因Windows taskkill进程树超时退出1，原平台仍健康；核对管理器拥有的Java PID后原生停止该进程，再由管理器start成功为31128，未改启动器或停止其他单元。Rust首次链接内存失败和旧属性顺序摘要失败均不计为通过；开发只读路径猜测/临时命令转义失败已修正，没有产生业务写入。

范围限制：这一步固定来源采样规则；旧历史点和既有Worker输出只保留原标记，不能据此追认完整摘要谱系。当前登记目录/兼容策略不支持任意自定义规则、降级、多个同id同时可选版本或列表完整翻页。标准指标目录还未进入工作流MAP执行，内联MAP仍固定在工作流语义摘要中。模型字段、工作流标准指标与依赖升级/发布冲突、可撤销可信后台身份、持久固定批次与输出查询、质量、重放恢复、多副本及生产容量仍未完成；完整目标保持active，M0–M4退出门槛不提升，无提交或推送。见[ADR-072](adr/072-explicit-metric-mapping-pins.md)、[公开契约](../contracts/metric-mapping-maintenance.md)及[操作说明](runbooks/metric-mappings.md)。

收尾：MetricMappingTable进一步抽离为纯展示，API与请求门禁不下放；37个本地Markdown链接实际检查通过。私有资料边界实际2456候选文件通过，git diff --check退出0、仅既有CRLF提示；追加本记录后再次运行两项，保留boundary-last和diff-last日志。再次以原三份已确认命令查询/原样重复回执，绑定版本仍不变，各项真实历史仍返回20点，原19实例/19创建回执/两凭据与四单元就绪不变。


## 111. 标准指标映射进入工作流目标与实际预览计划（2026-10-04）

本轮实现METRIC 1.1的完整metricKey与映射id/revision/digest目标。WorkflowMetricPlan解析完整登记定义，检查精确sourceKey和原始数值类型，执行归一化/范围并生成单位、GAUGE、固定维度和完整pin；旧ENTITY、LOG/METRIC 1.0语义和wire保持。新格式及pin参与工作流摘要、存储和差异；可信对象范围检查覆盖写入、历史查看及回执，执行前/保存回执前重编译，失配不留下新回执。发布原样重放保持历史版本，目录变化不自动升级。1.1当前只支持数值GAUGE、全部固定维度和MANUAL_SAMPLE，拒绝未知转换与超预算规则；仍非真实指标写入或后台工作流。

输入仍是5条、32个标量、4096字节；标准结果七字段、4096字节，value为64字符精确十进制字符串，嵌套仅限成功VALIDATE/OUTPUT。前端核对完整定义SHA、固定元数据及精确十进制范围。工作区单次授权响应提供映射；纯WorkflowMetricMappingConfig抽离展示与选择，不发请求；页面重建节点及连线，支持撤销、定义跳转、按需摘要和明确填入Fixture示例。目录缺失/格式不支持可读而不可预览，旧通用指标仍可选择。统一映射类型GAUGE/SUM/HISTOGRAM与既有遥测领域，当前执行子集只有GAUGE。没有新增执行单元、数据库、依赖或手改生成客户端。

| 检查 | 本轮实际运行结果 |
| --- | --- |
| 契约 | python -m pytest tests/contracts -o addopts='' -q，最终1296通过、9.53s、退出0；新增25项，涵盖闭合目标/结果、精确字符串、嵌套步骤限制、METRIC 1.1、旧格式与合成样例；关系与授权另由服务器检查 |
| 纯Java领域 | scripts/check_java_domain.py，62个main/1970检查、退出0；新WorkflowStandardMetricSmoke74项：12.5→0.125、固定维度与pin、精确来源键/范围、原始INTEGER、元数据篡改、历史查看/原发布重放、metric.read范围、目录竞态不保存回执等；不是来源真实采集 |
| Java HTTP/PG/适配器 | 选择26套相关测试，1m22s、退出0，实际XML汇总105项，失败/错误/跳过0。新WorkflowStandardMetricHttpIT3项及旧TelemetryWorkflowHttpIT新增无metric.read拒绝；实际本机PG保存、预览、发布、重放、重新解码、版本比较、目录过滤、伪pin/来源键/范围/嵌套输入拒绝通过。独立可信测试tenant与输入均明确Fixture，无指标点写入验收 |
| Rust | 现有MSVC、隔离.tmp/rust78-target，cargo test --workspace --locked -j1和--all-features -j1，41/49通过，失败/忽略0，实际退出0；未更改Rust能力、依赖或锁 |
| TypeScript/build | 最初独立typecheck通过；收尾构建包含最新tsc --noEmit及Vite，最终863ms、退出0，既有大chunk提示保留。主流程完整标识上画布，中央支持格式判断复用，精确范围比较没有使用Number舍入 |
| 浏览器 | workflow-standard-metrics、workflow-outputs、workflow-comparison、workflow-operators、workflows、configured-source-workflow六文件最终84项通过、1.4m、实际退出0，retry=0、单worker、已安装隔离Chromium及受控4179生产预览。13项新明确Fixture用例覆盖1440/390明暗、完整键/规则、无自动写请求、保存/预览/发布、固定元数据/高精度越界拒绝、缺失/不兼容旧定义、撤销旧链和403清空。旧实体、日志、通用指标及固定来源回归通过；不是全量浏览器测试 |
| 本地会话 | pnpm test:dev-session，7项通过、失败/取消/跳过0、323.2109ms、退出0；未增加代理权限或路径族 |
| 生成/静态 | generate_workflow_operators.py --check实际11算子核对，check_repo.py首次398、文档收尾399结构化文件/6只读Tool，均退出0；静态检查不替代运行验证 |
| 打包与启动 | 平台bootJar实际22s、退出0；核对管理器拥有的原Java PID31128后原生停止并由管理器start成功，新PID22276；runtime30548/worker12524/web29096保持。四单元存活/就绪均200/200，未增加永久服务 |
| 实际资源与来源 | 仅用第110节三份已确认维护原命令GET及原样重复POST核对，不产生新维护命令；三份pin/其他绑定字段保持，CPU、内存、运行时长各显式读20个真实历史点，zabbix-jsonrpc/not-persisted，无Fixture回退。原19实例/19创建回执/两凭据完整公开值与私有基线保持，401/伪查询400通过；本轮实际工作流仍无主tenant写入 |
| 实际页面 | 5173自动授权目录中实际显示三份完整标准指标；临时检查页选择CPU，核对精确来源键、转换、范围、mode=user与完整摘要，画布有标准指标校验。仅准备未保存样本草稿，未点击保存/预览/发布；关闭前明确放弃本地内容，原列表仍13流程。未更改用户主题、视口或原标签页。实际截图与明暗/窄屏Fixture截图分开保存在仓库外，验证预览子进程正常关闭 |

中间失败不记为通过：首次新纯领域测试用了不存在的MetricType常量，改为既有SUM后74项及完整领域通过；最初TS闭包未保留联合类型窄化，捕获目标后通过。首次契约发现新sourceKey尚未纳入METRIC分支及样例回执路径错误，分开1.0/1.1规则并修复后1296通过。原84项范围最初为83项，其中82通过、旧拖拽连线失败，单独重跑仍失败；viewport处理不足，实际命中两个SVG端口后确认原测试仍打开auto浮层，第一次按下先关闭它。测试明确先关闭连接管理、确保端口在视口内再拖拽，专项1项退出0，之后关联83项退出0；中央格式门禁及不兼容历史定义补充后最终84项退出0。没有改产品连线逻辑、放松断言或加隐藏重试；临时DOM诊断已移除。首条Rust嵌套命令转义失败，改用忽略目录内固定cmd脚本后两种测试退出0；Markdown状态插入首次CRLF匹配失败，兼容原换行后完成，没有将这些开发工具失败当作测试通过。

日志在忽略目录.tmp/dev-checks/standard111-*，含domain、contracts-final、java、rust-default/all、build-final、browser-complete/final-exit、dev-session、static/operators、package、live-preservation与report.json。声明的通过都来自真实退出0；部分重叠运行不能相加。截图、主机验收命令与私有资料仍在仓库外，无提交推送。

范围仍包括待完成的真实固定指标来源、系列身份、持久输出查询/采集checkpoint、可撤销可信后台执行身份、停止与恢复/租约fencing、多模型字段及依赖升级、质量重放/生产容量。既有Worker历史轮询、旧Host实体输出不是新标准指标工作流完成证明。完整目标active，M0–M4退出门槛不提升。见[ADR-073](adr/073-standard-metric-workflow-targets.md)、[标准契约](../contracts/workflow-standard-metrics.md)、[使用步骤](runbooks/workflow-standard-metrics.md)。


收尾：41个本地Markdown链接核对通过，私有资料边界2471候选文件实际通过，git diff --check实际退出0、只保留原有CRLF提示。实际检查页放弃未保存的合成内容后回到原13流程列表并关闭；未改变用户原页面。最终84项浏览器验证退出0，所属预览和测试子进程结束；没有提交或推送。


## 112. 固定指标来源、真实原值转换与发布（2026-10-04）

新增固定ZABBIX_METRIC来源联合、指标元数据pin和只读选择页，复用已有来源配置及发现存储。纯领域校验出处与目标映射，受控适配器读取固定系列、网络前后核对元数据与秘密版本；工作流执行标准映射一次。来源选择与节点展示抽离组件，缓存/取消/身份/过期门禁保留；源pin进入版本比较和只读回执。没有新增迁移、数据库或启动单元。

| 实际检查 | 命令/方法 | 最终结果 |
|---|---|---|
| 全部开发契约 | 开发venv的python -X utf8 -m pytest tests/contracts -o addopts='' -q | 1317 passed，10.88s |
| 纯Java领域与应用边界 | python -X utf8 scripts/check_java_domain.py | 63个main，1997 checks |
| Java平台HTTP/PG及来源适配器 | 本机source112-run-tests.mjs调用Gradle固定27套；XML重新汇总 | 112 tests，零失败/错误/跳过；BUILD SUCCESSFUL，1m21s |
| Rust锁定工作区 | 本机MSVC环境执行cargo test --workspace --locked -j1及--all-features | 默认41/all-features49，零失败/忽略 |
| TS/生产构建 | pnpm --dir apps/web-console build，包含tsc --noEmit | 实际退出0；Vite 852ms，保留已有大chunk提醒 |
| 关联浏览器 | 本机source112-browser.mjs，显式隔离Chromium，1 worker/retries0 | 最终103 passed，1.7m；8个来源/标准映射/输出/比较/算子/画布/历史文件 |
| 本地会话 | pnpm test:dev-session | 7 passed，零跳过 |
| 算子元数据 | python scripts/generate_workflow_operators.py --check | 11个内置算子验证 |
| 静态与私有资料隔离 | scripts/check_repo.py；check_reference_boundary.py传仓库外私有policy | 404结构文件/6只读工具；2499候选文件通过（最终文档检查另记下方） |

Java含新固定原值Reader的六项测试，以及实际HTTP/PG的来源选择、真实JSON-RPC预览与来源变化门禁；纯领域涵盖历史出处、权限、映射、摘要、过期选择与来源预算。浏览器Fixture明确标注，覆盖两种宽度和明暗主题、封闭请求、来源变化不发布、失败显式重读、原生过期项、抽屉取消/迟到和身份清除；Fixture不作为真实采样证据。

主本机验收显式创建一次新的固定配置指标发现，从服务器返回的真实CPU系列建立一条工作流，保存、只读预览5点且零拒绝、发布不可变v1，并GET原发布及回执验证。实际输入为原始值，标准结果按登记转换核对，writesPerformed=false。原13条流程完整值保持，新流程使列表为14条。原19实例、19创建回执、两凭据及三项已固定旧绑定保持；只重放原维护命令和GET，三项原绑定各读取20个真实历史点，没有新业务指标写入或资产绑定创建。实际值、身份原件和回执只保存在仓库外。

新版platform-api 27864及其他三单元在受控loopback开发模式均存活/就绪200。真实网页查看已发布版本、完整来源键/系列/配置/元数据摘要，以及原5点回执；来源读入6（含截断检测点）与转换5分开，历史无值正文。真实截图和1440/390明暗Fixture截图留在仓库外，临时IAB页已关闭；没有改变用户主题或视口。

中间失败保留：首轮测试误在未打开抽屉时查节点选择；修正后发现合成选择页映射与工作流目标不一致，统一样例。Windows隔离Chromium在失败后关闭曾挂起，按已确认自建PID结束，仅清理自有进程。后续99项通过但原生option的toBeDisabled断言不适用；页面disabled属性存在，改为核对原生禁用属性和不可绑定行为，并加入两项取消/身份迟到用例及谱系计数用例，最终103项退出0。真实验收首轮健康检查误用了Runtime路径，改用已登记healthz/readyz后只GET/原回执核验，没有重复创建流程或发布。全部首次失败均未记作通过。

最终文档核对：11份相关文档的63个本地链接存在；私有资料边界检查2499个候选文件通过，git diff --check退出0。最终自有隔离浏览器正常退出并清理临时目录，临时预览/测试进程已结束；旧只读进程查询曾长时间未返回，未据此重启任何服务。pnpm ops status单独实际退出0，四服务仍200/200。

当前只是单系列原始值→标准只读转换→不可变版本与元数据谱系闭环。发布不启动采集、不创建MetricBinding、不持久指标点；现有实体后台入口仍不接受该来源。S5可信可撤销后台授权、持久批次/checkpoint与幂等实际输出，S6模型字段/依赖升级、S7质量恢复/容量以及生产身份/租约/fencing门槛继续。未重跑全站浏览器、完整全部Java/VM集成、生产认证与容量灾备；不提升M0–M4退出状态，目标active，无提交推送。

## 113. 实体工作流的限时可撤销后台授权（2026-10-04）

原Host实体任务增加可信后台委托，操作员私有许可默认关闭，现有OIDC边界签发最长15分钟/20批授权，截止不晚于原身份期限。私有任务只持久身份映射引用与预算，公开任务五字段摘要与执行authorizationId分离；不持久登录凭据或Principal。每次处理/写入与提交游标前重读当前许可及完整身份映射，核对可信租户、所有者、工作流/来源范围；开发/委托轮询互相跳过，无自动续期或权限缓存。授权独立于浏览器注销；停止、撤权、到期和额度耗尽终止后续处理。失败/部分写入保留已确认ID而不推进游标，未来/乱序批次拒绝。

实际运行并取得退出0：契约1334项；64个纯Java main/2028检查；31套Java适配器、HTTP及实际PostgreSQL134项，失败/错误/跳过均0；Rust locked默认41、all-features49；TypeScript及生产build（最终Vite 1.56s）；八份关联浏览器文件109项。随后授权专项6项复验，核对1440/390明暗界面及非法期限/计数/私有字段拒绝；本地凭据/浏览器会话分别5与4项通过。最终运行面板删除重复教程/无关实现范围文案并格式化为独立展示组件后，整份工作流43项再次通过，包含6项授权视觉核对，私有截图更新。11内置算子开发生成核对、静态408结构文件/6只读工具、私有资料边界2515候选文件、14份相关文档74个本地链接与git diff --check退出0。Git仅提示原有CRLF归一化，没有白空格错误。

新增真实HTTP→协议Fixture登录→PG任务→实体输出测试固定隔离租户，验证请求不能带私有authority或伪造模型/租户权限、Origin/CSRF、公开授权不泄露映射引用，首批确认两个实体且跨租户不可见；注销后重建授权适配器，以当前政策和原持久引用处理第二批，授权标识和额度保持。显式停止后不消费新批次；移除许可、变更身份映射使任务失败，原游标保留。单元/纯领域另覆盖期限边界、时钟倒退、预算耗尽前不读批次、损坏/禁用政策、来源范围先于读取、错误身份、部分输出后撤权/失败、没有隐藏重试与开发模式隔离。重建的是授权适配器与PG读取，未实施OIDC平台OS杀进程、生产IdP或HA故障演练。

中间失败如实保留：新增单元测试把Runnable直接传给JUnit Executable导致测试编译失败，修正为方法引用后通过。原纯领域批次Fixture的完成时间在未来，新增时间边界正确拒绝；修正Fixture推进时钟后通过，没有放松执行器。Docker引擎当时未运行，首轮PG集成失败；恢复已存在Docker引擎及原容器后重跑。新的HTTP私有字段断言使用全文包含匹配，误命中测试流程名称；改为准确字段/秘密引用检查，最后整套134项通过。前端说明插入时留下字面反斜线引号，TS拒绝；修正后build通过。启动器Runtime等待并行Rust校验的构建目录锁超过5分钟上限，首次全启动失败；校验退出0后显式重启，构建4m20s后运行。首次只读保全检查发生在Runtime尚未就绪，健康探测失败；四服务就绪后重跑通过。以上首次失败均未计为通过。

最终本机platform 3296、runtime 28428、worker 21512、web 19696均200/200，仍为原loopback开发/真实来源模式。只GET确认19实例、19原创建回执、两凭据、原13工作流及上一节新增真实CPU不可变版本与5点只读回执保持，未新建接入或工作流、未启用委托任务。原实例/秘密元数据摘要保持；本节没有再次读取CPU上游或进行新的指标落库。私有真实证据、授权明暗/手机截图仅在仓库外；临时自有预览/浏览器正常退出，未操作用户页面或主题。无提交/推送。

边界：仅原未配置版本Host实体来源使用该授权；固定配置批次、指标/日志后台持久输出继续。控制命令仍是generation CAS，完整幂等命令账本/未知结果确认、失败批次恢复尚未完成；显式重新启动从新起点开始。身份及许可同时删除后重启不会执行旧任务，但旧RUNNING状态的管理对账仍待交付。S5整体、S6模型字段/依赖升级、S7质量恢复/容量、HA租约/fencing和生产身份继续；目标active，M0–M4退出状态不提升。决策与操作见ADR-075、workflow-background-authority契约及运行手册。

## 114. 启动/停止原受理回执与未知响应确认（2026-10-04）

新增纯Java命令/回执、可信tenant/owner存储接口及V041控制元数据表。原请求UUID固定操作、版本/摘要、设置和代数；任务与受理回执同现有PG事务提交。相同内容只读原结果，不重复签发授权或执行来源/输出；不同内容409。GET原回执去除私有引用，当前任务另读。每owner 200受理回执，START到180即拒绝，为最多20任务停止留出位置；代数上限同样保留停止余量，兼容旧RUNNING 1000000停止为终态1000001。没有新启动单元、数据库或隐藏执行后端。

| 实际检查 | 方法与最终结果 |
|---|---|
| 全部开发契约 | 开发venv的python -X utf8 -m pytest tests/contracts -o addopts='' -q：1344 passed，16.81s |
| 纯Java领域/应用边界 | python -X utf8 scripts/check_java_domain.py：65个main、2072检查；新增控制44项 |
| Java平台/适配器/实际PG | 本机control114-run-tests.mjs调用固定32套Gradle；XML汇总138项，失败/错误/跳过均0，实际退出0 |
| Rust默认/all-features | cargo test --locked -j1 --target-dir .tmp/rust-validation -p opsweave-agent-runtime及同目录--all-features：41/49项，零失败/忽略 |
| TS/生产构建 | pnpm --dir apps/web-console build含tsc --noEmit，实际退出0，最终Vite 1.36s；已有大chunk提醒保留 |
| 关联浏览器 | 显式隔离Chromium、retries0/worker1，八份关联文件116 passed，2.3m；代数余量补齐后整份工作流51 passed，1.4m，重叠不相加 |
| 本地会话 | pnpm test:dev-session：7 passed，零失败/跳过 |
| 静态/算子 | check_repo.py：412结构文件、6只读工具；generate_workflow_operators.py --check：11内置算子 |
| 文档/私有资料/差异 | 19份相关文档106本地链接存在；仓库外policy的边界检查2528候选文件通过；git diff --check退出0 |

新增实际PG四项覆盖四客户端并发同键仅一次提交/签发、停止后重放不重启、不同行文冲突与跨租户/所有者隔离、任务和回执共同回滚、新建存储实例读取原结果及损坏行拒绝。现有真实HTTP→OIDC协议Fixture→PG测试补充原POST/GET、拒绝额外查询字段、撤销后台许可后原START仍可读取而当前任务保持FAILED；公开回执无秘密引用。纯领域验证容量预留、无上游/输出调用、只读权限重放、原结果先于满额判断和旧上限停止。测试租户与协议Fixture明确隔离；不是生产IdP、真实丢包或OS/HA崩溃演练。

页面由WorkflowRuntimeSection管理原键/输入和可信身份生命周期，WorkflowRuntimeControlStatus/Panel仅展示与回调。新八项控制浏览器用例覆盖响应丢失后无自动查询/重发、原GET且新当前状态不被旧回执覆盖、显式404后原内容重发、停止原回执不停止后来任务、错误原键保留确认、放弃不等于服务器取消、身份变化丢弃迟到查询以及旧代数停止/非法终态拒绝。未知结果锁定输入/版本/关闭，控制期间暂停状态轮询；403和身份变化清除私有内容，未写浏览器存储。1440/390明暗截图核对完整UUID与换行，实际查看宽屏浅色/手机深色截图，证据留在仓库外；Fixture截图不证明主数据上执行任务。

中间失败保留：新增PG测试的Entry构造参数顺序错误，以及泛型计数导致JUnit assertEquals重载歧义，首次Java测试编译失败；修正后32套138项退出0，未放松断言。首次Rust默认测试使用运行中的共享target，Windows拒绝删除Runtime可执行文件而退出101；改用忽略目录内独立校验target，保留Runtime在线，默认及all-features真实退出0。原实现代数上限可能使最后一代RUNNING无法停止，复核后保留启动/停止余量并增加领域、Schema和浏览器验证，最终检查为本节所列结果。首次失败没有计为通过。

最终本机platform 9976、runtime 28428、worker 21512、web 19696均200/200，平台显式重启加载新迁移和控制接口，其余三单元保持。只读保全核对原19实例/19创建回执/两凭据、原13流程和上一节新增CPU不可变版本及5点回执，完整值及摘要保持。主数据上没有新建流程/接入、启动/停止任务、新读取CPU上游或持久指标写入。原证据和1440/390明暗截图仅留本机私有目录；自有预览和浏览器正常退出，没有操作用户页签或主题。无提交推送。

日志在忽略目录.tmp/dev-checks/control114-*，包括contracts、domain、java及首次编译失败、rust默认/all及首次锁失败、build-final、browser-complete及最终退出、静态/算子、文档/边界/diff、live-preservation、service-status和report.json。回退SQL文件仅编写，未执行DROP；生产独立运行/迁移角色权限未验收。没有重跑全站浏览器、全部Java/VM集成、生产身份/HA/容量灾备。受理回执不包含拒绝命令的成功结果，200份为受控开发上限，自动清理未实现；控制事务幂等不能替代外部资产/指标的未知写入确认。

后续仍为S5固定接入配置批次、实际指标/日志持久输出、checkpoint/失败重放和管理性恢复，S6模型字段/依赖升级，S7质量恢复/容量及HA租约/fencing、生产身份。整体目标active，M0–M4退出门槛不提升。见[ADR-076](adr/076-idempotent-workflow-control.md)、[控制契约](../contracts/workflow-runtime-control.md)及[操作手册](runbooks/workflow-runtime-control.md)。

## 115. 固定指标样本实际写入与原批次回读（2026-10-04）

纯Java应用服务增加闭合一次指标命令、原始RUN摘要复核、标准映射执行一次、固定系列及批次证据；V042在已有PG受理元数据，不存原始/标准数值。可信身份、来源配置/指标pin/映射/时间/数量/精度由执行器检查，外部POST之前再次核对。沿用既有平台与VictoriaMetrics，四启动单元保持，没有新数据库或隐藏执行后端。

| 实际检查 | 方法和最终结果 |
|---|---|
| 全部开发契约 | 开发venv的python -X utf8 -m pytest tests/contracts -o addopts='' -q：1356 passed in 23.46s |
| 纯Java领域/应用边界 | python -X utf8 scripts/check_java_domain.py：66个main、2114检查，新增输出42项 |
| Java协议/HTTP及实际PG/VM | 本机output115-run-tests.mjs，34套XML汇总149项，失败/错误/跳过均0，实际退出0，Gradle1m41s |
| Rust默认/all-features | 锁定Runtime包、MSVC、隔离.tmp/rust-validation：41/49项，零失败/忽略，实际退出0 |
| TS/生产构建 | pnpm --dir apps/web-console build含tsc --noEmit，实际退出0，最终built in 909ms；已有大chunk提醒保留 |
| 关联浏览器 | 隔离Chromium、1 worker/retries0，九份关联文件129 passed (2.4m)；局部留白及历史失败门禁补齐后输出专项13 passed (12.2s)，重叠不相加 |
| 本地会话 | pnpm test:dev-session：7 passed，零失败/跳过 |
| 静态/算子 | check_repo.py：418结构文件、6只读工具；generate_workflow_operators.py --check：11内置算子，实际退出0 |

适配器七项协议Fixture覆盖准确回读、已有相同点跳过POST、已存冲突/精度配置拒绝、受理后500无自动重写、部分到达不确认、错误tenant标签和2MiB响应上限、系统代理被显式绕过。PG三项在隔离tenant验证事务回滚、新建Store读取UNKNOWN及其原摘要、终态/身份/批次字段不可替换、未知不能降FAILED、损坏UUID拒绝。新增HTTP测试以明确合成Zabbix协议样本，经实际平台/PG/loopback VictoriaMetrics写入两点，显式读验证完整确认、归一化0.2/0.125、原POST/GET一致、不同内容409、非法身份查询400/未认证401、最近记录和凭据撤销后的历史查询；可见性等待只在测试显式GET，不重试业务POST。此协议Fixture不冒充真实采集。

浏览器输出专项覆盖1440/390明暗、完整来源/指标定义链接、封闭命令及原值、响应丢失后的原UUID/无自动GET或POST、明确404原内容重发且不新读来源、页面重载最近UNKNOWN的只读验证、部分0/1明确不匹配、数量/系列摘要错拒绝、claimed匹配点的批次摘要复核、历史读取失败先阻止新写且无自动重试、身份清除丢弃迟到结果。WorkflowMetricOutputSection持有请求/身份/门禁，Panel只展示。样式仅此面板，消除通用article/details造成的重复卡片留白；截图留在仓库外，实际查看宽屏浅色、手机深色及真实结果面板。没有更改全站外壳。

主本机验收复用上一节已发布真实CPU工作流v1，显式受控读取一次最近5个原值，按原命令UUID写入独立来源系列：accepted=confirmed=5，failed=unknown=0，实际time-series回读5点且批次摘要匹配。原命令重复POST/GET只返回原记录，不重复来源读取/写入；不同内容409、额外身份查询400、未认证401及刷新后历史存在通过。原19实例、19创建回执、两凭据的完整公开值/摘要及14工作流版本保持，未创建MetricBinding或资产。原三项绑定未变化；原13流程和CPU已发布版本/只读回执通过保全检查。实际值/原命令/回执只在本机私有output115.json。

只重启平台加载V042和新接口，最终platform1536，runtime28428/worker21512/web19696保持；四单元200/200。实际5173在隔离自有浏览器中通过现有自动授权会话打开原固定版本、从最近记录选择原回执、只GET回读5点，界面显示完整匹配，业务POST=0，未操作用户原页签/主题。该实际截图与合成Fixture截图分开保存。

中间失败保留：新增纯领域Fixture没有实现固定目标requireTarget，默认生产门禁正确拒绝；补齐Fixture固定来源/目标检查后42项及全领域通过，没有放松门禁。首次实际HTTP不同内容预期409却返回401，发现新Controller未登记统一异常Advice，补齐后实际409/400及整套149项通过。新增PG测试错误调用MappingDefinition.revision/digest，首次编译失败，改用正式mapping.pin访问器后通过。实际页面脚本最初期待手填Token栏，当前5173使用已有自动授权预览会话而没有该栏；适配实际会话入口后只读验收通过，没有改变认证模式。首次失败均不计通过。

当前CONFIRMED是已证实的批次结果，不代表未来保留策略下点永不丢失；当前点另读。PENDING/UNKNOWN全部点数保持保守未知，验证只读VM并细化状态，命令/批次字段不可变，不将查询失败判成确定写入失败。每owner200记录、最近20和并发2为受控开发限制，没有自动清理/分页。只支持明确loopback配置，available不是真实健康或生产安全证明。V042回退SQL未执行，生产独立运行/迁移角色权限、HA整机故障、吞吐/容量/备份未验收；没有重跑全站浏览器或全部Java集成。

完整目标active：仍需S5固定配置连续批次/完整窗口、checkpoint和失败/管理恢复、日志输出及资产系列关联，S6模型字段/依赖升级，S7质量恢复/保留容量、HA租约fencing及生产身份。此样本闭环不替代上述退出门槛，不提升M0–M4状态，无提交推送。见[ADR-077](adr/077-confirmed-workflow-metric-output.md)、[输出契约](../contracts/workflow-metric-output.md)、[操作步骤](runbooks/workflow-metric-output.md)。

最终补充：纯领域另验证伪造Fixture origin和不匹配trace目标均在受理/外部写入前拒绝，Sink固定直连loopback且不使用系统代理。最终领域42新增检查/66main/2114和34套149 Java实际退出0；输出13项最终浏览器与TS/build通过。最终文档15份180本地链接、私有资料边界2553候选文件和git diff --check实际退出0；没有输出私有词表或命中正文。原批次在重开平台后GET/原POST保持同一确认，当前5点仍匹配，不新读取来源。

## 116. 固定接入资产批次、停止与原扫描恢复

2026-10-04。固定ZABBIX_HOST→ENTITY已发布流程使用Java有限扫描，单页5条/清单1000项。V043在现有PG低频资产链路保存原规范输入、原时间/UUID和私有游标；公开Schema只提供元数据。READY/IN_FLIGHT分别先提交，资产写入全确认后推进checkpoint，失败/部分输出不推进。遗留IN_FLIGHT超过60秒转UNKNOWN并停任务，不自动重发；RESUME保留原内容和已确认ID、重新申请可信短时授权。完成扫描自动STOPPED，不等同周期采集。

实际运行：

- 契约：`python -X utf8 -m pytest -o addopts='' tests/contracts -q`，1366 passed in 10.55s。公开/私有输入分离、闭合状态、RESUME受理语义及错误/数量/身份字段拒绝通过。
- 纯Java：`scripts/check_java_domain.py`，67个main、2149个打印检查通过。新增35检查覆盖两页确认、停止期间无写入、原输入恢复、丢失确认后的稳定Observation、IN_FLIGHT中断、健康空末页、源撤权、身份隔离、来源读期间停止的代数检查、额度耗尽后显式续授，以及完成旧版本后新启动。
- HTTP/PG/适配器：35套153测试，XML失败/错误/跳过均0，最终Gradle1m36s。新增实际PG批次与库存、独立存储重建、丢失确认后恢复不重复Observation、旧游标继续、不可变body拒绝及公开私有分离。注册来源HTTP预览/发布/start→真实PG资产、确认末页、原start重放、未授权与额外参数拒绝通过。上游在联测中为明确Fixture，不能作为本机实际来源证据。
- Rust：隔离`.tmp/rust-validation`，`cargo test --locked -p opsweave-agent-runtime`默认41、`--all-features`49通过，失败/忽略均0；未使用运行服务持有的target。
- TypeScript/生产构建：`npm run build`通过，最终1.04s。既有大chunk提示保留，无依赖升级。
- 浏览器：HTTP Fixture关联139项/2.9m通过。容器留白、间距及版本门禁调整后，固定Host专项+整份workflows最终61项/1.6m通过。两轮重叠，不合计200项；新旧配置控制、未知恢复原命令查询、旧版本已完成新启动、失败不自动读取、身份/迟到清理及1440/390明暗布局验证通过。
- 本地会话7项、仓库424结构文件/6只读工具、11算子生成校验通过。相关文档链接、外部资料边界及diff检查另附本节末尾；只记录实际执行结果。

本机实际：新增一个固定真实接入配置v1的Host流程并发布，原命令先保存在仓库外再提交。扫描1批3条来源记录、3资产确认写入，末批完整并自动停止；原START POST/GET返回同一受理回执，不重启扫描、不改checkpoint。分别GET回读3资产（工作流命名空间/版本匹配）和3份同批次原Observation，未生成重复Observation。Observation保留已有投影/来源模式缺失标记，不把引用当成根因证明。真实5173页面只读打开已发布版本，显示完成/3条且业务POST=0；用户原标签和主题未改变，截图与原数据仅在仓库外。本机新平台12716、runtime28428、worker21512、web19696均200/200。原19接入实例/19创建回执/两凭据、原14流程版本保持，当前7发布+8草稿=15；旧实际CPU5点回读完整摘要仍匹配、标准转换一次且原3绑定未改。

首次未通过检查如实记录：Java记录访问器与规范化帮助函数重名、领域Fixture调用Observation查询签名错误已修正；一次开发编辑脚本未指定UTF-8导致读取失败，修正后执行；PG损坏数据检查暴露Jackson构造异常类型，存储解码改为无正文的稳定异常；浏览器拒绝损坏响应时原任务错误与契约错误同时存在，断言改为明确选择契约错误；首次Rust命令路径在cmd使用斜杠未启动，改正确路径后默认/all均实际通过；本机Observation回读首次以毫秒传给秒级契约返回400，修正单位后3份实际记录匹配。以上首次运行不算通过，结论来自修正后的输出。

当前不宣称持续遥测窗口、周期扫描、跨系统事务、连接丢失后的分布式租约/fencing、生产调度、自动放弃未完成扫描、模型依赖迁移或保留期清理完成。当前恢复证据是有界资产流程和本机单执行器，现有租户事务锁不等于HA完成。仍遵守四启动单元、可信身份、契约唯一源和指标点不逐点查PG。完整目标active，未提交/推送，M0–M4退出门槛不提升。

本节收尾实际结果：18份相关文档/192个本地链接有效；`check_reference_boundary.py --policy <仓库外本机词表>`扫描2576个提交候选文件通过，无命中正文或词表输出；`git diff --check`通过。修正后的本机Observation秒级查询、原Host控制回执重放、旧CPU批次保持和最终四单元状态再次核对通过。日志与汇总在`.tmp/dev-checks/host116-*`，实际原数据、回执和截图在仓库外私有目录；这些未提交到仓库。


## 117. 固定指标完整窗口与确认checkpoint（2026-10-04）

完成有界连续指标运行及STOP/RESUME、原不可变控制回执。首次服务器窗口前60秒，后续逐窗60秒、延后10秒读来源。两次固定指标元数据检查间按clock/ns升序读取最多61条，第61条失败；来源空窗与失败区分，24小时前的待处理范围拒绝，不跳过。每窗最多60点，算子按5条分组且转换一次；毫秒精度冲突不舍入覆盖。

V044只存任务、原控制回执及批次proof/时间戳，原值/标准值不进入PG。先提交IN_FLIGHT和pending，受控输出一次；成功提交后单次5秒确认等待，再精确回读一次。整批确认才推进checkpoint，明确拒绝可显式重新读同窗；UNKNOWN或中断IN_FLIGHT只能核验原系列/时间戳/digest，不发业务POST或读新来源。核验成功后失败任务保持STOPPED，恢复需新控制授权。停止与本机输出由租户锁串行，不能宣称HA/fencing完成。

实际检查：1376契约（21.32s）；68纯Java main、2186打印检查，新增连续指标37覆盖60点转换/61点拒绝、空窗、失败、原控制重放、停止期间不写、未知精确核验/无重发、短期授权到期/撤权/额度与开发隔离；38套163 Java，零失败/错误/跳过，最终Gradle 2m28s。包括3个窗口协议Fixture、5个PG连续套件（3个继承元数据检查、2个新增连续/恢复；实际独立租户与时序系列），2个真实HTTP错误边界，以及原实体/身份/来源/样本回归。Rust --locked 默认41/all-features49，隔离target目录；TypeScript/build 1.34s（既有大chunk提醒）；149关联Playwright 3.3m，含新增9个连续采集明暗/1440/390、未知核验/恢复、丢失控制响应、契约/503无自动重读和身份迟到检查，不与早期9专项相加。7个开发预览会话检查和11个算子生成核对通过；静态仓库检查见收尾记录。

本机实际：原CPU接入窗口超过初始5点门禁，任务暂停且没有批次入场或游标推进；提升完整窗口容量为60后明确RESUME原位置。后台连续确认2窗/12点，时序库实际点值与两份原摘要匹配，标准转换一次，随后停止，无pending。原START POST/GET受理快照保持，重放未重新启动或推进；伪造查询400、未授权401。真实5173页面只读显示2窗/12点及已停止/可明确恢复，业务POST=0，未操作用户原标签与主题。旧CPU5点样本摘要保持，旧固定Host1批3条/3资产保持；原19接入/19创建回执/两凭据及7发布+8草稿=15版本保持。最终平台18972、runtime28428、worker21512、web19696均200/200。原数据、控制内容、协议诊断与截图仅在仓库外本机目录。

首次未通过如实保留：新Schema的私有authority引用没有注册ID，改为闭合复用结构；Java协议Fixture泛型编译修正；客户端重复JSON编码导致原控制响应校验失败，改用统一HTTP客户端的对象body；即时写后回读暴露时序可见性延迟，补连续输出单次有界等待，未知仍保留，测试显式核验不算自动重试；未知核验后任务需要显式恢复，修正状态和实库Fixture停止前提；原回执缺失因遗漏ControllerAdvice触发错误分派401，加入新Controller及真实HTTP404回归；实际CPU每窗6点超过原5点上限，保持失败checkpoint并扩到60点完整处理，61点仍拒绝。首次失败不算通过，结论来自修正后实际输出。开发诊断中路径/命令转义错误已纠正，不作检查通过证明。

目前不宣称所有遥测频率、迟到回看、日志持久输出、周期Host、模型依赖升级、未知范围自动放弃、跨系统事务、物理掉电耐久性、分布式租约/fencing、共享多副本预算或生产容量完成。完整目标active，M0–M4退出门槛不提升，未提交推送。日志/汇总在.tmp/dev-checks/stream117-*，客户原数据和截图在仓库外私有目录。


本节收尾：21份相关文档/202本地链接有效；静态仓库432结构文件/6只读工具、11算子生成及7开发预览会话检查通过。仓库外私有词表检查扫描2600提交候选文件通过，无命中正文或词表输出；git diff --check退出0（仅已有行尾规范提醒）。实际原CPU两批分别6点，2窗12点且无pending，原样本/资产/控制与最终四单元再次核对通过。

最终展示措辞核对：零确认窗口显示“开始于”，不把初始游标标成已确认。随后再次实际TypeScript/build通过（912ms），9个连续采集专项通过（24.6s）；与149关联回归重叠，不合计。相关149结果已在汇总中保存，最终专项使用独立状态字段。


## 118. 固定主机周期采集与跨轮授权预算（2026-10-04）

实现60–900秒周期的固定发布Host→ENTITY采集，复用V043单轮日志和既有执行器；V045只存周期元数据及原控制快照。每轮确认完成后等待间隔，不追赶错过的周期。最后一批确认、整轮累计和下一次时间同事务；授权随后到期保留最近成功。每次明确启用或恢复最多20批，跨轮共用原授权ID、有效期和consumed计数。未知或来源失败暂停，无自动恢复/续权；STOP同时取消当前扫描与后续周期，普通单次START不能抢占，手动STOP关联扫描也停止周期。

实际契约1393（18.41s）；69纯Java main、2224打印检查，新增周期38项覆盖两轮/时钟边界、不追赶、原回执/变更409、停止读入竞态零写、未知原体恢复、20批、到期/撤权、身份隔离和确认后到期保留成功。Java 40套171项，零失败/错误/跳过，最终1m 58s。新增周期PG类5项含3项继承扫描回归和2项周期/恢复；真实PG及资产Observation验证重建后相同原回执、稳定实体、原批次和时间恢复。新增HTTP2项验证原404/401、伪造查询/私有授权字段及缺版本控制；身份适配器新增一项检验重建发现、浏览器注销后原授权跨轮累计、当前身份映射版本撤销。此身份检查是本机协议Fixture，未当作生产IdP验收。

Rust --locked 默认41/all-features49；TypeScript/build 1.16s（既有大chunk提醒）；159关联Playwright（3.6m），含10项周期明暗/1440/390、首次默认读取、丢失控制响应原GET、404后原体重发、失败不轮询、身份迟到，以及原Host/指标/画布/版本回归。真实5173页面只读展示停止后的2轮/6条，业务POST=0，未改变用户原标签与主题；4份周期主题/宽度截图和实际截图均在仓库外，已视觉查看窄屏暗色及真实页。

本机真实原Host工作流两轮各3条，共2确认批/6条。3个资产ID跨轮不变，6份新Observation按原观察时间和batchId回读匹配；明确停止后nextRunAt为空、扫描任务STOPPED。原START POST重放保持原快照，不修改当前停止记录；旧单次START快照和3份原Observation逐项保持。旧CPU连续2窗12点及2份时序摘要重新核对，原19接入/19创建回执/两凭据、7发布+8草稿=15版本保持。平台已重启最终代码，四单元均200/200；原数据、控制命令和回读证据保存在仓库外本机。

首次失败如实保留：迁移未加入启动清单导致17项PG/HTTP失败；补启动后发现尚未加入Gradle资源打包，导致缺迁移的级联失败；补齐后实际全套通过。浏览器首次6失败含已有截图定位到两个details的严格选择器错误，以及协议Fixture把完整URL中的/workflows/误当状态分支；修正精确路径与截图范围后159全套通过。补充名称字段契约复用和确认后到期事务统计后重新跑领域及Java，结论只来自最后实际输出。过程中两个本机Gradle调用短暂重叠，后续均取终态及最终XML核对，不将启动或编写测试等同通过。

静态仓库437结构文件/6只读工具、11算子元数据、7开发预览会话检查通过；24份相关文档/209本地链接有效。仓库外私有词表检查扫描2623提交候选文件通过，无词表或命中正文输出；git diff --check通过（只有已有行尾规范提醒）。日志/汇总在.tmp/dev-checks/host118-*。

当前不宣称日志持久输出、迟到/任意频率遥测、未知范围放弃、完整快照缺失资产生命周期、模型依赖迁移、长期后台委托、跨系统事务、物理掉电耐久性、分布式租约/fencing、共享多副本额度或生产容量完成。完整目标active，无提交推送，M0–M4退出门槛不提升。


## 119. 模型候选兼容性与固定引用（2026-10-04）

本节实现自己的保存候选差异检查、当前授权固定模型引用、列表/字段全称/精确版本导航及抽离组件。报告仅是元数据，发布事务仍重新验证；不代表破坏性迁移、工作流自动重绑定或任务升级。沿用四单元及既有PG表，无新迁移、数据库或后台框架。协议、边界、操作分别见[模型影响契约](../contracts/model-impact.md)、[ADR-081](adr/081-model-revision-review-and-fixed-references.md)及[操作说明](runbooks/model-impact.md)。

实际执行：

| 检查 | 实际结果 |
|---|---|
| `pytest tests/contracts -o addopts='' -q` | 1427通过，17.06秒；封闭请求、私有数据/身份覆盖、边界、权限缺失及矛盾兼容决定拒绝 |
| `scripts/check_java_domain.py` | 70个main、2276项打印检查通过；新增ModelImpactSmoke 52项，覆盖追加策略与发布规则一致、枚举分隔歧义、数值等价、私有候选/CAS、租户隔离、定向端点、引用截断、目标字段、本人任务与无执行 |
| Java HTTP/PG和身份适配器 | 42套181测试，失败/错误/跳过均0，2分23秒；真实loopback HTTP与PG，新增自己的保存候选检查、发布冲突/不兼容、明确未读取工作流、固定草稿/发布引用和只读前后比较；测试tenant隔离，输入显式Fixture |
| Rust锁定默认及all-features | 41/49通过；未改锁与运行后端 |
| TypeScript/build | 通过；已有大chunk提示保留 |
| 关联浏览器 | 194通过，4.0分，retry=0、workers=1；含模型/导航、固定来源、画布/算子/比较、单轮/周期主机、连续指标、输出、原控制和身份 |
| 最终模型与导航专项 | 38通过，33.7秒；与前项重叠，不合计。覆盖发布失败人工重查/编辑失效、矛盾响应拒绝、追加下一版与原版本引用、精确字段/目录外模型/关系端点、只读失败恢复、多页签关闭旧抽屉及主题布局 |
| 静态仓库/算子/本地预览 | 442结构文件/6只读Tool、11算子、7预览检查通过 |
| 文档链接/外部资料边界/diff | 28文档/231本地链接通过；私有词表检查2646候选通过；diff退出0（既有CRLF提示保留） |

初次新增领域检查出现两轮Java测试源码语法错误，修正后实际编译与所有main通过；不将测试文件写好等同检查通过。首次18项模型浏览器执行有3失败：两项用了不存在的主机`name`字段（实际为`hostname`），一项错误提示定位器不唯一；修正Fixture与定位后，关联193项中1失败暴露UI选择的`field`被混入封闭ref，改为只传ID/revision。随后21项专项及194关联完整通过；再补充多页签跳转、目录外关系端点和追加下一版，最终38专项通过。未隐藏失败或自动重试。

本机真实只读验收：新引用接口返回11项当前授权引用，其中保留既有Fixture标识；原真实固定Host工作流的本人单轮扫描/周期摘要均STOPPED，映射字段为完整模型字段。19接入、19创建回执、2凭据、15工作流版本及原连续指标任务元数据与§118逐项比较保持；周期仍2轮/6条。当前引用响应实际通过契约校验。没有新增接入、凭据、模型/流程版本、采集或输出命令，不把本轮元数据比较声称为业务点/Observation的重新回读。

实际Web5173的1440/390明暗四种视图只读显示11项，完整字段可跳转；业务POST=0、页面错误=0、根页面无横向溢出，表格自身局部滚动，完整长ID换行；实际查看桌面浅色和窄屏深色截图。操作使用自己的无头浏览器，用户标签和主题未动。原平台实际重建/重启后PID26556:8080，runtime28428:8090、worker21512:8081、web19696:5173，均200/200。证据保存在仓库外`D:/workspace/ops-weave-private/acceptance/model119.json`及`checks/model119`，本机汇总`.tmp/dev-checks/model119-report.json`。

日志持久输出、超60点完整分页/迟到回看、未知范围显式放弃、破坏性模型迁移/运行升级、质量/保留/容量和生产HA租约/fencing尚未完成。当前引用不构成全租户清单或跨目录/任务原子快照；不声明多副本、跨系统事务或断电耐久性。完整目标active，M0–M4退出门槛保持，未提交推送。


## 120. 固定手工日志持久批次与原结果确认（2026-10-04）

### 实现与实际边界

按既有可选日志引擎要求与ADR-082新增logs基础设施profile，镜像固定digest、loopback8123和私有随机凭据；没有增加业务启动单元或隐藏执行框架。受限应用账号只SELECT/INSERT，初始化DDL由部署完成，实际版本25.8.33.6。固定表/13字段/引擎/排序键及SELECT可用性由适配器实际检查；固定SQL使用typed parameters，无代理、重定向、DDL、客户端表名或任意URL；请求/响应有时限和64KiB上限。

V046与两种WorkflowStore实现保留17字段元数据回执，正文只进入日志引擎。MANUAL_SAMPLE→LOG固定已发布版本和最近本人RUN、输入摘要/trace/count/source/target、24小时/5条、可信独立log权限和共享IO预算在Java执行器核对。PG先保存PENDING再外部写一次、按tenant/owner/request/固定版本精确查询并显式FINAL；完整索引/原文摘要匹配后才CONFIRMED。原UUID重放只读旧回执，变更409、跨owner/tenant404。写后读取失败无论端口异常分类均UNKNOWN；进程重建和历史未知只查询验证，禁止自动补写。数值标量摘要统一无指数十进制，测试1e-7跨Java/浏览器一致。

UI入口默认折叠，展开只读一次能力/历史。请求容器与纯展示面板分离；用户明确写入才读取当前样本RUN并提交命令。原输入只在内存，网络/契约未知锁定原请求，原404才显式同键重发；历史无原文的未知只能查询。清除身份取消并丢弃迟到结果，正文只显示转义文本，不执行HTML/脚本/其中URL。历史CONFIRMED和当前不完整/失败读取分开。

### 实际运行的检查

| 检查 | 实际结果 |
| --- | --- |
| 全部契约pytest | 1474 passed，23.58s；封闭权限/对象范围、状态/数量/预算、完整数量与正文格式拒绝 |
| 纯Java领域脚本 | 71个main，2330条打印检查；日志新增54条，包括写后读失败UNKNOWN/恢复不重写、独立对象权限、重复/变更、时间范围与数值摘要 |
| Java实际HTTP/PG/日志与适配器 | 45套/192测试，零失败/错误/跳过；最后BUILD SUCCESSFUL 2m 10s |
| Rust runtime | MSVC环境cargo --locked默认41、all-features 49；本轮未改Rust实现，没有新增Rust fmt声明 |
| TypeScript/生产构建 | tsc --noEmit及Vite退出0，built in 957ms；已有大chunk提醒保留，无依赖升级 |
| Playwright关联回归 | 214通过，4.5m；workers1/retry0，覆盖日志/模型/导航/Host周期/指标持续/来源/输出/画布 |
| 最终日志专项 | 17通过，17.3s；与关联集重叠，不相加 |
| 本地会话/算子/静态边界 | 7会话、11算子生成检查；Repository static checks passed: 453 structured files; 6 read-only tool definitions |
| 文档链接/资料边界 | 31 documents; 249 local links checked；Reference boundary check passed: 2678 commit candidate files；git diff --check退出0，既有CRLF提示保留 |

### 本机实际证据

仓库外`acceptance/log120.json`保存一次主本机Fixture手工流程发布/RUN→实际Java→PG→日志库的原命令与回读：2条CONFIRMED、索引/时间/空格/换行/Unicode/引号/可选缺失逐项一致。主本机未用外部日志连接器，Fixture指输入，日志存储实际。重放后直接物理查询仍2条；PG直接查询只有1份17键回执，没有正文/样本/权限/凭据。`log-store/storage-proof.json`、`rebuild-proof.json`分别保存物理计数和平台重新建立后原回执/正文只读保持的实际结果。

19接入、19创建回执、2凭据和原15流程版本逐项元数据保持；新增一个日志Fixture流程，共16版本。既有Host周期仍STOPPED、2轮/6条，连续指标Task元数据保持；本阶段未重新验证其全部Observation或时序点，沿用早期对应阶段的证据。四单元最终200/200，平台PID见本机devctl状态。`checks/log120`保存四种实际1440/390明暗页面，只GET原回执和正文，业务POST0、页面错误0、根横向溢出0；已查看1440亮色及390暗色截图，用户标签页未改。

### 初始失败与修复

第一轮领域编译缺少WorkflowLogOutput导入，修正后完整脚本通过。一次开发修改脚本因引号语法未成功修改，改用直接patch后构建通过。初始容器引导命令失败，随后独立就绪确认成功；不把首次失败写成通过。

首次Java188项有4失败：3个协议Fixture把URI的`+`当空格，改用URLDecoder；实际日志写入返回UNKNOWN，直接诊断证明存储MEMORY_LIMIT_EXCEEDED。原512MiB/384MiB不足，受限profile改1GiB/768MiB并重建后，新批次通过。旧不明批次不自动补写，不据错误推定完全未落地。收尾复核加入显式log资源到可信授权文件/Schema，并补独立对象权限检查；同时加入小数规范化和写后read(false)仍UNKNOWN的必要反例。全部最终检查均对修复后代码实际运行。

### 后续

这里只验收手工有限日志批次基础。外部日志来源/持续采集/checkpoint、完整分页/迟到回看、未知范围显式放弃、破坏性模型迁移、质量/保留/备份/容量以及生产身份/HA租约/fencing仍需继续。存储引擎去重/本机PG事务不等于跨库exactly-once或物理掉电证明。完整目标active；无提交推送，M0–M4退出门槛不提升。


## 121. 完整指标分页与上一分钟迟到补采

日期2026-10-04；本节仅记录实际运行结果，不提升生产退出门槛。

- 契约：1479通过，20.53秒；纯Java 71个main、2341打印检查，持续窗口48检查。
- Java：46套/203检查，失败0、错误0、跳过0；3m 9s。实际PG V047迁移、大证明重开、旧16字段解码、实际时序598→600点补采，以及真实本机来源300→301点分页/迟到联测；输入为Synthetic Fixture，来源/存储协议真实，固定绑定及授权服务端口在测试中显式构造，不冒充完整接入注册链路。
- Rust --locked默认41、all-features 49；TypeScript生产构建3.40s，既有大块提示仍在。
- Playwright关联221项/4.6m、连续专项16项/43.5s，相互重叠不相加；原控制/身份晚到/未知门禁与补采计数、600时间戳、窄屏明暗主题实际通过。
- 真实来源7.0.27独立测试Host 300点整窗分页确认，随后新增1点，回读301点；原证明与cursor不变，输出无覆盖旧值。证明日志统计14次历史读取，包含有界只读等待，不是单窗请求数。专属Host名称/标签检查后清理；没有改既有Host。
- 实际平台重建为PID3460，原周期Host状态、19/19/2接入/创建回执/凭据及16流程版本逐项保持；原连续Task/摘要保持，12个旧点值和2条原日志正文重新只读匹配。实际四种页面明暗/宽度无异常、业务POST0、无根溢出，用户页签未动；四单元200/200。
- Repository static checks passed: 453 structured files; 6 read-only tool definitions；Workflow operator metadata: 11 built-in operators verified；开发会话7项；32 documents; 261 local links checked；Reference boundary check passed: 2682 commit candidate files；git diff --check退出0，仅既有CRLF提示。

首次真实大批次5秒回读未完整可见而为UNKNOWN；使用原批次只读核验确认，没有重发POST。首次回归还发现旧16字段JSON缺省反序列化失败和浏览器复用同页的测试准备错误，分别修复解码缺省与测试隔离，最终上述实际检查通过。关联预览首次启动超时，扩大预览启动等待预算后完成回归。开发管理器原taskkill停止失败，核对PID5564所属仓库jar后只停止该平台进程，由管理器重新构建启动；其他三个单元未重启。

局限：仅上一已确认分钟的新增标准点；全过滤迟到行不计latePoints，停止/到期不继续回看，更早迟到/历史修订、批次显式放弃、外部/持续日志及质量保留/容量/HA未完成。未执行V047回滚、生产身份负载、故障切换或断电耐久性检查；没有生产部署或提交推送。完整目标active。

[决策](adr/083-bounded-metric-pagination-and-late-points.md) · [操作](runbooks/workflow-metric-streams.md) · [契约](../contracts/workflow-metric-streams.md)

## 122. 固定外部日志来源、服务器样本写入与真实链路（2026-10-04）

本轮完成ZABBIX_LOG固定配置/日志项pin、自己的发现选择、前后metadata受控读取、只读预览/发布/谱系、四字段服务器样本写入和原回执确认。正文不由客户端传入或写入PG，最多5条最新样本与完整来源窗口分开；原手工路径、旧定义摘要及17字段回执保持。输入字段/清单和请求生命周期组件分开，日志源/目的授权独立。

实际运行并通过：

- 契约pytest：1507，37.50s；闭合源pin/选择/目标/源命令以及禁止client samples/身份/URL。真实主链路7份wire另外按对应schema验证。
- 纯Java：72个main、2380项打印检查；新日志来源39项，涵盖发现出处/过期/类型/权限、发布与固定源、原UUID不重读、命令namespace、未知只读确认和版本差异。
- Java：46套/202测试，失败0、错误0、跳过0，2m58s；真实PG/VM/日志引擎及HTTP旧路径保持，日志协议6项验证原文/缺失事件字段、两次metadata、条数/时间/顺序/字段/截止预算和闭合JSON。未重跑第121节已清理的300点来源Fixture专项，原12点在当前主平台真实回读保持。
- Rust --locked：默认41、all-features49；TypeScript生产构建1.13s，既有大块提示保持。开发会话7项和11个算子元数据检查通过。
- Playwright：250关联测试/5.4m；最终字段布局调整后29日志专项/30.6s通过，二者重叠不合计。来源选择、只读预览发布、原命令四字段、未知/原404、身份取消/晚到响应、正文转义和窄屏明暗得到覆盖。
- 主平台实际SOURCE日志链路：本机正式来源服务7.0.27中的专属Synthetic Fixture Host/item，经原已维护配置和账号发现，2条原文被Java服务器读取转换并在日志库25.8.33.6完整回读；原UUID复用只返回回执，变更409、额外samples/tenant/query拒绝、无认证401。原文未进入运行元数据；无未标记Mock或来源失败回退。
- 本机重建平台为PID30816，其他三单元未重启；四单元200/200。19/19/2来源/创建回执/凭据和原16版本逐项保持，新增1个明确Fixture版本共17。原Host周期、标准指标task/proofs保持，12个原指标点及2条旧日志原文重新只读匹配。
- 实际页面1440/390明暗四种来源抽屉/字段与确认日志回读；业务POST0、页面错误0、根溢出0，已查看宽屏来源和窄屏正文截图；用户页签未动。原始回读、命令和截图只在本机仓库外保存。

首轮关联回归244通过/6失败：日志Fixture未固定算子、option禁用断言采用了不适用的辅助断言、漂移错误文案使用指标名。分别修复Fixture固定版本、保留原生disabled属性检查，并改为通用来源项错误文案；最终250项全通过。视觉检查发现字段说明嵌入header段落，调整至来源配置之后，再实际构建、29专项和四页面复验通过。

首轮真实发现未包含测试日志项：测试Host误建在接入账号不可读的主机组。核对专属Host名称/Synthetic Fixture标签后，只将该测试Host移到既有可读主机的组，再新建服务端发现快照完成链路；没有扩大令牌权限或修改既有Host。测试Host/item和明确Fixture流程保留供检查，其最新样本在10分钟后自然不可再预览，不自动追加日志。

静态检查：460结构文件、6只读工具定义；私有词表边界2704候选文件；git diff --check退出0，只有既有CRLF提示。34份文档/280本机链接检查通过。没有提交推送、生产部署、HA/故障切换或生产负载认证验收。

局限：本轮是外部样本闭环，连续日志完整窗口、稳定事件身份/去重/checkpoint未完成；更早迟到和历史修订、UNKNOWN显式放弃、破坏性模型迁移、S7质量/保留/容量与生产HA继续。完整目标active，M0–M4退出门槛不提升。

[设计](adr/084-fixed-log-workflow-sources.md) · [来源协议](../contracts/workflow-log-sources.md) · [输出](../contracts/workflow-log-output.md) · [本机操作](runbooks/workflow-log-output.md)

## 123. 完整LOG窗口与整窗存储端口（2026-10-04）

2026-10-04 第123节：完整LOG窗口读取、纯处理及批量日志端口接通。固定来源60秒半开窗口、settle10秒、最近24小时内，最多1,000条/20次历史请求/20秒来源预算；clock/ns严格升序，满页重读完整边界秒，元数据漂移、失败、超量与超时均不返回成功前缀。来源接收位置与映射eventTime分开，原eventId不冒充全局身份；算子仍按5条内存执行，但全部校验完成才形成一次整窗输出，FILTER保留原索引。输入/输出摘要区分缺失与空文本。

既有日志库新增V002窗口表，UInt32索引及source_position；实际专属小表试验拒绝旧排序键类型扩大（Code 524），因此保留原样本表和5条契约。包内共用固定loopback有界HTTP，样本64KiB、窗口8MiB，正文不进SQL/普通日志，应用账号只有固定表SELECT/INSERT；没有新数据库、PG正文表或启动单元。新内部数据Schema/样例不构成公开HTTP任务或Web连续入口。

实际1531契约、73纯领域main/2403打印检查、49套215 Java检查零失败/错误/跳过、Rust锁定41/49、TS/build、46日志浏览器通过。真实本机来源7.0.27的专属Synthetic Fixture 1,000条同秒不同纳秒位置完整读取，经固定算子一次写入25.8.33.6日志库并逐条回读匹配，正文Unicode/换行/空白/缺失上下文保持；再次来源/存储读取位置和值一致，没有第二次输出。1份实际1,000行wire按新Schema校验。该联测不含PG窗口证明或持续任务。

原19接入/19创建回执/2凭据、17流程版本、原Host周期/指标任务及12旧指标点、原样本正文只读保持。平台重建PID29312，四单元200/200；1440/390明暗四种实际页面复查业务POST0、pageErrors0、无根溢出，用户页签未动。持续LOG任务、不可变证明/原UUID、代数停止、短时委托、未知只读恢复、迟到补采去重及确认后检查点继续；更早指标迟到/历史修订、UNKNOWN放弃、破坏性模型迁移与S7仍未完成。目标active，未提交推送，M0–M4退出门槛不提升。见[窗口契约](../contracts/workflow-log-windows.md)、[ADR-085](adr/085-bounded-complete-log-window-ports.md)及验证报告§123。

实际执行记录：

| 检查 | 结果与边界 |
|---|---|
| 契约pytest | 1531 passed，21.08s；新增内部记录/窗口正反例，不是公开持续任务API |
| Java纯领域 | 73个main、2403项打印检查；新LOG窗口23，覆盖1,000条、过滤索引、全窗拒绝与摘要 |
| 平台选择性集成 | 49套215，failures/errors/skipped均0；最终Gradle2m18s，包含原PG/VM/CH及新的真实来源/存储联测 |
| LOG协议/存储故障Fixture | 来源7个、存储5个JUnit；边界/密集秒/失败页/解析耗时/漂移、一次POST/丢失ACK/重复字段与超响应均实际通过 |
| Rust | --locked默认41、all-features49通过；未升级锁或新增Runtime执行能力 |
| TypeScript/build | 实际通过，1.43s，保留已有较大chunk警告；没有前端源码/依赖升级 |
| 日志浏览器 | 46通过，47.8s；独立loopback预览，无用户标签页修改 |
| 实际服务链路 | 7.0.27专属Synthetic Fixture同秒1,000条→完整窗口→固定算子→25.8.33.6窗口表→精确回读；两次来源/存储读取相同，输出只有一次。实际wire按新Schema通过 |
| 实际旧页面 | 1440/390亮暗四种，只读已发布来源字段与原样本回读，业务POST0/pageErrors0/根溢出0；截图另存本机并已查看 |
| 主环境只读核对 | 19接入/19创建回执/2凭据、17流程版本、Host周期、原指标证明和12点、原日志样本保持；不是新持续LOG任务验收 |
| 部署与运行 | V002由私有管理身份应用，新表只授予应用SELECT/INSERT；compose config -q通过；平台29312、Runtime28428、Worker21512、Web19696四单元200/200 |
| 辅助检查 | 11算子与7开发会话检查通过；结构464份文件/6个只读Tool、私有边界2720个候选、37份文档/455个本机链接及diff检查通过（原有CRLF提示保留） |

旧表排序键UInt8→UInt32的专属小表试验两次实际被Code524拒绝，小表已清理，没有修改旧样本表。首次领域Fixture将FILTER放在MAP前违反已存在结构，调整Fixture后最终纯领域通过；首次新增Python契约测试使用保留字造成收集错误，修正后最终1531通过。实际来源Fixture推送已观察成功后，版本查询携带认证被拒绝；修正匿名版本查询，原1,000条未重推。首轮49套214通过后增加解析预算/日期错误检查，最终49套215通过，以上数量使用最终结果、不重复累加。

本轮源码只提供内部完整窗口及批量端口。未新增公开连续任务、控制回执、PG窗口证明/检查点、迟到补采、HA、生产保留或容量声明。来源分页并非事务快照，元数据两次一致不能证明历史行未变。没有自动源读取重试、输出重试或失败Fixture回退；正文/秘密只留在受控内存、日志库与仓库外Synthetic Fixture证据。

## 124. 确认后推进的持续日志窗口（2026-10-04）

| 实际检查 | 结果与范围 |
|---|---|
| 契约 | 1544通过；新增任务/存储、批次、控制/存储、状态、分页与核验闭合Schema，禁止正文、来源、私有身份注入；15份真实主服务wire另按Schema校验 |
| 纯领域 | 74个main / 2447条打印检查通过；持续日志44条覆盖1000位置、分页、空窗、FILTER、迟到去重、旧输入变化、UNKNOWN只读、代数停止、授权/范围和20失败批后的原确认依据 |
| 平台 | 50套221项，失败0/错误0/跳过0；实际PG重开、原控制、UNKNOWN、回滚、隔离、元数据拒绝正文；实际日志库998→1000补采、50条分页，以及确认依据不依赖最近20批 |
| Rust | --locked默认41 / all-features 49通过，无锁文件升级 |
| TypeScript/build | 实际通过；原有大chunk提示保留 |
| 浏览器协议Fixture | 75关联首次通过；之后收紧文本上下文类型并增加不完整回读场景，最终14专项通过。76个不同场景，重叠不累加；同秒1000条纳秒位置、50条分页、明暗/窄屏、原摘要、UNKNOWN、错误停轮询、身份清正文 |
| 主服务真实链路 | 现有受控连接/vault/固定发布日志来源，本机7.0.27的明确Synthetic Fixture确认2条，迟到追加1条并去重2条；补采checkpoint保持，停止/恢复与健康空窗后累计2窗3条，任务最终停止；25.8.33.6实际回读正文和纳秒位置精确 |
| 实际页面 | 1440/1024/390明暗六种，只读任务/迟到记录，POST0/pageErrors0/根溢出0；已查看1440亮色任务和390暗色正文截图，用户页签未动 |
| 原环境保持 | 19接入/19创建回执/2凭据、17版本、原Host周期/指标任务及12指标点、原日志样本只读一致 |
| 最终运行与辅助 | 平台最终PID25980，Runtime28428/Worker21512/Web19696不变，四单元200/200；重建后原持续任务和2+1日志逐条只读保持。结构474文件/6只读Tool、11算子、7会话、私有边界2748候选、40文档481本机链接、diff检查通过（原CRLF提示保留） |

首轮新测试缺少适配器导入，修正后编译。随后测试揭示V048未进入资源打包，补齐固定声明；HTTP错误处理未覆盖新控制器，原404经错误分发成为401，加入既有封闭错误处理并增加非规范UUID拒绝检查。全部平台检查在修正后实际重跑通过。私有联测记录的最终停止快照覆盖了本机命令字段，按原回执摘要恢复精确命令，没有重发来源或服务器控制。末次复查发现回看依赖最近20批显示列表，改为固定窗口查询并实际增加/运行历史证明恢复检查。

连续任务只属于本机受限执行，不是生产日志管线验收。来源分页不是事务快照；最近分钟补采不能覆盖更早迟到或历史改写。没有隐式来源/输出重试、授权续期或Fixture回退；UNKNOWN显式放弃、分布式fencing/HA、生产容量/保留、独立OIDC日志端到端联测仍未完成。正文、凭据和原始Synthetic Fixture证据不进仓库。目标未标完成。

## 125. 固定版本只读运行质量与异常（2026-10-05）

本节在既有四启动单元/数据库内实现原批次观察，无来源读取或输出端口。窗口计数按批次，主机缺失分项为null；UNKNOWN/IN_FLIGHT、迟到新增/指标旧点重交与空窗口区分，确认率没有分母显示“—”。固定版本过滤先于21条查询上限，20条公开截断及原UUID查询保留；原摘要/时间损坏失败不隐藏成空结果。

| 实际执行 | 结果 |
| --- | --- |
| 契约 | 1563通过，14.55秒；四份闭合质量Schema及原契约回归 |
| 纯Java领域 | 75个main、2477打印检查；质量30项含UNKNOWN/空窗/来源失败/迟到/重交/本人隔离/旧批查询/截断 |
| Java平台与端口 | 52套226项，失败/错误/跳过0；真实PG固定版本过滤、重开UNKNOWN不变、主机逐实体权限与正文隔离、损坏元数据，真实HTTP身份/查询参数/UUID/405边界；原接入/目录/流任务/输出/OIDC关联回归 |
| Rust | 实际锁定默认41/all-features49，使用现有工具链与target目录；无依赖变更 |
| Web | 实际TypeScript/build通过，保留原较大chunk提示 |
| 浏览器协议Fixture | 首次关联59场景58通过，403测试断言原区域内错误但可信会话会清空页面；改为全页会话清理断言。质量19专项最终重跑通过；59个不同场景，重复不累加。最终专项目录与六种主题/宽度复查 |
| 真实主服务 | 三类原证明四版本；4份报告+2份原日志批次共6份wire验证；初始2条、补采输入3/去重2/确认1，原任务STOPPED和证明一致，无业务POST/来源请求 |
| 实际页面 | 1440/1024/390明暗六种，批次/原关联/计数真实，POST0/pageErrors0/根溢出0；查看亮色宽屏及暗色窄屏截图。用户页签未动 |
| 原环境 | 19接入/19创建回执/2凭据、17草稿+发布条目（其中9发布），原Host周期/指标任务及12点、原日志样本/连续2+1条只读保持 |

首轮纯领域和平台编译暴露目标方法名、测试Principal访问器/位置类型和适配器导入错误，逐项修正后实际重跑。新PG样例用实体映射字段导致日志目标拒绝，改为合法日志字段；UNKNOWN原证明断言也改为已保存的UNKNOWN状态，而非准入IN_FLIGHT。框架对不支持写方法转到错误路径返回401，接口明确拒绝返回405后验证通过。真实只读辅助脚本最初误将17个草稿+发布条目当作17发布，按原基线比较9个发布及全部17条目修正，没有改动服务器元数据。

这是S7基础观察，未完成专门schema/identity/timestamp/unit/queueWait计数、阈值告警、历史修订/重放、UNKNOWN明确放弃、保留与生产容量、分布式租约/fencing/HA或独立OIDC持续LOG端到端验收。真实来源中的输入为明确Synthetic Fixture，不是客户生产数据；没有提交/推送。最终辅助检查及服务状态由本节末项记录补充。

最终辅助实际通过：479结构文件/6只读Tool、私有边界2768候选、43文档493本机链接，diff退出0（原CRLF提示保留）。最终Platform PID30988、Runtime30108、Worker8600、Web31904均200/200；Rust/契约/领域/平台/Web/页面均实际运行，无提交或推送。完整目标仍active，本节为进展。


## 126. 实际转换处置、节点异常与指标原窗口恢复（2026-10-05）

持续指标/日志读取成功后在原转换评估中记录received、accepted/rejected/filtered、unknown和闭合节点计数，第一组错误后的剩余记录不冒充结果。来源端口未返回列表时SOURCE_FAILED数量null、nodes为空。结构/必需标识/时间/单位类别按已测拒绝行去重，类别可以重叠；单位元数据未读出列表的拒绝没有分母，queueWait/采样总体未测量。

V049只追加闭合元数据，200条本人容量在IO前检查，追加前重查当前代数/身份/版本；固定版本21条查询→20条及截断、原UUID查找、读取权限和损坏失败。新增请求容器、纯表格/节点详情与严格解析。指标原确认窗口专用查询先固定范围，不依赖可见20条。

| 实际执行 | 结果 |
| --- | --- |
| 契约 | 1582通过，26.82秒；新增19条检查契约用例，四份Schema闭合/nullable/状态/截断边界 |
| 纯Java领域 | 76个main、2506打印检查；新29项覆盖部分10→4/1/5、来源时间/标识、健康空读取、Source不可用、停止代数、容量IO前拒绝和21失败后的指标补采 |
| Java平台与端口 | 54套233项，失败/错误/跳过0，最终2分43秒；真实PG不可变追加/事务回滚/重开/租户本人/固定版本/损坏/200容量，隔离Schema迁移→回滚→重建保留发布表，真实时序库在21失败后补采，HTTP身份/参数/UUID/405及原关联回归 |
| Rust | 锁定默认41/all-features49，实际运行；没有依赖变更 |
| Web | TypeScript/build实际通过，1.32秒；保留原较大chunk提示 |
| 浏览器协议Fixture | 首轮77场景76通过，1处403测试先等待已清空区域的竞态；修正为直接验证拒绝后的清空。最终检查统计20+质量19共39专项通过（1.2分钟）；其余38关联首轮通过，重复不累计 |
| 真实来源/主平台/PG | 两个独立Synthetic Fixture LOG item/发布流程；来源直接拒绝空正文统计不可用，节点映射后拒绝得到10输入/4通过/1拒绝/5未检查/结构1。失败均无该窗口输出准入、checkpoint不动、原观察UUID读取一致；任务保留FAILED暂停，没有自动恢复 |
| 真实wire | 6份三类质量报告+2份旧日志批次+2份检查报告+2份原观察，共12份按Schema验证 |
| 实际页面 | 1440/1024/390明暗六种，真实10/4/1/5及EMPTY_REQUIRED节点明细，POST0/pageErrors0/根溢出0；查看亮色宽屏和暗色窄屏截图，用户页签未动 |
| 原环境 | 原19接入/19创建回执/2凭据、17草稿+发布条目保留；只新增2个独立Synthetic Fixture发布条目，当前19条目。原Host周期/指标任务、12旧指标点、原日志样本和连续2+1条只读保持 |

新领域测试最初调用不存在的store访问器，平台测试泛型assertEquals产生重载歧义，修正后实际重跑。首轮真实辅助脚本按整分钟放置测试记录，而START采用当前整秒前60秒；读取原状态并停止专属任务后，在下一原窗口注入新的独立记录并明确恢复，没有重发原push。来源端口直接拒绝空正文时没有返回列表，观察保持不可用；另用合法来源值经固定ENUM_MAP后产生节点空值，验证实际部分统计。STOP对FAILED返回409，保留暂停状态，没有通过恢复绕过它或声称已停止。

主机细分、unit元数据漂移及来源返回前的分母、排队时间、告警/阈值、历史修订/重放、UNKNOWN放弃、保留/生产容量、分布式租约/fencing/HA及独立OIDC持续LOG验收尚未完成。上面真实输入均为明确Synthetic Fixture，无客户生产流量、无提交/推送。目标active，本节是实际进展。

最终辅助实际通过：484结构文件/6只读Tool、私有边界2790候选、46文档509本机链接，diff退出0（原CRLF提示保留）。日志单位字段的服务端闭合约束补充后，领域76/2506、平台54套233项再次实际通过（最终2分43秒）；已重新构建/启动平台并只读复查真实wire和原环境。Platform PID2892、Runtime30108、Worker8600、Web31904均200/200。完整目标仍active，无提交/推送。


## 127. 固定主机分页检查统计与实体范围

2026-10-05 第127节：固定连接主机分页接入实际转换处置与节点统计。每页最多5条，包含过滤/拒绝实体的私有UUID范围见证；known received与见证数量一致。当前可信身份逐实体读取门禁，正常检查关联原批次并复核版本、数量与实体集合，损坏/失配失败。新分页首次评估记录一次，拒绝回滚批次准入、不输出/不推进检查点；UNKNOWN显式恢复使用原分页，不重读来源或重复统计。200条本人容量在IO前和追加前验证，停止竞态保留原门禁。

| 实际检查 | 结果 |
| --- | --- |
| 契约 | 1595通过，20.82秒；新增私有stored与公开隔离、5条上限、UUID/窗口/单位约束 |
| 纯Java领域 | 77个main/2528打印检查；主机专项22，含完整/过滤/拒绝/空页/不可用、逐实体权限、容量、停止竞态及UNKNOWN恢复不重复 |
| 平台 | 54套237项，失败/错误/跳过均0；真实PG重开、原UUID恢复、私有范围/父批次失配和拒绝回滚等 |
| Rust | --locked默认41/all-features49通过 |
| Web | TypeScript/build通过，2.02秒；原较大chunk提示保留 |
| 浏览器协议Fixture | 69相关场景通过，2.1分钟；检查统计30、质量19及主机运行/周期20 |
| 真实来源/主平台 | 正常确认6条（5+1）；异常末页1输入/0通过/1拒绝/0未检查/结构1，无该页批次准入，原前页5条确认保持 |
| 实际wire/页面 | 9份按Schema验证；1440/1024/390明暗六种，POST0/pageErrors0/根溢出0，已查看宽屏亮色和窄屏暗色截图；用户页签未动 |
| 原环境 | 19接入/19创建回执/2凭据、19流程条目保持；新增2专属Synthetic Fixture流程，当前21；周期/12指标点/旧日志及连续2+1、上节两条诊断和暂停状态保持 |

真实辅助脚本首次语法检查失败，修正后执行，没有先发生业务调用。新测试主机最初在受控账号不可见分组，首次扫描实际只读取并确认原5条；保留其检查和批次，调整仅专属测试主机到原测试主机分组，然后以新UUID/代数启动新扫描。没有修改受控凭据、原发布流程或重发未知来源写入。真实输入均明确Synthetic Fixture，不是客户生产流量。

S7及总目标仍active：来源返回前的异常分母、单位元数据漂移细分、queueWait、阈值告警、历史修订/重放、UNKNOWN显式放弃、保留与生产容量、租约/fencing/HA、生产身份及独立OIDC持续LOG验收继续。无新增迁移/数据库/启动单元，无提交或推送。

最终平台显式重跑27个Gradle任务，54套237项零失败/错误/跳过，2分44秒；之后只读复核新增主机的原观察/任务/检查点、原接入与流程、12指标点、日志2+1及上节暂停诊断保持。辅助实际通过：486结构文件/6只读Tool、2795私有边界候选、47文档519本机链接；diff退出0（原CRLF提示保留）。四单元最终200/200：Platform PID28744、Runtime30108、Worker8600、Web31904。总目标active，无提交或推送。


## 128. 固定任务明确终止恢复（当时待恢复，最终复验见第129节）

2026-10-05 第128节（最终联测与部署待恢复）：固定 HOST/METRIC/LOG 任务增加明确终止恢复与不可变原UUID回执。任务转ABANDONED、代数加1、撤掉后台授权，原批次保持UNKNOWN，确认游标/数量及可能已写入的内容不改；主机公开回执的preservedCursor固定null，私有分页仅留在内部检查点；200条本人容量与任务修改同事务。核验返回重查状态/代数；同版本不能重启/恢复，更高发布版本明确START建立新任务。按版本状态查询隔离旧任务与批次，控制代数单独返回，历史质量观察保留终止事件；回执查询使用实际流程对象范围，不多要求通配权限。

已实际通过：1625契约，78个纯Java main/2576打印检查（恢复专项48），Rust --locked默认41/all-features49，TypeScript/build、平台bootJar及124相关浏览器场景。六种1440/1024/390明暗协议Fixture覆盖，已查看宽屏亮色与窄屏暗色截图。终止恢复初版真实PG/指标库/日志库检查曾通过；版本隔离和范围权限扩展后的最终真实联测尚未通过，不能用先前结果替代。

| 实际检查 | 结果 |
| --- | --- |
| 契约 | 1625通过，36.14秒；命令确认、原回执闭合、ABANDONED及版本控制边界 |
| 纯Java领域 | 78 main/2576打印检查，恢复专项48；主机第二页私有游标不外泄/前页5条确认保持，三类原UNKNOWN、原UUID重放、并发核验、容量、时间回退、精确对象范围及新旧版本保留 |
| Rust | --locked默认41/all-features49通过 |
| Web | TS/build通过，2.49秒；原较大chunk提示保留 |
| 浏览器协议Fixture | 首轮121通过/1定位器多匹配失败；修正为精确summary后最终124通过（含私有游标拒绝）；本轮耗时见原始本机日志；不是主平台实际页面验收 |
| 平台构建 | 当前源码bootJar成功；包构建不等于启动/迁移/真实联测通过 |
| 最新平台真实联测 | 55套249项，140失败、错误/跳过0；Docker不可用时真实依赖失败，未回退Fixture输出；恢复后须全量重跑 |
| 主环境保持 | 当前尚未复核，不沿用上一节旧结果宣称新部署保持 |

早期编译发现调用不存在的方法/错误枚举、Schema引用失配及测试Fixture来源门禁/缺失字段，已修正并实际重跑对应检查。终止恢复初版真实PG/指标库/日志库检查曾成功；后续增加版本隔离、历史投影和精确范围后，最终扩大联测失败。Windows Docker API随后不可连接，CLI启动和WSL启动后引擎仍连续超时，辅助服务启动被当前系统权限拒绝。此处不据此断言全部失败已经定位或新平台已正常。

最新扩大平台联测55套249项，140失败、错误/跳过0；运行时确认Docker引擎不可用，随后CLI/WSL启动后接口仍超时，未切换模拟后端。最终真实联测、主平台新包启动/V050复核、原19接入/19回执/2凭据及21流程和旧实际数据保持均待恢复后验证。Web与Runtime已恢复200/200；Worker端口响应200/200但当前管理器无其PID，未停止该现存进程；Platform尚未恢复。无新增数据库/启动单元，无提交或推送，总目标active。

S7其余内容继续：普通停止/失败任务的版本替换与历史状态、单次样本UNKNOWN处置、来源返回前的异常分母、单位漂移细分、queueWait、阈值告警、历史修订/重放、保留与生产容量、租约/fencing/HA、生产身份及独立OIDC持续LOG验收。


## 129. 明确任务版本替换、终态历史与日志写入确定性

2026-10-05 第129节：已停止/失败的固定任务可明确 START 更高发布版本，保留不可变终态历史与原批次；运行中、降级、旧版本 RESUME 及未确认输出不能绕过门禁。新任务使用当前代数、授权和新的窗口/扫描。V051只存终态元数据，V052保留旧日志结果正文并增加明确未写入证据门禁；写入成功后回读失败保留 UNKNOWN。旧FAILED缺少该证据时不能恢复/替换，历史质量仍按原记录展示，不据FAILED推断未发生写入。沿用既有PG和四启动单元。

实际通过：1643契约；79个纯Java main/2612打印检查（版本33、连续日志47）；平台55套254项零失败/错误/跳过；Rust --locked默认41/all-features49；TypeScript/build及129相关浏览器场景。主平台新包已启动200/200，V050/V051/V052实际结构与两份按版本状态Schema复核通过；原19接入/19创建回执/2凭据、21流程、旧主机/12指标点/日志2+1及暂停诊断只读保持，无业务POST。

| 实际检查 | 结果 |
| --- | --- |
| 契约 | 1643通过，20.06秒；私有终态快照、字段/身份隔离、恢复可用性类型和缺失任务门禁 |
| 纯Java领域 | 79个main/2612打印检查；版本33、日志47，覆盖正常停止/来源失败/明确拒绝、UNKNOWN禁止升级、降级/旧版恢复拒绝、历史周期状态、容量IO前拒绝、事务回滚及成功写入后回读失败 |
| Java平台 | 55套254项，失败/错误/跳过均0；最终27个Gradle任务显式重跑，3分18秒。真实PG历史重开/隔离/回滚及V051/V052迁移→回滚→重建，真实指标库与日志库保持、HTTP固定版本与原关联回归 |
| Rust | --locked默认41/all-features49实际通过，无依赖变更 |
| Web | TypeScript/build实际通过，1.15秒；原较大chunk提示保留 |
| 浏览器协议Fixture | 最终129相关场景通过，5.7分钟；七套相关回归，包含新旧版本、原UUID、旧拒绝禁用恢复、非法恢复标记、1440/1024/390明暗布局。测试浏览器退出时Windows taskkill超时，核实路径后停止本轮专属浏览器，报告exit0；用户页签未动 |
| 平台包/主服务 | 当前bootJar实际成功，26秒；devctl重新构建并启动Platform PID26940，存活/就绪200/200。V050/V051表及V052证据列实际复核，两份主环境按版本状态符合当前Schema |
| 原环境 | 原19接入/19创建回执/2凭据、21流程，原Host周期/检查点、12指标点、原日志2条与持续2+1、两类旧诊断及暂停状态只读保持；本轮没有新增主环境流程、来源写入或业务POST |

首次并行检查遇到Windows页文件/原生内存分配失败：契约1636通过/2失败/1错误，平台未构建成功；顺序重跑和仅本轮512MiB构建堆/单worker后复验。领域首轮测试访问器名称、平台导入/泛型assertTrue，以及本机辅助脚本引用/命令引号已修正并实际重跑，没有用编写测试替代执行。

首次真实扩大联测252项仅旧大日志验收失败；只读原UUID回查确认原记录FAILED/OUTPUT_REJECTED但1000条已实际存在，无重写。连续执行器缺少样本执行器已有的“成功写入后回读失败=UNKNOWN”边界，已修复；新增真实写入、注入回读失败、禁止升级、原只读核验确认且写入次数1的验收。V052不改变旧结果体，默认没有明确未写入证据，禁止旧FAILED恢复/替换；新增不可变旧记录门禁验收。修复前失败的专属Synthetic Fixture保留，没有伪装成已修复或改其原结果。

下一扩大轮254项仅指标原点即时可见性失败；验收明确最多五次额外有界只读观察索引，原写入与runtime.verify均不重复，任务UNKNOWN不变。大日志验收在出现UNKNOWN时只进行一次显式原批次核验，不重写或重启；核验失败仍使验收失败。生产执行器没有自动重试或放宽确认。最终全量254项零失败；真实端口均保留，未回退Mock。

Platform/Runtime/Web三个管理器拥有的服务正常；Worker端口存活/就绪200/200，但管理器当前无PID，未停止或冒认该现存进程。Docker恢复后只启动原已停止日志容器，保持既有卷与所有原数据；没有更改系统页文件、辅助服务权限或清空Docker。

完整目标仍active：单次样本UNKNOWN、旧拒绝记录的完整不确定性投影/显式处置、来源异常分母、单位漂移细分、queueWait、阈值告警、同版本运行历史/重放、保留与生产容量、租约/fencing/HA及生产身份/独立OIDC持续LOG验收继续。没有新增数据库或启动单元，没有提交/推送。

最终辅助实际通过：492结构文件/6只读Tool、私有边界2829候选、53文档549本机链接，diff退出0，原CRLF提示保留。Platform26940、Runtime28896、Web25616均200/200；Worker现有端口200/200但非当前管理器拥有。无提交/推送，完整目标active。

## 130. 旧日志不确定性观察与显式终止恢复（2026-10-05）

原日志FAILED无明确未写入证据时，公开质量改为UNKNOWN、预期数量归unknown、拒绝和确认均0；原UUID、窗口、时间及存储体不改。当前/归档失败任务关联该批次时质量错误投影为OUTPUT_UNCONFIRMED。按版本日志状态保留原batch体并返回有界uncertainBatchIds；前端不提供原FAILED不支持的verification或安全重写。明确拒绝证据true仍是拒绝，不混同因代数上限禁用恢复的情况。

显式终止复用V050命令/回执，只有当前原pending及ref、代数、时间、容量、可信身份/范围匹配时受理；原体、证据false、游标及确认数量保持。重开PG服务、原UUID查询/重放、更高版本START及原质量历史检查通过。真实ClickHouse新独立Synthetic Fixture已写2条、模拟旧误分类后终止：正文仍在、原FAILED体保持、UNKNOWN质量不变、写入调用仅一次。没有新迁移或数据库/启动单元。

| 实际执行 | 结果 |
| --- | --- |
| 全部contracts pytest，最终样例更新后重跑 | 1650 passed，15.54s；零失败/错误/跳过 |
| `scripts/check_java_domain.py` | 79个main / 2617打印检查；质量专项35 |
| 原真实PG/VM/CH配置的55套平台检查，最终顺序重跑 | 255项；失败/错误/跳过均0，3m08s |
| Rust --locked默认 / all-features | 41 / 49通过，Rust源码未变 |
| TypeScript/build，最终UI修改后重跑 | 通过；原大chunk警告仍有，无依赖升级 |
| 最终三份相关Playwright及修正专项 | 67个不同场景：整组66通过/1终态文案断言失败；该断言修正后专项1通过（2.3s），未再重跑整组67。初版65整组通过单独保留 |
| 1440/1024/390明暗协议Fixture截图/根溢出 | 六组合执行；未知标签、禁用恢复、无核验与根宽度检查通过，查看1440亮色/390暗色；不是实际主环境UI截图 |
| platform bootJar | 24s，26任务2执行24up-to-date；已在主环境启动新包PID29744，200/200 |
| 真实主环境只读Schema/原数据 | 两份按版本状态及V050/V051/V052结构存在检查通过；19接入/19回执/2凭据/21流程与原主机、12指标点、样本日志2、持续日志2+1、暂停诊断保持，业务POST0 |
| 旧误分类回读 | 原独立Fixture仍原FAILED/OUTPUT_REJECTED，预期1000、实际1000日志；无写入或终止操作 |

首次平台255项有2失败：新代数负例误用构造器不接受的0，已改为合法但冲突的2；既有指标保留用例5次回读仍0，之后限定原系列的只读检查读到1点、原UNKNOWN保持、写入0。隔离测试显式允许最多30次额外限定读取，不改变应用写入/核验重试策略，不能把延迟读到点等同生产SLA。随后最终55/255通过。[存储文档](https://docs.victoriametrics.com/victoriametrics/troubleshooting/)说明近期写入可能存在可见延迟；此次未据此断言具体内部根因。

启动管理器restart未能停止旧PID，记录该失败；按原管理器PID26940、Java路径和启动时间核对后单独停止该平台实例，再由管理器start成功。没有停止其他Java进程、Worker、Docker容器或重建卷。Playwright两次整组Windows taskkill收尾失败，按各次已进入浏览器收尾的原PID及专属Chromium路径核对后停止，仅清理本轮测试浏览器；最终专项正常退出0，用户页签未操作。

67整组中新增原UUID用例实际完成查询并呈现服务器“已终止恢复”，原断言要求刷新前“已受理”文案而失败；修正为核对刷新后的终态，仍验证请求仅一次、原UUID一致、关闭详情保留未知原命令。此处没有放宽未知结果或服务器权限判断。

补充实际检查：仓库静态492份结构文件/6个只读Tool；私有资料边界2831份提交候选文件；54份文档567个本地链接；git diff --check退出0，原CRLF转换警告保留。

完整目标仍active，单次样本UNKNOWN、来源异常分母/单位漂移/排队、阈值告警、同版本历史/重放、保留容量、租约/fencing/HA与生产身份/独立OIDC持续LOG验收继续。没有提交或推送。参见[ADR-092](adr/092-legacy-log-outcome-uncertainty.md)。

## 131. 单次样本UNKNOWN的明确终止确认（2026-10-05）

仅原UNKNOWN的METRIC_SAMPLE/LOG_SAMPLE可终止；V053记录独立不可变事件，原证明和质量仍未知，原输出与检查点保持。服务仅Store/Clock，不读取来源/输出、求值或写入。命令绑定本人原UUID、固定ref、batchDigest、精确updatedAt及ack=true；可信目标/来源权限、每owner跨类型200上限、原命令幂等、唯一原批次和时间门禁实测。原核验在输出读取前拒绝，存储refinement再检查，终止在核验读取期间提交时原UNKNOWN不能被改为确认；核验先确认则终止失败。原数据仍能回读。

共享WorkflowSampleRecoverySection/Panel默认一次终止元数据读取，不自动输出核验或重写。明确勾选才提交；丢失/无效回执保留原UUID，查询404后原样重发/放弃本地确认。待确认版本/关闭/折叠门禁、session清理、隐藏取消/迟到隔离保持。独立局部样式使用现有主题，修正继承的表单列方向，三宽度/明暗确认区与终态截图保存在仓库外，宽屏亮色/窄屏暗色实际查看。

| 实际执行 | 结果 |
| --- | --- |
| 全部contracts pytest | 1675通过，零失败/错误/跳过；正常完成退出0 |
| `scripts/check_java_domain.py`，最终领域修改后 | 80个main / 2669打印检查；样本终止52 |
| 原真实PG/VM/CH配置的扩大平台检查 | 56套266项，失败/错误/跳过均0，3m34s |
| 最终解析加固后的HTTP专项 | 4项，零失败/错误/跳过，55s；扩大266中原3项重复覆盖，新增异常JSON/日期1项。没有宣称最终267整组同时运行 |
| Rust --locked 默认 / all-features | 41 / 49通过，无Rust源码变化 |
| TypeScript/build，最终局部样式后 | 通过；既有大chunk警告仍有，无依赖升级 |
| 三份相关Playwright | 整组69通过；三宽度/明暗及原UUID/404/证据/会话/门禁实际覆盖，无共享Shell修改 |
| 真实隔离Synthetic Fixture | 原样本点值和2条日志终止后仍在；PG重开原UNKNOWN/独立事件保持；明确拒绝不能冒充未知，无再次写入 |
| platform bootJar | 26s，26任务2执行24up-to-date；管理器再启动新版30856，200/200 |
| 主环境只读复核 | 两份原样本和两份按版本状态符合当前Schema，V053存在；19接入/19回执/2凭据/21流程、主机/12指标点、样本日志2/持续日志2+1、检查点及暂停诊断保持，业务POST0 |
| 旧误分类实际回读 | 原FAILED/OUTPUT_REJECTED体保持；预期1000/实际1000日志，写入0 |

首轮266前的262项有3个新HTTP错误状态失败，原因是新控制器漏接统一错误处理，修复后扩大检查通过。下一轮266项有两个继承执行的指标实际写入验收失败：真实适配器成功POST后即时回读无法确认而抛出unknown=true。验收只接受该不确定异常；确定拒绝仍失败，原写入仅一次，并在终止后至多30次限定原系列的500ms间隔只读观察，实际匹配原点值/摘要后通过。应用未增加自动重试或放宽确认，不能将可见延迟等同生产SLA。

初版浏览器69中12个新断言要求终止后核验按钮disabled，但产品会移除已终止的原核验入口；实际57通过/12失败，修正为核验入口不存在后整组69通过。之后局部布局及checkbox行方向调整，最终相关整组再次验证；没有修改服务器不确定性判断。

启动管理器restart停止进程树失败，按原管理器PID29744、Java路径及2026-10-05 17:00:03启动时间核对后仅停止该旧平台实例，再start新包30856成功。Runtime/Web/现存Worker及Docker容器/卷保留；未用WMI广泛查杀或停止其他服务。测试浏览器及自建预览正常退出，未操作用户页签。

完整目标仍active：来源异常分母、单位漂移、queueWait、阈值告警、同版本历史/重放、保留容量、租约/fencing/HA与生产身份/独立OIDC持续LOG验收继续。没有提交或推送，参见[ADR-093](adr/093-explicit-sample-output-closure.md)。

最终辅助实际通过：497份结构文件/6只读Tool、私有资料边界2854份提交候选文件、57份文档596个本地链接、git diff --check退出0。既有CRLF提示保留；没有提交或推送。


## 132. 来源端口实测分母与闭合元数据漂移（2026-10-05）

持续METRIC/LOG和固定主机新分页围绕一次来源端口调用记录sourceRead，attempts=1、completed+failed=1；完整返回数量与转换/输出处置分开。失败received为null，不补0、不推算拒绝记录。HOST/METRIC/LOG上限分别5/600/1000，转换分母已知时必须相等，主机私有见证数量也一致。来源键、值类型、单位和对象变化在元数据前后校验中区分闭合原因，原SOURCE_CHANGED拒绝保持；原始异常、端点、密钥和原文不持久化。主机不能声称指标键/类型/单位变化。来源成功但转换拒绝的0/1与PARTIAL未知量单独展示。

沿用V049有界JSON，容量IO前检查及当前代数/身份/版本提交检查保持。旧13字段存储兼容，未测量公开wire省略sourceRead，原体不回填或改写。元数据读取只有既有GET与UUID查询，当前范围/父版本授权保持。Web新增WorkflowSourceReadDetail纯展示，闭合时间/计数/原因及父观察校验，旧记录显示“—”；默认一次读取、隐藏取消、403清理及迟到隔离保持。没有入队观测点，queueWaitMillis继续null，窗口迟到不作为排队耗时。

| 实际执行 | 结果 |
| --- | --- |
| 全部contracts pytest | 最终1708通过，退出0；collect-only另确认1708，并非用收集代替执行 |
| scripts/check_java_domain.py | 81个main / 2744打印检查；新来源端口/协议Fixture75检查 |
| 扩大真实PG/VM/CH平台检查 | 56套268项，失败/错误/跳过均0，4m6s |
| 最终可信HTTP专项 | 3项零失败/错误/跳过，50s；新增正向来源/旧wire及原2项复验。没有宣称最终269整组同时运行 |
| Rust --locked默认 / all-features | 41 / 49通过，无Rust源码变更 |
| TypeScript/build | 最终通过，1.12s构建；既有大chunk警告保留，无依赖升级 |
| 六个相关Playwright文件 | 整组132通过，7.8m，三宽度/明暗、旧记录、新分母/转换部分拒绝、伪造/时间/计数/异常配对拒绝及会话/原UUID门禁覆盖；截图在仓库外，宽屏亮色和窄屏暗色已查看 |
| 新统计持久化/HTTP | 实际PG写入、重开、原UUID读取、缺失分母与旧13字段兼容；损坏元数据失败。正向HTTP使用完整Synthetic Fixture父接入/连接/发现链，不读取来源或写输出 |
| 平台bootJar / 本机启动 | 最终20s，26任务2执行24up-to-date；新版PID10032运行，健康/就绪200/200 |
| 主环境只读复核 | 19接入/19创建回执/2凭据/21流程、5条旧主机观察与检查点、12指标点、样本日志2/持续日志2+1、暂停日志诊断保持；两份样本与两份按版本状态Schema通过，已有迁移结构存在；业务POST0 |
| 旧误分类Fixture | 原FAILED/OUTPUT_REJECTED体保持，预期1000/实际1000日志，只读写入0 |

初次新契约3项失败来自把LOG元数据变化套在HOST样例上；改正Fixture并增加3个HOST反向约束后最终1708整组通过，未放宽HOST门禁。新增正向HTTP最初3轮失败来自手工样本/日志诊断不匹配及固定来源Fixture缺少完整父记录或PENDING→COMPLETED状态链；完整建模夹具后最终3项通过。服务器范围和存储状态门禁保持。协议变化仅使用显式Synthetic Fixture，不声称实际来源发生单位漂移。

打包首次PowerShell未引用JVM参数，错误识别为Gradle任务而退出1；改为固定参数进程调用后20s通过。已知管理器进程树停止失败没有重试广泛查杀；核对原管理器PID30856、Java路径及2026-10-05 17:59:24启动时间后只停止该平台，再由管理器启动10032。Runtime/Web/非管理器Worker及Docker容器与卷保持。

浏览器测试主体完成132项后Windows关闭等待超时；根据日志gracefully close start、该次启动的PID26568、精确自有Chromium路径与18:31:27启动时间，仅结束这个测试浏览器。Playwright随后完成临时目录清理，最终整组退出0；自建4179预览退出，未操作用户页签。

完整目标保持active：实际queueWait观测、阈值告警、同版本历史/重放、保留/容量、租约fencing/HA、生产身份与独立OIDC持续LOG验收尚未完成。没有迁移、数据库、启动单元、依赖升级、提交或推送。见[ADR-094](adr/094-measured-source-port-diagnostics.md)、[来源统计契约](../contracts/workflow-source-diagnostics.md)与[运行说明](runbooks/workflow-source-diagnostics.md)。

最终辅助实际通过：501份结构文件/6只读Tool，私有资料边界2863份提交候选文件，60份文档/623个本地链接；git diff --check退出0，既有CRLF提示保留。未提交或推送。


## 133. 本地FIFO的实测调度区间（2026-10-05）

持续METRIC/LOG及固定连接HOST本人轮询取得既有预算后，符合运行状态/授权类型的快照进入容量21的本地FIFO，入队随机UUID和规范UTC、出队开始时间实际记录。queueWaitMillis为实际区间的整数毫秒，不足1ms为已测0；未测量为null。反向时间、真实区间超过一小时、标量失配/缺少证据或开始晚于父观察均拒绝。一个出队任务的补采/当前窗口共享dispatch UUID，不能加成两次排队。本地等待不包含预算之前、前次轮询、全局积压或来源IO，不声称生产SLA。

排队任务快照不能替代身份，执行器IO前重查当前代数/状态/版本与可信授权。STOP和授权过期发生在等待期间时拒绝来源读取，原输出和检查点不变。沿用V049有界JSON、每本人观察容量及原输出门禁；公开旧13字段、来源14字段及同时来源/调度15字段兼容，历史不回填。纯展示WorkflowDispatchDetail沿用原容器请求与取消/迟到隔离，无新请求或公共Shell改动。

| 实际执行 | 结果 |
| --- | --- |
| 全部contracts pytest，最终UTC负例后 | 1735通过，正常退出0；执行日志逐项完成，不以收集代替执行 |
| scripts/check_java_domain.py | 82个main / 2776打印检查；新调度32，包括FIFO/上限/异常时间、同次双窗口、停止及授权到期 |
| 扩大真实PG/VM/CH平台检查 | 最终57套271项，失败/错误/跳过均0，3m50s；包含本节2个PG用例和原3个可信诊断HTTP用例 |
| 新调度持久化/HTTP | 真实PG中两个Synthetic Fixture任务顺序执行，前任务明确等待40ms，后任务实测等待至少30ms；重开与原UUID回读一致，损坏标量拒绝，另一tenant/本人拒绝。来源为空Fixture，不执行输出。可信HTTP实际GET并存15/14/13字段，无来源/输出IO |
| Rust --locked默认 / all-features | 41 / 49通过，无Rust源码改动 |
| TypeScript/build | 最终通过，构建1.16s；既有大chunk警告保留，无依赖升级 |
| 六份相关Playwright | 整组152通过，5.8m，包含20个新场景；1440/1024/390明暗、旧缺失/实测零、275ms与三字段展示、非规范时间/UUID/额外字段/成对标量/时间失配拒绝及一次默认请求。截图仅在本机仓库外，宽屏亮色与窄屏暗色已查看 |
| platform bootJar / 本机启动 | 19s，26任务2执行24up-to-date；管理器启动新版31404，存活/就绪200/200 |
| 主环境只读复核 | 19接入/19创建回执/2凭据/21流程、5条旧主机观察/检查点、12指标点、样本日志2及持续日志2+1、暂停日志诊断保持；两份样本与两份按版本状态Schema及已有迁移结构通过，业务POST0 |
| 旧误分类Fixture实际回读 | 原FAILED/OUTPUT_REJECTED体保持，预期1000/实际1000日志，读取写入0 |

测试浏览器152项主体完成后，Windows进程树收尾等待卡住。日志已进入gracefully close/force kill；核对本轮专属Chromium的PID19808、精确路径和19:36:40启动时间后仅结束这个测试实例，随后临时目录清理完成、整体退出0，自建4179预览关闭。用户页签未操作。

沿用已知管理器进程树停止失败的处理边界，核对原管理器PID10032、Java路径及2026-10-05 18:58:58启动时间，仅停止旧平台并由管理器启动新包31404。Runtime/Web/非管理器Worker及Docker容器与卷保持；未广泛查杀、增加服务或重建卷。

当前PG本人任务查询本身有21条上限，本地FIFO只覆盖该次接收快照；没有新增跨本人公平性、轮询完整性、HA租约/fencing、自动保留或生产容量承诺。阈值、同版本历史/重放和生产身份/独立OIDC持续LOG验收继续，完整目标active，无提交或推送。见[ADR-095](adr/095-measured-local-workflow-dispatch.md)、[契约](../contracts/workflow-dispatch-diagnostics.md)及[操作说明](runbooks/workflow-dispatch-diagnostics.md)。

最终辅助实际通过：504份结构文件/6只读Tool，私有资料边界2874份提交候选文件，63份文档/650个本地链接，git diff --check退出0。既有CRLF提示保留；没有提交或推送。

## 134 · 显式固定版本质量阈值 · 2026-10-06

在已有质量/诊断报告上增加显式配置与元数据评估。四条规则分别使用实测来源失败、已证实输出拒绝、去重后的本地排队峰值及当前精确版本任务状态。默认未启用，统计区间60至86,400秒、最近20条有界证据；缺失、截断及相同时间混合结果不能显示为未达阈值。连续次数达到阈值即停止，不代表完整失败总量。未知旧FAILED不计输出拒绝，归档任务不冒充当前任务。

纯领域与应用服务没有来源/输出/通知/动作端口，已有PG新增V054配置及不可变回执两表。本人/tenant/版本与固定对象范围由服务器复核，CAS及原UUID摘要控制并发/重复；每本人200回执，非空规则最多199，最后容量保留给停用。配置、质量、诊断同事务和asOf，时间截断微秒，损坏不回退空成功。四启动单元与既有依赖保持。

| 实际执行 | 结果 |
| --- | --- |
| 最终全部contracts pytest | 1774通过，正常退出0；39个新增阈值结构/负例检查 |
| scripts/check_java_domain.py | 83个main / 2815打印检查，阈值39；包含无业务IO、本人/tenant/权限、CAS/原回执、失败/缺失/顺序歧义、去重/峰值/截断和停用保留容量 |
| 扩大真实PG/VM/CH平台检查 | 最终58套273项，失败/错误/跳过均0，3m49s |
| 新阈值PG/HTTP | 隔离tenant实际配置、同UUID原内容/冲突、重开及原回执、当前三规则触发、停用保留旧回执、损坏拒绝；独立随机schema前进→回退→前进，只删除自己创建的schema。可信HTTP实测POST/GET、原回执、250ms触发且保留2份缺失、非法/私有字段/身份覆盖拒绝，无来源/输出IO；来源与失败证据为明确Synthetic Fixture |
| Rust --locked默认 / all-features | 41 / 49通过，无Rust源码或依赖升级 |
| 最终TypeScript/build | 通过，最终构建1.09s；既有大chunk提示保留 |
| 七份相关Playwright | 表单隔离后整组177通过，6.2m；质量/诊断、指标、日志、主机运行与周期及新阈值25场景，三宽度/明暗、一次读取、CAS、丢失/非法原回执、404原样重发、未保存折叠门禁、403全局清理及迟到响应隔离 |
| 最后局部结果表样式收紧 | 阈值专项25全部通过，31.9s，正常自然退出0；增加实际规则行≤100px、复选框≤18px、结果行≤90px和表单footer正常文档流检查。最后仅局部CSS与尺寸断言变化，未再运行整组177。最终宽屏亮色/窄屏暗色截图已查看，全部截图仓库外 |
| platform bootJar / 管理器启动 | 24s，26任务2执行24up-to-date；平台29760、Runtime11924、Web12756实际存活/就绪200/200。Worker端口200/200但非当前管理器所有，未操作 |
| 主环境只读及契约复核 | 原19接入/19创建回执/2凭据/21流程、主机5条观察/检查点、12指标点、样本日志2/持续日志2+1和暂停日志诊断保持。两份原样本/两份按版本任务及四份未配置阈值状态Schema通过，V054两表存在，业务POST0，原规则未启用 |
| 旧误分类Fixture | 原FAILED/OUTPUT_REJECTED体及预期1000保持，实际1000日志只读存在，无写入 |

初轮新增表未接入启动登记，造成2个实际失败；补登记后资源打包遗漏导致上下文失败。已同步补齐同一SQL资源及迁移调用，最终扩大58/273整组通过，不能用编写SQL或测试收集替代部署。浏览器初轮403断言错误地在已清理的私有页面内部等待，改为验证全局会话清理并完成整组复验。截图发现全局label/checkbox/footer/details使表单与依据行过高，已按局部组件隔离并实测尺寸。

扩大浏览器主体完成后，Windows清理进程树再次超时。日志已进入gracefully close/force kill；核对本轮专属Chromium PID24404、精确路径及02:10:33.0409530启动时间后，仅结束该测试实例，随后整组正常退出0、临时目录及自建4179预览关闭。最后25项专项自然退出，不需结束进程。用户浏览器与其他服务未操作；没有广泛查杀或重建卷。

本节仅配置并评估阈值，不推送通知、不执行动作、不签发后台身份，不声明全局健康或生产SLO。检查点停滞、预算耗尽与租约冲突尚需独立证据；同版本历史/重放、保留/生产容量、跨本人公平调度与轮询完整性、多副本租约/fencing/HA及生产身份/独立OIDC持续LOG验收继续。完整目标active，无提交或推送。见[ADR-096](adr/096-configurable-workflow-quality-thresholds.md)、[契约](../contracts/workflow-quality-alerts.md)及[操作说明](runbooks/workflow-quality-alerts.md)。

最终辅助实际通过：514份结构文件/6只读Tool，私有资料边界2901份提交候选文件，66份文档/679个本地链接，git diff --check退出0。既有CRLF提示保留；本机产品进度已更新，完整目标active，没有提交或推送。


## 135 · 固定版本检查历史与稳定分页 · 2026-10-06

运行记录页增加固定版本已保存的来源/转换检查，预览与版本测试单独保留。每页20条、本人原200条容量保持，完成时间降序/同时间UUID升序。第一页固定asOf快照、插入水位和数量，回填较早时间的新记录也不进入旧快照。AES-256-GCM游标绑定可信tenant/本人/ID/revision/digest，10分钟有效；序号与私有选择不公开。V055新增既有诊断表identity序号及唯一约束，原正文/时间保持。

纯Java应用服务每页重新检查固定来源/目标/版本及逐实体见证，包括前页锚点和下一条检测记录；没有来源、输出、通知或动作端口。失效/数量改变409，损坏503，其他范围令牌404，无静默空成功或重试。生产缺少/显式无效密钥配置503；仅未配置引用的开发模式生成进程随机密钥，重启需刷新。相同部署密钥支持适配器重开读取，不声明HA。

Web请求容器默认活动就绪读目录/版本各一次；目录之外的固定直链明确GET原版本，缺失不选最新替代。原13/14/15字段观察共用节点明细组件，连续offset/数量/时间/父版本/排序及跨页UUID均校验；未知保留“—”，转换通过与输出确认分开。视图/主题/尺寸不重读，更多/刷新显式，403清私有数据后不触发自动GET，明确凭据变更才重新读取。

| 实际执行 | 结果 |
| --- | --- |
| 全部contracts pytest | 1807通过，退出0；新增历史33项结构/负例，嵌套引用修正后通过 |
| scripts/check_java_domain.py | 84个main / 2862打印检查，历史47；包括45条三页、同时间UUID、回填排除、过期/时钟倒退、200条十页、容量拒绝、当前本人/tenant/版本/权限与逐实体锚点复核，无新增业务IO |
| 扩大真实平台检查 | 最终61套278项，失败/错误/跳过均0，3m46s；真实PG/VM/CH与既有可信身份/来源/输出回归 |
| 新历史PG/HTTP及加密 | 实际隔离tenant保存45条元数据并取20/20/5，插入回填记录后旧快照保持，重开/重复读、原正文/时间、范围拒绝和损坏/删除拒绝；随机隔离schema前进→回退→前进保留原数据。可信HTTP实际分页及身份/闭合查询/只读405，无来源或输出IO。JCA随机nonce、同钥重开、篡改/其他范围/轮换拒绝及无效配置不回退；证据为明确Synthetic Fixture，不声称真实来源负载 |
| Rust --locked默认 / all-features | 41 / 49通过，无Rust源码或依赖升级 |
| 最终TypeScript/build | 通过，最终构建0.926秒；既有大chunk提示保留 |
| 六份相关Playwright | 最终整组194通过，正常自然退出0；历史29、预览记录、诊断/质量/阈值及工作流画布/抽屉/原命令。1440/1024/390明暗、默认一次/失效无重试、稳定分页/拒绝合并/明确旧版本、身份取消、键盘节点详情和紧凑行高；新增实际按钮border/颜色断言。宽屏亮色和窄屏暗色最终截图已查看，全部截图仓库外 |
| 新按钮视觉专项 | 共用Button修复旧样式覆盖后专项1通过，再运行最终194整组；未用编写断言冒充通过 |
| platform bootJar / 管理器启动 | 25s，26任务2执行24up-to-date；核对原29760精确Java路径及02:05:08.4413506启动时间后仅停止旧平台，管理器启动22836。平台/Runtime11924/Web12756实际200/200；非管理器Worker端口200/200未操作 |
| 主环境只读 / Schema | 19接入/19创建回执/2凭据/21流程、主机5条原观察及检查点、12实际指标点、样本日志2/持续日志2+1及暂停诊断保持。四份真实历史页与原UUID明细一致并匹配Schema，V055identity/唯一约束存在；两份原样本/两份按版本状态及四份未配置阈值Schema保持。主环境业务POST0 |
| 旧误分类Fixture | 原FAILED/OUTPUT_REJECTED体及预期1000保持，实际1000日志只读存在，无写入 |

初轮33契约中8项因嵌套Schema绝对ID引用失败，改用既有本机相对引用后33及最终1807通过。领域容量负例最初把Store的IllegalStateException误写成应用层CAPACITY，已修正并完成47实际检查。平台首轮278中1项游标断言错误：格式非法400、合法形状但无法解密404；修正后最终278全通过。

浏览器初轮文案/空版本重新进入及403清理断言不准确；随后确实捕获凭据仍就绪时forbidden引起默认读取循环，已限定只在明确credentials变化触发新默认请求，最终检查至少1秒仍只一次。截图又发现普通按钮被旧样式覆盖，实际CSS断言失败后改用共用Button及组件局部样式；专项1及最终194整组通过。临时样式诊断已移除，最终浏览器自然退出，不需结束任何测试进程；用户页签未操作。

历史仅覆盖已记录检查，不补回未保存执行，也不是输出确认或生产留存。受控重放、阈值通知与独立检查点/预算/租约证据、保留/生产容量、公平调度/轮询完整性、多副本租约/fencing/HA、生产身份及独立OIDC持续LOG验收继续；完整目标active，无提交或推送。见[ADR-097](adr/097-scoped-recorded-workflow-history.md)、[契约](../contracts/workflow-history.md)及[操作说明](runbooks/workflow-history.md)。

最终辅助实际通过：518份结构文件/6只读Tool，私有资料边界2927份提交候选，69份相关文档/709个本地链接，git diff --check退出0。既有CRLF提示保留；本机产品进度和证据已更新，完整目标active，无提交或推送。


## 136 · 有界历史指标投影重建 · 2026-10-06

固定发布指标增加“检查重放范围→确认重放”。第一步持久PREPARING后读取过去24小时内已结束至少10秒的完整60秒窗，最多60条，运行原固定转换并封存输入/输出摘要、计数、标签与时间戳，READY有效10分钟；没有输出。来源端显式收窄原600点预算，超限/不完整拒绝，不返回截断成功。原值不存PG。

第二步绑定原选择与两个摘要，持久唯一PENDING执行后重新读取同一窗。完整证明变化拒绝输出，输出前重查当前可信workflow.replay、原source.sync及固定来源/连接/工作流/映射/指标/实体范围。默认身份未增加权限。使用原共享有界IO预算与真实时序适配器，写入collection_mode=REPLAY_60S和replay_id独立系列；原持续系列、任务、授权与checkpoint保持。没有新增数据库、启动单元、执行线程、动态执行器或通知/动作端口。

原UUID重复只返回原记录。明确输出拒绝才FAILED，潜在写入未确认保持UNKNOWN；显式核验只读原系列/时间戳与摘要，不重读来源或写入。PENDING受理不足180秒不提前读空，超过也不能推断未写入；此门禁不是分布式租约/fencing。空完整批次确认0条且不调用输出。V056选择/回执两表、父版本外键、每个选择唯一执行、租户事务锁与refinement保护保持；存在任何选择/执行记录时回退拒绝。本人200选择与最近20条列表不是生产留存。

Web复用请求容器与纯Panel，默认折叠，展开只读元数据一次。完整指标键可跳转既有定义；“范围已固定”与执行状态分列，避免将封存范围误画为执行确认。丢失/非法回执保留原UUID和内容，只有明确404才可原样重发；UNKNOWN仅显式核验。忙碌/本地待确认阻止折叠、切换版本及关闭页签；身份取消/清理与迟到隔离保持。局部组件使用共用Button与明暗/三宽度布局，不改公共Shell。

| 实际执行 | 结果 |
| --- | --- |
| 最终全部contracts pytest | 1844通过，53.45s，退出0；新闭合计划/证明/执行/回执/分页/原执行结构与负例实际执行 |
| scripts/check_java_domain.py | 85个main / 2901打印检查；重放39，包括权限/本人/版本、60与61条、输入变化、共享预算/到期、空批次、原UUID只读、未知回读及正在执行的PENDING不提前核验 |
| 扩大真实平台检查 | 最终63套291项，失败/错误/跳过均0，4m15s；包含真实PG/VM/CH和原身份、来源、输出回归 |
| 新重放真实PG/VM | 隔离tenant实际写入独立投影、精确标签/值回读与原回执重开；丢失确认保持UNKNOWN后显式核验，来源变化不写、损坏/范围拒绝、并发同UUID一次受理/尝试。随机隔离schema空表前进→回退→前进及非空拒绝已验证。来源明确Synthetic Fixture，不声称实际供应方重放负载；真实计划/确认回执两份Schema通过 |
| 可信HTTP边界 / 来源预算 | 实际401/403、无独立权限无业务IO、身份/额外字段/重复JSON/尾部内容/非法UUID与查询拒绝。正向重放HTTP来源链未验收；真实PG/VM闭环与浏览器Fixture各自说明。来源适配器新增60条哨兵/预算不得扩大两项，原600条语义回归保持 |
| Rust --locked默认 / all-features | 41 / 49通过，无Rust源码或依赖升级 |
| 最终TypeScript/build | 通过，最终构建1.19秒；最终报告前补存构建日志，既有大chunk提示保留 |
| 四份相关Playwright | 最终整组74通过，1.3m，正常自然退出0；重放20及历史/运行/持续指标回归。1440/1024/390明暗、首次元数据读取、先检查后确认、完整指标跳转、伪造/超限/坏空摘要拒绝、原UUID/404原样重发、未知核验、关闭门禁与身份清理。最终宽屏亮色/窄屏暗色截图已查看，全部截图仓库外；业务响应为明确Fixture |
| platform bootJar / 本机启动 | 20s，26任务2执行24up-to-date；核对原22836精确Java路径与03:15:43.4042198启动时间后仅停止旧平台，管理器启动29232。平台/Runtime11924/Web12756存活就绪200/200；非管理器Worker端口200/200未操作 |
| 主环境只读与Schema | 原19接入/19创建回执/2凭据/21版本、5主机观察与任务/检查点、12指标点、样本日志2及持续日志2+1保持；两份原样本、两份按版本状态、四份未配置阈值和四历史页Schema通过。V056两表及唯一执行结构存在，主环境重放计划0，默认权限GET仍403，业务POST0 |
| 旧误分类Fixture | 原FAILED/OUTPUT_REJECTED及预期1000保持，实际1000日志只读存在，无写入 |

平台初轮编译遇到Java复合var声明，修正后编译通过。第一次真实时序测试把即时确认写死为CONFIRMED，实际返回UNKNOWN；已保留原状态并用显式、有界、测试专用的可见性回读等待最终确认，不重试来源/写入。最终291项整组通过，不能把等待视为产品隐藏重试。

浏览器最初丢失执行断言误匹配已有确认文本，已改为等待实际原执行GET；空批次Fixture错误沿用非空摘要，已使用规范空摘要，并新增坏空摘要拒绝。完整指标链接初次参数不符，改用既有定义路由函数。最终74项在范围状态文案、链接、严格证明和局部样式变更后重新运行，自建预览与测试浏览器自然退出，用户页签未操作。

本节仅重建独立指标投影，历史来源重新读取不保证原始快照已留存，也不提供持续系列覆盖或exactly-once。日志/资产重放、覆盖修复与未确认重放处置、阈值通知及独立检查点/预算/租约证据、保留/生产容量、公平调度/轮询完整性、多副本租约/fencing/HA、生产身份与独立OIDC持续LOG仍需完成。完整目标active，无提交或推送。见[ADR-098](adr/098-bounded-historical-metric-reconstruction.md)、[契约](../contracts/workflow-metric-replay.md)与[操作说明](runbooks/workflow-metric-replay.md)。

最终辅助实际通过：531份结构文件/6只读Tool，私有资料边界2957份提交候选文件，72份相关文档/736个本地链接，git diff --check退出0。既有CRLF提示保留；本机产品进度与证据已更新，完整目标active，无提交或推送。


## 137 · 有界历史日志投影重建与显式正文查看 · 2026-10-06

固定发布日志增加“检查重放范围→确认重放”。完整历史窗为过去24小时内60秒、结束至少10秒、最多1,000条；先存PREPARING再读取来源与原固定转换，READY保存输入/输出摘要、计数、原始纳秒位置与接受索引，10分钟有效。确认先持久唯一PENDING，重读同一完整窗并匹配全部证明后才输出；来源变化拒绝，不返回截断成功或存正文到PG。

可信workflow.replay与原source.sync、固定来源/连接/发现/实体门禁并行。日志读写使用原log资源workflow.{id}，不能把工作流范围当日志授权。每次元数据/正文/核验需当前读范围，范围检查和执行另需写范围；默认身份不增加权限。沿用四启动单元、Java完整窗口/转换、共享有界IO和现有ClickHouse端口，无新增数据库、线程、执行框架或动态代码。

已有PG的V057两表、父版本外键、每个范围唯一执行和租户事务/refinement保存证明。既有日志库V003独立workflow_log_replays表，由构建时固定工厂选择；应用账号只增加该表SELECT/INSERT，DDL由既有私有管理员完成。原持续表、样本、任务、授权及checkpoint保持。整窗一次输出后精确回读；写入可能发生或写后读取失败保持UNKNOWN，明确拒绝才FAILED。原UUID重复只读，显式核验没有来源/写入调用，早期PENDING不提前核验；180秒保护不是分布式租约。空完整范围确认0条且不调用输出。

Web指标/日志共用纯WorkflowReplayPanel，API和请求容器各自闭合。首次展开只读元数据一次，正文由“查看重放日志”明确读取，复用文本组件及50条分页；位置/索引与原摘要、父范围复核，当前complete不替代原回执。身份/父范围变化清除正文与游标；原UUID丢失、明确404原样重发、关闭/版本待确认门禁保持。动作自然宽度并在窄屏换行，局部样式不改公共Shell。

| 实际执行 | 结果 |
| --- | --- |
| 最终全量contracts pytest | 1884通过，退出0；pytest重复quiet使摘要省略，实际进度点计数1884。新7类Schema/样例、闭合字段、1000边界、128/129字符tenant、纳秒位置和数据页负例已执行 |
| scripts/check_java_domain.py | 86个main / 2944打印检查；日志重放43，包括正确log范围与工作流范围不能代替、输入变化、1000/1001、共享预算/到期、原UUID只读、UNKNOWN显式核验、早期PENDING不读输出和50条分页 |
| 扩大真实平台检查 | 最终65套314项，失败/错误/跳过均0，4m5s；实际PG/VM/CH及原身份、来源、输出、历史、指标重放回归 |
| 新重放实际PG/CH | 隔离tenant实际1,000条独立投影、精确摘要/正文与原持续表隔离、50条分页、PG证明无正文、重开原UUID不重读/重写；丢失写确认UNKNOWN后显式核验、输入变化/损坏范围不写、并发原请求一次准入、随机隔离schema空表前进→回退→前进及非空回退拒绝保持。计划/确认回执两份实际Schema通过；来源明确Synthetic Fixture |
| 可信HTTP边界 | 实际401/403、缺独立权限无业务IO、身份/额外字段/重复JSON/尾部内容/UUID和查询拒绝；正向实际供应方重放HTTP来源链未验收，存储闭环与浏览器Fixture分别说明 |
| Rust --locked默认 / all-features | 41 / 49通过，无Rust源码或依赖升级；本节未另跑fmt，不将上一节检查当本节执行 |
| 最终TypeScript/build | 通过，1.15秒；既有大chunk提示保留 |
| 五份相关Playwright | 最终108通过，1.8m，自然退出0；日志重放26和原指标重放/历史/持续日志/运行回归。三宽度明暗、明确正文读取、分页、文本脚本不执行、坏范围/摘要/tenant/纳秒拒绝、原UUID/404恢复、UNKNOWN和关闭/身份清理。最终宽屏亮色与窄屏暗色截图已查看；截图仅本机，业务响应明确Fixture |
| platform bootJar / 服务恢复 | 23秒，26任务2执行24up-to-date。实际发现原平台29232/Runtime11924/Web12756已不存在且管理器显示stopped，本节未停止旧PID；现有管理器恢复平台17552/Runtime6260/Web11080，实际存活/就绪200/200。8081非管理器Worker端口200/200，进程11012未操作；原容器保持运行 |
| 主环境只读与Schema | 原19接入/19创建回执/2凭据/21版本、5主机观察和任务/检查点、12指标点、样本日志2及持续2+1保持；两份原样本、两份按版本状态、四份未配置阈值和四历史页Schema通过。V057两表/唯一执行及独立日志表实际存在，主环境指标/日志重放计划与重放正文均0，默认权限GET均403，业务POST0 |
| 旧误分类Fixture | 原FAILED/OUTPUT_REJECTED、预期1000和实际1000日志只读保持，无写入 |

实现自查修正日志授权使用原log资源、写后读取错误必须UNKNOWN、授权前不能查询输出端口，以及TenantId现有128字符边界。初轮Java编译和TS类型问题修正后，最终上述领域、契约、314项平台与build实际通过。日志表首次使用受限应用账号DDL被拒绝，之后使用既有私有管理员明确迁移及最小表读写授权；没有扩大业务身份权限或修改原表。

浏览器初轮105通过/1失败，定位器把“查看重放”和“查看重放日志”匹配到一起，已改精确匹配；截图发现结果区按钮被拉满宽，已改局部flex并新增实际宽度断言。最后调整与tenant负例后108项整组通过，测试浏览器和预览正常退出，用户页签未操作。Python/Java fixture不作隐藏执行后端，正文、凭据和截图不进入仓库。

本节仅重建独立日志投影；历史来源重新读取不保证原始快照已留存，200范围/最近20条不是生产留存。资产重放、覆盖修复与未确认重放处置、阈值通知及独立检查点/预算/租约证据、保留/生产容量、公平调度/轮询完整性、多副本租约/fencing/HA、生产身份和独立OIDC持续LOG、实际供应方正向重放HTTP仍需完成。完整目标active，无提交或推送。见[ADR-099](adr/099-bounded-historical-log-reconstruction.md)、[契约](../contracts/workflow-log-replay.md)与[操作说明](runbooks/workflow-log-replay.md)。

最终辅助实际通过：545份结构文件/6只读Tool，私有资料边界2990份提交候选文件，75份相关文档/777个本地链接，git diff --check退出0。既有CRLF提示保留；本机产品进度与证据已更新，完整目标active，无提交或推送。


## 138 · 实际来源与可信HTTP的指标/日志重放验收 · 2026-10-06

补齐已有指标/日志重放的本地实际来源HTTP链：随机端口、随机开发Token与独立测试tenant，现有Zabbix 7.0.27/PG/VictoriaMetrics/ClickHouse。来源记录明确Synthetic Fixture，脚本创建专属组/主机/两个trapper item并核对标签，写60条CPU百分比和60条日志后精确读回。修改前持久attempted，来源响应丢失不自动重发；不会操作旧主机/原item或改变日常身份权限。

真实链经过凭据加密保存→注册连接→连接测试→分页发现→精确item/host选择→草稿→来源只读预览→固定发布→范围封存→明确执行→精确存储回读。全局旧来源端口closed，实际来源只走已注册/受控连接，不使用Fixture fallback。CPU60条精确标准化，日志60条正文/空上下文与纳秒位置保持，明确分页50/10。没有将真实预览当历史输出，发布不创建持续任务。

第二个日志范围封存后增加一条本人Fixture来源记录。明确、有界的测试读取先证明61条已可见，不重发push；执行返回FAILED/SOURCE_WINDOW_CHANGED且新投影为空。原范围、UUID、60条已确认投影和两个工作流的原任务状态保持。来源/输出丢失的产品语义仍沿用UNKNOWN，不以此确定输入拒绝推断所有失败均未写入。

新增脚本和实际来源HTTP测试只用于开发验收；应用代码、业务Schema、迁移、依赖、默认权限和四启动单元保持。私有输入指针、会话令牌和证据不进仓库；脚本以自身模块路径定位checkout，Java测试从真实Git根检查证据路径，拒绝仓库内文件且限制64KB。最后完善路径检查后再次用新私有目录实际运行专项，不重复旧Fixture修改。

| 实际执行 | 结果 |
| --- | --- |
| 完整contracts pytest | 1884通过，56.24s，退出0 |
| scripts/check_java_domain.py | 86个main / 2944打印检查；指标重放39、日志重放43保持，无领域源码变化 |
| 扩大真实平台回归 | 66套315项，失败/错误/跳过均0，4m52s；真实PG/VM/CH、身份/来源/旧输出/历史/两类重放及实际来源HTTP链 |
| 最终实际来源专项 | 私有路径完善后的新目录再运行1套1项，零失败/错误/跳过，55s；最终整组未再重复，整组315与最后专项1分别记录 |
| 实际来源/HTTP产物Schema | final与final2两次9份，共18份实际计划/初始及确认回执/50与10条日志页/来源变化回执通过现有闭合Schema；完整链证明与源记录仍明确Synthetic Fixture |
| Rust --locked默认 / all-features | 41 / 49通过；未另跑fmt，无Rust或依赖变化 |
| TypeScript/build / Node脚本语法 | TypeScript/build通过2.67秒，既有大chunk提示保持；node --check退出0，本地脚本实际三目录分别执行，不声称生产连接器负载 |
| 浏览器 | 本轮未再运行；没有Web源码变化，§137的108项与截图保留为当时证据，不列作本轮执行 |
| 选定日志凭据扫描 | 7个实际测试/平台/Runtime/Web日志，对7个当前配置与验收凭据做精确扫描，0命中；只证明选定日志/确切值，不是全局日志或客户原文合规认证 |
| 主环境及原Schema | 平台17552/Runtime6260/Web11080实际存活就绪200/200，8081非管理器端口200/200且未操作；原19实例/19创建回执/2凭据/21版本、5主机观察和检查点、12指标点、样本日志2/持续2+1保持。两份原样本、两份版本状态、四阈值和四历史页Schema通过 |
| 主环境权限/历史输出 | 指标/日志默认重放GET403，原主环境计划/重放正文0、业务POST0；旧FAILED/OUTPUT_REJECTED与预期/实际1000日志保持；无应用服务重启、容器重启或新迁移 |

最初实际连接请求使用了错误的credentialPin字段id，真实HTTP400拒绝；改用现有领域Pin及credentialId闭合契约后链通过。测试自查还修正expectedRecords字段、BigDecimal数值比较、不能用当前readAt判断整个数据页不变，并将固定sleep改为明确来源可见性探测。第一条链1m10s通过，最后完整315及路径完善后专项1均有实际输出；没有把测试编码当测试通过，也没有用重发掩盖失败。

仓库外增加22项原AC的当前完成审计矩阵，并保留原观察结果、视觉/性能/容量门禁及不足。历史证据和文件存在只用于定位，不能替代最终全范围证明。完整目标active：本地实际协议/存储使用合成数据不证明外部客户或生产身份；资产重放、覆盖修复/未确认重放处置、通知与独立可靠性证据、保留/容量、公平调度/轮询完整性、租约/fencing/HA、生产身份及独立OIDC持续LOG仍继续。无提交或推送。见[验收步骤](runbooks/workflow-replay-provider-acceptance.md)、[指标重放](runbooks/workflow-metric-replay.md)与[日志重放](runbooks/workflow-log-replay.md)。

最终辅助实际通过：545份结构文件/6只读Tool，私有资料边界2993份提交候选文件，76份相关文档/790个本地链接，git diff --check退出0。既有CRLF提示保留；本机产品进度与证据已更新，完整目标active，无提交或推送。

## 139 · 模型约束实体实例与精确版本 pin · 2026-10-06

资产页实体实例抽屉按内置和已发布实体模型渲染字段控件；成功后的详情沿用本次选中的精确模型定义。实体持久化可选 `{id,revision,digest}` pin，详情/列表/分页返回 pin；Web 对 custom pin 读取固定发布版本并比对摘要，按 pin 缺失或定义不可读降级为字段 ID。来源 upsert 清除 pin。未知写入保留原请求及编号，只有显式操作才原样重试。Playwright 使用拦截的契约响应验证 Web 交互，不代表真实后端或数据库集成。

| 实际执行 | 结果 |
| --- | --- |
| 全部契约 pytest | Python 3.14：1908通过，24.61秒，退出0；新增 pin 合法、非法与旧实体无 pin 兼容用例 |
| Java 纯领域 / 平台编译 | `python -X utf8 scripts/check_java_domain.py` 退出0；离线平台主代码/测试编译通过，实体 pin smoke 通过；`EntityInstanceStoreTest` 通过 |
| Rust `--locked` 默认 / `--all-features -j 1` | 41 / 49 项通过 |
| TypeScript / Vite | `pnpm build:web` 通过；保留既有大 chunk 警告 |
| 实体创建 Playwright 专项 | 系统 Chrome：4项通过，覆盖创建字段标签/ID、刷新后精确版本解析、未知结果原样重试、切换身份后清空表单并重读目录 |
| PostgreSQL HTTP / 多实例 | 未运行：`OPSWEAVE_TEST_JDBC_URL` 未配置 |

V060 迁移和实体模型 pin 的 SQL 投影已通过离线 Java 主代码/测试编译，但本轮没有真实 PostgreSQL 执行迁移或 HTTP 集成。Fixture Playwright 只验证协议形状和前端恢复行为；不代表数据库持久化联测完成。
## 151. 2026-10-07 注册连接固定指标历史（追加）

新增注册连接版本指标历史读取：请求固定 source UUID、配置 revision、指标 itemId、闭合60秒窗口和最多600点；服务端从已完成发现回执解析完整来源 pin，复用登记 endpoint、credential 和 host group 范围，响应保留 connection digest、scope digest 和完整来源定义。没有新增启动单元、数据库或浏览器可覆盖的上游参数。同步页签新增回归用例覆盖首次读取历史、查看详情、手动同步和503不自动重试路径。

| 检查 | 实际结果 |
|---|---|
| Java | `./gradlew :apps:platform-api:compileJava :modules:integration:compileJava --no-daemon` BUILD SUCCESSFUL |
| Web | `pnpm --filter @opsweave/web-console exec tsc --noEmit` 退出0；`pnpm --filter @opsweave/web-console build` 退出0并保留既有大 chunk 警告 |
| 差异 | `git diff --check` 通过 |
| 浏览器 | 使用仓库外现有 Chromium `D:\\workspace\\git-code\\ops-weave\\.tmp\\chromium-1193\\chrome-win\\chrome.exe`，显式启动生产预览后运行 `registered-item-sync.spec.ts`，2项通过；覆盖首次历史、详情、手动同步和503不自动重试 |

本轮追加实际通过：仓库外 `.tmp/mvp-check-venv/Scripts/python.exe -X utf8 -m pytest tests/contracts/test_registered_item_scan.py tests/contracts/test_data_source_openapi.py -q`，19项通过；`pnpm --filter @opsweave/web-console exec tsc --noEmit` 退出0。仍未执行 PostgreSQL HTTP、多实例并发、真实来源/身份/TLS或生产部署。Go 开发管理器测试本轮未通过，Windows 进程树停止用例失败并留下日志句柄，不能视为管理器测试通过。完整目标仍 active。
