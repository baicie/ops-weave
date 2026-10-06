import { useEffect, useRef, useState } from 'react'
import { WorkflowRuntimePanel } from '../../components/workflows/WorkflowRuntimePanel.tsx'
import { WorkflowRuntimeControlStatus } from '../../components/workflows/WorkflowRuntimeControlStatus.tsx'
import { controlRuntime, readRuntimeControl, executeRuntime, readRuntime, RuntimeRequestError, type RuntimeStatus, type RuntimeSettings, type RuntimeControlCommand, type RuntimeControlReceipt } from '../../api/workflow-runtime.ts'
import { evaluateWorkflow, type Entry } from '../../api/workflows.ts'
import { usePageActive, usePageCloseGuard } from '../../state/page-workspace.ts'
import { usePlatformSession } from '../../state/platform-session.ts'
import { readHostScan, type HostScan } from '../../api/workflow-host-scan.ts'
import { WorkflowHostScanResults } from '../../components/workflows/WorkflowHostScanResults.tsx'

export function WorkflowRuntimeSection({ entry, sample, batch, names, onBusy }: { entry: Entry; sample: string; batch: string; names: { id: string; label: string }[]; onBusy: (value: boolean) => void }) {
 const [open,setOpen]=useState(false),[status,setStatus]=useState<RuntimeStatus|null>(null),[identity,setIdentity]=useState(''),[name,setName]=useState(''),[busy,setBusy]=useState(false),[error,setError]=useState(''),[notice,setNotice]=useState('')
 const [controlReceipt,setControlReceipt]=useState<RuntimeControlReceipt|null>(null),[missing,setMissing]=useState(false)
 const [hostScan,setHostScan]=useState<HostScan|null>(null),[hostLoaded,setHostLoaded]=useState(false)
 const hostFailed=useRef(false)
 const fixed=Boolean(entry.definition.source.configuration)
 const request=useRef<AbortController|null>(null),writing=useRef(false),pending=useRef<{id:string;values:unknown;batch:string;settings:RuntimeSettings}|null>(null),controlPending=useRef<RuntimeControlCommand|null>(null)
 const active=usePageActive()
 const ready=usePlatformSession(()=>{request.current?.abort();setStatus(null);setIdentity('');setName('');setOpen(false);setError('');setNotice('');setControlReceipt(null);setMissing(false);setHostScan(null);setHostLoaded(false);hostFailed.current=false;pending.current=null;controlPending.current=null;writing.current=false;setBusy(false);onBusy(false)})
 const task=status?.tasks.find(value=>value.workflowId===entry.definition.id)
 const sameVersion=Boolean(task&&task.revision===entry.definition.revision&&task.digest===entry.digest)
 const pendingPage=hostScan?.batches.find(value=>value.id===hostScan.checkpoint.pendingBatchId)
 const replaceVersion=Boolean(task&&entry.definition.revision>task.revision&&task.state!=='RUNNING'&&(task.state==='ABANDONED'||hostLoaded&&(!hostScan?.checkpoint.pendingBatchId||pendingPage?.state==='FAILED'&&pendingPage.entityIds.length===0)))
 const otherVersion=Boolean(task&&!sameVersion&&!replaceVersion)
 usePageCloseGuard(busy?{message:'流程执行正在处理，请等待回执后关闭。',blocked:true}:controlPending.current?{message:'任务控制结果待确认，请查询原请求回执。',blocked:true}:pending.current?{message:'流程写入结果待确认，请先刷新运行回执。',blocked:true}:null)

 async function action(kind:'read'|'poll'|'run'|'start'|'stop'|'resume'|'query'|'resubmit') {
  if(!ready||writing.current||controlPending.current&&['run','start','stop','resume'].includes(kind)||kind==='poll'&&hostFailed.current)return
  if(kind==='read')hostFailed.current=false
  request.current?.abort();const controller=new AbortController();request.current=controller;writing.current=true;setBusy(true);onBusy(true);setError('')
  const current=()=>request.current===controller&&!controller.signal.aborted
  async function refreshStatus(){const result=await readRuntime(controller.signal);if(current()){
   setStatus(result);const currentTask=result.tasks.find(value=>value.workflowId===entry.definition.id)
   if(!pending.current&&!controlPending.current&&currentTask&&currentTask.revision===entry.definition.revision&&currentTask.digest===entry.digest&&(currentTask.state==='RUNNING'||!identity)){setIdentity(currentTask.settings.identityField);setName(currentTask.settings.nameField)}
   if(pending.current&&result.executions.some(value=>value.id===pending.current!.id))pending.current=null
   if(fixed&&!currentTask)setIdentity('entity_id')
   if(fixed){
    setHostLoaded(false)
    if(!currentTask){setHostScan(null);setHostLoaded(true)}
    else{
     const reference={...entry,digest:currentTask.digest,definition:{...entry.definition,revision:currentTask.revision}}
     try{const scan=await readHostScan(reference,currentTask.generation,controller.signal);if(current()){setHostScan(scan);setHostLoaded(true);if(currentTask.revision!==entry.definition.revision||currentTask.digest!==entry.digest)setIdentity('entity_id')}}
     catch(failure){if(current()){hostFailed.current=true;setHostScan(null)}throw failure}
    }
   }
  }}
  try {
   if(kind==='read'||kind==='poll')await refreshStatus()
   else if(kind==='run'){
    if(!pending.current){const values:unknown=entry.definition.source.kind==='MANUAL_SAMPLE'?JSON.parse(sample):undefined;const preview=await evaluateWorkflow(entry,values,batch,controller.signal);if(!current())return;pending.current={id:preview.receipt.id,values,batch,settings:{identityField:identity,nameField:name}}}
    const submission=pending.current
    const result=await executeRuntime(entry,submission.settings,submission.values,submission.batch,submission.id,controller.signal)
    if(current()){pending.current=null;setStatus(value=>value?{...value,executions:[result,...value.executions.filter(item=>item.id!==result.id)].slice(0,20)}:value);setNotice(result.state==='SUCCEEDED'?'已确认写入 '+result.entityIds.length+' 条资产，可到资产页查看。':'执行失败，已确认写入 '+result.entityIds.length+' 条，请检查回执。')}
   }else{
    if(kind==='start'||kind==='stop'||kind==='resume'){
     controlPending.current={requestId:crypto.randomUUID(),id:entry.definition.id,revision:entry.definition.revision,digest:entry.digest,settings:kind==='resume'&&task?task.settings:{identityField:identity,nameField:name},expectedGeneration:task?.generation??0,operation:kind==='start'?'START':kind==='resume'?'RESUME':'STOP'}
     setMissing(false);setControlReceipt(null);setNotice('')
    }
    const command=controlPending.current;if(!command||kind==='resubmit'&&!missing)return
    const result=kind==='query'?await readRuntimeControl(command,controller.signal):await controlRuntime(command,controller.signal)
    if(current()){
     controlPending.current=null;setControlReceipt(result);setMissing(false);setStatus(null)
     setNotice(kind==='query'?'原控制结果已确认；当前状态单独读取。':command.operation==='RESUME'?'已重新授权，将沿原批次和游标继续。':command.operation==='START'?fixed?'固定版本扫描已启动。':'任务已启动，将处理此后完成的来源批次。':'任务已停止；已开始的批次已结束，后续批次不会写入。')
     await refreshStatus()
    }
   }
  }catch(failure){if(current()){
   if(failure instanceof RuntimeRequestError&&kind==='query'&&failure.status===404){setMissing(true);setError('原控制回执尚未找到。')}
   else{
    if(failure instanceof RuntimeRequestError&&[400,403,409,429].includes(failure.status))pending.current=null
    if(failure instanceof RuntimeRequestError&&failure.status===403){controlPending.current=null;setControlReceipt(null);setMissing(false);setStatus(null);setHostScan(null);setHostLoaded(false)}
    if(failure instanceof RuntimeRequestError&&['start','stop','resume','resubmit'].includes(kind)&&[400,403,429].includes(failure.status)){controlPending.current=null;setMissing(false)}
    setError(failure instanceof SyntaxError?'手工样本不是有效 JSON。':failure instanceof Error?failure.message:'流程运行失败')
   }
  }}finally{if(request.current===controller){writing.current=false;setBusy(false);onBusy(Boolean(pending.current||controlPending.current))}}
 }
 function dismissControl(){if(writing.current||!missing)return;controlPending.current=null;setMissing(false);setStatus(null);setError('');setNotice('已放弃本次确认；这不会取消服务器命令，请重新读取当前状态。');onBusy(Boolean(pending.current))}
 const actionRef=useRef(action);actionRef.current=action
 useEffect(()=>{
  if(!open||!active||!ready){request.current?.abort();return}
  if(!controlPending.current)void actionRef.current('read')
  const timer=window.setInterval(()=>{if(!writing.current&&!controlPending.current&&!request.current?.signal.aborted&&document.visibilityState==='visible')void actionRef.current('poll')},5000)
  return()=>{window.clearInterval(timer);request.current?.abort()}
 },[open,active,ready])
 return <details className="workflow-runtime-section" open={open} onToggle={event=>setOpen(event.currentTarget.open)}><summary>执行与任务管理</summary>{open?<>
  <WorkflowRuntimeControlStatus command={controlPending.current} receipt={controlReceipt} missing={missing} busy={busy} query={()=>void action('query')} resubmit={()=>void action('resubmit')} dismiss={dismissControl}/>
  {otherVersion&&!controlPending.current?<p role="alert">当前任务固定在 v{task!.revision}，请打开该版本管理任务。</p>:<WorkflowRuntimePanel entry={entry} status={status} task={task} busy={busy} pending={Boolean(pending.current)} controlPending={Boolean(controlPending.current)} error={error} notice={notice} identity={identity} name={name} names={names} identityChange={setIdentity} nameChange={setName} run={()=>void action('run')} control={start=>void action(start?'start':'stop')} resume={()=>void action('resume')} canResume={sameVersion&&task?.state!=='ABANDONED'&&hostLoaded&&Boolean(hostScan&&!hostScan.checkpoint.complete)} canStart={!fixed||hostLoaded&&(replaceVersion||task?.state!=='ABANDONED'&&(!hostScan||hostScan.checkpoint.complete))} refresh={()=>void action('read')} executions={status?.executions.filter(value=>value.workflowId===entry.definition.id&&value.revision===entry.definition.revision)??[]}/>}
  {hostScan?<WorkflowHostScanResults scan={hostScan}/>:null}
 </>:null}</details>
}
