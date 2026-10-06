import {platformClient} from './http.ts'
import {logPosition} from './workflow-log-streams.ts'
import {parseLogStreamData,type LogStreamData} from './workflow-log-streams.ts'
import type {Entry} from './workflows.ts'

export type ReplayCommand={requestId:string;id:string;revision:number;digest:string;from:string;till:string}
export type ReplayExecute={requestId:string;planId:string;inputDigest:string;batchDigest:string}
export type ReplayProof={inputDigest:string;batchDigest:string;inputCount:number;filtered:number;scope:{tenant:string;ownerScope:string;requestId:string;workflowId:string;revision:number;digest:string};indices:number[];positions:string[]}
export type ReplayPlan={schemaVersion:'2.0';requestId:string;reference:{id:string;revision:number;digest:string};commandDigest:string;from:string;till:string;createdAt:string;updatedAt:string;expiresAt:string;purpose:'REBUILD_LOG_PROJECTION';outputPolicy:'ISOLATED_LOG_WINDOW';notifications:false;actions:false;state:'PREPARING'|'READY'|'FAILED';proof:ReplayProof|null;error:string|null}
export type ReplayReceipt={schemaVersion:'2.0';requestId:string;planId:string;reference:ReplayPlan['reference'];commandDigest:string;acceptedAt:string;updatedAt:string;state:'PENDING'|'UNKNOWN'|'CONFIRMED'|'FAILED';error:string|null}
export class ReplayError extends Error{constructor(readonly status:number){super(status===403?'没有此版本的重放权限。':status===409?'重放范围、输入或原请求已变化，请查询原记录。':'重放请求失败 · HTTP '+status)}}
const root='/api/v1/integrations/workflows/log-replays',uuid=/^[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}$(?![\s\S])/,sha=/^sha256:[a-f0-9]{64}$(?![\s\S])/
const errors=['FORBIDDEN','SOURCE_UNAVAILABLE','SOURCE_CHANGED','MAPPING_CHANGED','INVALID_SAMPLE','WINDOW_INCOMPLETE','SOURCE_WINDOW_CHANGED','WINDOW_EXPIRED','RUNTIME_UNAVAILABLE','OUTPUT_REJECTED','OUTPUT_UNCONFIRMED']
function invalid():never{throw Error('重放响应不符合契约，保留原请求进行回查。')}
function obj(v:unknown,keys:string[]):Record<string,unknown>{if(!v||typeof v!=='object'||Array.isArray(v)||Object.keys(v).length!==keys.length||keys.some(k=>!Object.hasOwn(v,k)))invalid();return v as Record<string,unknown>}
function ref(v:unknown,e:Entry){const r=obj(v,['id','revision','digest']);if(r.id!==e.definition.id||r.revision!==e.definition.revision||r.digest!==e.digest)invalid()}
async function digest(parts:string[]){const encoder=new TextEncoder(),bytes=await crypto.subtle.digest('SHA-256',encoder.encode(parts.map(v=>encoder.encode(v).length+':'+v).join('')));return 'sha256:'+Array.from(new Uint8Array(bytes),n=>n.toString(16).padStart(2,'0')).join('')}

const canonical=(ms:number)=>new Date(ms).toISOString().replace('.000Z','Z')
export function replayEligible(e:Entry){return e.state==='PUBLISHED'&&e.definition.source.kind==='ZABBIX_LOG'&&Boolean(e.definition.source.configuration&&e.definition.source.log&&'kind' in e.definition.target&&e.definition.target.kind==='LOG')}
export async function parseReplayPlan(v:unknown,e:Entry,c?:ReplayCommand):Promise<ReplayPlan>{
 const p=obj(v,['schemaVersion','requestId','reference','commandDigest','from','till','createdAt','updatedAt','expiresAt','purpose','outputPolicy','notifications','actions','state','proof','error']);ref(p.reference,e)
 const from=logPosition(p.from),till=logPosition(p.till),at=logPosition(p.createdAt),updated=logPosition(p.updatedAt),expires=logPosition(p.expiresAt)
 if(p.schemaVersion!=='2.0'||typeof p.requestId!=='string'||!uuid.test(p.requestId)||p.notifications!==false||p.actions!==false||p.purpose!=='REBUILD_LOG_PROJECTION'||p.outputPolicy!=='ISOLATED_LOG_WINDOW'||!['PREPARING','READY','FAILED'].includes(String(p.state))||till-from!==60000000000n||from%1000000000n||at<till+10000000000n||from<at-86400000000000n||updated<at||expires-at!==600000000000n||(p.state==='READY')!==(p.proof!==null)||(p.state==='FAILED')!==(p.error!==null)||p.error!==null&&(!errors.includes(String(p.error))||String(p.error).startsWith('OUTPUT_')))invalid()
 if(p.commandDigest!==await digest(['log-replay-plan-v1',e.definition.id,String(e.definition.revision),e.digest,String(p.from),String(p.till)]))invalid()
 if(c&&(p.requestId!==c.requestId||p.from!==c.from||p.till!==c.till||e.definition.id!==c.id||e.definition.revision!==c.revision||e.digest!==c.digest))invalid()
 if(p.proof!==null){const q=obj(p.proof,['inputDigest','batchDigest','inputCount','filtered','scope','indices','positions']);if(!sha.test(String(q.inputDigest))||!sha.test(String(q.batchDigest))||!['inputCount','filtered'].every(k=>Number.isSafeInteger(q[k])&&Number(q[k])>=0&&Number(q[k])<=1000)||!Array.isArray(q.indices)||!Array.isArray(q.positions)||q.indices.length>1000||q.positions.length!==q.inputCount||Number(q.filtered)+q.indices.length!==q.inputCount)invalid()
  const indices=q.indices as unknown[],positions=q.positions as unknown[]
  if(indices.some((n,i)=>!Number.isSafeInteger(n)||Number(n)<0||Number(n)>=Number(q.inputCount)||i>0&&Number(indices[i-1])>=Number(n)))invalid()
  if(positions.some((v,i)=>logPosition(v)<from||logPosition(v)>=till||i>0&&logPosition(positions[i-1])>=logPosition(v)))invalid()
  const s=obj(q.scope,['tenant','ownerScope','requestId','workflowId','revision','digest'])
  if(typeof s.tenant!=='string'||!/^[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}$(?![\s\S])/.test(s.tenant)||typeof s.ownerScope!=='string'||!/^[a-f0-9]{64}$(?![\s\S])/.test(s.ownerScope)||s.requestId!==p.requestId||s.workflowId!==e.definition.id||s.revision!==e.definition.revision||s.digest!==e.digest)invalid()
  if(!indices.length&&q.batchDigest!==await digest(['workflow-log-window-v1',s.tenant,s.ownerScope,String(s.requestId),String(s.workflowId),String(s.revision),String(s.digest),String(p.from),String(p.till),String(q.inputCount),String(q.filtered)]))invalid()
 }
 return v as ReplayPlan
}
export async function parseReplayReceipt(v:unknown,e:Entry,p:ReplayPlan,c?:ReplayExecute):Promise<ReplayReceipt>{const r=obj(v,['schemaVersion','requestId','planId','reference','commandDigest','acceptedAt','updatedAt','state','error']);ref(r.reference,e);if(!p.proof||r.schemaVersion!=='2.0'||typeof r.requestId!=='string'||!uuid.test(r.requestId)||r.planId!==p.requestId||!['PENDING','UNKNOWN','CONFIRMED','FAILED'].includes(String(r.state))||logPosition(r.acceptedAt)<logPosition(p.createdAt)||logPosition(r.updatedAt)<logPosition(r.acceptedAt)||['UNKNOWN','FAILED'].includes(String(r.state))!==(r.error!==null)||r.error!==null&&!errors.includes(String(r.error))||r.state==='UNKNOWN'&&r.error!=='OUTPUT_UNCONFIRMED'||r.state==='FAILED'&&r.error==='OUTPUT_UNCONFIRMED'||r.commandDigest!==await digest(['log-replay-execute-v1',p.requestId,p.proof.inputDigest,p.proof.batchDigest])||c&&(r.requestId!==c.requestId||r.planId!==c.planId||p.proof.inputDigest!==c.inputDigest||p.proof.batchDigest!==c.batchDigest))invalid();return v as ReplayReceipt}
const options=(signal:AbortSignal)=>({signal,error:(status:number)=>new ReplayError(status)})
export async function replayPlans(e:Entry,signal:AbortSignal){const v=obj(await platformClient.request(root+'/workflows/'+e.definition.id+'/versions/'+e.definition.revision+'/'+encodeURIComponent(e.digest)+'/plans',options(signal)),['items','truncated']);if(!Array.isArray(v.items)||v.items.length>20||typeof v.truncated!=='boolean')invalid();const items=await Promise.all(v.items.map(v=>parseReplayPlan(v,e)));if(new Set(items.map(p=>p.requestId)).size!==items.length)invalid();return {items,truncated:v.truncated}}
export async function replayPlan(e:Entry,id:string,signal:AbortSignal,c?:ReplayCommand){if(!uuid.test(id))throw Error('请输入完整重放 UUID。');return parseReplayPlan(await platformClient.request(root+'/plans/'+id,options(signal)),e,c)}
export async function createReplay(e:Entry,c:ReplayCommand,signal:AbortSignal){return parseReplayPlan(await platformClient.request(root+'/plans',{...options(signal),method:'POST',body:c}),e,c)}
export async function executeReplay(e:Entry,p:ReplayPlan,c:ReplayExecute,signal:AbortSignal){return parseReplayReceipt(await platformClient.request(root+'/execute',{...options(signal),method:'POST',body:c}),e,p,c)}
export async function replayReceipt(e:Entry,p:ReplayPlan,c:ReplayExecute,signal:AbortSignal){return parseReplayReceipt(await platformClient.request(root+'/commands/'+c.requestId,options(signal)),e,p,c)}
export async function replayExecution(e:Entry,p:ReplayPlan,signal:AbortSignal){const v=obj(await platformClient.request(root+'/plans/'+p.requestId+'/execution',options(signal)),['receipt']);return v.receipt===null?null:parseReplayReceipt(v.receipt,e,p)}
export async function verifyReplay(e:Entry,p:ReplayPlan,r:ReplayReceipt,signal:AbortSignal){const next=await parseReplayReceipt(await platformClient.request(root+'/commands/'+r.requestId+'/verification',{...options(signal),method:'POST'}),e,p);if(next.requestId!==r.requestId)invalid();return next}
export function replayWindow(requestId:string,e:Entry,start:string):ReplayCommand{const ms=Date.parse(start);if(!Number.isFinite(ms)||ms%1000)throw Error('请输入精确到秒的历史起始时间。');return {requestId,id:e.definition.id,revision:e.definition.revision,digest:e.digest,from:canonical(ms),till:canonical(ms+60000)}}
export async function replayData(p:ReplayPlan,afterIndex:number,signal:AbortSignal):Promise<LogStreamData>{
 if(!p.proof||afterIndex< -1||afterIndex>=1000||afterIndex!==-1&&!p.proof.indices.includes(afterIndex))throw Error('重放记录位置无效。')
 return parseLogStreamData(await platformClient.request(root+'/plans/'+p.requestId+'/records'+(afterIndex<0?'':'/after/'+afterIndex),options(signal)),{id:p.requestId,indices:p.proof.indices,positions:p.proof.positions},afterIndex)
}
