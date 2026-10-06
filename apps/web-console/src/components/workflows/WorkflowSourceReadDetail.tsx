import type {DiagnosticSource} from '../../api/workflow-diagnostics.ts'
const reasons={UNAVAILABLE:'来源不可用',ACCESS_DENIED:'读取权限不足',INCOMPLETE_WINDOW:'完整窗口未读完',INVALID_RESPONSE:'返回数据不符合约定',METADATA_CHANGED:'固定对象发生变化',SOURCE_KEY_CHANGED:'来源键发生变化',VALUE_TYPE_CHANGED:'来源值类型发生变化',UNIT_CHANGED:'来源单位发生变化',OTHER_FAILURE:'读取未完成'}
export function WorkflowSourceReadDetail({source}:{source:DiagnosticSource|null}){
 return <dl aria-label="来源读取统计"><dt>来源读取统计</dt><dd>{source?source.completed?'完整返回':'读取失败':'—'}</dd>{source?<><dt>来源失败 / 尝试</dt><dd>{source.failed} / {source.attempts}</dd><dt>完整返回记录</dt><dd>{source.received===null?'—':source.received}</dd><dt>来源读取时间</dt><dd><time>{source.startedAt}</time> — <time>{source.completedAt}</time></dd>{source.failureCode?<><dt>来源异常</dt><dd>{reasons[source.failureCode]} · <code>{source.failureCode}</code></dd></>:null}</>:null}</dl>
}
