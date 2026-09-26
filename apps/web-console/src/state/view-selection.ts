// URL data carries read selection only, never identity, cached results or an executable command.
export type InventorySelection = { q: string; type: string; lifecycle: string; after: string | null; entityId: string | null }
export type IncidentSelection = { status: string; after: string | null; incidentId: string | null }
export type MetricsSelection = { q: string; after: string | null; entityId: string | null; metricKey: string; range: '15m' | '30m' | '1h'; from: number | null; till: number | null }
export const inventoryDefault = (): InventorySelection => ({ q: '', type: 'host', lifecycle: '', after: null, entityId: null })
export const incidentDefault = (): IncidentSelection => ({ status: '', after: null, incidentId: null })
export const metricsDefault = (): MetricsSelection => ({ q: '', after: null, entityId: null, metricKey: '', range: '1h', from: null, till: null })
const UUID = /^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/
const lifecycle = ['', 'ACTIVE', 'INACTIVE', 'DISCOVERED', 'DELETED', 'ARCHIVED']
const statuses = ['', 'OPEN', 'INVESTIGATING', 'MITIGATED', 'RESOLVED', 'CLOSED']
export class ViewSelectionError extends Error { constructor() { super('地址中的筛选或资源参数无效，请重置筛选后重试') } }
function check(ok: unknown): asserts ok { if (!ok) throw new ViewSelectionError() }
function params(hash: string, path: string, fields: string[]): URLSearchParams {
  check(hash.length <= 2048 && hash.split('?')[0] === `#${path}` && (hash.match(/\?/g)?.length ?? 0) <= 1)
  const raw = hash.split('?')[1] ?? ''
  try { decodeURIComponent(raw.replace(/\+/g, ' ')) } catch { throw new ViewSelectionError() }
  const p = new URLSearchParams(raw)
  check([...p.keys()].every(key => fields.includes(key) && p.getAll(key).length === 1))
  return p
}
function id(p: URLSearchParams, field: string) { const v = p.get(field); check(v === null || UUID.test(v)); return v }
function search(p: URLSearchParams) { const v = (p.get('q') ?? '').trim(); check(v.length <= 100 && !/[\x00-\x1f\x7f-\x9f\ufffd]/.test(v)); return v }
export function inventorySelection(hash: string): InventorySelection {
  const p = params(hash, '/inventory', ['q', 'type', 'lifecycle', 'after', 'entityId']), type = p.get('type') ?? 'host', state = p.get('lifecycle') ?? ''
  check(['', 'host'].includes(type) && lifecycle.includes(state))
  return { q: search(p), type, lifecycle: state, after: id(p, 'after'), entityId: id(p, 'entityId') }
}
export function incidentSelection(hash: string): IncidentSelection {
  const p = params(hash, '/incidents', ['status', 'after', 'incidentId']), status = p.get('status') ?? ''
  check(statuses.includes(status)); return { status, after: id(p, 'after'), incidentId: id(p, 'incidentId') }
}
export function metricsSelection(hash: string): MetricsSelection {
  const p = params(hash, '/metrics', ['q', 'after', 'entityId', 'metricKey', 'range', 'from', 'till']), key = p.get('metricKey') ?? '', range = p.get('range') ?? '1h'
  check((!key || /^[a-zA-Z0-9_.:/%\-]{1,128}$/.test(key)) && ['15m', '30m', '1h'].includes(range))
  const entityId = id(p, 'entityId'), from = p.get('from'), till = p.get('till')
  check((from === null) === (till === null))
  if (from !== null && till !== null) check(entityId !== null && /^(0|[1-9][0-9]{0,9})$/.test(from) && /^(0|[1-9][0-9]{0,9})$/.test(till)
    && Number(till) >= Number(from) && Number(till) - Number(from) <= 3600)
  return { q: search(p), after: id(p, 'after'), entityId, metricKey: key, range: range as MetricsSelection['range'], from: from === null ? null : Number(from), till: till === null ? null : Number(till) }
}
function hash(path: string, values: Record<string, string | number | null>) {
  const p = new URLSearchParams(); for (const [key, value] of Object.entries(values)) if (value !== null) p.set(key, String(value))
  return `#${path}${p.size ? `?${p}` : ''}`
}
export function inventoryHash(v: InventorySelection) {
  const value = hash('/inventory', { q: v.q || null, type: v.type === 'host' ? null : v.type, lifecycle: v.lifecycle || null, after: v.after, entityId: v.entityId })
  inventorySelection(value); return value
}
export function incidentHash(v: IncidentSelection) {
  const value = hash('/incidents', { status: v.status || null, after: v.after, incidentId: v.incidentId }); incidentSelection(value); return value
}
export function metricsHash(v: MetricsSelection) {
  const value = hash('/metrics', { q: v.q || null, after: v.after, entityId: v.entityId, metricKey: v.metricKey || null, range: v.range === '1h' ? null : v.range, from: v.from, till: v.till })
  metricsSelection(value); return value
}
