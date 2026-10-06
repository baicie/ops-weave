import type {SampleRecoveryCommand,SampleRecoveryReceipt} from '../../api/workflow-sample-recovery.ts'
import './workflow-sample-recovery.css'

export function WorkflowSampleRecoveryPanel(p:{checked:boolean;closed:SampleRecoveryReceipt|null;busy:boolean;acknowledged:boolean;pending:SampleRecoveryCommand|null;missing:boolean;error:string;acknowledge:(v:boolean)=>void;load:()=>void;abandon:()=>void;query:()=>void;resend:()=>void;dismiss:()=>void}){
 return <section className="workflow-sample-recovery" aria-label="终止样本确认"><header><h4>终止样本确认</h4></header>
  {p.closed?<p role="status">此批次已终止确认，原输出仍未确认。终止回执 <code>{p.closed.requestId}</code></p>:<>
   <p>原输出可能已存在。终止后保留原证明及数据，不再核验该批次。</p>
   {!p.checked&&!p.pending?<button disabled={p.busy} onClick={p.load}>读取终止状态</button>:null}
   {p.checked&&!p.pending?<><label><input type="checkbox" checked={p.acknowledged} disabled={p.busy} onChange={e=>p.acknowledge(e.target.checked)}/>我确认保留未知结果并终止该批次确认</label><button disabled={p.busy||!p.acknowledged} onClick={p.abandon}>终止确认</button></>:null}
   {p.pending?<><p role="status">终止请求 <code>{p.pending.requestId}</code> 的结果待确认。</p><button disabled={p.busy} onClick={p.query}>查询原终止回执</button></>:null}
   {p.missing?<><button disabled={p.busy} onClick={p.resend}>重发原终止请求</button><button disabled={p.busy} onClick={p.dismiss}>放弃本次终止确认</button></>:null}
  </>}{p.error?<p role="alert">{p.error}</p>:null}
 </section>
}
