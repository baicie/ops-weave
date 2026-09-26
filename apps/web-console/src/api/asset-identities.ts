import { platformClient } from './http.ts'
import { EntityRequestError, type EntityItem } from './entities.ts'
export type IdentityPin = { id: string; namespace: string; value: string; version: 1 }
export type AssetIdentity = { schemaVersion: '1.0'; id: string; tenantId: string; entityId: string; namespace: string; kind: 'asset-uuid'; value: string; version: 1 | 2; status: 'ACTIVE' | 'REVOKED'; verification: 'operator-confirmed'; actor: string; reason: string; assertedAt: string; revocation: null | { requestId: string; actor: string; reason: string; at: string } }
export type IdentityPage = { namespace: string; storage: string; items: AssetIdentity[]; nextCursor: string | null }
export type IdentityRequest = { path: string; body: { requestId: string; expectedEntityVersion: number; expectedNamespace: string; value?: string; reason: string }; action: 'ASSERT' | 'REVOKE'; identityId: string }
export type Resolution = { identity: AssetIdentity; entityVersion: number }
const object = (v: unknown): v is Record<string,unknown> => !!v && typeof v === 'object' && !Array.isArray(v)
function check(v: unknown): asserts v { if (!v) throw new Error('资产身份响应结构或范围不正确') }
const uuid = (v: unknown): v is string => typeof v === 'string' && /^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$(?![\s\S])/.test(v)
const assetUuid = (v: unknown): v is string => typeof v === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$(?![\s\S])/.test(v)
const namespace = (v: unknown): v is string => typeof v === 'string' && /^[a-z][a-z0-9._-]{0,63}$(?![\s\S])/.test(v)
const text = (v: unknown,max: number): v is string => typeof v === 'string' && !!v.trim() && v.length <= max && !/[\x00-\x1f\x7f-\x9f]/.test(v)
const version = (v: unknown): v is number => typeof v === 'number' && Number.isSafeInteger(v) && v > 0
function time(v: unknown): number { check(typeof v === 'string' && /^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,9})?Z$(?![\s\S])/.test(v)); const n = Date.parse(v); check(Number.isFinite(n) && new Date(n).toISOString().slice(0,19) === v.slice(0,19)); return n }
function exact(v: Record<string,unknown>,fields: string[]) { check(Object.keys(v).length === fields.length && Object.keys(v).every(k => fields.includes(k))) }
export function parseIdentityPin(v: unknown): IdentityPin {
  check(object(v)); exact(v,['id','namespace','value','version']); check(uuid(v.id) && namespace(v.namespace) && assetUuid(v.value) && v.version === 1); return v as IdentityPin
}
export function identityPin(v: AssetIdentity): IdentityPin { check(v.status === 'ACTIVE'); return {id:v.id,namespace:v.namespace,value:v.value,version:1} }
function identity(v: unknown): AssetIdentity {
  check(object(v)); exact(v,['schemaVersion','id','tenantId','entityId','namespace','kind','value','version','status','verification','actor','reason','assertedAt','revocation'])
  check(v.schemaVersion === '1.0' && uuid(v.id) && uuid(v.entityId) && text(v.tenantId,128) && namespace(v.namespace) && v.kind === 'asset-uuid' && assetUuid(v.value)
    && v.verification === 'operator-confirmed' && text(v.actor,128) && text(v.reason,500)); const asserted = time(v.assertedAt); check(asserted >= 0)
  if (v.status === 'ACTIVE') check(v.version === 1 && v.revocation === null)
  else { check(v.status === 'REVOKED' && v.version === 2 && object(v.revocation)); const r = v.revocation; exact(r,['requestId','actor','reason','at']); check(uuid(r.requestId) && text(r.actor,128) && text(r.reason,500) && time(r.at) >= asserted) }
  return v as unknown as AssetIdentity
}
const error = (status: number) => new EntityRequestError(status, status === 503 ? '资产身份功能未配置或存储不可用。' : status === 409 ? '身份已被占用、资产版本已变化，或仍有依赖的生效字段。请刷新核对；撤销身份前先撤销依赖字段。' : status === 404 ? '未找到可管理的已登记资产。' : `资产身份请求失败（HTTP ${status}）`)
export async function readIdentities(scope: Pick<EntityItem,'id'|'tenantId'>,after: string | null,signal: AbortSignal): Promise<IdentityPage> {
  const v = await platformClient.request(`/api/v1/entities/${scope.id}/identity-keys?limit=25${after ? `&after=${after}` : ''}`,{signal,error}); check(object(v))
  exact(v,['schemaVersion','storage','tenantId','entityId','namespace','items','after','limit','nextCursor'])
  check(v.schemaVersion === '1.0' && ['memory','postgres'].includes(String(v.storage)) && v.tenantId === scope.tenantId && v.entityId === scope.id && namespace(v.namespace) && Array.isArray(v.items) && v.items.length <= 25 && v.after === after && v.limit === 25)
  const items = v.items.map(identity); let previous = after ?? ''
  for (const item of items) { check(item.tenantId === scope.tenantId && item.entityId === scope.id && item.namespace === v.namespace && item.id > previous); previous = item.id }
  check(v.nextCursor === null || (uuid(v.nextCursor) && items.length === 25 && v.nextCursor === previous))
  return {namespace:v.namespace,storage:String(v.storage),items,nextCursor:v.nextCursor as string | null}
}
export async function submitIdentity(scope: Pick<EntityItem,'id'|'tenantId'>,request: IdentityRequest,signal: AbortSignal): Promise<{ identity: AssetIdentity; entityVersion: number }> {
  const v = await platformClient.request(request.path,{method:'POST',body:request.body,signal,error}); check(object(v)); exact(v,['schemaVersion','requestId','action','identity','entityVersion'])
  check(v.schemaVersion === '1.0' && v.requestId === request.body.requestId && v.action === request.action && version(v.entityVersion) && v.entityVersion === request.body.expectedEntityVersion + 1)
  const r = identity(v.identity); check(r.tenantId === scope.tenantId && r.entityId === scope.id && r.id === request.identityId && r.namespace === request.body.expectedNamespace)
  if (request.action === 'ASSERT') check(r.status === 'ACTIVE' && r.value === request.body.value && r.reason === request.body.reason)
  else check(r.status === 'REVOKED' && r.revocation?.requestId === request.body.requestId && r.revocation.reason === request.body.reason)
  return {identity:r,entityVersion:v.entityVersion}
}
export async function resolveIdentity(value: string,signal: AbortSignal): Promise<Resolution> {
  if (!assetUuid(value)) throw new Error('请输入资产登记系统中的规范 UUID。')
  const v = await platformClient.request('/api/v1/inventory/resolve-identity',{method:'POST',body:{value},signal,error}); check(object(v)); exact(v,['schemaVersion','storage','method','identity','entityVersion'])
  check(v.schemaVersion === '1.0' && ['memory','postgres'].includes(String(v.storage)) && v.method === 'registered-asset-uuid' && version(v.entityVersion))
  const r = identity(v.identity); check(r.value === value && r.status === 'ACTIVE'); return {identity:r,entityVersion:v.entityVersion}
}
