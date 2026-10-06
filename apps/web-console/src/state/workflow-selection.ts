import type { Entry } from '../api/workflows.ts'
export type WorkflowSelection={id:string;revision:number;state:Entry['state']}|{sourceSetup:string}|{task:string}|{sourceInstance:string;configurationRevision:number;connectionDigest:string}
export function configuredSourceWorkflowLink(sourceInstance:string,configurationRevision:number,connectionDigest:string) {
 const hash='#/integrations/workflows?'+new URLSearchParams({sourceInstance,configurationRevision:String(configurationRevision),connectionDigest})
 workflowSelection(hash)
 return hash
}
export function workflowSelection(hash:string):WorkflowSelection|null {
 const error=()=>{throw new Error('工作流地址参数无效，请返回数据源中心或工作流列表重新选择')}
 if(hash.length>256||hash.split('?')[0]!=='#/integrations/workflows'||(hash.match(/\?/g)?.length??0)>1)error()
 const raw=hash.split('?')[1]??'';try{decodeURIComponent(raw.replace(/\+/g,' '))}catch{error()}
 const p=new URLSearchParams(raw);if(!p.size)return null
 if(p.has('sourceInstance')) {
  const id=p.get('sourceInstance')??'',revision=p.get('configurationRevision')??'',digest=p.get('connectionDigest')??''
  if(p.size!==3||[...p.keys()].some(k=>!['sourceInstance','configurationRevision','connectionDigest'].includes(k)||p.getAll(k).length!==1)||!/^[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}$/.test(id)||!/^[1-9][0-9]{0,2}$/.test(revision)||Number(revision)>100||!/^sha256:[a-f0-9]{64}$/.test(digest))error()
  return {sourceInstance:id,configurationRevision:Number(revision),connectionDigest:digest}
 }
 if(p.has('task')){const task=p.get('task')??'';if(p.size!==1||p.getAll('task').length!==1||!/^[a-z][a-z0-9_-]{0,47}$/.test(task)||task.includes('\n'))error();return {task}}
 if(p.has('sourceSetup')){const id=p.get('sourceSetup')??'';if(p.size!==1||p.getAll('sourceSetup').length!==1||!/^[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}$/.test(id))error();return {sourceSetup:id}}
 if([...p.keys()].some(k=>!['id','revision','state'].includes(k)||p.getAll(k).length!==1)||p.size!==3)error()
 const id=p.get('id')??'',revision=p.get('revision')??'',state=p.get('state')??''
 if(!/^[a-z][a-z0-9_-]{0,47}$/.test(id)||id.includes('\n')||!/^[1-9][0-9]{0,4}$/.test(revision)||Number(revision)>10000||!['DRAFT','PUBLISHED'].includes(state))error()
 return {id,revision:Number(revision),state:state as Entry['state']}
}
