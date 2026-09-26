import { platformClient } from './http.ts'
import { EntityRequestError } from './entities.ts'

export type ObservationQuery = { from: number; till: number; asOf: string | null; source: string; after: string | null; limit: 25 }
export type Observation = { id: string; tenantId: string; entityId: string; sourceInstanceId: string; externalType: string; externalId: string; generation: string;
  observedAt: string; ingestedAt: string; fields: Record<string, unknown>; rawRecordRef: string; mappingRevision: number; timePrecision: string; gaps: string[] }
export type ObservationPage = { storage: string; query: ObservationQuery & { asOf: string }; items: Observation[]; nextCursor: string | null }
const object = (v: unknown): v is Record<string, unknown> => v !== null && typeof v === 'object' && !Array.isArray(v)
const text = (v: unknown, max = 128): v is string => typeof v === 'string' && v.length > 0 && v.length <= max
const cursor = (v: unknown): v is string => typeof v === 'string' && /^[a-zA-Z0-9_.:-]{1,128}$/.test(v)
function check(ok: unknown): asserts ok { if (!ok) throw new Error('观测历史响应结构、时间或范围不正确') }
function nanos(value: unknown): bigint {
  check(typeof value === 'string'); const match = /^(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d)(?:\.(\d{1,9}))?Z$/.exec(value)
  check(match); const base = Date.parse(match[1] + 'Z'); check(Number.isFinite(base) && new Date(base).toISOString().slice(0, 19) === match[1])
  return BigInt(base / 1000) * 1000000000n + BigInt((match[2] ?? '').padEnd(9, '0'))
}
export async function readObservations(entity: { id: string; tenantId: string }, query: ObservationQuery, signal: AbortSignal): Promise<ObservationPage> {
  const params = new URLSearchParams({ from: String(query.from), till: String(query.till), source: query.source, limit: String(query.limit) })
  if (query.asOf !== null) params.set('asOf', query.asOf); if (query.after !== null) params.set('after', query.after)
  const value = await platformClient.request(`/api/v1/entities/${encodeURIComponent(entity.id)}/observations?${params}`, { signal,
    error: status => new EntityRequestError(status, `观测历史读取失败（HTTP ${status}）`) })
  check(object(value) && value.entityId === entity.id && value.tenantId === entity.tenantId && ['memory', 'postgres'].includes(String(value.storage))
    && value.coverage === 'retained-observations-only' && object(value.query) && Array.isArray(value.items) && value.items.length <= 25)
  const q = value.query
  check(q.from === query.from && q.till === query.till && q.source === query.source && q.after === query.after && q.limit === 25)
  const cutoff = nanos(q.asOf); check(query.asOf === null || cutoff === nanos(query.asOf)); check(cutoff <= BigInt(Date.now() + 1000) * 1000000n)
  let previous = query.after ?? ''
  for (const item of value.items) {
    check(object(item) && item.schemaVersion === '1.0' && cursor(item.id) && item.id > previous && item.tenantId === entity.tenantId && item.entityId === entity.id
      && text(item.sourceInstanceId) && text(item.externalType, 64) && text(item.externalId, 512) && text(item.generation)
      && object(item.fields) && Object.keys(item.fields).length <= 256 && text(item.rawRecordRef, 256)
      && typeof item.mappingRevision === 'number' && Number.isSafeInteger(item.mappingRevision) && item.mappingRevision >= 1
      && ['nanoseconds', 'legacy-microseconds'].includes(String(item.timePrecision)) && Array.isArray(item.gaps) && item.gaps.length <= 2
      && item.gaps.every(gap => ['PROJECTION_FIELDS_UNAVAILABLE', 'SOURCE_MODE_UNAVAILABLE'].includes(gap)))
    check(!query.source || item.sourceInstanceId === query.source)
    const observed = nanos(item.observedAt)
    check(observed >= BigInt(query.from) * 1000000000n && observed <= BigInt(query.till) * 1000000000n && nanos(item.ingestedAt) <= cutoff)
    check(new TextEncoder().encode(JSON.stringify(item.fields)).length <= 16384)
    previous = item.id
  }
  check(value.nextCursor === null || (cursor(value.nextCursor) && value.items.length === 25 && value.nextCursor === previous))
  return value as unknown as ObservationPage
}
