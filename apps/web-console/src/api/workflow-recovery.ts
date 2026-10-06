import {platformClient} from './http.ts'
import type {Entry} from './workflows.ts'
import type {QualityReport} from './workflow-quality.ts'

export type RecoveryCommand={requestId:string;id:string;revision:number;digest:string;kind:QualityReport['kind'];batchId:string;expectedGeneration:number;acknowledgeUncertainOutput:true}
export type RecoveryReceipt={requestId:string;commandDigest:string;reference:QualityReport['reference'];kind:QualityReport['kind'];batchId:string;previousGeneration:number;generation:number;acceptedAt:string;state:'ABANDONED';preservedCursor:string|null;confirmedBatches:number;confirmedRecords:number}
export class RecoveryError extends Error{status:number;constructor(status:number){super('终止恢复请求失败 · HTTP '+status);this.status=status}}
const uuid=/^[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}$/,digest=/^sha256:[a-f0-9]{64}$/
function invalid():never{throw Error('终止恢复响应不符合契约')}
function object(v:unknown,keys:string[]):Record<string,unknown>{if(!v||typeof v!=='object'||Array.isArray(v)||Object.keys(v).length!==keys.length||keys.some(k=>!Object.hasOwn(v,k)))invalid();return v as Record<string,unknown>}
async function commandDigest(c:RecoveryCommand){const encoder=new TextEncoder(),parts=['workflow-abandon-recovery-v1',c.id,String(c.revision),c.digest,c.kind,c.batchId,String(c.expectedGeneration),'true'];const bytes=encoder.encode(parts.map(v=>encoder.encode(v).length+':'+v).join(''));return 'sha256:'+Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',bytes)),v=>v.toString(16).padStart(2,'0')).join('')}
export async function parseRecoveryReceipt(v:unknown,c:RecoveryCommand):Promise<RecoveryReceipt>{
 const r=object(v,['requestId','commandDigest','reference','kind','batchId','previousGeneration','generation','acceptedAt','state','preservedCursor','confirmedBatches','confirmedRecords']),ref=object(r.reference,['id','revision','digest'])
 if(!uuid.test(c.requestId)||!uuid.test(c.batchId)||!digest.test(c.digest)||!Number.isSafeInteger(c.expectedGeneration)||c.expectedGeneration<1||c.expectedGeneration>1000000||c.acknowledgeUncertainOutput!==true||r.requestId!==c.requestId||r.batchId!==c.batchId||r.kind!==c.kind||ref.id!==c.id||ref.revision!==c.revision||ref.digest!==c.digest||r.previousGeneration!==c.expectedGeneration||r.generation!==c.expectedGeneration+1||r.state!=='ABANDONED'||r.commandDigest!==await commandDigest(c))invalid()
 const m=typeof r.acceptedAt==='string'?/^(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d)(?:\.(\d{3}|\d{6}|\d{9}))?Z$/.exec(r.acceptedAt):null;if(!m)invalid();const time=Date.parse(m[1]+'Z');if(!Number.isFinite(time)||time<0||new Date(time).toISOString().replace('.000Z','Z')!==m[1]+'Z'||m[2]?.endsWith('000'))invalid()
 if(!Number.isSafeInteger(r.confirmedBatches)||Number(r.confirmedBatches)<0||Number(r.confirmedBatches)>200||!Number.isSafeInteger(r.confirmedRecords)||Number(r.confirmedRecords)<0||Number(r.confirmedRecords)>Number(r.confirmedBatches)*(c.kind==='HOST_SCAN'?5:c.kind==='METRIC_STREAM'?600:1000))invalid()
 if(c.kind==='HOST_SCAN'){if(r.preservedCursor!==null)invalid()}
 else{if(typeof r.preservedCursor!=='string'||!/^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ$/.test(r.preservedCursor)||!Number.isFinite(Date.parse(r.preservedCursor))||Date.parse(r.preservedCursor)>time||Date.parse(r.preservedCursor)<0||new Date(r.preservedCursor).toISOString().replace('.000Z','Z')!==r.preservedCursor)invalid()}
 return v as RecoveryReceipt
}
const root='/api/v1/integrations/workflows/recovery',options=(signal:AbortSignal)=>({signal,error:(status:number)=>new RecoveryError(status)})
export async function abandonRecovery(entry:Entry,c:RecoveryCommand,signal:AbortSignal){if(c.id!==entry.definition.id||c.revision!==entry.definition.revision||c.digest!==entry.digest)invalid();return parseRecoveryReceipt(await platformClient.request(root+'/abandon',{...options(signal),method:'POST',body:c}),c)}
export async function recoveryReceipt(c:RecoveryCommand,signal:AbortSignal){return parseRecoveryReceipt(await platformClient.request(root+'/commands/'+encodeURIComponent(c.requestId),options(signal)),c)}
