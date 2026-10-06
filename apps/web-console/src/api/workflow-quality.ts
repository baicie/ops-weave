import { platformClient } from './http.ts'
import type { Entry } from './workflows.ts'

export type QualityCounts={input:number;accepted:number|null;rejected:number|null;filtered:number|null;deduplicated:number|null;outputExpected:number|null;confirmed:number;outputRejected:number|null;unknown:number|null;pending:number|null;repeatedOutput:number|null;late:number|null}
export type QualityBatch={id:string;observedAt:string;updatedAt:string;from:string|null;till:string|null;unit:'ENTITY'|'POINT'|'LOG_RECORD';state:'READY'|'IN_FLIGHT'|'UNKNOWN'|'FAILED'|'CONFIRMED';coverage:'PAGE'|'WINDOW';sampleRate:null;counts:QualityCounts;error:string|null;reconcilesBatchId:string|null}
export type QualityTask={state:'RUNNING'|'STOPPED'|'FAILED'|'ABANDONED';generation:number;updatedAt:string;error:string|null;pendingBatchId:string|null}
export type QualityReport={schemaVersion:'2.0';asOf:string;reference:{id:string;revision:number;digest:string};kind:'HOST_SCAN'|'METRIC_STREAM'|'LOG_STREAM';task:QualityTask|null;batches:QualityBatch[];truncated:boolean}
export class QualityError extends Error{constructor(readonly status:number){super('运行质量读取失败（HTTP '+status+'）')}}
const uuid=/^[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}$/
const errors=['AUTHORIZATION_EXPIRED','AUTHORIZATION_REVOKED','BACKLOG_LIMIT','CAPACITY','EXECUTION_LIMIT','FORBIDDEN','INVALID_SAMPLE','MAPPING_CHANGED','MODEL_CHANGED','OPERATOR_CHANGED','OPERATOR_PIN_REQUIRED','OUTPUT_REJECTED','OUTPUT_UNAVAILABLE','OUTPUT_UNCONFIRMED','RUNTIME_UNAVAILABLE','SOURCE_CHANGED','SOURCE_UNAVAILABLE','SOURCE_WINDOW_CHANGED','TASK_CHANGED','WINDOW_EXPIRED','WINDOW_INCOMPLETE']
const keys=['input','accepted','rejected','filtered','deduplicated','outputExpected','confirmed','outputRejected','unknown','pending','repeatedOutput','late'] as const
function invalid():never{throw new Error('运行质量响应不符合契约')}
function object(v:unknown,keys:readonly string[]):Record<string,unknown>{if(!v||typeof v!=='object'||Array.isArray(v)||Object.keys(v).length!==keys.length||keys.some(k=>!Object.hasOwn(v,k)))invalid();return v as Record<string,unknown>}
function integer(v:unknown,max=1000,min=0){if(!Number.isSafeInteger(v)||Number(v)<min||Number(v)>max)invalid()}
function instant(v:unknown):bigint{
 if(typeof v!=='string')invalid();const m=/^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d{3}|\d{6}|\d{9}))?Z$/.exec(v);if(!m)invalid();const ms=Date.parse(m[1]+'Z'),fraction=m[2]??''
 if(!Number.isFinite(ms)||ms<0||new Date(ms).toISOString().replace('.000Z','Z')!==m[1]+'Z'||fraction&&(fraction.endsWith('000')||/^0+$/.test(fraction)))invalid();return BigInt(ms)*1000000n+BigInt(fraction.padEnd(9,'0')||'0')
}
const error=(v:unknown)=>{if(v!==null&&!errors.includes(String(v)))invalid()}
const identity=(v:unknown)=>{if(v!==null&&(typeof v!=='string'||!uuid.test(v)))invalid()}
const unit=(entry:Entry)=>({ZABBIX_HOST:'ENTITY',ZABBIX_METRIC:'POINT',ZABBIX_LOG:'LOG_RECORD'} as Record<string,'ENTITY'|'POINT'|'LOG_RECORD'>)[entry.definition.source.kind]
export function parseQualityBatch(value:unknown,entry:Entry,expectedId?:string):QualityBatch{
 const b=object(value,['id','observedAt','updatedAt','from','till','unit','state','coverage','sampleRate','counts','error','reconcilesBatchId']);identity(b.id);identity(b.reconcilesBatchId);error(b.error)
 if(b.id===null||expectedId&&b.id!==expectedId||b.id===b.reconcilesBatchId||b.unit!==unit(entry)||b.sampleRate!==null||!['READY','IN_FLIGHT','UNKNOWN','FAILED','CONFIRMED'].includes(String(b.state))||['READY','IN_FLIGHT','CONFIRMED'].includes(String(b.state))!==(b.error===null)||instant(b.updatedAt)<instant(b.observedAt))invalid()
 const c=object(b.counts,keys);for(const k of keys)if(c[k]!==null)integer(c[k]);integer(c.input);integer(c.confirmed);if(Number(c.confirmed)>Number(c.input))invalid()
 if(b.unit==='ENTITY'){if(b.coverage!=='PAGE'||b.from!==null||b.till!==null||b.reconcilesBatchId!==null||Number(c.input)>5||keys.some(k=>!['input','confirmed'].includes(k)&&c[k]!==null))invalid()}
 else{
  const from=instant(b.from),till=instant(b.till);if(b.coverage!=='WINDOW'||from%1000000000n!==0n||till-from!==60000000000n||instant(b.observedAt)<till+10000000000n||b.state==='READY'||keys.some(k=>c[k]===null)||Number(c.accepted)+Number(c.rejected)+Number(c.filtered)+Number(c.deduplicated)!==c.input||c.accepted!==c.outputExpected||Number(c.confirmed)+Number(c.outputRejected)+Number(c.unknown)+Number(c.pending)!==c.outputExpected||b.unit==='POINT'&&Number(c.input)>600)invalid()
  const n=Number(c.outputExpected);if(c.confirmed!==(b.state==='CONFIRMED'?n:0)||c.outputRejected!==(b.state==='FAILED'?n:0)||c.unknown!==(b.state==='UNKNOWN'?n:0)||c.pending!==(b.state==='IN_FLIGHT'?n:0)||n===0&&b.state!=='CONFIRMED'||Number(c.repeatedOutput)>n||Number(c.late)>n)invalid()
  if(b.reconcilesBatchId===null?(c.late!==0||c.repeatedOutput!==0||b.unit==='LOG_RECORD'&&c.deduplicated!==0):b.unit==='POINT'?Number(c.repeatedOutput)+Number(c.late)!==n:c.late!==n)invalid();if(b.unit==='LOG_RECORD'&&c.repeatedOutput!==0)invalid()
 }
 return value as QualityBatch
}
export function parseQualityReport(value:unknown,entry:Entry):QualityReport{
 const r=object(value,['schemaVersion','asOf','reference','kind','task','batches','truncated']),ref=object(r.reference,['id','revision','digest']),asOf=instant(r.asOf),expected={ENTITY:'HOST_SCAN',POINT:'METRIC_STREAM',LOG_RECORD:'LOG_STREAM'}[unit(entry)??'ENTITY']
 if(r.schemaVersion!=='2.0'||!unit(entry)||ref.id!==entry.definition.id||ref.revision!==entry.definition.revision||ref.digest!==entry.digest||r.kind!==expected||typeof r.truncated!=='boolean'||!Array.isArray(r.batches)||r.batches.length>20||r.truncated&&r.batches.length!==20)invalid()
 const seen=new Set<string>();for(const raw of r.batches){const b=parseQualityBatch(raw,entry);if(seen.has(b.id)||instant(b.updatedAt)>asOf)invalid();seen.add(b.id)}
 if(r.task!==null){const t=object(r.task,['state','generation','updatedAt','error','pendingBatchId']);if(t.state==='ABANDONED'&&(t.pendingBatchId===null||!['OUTPUT_UNCONFIRMED','OUTPUT_UNAVAILABLE'].includes(String(t.error))))invalid();integer(t.generation,1000001,1);error(t.error);identity(t.pendingBatchId);if(!['RUNNING','STOPPED','FAILED','ABANDONED'].includes(String(t.state))||(['FAILED','ABANDONED'].includes(String(t.state)))!==(t.error!==null)||instant(t.updatedAt)>asOf||t.generation===1000001&&!['STOPPED','ABANDONED'].includes(String(t.state)))invalid()}
 return value as QualityReport
}
const root=(e:Entry)=>'/api/v1/integrations/workflows/quality/workflows/'+encodeURIComponent(e.definition.id)+'/versions/'+e.definition.revision
const options=(signal:AbortSignal)=>({signal,error:(status:number)=>new QualityError(status)})
export async function qualityReport(entry:Entry,signal:AbortSignal){return parseQualityReport(await platformClient.request(root(entry),options(signal)),entry)}
export async function qualityBatch(entry:Entry,id:string,signal:AbortSignal){if(!uuid.test(id))throw new Error('请输入完整批次 UUID');return parseQualityBatch(await platformClient.request(root(entry)+'/batches/'+id,options(signal)),entry,id)}
