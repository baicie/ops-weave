import { platformClient } from './http.ts'

// Authoritative wire contract: contracts/schemas/v2/workflow-collection-binding-*.schema.json,
// contracts/schemas/v2/receipt-retention-*.schema.json, contracts/schemas/v2/entity-deletion-*.schema.json
// and contracts/governance.md.

export type CollectionBinding = {
  id: string; revision: number; digest: string; sourceInstanceId: string
  state: 'BOUND' | 'UNBOUND'; generation: number; updatedAt: string; reason: string
}
export type CollectionBindingCommand = {
  requestId: string; id: string; revision: number; digest: string
  sourceInstanceId: string; operation: 'ENABLE' | 'DISABLE'; reason: string
}
export type RetentionReport = {
  kind: 'onboarding' | 'source-command' | 'external-link'; scopeKey: string
  kept: number; keptProtected: number; maxPerScope: number; maxPerTenant: number
  status: 'OK' | 'NEAR_LIMIT' | 'AT_LIMIT'; summary: string
}
export type RetentionPreview = {
  kind: RetentionReport['kind']; scopeKey: string; previewDigest: string
  candidates: string[]; removable: number; keptProtected: number; keptUnfinished: number; maxRemove: number
}
export type RetentionConfirm = {
  requestId: string; kind: RetentionReport['kind']; scopeKey: string
  previewDigest: string; reason: string; maxRemove: number
}
export type DeletionCommand = { requestId: string; entityId: string; reason: string }

const uuid = (v: unknown): v is string => typeof v === 'string' && /^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(v)
const digest = (v: unknown): v is string => typeof v === 'string' && /^sha256:[a-f0-9]{64}$/.test(v)
const record = (v: unknown): v is Record<string, unknown> => !!v && typeof v === 'object' && !Array.isArray(v)
const bounded = (v: unknown, max: number): v is string => typeof v === 'string' && v.trim().length >= 1 && v.length <= max
const instant = (v: unknown): v is string => typeof v === 'string' && Number.isFinite(Date.parse(v))
const integer = (v: unknown, max: number): v is number => typeof v === 'number' && Number.isInteger(v) && v >= 0 && v <= max
const kinds = ['onboarding', 'source-command', 'external-link'] as const
const workflowId = (v: unknown): v is string => typeof v === 'string' && /^[a-z][a-z0-9_-]{0,47}$/.test(v)
const scopeKey = (v: unknown): v is string => typeof v === 'string' && /^[A-Za-z0-9_.:@-]{1,128}$/.test(v)

function binding(value: unknown): CollectionBinding {
  if (!record(value) || !workflowId(value.id) || !digest(value.digest) || typeof value.revision !== 'number'
    || !bounded(value.sourceInstanceId, 128) || (value.state !== 'BOUND' && value.state !== 'UNBOUND')
    || !integer(value.generation, 1000000) || !instant(value.updatedAt) || !bounded(value.reason, 256)) throw new Error('采集绑定状态不符合契约')
  return { id: value.id, revision: value.revision, digest: value.digest, sourceInstanceId: value.sourceInstanceId, state: value.state, generation: value.generation, updatedAt: value.updatedAt, reason: value.reason }
}

function receipt(value: unknown, command: CollectionBindingCommand): CollectionBinding {
  if (!record(value) || value.schemaVersion !== '2.0' || value.requestId !== command.requestId
    || value.operation !== command.operation || !digest(value.commandDigest) || !instant(value.createdAt)) throw new Error('采集绑定回执不符合契约')
  return binding(value.binding)
}

function failed(status: number, code: string): Error {
  if (code === 'COLLECTION_BINDING_REQUIRED') return new Error('该发布版本尚未启用采集绑定，或绑定已被解除，无法继续消费来源批次。')
  if (code === 'FORBIDDEN') return new Error('当前身份没有管理采集绑定或留存治理的权限。')
  if (code === 'NOT_FOUND') return new Error('目标对象不存在或当前身份不可见。')
  if (code === 'ENTITY_DELETION_PROTECTED') return new Error('该实体仍被未结束的关系记录引用，不能删除。')
  return new Error(status === 401 ? '请先建立平台会话，再执行治理操作。' : status === 400 ? '请求不符合契约。' : status === 409 ? '请求与已记录内容冲突或目标不可清理。' : '治理操作失败（HTTP ' + status + '）')
}

function commandBody(command: CollectionBindingCommand): Record<string, unknown> {
  if (!uuid(command.requestId) || !workflowId(command.id) || !Number.isInteger(command.revision) || command.revision < 1
    || !digest(command.digest) || !bounded(command.sourceInstanceId, 128)
    || (command.operation !== 'ENABLE' && command.operation !== 'DISABLE')
    || !bounded(command.reason, 256) || command.reason.split('').some(c => c.charCodeAt(0) < 32 || c.charCodeAt(0) === 127)) throw new Error('采集绑定命令无效')
  // The command is closed: it never carries a tenant, actor, permission or background authority.
  return { requestId: command.requestId, id: command.id, revision: command.revision, digest: command.digest, sourceInstanceId: command.sourceInstanceId, operation: command.operation, reason: command.reason }
}

export async function readCollectionBindings(signal: AbortSignal): Promise<CollectionBinding[]> {
  const value = await platformClient.request('/api/v1/integrations/workflows/collection-bindings', { signal, error: failed })
  if (!record(value) || value.schemaVersion !== '2.0' || !Array.isArray(value.items) || value.items.length > 200) throw new Error('采集绑定列表不符合契约')
  return value.items.map(binding)
}

export async function submitCollectionBinding(command: CollectionBindingCommand, signal: AbortSignal): Promise<CollectionBinding> {
  return receipt(await platformClient.request('/api/v1/integrations/workflows/collection-bindings/' + command.operation.toLowerCase(), { method: 'POST', body: commandBody(command), signal, error: failed }), command)
}

export async function readRetentionBudgets(signal: AbortSignal): Promise<RetentionReport[]> {
  const value = await platformClient.request('/api/v1/integrations/receipts/retention', { signal, error: failed })
  if (!record(value) || value.schemaVersion !== '1.0' || value.dataMode !== 'receipt-log' || !Array.isArray(value.items) || value.items.length > 50) throw new Error('回执留存预算不符合契约')
  return value.items.map(item => {
    if (!record(item) || !kinds.includes(item.kind as never) || !scopeKey(item.scopeKey) || !integer(item.kept, 5000) || !integer(item.keptProtected, 5000)
      || !integer(item.maxPerScope, 1000) || !integer(item.maxPerTenant, 5000)
      || (item.status !== 'OK' && item.status !== 'NEAR_LIMIT' && item.status !== 'AT_LIMIT') || !bounded(item.summary, 256)) throw new Error('回执留存预算行不符合契约')
    return { kind: item.kind, scopeKey: item.scopeKey, kept: item.kept, keptProtected: item.keptProtected, maxPerScope: item.maxPerScope, maxPerTenant: item.maxPerTenant, status: item.status, summary: item.summary } as RetentionReport
  })
}

export async function previewRetentionCleanup(signal: AbortSignal, request: { kind: RetentionReport['kind']; scopeKey: string; maxRemove: number }): Promise<RetentionPreview> {
  const value = await platformClient.request('/api/v1/integrations/receipts/retention/preview', { method: 'POST', body: request, signal, error: failed })
  if (!record(value) || value.schemaVersion !== '1.0' || !kinds.includes(value.kind as never) || !scopeKey(value.scopeKey) || !digest(value.previewDigest)
    || !Array.isArray(value.candidates) || value.candidates.length > 100 || !integer(value.removable, 100) || !integer(value.keptProtected, 5000)
    || value.keptUnfinished !== 0 || !integer(value.maxRemove, 100) || value.removable !== value.candidates.length) throw new Error('清理预览不符合契约')
  return { kind: value.kind, scopeKey: value.scopeKey, previewDigest: value.previewDigest, candidates: [...value.candidates], removable: value.removable, keptProtected: value.keptProtected, keptUnfinished: 0, maxRemove: value.maxRemove } as RetentionPreview
}

export async function confirmRetentionCleanup(command: RetentionConfirm, signal: AbortSignal): Promise<Record<string, unknown>> {
  const value = await platformClient.request('/api/v1/integrations/receipts/retention/confirm', { method: 'POST', body: command, signal, error: failed })
  if (!record(value) || value.schemaVersion !== '1.0' || value.requestId !== command.requestId || !digest(value.commandDigest)
    || !integer(value.removed, 100) || !integer(value.keptProtected, 5000) || value.keptUnfinished !== 0) throw new Error('清理回执不符合契约')
  return value
}

export async function deleteEntity(command: DeletionCommand, signal: AbortSignal): Promise<Record<string, unknown>> {
  if (!uuid(command.requestId) || !uuid(command.entityId) || !bounded(command.reason, 256)) throw new Error('删除命令无效')
  const value = await platformClient.request('/api/v1/entities/' + command.entityId + '/deletions', { method: 'POST', body: command, signal, error: failed })
  if (!record(value) || value.schemaVersion !== '1.0' || value.requestId !== command.requestId || !digest(value.commandDigest)
    || (value.outcome !== 'SOFT_DELETED' && value.outcome !== 'PROTECTED') || !integer(value.auditCount, 100)) throw new Error('删除回执不符合契约')
  return value
}
