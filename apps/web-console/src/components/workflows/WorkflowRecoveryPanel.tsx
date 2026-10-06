import type {RecoveryCommand,RecoveryReceipt} from '../../api/workflow-recovery.ts'

export function WorkflowRecoveryPanel(p:{batchId:string;closed:boolean;acknowledged:boolean;busy:boolean;pending:RecoveryCommand|null;missing:boolean;receipt:RecoveryReceipt|null;error:string;acknowledge:(v:boolean)=>void;abandon:()=>void;query:()=>void;resend:()=>void;dismiss:()=>void}){
 return <section className="workflow-quality-detail" aria-label="终止任务恢复"><h4>终止任务恢复</h4>
 {p.closed?<p role="status">此任务已终止恢复，原输出仍按批次证据显示。</p>:<><p>原输出可能已存在。终止后保留原批次与已确认进度，此版本不能继续恢复。</p><p>原批次 <code>{p.batchId}</code></p><label><input type="checkbox" checked={p.acknowledged} disabled={p.busy||Boolean(p.pending)} onChange={e=>p.acknowledge(e.target.checked)}/>我确认终止此任务的恢复</label><button disabled={p.busy||Boolean(p.pending)||!p.acknowledged} onClick={p.abandon}>终止恢复</button></>}
 {p.error?<p role="alert">{p.error}</p>:null}{p.busy?<p role="status">正在处理原恢复命令…</p>:null}
 {p.pending?<><p role="status">控制结果待确认 · <code>{p.pending.requestId}</code></p><button disabled={p.busy} onClick={p.query}>查询原终止回执</button>{p.missing?<><button disabled={p.busy} onClick={p.resend}>原样重发终止命令</button><button disabled={p.busy} onClick={p.dismiss}>放弃本次确认</button></>:null}</>:null}
 {p.receipt?<p role="status">已受理 · <code>{p.receipt.requestId}</code> · 已确认 {p.receipt.confirmedBatches} 批 / {p.receipt.confirmedRecords} 条保持</p>:null}
 </section>
}
