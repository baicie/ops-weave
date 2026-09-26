# ADR-022：当前知识读取会话与持久只读证据

状态：本机实现；生产身份、真实来源和模型验收未完成。日期：2026-09-25。

## 问题

M3 提供最新 Incident 投影和 VM 样本查询。采样时间不等于平台首次获得样本的时间；VM 当前没有可靠的历史 ingestion/availableAt 索引。把最新投影或今天读取的指标伪装成历史 asOf 已知数据，会泄漏未来知识。模型直接读取通用实体/指标接口也无法共享 Incident 范围、调用预算和证据复核。

## 决定

新增明确 `knowledgeMode: current` 的读取会话，固定 Incident 版本、关联实体集合、UTC 整秒且不超过一小时的采样窗口。时间窗口限定采样对象；证据 `availableAt` 是实际快照采集时间，过期时间为其后 24 小时。模型调用前的当前知识截止时间应在所有输入采集完成后确定；旧 Rust 历史 `asOf` 流程保持原有约束，不能把新证据直接塞入旧历史 ContextBuilder 或回写采样时间冒充可见时间。

Java `ai-control` 提供 `incident.get@2.0.0`、`metric.summary@2.0.0` 和 `evidence.get@2.0.0`。当前读取只含 Incident 问题概要和 Gauge 统计，来源/维度各自保留。未知指标、无采样、缺映射、缺日志和变更都显式披露；SUM/HISTOGRAM 暂不作 Gauge 运算。指标目录在读取前后比较，防止中途修改造成错误版本标签。

会话由 Java 已认证 Principal 创建；请求中不能覆盖 tenant、subject、权限或资源范围。Java 执行器重新检查 Incident、全部关联实体、指标和 Evidence 权限。Incident 版本及关联集合在采集/复核前后都检查。证据读取可授予其他具有相同资源权限的用户，但读取会话及其调用预算绑定创建者。

预算：每会话 60 秒、共 4 次调用，供两次输入读取及两次结果前复核使用；每主体最多 4 个未到期会话；每次执行 15 秒；平台实例共 4 个在执行请求。超时后底层工作未退出时仍占并发名额。最多 5 个实体、合计 500 个采样点、证据文档 30000 UTF-8 字节，超限拒绝且不静默截断。没有任意 HTTP、SQL、Shell 或动作工具。

V011 在已有平台 PostgreSQL 中保存读取会话、不可变证据和仅含元数据的调用审计。调用额度与 STARTED 审计原子写入；证据插入和完成审计在同一事务内。失败不得返回已保存成功。会话行锁和主体 advisory lock 保证多个适配器实例共享额度；JVM 执行器并发上限仍是单实例能力，不是分布式容量或 HA 证明。显式 memory 模式只用于开发，PG 失败不会回退内存。

Rust `PlatformHttp::loopback` 是本机开发 HTTP 适配器：仅接受数字 loopback HTTP origin，逐请求向固定 Java 边界转发调用者凭据，不配置跨用户管理员凭据。禁用重定向、代理和重试；2 秒连接、16 秒传输上限，另受剩余会话期限约束；流式读取最多 32768 字节。契约从 `contracts` 编译到二进制，只解析白名单内的内置引用，不下载 Schema。可信身份取自会话响应；证据内容仍是不可信数据，且检查 tenant/Incident/实体、版本、来源引用、窗口、采集/过期时间、统计数量和复核不可变性。

## 结果与剩余工作

本机独立 probe 已能调用 Java、PG 和 VM；它不调用模型，不是新的启动单元，也未作为生产 Runtime 端点开放。原 demo 保持固定 tenant/Incident 和显式 fixture。新的当前知识诊断模板、单次模型摘要、平台 AIInsight 幂等持久化、查询与证据页面仍待衔接；需新建固定版本 Skill 包，不能覆盖原已发布示例。

尚无历史 asOf 证据、完整原始观测历史、自动来源调度、证据/审计留存清理、租户存储总额限制、OS 崩溃恢复/HA 或生产授权验收。真实 Zabbix、模型和登录提供方暂未配置；本机来源为显式 fixture，不算真实 MVP 退出。当前引用复核只证明引用与权限/快照关系，不证明因果或事实正确性。

契约见 `contracts/schemas/v1/tool-*.schema.json`、`platform-evidence.schema.json` 和三个 v2 Tool 定义；执行验证见验证报告第 28 节；复现见 [本机 Tool 读取验收](../runbooks/tool-read-local-acceptance.md)。
