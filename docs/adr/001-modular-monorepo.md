# ADR-001：模块化 Monorepo，先粗粒度部署

状态：建议基线 · 日期：2026-09-21

## 决策

Java 领域模块只做库；初始启动应用为 API、采集 Worker、AI Runtime、Web。独立扩容/隔离/团队发布形成明确需求后才拆服务。

## 代价与后续

维护跨模块公开契约与架构测试；拆库需要真正迁移所有权，不允许长期共享写表。

关联：`docs/architecture/v4-design.md`。
