# 首轮研发任务清单

## Bootstrap

生成可信的依赖锁与Wrapper，运行Rust默认/全部feature测试和Java/Web完整构建，修复真实编译差异并锁定工具链。保留合成演示与closed默认值，提交验证报告。

## 真实数据前置

在Java identity/application层定义主体、tenant与resource scope，接入OIDC验证，补允许/拒绝矩阵；为数据库创建非表主人的最小权限运行角色。不要把dev token升级成生产token。

## 首个Connector闭环

先做CMDB与Zabbix Host只读同步：source-instance作用域、分页、checkpoint、完整快照标志、Raw引用、字段映射、ExternalLink、Observation；来源失败不删除；重放只产生幂等数据更新。

## Incident与只读Tool

实现Incident聚合、MetricDefinition与查询摘要、版本化 `incident.get` / `metric.summary`。服务端再次授权并限制时间范围/基数。Rust以HTTP adapter替换fixture端口，配置必须显式选真实模式；错误不能回退合成数据。

## 真实诊断链

Provider接入按官方固定版本测试，保留输出schema、缺失披露与Evidence引用校验。将AIInsight作为平台业务资源入库；Context的历史asOf与实际访问过期判断同时生效。

## 持久化和Skill发布

由Java ai-control管理不可变Skill版本；Rust持久化AIRun/StepRun/checkpoint，不写平台实体表。补租约、fencing、重复投递、worker退出、恢复鉴权与动作幂等验收后，再启用长任务和自动化。
