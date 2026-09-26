import { platformClient } from './http.ts'
import { getEntity, type EntityItem } from './entities.ts'
import { identityPin, parseIdentityPin, resolveIdentity, type IdentityPin } from './asset-identities.ts'
import { snapshotConfig, sourcePresence, parsePresence, parseSnapshotInput, parseSnapshotReceipt, validateSnapshotSubmission, snapshotTime, equalSnapshotInput, type SnapshotConfig, type SnapshotReceipt, type SnapshotRow, type PresenceRow } from './source-snapshots.ts'

export type CorrectionCommand = {requestId:string;externalId:string;expectedSnapshotId:string;previousEntityId:string;expectedPreviousVersion:number;targetEntityId:string;expectedTargetVersion:number;targetIdentity:IdentityPin;observedAt:string;values:SnapshotRow['values'];reason:string}
export type CorrectionReceipt = {schemaVersion:'1.0';storage:'postgres';dataMode:'import';tenantId:string;actor:string;sourceInstanceId:string;namespace:string;command:CorrectionCommand;previous:Omit<PresenceRow,'status'>;snapshot:SnapshotReceipt;previousEntityVersionAfter:number}
export type CorrectionPreview = {config:SnapshotConfig;previous:EntityItem;target:EntityItem;presence:PresenceRow;command:CorrectionCommand}
export type CorrectionPage = {schemaVersion:'1.0';storage:'postgres';dataMode:'import';tenantId:string;sourceInstanceId:string;namespace:string;entityId:string;after:string|null;limit:number;items:CorrectionReceipt[];nextCursor:string|null}
export class CorrectionError extends Error {constructor(readonly status:number,code:string){super(status===409?`更正未保存：${code}。请刷新预览；生效字段须先撤销。`:status===404?'未找到原操作者回执；不代表未知更正没有生效。':`来源更正请求失败（HTTP ${status}）`)}}
const error=(status:number,code:string)=>new CorrectionError(status,code),root='/api/v1/integrations/cmdb/binding-corrections'
function check(v:unknown):asserts v{if(!v)throw new Error('来源更正响应、身份范围或版本不一致')}
function exact(v:unknown,keys:string[]):asserts v is Record<string,unknown>{check(!!v && typeof v==='object' && !Array.isArray(v) && Object.keys(v).length===keys.length && keys.every(k=>k in v))}
const uuid=(v:unknown):v is string=>typeof v==='string' && /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?![\s\S])/.test(v)
const version=(v:unknown):v is number=>typeof v==='number' && Number.isSafeInteger(v) && v>0 && v<Number.MAX_SAFE_INTEGER
const text=(v:unknown,max:number):v is string=>typeof v==='string' && !!v.trim() && v.length<=max && !/[\x00-\x1f\x7f-\x9f]/.test(v)
const pinEqual=(a:IdentityPin,b:IdentityPin)=>a.id===b.id && a.namespace===b.namespace && a.value===b.value && a.version===b.version
export const correctionInput=(c:CorrectionCommand)=>({requestId:c.requestId,observedAt:c.observedAt,complete:false,records:[{externalId:c.externalId,assetUuid:c.targetIdentity.value,values:c.values}]})
export function parseCorrectionCommand(v:unknown):CorrectionCommand{
  exact(v,['requestId','externalId','expectedSnapshotId','previousEntityId','expectedPreviousVersion','targetEntityId','expectedTargetVersion','targetIdentity','observedAt','values','reason'])
  check(uuid(v.requestId) && uuid(v.expectedSnapshotId) && uuid(v.previousEntityId) && uuid(v.targetEntityId) && version(v.expectedPreviousVersion) && version(v.expectedTargetVersion) && text(v.reason,500));parseIdentityPin(v.targetIdentity)
  const c=v as unknown as CorrectionCommand;parseSnapshotInput(correctionInput(c));check(c.previousEntityId!==c.targetEntityId || c.expectedPreviousVersion===c.expectedTargetVersion);return c
}
export function parseCorrectionReceipt(v:unknown,id:string,config?:SnapshotConfig):CorrectionReceipt{
  exact(v,['schemaVersion','storage','dataMode','tenantId','actor','sourceInstanceId','namespace','command','previous','snapshot','previousEntityVersionAfter'])
  check(v.schemaVersion==='1.0' && v.storage==='postgres' && v.dataMode==='import');const c=parseCorrectionCommand(v.command),s=parseSnapshotReceipt(v.snapshot,id,config)
  check(c.requestId===id && v.tenantId===s.tenantId && v.actor===s.actor && v.sourceInstanceId===s.sourceInstanceId && v.namespace===s.namespace && v.previousEntityVersionAfter===c.expectedPreviousVersion+1 && equalSnapshotInput(s.input,correctionInput(c)))
  check(s.resolved.length===1 && s.resolved[0]!.entityId===c.targetEntityId && pinEqual(s.resolved[0]!.identity,c.targetIdentity) && s.resolved[0]!.entityVersion===c.expectedTargetVersion+1)
  exact(v.previous,['tenantId','entityId','sourceInstanceId','externalId','identity','observedAt','ingestedAt','expiresAt','present','snapshotId']);const p=v.previous
  // This is the retained pre-correction assertion, not a claim about its current identity status.
  const status=snapshotTime(p.expiresAt)<=snapshotTime(s.ingestedAt)?'STALE':p.present?'PRESENT':'ABSENT'
  parsePresence({schemaVersion:'1.0',storage:'postgres',dataMode:'import',tenantId:v.tenantId,entityId:c.previousEntityId,evaluatedAt:s.ingestedAt,items:[{...p,status}]},{tenantId:s.tenantId,id:c.previousEntityId})
  const oldPin=parseIdentityPin(p.identity);check(p.sourceInstanceId===s.sourceInstanceId && oldPin.namespace===s.namespace && p.externalId===c.externalId && p.snapshotId===c.expectedSnapshotId && snapshotTime(p.observedAt)<snapshotTime(c.observedAt))
  check(c.previousEntityId!==c.targetEntityId || !pinEqual(oldPin,c.targetIdentity));return v as unknown as CorrectionReceipt
}
function sameCommand(a:CorrectionCommand,b:CorrectionCommand){return equalSnapshotInput(correctionInput(a),correctionInput(b)) && a.expectedSnapshotId===b.expectedSnapshotId && a.previousEntityId===b.previousEntityId && a.targetEntityId===b.targetEntityId && a.expectedPreviousVersion===b.expectedPreviousVersion && a.expectedTargetVersion===b.expectedTargetVersion && pinEqual(a.targetIdentity,b.targetIdentity) && a.reason===b.reason}
export async function prepareCorrection(previousId:string,externalId:string,targetUuid:string,observedAt:string,values:unknown,reason:string,signal:AbortSignal):Promise<CorrectionPreview>{
  check(uuid(previousId));const [config,previous,resolution]=await Promise.all([snapshotConfig(signal),getEntity(previousId,signal),resolveIdentity(targetUuid,signal)])
  const [target,page]=await Promise.all([getEntity(resolution.identity.entityId,signal),sourcePresence(previous,signal)])
  check(previous.tenantId===config.tenantId && target.tenantId===config.tenantId && resolution.identity.tenantId===config.tenantId && resolution.identity.namespace===config.namespace && resolution.entityVersion===target.version)
  const presence=page.items.find(p=>p.externalId===externalId);check(presence && presence.sourceInstanceId===config.sourceInstanceId && presence.identity.namespace===config.namespace)
  const command=parseCorrectionCommand({requestId:crypto.randomUUID(),externalId,expectedSnapshotId:presence.snapshotId,previousEntityId:previousId,expectedPreviousVersion:previous.version,targetEntityId:target.id,expectedTargetVersion:target.version,targetIdentity:identityPin(resolution.identity),observedAt,values,reason})
  check(snapshotTime(command.observedAt)>snapshotTime(presence.observedAt));check(previous.id!==target.id || !pinEqual(presence.identity,command.targetIdentity));validateSnapshotSubmission(correctionInput(command),config)
  return {config,previous,target,presence,command}
}
export async function submitCorrection(p:CorrectionPreview,signal:AbortSignal){validateSnapshotSubmission(correctionInput(p.command),p.config);const r=parseCorrectionReceipt(await platformClient.request(root,{method:'POST',body:{command:p.command,mappingDigest:p.config.mappingDigest},signal,error}),p.command.requestId,p.config);check(sameCommand(r.command,p.command));return r}
export async function readCorrection(id:string,signal:AbortSignal){check(uuid(id));const config=await snapshotConfig(signal);return parseCorrectionReceipt(await platformClient.request(`${root}/${id}`,{signal,error}),id,config)}
export async function correctionHistory(entityId:string,after:string|null,signal:AbortSignal):Promise<CorrectionPage>{
  check(uuid(entityId) && (after===null || uuid(after)));const config=await snapshotConfig(signal),limit=10
  const v=await platformClient.request(`/api/v1/entities/${entityId}/source-binding-corrections?limit=${limit}${after?`&after=${after}`:''}`,{signal,error,responseBytes:262144});exact(v,['schemaVersion','storage','dataMode','tenantId','sourceInstanceId','namespace','entityId','after','limit','items','nextCursor'])
  check(v.schemaVersion==='1.0' && v.storage==='postgres' && v.dataMode==='import' && v.tenantId===config.tenantId && v.sourceInstanceId===config.sourceInstanceId && v.namespace===config.namespace && v.entityId===entityId && v.after===after && v.limit===limit && Array.isArray(v.items) && v.items.length<=limit)
  let cursor=after??'';for(const raw of v.items){const c=parseCorrectionCommand(raw?.command),r=parseCorrectionReceipt(raw,c.requestId);check(r.tenantId===config.tenantId && r.sourceInstanceId===config.sourceInstanceId && r.namespace===config.namespace && (c.previousEntityId===entityId || c.targetEntityId===entityId) && c.requestId>cursor);cursor=c.requestId}
  check(v.nextCursor===null || (v.nextCursor===cursor && v.items.length===limit));return v as unknown as CorrectionPage
}
