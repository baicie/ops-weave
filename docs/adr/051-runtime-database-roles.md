# ADR-051：运行数据库角色与迁移边界

状态：已采纳（2026-09-27）；实际运行证据见验证报告第60节。

## 背景

ROADMAP M1要求数据库使用最小权限运行角色。平台可在迁移账本齐全时跳过DDL，但Worker每次启动都会执行
V001/V002，包括ALTER TABLE；因此不能仅凭已实现HTTP鉴权宣称数据库权限边界完成。

## 决策

保留一个PostgreSQL存储，不增加服务或数据库。迁移所有者、platform-api、ingestion-worker使用不同身份；
Rust、Web及模型没有业务数据库访问入口。新运行角色使用LOGIN、NOSUPERUSER、NOCREATEDB、NOCREATEROLE、
NOINHERIT、NOREPLICATION、NOBYPASSRLS，不属于所有者或其它角色，也不拥有业务对象。

- 平台通过显式表清单获得实际adapter所需的SELECT/INSERT/UPDATE/DELETE；不可写迁移账本、不可访问ingestion，
  不获CREATE、TRUNCATE、REFERENCES、TRIGGER或未来表的默认授权。
- Worker仅获得ingestion schema USAGE和history_checkpoint的SELECT/INSERT/UPDATE，不能删除检查点或读取业务表。
- Worker的schema-mode默认verify：只读检查所需列和非延迟、有效、可用的tenant/source/item主键；缺失列、旧stream主键
  或无读取权限即拒绝启动。只有显式migrate才执行原有迁移，再校验结构。未知模式拒绝，不在运行中回退迁移。
- 迁移由独立所有者预先执行。平台启动读取现有schema_migration账本；缺迁移时受限角色无法补写DDL，启动失败。
  当前账本只记录migration ID，不是checksum校验；Worker结构检查也不是完整schema drift或迁移摘要验证器。
- 授权清单在db/security/runtime-grants.sql，逐表列举；新迁移加表时必须审查清单。清单拒绝共用、提权、有成员关系
  或拥有对象的角色；它不会自动撤销PUBLIC或既有授权，不替换目标环境权限审计。

## 验证与边界

scripts/check_database_roles.mjs仅面向显式、自有、loopback测试库，使用两个随机临时LOGIN身份，实际检查
跨模块的全部当前表权限、所有者切换、DDL、不可删原始记录及检查点等拒绝，再运行现有两条浏览器整链。
角色密码仅在内存/子进程环境/SQL stdin中，SQL错误输出只保留稳定失败说明；角色创建会关闭本连接的语句/错误语句日志。
结束时撤销本次随机角色的授权并删除角色，保留原所有者的业务对象和数据。不用于自动创建生产账号。

数据库角色隔离按启动单元分配，租户/对象/会话授权仍由平台执行器逐请求检查，没有新增RLS或声称数据库自动隔离租户。
PUBLIC默认TEMPORARY等权限需要运营方审计；本ADR证明业务schema的DDL拒绝，不泛称所有临时对象创建都被禁止。
真实Zabbix、模型、IdP/TLS和生产角色配置仍需要独立验收。合成fixture整链不会因此变为真实来源验收。
