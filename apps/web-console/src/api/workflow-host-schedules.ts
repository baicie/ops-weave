import { platformClient } from './http.ts'
import { parseTask,type RuntimeTask,type RuntimeSettings } from './workflow-runtime.ts'
import type { Entry } from './workflows.ts'

export type HostSchedule={workflowId:string;revision:number;digest:string;settings:RuntimeSettings;intervalSeconds:number;generation:number;state:'RUNNING'|'STOPPED'|'FAILED';taskGeneration:number;activeScanId:string|null;accountedScanId:string|null;completedScans:number;confirmedRecords:number;sessionBatches:number;nextRunAt:string|null;lastSuccessAt:string|null;updatedAt:string;error:string|null}
export type HostScheduleStatus={schemaVersion:'2.0';available:boolean;mode:'LOCAL_DEV_ENTITY'|'DELEGATED_ENTITY';pollSeconds:5;maxSessionBatches:20;schedule:HostSchedule|null;task:RuntimeTask|null}
export type HostScheduleCommand={requestId:string;id:string;revision:number;digest:string;settings:RuntimeSettings;intervalSeconds:number;expectedGeneration:number;operation:'START'|'STOP'|'RESUME'}
export type HostScheduleReceipt={requestId:string;operation:HostScheduleCommand['operation'];commandDigest:string;acceptedAt:string;schedule:HostSchedule}
export class HostScheduleError extends Error{constructor(readonly status:number){super('周期采集请求失败（HTTP '+status+'）')}}
const uuid=/^[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}$/,digest=/^sha256:[a-f0-9]{64}$/
const errors=['INVALID_SAMPLE','SOURCE_UNAVAILABLE','FORBIDDEN','MODEL_CHANGED','OPERATOR_CHANGED','OUTPUT_UNAVAILABLE','BACKLOG_LIMIT','RUNTIME_UNAVAILABLE','AUTHORIZATION_EXPIRED','AUTHORIZATION_REVOKED','EXECUTION_LIMIT','TASK_CHANGED']
function invalid():never{throw new Error('周期采集响应不符合契约')}
function object(v:unknown,keys:string[]):Record<string,unknown>{if(!v||typeof v!=='object'||Array.isArray(v)||Object.keys(v).length!==keys.length||keys.some(k=>!Object.hasOwn(v,k)))invalid();return v as Record<string,unknown>}
function integer(v:unknown,min:number,max:number){if(!Number.isSafeInteger(v)||Number(v)<min||Number(v)>max)invalid()}
function date(v:unknown){if(typeof v!=='string'||v.length>40||!Number.isFinite(Date.parse(v)))invalid();return Date.parse(v)}
function schedule(v:unknown,entry:Entry):HostSchedule{
 const s=object(v,['workflowId','revision','digest','settings','intervalSeconds','generation','state','taskGeneration','activeScanId','accountedScanId','completedScans','confirmedRecords','sessionBatches','nextRunAt','lastSuccessAt','updatedAt','error'])
 if(s.workflowId!==entry.definition.id||!digest.test(String(s.digest)))invalid();integer(s.revision,1,1000000);integer(s.intervalSeconds,60,900);integer(s.generation,1,1000001);integer(s.taskGeneration,1,1000001);integer(s.completedScans,0,200);integer(s.confirmedRecords,0,1000);integer(s.sessionBatches,0,20)
 const settings=object(s.settings,['identityField','nameField']);if(settings.identityField!=='entity_id'||typeof settings.nameField!=='string'||!/^[a-zA-Z][a-zA-Z0-9_]{0,47}$/.test(settings.nameField)||['tenantId','tenant_id','Authorization','authorization','constructor','prototype','secret','token','password'].includes(String(settings.nameField)))invalid()
 if(!['RUNNING','STOPPED','FAILED'].includes(String(s.state))||s.generation===1000001&&s.state!=='STOPPED'||(s.state==='FAILED')!==(s.error!==null)||s.error!==null&&!errors.includes(String(s.error)))invalid()
 for(const k of ['activeScanId','accountedScanId'])if(s[k]!==null&&!uuid.test(String(s[k])))invalid();const updated=date(s.updatedAt);if(s.lastSuccessAt!==null&&date(s.lastSuccessAt)>updated)invalid();if(s.nextRunAt!==null){date(s.nextRunAt);if(s.state!=='RUNNING'||s.activeScanId!==null)invalid()}
 return v as HostSchedule
}
export function parseHostSchedule(v:unknown,entry:Entry):HostScheduleStatus{
 const s=object(v,['schemaVersion','available','mode','pollSeconds','maxSessionBatches','schedule','task']);if(s.schemaVersion!=='2.0'||typeof s.available!=='boolean'||!['LOCAL_DEV_ENTITY','DELEGATED_ENTITY'].includes(String(s.mode))||s.pollSeconds!==5||s.maxSessionBatches!==20||(s.schedule===null)!==(s.task===null))invalid()
 if(s.schedule!==null){const v=schedule(s.schedule,entry),t=parseTask(s.task);if(t.workflowId!==v.workflowId||v.state==='RUNNING'&&(t.revision!==v.revision||t.digest!==v.digest||t.settings.identityField!==v.settings.identityField||t.settings.nameField!==v.settings.nameField||t.generation!==v.taskGeneration))invalid()}
 return s as HostScheduleStatus
}
function receipt(v:unknown,entry:Entry,command:HostScheduleCommand):HostScheduleReceipt{
 const r=object(v,['requestId','operation','commandDigest','acceptedAt','schedule']),s=schedule(r.schedule,entry);if(r.requestId!==command.requestId||r.operation!==command.operation||!digest.test(String(r.commandDigest))||date(r.acceptedAt)!==date(s.updatedAt)||s.state!==(command.operation==='STOP'?'STOPPED':'RUNNING')||s.generation!==command.expectedGeneration+1||s.revision!==command.revision||s.digest!==command.digest||s.intervalSeconds!==command.intervalSeconds||s.settings.identityField!==command.settings.identityField||s.settings.nameField!==command.settings.nameField)invalid();return v as HostScheduleReceipt
}
const root='/api/v1/integrations/workflows/host-schedules',options=(signal:AbortSignal)=>({signal,error:(status:number)=>new HostScheduleError(status)})
export async function hostScheduleStatus(entry:Entry,signal:AbortSignal){return parseHostSchedule(await platformClient.request(root+'/workflows/'+encodeURIComponent(entry.definition.id),options(signal)),entry)}
export async function hostScheduleControl(entry:Entry,c:HostScheduleCommand,signal:AbortSignal){const {operation,...body}=c;return receipt(await platformClient.request(root+'/'+operation.toLowerCase(),{...options(signal),method:'POST',body}),entry,c)}
export async function hostScheduleReceipt(entry:Entry,c:HostScheduleCommand,signal:AbortSignal){return receipt(await platformClient.request(root+'/commands/'+c.requestId,options(signal)),entry,c)}
