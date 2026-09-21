# Architecture checks

当前 `scripts/check_repo.py` 对领域层禁止框架/数据库依赖，并验证只读 Tool/Skill 配置。

这只是基础防线；后续加入 ArchUnit，检查模块循环依赖、只访问公开 API、跨领域不可引用 infrastructure、Connector 不直写 inventory 数据库。
