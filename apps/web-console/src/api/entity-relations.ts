import { platformClient } from './http.ts'

export type EntityRelation = {
  schemaVersion: '1.0'; id: string; tenantId: string; fromEntityId: string; toEntityId: string
  relationType: string; relationRevision: number; validFrom: string; validTo: string | null
  dataMode: 'fixture' | 'zabbix-jsonrpc' | 'import' | 'unknown'; version: number
}
export type EntityRelationPage = { schemaVersion: '1.0'; storage: 'memory' | 'postgres'; tenantId: string; entityId: string; asOf: string; items: EntityRelation[]; nextCursor: string | null }
export type EntityRelationCommand = {
  requestId: string; relationType: string; relationRevision: number; fromEntityId: string; toEntityId: string
  validFrom: string; validTo: string | null; sourceRef?: string; dataMode?: EntityRelation['dataMode']
  expectedFromVersion: number; expectedToVersion: number
}
export type EntityRelationReceipt = { schemaVersion: '1.0'; requestId: string; replayed: boolean; relation: EntityRelation }
const uuid = (v: unknown): v is string => typeof v === 'string' && /^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(v)
const record = (v: unknown): v is Record<string, unknown> => !!v && typeof v === 'object' && !Array.isArray(v)
const relationType = (v: unknown): v is string => typeof v === 'string' && /^(builtin|custom)\.[a-z][a-z0-9_]{0,47}$/.test(v)
const instant = (v: unknown): v is string => typeof v === 'string' && Number.isFinite(Date.parse(v))
function parse(value: unknown, entityId: string, tenantId: string, expectedAsOf?: string | null): EntityRelationPage {
  if (!record(value) || value.schemaVersion !== '1.0' || !['memory', 'postgres'].includes(String(value.storage)) || value.tenantId !== tenantId || value.entityId !== entityId || !instant(value.asOf) || (expectedAsOf && value.asOf !== expectedAsOf) || !Array.isArray(value.items) || value.items.length > 50 || (value.nextCursor !== null && !uuid(value.nextCursor))) throw new Error('关系响应不符合契约')
  const items = value.items.map(item => {
    if (!record(item)) throw new Error('关系项不符合契约')
    const revision = item.relationRevision
    const version = item.version
    if (item.schemaVersion !== '1.0' || !uuid(item.id) || item.tenantId !== tenantId || !uuid(item.fromEntityId) || !uuid(item.toEntityId) || item.fromEntityId === item.toEntityId || !relationType(item.relationType) || typeof revision !== 'number' || !Number.isSafeInteger(revision) || revision < 1 || revision > 10000 || !instant(item.validFrom) || (item.validTo !== null && (!instant(item.validTo) || Date.parse(item.validTo) <= Date.parse(item.validFrom))) || !['fixture', 'zabbix-jsonrpc', 'import', 'unknown'].includes(String(item.dataMode)) || typeof version !== 'number' || !Number.isSafeInteger(version) || version < 1) throw new Error('关系项不符合契约')
    return item as unknown as EntityRelation
  })
  return { ...value, items } as unknown as EntityRelationPage
}
export async function pageEntityRelations(entityId: string, tenantId: string, after: string | null, asOf: string | null, signal: AbortSignal) {
  if (!uuid(entityId) || (after !== null && !uuid(after))) throw new Error('资产标识无效')
  if (asOf !== null && !instant(asOf)) throw new Error('关系时间锚无效')
  const query = new URLSearchParams({ limit: '25' }); if (after) query.set('after', after); if (asOf) query.set('asOf', asOf)
  return parse(await platformClient.request(`/api/v1/entities/${encodeURIComponent(entityId)}/relations?${query}`, { signal }), entityId, tenantId, asOf)
}
export async function createEntityRelation(entityId: string, tenantId: string, command: EntityRelationCommand, signal: AbortSignal): Promise<EntityRelationReceipt> {
  if (!uuid(entityId) || !uuid(command.requestId) || command.fromEntityId.toLowerCase() !== entityId.toLowerCase() || command.toEntityId === entityId) throw new Error('关系端点无效')
  const value = await platformClient.request(`/api/v1/entities/${encodeURIComponent(entityId)}/relations`, { method: 'POST', body: command, signal })
  if (!record(value) || value.schemaVersion !== '1.0' || value.requestId !== command.requestId || typeof value.replayed !== 'boolean' || !record(value.relation)) throw new Error('关系写入回执不符合契约')
  const page = parse({ schemaVersion: '1.0', storage: 'memory', tenantId, entityId, asOf: command.validFrom, items: [value.relation], nextCursor: null }, entityId, tenantId, command.validFrom)
  return { schemaVersion: '1.0', requestId: value.requestId, replayed: value.replayed, relation: page.items[0]! }
}
