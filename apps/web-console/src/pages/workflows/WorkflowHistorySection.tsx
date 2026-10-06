import {useEffect,useRef,useState} from 'react'
import type {Entry} from '../../api/workflows.ts'
import type {DiagnosticObservation} from '../../api/workflow-diagnostics.ts'
import {workflowHistory,type WorkflowHistoryPage} from '../../api/workflow-history.ts'
import {WorkflowHistoryPanel} from '../../components/workflows/WorkflowHistoryPanel.tsx'
import {usePlatformSession} from '../../state/platform-session.ts'
import {usePageActive,usePageCloseGuard} from '../../state/page-workspace.ts'
import {replayEligible} from '../../api/workflow-metric-replay.ts'
import {replayEligible as logReplayEligible} from '../../api/workflow-log-replay.ts'
import {WorkflowLogReplaySection} from './WorkflowLogReplaySection.tsx'
import {WorkflowMetricReplaySection} from './WorkflowMetricReplaySection.tsx'

/** Owns selection, authenticated requests and cancellation; the panel only presents metadata. */
export function WorkflowHistorySection(p:{entries:Entry[];linkedKey:string|null;visible:boolean;onBusy:(busy:boolean)=>void}){
 const [key,setKey]=useState(p.linkedKey??(p.entries[0]?p.entries[0].definition.id+'@'+p.entries[0].definition.revision:'')),[report,setReport]=useState<WorkflowHistoryPage|null>(null),[items,setItems]=useState<DiagnosticObservation[]>([]),[selected,setSelected]=useState<DiagnosticObservation|null>(null),[busy,setBusy]=useState(false),[error,setError]=useState('')
 const [replayLocked,setReplayLocked]=useState(false)
 const request=useRef<AbortController|null>(null),attempted=useRef(false),active=usePageActive(),entry=p.entries.find(e=>e.definition.id+'@'+e.definition.revision===key)??null,ready=usePlatformSession(change=>{request.current?.abort();request.current=null;attempted.current=false;setReport(null);setItems([]);setSelected(null);setBusy(false);setReplayLocked(false);p.onBusy(false);setKey('');setError(change.error?.message??'')})
 function lock(value:boolean){setReplayLocked(value);p.onBusy(value||busy)}
 usePageCloseGuard(busy?{blocked:true,message:'正在读取运行历史，请等待完成后关闭。'}:null)
 async function read(more=false){
  if(!ready||!active||!p.visible||!entry||request.current||more&&!report?.hasMore)return;const c=new AbortController();request.current=c;setBusy(true);p.onBusy(true);setError('');const current=()=>request.current===c&&!c.signal.aborted
  try{const next=await workflowHistory(entry,c.signal,more?report??undefined:undefined);if(current()){if(more&&next.items.some(o=>items.some(old=>old.id===o.id)))throw Error('运行历史重复，请刷新运行记录。');setReport(next);setItems(more?[...items,...next.items]:next.items);if(!more)setSelected(null)}}catch(failure){if(current())setError(failure instanceof Error?failure.message:'运行历史读取失败')}
  finally{if(current()){request.current=null;setBusy(false);p.onBusy(false)}}
 }
 const readRef=useRef(read);readRef.current=read
 useEffect(()=>{attempted.current=false;setReport(null);setItems([]);setSelected(null);setError('');if(key&&!entry)setError('链接中的发布版本不可用，请明确选择其他版本。')},[key,entry?.digest])
 useEffect(()=>{if(p.visible&&active&&ready&&entry&&!attempted.current){attempted.current=true;void readRef.current()}return()=>{if(request.current){request.current.abort();request.current=null;setBusy(false);p.onBusy(false);setError('读取已取消，请刷新运行历史。')}}},[p.visible,active,ready,key,entry?.digest])
 return <><WorkflowHistoryPanel entries={p.entries} entry={entry} report={report} items={items} selected={selected} busy={busy||replayLocked} error={error} selectVersion={value=>setKey(value)} select={setSelected} refresh={()=>void read()} more={()=>void read(true)}/>{entry&&replayEligible(entry)?<WorkflowMetricReplaySection key={key} entry={entry} visible={p.visible&&!busy} onLocked={lock}/>:null}{entry&&logReplayEligible(entry)?<WorkflowLogReplaySection key={key} entry={entry} visible={p.visible&&!busy} onLocked={lock}/>:null}</>
}
