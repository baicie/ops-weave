# 受控来源连接目录

§102提供授权地址目录和内部固定pin读取适配器；§103已接入实例新增/更新、完整配置快照、地址与固定凭据版本选择和公开TEST/DISCOVER。操作见[实例连接](source-connections.md)。旧env创建入口和历史仍保留，工作流完整pin继续实施；仅登记目录不能宣称来源已连接或任务已运行。

管理员在仓库外本机目录创建JSON文件，限制文件访问权限并配置平台单元的`OPSWEAVE_SOURCE_ENDPOINTS_PATH`绝对路径。登记文件不含秘密，仍属于私有部署信息；不得提交文件、实际地址/租户目录或外部研究证据。配置结构如下，示例是Fixture，不会发起网络读取：

```json
{
  "schemaVersion": "2.0",
  "endpoints": [{
    "id": "fixture-host",
    "name": "Fixture Host 连接",
    "connectorKind": "ZABBIX_HOST",
    "address": "https://192.0.2.10/api_jsonrpc.php",
    "tenants": ["endpoint-fixture"]
  }]
}
```

文件上限65536字节、32条登记，每条明确1–32个租户。没有配置时目录为空；配置错误拒绝启动，并以固定错误信息报告，不打印文件内容或底层异常。字段闭集、地址范围与规范端口在服务器校验；详细边界见[契约](../../contracts/source-endpoints.md)。以数值IP连接，HTTPS证书必须覆盖该IP；域名/非标准路径暂不支持。HTTP只允许显式本机dev并且bindLoopbackOnly模式的回环IP。

保存后明确重启platform单元，通过可信平台会话GET `/api/v2/data-sources/endpoints`查看授权项，再GET对应ID核对语义摘要。读取不会访问上游、创建实例、迁移env连接或启动任务。不要把真实Token写入命令历史、URL或普通日志，也不要保存公开元数据之外的凭据正文。UI只选择这里返回的登记ID与固定摘要，不能自带URL、代理或请求头。

地址变化保留旧引用的不可用状态并要求明确更新配置；凭据轮换创建新的秘密版本，旧固定pin不会自动切换。旧版本或整个凭据撤销后，新读拒绝，读中撤销则不接受结果。原env来源没有秘密版本，继续按UNVERIFIED检查结果显示。当前无目录写入API、生产委托、域名出口网关或多副本预算保障。
