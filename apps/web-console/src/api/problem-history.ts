import { platformClient } from './http.ts'
import { IncidentRequestError, parseProblem, type IncidentRecord, type Problem } from './incidents.ts'

export type ProblemHistoryQuery = { version: number; from: number; till: number; asOf: string | null; source: string; eventId: string; after: string | null; limit: 25 }
export type ProblemObservation = Omit<Problem, 'lastReceivedAt'> & { schemaVersion: '1.0'; id: string; gaps: string[] }
export type ProblemHistoryPage = { storage: string; query: ProblemHistoryQuery & { asOf: string }; items: ProblemObservation[]; nextCursor: string | null }
const object = (v: unknown): v is Record<string, unknown> => v !== null && typeof v === 'object' && !Array.isArray(v)
const uuid = (v: unknown): v is string => typeof v === 'string' && /^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(v)
function check(ok: unknown): asserts ok { if (!ok) throw new Error('告警观测响应结构、时间或归属不正确') }
function nanos(value: unknown): bigint {
  check(typeof value === 'string'); const match = /^(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d)(?:\.(\d{1,9}))?Z$/.exec(value)
  check(match); const base = Date.parse(match[1] + 'Z'); check(Number.isFinite(base) && new Date(base).toISOString().slice(0, 19) === match[1])
  return BigInt(base / 1000) * 1000000000n + BigInt((match[2] ?? '').padEnd(9, '0'))
}
export async function readProblemHistory(record: IncidentRecord, query: ProblemHistoryQuery, signal: AbortSignal): Promise<ProblemHistoryPage> {
  const params = new URLSearchParams({ version: String(query.version), from: String(query.from), till: String(query.till), source: query.source, eventId: query.eventId, limit: String(query.limit) })
  if (query.asOf !== null) params.set('asOf', query.asOf); if (query.after !== null) params.set('after', query.after)
  const value = await platformClient.request(`/api/v1/incidents/${record.incident.id}/problem-observations?${params}`, { signal, error: status => new IncidentRequestError(status) })
  check(object(value) && value.schemaVersion === '1.0' && value.incidentId === record.incident.id && ['memory', 'postgres'].includes(String(value.storage))
    && value.coverage === 'retained-normalized-current-ownership' && object(value.query) && Array.isArray(value.items) && value.items.length <= 25
    && JSON.stringify(value.gaps) === JSON.stringify(['PRE_RETENTION_HISTORY_UNAVAILABLE', 'VENDOR_RAW_PAYLOAD_NOT_RETAINED']))
  const q = value.query
  check(q.version === record.incident.version && q.version === query.version && q.from === query.from && q.till === query.till
    && q.source === query.source && q.eventId === query.eventId && q.after === query.after && q.limit === 25)
  const cutoff = nanos(q.asOf); check(query.asOf === null || cutoff === nanos(query.asOf)); check(cutoff <= BigInt(Date.now() + 1000) * 1000000n)
  let previous = query.after ?? ''
  for (const item of value.items) {
    check(object(item) && item.schemaVersion === '1.0' && uuid(item.id) && item.id > previous)
    const parsed = parseProblem({ ...item, lastReceivedAt: item.firstReceivedAt }, record.incident.tenantId), p = parsed.observation
    check(record.problems.some(current => current.observation.sourceInstanceId === p.sourceInstanceId && current.observation.problemEventId === p.problemEventId))
    check((!query.source || query.source === p.sourceInstanceId) && (!query.eventId || query.eventId === p.problemEventId))
    check(parsed.entities.every(e => p.hostIds.includes(e.hostId)))
    const missing = p.hostIds.length === 0 || p.hostIds.some(host => !parsed.entities.some(e => e.hostId === host))
    check(JSON.stringify(item.gaps) === JSON.stringify(missing ? ['ENTITY_MAPPING_MISSING'] : []))
    const observed = nanos(p.observedAt), received = nanos(parsed.firstReceivedAt), occurred = nanos(p.occurredAt)
    check(observed >= BigInt(query.from) * 1000000000n && observed <= BigInt(query.till) * 1000000000n && occurred <= observed && received >= observed && received <= cutoff)
    check(p.recoveryEventId !== p.problemEventId && (p.recoveredAt === null || (nanos(p.recoveredAt) >= occurred && nanos(p.recoveredAt) <= observed)))
    previous = item.id
  }
  check(value.nextCursor === null || (uuid(value.nextCursor) && value.items.length === 25 && value.nextCursor === previous))
  return value as unknown as ProblemHistoryPage
}
