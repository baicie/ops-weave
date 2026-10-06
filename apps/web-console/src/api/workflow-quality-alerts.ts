import {platformClient} from './http.ts'
import {logPosition} from './workflow-log-streams.ts'
import type {Entry} from './workflows.ts'

export const ALERT_KINDS=['SOURCE_FAILURES','OUTPUT_REJECTIONS','QUEUE_WAIT','TASK_FAILURE'] as const
export type AlertKind=typeof ALERT_KINDS[number]
export type AlertRule={kind:AlertKind;threshold:number}
type Reference={id:string;revision:number;digest:string}
export type AlertConfiguration={reference:Reference;editVersion:number;windowSeconds:number;rules:AlertRule[];updatedAt:string}
export type AlertCommand={requestId:string;id:string;revision:number;digest:string;expectedVersion:number;windowSeconds:number;rules:AlertRule[]}
export type AlertReceipt={schemaVersion:'2.0';requestId:string;commandDigest:string;acceptedAt:string;configuration:AlertConfiguration}
export type AlertEvaluation={kind:AlertKind;state:'TRIGGERED'|'NORMAL'|'UNAVAILABLE';value:number|null;unit:'COUNT'|'MILLISECONDS';sampleCount:number;missingCount:number;evidenceIds:string[];truncated:boolean;reason:string|null}
export type AlertStatus={schemaVersion:'2.0';asOf:string;reference:Reference;configuration:AlertConfiguration|null;from:string|null;till:string|null;evaluations:AlertEvaluation[]}
export class AlertError extends Error{constructor(readonly status:number){super('质量阈值请求失败 · HTTP '+status)}}
const uuid=/^[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}$/
function invalid():never{throw Error('质量阈值响应不符合契约')}
function object(v:unknown,keys:string[]):Record<string,unknown>{if(!v||typeof v!=='object'||Array.isArray(v)||Object.keys(v).length!==keys.length||keys.some(k=>!Object.hasOwn(v,k)))invalid();return v as Record<string,unknown>}
function integer(v:unknown,min:number,max:number){if(!Number.isSafeInteger(v)||Number(v)<min||Number(v)>max)invalid()}
function reference(v:unknown,entry:Entry){const r=object(v,['id','revision','digest']);if(r.id!==entry.definition.id||r.revision!==entry.definition.revision||r.digest!==entry.digest)invalid()}
function rules(v:unknown):AlertRule[]{if(!Array.isArray(v)||v.length>4)invalid();let previous=-1;for(const value of v){const r=object(value,['kind','threshold']),position=ALERT_KINDS.indexOf(r.kind as AlertKind);if(position<=previous)invalid();previous=position;integer(r.threshold,1,r.kind==='QUEUE_WAIT'?3600000:r.kind==='TASK_FAILURE'?1:20)}return v as AlertRule[]}
function configuration(v:unknown,entry:Entry):AlertConfiguration{const c=object(v,['reference','editVersion','windowSeconds','rules','updatedAt']);reference(c.reference,entry);integer(c.editVersion,1,1000000);integer(c.windowSeconds,60,86400);rules(c.rules);logPosition(c.updatedAt);return v as AlertConfiguration}
export function parseAlertStatus(v:unknown,entry:Entry):AlertStatus{
 const s=object(v,['schemaVersion','asOf','reference','configuration','from','till','evaluations']);if(s.schemaVersion!=='2.0'||!Array.isArray(s.evaluations))invalid();reference(s.reference,entry);const now=logPosition(s.asOf)
 if(s.configuration===null){if(s.from!==null||s.till!==null||s.evaluations.length)invalid();return v as AlertStatus}
 const c=configuration(s.configuration,entry);if(logPosition(c.updatedAt)>now||s.till!==s.asOf||now-logPosition(s.from)!==BigInt(c.windowSeconds)*1000000000n||s.evaluations.length!==c.rules.length)invalid()
 for(let i=0;i<c.rules.length;i++){const e=object(s.evaluations[i],['kind','state','value','unit','sampleCount','missingCount','evidenceIds','truncated','reason']),rule=c.rules[i];if(e.kind!==rule.kind||!['TRIGGERED','NORMAL','UNAVAILABLE'].includes(String(e.state))||e.unit!==(rule.kind==='QUEUE_WAIT'?'MILLISECONDS':'COUNT')||typeof e.truncated!=='boolean'||!Array.isArray(e.evidenceIds)||e.evidenceIds.length>20||new Set(e.evidenceIds).size!==e.evidenceIds.length||e.evidenceIds.some(id=>typeof id!=='string'||!uuid.test(id)))invalid();integer(e.sampleCount,0,20);integer(e.missingCount,0,20);if(Number(e.sampleCount)+Number(e.missingCount)>20||e.evidenceIds.length>Number(e.sampleCount)+Number(e.missingCount))invalid();if(e.state==='UNAVAILABLE'){if(e.value!==null||!['NO_DATA','MISSING_MEASUREMENT','HISTORY_TRUNCATED','NO_CURRENT_TASK','AMBIGUOUS_ORDER'].includes(String(e.reason)))invalid()}else{integer(e.value,0,rule.kind==='QUEUE_WAIT'?3600000:rule.kind==='TASK_FAILURE'?1:20);if(!Number(e.sampleCount)||e.reason!==null||(e.state==='TRIGGERED')!==(Number(e.value)>=rule.threshold)||e.state==='NORMAL'&&Number(e.missingCount))invalid()}}
 return v as AlertStatus
}
async function digest(c:AlertCommand){const encoder=new TextEncoder(),parts=['workflow-quality-alerts-v1',c.id,String(c.revision),c.digest,String(c.expectedVersion),String(c.windowSeconds),...c.rules.flatMap(r=>[r.kind,String(r.threshold)])];const bytes=await crypto.subtle.digest('SHA-256',encoder.encode(parts.map(v=>encoder.encode(v).length+':'+v).join('')));return 'sha256:'+Array.from(new Uint8Array(bytes),n=>n.toString(16).padStart(2,'0')).join('')}
async function receipt(v:unknown,entry:Entry,c:AlertCommand):Promise<AlertReceipt>{const r=object(v,['schemaVersion','requestId','commandDigest','acceptedAt','configuration']),cfg=configuration(r.configuration,entry);if(r.schemaVersion!=='2.0'||r.requestId!==c.requestId||!uuid.test(c.requestId)||r.commandDigest!==await digest(c)||cfg.editVersion!==c.expectedVersion+1||cfg.windowSeconds!==c.windowSeconds||JSON.stringify(cfg.rules)!==JSON.stringify(c.rules)||r.acceptedAt!==cfg.updatedAt)invalid();return v as AlertReceipt}
const root=(e:Entry)=>'/api/v1/integrations/workflows/quality/workflows/'+e.definition.id+'/versions/'+e.definition.revision+'/alerts',options=(signal:AbortSignal)=>({signal,error:(status:number)=>new AlertError(status)})
export async function alertStatus(e:Entry,signal:AbortSignal){return parseAlertStatus(await platformClient.request(root(e),options(signal)),e)}
export async function configureAlerts(e:Entry,c:AlertCommand,signal:AbortSignal){return receipt(await platformClient.request(root(e)+'/configure',{...options(signal),method:'POST',body:c}),e,c)}
export async function alertReceipt(e:Entry,c:AlertCommand,signal:AbortSignal){return receipt(await platformClient.request(root(e)+'/commands/'+c.requestId,options(signal)),e,c)}
