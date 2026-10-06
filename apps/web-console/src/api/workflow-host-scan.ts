import { platformClient } from './http.ts'
import type { Entry } from './workflows.ts'
import { RuntimeRequestError, type RuntimeSettings } from './workflow-runtime.ts'

export type HostCheckpoint = { workflowId:string;revision:number;digest:string;generation:number;scanId:string;pendingBatchId:string|null;confirmedBatches:number;confirmedRecords:number;complete:boolean;updatedAt:string }
export type HostBatch = { id:string;scanId:string;workflowId:string;revision:number;digest:string;settings:RuntimeSettings;sequence:number;complete:boolean;observedAt:string;state:'READY'|'IN_FLIGHT'|'UNKNOWN'|'FAILED'|'CONFIRMED';entityIds:string[];error:string|null;updatedAt:string;recordCount:number }
export type HostScan = { schemaVersion:'2.0';checkpoint:HostCheckpoint;batches:HostBatch[] }
const uuid=/^[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}$/
const errors=['OUTPUT_UNAVAILABLE','INVALID_SAMPLE','SOURCE_UNAVAILABLE','FORBIDDEN','MODEL_CHANGED','OPERATOR_CHANGED','AUTHORIZATION_EXPIRED','AUTHORIZATION_REVOKED','EXECUTION_LIMIT','BACKLOG_LIMIT','RUNTIME_UNAVAILABLE']
function invalid():never{throw new Error('资产批次响应不符合契约')}
function object(v:unknown,keys:string[]):Record<string,unknown>{if(!v||typeof v!=='object'||Array.isArray(v)||Object.keys(v).length!==keys.length||keys.some(k=>!Object.hasOwn(v,k)))invalid();return v as Record<string,unknown>}
function count(v:unknown,max:number,min=0){if(!Number.isSafeInteger(v)||Number(v)<min||Number(v)>max)invalid()}
function date(v:unknown){if(typeof v!=='string'||v.length>40||!Number.isFinite(Date.parse(v)))invalid()}
function ref(v:Record<string,unknown>,entry:Entry){if(v.workflowId!==entry.definition.id||!Number.isInteger(v.revision)||Number(v.revision)<1||Number(v.revision)>10000||typeof v.digest!=='string'||!/^sha256:[a-f0-9]{64}$/.test(v.digest))invalid()}
export function parseHostScan(value:unknown,entry:Entry,generation:number):HostScan{
 const v=object(value,['schemaVersion','checkpoint','batches']),c=object(v.checkpoint,['workflowId','revision','digest','generation','scanId','pendingBatchId','confirmedBatches','confirmedRecords','complete','updatedAt'])
 ref(c,entry);count(c.generation,1000001,1);count(c.confirmedBatches,200);count(c.confirmedRecords,1000);date(c.updatedAt)
 if(v.schemaVersion!=='2.0'||c.revision!==entry.definition.revision||c.digest!==entry.digest||c.generation!==generation||!uuid.test(String(c.scanId))||c.pendingBatchId!==null&&!uuid.test(String(c.pendingBatchId))||typeof c.complete!=='boolean'||c.complete&&c.pendingBatchId!==null||Number(c.confirmedRecords)>Number(c.confirmedBatches)*5||!Array.isArray(v.batches)||v.batches.length>20)invalid()
 for(const raw of v.batches){
  const b=object(raw,['id','scanId','workflowId','revision','digest','settings','sequence','complete','observedAt','state','entityIds','error','updatedAt','recordCount']);ref(b,entry)
  const s=object(b.settings,['identityField','nameField']);for(const field of Object.values(s))if(typeof field!=='string'||!/^[A-Za-z][A-Za-z0-9_]{0,47}$/.test(field)||['tenantId','tenant_id','authorization','Authorization','token','secret','password','constructor','prototype'].includes(field))invalid()
  count(b.sequence,200,1);count(b.recordCount,5);date(b.observedAt);date(b.updatedAt)
  if(!uuid.test(String(b.id))||!uuid.test(String(b.scanId))||typeof b.complete!=='boolean'||!['READY','IN_FLIGHT','UNKNOWN','FAILED','CONFIRMED'].includes(String(b.state))||!Array.isArray(b.entityIds)||b.entityIds.length>Number(b.recordCount)||new Set(b.entityIds).size!==b.entityIds.length||b.entityIds.some(id=>typeof id!=='string'||!uuid.test(id))||Date.parse(String(b.updatedAt))<Date.parse(String(b.observedAt))||(['READY','IN_FLIGHT','CONFIRMED'].includes(String(b.state)))!==(b.error===null)||b.error!==null&&!errors.includes(String(b.error)))invalid()
  if(b.id===c.pendingBatchId&&(b.scanId!==c.scanId||b.revision!==c.revision||b.digest!==c.digest||b.sequence!==Number(c.confirmedBatches)+1||b.state==='CONFIRMED'))invalid()
 }
 return value as HostScan
}
export async function readHostScan(entry:Entry,generation:number,signal:AbortSignal){
 const value=await platformClient.request('/api/v1/integrations/workflows/runtime/host-scans/'+encodeURIComponent(entry.definition.id),{signal,error:(status)=>new RuntimeRequestError(status,'资产批次读取失败（HTTP '+status+'）')})
 return parseHostScan(value,entry,generation)
}
