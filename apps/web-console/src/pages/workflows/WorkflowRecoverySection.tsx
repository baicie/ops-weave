import {useEffect,useRef,useState} from 'react'
import {abandonRecovery,recoveryReceipt,RecoveryError,type RecoveryCommand,type RecoveryReceipt} from '../../api/workflow-recovery.ts'
import type {Entry} from '../../api/workflows.ts'
import type {QualityReport} from '../../api/workflow-quality.ts'
import {WorkflowRecoveryPanel} from '../../components/workflows/WorkflowRecoveryPanel.tsx'
import {usePlatformSession} from '../../state/platform-session.ts'
import {usePageActive,usePageCloseGuard} from '../../state/page-workspace.ts'

export function WorkflowRecoverySection({entry,report,refresh,onLocked}:{entry:Entry;report:QualityReport;refresh:()=>void;onLocked:(v:boolean)=>void}){
 const [acknowledged,setAcknowledged]=useState(false),[busy,setBusy]=useState(false),[missing,setMissing]=useState(false),[error,setError]=useState(''),[receipt,setReceipt]=useState<RecoveryReceipt|null>(null)
 const pending=useRef<RecoveryCommand|null>(null),request=useRef<AbortController|null>(null),writing=useRef(false),active=usePageActive(),task=report.task
 const ready=usePlatformSession(()=>{request.current?.abort();request.current=null;writing.current=false;pending.current=null;onLocked(false);setAcknowledged(false);setBusy(false);setMissing(false);setError('');setReceipt(null)})
 usePageCloseGuard(busy?{message:'终止恢复命令正在处理。',blocked:true}:pending.current?{message:'终止恢复结果待确认，请查询原回执。',blocked:true}:null)
 useEffect(()=>()=>{request.current?.abort();request.current=null;writing.current=false;setBusy(false)},[active,entry.digest])
 async function action(kind:'abandon'|'query'|'resend'){
  if(!ready||!active||writing.current||kind==='abandon'&&(pending.current||!acknowledged||!task||task.state==='ABANDONED'||!task.pendingBatchId)||kind==='resend'&&!missing)return
  if(kind==='abandon'){onLocked(true);pending.current={requestId:crypto.randomUUID(),id:entry.definition.id,revision:entry.definition.revision,digest:entry.digest,kind:report.kind,batchId:task!.pendingBatchId!,expectedGeneration:task!.generation,acknowledgeUncertainOutput:true}}
  const command=pending.current;if(!command)return;const controller=new AbortController();request.current?.abort();request.current=controller;writing.current=true;setBusy(true);setError('');const current=()=>request.current===controller&&!controller.signal.aborted
  try{const result=kind==='query'?await recoveryReceipt(command,controller.signal):await abandonRecovery(entry,command,controller.signal);if(current()){setReceipt(result);pending.current=null;onLocked(false);setMissing(false);setAcknowledged(false);refresh()}}
  catch(failure){if(current()){if(failure instanceof RecoveryError&&failure.status===403){pending.current=null;onLocked(false);setReceipt(null);setAcknowledged(false);setMissing(false)}if(failure instanceof RecoveryError&&failure.status===404&&kind==='query')setMissing(true);setError(failure instanceof Error?failure.message:'终止恢复请求失败')}}
  finally{if(request.current===controller){writing.current=false;setBusy(false);request.current=null}}
 }
 function dismiss(){if(!missing||writing.current)return;pending.current=null;onLocked(false);setMissing(false);setAcknowledged(false);setError('已放弃本次确认；服务器命令未取消，请刷新当前状态。');refresh()}
 if(!task?.pendingBatchId)return null
 return <WorkflowRecoveryPanel batchId={task.pendingBatchId} closed={task.state==='ABANDONED'||receipt!==null} acknowledged={acknowledged} busy={busy} pending={pending.current} missing={missing} receipt={receipt} error={error} acknowledge={setAcknowledged} abandon={()=>void action('abandon')} query={()=>void action('query')} resend={()=>void action('resend')} dismiss={dismiss}/>
}
