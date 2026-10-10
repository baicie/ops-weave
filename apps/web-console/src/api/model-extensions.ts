import { platformClient } from './http.ts'
import type { FieldType, ModelRef } from './model-catalog.ts'

// Wire shapes and bounds: contracts/schemas/v1/model-extension*.schema.json.
export type ModelExtensionField = { id: string; label: string; type: FieldType; required: false; maxLength?: number; min?: number; max?: number; choices?: string[] }
export type ModelExtension = { schemaVersion: '1.0'; base: ModelRef; revision: number; digest: string; fields: ModelExtensionField[] }
export type ModelExtensionEntry = { extension: ModelExtension; state: 'DRAFT' | 'PUBLISHED'; editVersion: number; updatedAt: string }
export type ModelExtensionPage = {
  schemaVersion: '1.0'
  storage: string
  published: { items: ModelExtensionEntry[]; truncated: boolean }
  drafts: { items: ModelExtensionEntry[]; truncated: boolean }
}

const types: FieldType[] = ['TEXT', 'INTEGER', 'DECIMAL', 'BOOLEAN', 'ENUM', 'DATETIME']
const reserved = ['id', 'tenant_id', 'tenantId', 'version', 'source', 'source_ref', 'created_at', 'updated_at', '__proto__', 'constructor', 'prototype']
function invalid(): never { throw new Error('扩展字段内容不符合契约，请检查字段、类型及版本') }
function obj(v: unknown, allowed: string[], required = allowed): Record<string, unknown> {
  if (!v || typeof v !== 'object' || Array.isArray(v) || Object.keys(v).some(k => !allowed.includes(k)) || required.some(k => !Object.hasOwn(v, k))) invalid()
  return v as Record<string, unknown>
}
function text(v: unknown, max = 128, empty = false): asserts v is string {
  if (typeof v !== 'string' || v.length > max || !empty && !v.trim()) invalid()
}
function int(v: unknown, min: number, max: number): asserts v is number {
  if (!Number.isSafeInteger(v) || (v as number) < min || (v as number) > max) invalid()
}
function list(v: unknown, max: number): unknown[] { if (!Array.isArray(v) || v.length > max) invalid(); return v }
function ref(v: unknown): ModelRef {
  const r = obj(v, ['id', 'revision']); text(r.id, 56)
  if (!/^(builtin|custom)\.[a-z][a-z0-9_]{0,47}$/.test(r.id)) invalid()
  int(r.revision, 1, 10000)
  return { id: r.id, revision: r.revision }
}
export function parseField(v: unknown): ModelExtensionField {
  const f = obj(v, ['id', 'label', 'type', 'required', 'maxLength', 'min', 'max', 'choices'], ['id', 'label', 'type', 'required'])
  text(f.id, 48); text(f.label, 80)
  if (!/^[a-z][a-z0-9_]{0,47}$/.test(f.id) || reserved.includes(f.id) || !types.includes(f.type as FieldType) || f.required !== false) invalid()
  if (f.type === 'TEXT' || f.type === 'ENUM') int(f.maxLength, 1, 2048); else if ('maxLength' in f) invalid()
  if (f.type === 'ENUM') {
    const choices = list(f.choices, 32)
    if (!choices.length || new Set(choices).size !== choices.length) invalid()
    for (const c of choices) { text(c, Math.min(80, f.maxLength as number)); if (c !== c.trim()) invalid() }
  } else if ('choices' in f) invalid()
  for (const k of ['min', 'max']) if (k in f && (!['INTEGER', 'DECIMAL'].includes(String(f.type)) || typeof f[k] !== 'number' || !Number.isFinite(f[k])
    || Math.abs(f[k] as number) > Number.MAX_SAFE_INTEGER || f.type === 'INTEGER' && !Number.isSafeInteger(f[k]))) invalid()
  if (typeof f.min === 'number' && typeof f.max === 'number' && f.min > f.max) invalid()
  return v as ModelExtensionField
}
export function parseExtension(v: unknown): ModelExtension {
  const e = obj(v, ['schemaVersion', 'base', 'revision', 'digest', 'fields'])
  if (e.schemaVersion !== '1.0') invalid()
  const base = ref(e.base); int(e.revision, 1, 10000); text(e.digest, 71)
  if (!/^sha256:[a-f0-9]{64}$/.test(e.digest)) invalid()
  const fields = list(e.fields, 32)
  if (!fields.length) invalid()
  const ids = new Set<string>()
  for (const value of fields) { const field = parseField(value); if (ids.has(field.id)) invalid(); ids.add(field.id) }
  return { ...e, base, fields: fields as ModelExtensionField[] } as ModelExtension
}
function entry(v: unknown): ModelExtensionEntry {
  const e = obj(v, ['extension', 'state', 'editVersion', 'updatedAt'])
  parseExtension(e.extension); text(e.updatedAt, 40)
  if (!['DRAFT', 'PUBLISHED'].includes(String(e.state)) || !Number.isFinite(Date.parse(e.updatedAt))) invalid()
  int(e.editVersion, e.state === 'DRAFT' ? 1 : 0, e.state === 'DRAFT' ? 1000000 : 0)
  return v as ModelExtensionEntry
}
function page(v: unknown): ModelExtensionPage {
  const p = obj(v, ['schemaVersion', 'storage', 'published', 'drafts'])
  if (p.schemaVersion !== '1.0' || typeof p.storage !== 'string' || !p.storage) invalid()
  for (const key of ['published', 'drafts']) {
    const rows = obj(p[key], ['items', 'truncated'])
    if (typeof rows.truncated !== 'boolean') invalid()
    const entries = list(rows.items, 50).map(entry)
    if (entries.some(e => e.state !== (key === 'drafts' ? 'DRAFT' : 'PUBLISHED'))) invalid()
  }
  return v as ModelExtensionPage
}

/** Mirrors the server digest scheme opsweave-model-extension-v1 (fields sorted by id). */
export async function extensionDigest(extension: { base: ModelRef; revision: number; fields: ModelExtensionField[] }): Promise<string> {
  const decimal = (value: number | undefined) => value === undefined ? '' : String(value)
  const parts = ['opsweave-model-extension-v1', extension.base.id, String(extension.base.revision), String(extension.revision), 'safe-scalars-v1']
  for (const field of [...extension.fields].sort((a, b) => a.id < b.id ? -1 : a.id > b.id ? 1 : 0)) {
    parts.push(field.id, field.label, field.type, 'false', field.maxLength === undefined ? '' : String(field.maxLength),
      decimal(field.min), decimal(field.max), String(field.choices?.length ?? 0))
    for (const choice of field.choices ?? []) parts.push(choice)
  }
  const encoder = new TextEncoder(), chunks: Uint8Array[] = []
  for (const part of parts) { const bytes = encoder.encode(part); chunks.push(encoder.encode(bytes.length + ':'), bytes) }
  const input = new Uint8Array(chunks.reduce((sum, chunk) => sum + chunk.length, 0)); let offset = 0
  for (const chunk of chunks) { input.set(chunk, offset); offset += chunk.length }
  return 'sha256:' + Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', input)), byte => byte.toString(16).padStart(2, '0')).join('')
}

const options = (signal: AbortSignal) => ({ signal, error: (s: number, c: string) => new ModelExtensionError(s, c) })
export class ModelExtensionError extends Error {
  constructor(readonly status: number, code: string) {
    super(({ CONFLICT: '扩展草稿或发布版本已变化；内置模型本体不可覆盖，请重新读取后再编辑',
      INCOMPATIBLE_REVISION: '该修改不兼容旧扩展版本；当前只允许追加可选字段及调整显示名称',
      NOT_FOUND: '目标模型版本或扩展草稿不可用',
      FORBIDDEN: '当前身份没有模型目录权限',
      INVALID_REQUEST: '扩展字段不符合契约，请核对后重试' } as Record<string, string>)[code] ?? '扩展字段请求失败（HTTP ' + status + '）')
  }
}
export async function readModelExtensions(signal: AbortSignal): Promise<ModelExtensionPage> {
  return page(await platformClient.request('/api/v1/catalog/extensions', options(signal)))
}
export async function saveModelExtension(base: ModelRef, fields: ModelExtensionField[], expectedEditVersion: number, signal: AbortSignal): Promise<ModelExtensionEntry> {
  ref(base); for (const field of fields) parseField(field); int(expectedEditVersion, 0, 1000000)
  const value = entry(await platformClient.request('/api/v1/catalog/extensions/drafts', {
    ...options(signal), body: { base, fields, expectedEditVersion },
  }))
  if (value.state !== 'DRAFT' || value.editVersion !== expectedEditVersion + 1
    || value.extension.base.id !== base.id || value.extension.base.revision !== base.revision) invalid()
  return value
}
export async function publishModelExtension(entryValue: ModelExtensionEntry, signal: AbortSignal): Promise<ModelExtensionEntry> {
  if (entryValue.state !== 'DRAFT' || !Number.isSafeInteger(entryValue.editVersion) || entryValue.editVersion < 1) invalid()
  const value = entry(await platformClient.request('/api/v1/catalog/extensions/publish', {
    ...options(signal),
    body: { base: entryValue.extension.base, expectedEditVersion: entryValue.editVersion, digest: entryValue.extension.digest },
  }))
  if (value.state !== 'PUBLISHED' || value.extension.digest !== entryValue.extension.digest
    || value.extension.base.id !== entryValue.extension.base.id || value.extension.base.revision !== entryValue.extension.base.revision) invalid()
  return value
}
