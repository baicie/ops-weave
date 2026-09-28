# 本地 Zabbix 联调环境

2026-09-27 已实际启动。来源标识为 local-test / real-zabbix-agent-container。
使用真实 Zabbix 软件和 Agent 采样，验收范围仅本地监控测试栈。该环境由用户明确要求启动，不改变 OpsWeave 四个启动单元。

## 访问与凭据

- Web：http://127.0.0.1:18088
- JSON-RPC：http://127.0.0.1:18088/api_jsonrpc.php
- 用户名：Admin。随机密码保存在忽略文件 `.tmp/zabbix-local/login.txt`；默认密码已替换并实际登录验证。
- OpsWeave 只读账号：opsweave_readonly。凭据和 API token 位于 `.tmp/zabbix-local/readonly-api.json`，不得提交或复制到日志。
- Token 到期：2026-10-27T05:30:35Z。只允许 host.get、item.get、history.get、event.get，主机组 22 只读。
- 浏览器进入 Monitoring → Latest data，Name 填写 Container: 并 Apply，可看到三个指标。

## 启停

在仓库根目录执行，需要 Docker Desktop Linux 引擎运行：

```powershell
node .tmp/zabbix-local/control.cjs status
node .tmp/zabbix-local/control.cjs stop
node .tmp/zabbix-local/control.cjs start
```

启动器使用后台容器和 windowsHide；stop 保留数据卷。只有 Web 发布到 127.0.0.1:18088。
Compose 项目 opsweave-zabbix-local，数据卷 opsweave-zabbix-local_zabbix-db。
配置、摘要、凭据与验证产物均在忽略目录 `.tmp/zabbix-local/`，清理目录前应保留凭据和配置。

## 固定软件与采样范围

复用已有官方镜像并钉住 RepoDigest，启动时不拉取可变 latest：

| 组件 | 版本 | 摘要 |
|---|---|---|
| Server | 7.0.27 | sha256:5d10120f22775ddc33d8e01693ea02638c056931520d06c4c54940f214dee00c |
| Web | 7.0.27 | sha256:97db93456cf41cd22876ab57a82a600d84a9bdbcac28b1cd6a344d547f3df673 |
| Agent 2 | 7.0.27 | sha256:4bfb192325d54d0ca4f2b5ff1ade5db2832cad34150d8199417747f04949728e |
| PostgreSQL | 17.10 | sha256:742f40ea20b9ff2ff31db5458d127452988a2164df9e17441e191f3b72252193 |

配置参考 [Zabbix 官方容器文档](https://www.zabbix.com/documentation/7.0/en/manual/installation/containers)。
主机 10683 为专用测试 Agent 容器，采集 Agent 可见的 Linux CPU、可用内存百分比、系统运行时间。
Linux/WSL 共享内核的系统指标不能解释为 Windows 宿主机或容器独占资源。
Item 为 50740、50741、50742，每10秒采样；没有启用通知、修复、远程命令或伪造告警。
默认未配置 Agent 的 Zabbix server 示例主机已禁用。

## 本轮实际验证

- 四容器运行，Web/PG healthy，apiinfo.version 返回 7.0.27。
- 管理员换密后登录成功；只读 token 可见一个主机，三个指标各读回至少三条实际 history。
- 只读 token 调用 host.update 被拒绝，数据库与其他容器均没有宿主发布端口。
- Headless Chromium 实际登录、打开最新数据、筛选并看到三个指标，pageErrors=0，截图已查看。
- 产物：verification.json、browser-verification.json、latest-data.png；status 入口实际退出0。
- 本轮没有重跑 OpsWeave 全量契约/领域/Java/PG/VM/Rust/TypeScript/build，也没有执行 OpsWeave→Zabbix 完整接入链。
  历史结果保持原报告范围。模型、IdP/TLS与人工验收仍待完成，MVP估算不变。

Docker 原因遗留 dockerInference/engine.sock 启动失败，停止本次失败进程后保留故障通信目录并重建，Docker API 恢复。
备份位于 LocalAppData 下 Docker/run.stale-20260927-zabbix* 和 docker-secrets-engine.stale-20260927-zabbix*。
没有重置已有镜像、容器和数据卷，没有安装 Windows PG 服务。
