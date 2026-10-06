import type { RuntimeControlCommand, RuntimeControlReceipt } from '../../api/workflow-runtime.ts'

type Props = { command: RuntimeControlCommand | null; receipt: RuntimeControlReceipt | null; missing: boolean; busy: boolean; query: () => void; resubmit: () => void; dismiss: () => void }
export function WorkflowRuntimeControlStatus({command,receipt,missing,busy,query,resubmit,dismiss}:Props) {
 if(command)return <section className="workflow-control-status" aria-label="任务控制结果待确认">
  <p role="status">{({START:'启动',STOP:'停止',RESUME:'恢复'})[command.operation]}请求待确认 · 固定 v{command.revision}</p>
  <p>原请求标识 <code>{command.requestId}</code></p>
  <button disabled={busy} onClick={query}>查询原控制结果</button>
  {missing?<><p>未找到原回执。可以显式重发原命令，或放弃本次确认后重新读取当前状态。</p><button disabled={busy} onClick={resubmit}>按原标识重新提交</button><button disabled={busy} onClick={dismiss}>放弃本次确认</button></>:null}
 </section>
 if(!receipt)return null
 return <details className="workflow-control-status"><summary>原{({START:'启动',STOP:'停止',RESUME:'恢复'})[receipt.operation]}回执已确认 · 固定 v{receipt.task.revision}</summary><dl className="run-meta"><div><dt>原请求标识</dt><dd><code>{receipt.requestId}</code></dd></div><div><dt>受理时间</dt><dd>{new Date(receipt.createdAt).toLocaleString()}</dd></div><div><dt>受理时任务代次</dt><dd>{receipt.task.generation}</dd></div></dl></details>
}
