# 来源绑定更正契约

唯一wire定义：`source-binding-correction-input`、`source-binding-correction-receipt`、`source-binding-correction-page` Schema/样例及OpenAPI。所有入口只对可信配置的PG导入来源开放，要求全租户entity.read/entity.manage/source.sync；不接受tenant/actor/source/namespace/权限/租约覆盖。

- POST `/api/v1/integrations/cmdb/binding-corrections`：command + 固定mappingDigest，最多16KiB；command带原snapshotId、原/目标实体与版本、目标登记pin、新观测时间/字段和原因。同一PG事务替换当前绑定、保存新观测/PENDING字段与旧绑定审计回执，失败全部回滚。
- GET `/api/v1/integrations/cmdb/binding-corrections/{requestId}`：原操作者重新授权后读取原回执，404不证明未知写入失败。
- GET `/api/v1/entities/{entityId}/source-binding-corrections?after=...&limit=...`：整源管理者读取涉及该实体的历史，保留原操作者；limit1–25、默认25，UUID顺序实时分页。Web采用每页10条。

409码为BINDING_CHANGED、BINDING_UNCHANGED、BINDING_FIELDS_ACTIVE、CORRECTION_REQUEST_CONFLICT、CORRECTION_LIMIT；新观测还沿用SNAPSHOT_OUTDATED/BINDING_CONFLICT等快照码与SOURCE_REVIEW_CONFLICT。原生效字段必须先撤销；未知或已撤销的目标登记不接受。空字段、重复键、尾随JSON、无效UUID/版本/原因、越界请求拒绝；503不得回退或隐式重试。

新观测requestId与更正requestId相同；普通快照已占用该ID时拒绝更正。更正只导入一条部分快照，不对其他对象标缺失；同实体换pin时只增一次版本。旧Observation/快照/字段审核及Zabbix/指标/Incident/Evidence引用始终保留原entityId，回执不表示历史实体已合并。此处模式固定import，不声称厂商API或自动采集已连接。
