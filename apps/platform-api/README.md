# platform-api

启动装配骨架，目前只有健康检查；所有非健康 HTTP 路径默认拒绝。

`gradle :apps:platform-api:bootRun`

真实数据库、OIDC、业务 API、任务执行均待实现。不要因进程健康误以为业务已经可用。
