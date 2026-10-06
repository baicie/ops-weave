import { platformClient } from './http.ts'

// Wire shapes and bounds: contracts/schemas/v1/model-*.schema.json.
export type FieldType = 'TEXT' | 'INTEGER' | 'DECIMAL' | 'BOOLEAN' | 'ENUM' | 'DATETIME'
export type ModelField = { id: string; label: string; type: FieldType; required: boolean; maxLength?: number; min?: number; max?: number; choices?: string[] }
export type ModelRef = { id: string; revision: number }
export type ModelDefinition = ModelRef & { schemaVersion: '1.0'; kind: 'ENTITY' | 'RELATION'; label: string; description: string; cleaningProfile: 'safe-scalars-v1'; fields: ModelField[]; endpoints?: { from: ModelRef; to: ModelRef; cardinality: 'ONE_TO_ONE' | 'ONE_TO_MANY' | 'MANY_TO_MANY' } }
export type ModelEntry = { definition: ModelDefinition; digest: string; state: 'DRAFT' | 'PUBLISHED'; editVersion: number; updatedAt: string }
export type CatalogMetric = { key: string; label: string; entityType: string; unit: string; kind: string; sourceKey: string; transform: string; mapping: string }
export type CatalogPage = { schemaVersion: '1.0'; storage: 'postgres' | 'memory'; package: { schemaVersion: '1.0'; packageId: string; version: string; definitions: ModelDefinition[]; metrics: CatalogMetric[]; cleaning: { id: string; rules: string[]; missingPolicy: string; sampleLimit: number; fieldLimit: number }; connectorPolicy: { connector: string; discovery: string; normalizationBoundary: string; locallyVerifiedVersions: string[]; otherVersions: string; authentication: string; note: string } }; published: { items: ModelEntry[]; truncated: boolean }; drafts: { items: ModelEntry[]; truncated: boolean } }
export type ModelPreview = { valid: boolean; values: Record<string, string | number | boolean | null>; issues: { field: string; code: string }[]; changes: { field: string; rule: string }[] }
const types: FieldType[] = ['TEXT', 'INTEGER', 'DECIMAL', 'BOOLEAN', 'ENUM', 'DATETIME']
function invalid(): never { throw new Error('模型内容不符合契约，请检查字段、类型及版本') }
function obj(v: unknown, allowed: string[], required = allowed): Record<string, unknown> { if (!v || typeof v !== 'object' || Array.isArray(v) || Object.keys(v).some(k => !allowed.includes(k)) || required.some(k => !Object.hasOwn(v, k))) invalid(); return v as Record<string, unknown> }
function text(v: unknown, max = 128, empty = false): asserts v is string { if (typeof v !== 'string' || v.length > max || !empty && !v.trim()) invalid() }
function int(v: unknown, min: number, max: number) { if (!Number.isSafeInteger(v) || (v as number) < min || (v as number) > max) invalid() }
function list(v: unknown, max: number): unknown[] { if (!Array.isArray(v) || v.length > max) invalid(); return v }
function ref(v: unknown) { const r = obj(v, ['id', 'revision']); text(r.id, 56); if (!/^(builtin|custom)\.[a-z][a-z0-9_]{0,47}$/.test(r.id)) invalid(); int(r.revision, 1, 10000) }
export function parseDefinition(v: unknown): ModelDefinition {
  const d = obj(v, ['schemaVersion', 'id', 'revision', 'kind', 'label', 'description', 'cleaningProfile', 'fields', 'endpoints'], ['schemaVersion', 'id', 'revision', 'kind', 'label', 'description', 'cleaningProfile', 'fields'])
  ref({ id: d.id, revision: d.revision }); text(d.label, 80); text(d.description, 1000, true)
  if (d.schemaVersion !== '1.0' || d.cleaningProfile !== 'safe-scalars-v1' || !['ENTITY', 'RELATION'].includes(String(d.kind))) invalid()
  const ids = new Set<string>()
  for (const value of list(d.fields, 32)) {
    const f = obj(value, ['id', 'label', 'type', 'required', 'maxLength', 'min', 'max', 'choices'], ['id', 'label', 'type', 'required']); text(f.id, 48); text(f.label, 80)
    if (!/^[a-z][a-z0-9_]{0,47}$/.test(f.id) || ['id', 'tenant_id', 'version', 'source', 'source_ref', 'created_at', 'updated_at', 'constructor', 'prototype'].includes(f.id) || ids.has(f.id) || !types.includes(f.type as FieldType) || typeof f.required !== 'boolean') invalid()
    ids.add(f.id)
    if (f.type === 'TEXT' || f.type === 'ENUM') int(f.maxLength, 1, 2048); else if ('maxLength' in f) invalid()
    if (f.type === 'ENUM') { const choices = list(f.choices, 32); if (!choices.length || new Set(choices).size !== choices.length) invalid(); for (const c of choices) { text(c, Math.min(80, f.maxLength as number)); if (c !== c.trim()) invalid() } } else if ('choices' in f) invalid()
    for (const k of ['min', 'max']) if (k in f && (!['INTEGER', 'DECIMAL'].includes(String(f.type)) || typeof f[k] !== 'number' || !Number.isFinite(f[k]) || Math.abs(f[k] as number) > Number.MAX_SAFE_INTEGER || f.type === 'INTEGER' && !Number.isSafeInteger(f[k]))) invalid()
    if (typeof f.min === 'number' && typeof f.max === 'number' && f.min > f.max) invalid()
  }
  if (d.kind === 'RELATION') { if ((d.fields as unknown[]).length) invalid(); const e = obj(d.endpoints, ['from', 'to', 'cardinality']); ref(e.from); ref(e.to); if (!['ONE_TO_ONE', 'ONE_TO_MANY', 'MANY_TO_MANY'].includes(String(e.cardinality))) invalid() } else if ('endpoints' in d) invalid()
  return v as ModelDefinition
}
function entry(v: unknown): ModelEntry {
  const e = obj(v, ['definition', 'digest', 'state', 'editVersion', 'updatedAt']); parseDefinition(e.definition); text(e.digest, 71); text(e.updatedAt, 40)
  if (!/^sha256:[a-f0-9]{64}$/.test(e.digest) || !['DRAFT', 'PUBLISHED'].includes(String(e.state)) || !Number.isFinite(Date.parse(e.updatedAt))) invalid()
  int(e.editVersion, e.state === 'DRAFT' ? 1 : 0, e.state === 'DRAFT' ? 1000000 : 0); return v as ModelEntry
}
function page(v: unknown): CatalogPage {
  const p = obj(v, ['schemaVersion', 'storage', 'package', 'published', 'drafts']); if (p.schemaVersion !== '1.0' || !['memory', 'postgres'].includes(String(p.storage))) invalid()
  const b = obj(p.package, ['schemaVersion', 'packageId', 'version', 'definitions', 'metrics', 'cleaning', 'connectorPolicy']); if (b.schemaVersion !== '1.0' || b.packageId !== 'opsweave-core' || b.version !== '1.0.0') invalid()
  const definitions = list(b.definitions, 9).map(parseDefinition); if (definitions.length !== 9 || definitions.some(d => !d.id.startsWith('builtin.')) || new Set(definitions.map(d => d.id)).size !== definitions.length) invalid()
  for (const m of list(b.metrics, 32)) { const row = obj(m, ['key', 'label', 'entityType', 'unit', 'kind', 'sourceKey', 'transform', 'mapping']); for (const val of Object.values(row)) text(val, 128); if (row.kind !== 'gauge' || !['identity', 'percent-to-ratio'].includes(String(row.transform))) invalid() }
  const cleaning = obj(b.cleaning, ['id', 'rules', 'missingPolicy', 'sampleLimit', 'fieldLimit']); if (cleaning.id !== 'safe-scalars-v1' || cleaning.missingPolicy !== 'preserve-and-report' || cleaning.sampleLimit !== 1 || cleaning.fieldLimit !== 32) invalid(); for (const rule of list(cleaning.rules, 5)) text(rule, 64)
  const connector = obj(b.connectorPolicy, ['connector', 'discovery', 'normalizationBoundary', 'locallyVerifiedVersions', 'otherVersions', 'authentication', 'note']); for (const [k, val] of Object.entries(connector)) if (k !== 'locallyVerifiedVersions') text(val, 255); for (const val of list(connector.locallyVerifiedVersions, 1)) if (val !== '7.0.27') invalid(); if (connector.otherVersions !== 'UNVERIFIED') invalid()
  for (const key of ['published', 'drafts']) { const rows = obj(p[key], ['items', 'truncated']); if (typeof rows.truncated !== 'boolean') invalid(); const entries = list(rows.items, 50).map(entry); if (entries.some(e => e.state !== (key === 'drafts' ? 'DRAFT' : 'PUBLISHED') || !e.definition.id.startsWith('custom.'))) invalid() }
  return v as CatalogPage
}
export class CatalogError extends Error { constructor(readonly status: number, code: string) { super(({ CONFLICT: '草稿或发布版本已变化，请重新读取后再编辑', INCOMPATIBLE_REVISION: '该修改不兼容旧版本；当前只允许追加可选字段及修改说明', UNKNOWN_ENTITY_TYPE: '关系端点必须引用本租户已发布的实体类型', FORBIDDEN: '当前身份没有模型目录权限', INVALID_REQUEST: '模型字段或样本不符合契约，请核对后重试' } as Record<string, string>)[code] ?? '模型目录请求失败（HTTP ' + status + '）') } }
const options = (signal: AbortSignal) => ({ signal, error: (s: number, c: string) => new CatalogError(s, c) })
export async function readCatalog(signal: AbortSignal): Promise<CatalogPage> { return page(await platformClient.request('/api/v1/catalog', options(signal))) }
export async function readModelVersion(reference: ModelRef, signal: AbortSignal): Promise<ModelEntry> {
  ref(reference); const value=entry(await platformClient.request('/api/v1/catalog/versions/'+encodeURIComponent(reference.id)+'/'+reference.revision,options(signal)))
  if(value.state!=='PUBLISHED'||value.definition.id!==reference.id||value.definition.revision!==reference.revision)invalid();return value
}
export async function saveModel(definition: ModelDefinition, expectedEditVersion: number, signal: AbortSignal): Promise<ModelEntry> {
  parseDefinition(definition); const e = entry(await platformClient.request('/api/v1/catalog/drafts', { ...options(signal), body: { definition, expectedEditVersion } })); if (e.state !== 'DRAFT' || e.editVersion !== expectedEditVersion + 1 || e.definition.id !== definition.id || e.definition.revision !== definition.revision) invalid(); return e
}
export async function publishModel(draft: ModelEntry, signal: AbortSignal): Promise<ModelEntry> {
  const e = entry(await platformClient.request('/api/v1/catalog/publish', { ...options(signal), body: { ref: { id: draft.definition.id, revision: draft.definition.revision }, expectedEditVersion: draft.editVersion, digest: draft.digest } })); if (e.state !== 'PUBLISHED' || e.digest !== draft.digest || e.definition.id !== draft.definition.id || e.definition.revision !== draft.definition.revision) invalid(); return e
}
export async function previewModel(definition: ModelDefinition, sample: unknown, signal: AbortSignal): Promise<ModelPreview> {
  parseDefinition(definition); const v = await platformClient.request('/api/v1/catalog/preview', { ...options(signal), body: { definition, sample } }); const p = obj(v, ['valid', 'values', 'issues', 'changes']); if (typeof p.valid !== 'boolean') invalid()
  if (!p.values || typeof p.values !== 'object' || Array.isArray(p.values) || Object.keys(p.values).length > 32) invalid(); for (const val of Object.values(p.values)) if (val !== null && !['string', 'number', 'boolean'].includes(typeof val)) invalid()
  for (const issue of list(p.issues, 64)) { const i = obj(issue, ['field', 'code']); text(i.field, 80); text(i.code, 32) }
  for (const change of list(p.changes, 32)) { const c = obj(change, ['field', 'rule']); text(c.field, 48); if (!['trim-text', 'strict-scalar-conversion'].includes(String(c.rule))) invalid() }
  if (p.valid !== ((p.issues as unknown[]).length === 0)) invalid(); return v as ModelPreview
}
