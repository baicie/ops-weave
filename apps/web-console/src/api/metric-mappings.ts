import { platformClient } from './http.ts'

// Closed wire contracts: contracts/schemas/v2/metric-mapping-*.schema.json.
export type MappingPin = { id: string; revision: number; digest: string }
export type MappingDefinition = { mappingPin: MappingPin; connector: string; sourceKey: string; metricKey: string; displayName: string; metricType: string; unit: string; valueType: string; dimensionSchema: string[]; fixedDimensions: Record<string, string>; valueTransform: string; minimum: string | null; maximum: string | null }
export type MappingBinding = { sourceType: string; sourceInstanceId: string; externalItemId: string; entityId: string; hostExternalId: string; metricKey: string; fixedDimensions: Record<string, string>; sourceUnit: string; valueTransform: string; mappingRevision: number; lifecycle: 'ACTIVE' | 'INACTIVE'; version: number; mappingPin: MappingPin | null }
export type MappingView = { binding: MappingBinding; canConfigure: boolean; candidates: MappingDefinition[] }
export type MappingPage = { schemaVersion: '2.0'; items: MappingView[]; truncated: boolean }
export type MappingCommand = { requestId: string; expectedBindingVersion: number; mappingPin: MappingPin }
export type PendingMapping = { previous: MappingBinding; definition: MappingDefinition; command: MappingCommand }
export class MappingRejectedError extends Error {}
const uuid = /^[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}$/, hash = /^sha256:[a-f0-9]{64}$/
function invalid(): never { throw new Error('映射响应不符合契约，请核对原请求结果') }
function object(v: unknown, keys: string[]): Record<string, unknown> { if (!v || typeof v !== 'object' || Array.isArray(v) || Object.keys(v).length !== keys.length || keys.some(k => !Object.hasOwn(v, k))) invalid(); return v as Record<string, unknown> }
function text(v: unknown, max = 255, empty = false): asserts v is string { if (typeof v !== 'string' || v.length > max || !empty && !v.trim()) invalid() }
function integer(v: unknown, max = 1_000_000_000) { if (!Number.isSafeInteger(v) || Number(v) < 1 || Number(v) > max) invalid() }
function dimensions(v: unknown): Record<string, string> { if (!v || typeof v !== 'object' || Array.isArray(v) || Object.keys(v).length > 32) invalid(); for (const [k, value] of Object.entries(v)) { text(k, 64); text(value, 256, true) }; return v as Record<string, string> }
function pin(v: unknown): MappingPin { const p = object(v, ['id', 'revision', 'digest']); text(p.id, 96); if (!/^[A-Za-z0-9][A-Za-z0-9_.-]{0,95}$/.test(p.id) || typeof p.digest !== 'string' || !hash.test(p.digest)) invalid(); integer(p.revision, 1_000_000); return p as unknown as MappingPin }
export { pin as parseMappingPin, definition as parseMappingDefinition }
export function sameMappingPin(a: MappingPin | null, b: MappingPin | null) { return a === null ? b === null : !!b && a.id === b.id && a.revision === b.revision && a.digest === b.digest }
async function digest(parts: string[]) { const encoder = new TextEncoder(), chunks: Uint8Array[] = []; for (const part of parts) { const bytes = encoder.encode(part); chunks.push(encoder.encode(bytes.length + ':'), bytes) }; const bytes = new Uint8Array(chunks.reduce((sum, p) => sum + p.length, 0)); let offset = 0; for (const p of chunks) { bytes.set(p, offset); offset += p.length }; return 'sha256:' + Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', bytes)), b => b.toString(16).padStart(2, '0')).join('') }
function binding(v: unknown): MappingBinding {
  const b = object(v, ['sourceType', 'sourceInstanceId', 'externalItemId', 'entityId', 'hostExternalId', 'metricKey', 'fixedDimensions', 'sourceUnit', 'valueTransform', 'mappingRevision', 'lifecycle', 'version', 'mappingPin'])
  text(b.sourceType, 64); text(b.sourceInstanceId, 64); text(b.externalItemId, 20); text(b.hostExternalId, 20); text(b.metricKey); text(b.valueTransform, 64); text(b.sourceUnit, 64, true)
  if (!/^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$/.test(b.sourceInstanceId) || !/^[1-9][0-9]{0,19}$/.test(b.externalItemId) || !/^[1-9][0-9]{0,19}$/.test(b.hostExternalId) || typeof b.entityId !== 'string' || !uuid.test(b.entityId) || !['ACTIVE', 'INACTIVE'].includes(String(b.lifecycle))) invalid()
  integer(b.version); integer(b.mappingRevision, 1_000_000); dimensions(b.fixedDimensions)
  if (b.mappingPin !== null && pin(b.mappingPin).revision !== b.mappingRevision) invalid()
  return b as unknown as MappingBinding
}
async function definition(v: unknown): Promise<MappingDefinition> {
  const d = object(v, ['mappingPin', 'connector', 'sourceKey', 'metricKey', 'displayName', 'metricType', 'unit', 'valueType', 'dimensionSchema', 'fixedDimensions', 'valueTransform', 'minimum', 'maximum']), p = pin(d.mappingPin)
  text(d.connector, 64); text(d.sourceKey, 1024); text(d.metricKey); text(d.displayName); text(d.unit, 64); text(d.valueTransform, 64)
  if (!['GAUGE', 'SUM', 'HISTOGRAM'].includes(String(d.metricType)) || !['DOUBLE', 'INTEGER', 'STRING', 'BOOLEAN', 'UNKNOWN'].includes(String(d.valueType)) || !Array.isArray(d.dimensionSchema) || d.dimensionSchema.length > 32 || new Set(d.dimensionSchema).size !== d.dimensionSchema.length) invalid()
  for (const n of d.dimensionSchema) text(n, 64)
  const names = d.dimensionSchema as string[], dims = dimensions(d.fixedDimensions); if (Object.keys(dims).some(k => !names.includes(k))) invalid()
  for (const n of [d.minimum, d.maximum]) if (n !== null && (typeof n !== 'string' || n.length > 64 || !/^-?[0-9]+(\.[0-9]+)?([eE][+-]?[0-9]+)?$/.test(n) || !Number.isFinite(Number(n)))) invalid()
  if (d.minimum !== null && d.maximum !== null && Number(d.minimum) > Number(d.maximum)) invalid()
  const parts = ['metric-mapping-v1', p.id, String(d.connector), String(d.sourceKey), String(d.metricKey), String(d.displayName), String(d.metricType), String(d.unit), String(d.valueType), String(d.valueTransform), String(p.revision), d.minimum === null ? '' : String(d.minimum), d.maximum === null ? '' : String(d.maximum), String(d.dimensionSchema.length), ...d.dimensionSchema, String(Object.keys(dims).length)]
  for (const key of Object.keys(dims).sort()) parts.push(key, dims[key])
  if (p.digest !== await digest(parts)) invalid()
  return d as unknown as MappingDefinition
}
async function view(v: unknown): Promise<MappingView> {
  const o = object(v, ['binding', 'canConfigure', 'candidates']), b = binding(o.binding)
  if (typeof o.canConfigure !== 'boolean' || !Array.isArray(o.candidates) || o.candidates.length > 100) invalid()
  const candidates = await Promise.all(o.candidates.map(definition))
  if (new Set(candidates.map(d => d.mappingPin.id)).size !== candidates.length || candidates.some(d => d.connector !== b.sourceType || d.metricKey !== b.metricKey || (b.mappingPin === null ? d.mappingPin.revision !== b.mappingRevision || d.valueTransform !== b.valueTransform || !equalDimensions(d.fixedDimensions, b.fixedDimensions) : !sameMappingPin(d.mappingPin, b.mappingPin) && (d.mappingPin.id !== b.mappingPin.id || d.mappingPin.revision <= b.mappingRevision)))) invalid()
  return { binding: b, canConfigure: o.canConfigure, candidates }
}
function equalDimensions(a: Record<string, string>, b: Record<string, string>) { return Object.keys(a).length === Object.keys(b).length && Object.entries(a).every(([k, v]) => b[k] === v) }
function path(b: Pick<MappingBinding, 'sourceInstanceId' | 'externalItemId'>) { if (!/^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$/.test(b.sourceInstanceId) || !/^[1-9][0-9]{0,19}$/.test(b.externalItemId)) invalid(); return '/' + b.sourceInstanceId + '/' + b.externalItemId }
const errors: Record<string, string> = { METRIC_MAPPING_CONFLICT: '绑定版本或原命令已变化，请读取最新绑定后核对', METRIC_MAPPING_INCOMPATIBLE: '这份映射与当前绑定不兼容，未更改绑定', METRIC_MAPPING_FORBIDDEN: '当前身份没有这份绑定的维护权限', METRIC_MAPPING_NOT_FOUND: '当前身份下找不到这份绑定或原请求', METRIC_MAPPING_CAPACITY: '维护回执已达到容量上限', METRIC_MAPPING_UNAVAILABLE: '映射维护暂不可用，提交结果需要核对' }
function request(suffix: string, signal: AbortSignal, command?: MappingCommand) { return platformClient.request('/api/v2/metric-bindings' + suffix, { signal, ...(command ? { method: 'POST' as const, body: command } : {}), error: (status, code) => command && [400, 403, 404, 409].includes(status) ? new MappingRejectedError(errors[code] ?? '映射命令被拒绝') : new Error(errors[code] ?? '映射读取失败（HTTP ' + status + '）') }) }
export async function readMappingBindings(signal: AbortSignal): Promise<MappingPage> { const p = object(await request('', signal), ['schemaVersion', 'items', 'truncated']); if (p.schemaVersion !== '2.0' || !Array.isArray(p.items) || p.items.length > 20 || typeof p.truncated !== 'boolean') invalid(); const items = await Promise.all(p.items.map(view)); if (new Set(items.map(v => path(v.binding))).size !== items.length) invalid(); return { schemaVersion: '2.0', items, truncated: p.truncated } }
export async function readMappingBinding(b: MappingBinding, signal: AbortSignal) { const p = object(await request(path(b), signal), ['schemaVersion', 'view']); if (p.schemaVersion !== '2.0') invalid(); const v = await view(p.view); if (path(v.binding) !== path(b)) invalid(); return v }
async function parseReceipt(value: unknown, pending: PendingMapping): Promise<MappingBinding> {
  const p = object(value, ['schemaVersion', 'receipt']), r = object(p.receipt, ['requestId', 'expectedBindingVersion', 'commandDigest', 'previousPin', 'binding', 'createdAt']), c = pending.command, old = pending.previous, b = binding(r.binding)
  const expectedDigest = await digest(['metric-mapping-maintenance-v1', old.sourceInstanceId, old.externalItemId, c.requestId, String(c.expectedBindingVersion), c.mappingPin.id, String(c.mappingPin.revision), c.mappingPin.digest])
  const previous = r.previousPin === null ? null : pin(r.previousPin), changed = !sameMappingPin(old.mappingPin, c.mappingPin)
  if (!sameMappingPin(pending.definition.mappingPin, c.mappingPin) || b.valueTransform !== pending.definition.valueTransform || !equalDimensions(b.fixedDimensions, pending.definition.fixedDimensions)) invalid()
  if (p.schemaVersion !== '2.0' || r.requestId !== c.requestId || r.expectedBindingVersion !== c.expectedBindingVersion || r.commandDigest !== expectedDigest || !sameMappingPin(previous, old.mappingPin) || !sameMappingPin(b.mappingPin, c.mappingPin) || b.mappingRevision !== c.mappingPin.revision || b.version !== c.expectedBindingVersion + (changed ? 1 : 0) || b.lifecycle !== 'ACTIVE' || path(b) !== path(old) || b.entityId !== old.entityId || b.hostExternalId !== old.hostExternalId || b.metricKey !== old.metricKey || b.sourceType !== old.sourceType || b.sourceUnit !== old.sourceUnit || typeof r.createdAt !== 'string' || !Number.isFinite(Date.parse(r.createdAt))) invalid()
  return b
}
export async function maintainMapping(pending: PendingMapping, signal: AbortSignal) { if (!uuid.test(pending.command.requestId)) invalid(); return parseReceipt(await request(path(pending.previous) + '/mapping', signal, pending.command), pending) }
export async function readMappingReceipt(pending: PendingMapping, signal: AbortSignal) { if (!uuid.test(pending.command.requestId)) invalid(); return parseReceipt(await request(path(pending.previous) + '/mapping/commands/' + pending.command.requestId, signal), pending) }
