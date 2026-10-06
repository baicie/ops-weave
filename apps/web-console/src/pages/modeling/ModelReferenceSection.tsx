import { useEffect,useRef,useState } from 'react'
import { readModelReferences,ModelImpactError,type ModelReferenceReport } from '../../api/model-impact.ts'
import type { ModelRef } from '../../api/model-catalog.ts'
import { ModelReferenceTable } from '../../components/modeling/ModelReferenceTable.tsx'
import { usePlatformSession } from '../../state/platform-session.ts'
import { usePageActive } from '../../state/page-workspace.ts'

export function ModelReferenceSection({reference,digest}:{reference:ModelRef;digest?:string}){
 const [open,setOpen]=useState(false),[report,setReport]=useState<ModelReferenceReport|null>(null),[busy,setBusy]=useState(false),[error,setError]=useState(''),request=useRef<AbortController|null>(null),attempted=useRef(false)
 const active=usePageActive(),ready=usePlatformSession(()=>{request.current?.abort();attempted.current=false;setOpen(false);setReport(null);setError('');setBusy(false)})
 async function read(){if(!ready||!active||request.current)return;const controller=new AbortController();request.current=controller;setBusy(true);setError('');setReport(null);try{const value=await readModelReferences(reference,controller.signal,digest);if(request.current===controller&&!controller.signal.aborted)setReport(value)}catch(failure){if(request.current===controller&&!controller.signal.aborted){if(failure instanceof ModelImpactError&&failure.status===403)setReport(null);setError(failure instanceof Error?failure.message:'模型引用读取失败')}}finally{if(request.current===controller){request.current=null;setBusy(false)}}}
 const readRef=useRef(read);readRef.current=read
 useEffect(()=>{if(open&&ready&&active&&!attempted.current){attempted.current=true;void readRef.current()}if(!open||!active){request.current?.abort();request.current=null;setBusy(false)}},[open,ready,active])
 useEffect(()=>()=>{request.current?.abort();request.current=null},[])
 return <details className="model-reference-section" open={open} onToggle={e=>setOpen(e.currentTarget.open)}><summary>固定版本引用</summary>{open?<><button disabled={busy} onClick={()=>void read()}>刷新模型引用</button>{report?<ModelReferenceTable report={report}/>:null}{busy?<p role="status">正在读取引用…</p>:null}{error?<p role="alert">{error}</p>:null}</>:null}</details>
}
