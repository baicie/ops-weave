import { platformClient } from './http.ts'
type FieldAuthorityInfo = { reviewId: string; sourceInstanceId: string; observedAt: string; expiresAt: string; fields: string[] }
export type EntityItem = {
  id: string
  tenantId: string
  entityType: string
  name: string
  lifecycle: string
  version: number
  attributes: {
    owner?: string
    environment?: string
    fieldAuthority?: FieldAuthorityInfo
    hostId: string
    ip: string
    status: string
    source: string
    lastSeen: string
    rawReference: string
    sourceInstanceId: string
    dataMode: string
    pipelineId: string
    pipelineRevision: number | null
    pipelineDigest: string
  }
}

export type EntityList = {
  items: EntityItem[]
}

export type HostSyncResult = {
  accepted: number
  retired: number
  pages: number
  snapshotComplete: boolean
  dataMode: string
  inventoryStore: string
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}

function text(value: unknown): string {
  return typeof value === 'string' ? value : ''
}

function parseAuthority(value: unknown): FieldAuthorityInfo | undefined {
  if (value === undefined) return undefined
  if (!isRecord(value) || !uuid(value.reviewId) || typeof value.sourceInstanceId !== 'string'
      || typeof value.observedAt !== 'string' || !Number.isFinite(Date.parse(value.observedAt))
      || typeof value.expiresAt !== 'string' || !Number.isFinite(Date.parse(value.expiresAt))
      || value.dataMode !== 'import' || !Array.isArray(value.fields) || value.fields.length > 4
      || !value.fields.every(field => ['name','ip','owner','environment'].includes(field))) throw new Error('资产字段来源响应不正确')
  return value as unknown as FieldAuthorityInfo
}
export function parseEntityList(value: unknown): EntityList {
  if (!isRecord(value) || !Array.isArray(value.items)) {
    throw new Error('接口响应结构不正确')
  }
  const items = value.items.map(item => {
    if (!isRecord(item) || !isRecord(item.attributes)) {
      throw new Error('接口响应结构不正确')
    }
    if (typeof item.id !== 'string' || typeof item.name !== 'string' || typeof item.lifecycle !== 'string') {
      throw new Error('接口响应结构不正确')
    }
    const attributes = item.attributes
    return {
      id: item.id,
      tenantId: text(item.tenantId),
      entityType: text(item.entityType),
      name: item.name,
      lifecycle: item.lifecycle,
      version: typeof item.version === 'number' ? item.version : 0,
      attributes: {
        owner: text(attributes.owner),
        environment: text(attributes.environment),
        fieldAuthority: parseAuthority(attributes.fieldAuthority),
        hostId: text(attributes.hostId),
        ip: text(attributes.ip),
        status: text(attributes.status),
        source: text(attributes.source),
        lastSeen: text(attributes.lastSeen),
        rawReference: text(attributes.rawReference),
        sourceInstanceId: text(attributes.sourceInstanceId),
        dataMode: ['labeled-fixture', 'zabbix-jsonrpc'].includes(String(attributes.dataMode)) ? String(attributes.dataMode) : '',
        pipelineId: text(attributes.pipelineId),
        pipelineRevision: typeof attributes.pipelineRevision === 'number' ? attributes.pipelineRevision : null,
        pipelineDigest: text(attributes.pipelineDigest),
      },
    }
  })
  return { items }
}

export function parseHostSync(value: unknown): HostSyncResult {
  if (!isRecord(value) || typeof value.dataMode !== 'string' || value.snapshotComplete !== true) {
    throw new Error('接口响应结构不正确')
  }
  return {
    accepted: typeof value.accepted === 'number' ? value.accepted : 0,
    retired: typeof value.retired === 'number' ? value.retired : 0,
    pages: typeof value.pages === 'number' ? value.pages : 0,
    snapshotComplete: true,
    dataMode: value.dataMode,
    inventoryStore: text(value.inventoryStore),
  }
}

async function request(path: string, method: 'GET' | 'POST', signal: AbortSignal): Promise<unknown> {
  return platformClient.request(path, { method, signal, error: (status, _code, metadata) => new EntityRequestError(status,
    `资产请求失败（HTTP ${status}${metadata.failureCode ? ' ' + metadata.failureCode : ''}${metadata.pages === null ? '' : '，已扫描 ' + metadata.pages + ' 页'}）。`) })
}

export class EntityRequestError extends Error {
  constructor(readonly status: number, message: string) { super(message) }
}
export type EntityFilters = { q: string; type: string; lifecycle: string }
export type EntityPage = { storage: 'memory' | 'postgres'; items: EntityItem[]; nextCursor: string | null }
const uuid = (value: unknown): value is string => typeof value === 'string' && /^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(value)
function entity(value: unknown): EntityItem {
  if (!isRecord(value) || value.schemaVersion !== '1.0' || !uuid(value.id) || typeof value.tenantId !== 'string'
    || typeof value.entityType !== 'string' || !value.entityType || typeof value.name !== 'string' || !value.name || value.name.length > 255
    || !['DISCOVERED', 'ACTIVE', 'INACTIVE', 'DELETED', 'ARCHIVED'].includes(String(value.lifecycle))
    || typeof value.version !== 'number' || !Number.isSafeInteger(value.version) || value.version < 1) throw new Error('资产响应结构不正确')
  return parseEntityList({ items: [value] }).items[0]!
}
export async function pageEntities(filters: EntityFilters, after: string | null, signal: AbortSignal): Promise<EntityPage> {
  const params = new URLSearchParams({ q: filters.q.trim(), type: filters.type, lifecycle: filters.lifecycle, limit: '25' })
  if (after) params.set('after', after)
  const value = await request(`/api/v1/entities/page?${params}`, 'GET', signal)
  if (!isRecord(value) || !isRecord(value.query) || !['memory', 'postgres'].includes(String(value.storage))
    || !Array.isArray(value.items) || value.items.length > 25 || (value.nextCursor !== null && !uuid(value.nextCursor))
    || value.query.q !== filters.q.trim() || value.query.type !== filters.type || value.query.lifecycle !== filters.lifecycle
    || value.query.after !== after || value.query.limit !== 25) throw new Error('资产分页响应结构不正确')
  const items = value.items.map(entity)
  let previous = after ?? ''
  for (const item of items) {
    if (item.id <= previous) throw new Error('资产分页顺序不正确')
    previous = item.id
  }
  if (value.nextCursor !== null && (items.length !== 25 || value.nextCursor !== items.at(-1)?.id)) throw new Error('资产分页游标不正确')
  return { storage: value.storage as EntityPage['storage'], items, nextCursor: value.nextCursor as string | null }
}
export async function getEntity(id: string, signal: AbortSignal): Promise<EntityItem> {
  const item = entity(await request(`/api/v1/entities/${encodeURIComponent(id)}`, 'GET', signal))
  if (item.id !== id) throw new Error('资产详情与请求不一致')
  return item
}

export function listEntities(signal: AbortSignal): Promise<EntityList> {
  return request('/api/v1/entities', 'GET', signal).then(parseEntityList)
}

export function syncZabbixHosts(signal: AbortSignal): Promise<HostSyncResult> {
  return request('/api/v1/integrations/zabbix/hosts/sync', 'POST', signal).then(parseHostSync)
}
