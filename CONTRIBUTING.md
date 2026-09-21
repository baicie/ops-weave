# 开发与提交

阅读AGENTS.md和初始化手册。分支建议 `feat/<domain>-<goal>`、`fix/<domain>-<problem>`、`docs/<topic>`。

变更须给出领域边界、契约影响、迁移方式和测试结果。破坏接口要版本化；新增部署组件/动作权限/外部依赖需ADR。

先在可信环境生成Cargo/pnpm/Gradle锁与Wrapper；格式化Rust后提交。正常CI使用locked/frozen依赖，不运行自动升级。发布前完成SBOM、漏洞扫描、秘密扫描及真实端到端测试。

只有真正执行的检查才能写“通过”；仅静态语法检查、Mock和Schema检查不能声称真实模型、数据源或生产系统已可用。
