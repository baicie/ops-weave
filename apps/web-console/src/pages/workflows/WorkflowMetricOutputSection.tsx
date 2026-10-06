import { WorkflowSampleRecoverySection } from './WorkflowSampleRecoverySection.tsx'
import { useEffect, useRef, useState } from 'react'
import { evaluateWorkflow, type Entry } from '../../api/workflows.ts'
import type { MappingDefinition } from '../../api/metric-mappings.ts'
import { metricOutputCapability,metricOutputHistory,writeMetricOutput,readMetricOutput,metricOutputPoints,MetricOutputError,type MetricOutputCommand,type MetricOutputReceipt,type MetricOutputData } from '../../api/workflow-metric-outputs.ts'
import { WorkflowMetricOutputPanel } from '../../components/workflows/WorkflowMetricOutputPanel.tsx'
import { usePageActive,usePageCloseGuard } from '../../state/page-workspace.ts'
import { usePlatformSession } from '../../state/platform-session.ts'

export function WorkflowMetricOutputSection({entry,mapping,onBusy}:{entry:Entry;mapping:MappingDefinition|undefined;onBusy:(value:boolean)=>void}){
 const [open,setOpen]=useState(false),[available,setAvailable]=useState<boolean|null>(null),[busy,setBusy]=useState(false),[error,setError]=useState(''),[notice,setNotice]=useState(''),[missing,setMissing]=useState(false)
 const [receipt,setReceipt]=useState<MetricOutputReceipt|null>(null),[records,setRecords]=useState<MetricOutputReceipt[]>([]),[truncated,setTruncated]=useState(false),[data,setData]=useState<MetricOutputData|null>(null)
 const [sampleBusy,setSampleBusy]=useState(false),[closedSampleId,setClosedSampleId]=useState<string|null>(null),sampleLocked=useRef(false)
 const request=useRef<AbortController|null>(null),writing=useRef(false),attempted=useRef(false),pending=useRef<{id:string;command?:MetricOutputCommand}|null>(null)
 const active=usePageActive(),ready=usePlatformSession(()=>{request.current?.abort();writing.current=false;attempted.current=false;pending.current=null;sampleLocked.current=false;setSampleBusy(false);setClosedSampleId(null);setBusy(false);onBusy(false);setOpen(false);setAvailable(null);setReceipt(null);setRecords([]);setData(null);setError('');setNotice('');setMissing(false)})
 usePageCloseGuard(busy?{message:'指标请求正在处理，请等待结果后关闭。',blocked:true}:pending.current?{message:'指标写入结果待确认，请查询原回执或验证批次。',blocked:true}:null)
 function accept(result:MetricOutputReceipt){setReceipt(result);setMissing(false);setRecords(rows=>[result,...rows.filter(r=>r.requestId!==result.requestId)].slice(0,20));if(['CONFIRMED','FAILED'].includes(result.state)||closedSampleId===result.requestId)pending.current=null;else pending.current={id:result.requestId,command:pending.current?.command}}
 async function action(kind:'load'|'write'|'query'|'verify'|'points'|'resubmit'){
  if(!ready||!active||writing.current||sampleLocked.current||kind==='write'&&(pending.current||available!==true||!mapping)||kind==='resubmit'&&(!missing||!pending.current?.command))return
  request.current?.abort();const controller=new AbortController();request.current=controller;writing.current=true;setBusy(true);onBusy(true);setError('');setNotice('')
  const current=()=>request.current===controller&&!controller.signal.aborted
  try{
   if(kind==='load'){const capability=await metricOutputCapability(controller.signal);if(!current())return;setAvailable(capability);const history=await metricOutputHistory(entry,controller.signal);if(current()){const selected=history.items.filter(r=>r.revision===entry.definition.revision&&r.digest===entry.digest);setRecords(selected);setTruncated(history.truncated||history.items.length!==selected.length)}}
   else if(kind==='write'){
    const preview=await evaluateWorkflow(entry,undefined,'',controller.signal,mapping);if(!current())return
    if(preview.sourceStatus!=='SUCCEEDED'||preview.missingRaw!==0||preview.receipt.accepted<1||preview.receipt.rejected!==0)throw new Error('来源样本不完整或转换失败，未发起写入')
    const samples=preview.evaluation.rows.map(row=>{const values=row.steps[0].values;if(Object.keys(values).length!==3||!['timestamp','sourceKey','value'].every(key=>typeof values[key]==='string'))throw new Error('原始指标样本不符合写入契约');return {timestamp:values.timestamp as string,sourceKey:values.sourceKey as string,value:values.value as string}})
    const command:MetricOutputCommand={requestId:crypto.randomUUID(),id:entry.definition.id,revision:entry.definition.revision,digest:entry.digest,previewId:preview.receipt.id,samples};pending.current={id:command.requestId,command};setReceipt(null);setData(null);setMissing(false)
    const result=await writeMetricOutput(entry,command,controller.signal);if(current())accept(result)
   }else if(kind==='points'){if(receipt){const result=await metricOutputPoints(receipt,controller.signal);if(current())setData(result)}}
   else{const original=pending.current;if(!original)return;const result=kind==='resubmit'?await writeMetricOutput(entry,original.command!,controller.signal):await readMetricOutput(entry,original.id,controller.signal,kind==='verify',original.command);if(current())accept(result)}
  }catch(failure){if(current()){
   if(kind==='load')setAvailable(null)
   if(failure instanceof MetricOutputError&&failure.status===403){pending.current=null;setReceipt(null);setRecords([]);setData(null);setAvailable(null);setMissing(false)}
   if(failure instanceof MetricOutputError&&failure.status===404&&kind==='query')setMissing(Boolean(pending.current?.command))
   setError(failure instanceof Error?failure.message:'指标输出请求失败')
  }}finally{if(request.current===controller){writing.current=false;setBusy(false);onBusy(Boolean(pending.current)||sampleLocked.current)}}
 }
 const actionRef=useRef(action);actionRef.current=action
 useEffect(()=>{if(!open||!active||!ready){request.current?.abort();return}if(!attempted.current){attempted.current=true;void actionRef.current('load')}return()=>request.current?.abort()},[open,active,ready])
 function dismiss(){if(writing.current||!missing)return;pending.current=null;setMissing(false);setReceipt(null);setData(null);setError('');setNotice('已放弃本次确认；服务器写入未取消，请刷新记录核对。');onBusy(false)}
 function select(result:MetricOutputReceipt){if(writing.current||pending.current||sampleLocked.current)return;setReceipt(result);setData(null);setError('');setMissing(false);pending.current=['PENDING','UNKNOWN'].includes(result.state)&&closedSampleId!==result.requestId?{id:result.requestId}:null;onBusy(Boolean(pending.current)||sampleLocked.current)}
 return <details className="workflow-runtime-section" open={open} onToggle={event=>{if(event.target===event.currentTarget){if(!event.currentTarget.open&&sampleLocked.current)event.currentTarget.open=true;else setOpen(event.currentTarget.open)}}}><summary>指标输出与回读</summary>{open?<><WorkflowMetricOutputPanel entry={entry} available={available} busy={busy||sampleBusy} pendingId={pending.current?.id??null} missing={missing} receipt={receipt} records={records} truncated={truncated} data={data} error={error} notice={notice} write={()=>void action('write')} query={()=>void action('query')} verify={()=>void action('verify')} points={()=>void action('points')} refresh={()=>void action('load')} resubmit={()=>void action('resubmit')} dismiss={dismiss} select={select}/>{receipt?.state==='UNKNOWN'?<WorkflowSampleRecoverySection key={receipt.requestId} entry={entry} kind="METRIC_SAMPLE" proof={{batchId:receipt.requestId,batchDigest:receipt.batchDigest,updatedAt:receipt.updatedAt,uncertainRecords:receipt.timestamps.length}} onClosed={()=>{setClosedSampleId(receipt.requestId);pending.current=null;onBusy(sampleLocked.current)}} onLocked={value=>{sampleLocked.current=value;setSampleBusy(value);onBusy(value||Boolean(pending.current))}}/>:null}</>:null}</details>
}
