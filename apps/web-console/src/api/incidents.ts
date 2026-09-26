import { platformClient } from './http.ts'
// Handwritten boundary adapter for contracts/schemas/v1/incident-*.schema.json.
export const INCIDENT_STATUSES = ['OPEN', 'INVESTIGATING', 'MITIGATED', 'RESOLVED', 'CLOSED'] as const
export type IncidentStatus = (typeof INCIDENT_STATUSES)[number]
export type IncidentHeader = { schemaVersion: '1.0'; id: string; tenantId: string; title: string; status: IncidentStatus; severity: number; version: number; createdAt: string }
export type Problem = {
  observation: { schemaVersion: '1.0'; tenantId: string; sourceInstanceId: string; problemEventId: string; triggerId: string; title: string; severity: number;
    occurredAt: string; observedAt: string; hostIds: string[]; suppressed: boolean; recoveryEventId: string | null; recoveredAt: string | null;
    state: 'ACTIVE' | 'RECOVERED' | 'RECOVERY_UNKNOWN'; gaps: string[] }
  dataMode: 'labeled-fixture' | 'zabbix-jsonrpc'; sourceContract: 'zabbix-7.0-event-v1';
  entities: { hostId: string; entityId: string }[]; firstReceivedAt: string; lastReceivedAt: string
}
export type TimelineEntry = { id: string; kind: 'PROBLEM' | 'RECOVERY' | 'STATUS_CHANGE'; occurredAt: string; availableAt: string;
  sourceInstanceId: string | null; problemEventId: string | null; recoveryEventId: string | null;
  fromStatus: IncidentStatus | null; toStatus: IncidentStatus | null; actor: string | null }
export type IncidentRecord = { schemaVersion: '1.0'; incident: IncidentHeader; problems: Problem[]; timeline: TimelineEntry[]; gaps: string[];
  organization?: { version: number; changeId: string; mergedInto: string | null } }
export type IncidentDetail = { storage: 'memory' | 'postgres'; record: IncidentRecord }
export type IncidentPage = { storage: 'memory' | 'postgres'; items: IncidentHeader[]; nextCursor: string | null }
export type ProblemWindow = { from: number; till: number; afterEventId: string | null; limit: number }
export type ImportResult = { storage: 'memory' | 'postgres'; dataMode: Problem['dataMode']; sourceInstanceId: string; accepted: number;
  createdIncidents: number; changedIncidents: number; unmappedHosts: number; nextAfterEventId: string | null }
export type TransitionRequest = { expectedVersion: number; target: IncidentStatus; requestKey: string }
export class IncidentRequestError extends Error {
  constructor(readonly status: number) { super(`Incident 请求失败（HTTP ${status}）`) }
}
const object = (v: unknown): v is Record<string, unknown> => v !== null && typeof v === 'object' && !Array.isArray(v)
const uuid = (v: unknown): v is string => typeof v === 'string' && /^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(v)
const str = (v: unknown, max: number): v is string => typeof v === 'string' && v.length > 0 && v.length <= max
const integer = (v: unknown, min: number, max = Number.MAX_SAFE_INTEGER): v is number => typeof v === 'number' && Number.isSafeInteger(v) && v >= min && v <= max
const instant = (v: unknown): v is string => typeof v === 'string' && /^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,9})?Z$/.test(v) && Number.isFinite(Date.parse(v))
const source = (v: unknown): v is string => typeof v === 'string' && /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/.test(v)
const eventId = (v: unknown): v is string => typeof v === 'string' && /^[1-9][0-9]{0,19}$/.test(v) && BigInt(v) <= 18446744073709551615n
const status = (v: unknown): v is IncidentStatus => INCIDENT_STATUSES.includes(v as IncidentStatus)
const storage = (v: unknown): v is IncidentDetail['storage'] => v === 'memory' || v === 'postgres'
const mode = (v: unknown): v is Problem['dataMode'] => v === 'labeled-fixture' || v === 'zabbix-jsonrpc'
function requireShape(ok: unknown): asserts ok { if (!ok) throw new Error('Incident 响应结构或关联范围不正确') }
function header(v: unknown): IncidentHeader {
  requireShape(object(v) && v.schemaVersion === '1.0' && uuid(v.id) && str(v.tenantId, 128) && str(v.title, 300)
    && status(v.status) && integer(v.severity, 0, 5) && integer(v.version, 1) && instant(v.createdAt))
  return v as unknown as IncidentHeader
}
export function parseProblem(v: unknown, tenant: string): Problem {
  requireShape(object(v) && object(v.observation) && mode(v.dataMode) && v.sourceContract === 'zabbix-7.0-event-v1'
    && Array.isArray(v.entities) && v.entities.length <= 20 && instant(v.firstReceivedAt) && instant(v.lastReceivedAt))
  const p = v.observation
  requireShape(p.schemaVersion === '1.0' && p.tenantId === tenant && source(p.sourceInstanceId) && eventId(p.problemEventId)
    && eventId(p.triggerId) && str(p.title, 300) && integer(p.severity, 0, 5) && instant(p.occurredAt) && instant(p.observedAt)
    && Array.isArray(p.hostIds) && p.hostIds.length <= 20 && p.hostIds.every(eventId) && new Set(p.hostIds).size === p.hostIds.length
    && typeof p.suppressed === 'boolean' && (p.recoveryEventId === null || eventId(p.recoveryEventId))
    && (p.recoveredAt === null || instant(p.recoveredAt)) && Array.isArray(p.gaps))
  requireShape(Date.parse(p.occurredAt) <= Date.parse(p.observedAt) && Date.parse(p.observedAt) <= Date.parse(v.lastReceivedAt)
    && Date.parse(v.firstReceivedAt) <= Date.parse(v.lastReceivedAt))
  const expected = p.recoveryEventId === null ? 'ACTIVE' : p.recoveredAt === null ? 'RECOVERY_UNKNOWN' : 'RECOVERED'
  requireShape(p.state === expected && (p.recoveredAt === null || (p.recoveryEventId !== null
    && Date.parse(p.recoveredAt) >= Date.parse(p.occurredAt) && Date.parse(p.recoveredAt) <= Date.parse(p.observedAt))))
  requireShape(JSON.stringify(p.gaps) === JSON.stringify(expected === 'RECOVERY_UNKNOWN' ? ['RECOVERY_EVENT_UNAVAILABLE'] : []))
  requireShape(v.entities.every(e => object(e) && eventId(e.hostId) && uuid(e.entityId))
    && new Set(v.entities.map(e => e.hostId)).size === v.entities.length)
  return v as unknown as Problem
}
function timeline(v: unknown): TimelineEntry {
  requireShape(object(v) && uuid(v.id) && instant(v.occurredAt) && instant(v.availableAt)
    && Date.parse(v.occurredAt) <= Date.parse(v.availableAt))
  if (v.kind === 'STATUS_CHANGE') {
    requireShape(v.sourceInstanceId === null && v.problemEventId === null && v.recoveryEventId === null
      && status(v.fromStatus) && status(v.toStatus) && str(v.actor, 128))
  } else {
    requireShape((v.kind === 'PROBLEM' || v.kind === 'RECOVERY') && source(v.sourceInstanceId) && eventId(v.problemEventId)
      && (v.kind === 'PROBLEM' ? v.recoveryEventId === null : eventId(v.recoveryEventId))
      && v.fromStatus === null && v.toStatus === null && v.actor === null)
  }
  return v as unknown as TimelineEntry
}
export function parseIncidentDetail(v: unknown, id: string): IncidentDetail {
  requireShape(object(v) && storage(v.storage) && object(v.record))
  const r = v.record; const h = header(r.incident)
  requireShape(r.schemaVersion === '1.0' && h.id === id && Array.isArray(r.problems) && r.problems.length >= 1 && r.problems.length <= 50
    && Array.isArray(r.timeline) && r.timeline.length >= 1 && r.timeline.length <= 100 && Array.isArray(r.gaps) && r.gaps.length <= 4)
  const problems = r.problems.map(p => parseProblem(p, h.tenantId)); const entries = r.timeline.map(timeline)
  const keys = new Set(problems.map(p => `${p.observation.sourceInstanceId}:${p.observation.problemEventId}`))
  requireShape(keys.size === problems.length && new Set(entries.map(e => e.id)).size === entries.length
    && entries.every(e => e.kind === 'STATUS_CHANGE' || keys.has(`${e.sourceInstanceId}:${e.problemEventId}`)))
  const gaps = ['LOGS_NOT_CONNECTED', 'CHANGES_NOT_CONNECTED']
  if (problems.some(p => p.observation.hostIds.length === 0 || p.observation.hostIds.some(id => !p.entities.some(e => e.hostId === id)))) gaps.push('ENTITY_MAPPING_MISSING')
  if (problems.some(p => p.observation.state === 'RECOVERY_UNKNOWN')) gaps.push('RECOVERY_EVENT_UNAVAILABLE')
  const receivedGaps = r.gaps
  requireShape(gaps.length === receivedGaps.length && gaps.every(g => receivedGaps.includes(g)))
  const organization = r.organization
  if (organization !== undefined) requireShape(object(organization) && integer(organization.version, 1, h.version) && uuid(organization.changeId)
    && (organization.mergedInto === null || (uuid(organization.mergedInto) && organization.mergedInto !== h.id)))
  return { storage: v.storage, record: { schemaVersion: '1.0', incident: h, problems, timeline: entries, gaps, ...(organization === undefined ? {} : { organization: organization as NonNullable<IncidentRecord['organization']> }) } }
}
async function request(path: string, signal: AbortSignal, body?: unknown): Promise<unknown> {
  return platformClient.request(path, { signal, body, error: status => new IncidentRequestError(status) })
}

export async function listIncidents(filter: string, after: string | null, signal: AbortSignal): Promise<IncidentPage> {
  const query = new URLSearchParams({ status: filter, limit: '25' }); if (after) query.set('after', after)
  const v = await request(`/api/v1/incidents?${query}`, signal)
  requireShape(object(v) && storage(v.storage) && object(v.query) && v.query.status === filter && v.query.after === after && v.query.limit === 25
    && Array.isArray(v.items) && v.items.length <= 25 && (v.nextCursor === null || uuid(v.nextCursor)))
  const items = v.items.map(header)
  let previous = after ?? ''
  for (const item of items) { requireShape(item.id > previous && (!filter || item.status === filter)); previous = item.id }
  requireShape(v.nextCursor === null || (items.length === 25 && v.nextCursor === items.at(-1)?.id))
  return { storage: v.storage, items, nextCursor: v.nextCursor as string | null }
}
export async function getIncident(id: string, signal: AbortSignal): Promise<IncidentDetail> {
  return parseIncidentDetail(await request(`/api/v1/incidents/${encodeURIComponent(id)}`, signal), id)
}
export async function importProblems(window: ProblemWindow, signal: AbortSignal): Promise<ImportResult> {
  const v = await request('/api/v1/integrations/zabbix/problems/ingest', signal, window)
  requireShape(object(v) && storage(v.storage) && mode(v.dataMode) && source(v.sourceInstanceId) && integer(v.accepted, 0, window.limit)
    && integer(v.createdIncidents, 0, v.accepted) && integer(v.changedIncidents, 0, v.accepted) && v.createdIncidents + v.changedIncidents <= v.accepted
    && integer(v.unmappedHosts, 0, window.limit * 20) && (v.nextAfterEventId === null || (eventId(v.nextAfterEventId)
      && v.accepted === window.limit && BigInt(v.nextAfterEventId) > BigInt(window.afterEventId ?? '0'))))
  return v as unknown as ImportResult
}
export async function transitionIncident(id: string, body: TransitionRequest, signal: AbortSignal): Promise<void> {
  const v = await request(`/api/v1/incidents/${encodeURIComponent(id)}/transitions`, signal, body)
  requireShape(object(v) && storage(v.storage) && v.incidentId === id && v.status === body.target && v.version === body.expectedVersion + 1)
}
