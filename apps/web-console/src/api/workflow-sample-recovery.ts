import {platformClient} from './http.ts'
import {logPosition} from './workflow-log-streams.ts'
import type {Entry} from './workflows.ts'

export type SampleKind='METRIC_SAMPLE'|'LOG_SAMPLE'
export type SampleProof={batchId:string;batchDigest:string;updatedAt:string;uncertainRecords:number}
export type SampleRecoveryCommand={requestId:string;id:string;revision:number;digest:string;kind:SampleKind;batchId:string;batchDigest:string;expectedUpdatedAt:string;acknowledgeUncertainOutput:true}
export type SampleRecoveryReceipt={schemaVersion:'2.0';requestId:string;commandDigest:string;reference:{id:string;revision:number;digest:string};kind:SampleKind;batchId:string;batchDigest:string;proofUpdatedAt:string;acceptedAt:string;state:'ABANDONED';uncertainRecords:number}
export class SampleRecoveryError extends Error{constructor(readonly status:number){super('样本终止确认请求失败 · HTTP '+status)}}
const uuid=/^[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}$/
function invalid():never{throw Error('样本终止确认响应不符合契约')}
function object(v:unknown,keys:string[]):Record<string,unknown>{if(!v||typeof v!=='object'||Array.isArray(v)||Object.keys(v).length!==keys.length||keys.some(k=>!Object.hasOwn(v,k)))invalid();return v as Record<string,unknown>}
async function digest(c:SampleRecoveryCommand){const encoder=new TextEncoder(),parts=['workflow-abandon-sample-v1',c.id,String(c.revision),c.digest,c.kind,c.batchId,c.batchDigest,c.expectedUpdatedAt,'true'];const hash=await crypto.subtle.digest('SHA-256',encoder.encode(parts.map(v=>encoder.encode(v).length+':'+v).join('')));return 'sha256:'+Array.from(new Uint8Array(hash),n=>n.toString(16).padStart(2,'0')).join('')}
export async function parseSampleRecovery(v:unknown,entry:Entry,kind:SampleKind,proof:SampleProof,command?:SampleRecoveryCommand):Promise<SampleRecoveryReceipt>{
 const r=object(v,['schemaVersion','requestId','commandDigest','reference','kind','batchId','batchDigest','proofUpdatedAt','acceptedAt','state','uncertainRecords']),ref=object(r.reference,['id','revision','digest'])
 if(r.schemaVersion!=='2.0'||!uuid.test(String(r.requestId))||r.state!=='ABANDONED'||r.kind!==kind||r.batchId!==proof.batchId||r.batchDigest!==proof.batchDigest||r.proofUpdatedAt!==proof.updatedAt||r.uncertainRecords!==proof.uncertainRecords||!Number.isSafeInteger(r.uncertainRecords)||Number(r.uncertainRecords)<1||Number(r.uncertainRecords)>5||ref.id!==entry.definition.id||ref.revision!==entry.definition.revision||ref.digest!==entry.digest||logPosition(r.acceptedAt)<logPosition(r.proofUpdatedAt))invalid()
 const expected:SampleRecoveryCommand=command??{requestId:r.requestId as string,id:entry.definition.id,revision:entry.definition.revision,digest:entry.digest,kind,batchId:proof.batchId,batchDigest:proof.batchDigest,expectedUpdatedAt:proof.updatedAt,acknowledgeUncertainOutput:true}
 if(r.requestId!==expected.requestId||r.commandDigest!==await digest(expected))invalid();return v as SampleRecoveryReceipt
}
const root='/api/v1/integrations/workflows/sample-recovery',options=(signal:AbortSignal)=>({signal,error:(status:number)=>new SampleRecoveryError(status)})
export async function sampleRecoveryStatus(entry:Entry,kind:SampleKind,proof:SampleProof,signal:AbortSignal){const r=object(await platformClient.request(root+'/batches/'+kind+'/'+proof.batchId,options(signal)),['schemaVersion','closure']);if(r.schemaVersion!=='2.0')invalid();return r.closure===null?null:parseSampleRecovery(r.closure,entry,kind,proof)}
export async function abandonSample(entry:Entry,kind:SampleKind,proof:SampleProof,command:SampleRecoveryCommand,signal:AbortSignal){return parseSampleRecovery(await platformClient.request(root+'/abandon',{...options(signal),method:'POST',body:command}),entry,kind,proof,command)}
export async function sampleRecoveryReceipt(entry:Entry,kind:SampleKind,proof:SampleProof,command:SampleRecoveryCommand,signal:AbortSignal){return parseSampleRecovery(await platformClient.request(root+'/commands/'+command.requestId,options(signal)),entry,kind,proof,command)}
