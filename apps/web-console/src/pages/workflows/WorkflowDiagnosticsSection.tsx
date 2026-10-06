import {useEffect,useRef,useState} from 'react'
import {DiagnosticError,diagnosticReport,diagnosticObservation,type DiagnosticReport,type DiagnosticObservation} from '../../api/workflow-diagnostics.ts'
import type {Entry} from '../../api/workflows.ts'
import {WorkflowDiagnosticsPanel} from '../../components/workflows/WorkflowDiagnosticsPanel.tsx'
import {usePlatformSession} from '../../state/platform-session.ts'
import {usePageActive} from '../../state/page-workspace.ts'
export function WorkflowDiagnosticsSection({entry}:{entry:Entry}){
 const [open,setOpen]=useState(false),[report,setReport]=useState<DiagnosticReport|null>(null),[selected,setSelected]=useState<DiagnosticObservation|null>(null),[busy,setBusy]=useState(false),[error,setError]=useState(''),[lookup,setLookup]=useState('')
 const request=useRef<AbortController|null>(null),attempted=useRef(false),active=usePageActive()
 const ready=usePlatformSession(()=>{request.current?.abort();request.current=null;attempted.current=false;setOpen(false);setReport(null);setSelected(null);setBusy(false);setError('');setLookup('')})
 async function read(kind:'report'|'observation'){
  if(!ready||!active||!open||request.current)return;const controller=new AbortController();request.current=controller;setBusy(true);setError('');const current=()=>request.current===controller&&!controller.signal.aborted
  try{if(kind==='report'){const next=await diagnosticReport(entry,controller.signal);if(current()){setReport(next);setSelected(null)}}else{setSelected(null);const next=await diagnosticObservation(entry,lookup,controller.signal);if(current())setSelected(next)}}
  catch(failure){if(current()){if(failure instanceof DiagnosticError&&failure.status===403){setReport(null);setSelected(null)}setError(failure instanceof Error?failure.message:'检查统计读取失败')}}
  finally{if(request.current===controller){request.current=null;setBusy(false)}}
 }
 const readRef=useRef(read);readRef.current=read
 useEffect(()=>{if(open&&active&&ready&&!attempted.current){attempted.current=true;void readRef.current('report')}return()=>{request.current?.abort();request.current=null;setBusy(false)}},[open,active,ready])
 return <details className="workflow-runtime-section" open={open} onToggle={e=>setOpen(e.currentTarget.open)}><summary>检查统计</summary>{open&&active?<WorkflowDiagnosticsPanel report={report} selected={selected} busy={busy} error={error} lookup={lookup} setLookup={setLookup} refresh={()=>void read('report')} find={()=>void read('observation')} select={setSelected}/>:null}</details>
}
