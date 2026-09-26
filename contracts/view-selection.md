# 浏览器只读选择契约 v1

规范化对象由 `schemas/v1/{inventory,incident,metrics}-view-selection.schema.json` 定义，样例在 `examples`。它们不是新的 API 或身份凭据。Web 手写解析器与序列化器必须通过相同样例与 URL 负例测试。

| hash 路径 | 可选参数 | 缺省值 |
|---|---|---|
| `#/inventory` | q、type、lifecycle、after、entityId | q/lifecycle 空，type=host，ID/cursor=null |
| `#/incidents` | status、after、incidentId | status 空，ID/cursor=null |
| `#/metrics` | q、after、entityId、metricKey、range、from、till | q/metricKey 空，range=1h，ID/cursor/window=null |

- hash 最长 2048 个 UTF-16 code units；只允许一个 `?`，URLSearchParams 编码，空格使用 `+`。非法百分号或 UTF-8、未知字段、重复字段都拒绝；不接受 tenant/user/permission/token/action 等替代身份或执行指令。
- q 去除两端空白后最多 100 个 UTF-16 code units，不含 C0/C1 控制字符或 U+FFFD。schema 的 maxLength 按 Unicode 字符计数，Web 额外执行较严格的 UTF-16 长度上限；此差异只影响非 BMP 字符。UUID 为小写标准 8-4-4-4-12 格式，null 通过缺省参数表达，禁止空 ID。
- type 仅 host 或空（全部）；lifecycle、status、range 使用 schema 枚举。metricKey 为空（待读取目录后选择）或 1–128 位 `a-zA-Z0-9_.:/%-`。
- from/till 为 UTC 整数秒，范围 0–9999999999，规范十进制，无前导零。必须同时缺省或同时存在；存在时必须有 entityId，且 `0 <= till-from <= 3600`。跨字段差值由 Web codec 检查；破损窗口不得退回最近时间。实际查询后把完整资源、指标与时间窗口写入 URL，点击 Last 范围才重新选择最近窗口。
- serializer 省略缺省值，type=空必须保留 `type=`。解码后的对象包含 schema 全部字段。无任何凭据、缓存结果、写操作输入或幂等键。
- 恢复链接仅还原表单/选择，不自动请求、导入或变更状态。每次显式读取仍由服务端重新授权。游标是实时 UUID 游标，不代表快照或可推断的总页数。
- 浏览器后退/前进使旧响应失效，丢弃结果。刷新仅保存 URL 选择，开发 Token 仍仅在内存。初次输入凭据（包括逐字输入）到首次读取前保留链接；已读取过或进入页面时已有凭据的会话，更换凭据会清除当前选择。注销、401/403、过期也清除选择；早先浏览器历史仍可能含非凭据选择，不能作为授权依据。无论是否已读取，凭据变化始终取消请求、丢弃结果。
- URL 可以包含资源 ID 和搜索词；勿在搜索词中输入秘密。无效链接保留原地址并展示错误，必须显式重置后才能读取。选中的指标从当前授权目录消失时展示错误，不替换成第一项。
