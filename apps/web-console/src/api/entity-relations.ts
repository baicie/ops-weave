import { platformClient } from './http.ts'

export type EntityRelation = {
  schemaVersion: '1.0'; id: string; tenantId: string; fromEntityId: string; toEntityId: string
  relationType: string; relationRevision: number; validFrom: string; validTo: string | null
  dataMode: 'fixture' | 'zabbix-jsonrpc' | 'import' | 'unknown'; version: number
}
export type EntityRelationPage = { schemaVersion: '1.0'; storage: 'memory' | 'postgres'; tenantId: string; entityId: string; asOf: string; items: EntityRelation[]; nextCursor: string | null }
export type EntityRelationHistoryEntry = { relation: EntityRelation; closeReason: string | null }
export type EntityRelationHistoryPage = { schemaVersion: '1.0'; storage: 'memory' | 'postgres'; tenantId: string; entityId: string; items: EntityRelationHistoryEntry[]; nextCursor: string | null }
export type EntityRelationCommand = {
  requestId: string; relationType: string; relationRevision: number; fromEntityId: string; toEntityId: string
  validFrom: string; validTo: string | null; sourceRef?: string; dataMode?: EntityRelation['dataMode']
  expectedFromVersion: number; expectedToVersion: number
}
export type EntityRelationCloseCommand = {
  requestId: string; relationId: string; reason: string; validTo: string | null; expectedVersion: number
}
export type EntityRelationCorrectionCommand = {
  requestId: string; relationId: string; reason: string; expectedVersion: number
  fromEntityId: string; toEntityId: string; relationType: string; relationRevision: number
}
export type EntityRelationCorrectionReceipt = { schemaVersion: '1.0'; requestId: string; replayed: boolean; previousRelationId: string; relation: EntityRelation }
export type EntityRelationConfirmationCommand = {
  requestId: string; sourceKind: 'manual' | 'source'; sourceKey: string; batchRef: string; dataMode: EntityRelation['dataMode']
}
export type EntityRelationConfirmationWithdrawCommand = {
  requestId: string; sourceKind: 'manual' | 'source'; sourceKey: string; batchRef: string; reason: string
}
export type EntityRelationConfirmationEntry = {
  sourceKind: 'manual' | 'source'; sourceKey: string; batchRef: string; dataMode: EntityRelation['dataMode']
  state: 'CONFIRMED' | 'WITHDRAWN'; confirmedAt: string; withdrawnAt: string | null; withdrawReason: string | null
}
export type EntityRelationConfirmationReceipt = { schemaVersion: '1.0'; requestId: string; replayed: boolean; relationId: string; entry: EntityRelationConfirmationEntry }
export type EntityRelationConfirmationView = {
  schemaVersion: '1.0'; storage: 'memory' | 'postgres'; tenantId: string; relationId: string
  relationState: 'ACTIVE' | 'TERMINATED'; entries: EntityRelationConfirmationEntry[]
}
export type EntityRelationReceipt = { schemaVersion: '1.0'; requestId: string; replayed: boolean; relation: EntityRelation }
const uuid = (v: unknown): v is string => typeof v === 'string' && /^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(v)
const record = (v: unknown): v is Record<string, unknown> => !!v && typeof v === 'object' && !Array.isArray(v)
const relationType = (v: unknown): v is string => typeof v === 'string' && /^(builtin|custom)\.[a-z][a-z0-9_]{0,47}$/.test(v)
const instant = (v: unknown): v is string => typeof v === 'string' && Number.isFinite(Date.parse(v))
function parseRelation(value: unknown, tenantId: string): EntityRelation {
  if (!record(value)) throw new Error('关系项不符合契约')
  const revision = value.relationRevision
  const version = value.version
  if (value.schemaVersion !== '1.0' || !uuid(value.id) || value.tenantId !== tenantId || !uuid(value.fromEntityId) || !uuid(value.toEntityId) || value.fromEntityId === value.toEntityId || !relationType(value.relationType) || typeof revision !== 'number' || !Number.isSafeInteger(revision) || revision < 1 || revision > 10000 || !instant(value.validFrom) || (value.validTo !== null && (!instant(value.validTo) || Date.parse(value.validTo) <= Date.parse(value.validFrom))) || !['fixture', 'zabbix-jsonrpc', 'import', 'unknown'].includes(String(value.dataMode)) || typeof version !== 'number' || !Number.isSafeInteger(version) || version < 1) throw new Error('关系项不符合契约')
  return value as unknown as EntityRelation
}
function parse(value: unknown, entityId: string, tenantId: string, expectedAsOf?: string | null): EntityRelationPage {
  if (!record(value) || value.schemaVersion !== '1.0' || !['memory', 'postgres'].includes(String(value.storage)) || value.tenantId !== tenantId || value.entityId !== entityId || !instant(value.asOf) || (expectedAsOf && value.asOf !== expectedAsOf) || !Array.isArray(value.items) || value.items.length > 50 || (value.nextCursor !== null && !uuid(value.nextCursor))) throw new Error('关系响应不符合契约')
  const items = value.items.map(item => parseRelation(item, tenantId))
  return { ...value, items } as unknown as EntityRelationPage
}
function parseHistoryEntry(value: unknown, tenantId: string): EntityRelationHistoryEntry {
  if (!record(value) || !('closeReason' in value)) throw new Error('关系历史项不符合契约')
  const relation = parseRelation(value.relation, tenantId)
  const closeReason = value.closeReason
  if (closeReason !== null && (typeof closeReason !== 'string' || closeReason.length < 1 || closeReason.length > 256)) throw new Error('关系历史项不符合契约')
  if (typeof closeReason === 'string' && (relation.validTo === null || !instant(relation.validTo))) throw new Error('关系历史项不符合契约')
  return { relation, closeReason: closeReason as string | null }
}
function parseHistory(value: unknown, entityId: string, tenantId: string): EntityRelationHistoryPage {
  if (!record(value) || value.schemaVersion !== '1.0' || !['memory', 'postgres'].includes(String(value.storage)) || value.tenantId !== tenantId || value.entityId !== entityId || !Array.isArray(value.items) || value.items.length > 50 || (value.nextCursor !== null && !uuid(value.nextCursor))) throw new Error('关系历史响应不符合契约')
  return { ...value, items: value.items.map(item => parseHistoryEntry(item, tenantId)) } as unknown as EntityRelationHistoryPage
}
export async function pageEntityRelations(entityId: string, tenantId: string, after: string | null, asOf: string | null, signal: AbortSignal) {
  if (!uuid(entityId) || (after !== null && !uuid(after))) throw new Error('资产标识无效')
  if (asOf !== null && !instant(asOf)) throw new Error('关系时间锚无效')
  const query = new URLSearchParams({ limit: '25' }); if (after) query.set('after', after); if (asOf) query.set('asOf', asOf)
  return parse(await platformClient.request(`/api/v1/entities/${encodeURIComponent(entityId)}/relations?${query}`, { signal }), entityId, tenantId, asOf)
}
export async function pageEntityRelationHistory(entityId: string, tenantId: string, after: string | null, signal: AbortSignal): Promise<EntityRelationHistoryPage> {
  if (!uuid(entityId) || (after !== null && !uuid(after))) throw new Error('资产标识无效')
  const query = new URLSearchParams({ limit: '25' }); if (after) query.set('after', after)
  return parseHistory(await platformClient.request(`/api/v1/entities/${encodeURIComponent(entityId)}/relations/history?${query}`, { signal }), entityId, tenantId)
}
export async function createEntityRelation(entityId: string, tenantId: string, command: EntityRelationCommand, signal: AbortSignal): Promise<EntityRelationReceipt> {
  if (!uuid(entityId) || !uuid(command.requestId) || command.fromEntityId.toLowerCase() !== entityId.toLowerCase() || command.toEntityId === entityId) throw new Error('关系端点无效')
  const value = await platformClient.request(`/api/v1/entities/${encodeURIComponent(entityId)}/relations`, { method: 'POST', body: command, signal })
  if (!record(value) || value.schemaVersion !== '1.0' || value.requestId !== command.requestId || typeof value.replayed !== 'boolean' || !record(value.relation)) throw new Error('关系写入回执不符合契约')
  return { schemaVersion: '1.0', requestId: value.requestId, replayed: value.replayed, relation: parseRelation(value.relation, tenantId) }
}
export async function closeEntityRelation(entityId: string, tenantId: string, command: EntityRelationCloseCommand, signal: AbortSignal): Promise<EntityRelationReceipt> {
  if (!uuid(entityId) || !uuid(command.requestId) || !uuid(command.relationId) || typeof command.reason !== 'string' || command.reason.trim().length < 1 || command.reason.length > 256 || command.reason.split('').some(char => char.charCodeAt(0) < 32 || char.charCodeAt(0) === 127) || (command.validTo !== null && !instant(command.validTo)) || !Number.isSafeInteger(command.expectedVersion) || command.expectedVersion < 1) throw new Error('关系终止请求无效')
  const value = await platformClient.request(`/api/v1/entities/${encodeURIComponent(entityId)}/relations/${encodeURIComponent(command.relationId)}/close`, { method: 'POST', body: command, signal })
  if (!record(value) || value.schemaVersion !== '1.0' || value.requestId !== command.requestId || typeof value.replayed !== 'boolean' || !record(value.relation)) throw new Error('关系终止回执不符合契约')
  return { schemaVersion: '1.0', requestId: value.requestId, replayed: value.replayed, relation: parseRelation(value.relation, tenantId) }
}
function boundedText(value: unknown, max: number): value is string {
  return typeof value === 'string' && value.length >= 1 && value.length <= max && !value.split('').some(char => char.charCodeAt(0) < 32 || char.charCodeAt(0) === 127)
}
function sourceKind(value: unknown): value is EntityRelationConfirmationEntry['sourceKind'] { return value === 'manual' || value === 'source' }
function parseConfirmationEntry(value: unknown): EntityRelationConfirmationEntry {
  if (!record(value) || !sourceKind(value.sourceKind) || !boundedText(value.sourceKey, 128) || !boundedText(value.batchRef, 128)
    || !['fixture', 'zabbix-jsonrpc', 'import', 'unknown'].includes(String(value.dataMode)) || !instant(value.confirmedAt)
    || (value.state !== 'CONFIRMED' && value.state !== 'WITHDRAWN')) throw new Error('关系确认条目不符合契约')
  const withdrawn = value.state === 'WITHDRAWN'
  if (withdrawn !== (value.withdrawnAt !== null) || withdrawn !== (value.withdrawReason !== null)) throw new Error('关系确认条目不符合契约')
  if (value.withdrawnAt !== null && (!instant(value.withdrawnAt) || Date.parse(value.withdrawnAt) < Date.parse(value.confirmedAt))) throw new Error('关系确认条目不符合契约')
  if (value.withdrawReason !== null && !boundedText(value.withdrawReason, 256)) throw new Error('关系确认条目不符合契约')
  return value as unknown as EntityRelationConfirmationEntry
}
function parseConfirmationReceipt(value: unknown, relationId: string, requestId: string): EntityRelationConfirmationReceipt {
  if (!record(value) || value.schemaVersion !== '1.0' || value.requestId !== requestId || typeof value.replayed !== 'boolean' || value.relationId !== relationId) throw new Error('关系确认回执不符合契约')
  return { schemaVersion: '1.0', requestId: value.requestId, replayed: value.replayed, relationId: value.relationId, entry: parseConfirmationEntry(value.entry) }
}
export async function correctEntityRelation(entityId: string, tenantId: string, command: EntityRelationCorrectionCommand, signal: AbortSignal): Promise<EntityRelationCorrectionReceipt> {
  if (!uuid(entityId) || !uuid(command.requestId) || !uuid(command.relationId) || !uuid(command.fromEntityId) || !uuid(command.toEntityId)
    || command.fromEntityId === command.toEntityId || !relationType(command.relationType) || !Number.isSafeInteger(command.relationRevision) || command.relationRevision < 1 || command.relationRevision > 10000
    || !boundedText(command.reason, 256) || command.reason.trim().length < 1 || !Number.isSafeInteger(command.expectedVersion) || command.expectedVersion < 1) throw new Error('关系更正请求无效')
  const value = await platformClient.request(`/api/v1/entities/${encodeURIComponent(entityId)}/relations/${encodeURIComponent(command.relationId)}/correct`, { method: 'POST', body: command, signal })
  if (!record(value) || value.schemaVersion !== '1.0' || value.requestId !== command.requestId || typeof value.replayed !== 'boolean' || value.previousRelationId !== command.relationId || !record(value.relation)) throw new Error('关系更正回执不符合契约')
  return { schemaVersion: '1.0', requestId: value.requestId, replayed: value.replayed, previousRelationId: value.previousRelationId, relation: parseRelation(value.relation, tenantId) }
}
export async function confirmEntityRelationSource(entityId: string, relationId: string, command: EntityRelationConfirmationCommand, signal: AbortSignal): Promise<EntityRelationConfirmationReceipt> {
  if (!uuid(entityId) || !uuid(relationId) || !uuid(command.requestId) || !sourceKind(command.sourceKind) || !boundedText(command.sourceKey, 128)
    || !boundedText(command.batchRef, 128) || !['fixture', 'zabbix-jsonrpc', 'import', 'unknown'].includes(String(command.dataMode))) throw new Error('关系确认请求无效')
  return parseConfirmationReceipt(await platformClient.request(`/api/v1/entities/${encodeURIComponent(entityId)}/relations/${encodeURIComponent(relationId)}/confirmations`, { method: 'POST', body: command, signal }), relationId, command.requestId)
}
export async function withdrawEntityRelationSourceConfirmation(entityId: string, relationId: string, command: EntityRelationConfirmationWithdrawCommand, signal: AbortSignal): Promise<EntityRelationConfirmationReceipt> {
  if (!uuid(entityId) || !uuid(relationId) || !uuid(command.requestId) || !sourceKind(command.sourceKind) || !boundedText(command.sourceKey, 128)
    || !boundedText(command.batchRef, 128) || !boundedText(command.reason, 256) || command.reason.trim().length < 1) throw new Error('关系确认撤回请求无效')
  return parseConfirmationReceipt(await platformClient.request(`/api/v1/entities/${encodeURIComponent(entityId)}/relations/${encodeURIComponent(relationId)}/confirmations/withdraw`, { method: 'POST', body: command, signal }), relationId, command.requestId)
}
export async function readEntityRelationConfirmations(entityId: string, relationId: string, tenantId: string, signal: AbortSignal): Promise<EntityRelationConfirmationView> {
  if (!uuid(entityId) || !uuid(relationId)) throw new Error('关系标识无效')
  const value = await platformClient.request(`/api/v1/entities/${encodeURIComponent(entityId)}/relations/${encodeURIComponent(relationId)}/confirmations`, { signal })
  if (!record(value) || value.schemaVersion !== '1.0' || !['memory', 'postgres'].includes(String(value.storage)) || value.tenantId !== tenantId || value.relationId !== relationId
    || (value.relationState !== 'ACTIVE' && value.relationState !== 'TERMINATED') || !Array.isArray(value.entries) || value.entries.length > 50) throw new Error('关系确认视图不符合契约')
  return { schemaVersion: '1.0', storage: value.storage, tenantId: value.tenantId, relationId: value.relationId, relationState: value.relationState, entries: value.entries.map(parseConfirmationEntry) } as unknown as EntityRelationConfirmationView
}
