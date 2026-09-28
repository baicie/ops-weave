# 数据库运行角色与本机验证

该说明对应[ADR-051](../adr/051-runtime-database-roles.md)。一个PostgreSQL库中使用独立迁移所有者、平台运行角色、
Worker运行角色；不把测试库所有者凭据继续用于正式运行，不给Rust或Web业务数据库凭据。

## 启动顺序

1. 在受控维护步骤用独立所有者执行并核实平台迁移：以InventoryWiring登记的V002、V003、V004、V007至V027为准，
   保留SchemaMigrator的事务锁与账本语义；V001是原型，不作为当前运行表的初始化脚本。平台已有账本时跳过DDL。
2. 由同一维护边界执行db/migrations/ingestion/V001__history_checkpoint.sql和V002__history_checkpoint_lease.sql，
   每次迁移在事务及pg_advisory_xact_lock(1875725101)下完成。旧库重复series必须先人工处理，不能自动合并游标。
3. 运维通过受控凭据管理创建两个不同的LOGIN角色，全部特权标志关闭、没有角色成员关系或对象所有权。
   使用psql的platform_role/history_role标识符变量执行[授权清单](../../db/security/runtime-grants.sql)，并赋予目标库CONNECT。
   清单不包含密码，不撤销PUBLIC，不给未来表默认权限；部署前检查PUBLIC/成员关系/既有对象授权及备份运维角色。
4. 平台使用OPSWEAVE_JDBC_USER/PASSWORD；Worker使用独立OPSWEAVE_HISTORY_JDBC_USER/PASSWORD，
   OPSWEAVE_HISTORY_SCHEMA_MODE保持verify（默认）。缺结构/权限时修正维护步骤，不能切回所有者掩盖问题。

显式开发引导才允许OPSWEAVE_HISTORY_SCHEMA_MODE=migrate；此模式需要所有者权限，不用作日常采集身份。
现有本机fixture脚本显式选择migrate引导；独立角色检查选择verify。Worker仍只有受控平台历史读取与VM写入，
不因数据库身份变更获得业务表访问、任意SQL Tool或动作权限。

## 自有本机库验证

先运行Java/PG测试完成平台迁移，并构建两个bootJar、Web、Rust all-features。显式配置
OPSWEAVE_TEST_JDBC_URL/USER/PASSWORD（专用测试管理员，需能创建角色并关闭本连接语句日志；业务链不会继续使用此账号）、OPSWEAVE_TEST_VM_URL和绝对
OPSWEAVE_TEST_PSQL_EXECUTABLE；已有Chromium可用OPSWEAVE_TEST_CHROMIUM_EXECUTABLE明确指定。

运行 node scripts/check_database_roles.mjs。脚本只接受数字loopback地址及显式端口/数据库；不下载依赖，
不替换Docker配置，不访问外部Zabbix/模型/IdP。它将临时创建两个随机角色，验证权限拒绝后顺序运行：

- node scripts/check_metrics_stack.mjs --pipeline --runtime
- node scripts/check_oidc_stack.mjs --history-service

平台与Worker分别使用不同受限角色；后者必须通过OPSWEAVE_TEST_HISTORY_JDBC_USER/PASSWORD成对传递，
检查点查询也使用Worker角色。单独运行旧fixture入口没有这组显式参数时，仍用测试所有者显式migrate引导。
测试账号不写报告/命令行，SQL原始错误不回显。测试结束撤销并删除本轮随机角色，业务数据保留；异常退出时，
检查pg_roles中该次随机ow_platform_/ow_history_身份，仅清理已核实属于该次运行的角色，不能按前缀批量删除。

该脚本不签署M1–M4真实退出；PUBLIC TEMPORARY、目标环境TLS/IdP/来源/模型、备份和人工审阅仍需各自证据。
Windows本机PG不得使用detached直接启动postgres.exe：后台子进程可能创建可见控制台。应采用已验证的隐藏控制台
继承方式，并在放开验证连接前确认主进程和子进程均隐藏；仅设置父进程windowsHide不足以证明子进程不弹窗。

Windows控制台继承规则参考[Microsoft官方说明](https://learn.microsoft.com/en-us/windows/console/creation-of-a-console)；
PostgreSQL的Windows后台进程创建方式见[官方源码](https://github.com/postgres/postgres/blob/REL_17_STABLE/src/backend/postmaster/launch_backend.c)。
