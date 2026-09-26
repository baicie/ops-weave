# 本机告警与 Incident 验收

范围：显式 Zabbix fixture → Java HTTP → PostgreSQL Incident → Web 时间线/人工流转 → 重新授权的资产与同窗口 VictoriaMetrics 查询。不是厂商或真实模型验收；用户当前尚未准备外部环境。

## 运行

使用已有开发环境配置，密钥只放本机环境或未跟踪配置文件，不粘贴到终端输出/任务消息。平台绑定 127.0.0.1；`OPSWEAVE_AUTH_MODE=dev`、随机至少 32 字符 `OPSWEAVE_DEV_TOKEN`、固定 `OPSWEAVE_DEV_TENANT`。显式启用 `OPSWEAVE_DEV_PERMISSIONS=entity.read,metric.read,source.sync,incident.read,incident.manage`；默认配置不会给新权限。

设置 `OPSWEAVE_INVENTORY_STORE=postgres` 及 JDBC 环境变量；设置 `OPSWEAVE_ZABBIX_MODE=fixture`、`OPSWEAVE_ZABBIX_SOURCE=zabbix-1`。指标查询需配置 `OPSWEAVE_VICTORIAMETRICS_URL`，不可用时返回失败。按已有开发启动说明启动平台和 Web。

1. 资产页同步 Host，使外部 Host ID 10084/10085 与本地资产建立可信链接。
2. 打开 `#/incidents`，输入开发 Token，展开导入区，选择“使用 fixture 时间窗口”（2026-09-21 12:00–13:00 UTC）。
3. 显式导入首批：首次接收 2 条，新建 2 个；再次导入新建 0 个。若没有先同步 Host，保留未映射 gap，随后同步 Host、重导即可补齐。
4. 刷新列表、查看详情：来源标记 labeled-fixture；CPU 问题有恢复事件 30003，服务问题仍活动且来源已抑制；两者人工状态均保持 OPEN。
5. 手工转为 INVESTIGATING，再刷新页面重新读取，验证版本/状态/时间线仍在。状态冲突要求重新读取；不确定响应可以重试同一个请求键。
6. 读取关联资产、跳到发生前后 30 分钟指标。新页重新输入 Token 后读取目录，链接不能授予权限。fixture 历史窗口未写采样时显示 No data，不伪造曲线。
7. 清除或更换 Token 应清空列表、详情、关联资产和待确认结果。失去权限时也不得保留旧详情。

## 自动验收

先实际运行 `gradlew.bat :apps:platform-api:bootJar :apps:ingestion-worker:bootJar`、`pnpm --dir apps/web-console build`。在专用 loopback 测试存储设置 `OPSWEAVE_TEST_JDBC_URL`、`OPSWEAVE_TEST_JDBC_USER`、`OPSWEAVE_TEST_JDBC_PASSWORD`、`OPSWEAVE_TEST_VM_URL`，需要时设置 `OPSWEAVE_TEST_CHROMIUM_EXECUTABLE`，再运行：

```text
node scripts/check_metrics_stack.mjs --pipeline
```

脚本为本次验收生成随机 Token 和专用租户，实际导入 Host/Item/问题，向 VM 写显式合成点，验证指标、资产、Incident 及流水线。关闭自己启动的 Java/Vite/Chromium，保留测试存储供检查；截图在 `.tmp/metrics-acceptance/`，不提交。该脚本不使用 Playwright 路由拦截后端，不调用外部 Zabbix 或模型。单独 Web 交互回归使用 `pnpm test:web`，那些响应有明确 fixture 拦截。

当前详情为最新有界快照；不提供完整原始问题历史、人工合并/拆分、自动诊断或生产认证。更具体的设计限制见 ADR-021。
