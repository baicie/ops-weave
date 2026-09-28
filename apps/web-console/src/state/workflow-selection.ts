import type { Entry } from '../api/workflows.ts'
export type WorkflowSelection={id:string;revision:number;state:Entry['state']}
export function workflowSelection(hash:string):WorkflowSelection|null {
 const error=()=>{throw new Error('工作流地址参数无效，请返回数据源中心或工作流列表重新选择')}
 if(hash.length>256||hash.split('?')[0]!=='#/integrations/workflows'||(hash.match(/\?/g)?.length??0)>1)error()
 const raw=hash.split('?')[1]??'';try{decodeURIComponent(raw.replace(/\+/g,' '))}catch{error()}
 const p=new URLSearchParams(raw);if(!p.size)return null
 if([...p.keys()].some(k=>!['id','revision','state'].includes(k)||p.getAll(k).length!==1)||p.size!==3)error()
 const id=p.get('id')??'',revision=p.get('revision')??'',state=p.get('state')??''
 if(!/^[a-z][a-z0-9_-]{0,47}$/.test(id)||id.includes('\n')||!/^[1-9][0-9]{0,4}$/.test(revision)||Number(revision)>10000||!['DRAFT','PUBLISHED'].includes(state))error()
 return {id,revision:Number(revision),state:state as Entry['state']}
}
