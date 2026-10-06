import {useEffect,useRef,useState} from 'react'
import {ALERT_KINDS,AlertError,alertStatus,configureAlerts,alertReceipt,type AlertCommand,type AlertConfiguration,type AlertStatus} from '../../api/workflow-quality-alerts.ts'
import type {Entry} from '../../api/workflows.ts'
import {WorkflowQualityAlertsPanel,type AlertForm} from '../../components/workflows/WorkflowQualityAlertsPanel.tsx'
import {usePlatformSession} from '../../state/platform-session.ts'
import {usePageActive,usePageCloseGuard} from '../../state/page-workspace.ts'

const formFor=(c:AlertConfiguration|null):AlertForm=>{const form:AlertForm={windowSeconds:String(c?.windowSeconds??600),enabled:{SOURCE_FAILURES:false,OUTPUT_REJECTIONS:false,QUEUE_WAIT:false,TASK_FAILURE:false},thresholds:{SOURCE_FAILURES:'3',OUTPUT_REJECTIONS:'1',QUEUE_WAIT:'250',TASK_FAILURE:'1'}};for(const rule of c?.rules??[]){form.enabled[rule.kind]=true;form.thresholds[rule.kind]=String(rule.threshold)}return form}
export function WorkflowQualityAlertsSection({entry,onLocked}:{entry:Entry;onLocked:(v:boolean)=>void}){
 const [open,setOpen]=useState(false),[configuration,setConfiguration]=useState<AlertConfiguration|null>(null),[report,setReport]=useState<AlertStatus|null>(null),[form,setForm]=useState<AlertForm>(()=>formFor(null)),[loaded,setLoaded]=useState(false),[busy,setBusy]=useState(false),[missing,setMissing]=useState(false),[error,setError]=useState(''),[notice,setNotice]=useState('')
 const request=useRef<AbortController|null>(null),pending=useRef<AlertCommand|null>(null),writing=useRef(false),attempted=useRef(false),baseline=useRef(JSON.stringify(formFor(null))),active=usePageActive(),dirty=JSON.stringify(form)!==baseline.current
 function apply(c:AlertConfiguration|null){setConfiguration(c);const next=formFor(c);baseline.current=JSON.stringify(next);setForm(next);setLoaded(true)}
 const ready=usePlatformSession(()=>{request.current?.abort();request.current=null;pending.current=null;writing.current=false;attempted.current=false;baseline.current=JSON.stringify(formFor(null));setOpen(false);setConfiguration(null);setReport(null);setForm(formFor(null));setLoaded(false);setBusy(false);setMissing(false);setError('');setNotice('');onLocked(false)})
 usePageCloseGuard(pending.current||busy?{message:'阈值配置结果待确认，请查询原回执。',blocked:true}:dirty?{message:'阈值规则有未保存的修改，请保存或撤销。',blocked:true}:null)
 useEffect(()=>{onLocked(dirty||busy||Boolean(pending.current))},[dirty,busy,onLocked])
 async function action(kind:'load'|'save'|'query'|'resend'){
  if(!ready||!active||!open||writing.current||kind==='load'&&(dirty||pending.current)||kind==='save'&&(!loaded||!dirty||pending.current)||kind==='resend'&&!missing)return
  if(kind==='save'){
   const seconds=Number(form.windowSeconds),rules=ALERT_KINDS.filter(k=>form.enabled[k]).map(k=>({kind:k,threshold:Number(form.thresholds[k])}));if(!Number.isSafeInteger(seconds)||seconds<60||seconds>86400||rules.some(r=>!Number.isSafeInteger(r.threshold)||r.threshold<1||r.threshold>(r.kind==='QUEUE_WAIT'?3600000:r.kind==='TASK_FAILURE'?1:20))){setError('请输入范围内的整数阈值和统计区间。');return}
   pending.current={requestId:crypto.randomUUID(),id:entry.definition.id,revision:entry.definition.revision,digest:entry.digest,expectedVersion:configuration?.editVersion??0,windowSeconds:seconds,rules}
  }
  if(kind!=='load'&&!pending.current)return
  const controller=new AbortController();request.current?.abort();request.current=controller;writing.current=true;setBusy(true);onLocked(true);setError('');setNotice('');const current=()=>request.current===controller&&!controller.signal.aborted
  try{
   if(kind!=='load'){const result=kind==='query'?await alertReceipt(entry,pending.current!,controller.signal):await configureAlerts(entry,pending.current!,controller.signal);if(!current())return;apply(result.configuration);pending.current=null;setMissing(false);setNotice('规则配置已保存。');setReport(null)}
   const next=await alertStatus(entry,controller.signal);if(current()){apply(next.configuration);setReport(next)}
  }catch(failure){if(current()){
   if(failure instanceof AlertError&&failure.status===403){pending.current=null;setLoaded(false);setConfiguration(null);setReport(null);const empty=formFor(null);baseline.current=JSON.stringify(empty);setForm(empty);setMissing(false)}
   else if(failure instanceof AlertError&&failure.status===404&&kind==='query'&&pending.current)setMissing(true)
   else if(failure instanceof AlertError&&[400,409].includes(failure.status)&&['save','resend'].includes(kind)&&pending.current){pending.current=null;setMissing(false)}
   setError(failure instanceof Error?failure.message:'阈值请求失败')
  }}finally{if(request.current===controller){request.current=null;writing.current=false;setBusy(false);onLocked(Boolean(pending.current)||JSON.stringify(form)!==baseline.current)}}
 }
 const actionRef=useRef(action);actionRef.current=action
 useEffect(()=>{if(open&&active&&ready&&!attempted.current){attempted.current=true;void actionRef.current('load')}return()=>{request.current?.abort();request.current=null;writing.current=false;setBusy(false)}},[open,active,ready])
 function cancel(){if(busy||pending.current)return;setForm(formFor(configuration));setError('');onLocked(false)}
 function dismiss(){if(!missing||busy)return;pending.current=null;setMissing(false);setForm(formFor(configuration));setError('');setNotice('已放弃本次本地确认，请刷新配置；服务器命令未取消。');onLocked(false)}
 return <details className="workflow-runtime-section" open={open} onToggle={e=>{if(e.target===e.currentTarget){if(!e.currentTarget.open&&(dirty||pending.current||busy))e.currentTarget.open=true;else setOpen(e.currentTarget.open)}}}><summary>质量阈值</summary>{open&&active?<WorkflowQualityAlertsPanel configuration={configuration} report={report} loaded={loaded} form={form} dirty={dirty} busy={busy} pending={pending.current} missing={missing} error={error} notice={notice} change={setForm} load={()=>void action('load')} save={()=>void action('save')} cancel={cancel} query={()=>void action('query')} resend={()=>void action('resend')} dismiss={dismiss}/>:null}</details>
}
