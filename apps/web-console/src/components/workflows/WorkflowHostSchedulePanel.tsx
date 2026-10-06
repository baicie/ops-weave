import type { Entry } from '../../api/workflows.ts'
import type { HostScheduleStatus,HostScheduleCommand,HostScheduleReceipt } from '../../api/workflow-host-schedules.ts'
import { runtimeFailureMessage } from './WorkflowTaskAuthorization.tsx'

type Props={entry:Entry;status:HostScheduleStatus|null;busy:boolean;pending:HostScheduleCommand|null;missing:boolean;receipt:HostScheduleReceipt|null;error:string;interval:number;name:string;names:{id:string;label:string}[];intervalChange:(v:number)=>void;nameChange:(v:string)=>void;refresh:()=>void;control:(op:HostScheduleCommand['operation'])=>void;query:()=>void;resend:()=>void;dismiss:()=>void}
export function WorkflowHostSchedulePanel(p:Props){
 const s=p.status?.schedule,blocked=p.busy||Boolean(p.pending),other=s&&(s.revision!==p.entry.definition.revision||s.digest!==p.entry.digest),locked=blocked||s?.state==='RUNNING'||Boolean(s?.activeScanId)
 return <section className="workflow-runtime-panel" aria-label="周期主机采集">
  <header><h3>周期采集</h3><button disabled={p.busy||Boolean(p.pending)} onClick={p.refresh}>刷新周期状态</button></header>
  <div className="workflow-runtime-settings"><label>采集间隔<select aria-label="主机采集间隔" value={locked?s?.intervalSeconds??p.interval:p.interval} disabled={locked} onChange={e=>p.intervalChange(Number(e.target.value))}>{[60,300,900].map(value=><option key={value} value={value}>{value/60} 分钟</option>)}</select></label><label>实体名称字段<select aria-label="周期采集名称字段" value={locked?s?.settings.nameField??p.name:p.name} disabled={locked} onChange={e=>p.nameChange(e.target.value)}><option value="">请选择</option>{p.names.map(f=><option key={f.id} value={f.id}>{f.label} · {f.id}</option>)}</select></label></div>
  <div className="workflow-runtime-actions">
   {!s||s.state!=='RUNNING'&&!s.activeScanId?<button disabled={blocked||!p.status?.available||!p.name||Boolean(p.status?.task?.state==='RUNNING')} onClick={()=>p.control('START')}>{s?'重新启用周期采集':'启动周期采集'}</button>:null}
   {s&&s.state!=='RUNNING'?<button disabled={blocked||!p.status?.available||Boolean(other)||p.status?.task?.generation!==s.taskGeneration} onClick={()=>p.control('RESUME')}>恢复周期采集</button>:null}
   {s?.state==='RUNNING'?<button disabled={blocked} onClick={()=>p.control('STOP')}>停止周期采集</button>:null}
   <span role="status">{s?{RUNNING:'已启用',STOPPED:'已停止',FAILED:'已暂停'}[s.state]:'未启动'}{s?' · 固定 v'+s.revision:''}</span>
  </div>
  <p className="workflow-runtime-boundary">每轮扫描完成后等待采集间隔；失败时暂停，恢复保留原扫描。每次启用或恢复最多处理20批。</p>
  {p.status?.mode==='DELEGATED_ENTITY'?<p className="workflow-runtime-boundary">后台授权最长15分钟；周期采集共用原授权和剩余批次，过期后需明确恢复。</p>:null}
  {s?<><p>已完成 {s.completedScans} 轮 / 已确认 {s.confirmedRecords} 条 · 本次已用 {s.sessionBatches}/20 批</p><p>最近完成：{s.lastSuccessAt?new Date(s.lastSuccessAt).toLocaleString():'尚未完成'} · 下次采集：{s.nextRunAt?new Date(s.nextRunAt).toLocaleString():s.state==='RUNNING'?'扫描中':'已暂停'}</p></>:null}
  {other?<p role="alert">周期任务使用 v{s!.revision}，请打开该版本管理。</p>:null}
  {s?.error?<p role="alert">{s.error==='TASK_CHANGED'?'扫描状态已变更，请读取当前任务。':runtimeFailureMessage(s.error)}</p>:null}
  {p.pending?<aside aria-label="周期控制结果待确认"><p>控制结果待确认，保留原请求标识。</p><button disabled={p.busy} onClick={p.query}>查询原周期回执</button>{p.missing?<><button disabled={p.busy} onClick={p.resend}>按原请求重新提交</button><button disabled={p.busy} onClick={p.dismiss}>放弃本次确认</button></>:null}</aside>:null}
  {p.receipt?<p role="status">原控制已确认：{p.receipt.operation==='STOP'?'停止':p.receipt.operation==='START'?'启动':'恢复'} · 代次 {p.receipt.schedule.generation}。当前状态单独读取。</p>:null}
  {p.error?<p role="alert">{p.error}</p>:null}
 </section>
}
