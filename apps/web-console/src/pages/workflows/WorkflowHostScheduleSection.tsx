import { useEffect,useRef,useState } from 'react'
import { hostScheduleStatus,hostScheduleControl,hostScheduleReceipt,HostScheduleError,type HostScheduleStatus,type HostScheduleCommand,type HostScheduleReceipt } from '../../api/workflow-host-schedules.ts'
import type { Entry } from '../../api/workflows.ts'
import { WorkflowHostSchedulePanel } from '../../components/workflows/WorkflowHostSchedulePanel.tsx'
import { usePlatformSession } from '../../state/platform-session.ts'
import { usePageActive,usePageCloseGuard } from '../../state/page-workspace.ts'

export function WorkflowHostScheduleSection({entry,names,onBusy}:{entry:Entry;names:{id:string;label:string}[];onBusy:(v:boolean)=>void}){
 const [open,setOpen]=useState(false),[status,setStatus]=useState<HostScheduleStatus|null>(null),[busy,setBusy]=useState(false),[error,setError]=useState(''),[missing,setMissing]=useState(false),[receipt,setReceipt]=useState<HostScheduleReceipt|null>(null),[interval,setInterval]=useState(60),[name,setName]=useState('')
 const request=useRef<AbortController|null>(null),pending=useRef<HostScheduleCommand|null>(null),writing=useRef(false),attempted=useRef(false),polling=useRef(false)
 const active=usePageActive(),ready=usePlatformSession(()=>{request.current?.abort();writing.current=false;pending.current=null;attempted.current=false;polling.current=false;setOpen(false);setStatus(null);setBusy(false);setReceipt(null);setMissing(false);setError('');setName('');setInterval(60);onBusy(false)})
 usePageCloseGuard(busy?{message:'周期采集请求正在处理。',blocked:true}:pending.current?{message:'周期控制结果待确认，请查询原回执。',blocked:true}:null)
 async function action(kind:'load'|'query'|'resend'|HostScheduleCommand['operation']){
  if(!ready||!active||writing.current||pending.current&&!['query','resend'].includes(kind)||kind==='resend'&&!missing)return
  request.current?.abort();const controller=new AbortController();request.current=controller;writing.current=true;setBusy(true);onBusy(true);setError('');const current=()=>request.current===controller&&!controller.signal.aborted
  try{
   if(kind==='load'){const value=await hostScheduleStatus(entry,controller.signal);if(current()){setStatus(value);if(value.schedule){setInterval(value.schedule.intervalSeconds);setName(value.schedule.settings.nameField)}polling.current=true}}
   else{
    if(['START','STOP','RESUME'].includes(kind)){const s=status?.schedule;pending.current={requestId:crypto.randomUUID(),id:entry.definition.id,revision:kind==='START'?entry.definition.revision:s?.revision??entry.definition.revision,digest:kind==='START'?entry.digest:s?.digest??entry.digest,settings:kind==='START'?{identityField:'entity_id',nameField:name}:s?.settings??{identityField:'entity_id',nameField:name},intervalSeconds:kind==='START'?interval:s?.intervalSeconds??interval,expectedGeneration:s?.generation??0,operation:kind as HostScheduleCommand['operation']};setMissing(false);setReceipt(null)}
    const command=pending.current;if(!command)return;polling.current=false
    const result=kind==='query'?await hostScheduleReceipt(entry,command,controller.signal):await hostScheduleControl(entry,command,controller.signal)
    if(current()){setReceipt(result);pending.current=null;setMissing(false);const value=await hostScheduleStatus(entry,controller.signal);if(current()){setStatus(value);if(value.schedule){setInterval(value.schedule.intervalSeconds);setName(value.schedule.settings.nameField)}polling.current=true}}
   }
  }catch(failure){if(current()){polling.current=false;if(failure instanceof HostScheduleError&&failure.status===403){pending.current=null;setStatus(null);setReceipt(null);setMissing(false)}if(failure instanceof HostScheduleError&&kind==='query'&&failure.status===404)setMissing(true);setError(failure instanceof Error?failure.message:'周期采集请求失败')}}
  finally{if(request.current===controller){writing.current=false;setBusy(false);onBusy(Boolean(pending.current))}}
 }
 const actionRef=useRef(action);actionRef.current=action
 useEffect(()=>{if(!open||!active||!ready){request.current?.abort();return}if(!attempted.current){attempted.current=true;void actionRef.current('load')}const timer=window.setInterval(()=>{if(polling.current&&status?.schedule?.state==='RUNNING'&&!pending.current&&!writing.current&&document.visibilityState==='visible')void actionRef.current('load')},5000);return()=>{clearInterval(timer);request.current?.abort()}},[open,active,ready,status?.schedule?.state])
 function dismiss(){if(!missing||writing.current)return;pending.current=null;setMissing(false);setStatus(null);onBusy(false);setError('已放弃本次确认；服务器操作未取消，请刷新状态。')}
 return <details className="workflow-runtime-section" open={open} onToggle={e=>setOpen(e.currentTarget.open)}><summary>周期主机采集</summary>{open?<WorkflowHostSchedulePanel entry={entry} status={status} busy={busy} pending={pending.current} missing={missing} receipt={receipt} error={error} interval={interval} name={name} names={names} intervalChange={setInterval} nameChange={setName} refresh={()=>void action('load')} control={op=>void action(op)} query={()=>void action('query')} resend={()=>void action('resend')} dismiss={dismiss}/>:null}</details>
}
