# Database prototypes

`migrations/platform` 与 `migrations/ai` 是起始 SQL 原型，并非完整生产表结构。当前 Java/Python 应用未加载这些迁移，不会自动连接或修改数据库。

正式接入时通过 Flyway/AI 迁移工具管理，分别配置 migration role 与 runtime role。同一数据库内用 schema 标明所有权，不允许 Worker 直接写其他服务业务表。拆库时再将必要跨域外键转为受控 API 校验与对账，不是简单删掉约束。

本次环境没有 PostgreSQL 服务，SQL 未执行验证。`examples/rls.sql` 仅展示一个表的策略，绝不代表整个平台已获得多租户隔离。
