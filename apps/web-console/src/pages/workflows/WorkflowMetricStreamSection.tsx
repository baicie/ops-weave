import { useEffect, useRef, useState } from 'react'
import { metricStreamStatus,metricStreamControl,metricStreamReceipt,metricStreamVerify,MetricStreamError,type MetricStreamStatus,type MetricStreamCommand,type MetricStreamReceipt } from '../../api/workflow-metric-streams.ts'
import type { Entry } from '../../api/workflows.ts'
import { WorkflowMetricStreamPanel } from '../../components/workflows/WorkflowMetricStreamPanel.tsx'
import { usePlatformSession } from '../../state/platform-session.ts'
import { usePageActive,usePageCloseGuard } from '../../state/page-workspace.ts'

export function WorkflowMetricStreamSection({entry,onBusy}:{entry:Entry;onBusy:(v:boolean)=>void}){
 const [open,setOpen]=useState(false),[status,setStatus]=useState<MetricStreamStatus|null>(null),[busy,setBusy]=useState(false),[error,setError]=useState(''),[missing,setMissing]=useState(false),[receipt,setReceipt]=useState<MetricStreamReceipt|null>(null)
 const request=useRef<AbortController|null>(null),pending=useRef<MetricStreamCommand|null>(null),writing=useRef(false),attempted=useRef(false),polling=useRef(false)
 const active=usePageActive(),ready=usePlatformSession(()=>{request.current?.abort();writing.current=false;pending.current=null;attempted.current=false;polling.current=false;setOpen(false);setStatus(null);setBusy(false);setReceipt(null);setMissing(false);setError('');onBusy(false)})
 usePageCloseGuard(busy?{message:'持续采集请求正在处理。',blocked:true}:pending.current?{message:'持续采集控制结果待确认，请查询原回执。',blocked:true}:null)
 async function action(kind:'load'|'query'|'resend'|'verify'|MetricStreamCommand['operation']){
  if(!ready||!active||writing.current||pending.current&&!['query','resend'].includes(kind)||kind==='resend'&&!missing)return
  request.current?.abort();const controller=new AbortController();request.current=controller;writing.current=true;setBusy(true);onBusy(true);setError('');const current=()=>request.current===controller&&!controller.signal.aborted
  try{
   if(kind==='load'){const next=await metricStreamStatus(entry,controller.signal);if(current()){setStatus(next);polling.current=true}}
   else if(kind==='verify'){if(status?.task?.pendingBatchId){const next=await metricStreamVerify(entry,status.task.pendingBatchId,controller.signal);if(current()){setStatus(next);polling.current=true}}}
   else{
    if(['START','STOP','RESUME'].includes(kind)){pending.current={requestId:crypto.randomUUID(),id:entry.definition.id,revision:entry.definition.revision,digest:entry.digest,expectedGeneration:status?.control?.generation??status?.task?.generation??0,operation:kind as MetricStreamCommand['operation']};setMissing(false);setReceipt(null)}
    const command=pending.current;if(!command)return;polling.current=false
    const result=kind==='query'?await metricStreamReceipt(entry,command,controller.signal):await metricStreamControl(entry,command,controller.signal)
    if(current()){setReceipt(result);pending.current=null;setMissing(false);const next=await metricStreamStatus(entry,controller.signal);if(current()){setStatus(next);polling.current=true}}
   }
  }catch(failure){if(current()){polling.current=false;if(failure instanceof MetricStreamError&&failure.status===403){pending.current=null;setStatus(null);setReceipt(null);setMissing(false)}if(failure instanceof MetricStreamError&&failure.status===404&&kind==='query')setMissing(true);setError(failure instanceof Error?failure.message:'持续采集请求失败')}}
  finally{if(request.current===controller){writing.current=false;setBusy(false);onBusy(Boolean(pending.current))}}
 }
 const actionRef=useRef(action);actionRef.current=action
 useEffect(()=>{if(!open||!active||!ready){request.current?.abort();return}if(!attempted.current){attempted.current=true;void actionRef.current('load')}const timer=window.setInterval(()=>{if(polling.current&&status?.task?.state==='RUNNING'&&!pending.current&&!writing.current)void actionRef.current('load')},5000);return()=>{clearInterval(timer);request.current?.abort()}},[open,active,ready,status?.task?.state])
 function dismiss(){if(!missing||writing.current)return;pending.current=null;setMissing(false);onBusy(false);setError('已放弃本次确认；服务器操作未取消，请刷新状态。')}
 return <details className="workflow-runtime-section" open={open} onToggle={e=>setOpen(e.currentTarget.open)}><summary>持续采集</summary>{open?<WorkflowMetricStreamPanel entry={entry} status={status} busy={busy} pending={pending.current} missing={missing} receipt={receipt} error={error} refresh={()=>void action('load')} control={op=>void action(op)} query={()=>void action('query')} resend={()=>void action('resend')} verify={()=>void action('verify')} dismiss={dismiss}/>:null}</details>
}
