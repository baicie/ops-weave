import { useEffect, useRef, useState } from 'react'
import { WorkflowComparisonDrawer, comparisonEntryKey } from '../../components/workflows/WorkflowComparisonDrawer.tsx'
import { compareWorkflows, versionReference, type WorkflowComparison } from '../../api/workflow-comparisons.ts'
import { readWorkspace, type Entry } from '../../api/workflows.ts'
import { usePlatformSession } from '../../state/platform-session.ts'
import { TransportError } from '../../api/http.ts'
export function WorkflowComparisonPanel(p:{active:boolean;requested:{nonce:number;entry:Entry}|null;entries:Entry[];truncated:boolean}) {
 const [open,setOpen]=useState(false),[entries,setEntries]=useState<Entry[]>([]),[base,setBase]=useState(''),[candidate,setCandidate]=useState(''),[report,setReport]=useState<WorkflowComparison|null>(null),[loading,setLoading]=useState(false),[error,setError]=useState(''),[truncated,setTruncated]=useState(false)
 const controller=useRef<AbortController|null>(null),attempt=useRef(0),disposed=useRef(false)
 const observedEntries=useRef(new Map<string,string>())
 const ready=usePlatformSession(change=>{controller.current?.abort();controller.current=null;setOpen(false);setEntries([]);setBase('');setCandidate('');setReport(null);setLoading(false);setError(change.error?.message??'')})
 function cancel(){controller.current?.abort();controller.current=null;setLoading(false)}
 function close(){cancel();setOpen(false)}
 useEffect(()=>{disposed.current=false;return()=>{disposed.current=true;controller.current?.abort()}},[])
 useEffect(()=>{if(!p.active)close()},[p.active])
 useEffect(()=>{
  if(!p.active||!ready||!p.requested||attempt.current===p.requested.nonce)return
  attempt.current=p.requested.nonce;cancel();const selected=p.requested.entry
  const options=[selected,...p.entries.filter(e=>e.definition.id===selected.definition.id&&comparisonEntryKey(e)!==comparisonEntryKey(selected))].sort((a,b)=>b.definition.revision-a.definition.revision||a.state.localeCompare(b.state))
  setEntries(options);setCandidate(comparisonEntryKey(selected));setBase(comparisonEntryKey(options.find(e=>e.state==='PUBLISHED'&&comparisonEntryKey(e)!==comparisonEntryKey(selected))??selected));setReport(null);setError('');setTruncated(p.truncated);setOpen(true)
 },[p.active,ready,p.requested,p.entries,p.truncated])
 function select(key:string,target:'base'|'candidate'){cancel();setReport(null);setError('');if(target==='base')setBase(key);else setCandidate(key)}
 async function request(refresh=false) {
  if(!ready||!p.active||!open||loading)return
  const a=entries.find(e=>comparisonEntryKey(e)===base),b=entries.find(e=>comparisonEntryKey(e)===candidate)
  const workflowId=p.requested?.entry.definition.id;if(!workflowId||!refresh&&(!a||!b))return
  const c=new AbortController();controller.current?.abort();controller.current=c;setLoading(true);setReport(null);setError('')
  const current=()=>!disposed.current&&controller.current===c&&!c.signal.aborted
  try{
   if(refresh){const w=await readWorkspace(c.signal);if(!current())return;const all=[...w.drafts.items,...w.published.items].filter(e=>e.definition.id===workflowId).sort((x,y)=>y.definition.revision-x.definition.revision||x.state.localeCompare(y.state));setEntries(all);setTruncated(w.drafts.truncated||w.published.truncated);if(!all.some(e=>comparisonEntryKey(e)===base))setBase('');if(!all.some(e=>comparisonEntryKey(e)===candidate))setCandidate('')}
   else {const result=await compareWorkflows(a!,b!,c.signal);if(current())setReport(result)}
  }catch(cause){if(current())setError(cause instanceof TransportError?'版本数据读取失败，请重新刷新或比较。（请求 '+cause.requestId+'）':cause instanceof Error?cause.message:'版本比较失败')}
  finally{if(current())setLoading(false)}
 }
 useEffect(()=>{
  const key=(e:{id:string;revision:number;state:string})=>JSON.stringify([e.id,e.revision,e.state])
  const current=new Map(p.entries.map(e=>{const ref=versionReference(e);return [key(ref),JSON.stringify(ref)]}))
  const changed=report&&[report.base,report.candidate].some(ref=>{const id=key(ref),before=observedEntries.current.get(id),after=current.get(id);return before&&after&&before!==after&&after!==JSON.stringify(ref)})
  observedEntries.current=current
  if(changed){cancel();setReport(null);setError('版本已变化，请刷新版本选项后重新比较。')}
 },[p.entries,report])
 return <WorkflowComparisonDrawer active={p.active} open={open} entries={entries} base={base} candidate={candidate} report={report} loading={loading} error={error} truncated={truncated} changeBase={key=>select(key,'base')} changeCandidate={key=>select(key,'candidate')} compare={()=>{void request()}} refresh={()=>{void request(true)}} close={close}/>
}
