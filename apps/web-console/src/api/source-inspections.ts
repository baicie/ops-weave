import { platformClient } from './http.ts'
export type InspectionKind = 'TEST' | 'DISCOVER' | 'DISCOVER_METRICS' | 'DISCOVER_METRIC_PAGE'
export interface InspectionCommand { requestId: string; configurationRevision: number; connectionDigest: string; previousRequestId?: string | null }
export interface PendingInspection { kind: InspectionKind; dataMode: 'fixture' | 'zabbix-jsonrpc'; command: InspectionCommand; previous?: Inspection }
export interface DiscoveredField { name: string; type: string; nullable: boolean }
export interface DiscoveredMetric { itemId: string; hostId: string; sourceKey: string; name: string; sourceUnit: string; sourceValueType: string; mappingStatus: 'MAPPED' | 'NO_MAPPING' | 'TYPE_MISMATCH'; mapping: { id: string; revision: number; digest: string; metricKey: string; unit: string; valueType: string; valueTransform: string } | null }
export interface MetricDiscovery { items: DiscoveredMetric[]; complete: boolean; scanConsistency: string; statusCode: string; fingerprint: string; scope: 'FIRST_ITEM_PAGE'; limit: 20 }
export interface MetricManifest { snapshotId: string; asOf: string; expiresAt: string; total: number; fingerprint: string }
export interface MetricPage { manifest: MetricManifest | null; offset: number; items: DiscoveredMetric[]; statusCode: 'READ_VERIFIED' | 'MEMBERSHIP_CHANGED' | 'CAPACITY' | 'UNREACHABLE'; scanConsistency: string; fingerprint: string; complete: boolean; nextOffset: number | null; limit: 20 }
export interface Inspection {
  requestId: string; sourceId: string; kind: InspectionKind; configurationRevision: number; connectionDigest: string; dataMode: 'fixture' | 'zabbix-jsonrpc'; commandDigest: string
  state: 'PENDING' | 'COMPLETED' | 'UNKNOWN'; asOf: string; deadline: string; availableAt: string | null; expiresAt: string | null
  check: { reachable: boolean; statusCode: string; reportedVersion: string | null } | null
  discovery: { fields: DiscoveredField[]; observedRecords: number; complete: boolean; scanConsistency: string; statusCode: string; fingerprint: string; scope: 'FIRST_HOST_PAGE' } | null
  metricDiscovery?: MetricDiscovery | null
  previousRequestId?: string | null; metricPage?: MetricPage | null
}
export interface InspectionView { inspection: Inspection; validity: 'CURRENT' | 'STALE' | 'EXPIRED' | 'UNVERIFIED' }
const uuid = /^[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}$/, hash = /^sha256:[a-f0-9]{64}$/
function invalid(): never { throw new Error('测试或发现回执与原配置不一致，请保留请求并重新核对') }
function obj(value: unknown, keys: string[]): Record<string, any> { if (!value || typeof value !== 'object' || Array.isArray(value) || Object.keys(value).length !== keys.length || !keys.every(key => Object.hasOwn(value, key))) invalid(); return value as Record<string, any> }
function instant(v: unknown) { if (typeof v !== 'string' || v.length > 40 || !/^\d{4}-\d{2}-\d{2}T.*(?:Z|[+-]\d{2}:\d{2})$/.test(v) || !Number.isFinite(Date.parse(v))) invalid(); return Date.parse(v) }
async function digest(parts: string[]) { const encoder = new TextEncoder(), chunks: Uint8Array[] = []; for (const part of parts) { const bytes = encoder.encode(part); chunks.push(encoder.encode(bytes.length + ':'), bytes) }; const bytes = new Uint8Array(chunks.reduce((n, p) => n + p.length, 0)); let offset = 0; for (const p of chunks) { bytes.set(p, offset); offset += p.length }; return 'sha256:' + Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', bytes)), x => x.toString(16).padStart(2, '0')).join('') }
async function view(value: unknown, id: string): Promise<InspectionView> {
  const v = obj(value, ['inspection', 'validity']), i = obj(v.inspection, ['requestId', 'sourceId', 'kind', 'configurationRevision', 'connectionDigest', 'dataMode', 'commandDigest', 'state', 'asOf', 'deadline', 'availableAt', 'expiresAt', 'check', 'discovery', ...['metricDiscovery', 'previousRequestId', 'metricPage'].filter(key => v.inspection && Object.hasOwn(v.inspection, key))])
  if (i.sourceId !== id || !uuid.test(i.requestId) || !['TEST', 'DISCOVER', 'DISCOVER_METRICS', 'DISCOVER_METRIC_PAGE'].includes(i.kind) || !Number.isInteger(i.configurationRevision) || i.configurationRevision < 1 || i.configurationRevision > 100 || !hash.test(i.connectionDigest) || !['fixture', 'zabbix-jsonrpc'].includes(i.dataMode) || !['PENDING', 'COMPLETED', 'UNKNOWN'].includes(i.state) || !['CURRENT', 'STALE', 'EXPIRED', 'UNVERIFIED'].includes(v.validity)) invalid()
  if (i.kind === 'DISCOVER_METRIC_PAGE' ? !Object.hasOwn(i, 'previousRequestId') || !Object.hasOwn(i, 'metricPage') || i.previousRequestId !== null && (!uuid.test(i.previousRequestId) || i.previousRequestId === i.requestId) : i.previousRequestId != null || i.metricPage != null) invalid()
  if (i.commandDigest !== await digest(i.kind === 'DISCOVER_METRIC_PAGE' ? ['source-metric-page-command-v1', id, i.requestId, String(i.configurationRevision), i.connectionDigest, i.previousRequestId ?? 'initial'] : ['source-inspection-v2', id, i.requestId, i.kind, String(i.configurationRevision), i.connectionDigest]) || instant(i.deadline) - instant(i.asOf) !== 65000) invalid()
  if (i.state !== 'COMPLETED') { if (i.availableAt !== null || i.expiresAt !== null || i.check !== null || i.discovery !== null || i.metricDiscovery != null || i.metricPage != null || v.validity !== 'UNVERIFIED') invalid() }
  else {
    if (instant(i.availableAt) < instant(i.asOf) || instant(i.availableAt) > instant(i.deadline) || instant(i.expiresAt) - instant(i.availableAt) !== 900000) invalid()
    if (i.kind !== 'DISCOVER_METRICS' && i.metricDiscovery != null) invalid()
    if (i.kind === 'DISCOVER_METRIC_PAGE') { if (i.check !== null || i.discovery !== null) invalid(); await metricPage(i.metricPage, i) }
    else if (i.kind === 'DISCOVER_METRICS') { if (i.check !== null || i.discovery !== null) invalid(); await metrics(i.metricDiscovery) }
    else if (i.kind === 'TEST') { if (i.discovery !== null) invalid(); const c = obj(i.check, ['reachable', 'statusCode', 'reportedVersion']); if (typeof c.reachable !== 'boolean' || !['READ_VERIFIED', 'UNREACHABLE', 'UNVERIFIED', 'LABELED_FIXTURE'].includes(c.statusCode) || c.reachable === (c.statusCode === 'UNREACHABLE') || c.reportedVersion !== null && (typeof c.reportedVersion !== 'string' || !/^[ -~]{1,32}$/.test(c.reportedVersion))) invalid() }
    else {
      if (i.check !== null) invalid(); const d = obj(i.discovery, ['fields', 'observedRecords', 'complete', 'scanConsistency', 'statusCode', 'fingerprint', 'scope'])
      if (!Array.isArray(d.fields) || d.fields.length > 5 || !Number.isInteger(d.observedRecords) || d.observedRecords < 0 || d.observedRecords > 5 || typeof d.complete !== 'boolean' || d.scope !== 'FIRST_HOST_PAGE' || !['HOSTID_WATERMARK', 'LABELED_FIXTURE', 'UNVERIFIED'].includes(d.scanConsistency) || !['READ_VERIFIED', 'INCOMPLETE', 'UNREACHABLE'].includes(d.statusCode) || !hash.test(d.fingerprint)) invalid()
      for (const value of d.fields) { const f = obj(value, ['name', 'type', 'nullable']); if (!['hostid', 'host', 'name', 'status', 'interfaces.ip'].includes(f.name) || !['TEXT', 'NUMBER', 'BOOLEAN', 'TEXT_ARRAY', 'NULL', 'MIXED'].includes(f.type) || typeof f.nullable !== 'boolean') invalid() }
      if (new Set(d.fields.map((f: DiscoveredField) => f.name)).size !== d.fields.length || !d.observedRecords && d.fields.length || d.complete && (d.scanConsistency === 'UNVERIFIED' || d.statusCode !== 'READ_VERIFIED') || d.statusCode === 'UNREACHABLE' && (d.fields.length || d.observedRecords || d.complete)) invalid()
      const parts = ['source-host-fields-v1']; for (const f of [...d.fields].sort((a, b) => a.name.localeCompare(b.name))) parts.push(f.name, f.type, String(f.nullable)); if (d.fingerprint !== await digest(parts)) invalid()
    }
    if (v.validity === 'CURRENT' && (Date.now() >= instant(i.expiresAt) || i.check && (!i.check.reachable || i.check.statusCode === 'UNVERIFIED') || i.discovery && !i.discovery.complete || i.metricDiscovery && !i.metricDiscovery.complete || i.metricPage && (i.metricPage.statusCode !== 'READ_VERIFIED' || Date.now() >= instant(i.metricPage.manifest.expiresAt)))) invalid()
  }
  return v as unknown as InspectionView
}
function label(v: unknown, max: number, empty = false) { return typeof v === 'string' && v.length <= max && (empty || !!v.trim()) && !/[\x00-\x1f\x7f-\x9f]/.test(v) }
async function metrics(value: unknown) {
  const d = obj(value, ['items', 'complete', 'scanConsistency', 'statusCode', 'fingerprint', 'scope', 'limit'])
  if (!Array.isArray(d.items) || d.items.length > 20 || typeof d.complete !== 'boolean' || d.scope !== 'FIRST_ITEM_PAGE' || d.limit !== 20 || !['FIRST_PAGE_MATCH', 'LABELED_FIXTURE', 'UNVERIFIED'].includes(d.scanConsistency) || !['READ_VERIFIED', 'INCOMPLETE', 'UNREACHABLE'].includes(d.statusCode) || !hash.test(d.fingerprint) || d.complete && d.scanConsistency === 'UNVERIFIED' || (d.statusCode === 'READ_VERIFIED') !== d.complete || d.statusCode === 'UNREACHABLE' && (d.items.length || d.complete || d.scanConsistency !== 'UNVERIFIED')) invalid()
  if (d.fingerprint !== await itemDigest(d.items)) invalid()
}
export async function itemDigest(items: unknown): Promise<string> {
  if (!Array.isArray(items) || items.length > 20) invalid()
  const parts = ['source-metric-metadata-v1']; let previous = 0n
  for (const value of items) {
    const i = obj(value, ['itemId', 'hostId', 'sourceKey', 'name', 'sourceUnit', 'sourceValueType', 'mappingStatus', 'mapping'])
    if (typeof i.itemId !== 'string' || !/^[1-9][0-9]{0,19}$/.test(i.itemId) || typeof i.hostId !== 'string' || !/^[1-9][0-9]{0,19}$/.test(i.hostId) || BigInt(i.itemId) <= previous || !label(i.sourceKey, 2048) || !label(i.name, 512) || !label(i.sourceUnit, 64, true) || !['FLOAT', 'UNSIGNED', 'CHARACTER', 'LOG', 'TEXT', 'BINARY', 'UNKNOWN'].includes(i.sourceValueType) || !['MAPPED', 'NO_MAPPING', 'TYPE_MISMATCH'].includes(i.mappingStatus) || (i.mappingStatus === 'NO_MAPPING') !== (i.mapping === null)) invalid()
    previous = BigInt(i.itemId); parts.push(i.itemId, i.hostId, i.sourceKey, i.name, i.sourceUnit, i.sourceValueType, i.mappingStatus)
    if (i.mapping === null) parts.push('absent')
    else {
      const m = obj(i.mapping, ['id', 'revision', 'digest', 'metricKey', 'unit', 'valueType', 'valueTransform'])
      if (!label(m.id, 96) || !Number.isInteger(m.revision) || m.revision < 1 || !hash.test(m.digest) || !label(m.metricKey, 128) || !label(m.unit, 64) || !['DOUBLE', 'INTEGER', 'STRING'].includes(m.valueType) || !label(m.valueTransform, 96)) invalid()
      const compatible = ['FLOAT', 'UNSIGNED'].includes(i.sourceValueType) ? ['DOUBLE', 'INTEGER'].includes(m.valueType) : ['CHARACTER', 'LOG', 'TEXT'].includes(i.sourceValueType) && m.valueType === 'STRING'
      if ((i.mappingStatus === 'MAPPED') !== compatible) invalid()
      parts.push('present', m.id, String(m.revision), m.digest, m.metricKey, m.unit, m.valueType, m.valueTransform)
    }
  }
  return digest(parts)
}
async function metricPage(value: unknown, inspection: Record<string, any>) {
  const p = obj(value, ['manifest', 'offset', 'items', 'statusCode', 'scanConsistency', 'fingerprint', 'complete', 'nextOffset', 'limit'])
  if (!Number.isInteger(p.offset) || p.offset < 0 || p.offset > 980 || p.offset % 20 || p.limit !== 20 || !['READ_VERIFIED', 'MEMBERSHIP_CHANGED', 'CAPACITY', 'UNREACHABLE'].includes(p.statusCode) || !['ITEMID_WATERMARK', 'LABELED_FIXTURE', 'UNVERIFIED'].includes(p.scanConsistency) || typeof p.complete !== 'boolean' || !hash.test(p.fingerprint)) invalid()
  const itemsHash = await itemDigest(p.items), parts = ['source-metric-page-v1']
  if (p.manifest === null) { parts.push('absent'); if (p.offset !== 0) invalid() }
  else {
    const m = obj(p.manifest, ['snapshotId', 'asOf', 'expiresAt', 'total', 'fingerprint'])
    if (!uuid.test(m.snapshotId) || !hash.test(m.fingerprint) || !Number.isInteger(m.total) || m.total < 0 || m.total > 1000 || instant(m.expiresAt) - instant(m.asOf) !== 900000 || instant(m.asOf) > instant(inspection.asOf)) invalid()
    if (inspection.previousRequestId === null && (m.snapshotId !== inspection.requestId || m.asOf !== inspection.asOf || p.offset !== 0)) invalid()
    if (inspection.previousRequestId !== null && (m.snapshotId === inspection.requestId || p.offset === 0)) invalid()
    parts.push('present', m.snapshotId, m.asOf, m.expiresAt, String(m.total), m.fingerprint)
  }
  if (p.statusCode === 'READ_VERIFIED') {
    const m = p.manifest; if (!m || p.scanConsistency === 'UNVERIFIED' || p.offset > m.total || p.offset === m.total && p.offset !== 0 || p.items.length !== Math.min(20, m.total - p.offset) || p.complete !== (p.offset + p.items.length === m.total) || p.nextOffset !== (p.complete ? null : p.offset + 20) || instant(inspection.availableAt) >= instant(m.expiresAt)) invalid()
  } else if (p.items.length || p.complete || p.nextOffset !== null || p.scanConsistency !== 'UNVERIFIED') invalid()
  parts.push(String(p.offset), p.statusCode, p.scanConsistency, itemsHash); if (p.fingerprint !== await digest(parts)) invalid()
}
export function sameMetricManifest(a: MetricManifest | null | undefined, b: MetricManifest | null | undefined) { return !!a && !!b && a.snapshotId === b.snapshotId && a.asOf === b.asOf && a.expiresAt === b.expiresAt && a.total === b.total && a.fingerprint === b.fingerprint }
export class InspectionRejectedError extends Error {}
function request(id: string, path: string, signal: AbortSignal, command?: InspectionCommand) {
  if (!uuid.test(id)) invalid()
  return platformClient.request('/api/v2/data-sources/' + id + path, { signal, ...(command ? { method: 'POST' as const, body: command } : {}), error: (status, code) => {
    const messages: Record<string, string> = { CONFLICT: '配置版本已变化、实例已归档或请求键已使用，请读取最新实例后核对', SOURCE_UNAVAILABLE: '保存的连接与当前登记配置不一致，请先核对连接配置', CAPACITY: '测试与发现回执已达到容量上限', BUSY: '测试和发现并发已满，请稍后显式重试', FORBIDDEN: '当前身份无此来源权限', NOT_FOUND: '当前身份下找不到实例或原回执' }
    const message = messages[code] ?? '测试或发现请求失败（HTTP ' + status + '）'
    return command && ([400, 409, 429].includes(status) || status === 503 && code === 'SOURCE_UNAVAILABLE') ? new InspectionRejectedError(message) : new Error(message)
  } })
}
async function result(value: unknown, id: string, pending: PendingInspection) { const r = obj(value, ['schemaVersion', 'storage', 'view']); if (r.schemaVersion !== '2.0' || !['postgres', 'memory'].includes(r.storage)) invalid(); const v = await view(r.view, id), i = v.inspection, c = pending.command; if (i.requestId !== c.requestId || i.kind !== pending.kind || i.dataMode !== pending.dataMode || i.configurationRevision !== c.configurationRevision || i.connectionDigest !== c.connectionDigest || (i.previousRequestId ?? null) !== (c.previousRequestId ?? null)) invalid();
  if (pending.previous && i.state === 'COMPLETED') {
    const parent = pending.previous, a = parent.metricPage, b = i.metricPage
    if (!a || !b || !sameMetricManifest(a.manifest, b.manifest) || a.nextOffset !== b.offset || instant(i.asOf) < instant(parent.availableAt)) invalid()
  }
  return v }
/**
 * TEST is exposed through the instance-scoped connection-check alias. The
 * command remains the same, but the route makes it explicit that the probe is
 * bound to the saved instance configuration and credential pin.
 */
export async function runInspection(id: string, pending: PendingInspection, signal: AbortSignal) { return result(await request(id, pending.kind === 'DISCOVER_METRIC_PAGE' ? '/metric-discoveries' : pending.kind === 'TEST' ? '/connection-check' : pending.kind === 'DISCOVER_METRICS' ? '/discover-metrics' : '/discover', signal, pending.command), id, pending) }
export async function readInspection(id: string, pending: PendingInspection, signal: AbortSignal) { if (!uuid.test(pending.command.requestId)) invalid(); return result(await request(id, '/inspections/' + pending.command.requestId, signal), id, pending) }
export async function readInspections(id: string, signal: AbortSignal): Promise<InspectionView[]> { const r = obj(await request(id, '/inspections', signal), ['schemaVersion', 'storage', 'sourceId', 'items']); if (r.schemaVersion !== '2.0' || r.sourceId !== id || !['postgres', 'memory'].includes(r.storage) || !Array.isArray(r.items) || r.items.length > 20) invalid(); const items = await Promise.all(r.items.map((v: unknown) => view(v, id))); if (new Set(items.map(v => v.inspection.requestId)).size !== items.length) invalid(); return items }

export async function readStoredInspection(id: string, requestId: string, signal: AbortSignal) {
  if (!uuid.test(requestId)) invalid(); const r = obj(await request(id, '/inspections/' + requestId, signal), ['schemaVersion', 'storage', 'view'])
  if (r.schemaVersion !== '2.0' || !['postgres', 'memory'].includes(r.storage)) invalid()
  const v = await view(r.view, id); if (v.inspection.requestId !== requestId) invalid(); return v
}
