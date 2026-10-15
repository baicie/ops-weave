# 产品与架构边界

适用范围：所有仓库任务；与根 `AGENTS.md` 一起遵守。

- 当前产品任务只服务 OpsWeave 0.1.0：多源数据接入 → Entity/Incident → Evidence/Root-cause hypothesis → Investigation → 有证据的 RCA 报告。不要为了页面需求创建插件市场、自由脚本、通用工作流引擎或新微服务。
- 优先阅读仓库根部 `AGENTS.md`；本规则绝不覆盖已有安全、身份、数据、真实验证、非项目对标材料等要求。
- OpsWeave Java Platform 拥有业务主数据；前端不是鉴权或根因推断的可信边界。
- 数据来源 `REAL` / `FIXTURE` / `MOCK` / `UNKNOWN` 明确标注。无实际运行环境时记录 `NOT_RUN`，不能汇报未执行的截图、E2E、可视化或真实来源测试。
- 新 Investigation 数据按页面和 typed adapter 管理，不注入无关的工作流共享状态。Web Console 按用户旅程设计，保留现有路由和会话边界。
