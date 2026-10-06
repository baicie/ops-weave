import {useEffect,useRef,useState} from 'react'
import {WorkflowLogSourceList} from '../../components/workflows/WorkflowLogSourceList.tsx'
import {readLogSources,type LogSourcePage} from '../../api/workflow-log-sources.ts'
import type {ConnectionConfiguration} from '../../api/source-connections.ts'
import type {WorkflowSource} from '../../api/workflows.ts'
import {usePlatformSession} from '../../state/platform-session.ts'

export function WorkflowLogSourcePicker(p:{visible:boolean;configuration:ConnectionConfiguration;sourceInstanceId:string;onBind:(source:WorkflowSource)=>void}){
 const [page,setPage]=useState<LogSourcePage|null>(null),[selected,setSelected]=useState(''),[busy,setBusy]=useState(false),[error,setError]=useState('')
 const controller=useRef<AbortController|null>(null),attempt=useRef(''),currentKey=p.configuration.sourceId+'@'+p.configuration.revision+'@'+p.configuration.connectionDigest
 const ready=usePlatformSession(change=>{controller.current?.abort();controller.current=null;attempt.current='';setPage(null);setSelected('');setBusy(false);setError(change.error?.message??'')})
 useEffect(()=>()=>controller.current?.abort(),[])
 async function load(){if(!p.visible||!ready)return;controller.current?.abort();const c=new AbortController();controller.current=c;attempt.current=currentKey;setBusy(true);setError('');setPage(null);setSelected('');try{const result=await readLogSources(p.configuration,p.sourceInstanceId,c.signal);if(controller.current===c&&!c.signal.aborted)setPage(result)}catch(e){if(controller.current===c&&!c.signal.aborted)setError(e instanceof Error?e.message:'日志清单读取失败')}finally{if(controller.current===c&&!c.signal.aborted)setBusy(false)}}
 useEffect(()=>{if(!p.visible||!ready){if(controller.current&&!controller.current.signal.aborted&&!page)setError('日志清单读取已取消，请重新读取。');controller.current?.abort();controller.current=null;setBusy(false);return}if(attempt.current!==currentKey)void load()},[p.visible,ready,currentKey])
 return <WorkflowLogSourceList page={page} selectedId={selected} busy={busy} error={error} choose={setSelected} retry={()=>{void load()}} bind={()=>{const choice=page?.items.find(i=>i.source.log?.itemId===selected);if(!choice||busy||!p.visible||!ready||Date.now()>=Date.parse(choice.expiresAt))return;p.onBind(choice.source)}}/>
}
