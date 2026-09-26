import { platformClient } from './http.ts'
import { EntityRequestError } from './entities.ts'
import { parseIdentityPin, type IdentityPin } from './asset-identities.ts'

export const reviewFields = ['name', 'ip', 'owner', 'environment'] as const
export type ReviewField = typeof reviewFields[number]
export type Choices = Partial<Record<ReviewField, 'PRIMARY' | 'SUPPLEMENTAL'>>
export type Review = { id: string; tenantId: string; entityId: string; baseVersion: number; version: number; status: 'PENDING' | 'ACCEPTED' | 'REJECTED' | 'REVOKED';
  sourceInstanceId: string; externalId: string; observedAt: string; ingestedAt: string; expiresAt: string; values: Partial<Record<ReviewField, string>>; primaryAtImport: Partial<Record<ReviewField, string>>;
  mappingDigest: string; actor: string; rawRecordRef: string; identity?: IdentityPin; decisions: { requestId: string; action: 'ACCEPT' | 'REJECT' | 'REVOKE'; choices: Choices; reason: string; actor: string; at: string; entityVersion: number }[] }
export type ReviewPage = { storage: string; sourceInstanceId: string; items: Review[]; active: Review | null; nextCursor: string | null; mapping: { digest: string; engine: string } }
export type ReviewRequest = { path: string; body: Record<string, unknown>; source: string; reviewId: string }
type Scope = { id: string; tenantId: string }
const object = (v: unknown): v is Record<string, unknown> => v !== null && typeof v === 'object' && !Array.isArray(v)
const uuid = (v: unknown): v is string => typeof v === 'string' && /^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(v)
const text = (v: unknown, max = 128): v is string => typeof v === 'string' && !!v.trim() && v.length <= max && !/[\x00-\x1f\x7f-\x9f]/.test(v)
const version = (v: unknown): v is number => typeof v === 'number' && Number.isSafeInteger(v) && v > 0
const digest = (v: unknown): v is string => typeof v === 'string' && /^sha256:[0-9a-f]{64}$/.test(v)
function check(ok: unknown): asserts ok { if (!ok) throw new Error('补充来源响应结构或范围不正确') }
function timestamp(v: unknown): number { check(typeof v === 'string' && /^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,9})?Z$/.test(v)); const n = Date.parse(v); check(Number.isFinite(n) && new Date(n).toISOString().slice(0,19) === v.slice(0,19)); return n }
function fields(v: unknown, empty = false): asserts v is Record<ReviewField, string> {
  check(object(v) && (empty || Object.keys(v).length > 0) && Object.entries(v).every(([k,value]) => reviewFields.includes(k as ReviewField) && text(value, k === 'ip' ? 128 : 255)))
}
function review(v: unknown, scope: Scope, source: string): Review {
  check(object(v) && v.schemaVersion === '1.0' && uuid(v.id) && v.tenantId === scope.tenantId && v.entityId === scope.id && v.sourceInstanceId === source
    && text(v.externalId,256) && version(v.baseVersion) && version(v.version) && v.version <= 3 && v.dataMode === 'import' && v.mappingId === 'cmdb-host-import'
    && v.mappingRevision === 1 && digest(v.mappingDigest) && text(v.actor) && v.rawRecordRef === `source-review:${v.id}` && Array.isArray(v.decisions) && v.decisions.length <= 2)
  fields(v.values); fields(v.primaryAtImport,true)
  if(Object.hasOwn(v,'identity')) parseIdentityPin(v.identity)
  const observed = timestamp(v.observedAt), ingested = timestamp(v.ingestedAt)
  check(observed >= 0 && observed <= ingested && timestamp(v.expiresAt) === observed + 7 * 86400000)
  check(v.version === v.decisions.length + 1)
  let status = 'PENDING', previous = ingested
  for (const [index, d] of v.decisions.entries()) {
    check(object(d) && uuid(d.requestId) && text(d.reason,500) && text(d.actor) && version(d.entityVersion) && object(d.choices))
    check(index === 0 ? ['ACCEPT','REJECT'].includes(String(d.action)) : status === 'ACCEPTED' && d.action === 'REVOKE')
    const keys = Object.keys(d.choices)
    check(d.action === 'ACCEPT' ? keys.length === Object.keys(v.values).length && keys.every(k => Object.hasOwn(v.values as object,k) && ['PRIMARY','SUPPLEMENTAL'].includes(String((d.choices as Record<string,unknown>)[k]))) : keys.length === 0)
    const at = timestamp(d.at); check(at >= previous); previous = at
    status = d.action === 'ACCEPT' ? 'ACCEPTED' : d.action === 'REJECT' ? 'REJECTED' : 'REVOKED'
  }
  check(v.status === status)
  return v as unknown as Review
}
const error = (status: number, code: string) => new EntityRequestError(status,
  code === 'CMDB_IMPORT_NOT_CONFIGURED' ? '尚未配置补充来源导入。请配置本地导入来源标识。' : status === 409 ? '记录、资产版本或生效绑定已变化，请重新读取后核对。' : `补充来源请求失败（HTTP ${status}）`)
export async function readReviews(scope: Scope, after: string | null, signal: AbortSignal): Promise<ReviewPage> {
  const v = await platformClient.request(`/api/v1/entities/${scope.id}/source-reviews?limit=25${after ? `&after=${after}` : ''}`, { signal, error })
  check(object(v) && v.schemaVersion === '1.0' && v.dataMode === 'import' && v.entityId === scope.id && v.tenantId === scope.tenantId && text(v.sourceInstanceId)
    && /^[a-zA-Z0-9_.:-]{1,128}$/.test(v.sourceInstanceId) && ['memory','postgres'].includes(String(v.storage)) && v.after === after && v.limit === 25
    && object(v.mapping) && digest(v.mapping.digest) && v.mapping.engine === 'cmdb-host-import-v1' && Array.isArray(v.items) && v.items.length <= 25)
  const items = v.items.map(item => review(item, scope, v.sourceInstanceId as string)); let previous = after ?? ''
  for (const item of items) { check(item.id > previous); previous = item.id }
  check(v.nextCursor === null || (uuid(v.nextCursor) && items.length === 25 && v.nextCursor === previous))
  const active = v.active === null ? null : review(v.active, scope, v.sourceInstanceId)
  check(active === null || active.status === 'ACCEPTED')
  return { storage: String(v.storage), sourceInstanceId: v.sourceInstanceId, items, active, nextCursor: v.nextCursor as string | null, mapping: { digest: v.mapping.digest, engine: v.mapping.engine } }
}
export async function submitReview(scope: Scope, request: ReviewRequest, signal: AbortSignal): Promise<Review> {
  const v = review(await platformClient.request(request.path, { method: 'POST', body: request.body, signal, error }), scope, request.source)
  check(v.id === request.reviewId)
  if (request.body.action) {
    const d = v.decisions.at(-1); check(d && d.requestId === request.body.requestId && d.action === request.body.action && d.reason === request.body.reason)
    check(JSON.stringify(Object.entries(d.choices).sort()) === JSON.stringify(Object.entries(request.body.choices as object).sort()))
  } else {
    check(JSON.stringify(v.identity ? Object.entries(v.identity).sort() : null) === JSON.stringify(request.body.identity ? Object.entries(parseIdentityPin(request.body.identity)).sort() : null))
    check(v.baseVersion === request.body.expectedEntityVersion && v.externalId === request.body.externalId && v.mappingDigest === request.body.mappingDigest
      && timestamp(v.observedAt) === timestamp(request.body.observedAt) && JSON.stringify(Object.entries(v.values).sort()) === JSON.stringify(Object.entries(request.body.values as object).sort()))
  }
  return v
}
