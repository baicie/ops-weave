import type {DiagnosticDispatch} from '../../api/workflow-diagnostics.ts'

export function WorkflowDispatchDetail({dispatch}:{dispatch:DiagnosticDispatch|null}){
 if(!dispatch)return null
 return <dl aria-label="调度时间"><dt>入队时间</dt><dd><time>{dispatch.enqueuedAt}</time></dd><dt>开始执行</dt><dd><time>{dispatch.startedAt}</time></dd></dl>
}
