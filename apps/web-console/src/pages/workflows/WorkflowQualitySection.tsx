import { useEffect,useRef,useState } from 'react'
import { qualityReport,qualityBatch,QualityError,type QualityReport,type QualityBatch } from '../../api/workflow-quality.ts'
import type { Entry } from '../../api/workflows.ts'
import { WorkflowQualityPanel } from '../../components/workflows/WorkflowQualityPanel.tsx'
import { usePlatformSession } from '../../state/platform-session.ts'
import { usePageActive } from '../../state/page-workspace.ts'
import { WorkflowRecoverySection } from './WorkflowRecoverySection.tsx'
import { WorkflowQualityAlertsSection } from './WorkflowQualityAlertsSection.tsx'
import { WorkflowDiagnosticsSection } from './WorkflowDiagnosticsSection.tsx'

export function WorkflowQualitySection({entry,onBusy}:{entry:Entry;onBusy:(v:boolean)=>void}){
 const [open,setOpen]=useState(false),[report,setReport]=useState<QualityReport|null>(null),[selected,setSelected]=useState<QualityBatch|null>(null),[busy,setBusy]=useState(false),[error,setError]=useState(''),[lookup,setLookup]=useState('')
 const request=useRef<AbortController|null>(null),attempted=useRef(false),recoveryLocked=useRef(false),alertsLocked=useRef(false),active=usePageActive()
 const ready=usePlatformSession(()=>{request.current?.abort();request.current=null;attempted.current=false;recoveryLocked.current=false;alertsLocked.current=false;onBusy(false);setOpen(false);setReport(null);setSelected(null);setLookup('');setBusy(false);setError('')})
 async function read(kind:'report'|'batch'){
  if(!ready||!active||!open||request.current||recoveryLocked.current||alertsLocked.current)return;const controller=new AbortController();request.current=controller;setBusy(true);setError('');const current=()=>request.current===controller&&!controller.signal.aborted
  try{if(kind==='report'){const next=await qualityReport(entry,controller.signal);if(current()){setReport(next);setSelected(null)}}else{setSelected(null);const next=await qualityBatch(entry,lookup,controller.signal);if(current())setSelected(next)}}
  catch(failure){if(current()){if(failure instanceof QualityError&&failure.status===403){setReport(null);setSelected(null)}setError(failure instanceof Error?failure.message:'运行质量读取失败')}}
  finally{if(request.current===controller){request.current=null;setBusy(false)}}
 }
 const readRef=useRef(read);readRef.current=read
 useEffect(()=>{if(open&&active&&ready&&!attempted.current){attempted.current=true;void readRef.current('report')}return()=>{request.current?.abort();request.current=null;setBusy(false)}},[open,active,ready])
 return <details className="workflow-runtime-section" open={open} onToggle={e=>{if(e.target===e.currentTarget){if(!e.currentTarget.open&&(recoveryLocked.current||alertsLocked.current))e.currentTarget.open=true;else setOpen(e.currentTarget.open)}}}><summary>运行质量与异常</summary>{open?<>{report?.task?.pendingBatchId&&(report.task.state==='ABANDONED'||['FAILED','STOPPED'].includes(report.task.state)&&(recoveryLocked.current||report.batches.some(b=>b.id===report.task?.pendingBatchId&&b.state==='UNKNOWN')||selected?.id===report.task.pendingBatchId&&selected.state==='UNKNOWN'))?<WorkflowRecoverySection entry={entry} report={report} refresh={()=>void read('report')} onLocked={v=>{recoveryLocked.current=v;onBusy(v||alertsLocked.current)}}/>:null}<WorkflowQualityAlertsSection entry={entry} onLocked={v=>{alertsLocked.current=v;onBusy(v||recoveryLocked.current)}}/><WorkflowQualityPanel report={report} selected={selected} busy={busy} error={error} lookup={lookup} setLookup={setLookup} refresh={()=>void read('report')} find={()=>void read('batch')} select={setSelected}/>{(['ZABBIX_METRIC','ZABBIX_LOG'].includes(entry.definition.source.kind)||entry.definition.source.kind==='ZABBIX_HOST'&&entry.definition.source.configuration!==undefined)?<WorkflowDiagnosticsSection entry={entry}/>:null}</>:null}</details>
}
