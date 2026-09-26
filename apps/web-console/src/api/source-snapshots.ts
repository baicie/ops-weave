import { platformClient } from './http.ts'
import { parseIdentityPin, type IdentityPin } from './asset-identities.ts'
import type { EntityItem } from './entities.ts'

export type SnapshotRow = { externalId: string; assetUuid: string; values: Partial<Record<'name'|'ip'|'owner'|'environment',string>> }
export type SnapshotInput = { requestId: string; observedAt: string; complete: boolean; records: SnapshotRow[] }
export type SnapshotConfig = { schemaVersion:'1.0'; storage:'postgres'; dataMode:'import'; tenantId:string; actor:string; sourceInstanceId:string; namespace:string; mappingDigest:string; engine:'registered-cmdb-snapshot-v1'; maxRecords:100; maxAgeSeconds:604800 }
export type SnapshotReceipt = { schemaVersion:'1.0'; storage:'postgres'; dataMode:'import'; tenantId:string; actor:string; sourceInstanceId:string; namespace:string; input:SnapshotInput; ingestedAt:string; mappingDigest:string; engine:'registered-cmdb-snapshot-v1'; markedAbsent:number; resolved:{externalId:string;entityId:string;identity:IdentityPin;reviewId:string;entityVersion:number}[] }
export type PresenceRow = {tenantId:string;entityId:string;sourceInstanceId:string;externalId:string;identity:IdentityPin;observedAt:string;ingestedAt:string;expiresAt:string;present:boolean;snapshotId:string;status:'PRESENT'|'ABSENT'|'STALE'|'IDENTITY_REVOKED'}
export type PresencePage = {schemaVersion:'1.0';storage:'postgres';dataMode:'import';tenantId:string;entityId:string;evaluatedAt:string;items:PresenceRow[]}
export class SnapshotError extends Error {constructor(readonly status:number,code:string){super(status===409?`快照未保存：${code || '来源或标识已变化'}。请核对登记与原回执后重新准备快照。`:status===404?'未找到原操作者回执；不代表未知提交没有生效。':status===503?'CMDB 快照需要已配置的来源、标识命名空间及 PostgreSQL；当前不可用。':`快照请求失败（HTTP ${status}）`)}}
function check(v:unknown):asserts v {if(!v)throw new Error('来源快照响应结构、范围或时间不正确')}
function exact(v:unknown,keys:string[]):asserts v is Record<string,unknown>{check(!!v && typeof v==='object' && !Array.isArray(v) && Object.keys(v).length===keys.length && keys.every(k=>k in v))}
const uuid=(v:unknown):v is string=>typeof v==='string' && /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?![\s\S])/.test(v)
const text=(v:unknown,max=128):v is string=>typeof v==='string' && !!v.trim() && v.length<=max && !/[\x00-\x1f\x7f-\x9f]/.test(v)
const ns=(v:unknown):v is string=>typeof v==='string' && /^[a-z][a-z0-9._-]{0,63}(?![\s\S])/.test(v)
const source=(v:unknown):v is string=>typeof v==='string' && /^[A-Za-z0-9_.:-]{1,128}(?![\s\S])/.test(v)
const digest=(v:unknown):v is string=>typeof v==='string' && /^sha256:[0-9a-f]{64}(?![\s\S])/.test(v)
function time(v:unknown):bigint {check(typeof v==='string' && /^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,9})?Z(?![\s\S])/.test(v));const ms=Date.parse(v);check(ms>=0 && Number.isFinite(ms) && new Date(ms).toISOString().slice(0,19)===v.slice(0,19));return BigInt(Math.floor(ms/1000))*1000000000n+BigInt((v.match(/\.(\d+)Z$/)?.[1]??'').padEnd(9,'0'))}
const week=604800000000000n
export function parseSnapshotInput(v:unknown):SnapshotInput {
  exact(v,['requestId','observedAt','complete','records']);check(uuid(v.requestId) && typeof v.complete==='boolean' && Array.isArray(v.records) && v.records.length<=100);time(v.observedAt)
  for(const row of v.records){exact(row,['externalId','assetUuid','values']);check(text(row.externalId,256));parseIdentityPin({id:v.requestId,namespace:'validation',value:row.assetUuid,version:1});check(!!row.values && typeof row.values==='object' && !Array.isArray(row.values));const entries=Object.entries(row.values);check(entries.length>0 && entries.every(([k,value])=>['name','ip','owner','environment'].includes(k) && text(value,k==='ip'?128:255)))}
  check(new Set(v.records.map(r=>r.externalId)).size===v.records.length && new Set(v.records.map(r=>r.assetUuid)).size===v.records.length);return v as unknown as SnapshotInput
}
function base(v:Record<string,unknown>){check(v.schemaVersion==='1.0' && v.storage==='postgres' && v.dataMode==='import')}
export function parseSnapshotReceipt(v:unknown,id:string,config?:SnapshotConfig):SnapshotReceipt {
  exact(v,['schemaVersion','storage','dataMode','tenantId','actor','sourceInstanceId','namespace','input','ingestedAt','mappingDigest','engine','markedAbsent','resolved']);base(v)
  check(text(v.tenantId) && text(v.actor) && source(v.sourceInstanceId) && ns(v.namespace) && digest(v.mappingDigest) && v.engine==='registered-cmdb-snapshot-v1' && Number.isSafeInteger(v.markedAbsent) && Number(v.markedAbsent)>=0 && Number(v.markedAbsent)<=100)
  const input=parseSnapshotInput(v.input);check(input.requestId===id && (input.complete || v.markedAbsent===0));const observed=time(input.observedAt),ingested=time(v.ingestedAt);check(ingested>=observed && ingested<observed+week)
  check(Array.isArray(v.resolved) && v.resolved.length===input.records.length)
  for(let i=0;i<v.resolved.length;i++){const r=v.resolved[i];exact(r,['externalId','entityId','identity','reviewId','entityVersion']);const pin=parseIdentityPin(r.identity);check(r.externalId===input.records[i]!.externalId && pin.value===input.records[i]!.assetUuid && pin.namespace===v.namespace && uuid(r.entityId) && uuid(r.reviewId) && Number.isSafeInteger(r.entityVersion) && Number(r.entityVersion)>0)}
  check(new Set(v.resolved.map(r=>r.entityId)).size===v.resolved.length)
  if(config)check(v.tenantId===config.tenantId && v.actor===config.actor && v.sourceInstanceId===config.sourceInstanceId && v.namespace===config.namespace && v.mappingDigest===config.mappingDigest)
  return v as unknown as SnapshotReceipt
}
const error=(status:number,code:string)=>new SnapshotError(status,code)
const path='/api/v1/integrations/cmdb/snapshots'
export async function snapshotConfig(signal:AbortSignal):Promise<SnapshotConfig>{const v=await platformClient.request(path+'/config',{signal,error});exact(v,['schemaVersion','storage','dataMode','tenantId','actor','sourceInstanceId','namespace','mappingDigest','engine','maxRecords','maxAgeSeconds']);base(v);check(text(v.tenantId) && text(v.actor) && source(v.sourceInstanceId) && ns(v.namespace) && digest(v.mappingDigest) && v.engine==='registered-cmdb-snapshot-v1' && v.maxRecords===100 && v.maxAgeSeconds===604800);return v as unknown as SnapshotConfig}
export function validateSnapshotSubmission(input:SnapshotInput,config:SnapshotConfig){parseSnapshotInput(input);const now=BigInt(Date.now())*1000000n;check(time(input.observedAt)<=now && time(input.observedAt)+week>now);check(new TextEncoder().encode(JSON.stringify({input,mappingDigest:config.mappingDigest})).byteLength<=65536)}
export async function submitSnapshot(input:SnapshotInput,config:SnapshotConfig,signal:AbortSignal):Promise<SnapshotReceipt>{validateSnapshotSubmission(input,config);const v=parseSnapshotReceipt(await platformClient.request(path,{method:'POST',body:{input,mappingDigest:config.mappingDigest},signal,error,responseBytes:262144}),input.requestId,config);check(JSON.stringify(v.input)===JSON.stringify(input) || equalInput(v.input,input));return v}
function equalInput(a:SnapshotInput,b:SnapshotInput){return a.requestId===b.requestId && time(a.observedAt)===time(b.observedAt) && a.complete===b.complete && a.records.length===b.records.length && a.records.every((r,i)=>r.externalId===b.records[i]?.externalId && r.assetUuid===b.records[i]?.assetUuid && JSON.stringify(Object.entries(r.values).sort())===JSON.stringify(Object.entries(b.records[i]!.values).sort()))}
export { time as snapshotTime, equalInput as equalSnapshotInput }
export async function snapshotReceipt(id:string,signal:AbortSignal):Promise<SnapshotReceipt>{check(uuid(id));return parseSnapshotReceipt(await platformClient.request(`${path}/${id}`,{signal,error,responseBytes:262144}),id)}
export function parsePresence(v:unknown,scope:Pick<EntityItem,'id'|'tenantId'>):PresencePage {
  exact(v,['schemaVersion','storage','dataMode','tenantId','entityId','evaluatedAt','items']);base(v);check(v.tenantId===scope.tenantId && v.entityId===scope.id && Array.isArray(v.items) && v.items.length<=1);const at=time(v.evaluatedAt)
  for(const row of v.items){exact(row,['tenantId','entityId','sourceInstanceId','externalId','identity','observedAt','ingestedAt','expiresAt','present','snapshotId','status']);check(row.tenantId===scope.tenantId && row.entityId===scope.id && source(row.sourceInstanceId) && text(row.externalId,256) && uuid(row.snapshotId) && typeof row.present==='boolean');parseIdentityPin(row.identity);const observed=time(row.observedAt),ingested=time(row.ingestedAt),expires=time(row.expiresAt);check(observed<=ingested && expires===observed+week && ingested<=at);check(row.status==='IDENTITY_REVOKED' || row.status===(expires<=at?'STALE':row.present?'PRESENT':'ABSENT'))}
  return v as unknown as PresencePage
}
export async function sourcePresence(scope:Pick<EntityItem,'id'|'tenantId'>,signal:AbortSignal){return parsePresence(await platformClient.request(`/api/v1/entities/${scope.id}/source-presence`,{signal,error}),scope)}
