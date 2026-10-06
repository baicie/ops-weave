import { useEffect,useRef,useState } from 'react'
import { logStreamStatus,logStreamControl,logStreamReceipt,logStreamVerify,logStreamData,LogStreamError,type LogStreamStatus,type LogStreamCommand,type LogStreamReceipt,type LogStreamBatch,type LogStreamData } from '../../api/workflow-log-streams.ts'
import type { Entry } from '../../api/workflows.ts'
import { WorkflowLogStreamPanel } from '../../components/workflows/WorkflowLogStreamPanel.tsx'
import { WorkflowLogStreamRecords } from '../../components/workflows/WorkflowLogStreamRecords.tsx'
import { usePlatformSession } from '../../state/platform-session.ts'
import { usePageActive,usePageCloseGuard } from '../../state/page-workspace.ts'

export function WorkflowLogStreamSection({entry,onBusy}:{entry:Entry;onBusy:(v:boolean)=>void}){
 const [open,setOpen]=useState(false),[status,setStatus]=useState<LogStreamStatus|null>(null),[busy,setBusy]=useState(false),[error,setError]=useState(''),[missing,setMissing]=useState(false),[receipt,setReceipt]=useState<LogStreamReceipt|null>(null),[data,setData]=useState<LogStreamData|null>(null)
 const request=useRef<AbortController|null>(null),pending=useRef<LogStreamCommand|null>(null),writing=useRef(false),attempted=useRef(false),polling=useRef(false),selected=useRef<LogStreamBatch|null>(null),pages=useRef<number[]>([-1])
 const active=usePageActive(),ready=usePlatformSession(()=>{request.current?.abort();writing.current=false;pending.current=null;attempted.current=false;polling.current=false;selected.current=null;pages.current=[-1];setOpen(false);setStatus(null);setBusy(false);setReceipt(null);setData(null);setMissing(false);setError('');onBusy(false)})
 usePageCloseGuard(busy?{message:'日志采集请求正在处理。',blocked:true}:pending.current?{message:'日志采集控制结果待确认，请查询原回执。',blocked:true}:null)
 async function action(kind:'load'|'query'|'resend'|'verify'|'data'|LogStreamCommand['operation'],batch?:LogStreamBatch,afterIndex=-1){
  if(!ready||!active||!open||writing.current||pending.current&&!['query','resend'].includes(kind)||kind==='resend'&&!missing)return
  request.current?.abort();const controller=new AbortController();request.current=controller;writing.current=true;setBusy(true);onBusy(true);setError('');const current=()=>request.current===controller&&!controller.signal.aborted
  try{
   if(kind==='load'){const next=await logStreamStatus(entry,controller.signal);if(current()){setStatus(next);polling.current=true}}
   else if(kind==='data'&&batch){setData(null);const next=await logStreamData(entry,batch,afterIndex,controller.signal);if(current()){selected.current=batch;const prior=pages.current.indexOf(afterIndex);pages.current=prior<0?[...pages.current,afterIndex]:pages.current.slice(0,prior+1);setData(next)}}
   else if(kind==='verify'){if(status?.task?.pendingBatchId){const next=await logStreamVerify(entry,status.task.pendingBatchId,controller.signal);if(current()){setStatus(next);polling.current=true}}}
   else{
    if(['START','STOP','RESUME'].includes(kind)){pending.current={requestId:crypto.randomUUID(),id:entry.definition.id,revision:entry.definition.revision,digest:entry.digest,expectedGeneration:status?.control?.generation??status?.task?.generation??0,operation:kind as LogStreamCommand['operation']};setMissing(false);setReceipt(null);setData(null)}
    const command=pending.current;if(!command)return;polling.current=false
    const result=kind==='query'?await logStreamReceipt(entry,command,controller.signal):await logStreamControl(entry,command,controller.signal)
    if(current()){setReceipt(result);pending.current=null;setMissing(false);const next=await logStreamStatus(entry,controller.signal);if(current()){setStatus(next);polling.current=true}}
   }
  }catch(failure){if(current()){polling.current=false;if(failure instanceof LogStreamError&&failure.status===403){pending.current=null;setStatus(null);setReceipt(null);setData(null);selected.current=null;setMissing(false)}if(failure instanceof LogStreamError&&failure.status===404&&kind==='query')setMissing(true);setError(failure instanceof Error?failure.message:'日志采集请求失败')}}
  finally{if(request.current===controller){writing.current=false;setBusy(false);onBusy(Boolean(pending.current))}}
 }
 const actionRef=useRef(action);actionRef.current=action
 useEffect(()=>{if(!open||!active||!ready){request.current?.abort();setData(null);selected.current=null;pages.current=[-1];return}if(!attempted.current){attempted.current=true;void actionRef.current('load')}const timer=window.setInterval(()=>{if(polling.current&&status?.task?.state==='RUNNING'&&!pending.current&&!writing.current)void actionRef.current('load')},5000);return()=>{clearInterval(timer);request.current?.abort()}},[open,active,ready,status?.task?.state])
 function dismiss(){if(!missing||writing.current)return;pending.current=null;setMissing(false);onBusy(false);setError('已放弃本次确认；服务器操作未取消，请刷新状态。')}
 function read(batch:LogStreamBatch){if(writing.current||pending.current)return;selected.current=batch;pages.current=[-1];void action('data',batch)}
 return <details className="workflow-runtime-section" open={open} onToggle={e=>setOpen(e.currentTarget.open)}><summary>持续日志采集</summary>{open&&active?<><WorkflowLogStreamPanel entry={entry} status={status} busy={busy} pending={pending.current} missing={missing} receipt={receipt} error={error} refresh={()=>void action('load')} control={op=>void action(op)} query={()=>void action('query')} resend={()=>void action('resend')} verify={()=>void action('verify')} dismiss={dismiss} read={read}/>{data?<div className="workflow-log-output"><WorkflowLogStreamRecords data={data} busy={busy} previous={pages.current.length>1?()=>void action('data',selected.current!,pages.current.at(-2)!):null} next={data.nextIndex!==null?()=>void action('data',selected.current!,data.nextIndex!):null}/></div>:null}</>:null}</details>
}
