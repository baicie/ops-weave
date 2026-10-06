import {useEffect,useRef,useState} from 'react'
import {reviewModel,readModelReferences,type ModelReview,type ModelReferenceReport} from '../../api/model-impact.ts'
import type {ModelEntry} from '../../api/model-catalog.ts'
import {ModelRevisionReviewPanel} from '../../components/modeling/ModelRevisionReviewPanel.tsx'
import {ModelReferenceTable} from '../../components/modeling/ModelReferenceTable.tsx'
import {usePlatformSession} from '../../state/platform-session.ts'
import {usePageActive} from '../../state/page-workspace.ts'

/** Mounted only for one clean saved draft. Edits invalidate this candidate and its publication gate. */
export function ModelPublishReviewSection({entry,checked}:{entry:ModelEntry;checked:(review:ModelReview|null)=>void}){
 const [review,setReview]=useState<ModelReview|null>(null),[references,setReferences]=useState<ModelReferenceReport|null>(null),[busy,setBusy]=useState(false),[error,setError]=useState('')
 const request=useRef<AbortController|null>(null),attempted=useRef(false),callback=useRef(checked);callback.current=checked
 const active=usePageActive(),ready=usePlatformSession(()=>{request.current?.abort();request.current=null;attempted.current=false;setReview(null);setReferences(null);setError('');setBusy(false);callback.current(null)})
 async function read(){if(!ready||!active||request.current)return;const controller=new AbortController();request.current=controller;setBusy(true);setError('');setReview(null);setReferences(null);callback.current(null)
  try{const value=await reviewModel(entry,controller.signal);if(request.current!==controller||controller.signal.aborted)return;setReview(value)
   if(value.base){const refs=await readModelReferences(value.base,controller.signal,value.base.digest);if(request.current!==controller||controller.signal.aborted)return;setReferences(refs)}
   callback.current(value)
  }catch(failure){if(request.current===controller&&!controller.signal.aborted){setError(failure instanceof Error?failure.message:'发布检查失败');callback.current(null)}}finally{if(request.current===controller){request.current=null;setBusy(false)}}
 }
 const readRef=useRef(read);readRef.current=read
 useEffect(()=>{if(ready&&active&&!attempted.current){attempted.current=true;void readRef.current()}if(!active){request.current?.abort();request.current=null;setBusy(false);callback.current(null)}},[ready,active])
 useEffect(()=>()=>{request.current?.abort();request.current=null},[])
 return <><ModelRevisionReviewPanel review={review} references={references} busy={busy} error={error} refresh={()=>void read()}/>{references?<ModelReferenceTable report={references}/>:null}</>
}
