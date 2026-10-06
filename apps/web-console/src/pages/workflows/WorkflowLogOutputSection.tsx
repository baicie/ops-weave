import { WorkflowSampleRecoverySection } from './WorkflowSampleRecoverySection.tsx'
import { useEffect,useRef,useState } from 'react'
import { evaluateWorkflow,type Entry } from '../../api/workflows.ts'
import { logOutputCapability,logOutputHistory,writeLogOutput,readLogOutput,logOutputData,LogOutputError,type LogOutputCapability,type LogOutputCommand,type AnyLogOutputCommand,type LogOutputReceipt,type LogOutputData } from '../../api/workflow-log-outputs.ts'
import { WorkflowLogOutputPanel } from '../../components/workflows/WorkflowLogOutputPanel.tsx'
import { usePageActive,usePageCloseGuard } from '../../state/page-workspace.ts'
import { usePlatformSession } from '../../state/platform-session.ts'
export function WorkflowLogOutputSection({entry,sample,onBusy}:{entry:Entry;sample:string;onBusy:(value:boolean)=>void}){
 const [open,setOpen]=useState(false),[capability,setCapability]=useState<LogOutputCapability|null>(null),[busy,setBusy]=useState(false),[error,setError]=useState(''),[notice,setNotice]=useState(''),[missing,setMissing]=useState(false)
 const [receipt,setReceipt]=useState<LogOutputReceipt|null>(null),[records,setRecords]=useState<LogOutputReceipt[]>([]),[truncated,setTruncated]=useState(false),[data,setData]=useState<LogOutputData|null>(null)
 const [sampleBusy,setSampleBusy]=useState(false),[closedSampleId,setClosedSampleId]=useState<string|null>(null),sampleLocked=useRef(false)
 const request=useRef<AbortController|null>(null),writing=useRef(false),attempted=useRef(false),pending=useRef<{id:string;command?:AnyLogOutputCommand}|null>(null)
 const active=usePageActive(),ready=usePlatformSession(()=>{request.current?.abort();writing.current=false;attempted.current=false;pending.current=null;sampleLocked.current=false;setSampleBusy(false);setClosedSampleId(null);setBusy(false);onBusy(false);setOpen(false);setCapability(null);setReceipt(null);setRecords([]);setData(null);setError('');setNotice('');setMissing(false)})
 usePageCloseGuard(busy?{message:'日志请求正在处理，请等待结果后关闭。',blocked:true}:pending.current?{message:'日志写入结果待确认，请查询原回执或验证批次。',blocked:true}:null)
 function accept(result:LogOutputReceipt){setReceipt(result);setMissing(false);setRecords(rows=>[result,...rows.filter(r=>r.requestId!==result.requestId)].slice(0,20));pending.current=['CONFIRMED','FAILED'].includes(result.state)||closedSampleId===result.requestId?null:{id:result.requestId,command:pending.current?.command}}
 async function action(kind:'load'|'write'|'query'|'verify'|'data'|'resubmit'){
  if(!ready||!active||writing.current||sampleLocked.current||kind==='write'&&(pending.current||!capability?.available||!capability.writeAllowed)||kind==='resubmit'&&(!missing||!pending.current?.command)||kind==='verify'&&!capability?.writeAllowed||kind==='data'&&!capability?.readAllowed)return
  request.current?.abort();const controller=new AbortController();request.current=controller;writing.current=true;setBusy(true);onBusy(true);setError('');setNotice('');const current=()=>request.current===controller&&!controller.signal.aborted
  try{
   if(kind==='load'){const cap=await logOutputCapability(entry,controller.signal);if(!current())return;setCapability(cap);const history=await logOutputHistory(entry,controller.signal);if(current()){const selected=history.items.filter(r=>r.revision===entry.definition.revision&&r.digest===entry.digest);setRecords(selected);setTruncated(history.truncated||history.items.length!==selected.length)}}
   else if(kind==='write'){
    if(entry.definition.source.kind==='ZABBIX_LOG'){
     const command:AnyLogOutputCommand={requestId:crypto.randomUUID(),id:entry.definition.id,revision:entry.definition.revision,digest:entry.digest};pending.current={id:command.requestId,command};setReceipt(null);setData(null);setMissing(false);const result=await writeLogOutput(entry,command,controller.signal);if(current())accept(result)
    }else{
    const samples=JSON.parse(sample) as LogOutputCommand['samples'];const preview=await evaluateWorkflow(entry,samples,'',controller.signal);if(!current())return
    if(preview.sourceStatus!=='MANUAL_SAMPLE'||preview.receipt.accepted<1||preview.receipt.rejected!==0||preview.missingRaw!==0||preview.truncated)throw new Error('样本未通过校验，未发起日志写入')
    const command:LogOutputCommand={requestId:crypto.randomUUID(),id:entry.definition.id,revision:entry.definition.revision,digest:entry.digest,previewId:preview.receipt.id,samples};pending.current={id:command.requestId,command};setReceipt(null);setData(null);setMissing(false);const result=await writeLogOutput(entry,command,controller.signal);if(current())accept(result)
   }}else if(kind==='data'){if(receipt){const result=await logOutputData(receipt,controller.signal);if(current())setData(result)}}
   else{const original=pending.current;if(!original)return;const result=kind==='resubmit'?await writeLogOutput(entry,original.command!,controller.signal):await readLogOutput(entry,original.id,controller.signal,kind==='verify',original.command);if(current())accept(result)}
  }catch(failure){if(current()){
   if(kind==='load')setCapability(null)
   if(failure instanceof LogOutputError&&failure.status===403){pending.current=null;setReceipt(null);setRecords([]);setData(null);setCapability(null);setMissing(false)}
   if(failure instanceof LogOutputError&&failure.status===404&&kind==='query')setMissing(Boolean(pending.current?.command))
   setError(failure instanceof Error?failure.message:'日志输出请求失败')
  }}finally{if(request.current===controller){writing.current=false;setBusy(false);onBusy(Boolean(pending.current)||sampleLocked.current)}}
 }
 const actionRef=useRef(action);actionRef.current=action
 useEffect(()=>{if(!open||!active||!ready){request.current?.abort();return}if(!attempted.current){attempted.current=true;void actionRef.current('load')}return()=>request.current?.abort()},[open,active,ready])
 function select(result:LogOutputReceipt){if(writing.current||pending.current||sampleLocked.current)return;setReceipt(result);setData(null);setError('');setMissing(false);pending.current=['PENDING','UNKNOWN'].includes(result.state)&&closedSampleId!==result.requestId?{id:result.requestId}:null;onBusy(Boolean(pending.current)||sampleLocked.current)}
 function dismiss(){if(writing.current||!missing)return;pending.current=null;setMissing(false);setReceipt(null);setData(null);setError('');setNotice('已放弃本次确认；服务器写入未取消，请刷新记录核对。');onBusy(false)}
 return <details className="workflow-runtime-section" open={open} onToggle={event=>{if(event.target===event.currentTarget){if(!event.currentTarget.open&&sampleLocked.current)event.currentTarget.open=true;else setOpen(event.currentTarget.open)}}}><summary>日志写入与回读</summary>{open?<><WorkflowLogOutputPanel capability={capability} busy={busy||sampleBusy} pendingId={pending.current?.id??null} missing={missing} receipt={receipt} records={records} truncated={truncated} data={data} error={error} notice={notice} write={()=>void action('write')} query={()=>void action('query')} verify={()=>void action('verify')} read={()=>void action('data')} refresh={()=>void action('load')} resubmit={()=>void action('resubmit')} dismiss={dismiss} select={select}/>{receipt?.state==='UNKNOWN'?<WorkflowSampleRecoverySection key={receipt.requestId} entry={entry} kind="LOG_SAMPLE" proof={{batchId:receipt.requestId,batchDigest:receipt.batchDigest,updatedAt:receipt.updatedAt,uncertainRecords:receipt.accepted}} onClosed={()=>{setClosedSampleId(receipt.requestId);pending.current=null;onBusy(sampleLocked.current)}} onLocked={value=>{sampleLocked.current=value;setSampleBusy(value);onBusy(value||Boolean(pending.current))}}/>:null}</>:null}</details>
}
