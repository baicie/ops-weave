# 开发规程

1. 按初始化手册创建真实依赖锁，先验证默认Mock和所有可选feature。
2. 本地交互演示使用Rust+Vite，不依赖PG。所有数据为合成数据。
3. 合成数据与真实平台适配必须显式选择，禁止失败时静默回退。
4. `/healthz`只验证进程，`/readyz`只描述当前模式；都不是生产可用性承诺。
5. 输出失败排查runId、provider、预算、Schema和证据引用；不要把完整Prompt和Key打印到日志。
6. 导入真实数据前完成租户权限、Connector只读scope、Schema映射及去重；操作前备份。
7. 重放默认关闭通知/Action；误采数据通过可审计迁移修正，不盲删资源。

完整命令见 `docs/architecture/repository-guide.md`。
