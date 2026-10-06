# 受控来源地址目录 v2

服务器部署目录是新增连接的地址权威来源。`source-endpoint`、`source-endpoint-pin`、`source-endpoint-page`、`source-endpoint-read`与`source-endpoint-registry`使用封闭Schema，样例均为Fixture。公开元数据包含登记ID、名称、连接器类型、地址和语义摘要；不包含租户列表、凭据、请求头、代理设置或可执行指令。

§103已将目录pin接入[实例连接配置](source-connections.md)与公开TEST/DISCOVER；完整快照、原回执和凭据版本选择不改变本目录的出口限制。工作流完整绑定继续实施。

| 接口 | 当前行为 |
|---|---|
| GET `/api/v2/data-sources/endpoints` | 返回当前租户、对象范围与`source.sync`或`source.configure`授权允许的登记项，最多32条，按ID排序 |
| GET `/api/v2/data-sources/endpoints/{id}` | 读取一个当前授权登记项；其他租户的登记项不可读 |

接口不接受查询参数和客户端身份字段，不执行网络探测，不写业务数据。响应`schemaVersion=2.0`、`storage=memory|postgres`，沿用可信平台会话和`Cache-Control: no-store`。非法地址选择器400、无读取权限403、当前租户不存在404。没有登记写入HTTP接口；不支持的方法可能由受保护错误分派返回401，不能据此推断登记已更新。

`SourceEndpoint.Pin={id,digest}`中的摘要按现有UTF-8字节长度前缀规则，对`source-endpoint-v2`、ID、`ZABBIX_HOST`、地址依次计算。名称不进入摘要，地址/ID/连接器类型变化必须改变pin。当前没有兼容别名或“最新”解析。

部署文件最多65536字节、32项，每项1–32个明确租户ID；重复ID、重复租户、未知字段、重复JSON键、尾随正文和不支持的类型拒绝启动。文件必须是本机绝对路径，解析后的真实文件位于仓库外；没有配置时为空目录，无环境来源自动导入或网络下载。目录在启动时加载，文件改动经明确重启生效，不热加载。

新增登记地址只接受数值IPv4/IPv6和固定`/api_jsonrpc.php`路径。IPv4四段十进制不能有前导零；端口1–65535且为规范十进制。拒绝域名、IPv6区号/IPv4映射、空地址、link-local、multicast、IPv4 0段及224段以上、IPv6非全球单播/ULA范围、userinfo/query/fragment/编码或歧义URL。HTTPS默认允许登记单播IP；回环地址仅显式`dev + bindLoopbackOnly`可登记，同模式才允许回环HTTP。JSON Schema约束地址形式，完整IP/端口与部署模式策略在服务器适配器执行。

内部`RegisteredHostSourceReader`仅支持TEST/DISCOVER，检查可信主体的source、source-endpoint和credential读取范围。地址pin和固定秘密版本在IO前后核对；凭据轮换不自动换版，撤销后拒绝新读取，读取中撤销则不接受结果并清空临时字符数组。连接器已有String秘密参数只短暂构造字符串，不缓存/回显/记录；不能承诺JVM不可变字符串已擦除。

新登记传输固定精确URI，显式禁止重定向、绕过系统代理，不解析域名。保持JDK HTTPS证书校验、5秒连接/10秒每请求超时、2MiB每响应、1000条成员清单、TEST最多1条/DISCOVER最多5条、最多6/5次HTTP请求及进程内2个并发读取。不重试失败，不回退Fixture；来源字段值不进入发现元数据。该读适配器目前是内部基础设施，未绑定维护实例或公开POST检查接口，不能将其测试通过解释为多连接实例和工作流运行已经上线。

现有部署env连接及其v1/v2接口保持原实现，未自动转入新目录；其真实检查继续为UNVERIFIED凭据引用。固定配置快照、创建/更新UI和检查回执接入新适配器是后续阶段，完整发布依赖及可信后台委托也仍待实施。
