import type { Page } from '@playwright/test'
import {readFileSync} from 'node:fs'
import {WORKFLOW_OPERATORS,TOKEN_OK,closeWorkflowInspector} from './helpers.ts'
// Explicit browser protocol fixture; real PG and upstream checks are separate.
const example=(name:string)=>JSON.parse(readFileSync(new URL('../../../contracts/'+name,import.meta.url),'utf8'))
export const digest='sha256:'+'a'.repeat(64),batchId='10000000-0000-4000-8000-000000000116',scanId='10000000-0000-4000-8000-000000000117',time='2026-10-04T01:00:00Z'
export async function fixture(page:Page,mode='normal',hold?:Promise<void>,scheduleMode?:string){
 const model=example('catalog/opsweave-core-1.0.0.json').definitions.find((d:any)=>d.id==='builtin.host')
 const d={schemaVersion:'2.0',id:'fixture-fixed-host',revision:1,name:'Fixture fixed host scan',source:{kind:'ZABBIX_HOST',instanceId:'fixture-host',configuration:example('examples/v2/workflow-source-configuration-pin.json')},target:{id:model.id,revision:1,digest},nodes:['SOURCE','MAP','VALIDATE','OUTPUT'].map((type,i)=>({id:['source','mapping','validate','output'][i],type,version:'1',operatorDigest:WORKFLOW_OPERATORS.operators.find(o=>o.type===type)!.digest,config:type==='MAP'?{name:'hostname'}:{}})),edges:[{from:'source',to:'mapping'},{from:'mapping',to:'validate'},{from:'validate',to:'output'}]}
 const entry={definition:d,digest,state:'PUBLISHED',editVersion:0,preview:null,updatedAt:time,layout:Object.fromEntries(d.nodes.map((n,i)=>[n.id,{x:180,y:40+i*160}]))}
 const settings={identityField:'entity_id',nameField:'hostname'}
 let task:any=mode==='normal'?null:{workflowId:d.id,revision:1,digest,settings,generation:1,state:'FAILED',cursor:time,cursorId:batchId,updatedAt:time,error:'OUTPUT_UNAVAILABLE'},ack:any
 let checkpoint:any={workflowId:d.id,revision:1,digest,generation:1,scanId,pendingBatchId:mode==='normal'?null:batchId,confirmedBatches:0,confirmedRecords:0,complete:false,updatedAt:time}
 let batches:any[]=mode==='normal'?[]:[{id:batchId,scanId,workflowId:d.id,revision:1,digest,settings,sequence:1,complete:false,observedAt:time,state:'UNKNOWN',entityIds:['10000000-0000-4000-8000-000000000118'],error:'OUTPUT_UNAVAILABLE',updatedAt:time,recordCount:5}]
 if(['completed-other','stopped-other','unknown-other','older-other'].includes(mode)){
  Object.assign(d,{revision:2});Object.assign(entry,{digest:'sha256:'+'b'.repeat(64)})
  Object.assign(task,{state:'STOPPED',error:null});Object.assign(checkpoint,{complete:true,pendingBatchId:null,confirmedBatches:1,confirmedRecords:5})
  batches=batches.map(b=>({...b,state:'CONFIRMED',complete:true,error:null}))
  if(mode==='stopped-other'){checkpoint.complete=false;batches=batches.map(b=>({...b,complete:false}))}
  if(mode==='unknown-other'){task.state='FAILED';task.error='OUTPUT_UNAVAILABLE';checkpoint.complete=false;checkpoint.pendingBatchId=batchId;batches=batches.map(b=>({...b,state:'UNKNOWN',complete:false,error:'OUTPUT_UNAVAILABLE'}))}
  if(mode==='older-other'){task.revision=3;checkpoint.revision=3;batches=batches.map(b=>({...b,revision:3}))}
 }
 let schedule:any=null,scheduleAck:any
 if(scheduleMode==='idle'){
  task={workflowId:d.id,revision:1,digest,settings,generation:1,state:'STOPPED',cursor:time,cursorId:batchId,updatedAt:time,error:null}
  schedule={workflowId:d.id,revision:1,digest,settings,intervalSeconds:60,generation:1,state:'RUNNING',taskGeneration:1,activeScanId:null,accountedScanId:scanId,completedScans:1,confirmedRecords:3,sessionBatches:1,nextRunAt:'2026-10-04T01:01:00Z',lastSuccessAt:time,updatedAt:time,error:null}
 }
 const calls:{path:string;body:any}[]=[]
 await page.route(/\/api\/v1\/integrations\/workflows(?:\/|$)/,async route=>{
  const path=new URL(route.request().url()).pathname,body=route.request().postDataJSON();calls.push({path,body})
  if(path.includes('/host-schedules/')){
   if(path.includes('/commands/')){if(hold)await hold;if(scheduleMode==='missing')return route.fulfill({status:404,json:{error:'NOT_FOUND'}});return route.fulfill({json:scheduleAck}).catch(()=>{})}
   if(path.includes('/host-schedules/workflows/')){
    if(scheduleMode==='unavailable')return route.fulfill({status:503,json:{error:'SOURCE_UNAVAILABLE'}})
    const value={schemaVersion:'2.0',available:true,mode:'LOCAL_DEV_ENTITY',pollSeconds:5,maxSessionBatches:20,schedule,task:schedule?task:null}
    if(scheduleMode==='corrupt')Object.assign(value,{authority:'private field'})
    return route.fulfill({json:value})
   }
   if(/\/(start|stop|resume)$/.test(path)){
    const operation=path.split('/').at(-1)!.toUpperCase()
    task={workflowId:d.id,revision:body.revision,digest:body.digest,settings:body.settings,generation:operation==='STOP'?task?.generation??1:(task?.generation??0)+1,state:operation==='STOP'?'STOPPED':'RUNNING',cursor:time,cursorId:batchId,updatedAt:time,error:null}
    schedule={workflowId:d.id,revision:body.revision,digest:body.digest,settings:body.settings,intervalSeconds:body.intervalSeconds,generation:body.expectedGeneration+1,state:operation==='STOP'?'STOPPED':'RUNNING',taskGeneration:task.generation,activeScanId:operation==='STOP'?null:scanId,accountedScanId:operation==='STOP'?scanId:null,completedScans:operation==='STOP'?1:0,confirmedRecords:operation==='STOP'?3:0,sessionBatches:operation==='STOP'?1:0,nextRunAt:null,lastSuccessAt:operation==='STOP'?time:null,updatedAt:time,error:null}
    scheduleAck={requestId:body.requestId,operation,commandDigest:digest,acceptedAt:time,schedule}
    if(scheduleMode==='lost'||scheduleMode==='missing')return route.abort('failed')
    return route.fulfill({json:scheduleAck})
   }
  }
  if(path.endsWith('/workflows'))return route.fulfill({json:{schemaVersion:'2.0',storage:'postgres',operatorCatalog:WORKFLOW_OPERATORS,drafts:{items:[],truncated:false},published:{items:[entry],truncated:false},models:[{definition:model,digest}],modelsTruncated:false,zabbixSource:{instanceId:'fixture-host',mode:'fixture'},runs:{items:[],truncated:false}}})
  if(path.includes('/versions/'))return route.fulfill({json:entry})
  if(path.endsWith('/runtime'))return route.fulfill({json:{schemaVersion:'2.0',mode:'LOCAL_DEV_ENTITY',backgroundAvailable:true,pollSeconds:5,maxBatchRecords:5,tasks:task?[task]:[],executions:[]}})
  if(path.includes('/host-scans/')){
   if(mode==='failed-read')return route.fulfill({status:503,json:{error:'SOURCE_UNAVAILABLE'}})
   const c=structuredClone(checkpoint);if(mode==='corrupt')c.nextCursor='private cursor must be rejected'
   return route.fulfill({json:{schemaVersion:'2.0',checkpoint:c,batches}})
  }
  if(path.includes('/commands/')){if(hold)await hold;return route.fulfill({json:ack}).catch(()=>{})}
  if(/\/runtime\/(start|stop|resume)$/.test(path)){
   const operation=path.split('/').at(-1)!.toUpperCase();task={workflowId:d.id,revision:body.revision,digest:body.digest,settings:body.settings,generation:body.expectedGeneration+1,state:operation==='STOP'?'STOPPED':'RUNNING',cursor:time,cursorId:batchId,updatedAt:time,error:null}
   checkpoint.generation=task.generation
   if(operation==='START'){checkpoint={...checkpoint,revision:body.revision,digest:body.digest,pendingBatchId:null,complete:false,confirmedBatches:0,confirmedRecords:0};batches=[]}
   if(operation==='RESUME'){
    batches=batches.map(b=>({...b,state:'CONFIRMED',entityIds:Array.from({length:5},(_,i)=>'10000000-0000-4000-8000-'+String(118+i).padStart(12,'0')),error:null}))
    checkpoint={...checkpoint,pendingBatchId:null,confirmedBatches:1,confirmedRecords:5}
   }
   ack={requestId:body.requestId,operation,commandDigest:digest,createdAt:time,task}
   if(mode==='lost-resume'&&operation==='RESUME')return route.abort('failed')
   return route.fulfill({json:ack})
  }
  return route.fulfill({status:404,json:{error:'NOT_FOUND'}})
 })
 await page.goto('/#/integrations/workflows');await page.getByRole('textbox',{name:'平台开发 Token（仅保存在当前标签页内存）'}).fill(TOKEN_OK);await page.getByRole('button',{name:'查看',exact:true}).click();await closeWorkflowInspector(page);await page.getByText('执行与任务管理',{exact:true}).click();return calls
}
