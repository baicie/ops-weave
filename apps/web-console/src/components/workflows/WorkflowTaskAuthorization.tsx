import type { RuntimeTask } from '../../api/workflow-runtime.ts'

export function WorkflowTaskAuthorization({task}:{task:RuntimeTask|undefined}) {
 const authorization=task?.authorization
 if(!authorization)return null
 return <details className="workflow-task-authorization"><summary>后台授权 · 已用 {authorization.consumedBatches}/{authorization.maxBatches} 批</summary><dl className="run-meta"><div><dt>授权截止</dt><dd>{new Date(authorization.expiresAt).toLocaleString()}</dd></div><div><dt>授权标识</dt><dd><code>{authorization.id}</code></dd></div></dl></details>
}

export function runtimeFailureMessage(error:string):string {
 const messages:Record<string,string>={
  AUTHORIZATION_EXPIRED:'后台授权已到期，请核对后显式重新启动。',
  AUTHORIZATION_REVOKED:'后台授权已撤销或权限配置不可用，请核对授权。',
  EXECUTION_LIMIT:'本次授权的批次额度已用完，请核对后显式重新启动。',
  OPERATOR_CHANGED:'固定算子版本与当前实现不匹配，请核对固定版本后显式重新启动。',
 }
 return messages[error]??error+'，请检查回执后再决定是否重新启动。'
}
