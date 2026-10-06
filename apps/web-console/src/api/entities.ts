import { platformClient } from './http.ts'
type FieldAuthorityInfo = { reviewId: string; sourceInstanceId: string; observedAt: string; expiresAt: string; fields: string[] }
export type EntityItem = {
  id: string
  tenantId: string
  entityType: string
  name: string
  lifecycle: string
  version: number
  model?: EntityModelPin
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
  modelAttributes: Record<string, EntityAttributeValue>
}

export type EntityModelPin = { id: string; revision: number; digest: string }

export type EntityAttributeValue = string | number | boolean | null

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

function parseModelPin(value: unknown): EntityModelPin | undefined {
  if (value === undefined) return undefined
  if (!isRecord(value) || Object.keys(value).length !== 3 || !/^(builtin|custom)\.[a-z][a-z0-9_]{0,47}$/.test(String(value.id))
      || !Number.isSafeInteger(value.revision) || (value.revision as number) < 1 || (value.revision as number) > 10000
      || typeof value.digest !== 'string' || !/^sha256:[a-f0-9]{64}$/.test(value.digest)) throw new Error('资产模型版本固定信息不正确')
  return { id: value.id as string, revision: value.revision as number, digest: value.digest }
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
    const model = parseModelPin(item.model)
    const modelAttributes: Record<string, EntityAttributeValue> = Object.create(null)
    const reserved = new Set(['hostId', 'ip', 'status', 'source', 'lastSeen', 'rawReference', 'sourceInstanceId', 'dataMode',
      'pipelineId', 'pipelineRevision', 'pipelineDigest', 'fieldAuthority'])
    for (const [key, field] of Object.entries(attributes)) {
      if (reserved.has(key)) continue
      if (field === null || typeof field === 'string' || typeof field === 'number' && Number.isFinite(field) || typeof field === 'boolean') {
        modelAttributes[key] = field
      }
    }
    return {
      id: item.id,
      tenantId: text(item.tenantId),
      entityType: text(item.entityType),
      name: item.name,
      lifecycle: item.lifecycle,
      version: typeof item.version === 'number' ? item.version : 0,
      ...(model ? { model } : {}),
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
        dataMode: ['labeled-fixture', 'zabbix-jsonrpc', 'MANUAL_SAMPLE'].includes(String(attributes.dataMode)) ? String(attributes.dataMode) : '',
        pipelineId: text(attributes.pipelineId),
        pipelineRevision: typeof attributes.pipelineRevision === 'number' ? attributes.pipelineRevision : null,
        pipelineDigest: text(attributes.pipelineDigest),
      },
      modelAttributes,
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

export type EntityInstanceCommand = {
  requestId: string
  entityId: string
  model: { id: string; revision: number }
  name: string
  lifecycle?: EntityItem['lifecycle']
  attributes: Record<string, EntityAttributeValue>
  expectedVersion?: number
}

export type EntityInstanceReceipt = {
  entity: EntityItem
  replayed: boolean
  storage: string
  model: EntityModelPin
}

export class EntityWriteUnknownError extends Error {}

export async function createEntityInstance(command: EntityInstanceCommand, signal: AbortSignal): Promise<EntityInstanceReceipt> {
  const uuidPattern = /^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i
  if (!uuidPattern.test(command.requestId) || !uuidPattern.test(command.entityId)
    || !/^(builtin|custom)\.[a-z][a-z0-9_]{0,47}$/.test(command.model.id)
    || !Number.isSafeInteger(command.model.revision) || command.model.revision < 1
    || !command.name.trim() || command.name.length > 255 || Object.keys(command.attributes).length > 32
    || command.lifecycle !== undefined && !['DISCOVERED', 'ACTIVE', 'INACTIVE', 'DELETED', 'ARCHIVED'].includes(command.lifecycle)
    || command.expectedVersion !== undefined && (!Number.isSafeInteger(command.expectedVersion) || command.expectedVersion < 1 || command.expectedVersion > 9_007_199_254_740_990)
    || Object.values(command.attributes).some(value => value !== null && !['string', 'number', 'boolean'].includes(typeof value)
      || typeof value === 'number' && !Number.isFinite(value))) throw new Error('资产实例命令不符合契约')

  const body = {
    requestId: command.requestId,
    entityId: command.entityId,
    model: { id: command.model.id, revision: command.model.revision },
    name: command.name,
    ...(command.lifecycle === undefined ? {} : { lifecycle: command.lifecycle }),
    attributes: { ...command.attributes },
    ...(command.expectedVersion === undefined ? {} : { expectedVersion: command.expectedVersion }),
  }
  let value: unknown
  try {
    value = await platformClient.request('/api/v1/entities', {
      method: 'POST', body, signal,
      error: status => new EntityRequestError(status, status === 403
        ? '当前身份无权创建此资产。'
        : status === 409 ? '模型或资产版本已变化，请重新读取后再提交。'
          : status >= 500 ? '服务端未确认创建结果；请使用同一请求重试以核对原回执。'
            : `资产创建失败（HTTP ${status}）。`),
    })
  } catch (cause) {
    if (cause instanceof EntityRequestError && cause.status < 500) throw cause
    if (cause instanceof DOMException && cause.name === 'AbortError') throw cause
    throw new EntityWriteUnknownError('资产创建结果待确认。保留原请求标识后可安全重试。')
  }
  try {
    if (!isRecord(value) || value.schemaVersion !== '1.0' || typeof value.storage !== 'string' || !value.storage
      || typeof value.replayed !== 'boolean' || typeof value.tenantId !== 'string' || !value.tenantId
      || !isRecord(value.model) || value.model.id !== command.model.id || value.model.revision !== command.model.revision
      || typeof value.model.digest !== 'string' || !/^sha256:[a-f0-9]{64}$/.test(value.model.digest)) throw new Error('receipt')
    const item = entity(value.entity)
    if (item.id !== command.entityId || item.tenantId !== value.tenantId || item.model?.id !== value.model.id
      || item.model.revision !== value.model.revision || item.model.digest !== value.model.digest) throw new Error('receipt')
    return { entity: item, replayed: value.replayed, storage: value.storage, model: value.model as EntityInstanceReceipt['model'] }
  } catch {
    throw new EntityWriteUnknownError('服务端已响应，但创建回执无法确认；请使用原请求重试核对。')
  }
}

export function listEntities(signal: AbortSignal): Promise<EntityList> {
  return request('/api/v1/entities', 'GET', signal).then(parseEntityList)
}

export function syncZabbixHosts(signal: AbortSignal): Promise<HostSyncResult> {
  return request('/api/v1/integrations/zabbix/hosts/sync', 'POST', signal).then(parseHostSync)
}
