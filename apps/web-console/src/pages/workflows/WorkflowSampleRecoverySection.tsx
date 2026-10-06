import {useEffect,useRef,useState} from 'react'
import type {Entry} from '../../api/workflows.ts'
import {abandonSample,sampleRecoveryStatus,sampleRecoveryReceipt,SampleRecoveryError,type SampleKind,type SampleProof,type SampleRecoveryCommand,type SampleRecoveryReceipt} from '../../api/workflow-sample-recovery.ts'
import {WorkflowSampleRecoveryPanel} from '../../components/workflows/WorkflowSampleRecoveryPanel.tsx'
import {usePlatformSession} from '../../state/platform-session.ts'
import {usePageActive,usePageCloseGuard} from '../../state/page-workspace.ts'

export function WorkflowSampleRecoverySection({entry,kind,proof,onClosed,onLocked}:{entry:Entry;kind:SampleKind;proof:SampleProof;onClosed:()=>void;onLocked:(v:boolean)=>void}){
 const [checked,setChecked]=useState(false),[closed,setClosed]=useState<SampleRecoveryReceipt|null>(null),[busy,setBusy]=useState(false),[acknowledged,setAcknowledged]=useState(false),[missing,setMissing]=useState(false),[error,setError]=useState('')
 const request=useRef<AbortController|null>(null),pending=useRef<SampleRecoveryCommand|null>(null),writing=useRef(false),attempted=useRef(false),active=usePageActive()
 const ready=usePlatformSession(()=>{request.current?.abort();request.current=null;pending.current=null;writing.current=false;attempted.current=false;setChecked(false);setClosed(null);setBusy(false);setAcknowledged(false);setMissing(false);setError('');onLocked(false)})
 usePageCloseGuard(busy?{message:'样本终止请求正在处理。',blocked:true}:pending.current?{message:'样本终止结果待确认，请查询原回执。',blocked:true}:null)
 async function action(kindOfAction:'load'|'abandon'|'query'|'resend'){
  if(!ready||!active||writing.current||kindOfAction==='load'&&pending.current||kindOfAction==='abandon'&&(!checked||closed||pending.current||!acknowledged)||kindOfAction==='resend'&&!missing)return
  if(kindOfAction==='abandon')pending.current={requestId:crypto.randomUUID(),id:entry.definition.id,revision:entry.definition.revision,digest:entry.digest,kind,batchId:proof.batchId,batchDigest:proof.batchDigest,expectedUpdatedAt:proof.updatedAt,acknowledgeUncertainOutput:true}
  if(kindOfAction!=='load'&&!pending.current)return
  const controller=new AbortController();request.current?.abort();request.current=controller;writing.current=true;setBusy(true);onLocked(true);setError('');const current=()=>request.current===controller&&!controller.signal.aborted
  try{const result=kindOfAction==='load'?await sampleRecoveryStatus(entry,kind,proof,controller.signal):kindOfAction==='query'?await sampleRecoveryReceipt(entry,kind,proof,pending.current!,controller.signal):await abandonSample(entry,kind,proof,pending.current!,controller.signal)
   if(current()){setChecked(true);if(result){setClosed(result);pending.current=null;setMissing(false);setAcknowledged(false);onClosed()}}
  }catch(failure){if(current()){if(failure instanceof SampleRecoveryError&&failure.status===403){pending.current=null;setChecked(false);setClosed(null);setAcknowledged(false);setMissing(false)}if(failure instanceof SampleRecoveryError&&failure.status===404&&kindOfAction==='query')setMissing(true);setError(failure instanceof Error?failure.message:'样本终止确认失败')}}
  finally{if(request.current===controller){request.current=null;writing.current=false;setBusy(false);onLocked(Boolean(pending.current))}}
 }
 const actionRef=useRef(action);actionRef.current=action
 useEffect(()=>{if(ready&&active&&!attempted.current){attempted.current=true;void actionRef.current('load')}return()=>{request.current?.abort();request.current=null;writing.current=false;setBusy(false);onLocked(Boolean(pending.current))}},[ready,active,entry.digest,proof.batchId])
 function dismiss(){if(!missing||writing.current)return;pending.current=null;onLocked(false);setMissing(false);setChecked(false);setAcknowledged(false);setError('已放弃本次终止确认；服务器命令未取消，请读取终止状态。')}
 return <WorkflowSampleRecoveryPanel checked={checked} closed={closed} busy={busy} acknowledged={acknowledged} pending={pending.current} missing={missing} error={error} acknowledge={setAcknowledged} load={()=>void action('load')} abandon={()=>void action('abandon')} query={()=>void action('query')} resend={()=>void action('resend')} dismiss={dismiss}/>
}
