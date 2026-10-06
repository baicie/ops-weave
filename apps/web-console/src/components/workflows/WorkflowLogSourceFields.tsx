export function WorkflowLogSourceFields(){return <details className="workflow-source-config"><summary>日志来源字段</summary><dl>
 <dt>timestamp</dt><dd>接收时间，精确到纳秒；默认映射 eventTime</dd><dt>body</dt><dd>原始正文，保留空白和换行；默认映射 body</dd><dt>sourceKey</dt><dd>日志项的完整来源键</dd>
 <dt>logEventTime</dt><dd>来源提供的事件时间，未提供时为空</dd><dt>severityCode</dt><dd>来源的原始级别代码，需要按实际来源显式转换</dd><dt>eventSource</dt><dd>来源提供的事件来源，未提供时为空</dd><dt>eventId</dt><dd>来源的原始事件编号，不代表全局唯一标识</dd>
 </dl></details>}
