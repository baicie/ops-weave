import {useEffect,useRef,useState} from 'react'
import type {Entry} from '../../api/workflows.ts'
import {ReplayError,replayWindow,replayPlans,replayPlan,createReplay,executeReplay,replayReceipt,replayExecution,verifyReplay,replayData,type ReplayPlan,type ReplayReceipt,type ReplayCommand,type ReplayExecute} from '../../api/workflow-log-replay.ts'
import type {LogStreamData} from '../../api/workflow-log-streams.ts'
import {WorkflowLogStreamRecords} from '../../components/workflows/WorkflowLogStreamRecords.tsx'
import {WorkflowLogReplayPanel} from '../../components/workflows/WorkflowLogReplayPanel.tsx'
import {usePageActive,usePageCloseGuard} from '../../state/page-workspace.ts'
import {usePlatformSession} from '../../state/platform-session.ts'

const initial=()=>{const d=new Date(Math.floor((Date.now()-120000)/60000)*60000);return new Date(d.getTime()-d.getTimezoneOffset()*60000).toISOString().slice(0,19)}
type Pending={kind:'prepare';command:ReplayCommand}|{kind:'execute';command:ReplayExecute;plan:ReplayPlan}
export function WorkflowLogReplaySection({entry,visible,onLocked}:{entry:Entry;visible:boolean;onLocked:(v:boolean)=>void}){
 const [open,setOpen]=useState(false),[start,setStart]=useState(initial),[lookup,setLookup]=useState(''),[plans,setPlans]=useState<ReplayPlan[]>([]),[truncated,setTruncated]=useState(false),[plan,setPlan]=useState<ReplayPlan|null>(null),[receipt,setReceipt]=useState<ReplayReceipt|null>(null),[loaded,setLoaded]=useState(false),[busy,setBusy]=useState(false),[missing,setMissing]=useState(false),[error,setError]=useState('')
 const [data,setData]=useState<LogStreamData|null>(null),[dataCursors,setDataCursors]=useState<number[]>([])
 const request=useRef<AbortController|null>(null),pending=useRef<Pending|null>(null),attempted=useRef(false),active=usePageActive()
 const ready=usePlatformSession(()=>{request.current?.abort();request.current=null;pending.current=null;attempted.current=false;setOpen(false);setPlans([]);setData(null);setDataCursors([]);setPlan(null);setReceipt(null);setLoaded(false);setBusy(false);setMissing(false);setLookup('');setStart(initial());setError('');onLocked(false)})
 usePageCloseGuard(busy||pending.current?{blocked:true,message:'重放请求结果待确认，请查询原请求。'}:null)
 async function action(kind:'list'|'prepare'|'execute'|'query'|'resend'|'select'|'verify'|'data',id?:string,cursor=-1){
  if(!ready||!active||!visible||!open||request.current||pending.current&&!['query','resend'].includes(kind)||kind==='resend'&&!missing)return
  if(kind==='prepare'){setData(null);setDataCursors([]);try{pending.current={kind:'prepare',command:replayWindow(crypto.randomUUID(),entry,start)}}catch(failure){setError(failure instanceof Error?failure.message:'时间范围无效');return}}
  if(kind==='execute'){if(!plan?.proof||receipt||plan.state!=='READY')return;pending.current={kind:'execute',plan,command:{requestId:crypto.randomUUID(),planId:plan.requestId,inputDigest:plan.proof.inputDigest,batchDigest:plan.proof.batchDigest}}}
  const original=pending.current,c=new AbortController();request.current=c;setBusy(true);onLocked(true);setError('');const current=()=>request.current===c&&!c.signal.aborted
  try{
   if(kind==='list'){const result=await replayPlans(entry,c.signal);if(current()){setPlans(result.items);setTruncated(result.truncated);setLoaded(true)}}
   else if(['prepare','execute','query','resend'].includes(kind)&&original){
    if(original.kind==='prepare'){const result=kind==='query'?await replayPlan(entry,original.command.requestId,c.signal,original.command):await createReplay(entry,original.command,c.signal);if(current()){setPlan(result);setReceipt(null);setPlans(rows=>[result,...rows.filter(r=>r.requestId!==result.requestId)].slice(0,20));pending.current=null;setMissing(false)}}
    else {const result=kind==='query'?await replayReceipt(entry,original.plan,original.command,c.signal):await executeReplay(entry,original.plan,original.command,c.signal);if(current()){setPlan(original.plan);setReceipt(result);pending.current=null;setMissing(false)}}
   }else if(kind==='select'){setData(null);setDataCursors([]);const result=await replayPlan(entry,id??lookup,c.signal);const output=await replayExecution(entry,result,c.signal);if(current()){setPlan(result);setReceipt(output)}}
   else if(kind==='data'&&plan&&receipt){const output=await replayData(plan,cursor,c.signal);if(current()){setData(output);setDataCursors(rows=>{const prior=rows.indexOf(cursor);return prior>=0?rows.slice(0,prior+1):[...rows,cursor]})}}
   else if(kind==='verify'&&plan&&receipt){const output=await verifyReplay(entry,plan,receipt,c.signal);if(current())setReceipt(output)}
  }catch(failure){if(current()){if(failure instanceof ReplayError&&failure.status===404&&kind==='query'&&pending.current)setMissing(true);else if(failure instanceof ReplayError&&[400,409].includes(failure.status)&&['prepare','execute','resend'].includes(kind)){pending.current=null;setMissing(false)}setError(failure instanceof Error?failure.message:'重放请求失败')}}
  finally{if(request.current===c){request.current=null;setBusy(false);onLocked(Boolean(pending.current))}}
 }
 const actionRef=useRef(action);actionRef.current=action
 useEffect(()=>{if(open&&visible&&active&&ready&&!attempted.current){attempted.current=true;void actionRef.current('list')}return()=>{if(request.current){request.current.abort();request.current=null;setBusy(false);onLocked(Boolean(pending.current))}}},[open,visible,active,ready])
 function dismiss(){if(!missing||busy)return;pending.current=null;setMissing(false);setError('已放弃本地确认；服务器请求未取消，请刷新重放记录。');onLocked(false)}
 return <details className="workflow-metric-replay" open={open} onToggle={e=>{if(e.target!==e.currentTarget)return;if(!e.currentTarget.open&&(busy||pending.current))e.currentTarget.open=true;else setOpen(e.currentTarget.open)}}><summary>日志历史重放</summary>{open&&visible&&active?<WorkflowLogReplayPanel entry={entry} start={start} lookup={lookup} plans={plans} truncated={truncated} plan={plan} receipt={receipt} busy={busy} pending={Boolean(pending.current)} missing={missing} error={error} loaded={loaded} changeStart={setStart} changeLookup={setLookup} prepare={()=>void action('prepare')} execute={()=>void action('execute')} query={()=>void action('query')} resend={()=>void action('resend')} dismiss={dismiss} select={id=>void action('select',id)} lookupPlan={()=>void action('select')} refresh={()=>void action('list')} verify={()=>void action('verify')} readData={()=>void action('data',undefined,-1)}>{data?<WorkflowLogStreamRecords title="重放日志" data={data} busy={busy||Boolean(pending.current)} previous={dataCursors.length>1?()=>void action('data',undefined,dataCursors[dataCursors.length-2]):null} next={data.nextIndex!==null?()=>void action('data',undefined,data.nextIndex!):null}/>:null}</WorkflowLogReplayPanel>:null}</details>
}
