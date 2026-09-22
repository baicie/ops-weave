export type EntityItem = {
  id: string
  entityType: string
  name: string
  lifecycle: string
  attributes: {
    hostId: string
    ip: string
    status: string
    source: string
    lastSeen: string
    rawReference: string
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
      entityType: text(item.entityType),
      name: item.name,
      lifecycle: item.lifecycle,
      attributes: {
        hostId: text(attributes.hostId),
        ip: text(attributes.ip),
        status: text(attributes.status),
        source: text(attributes.source),
        lastSeen: text(attributes.lastSeen),
        rawReference: text(attributes.rawReference),
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

async function request(path: string, method: 'GET' | 'POST', token: string, signal: AbortSignal): Promise<unknown> {
  const response = await fetch(path, {
    method,
    headers: { Authorization: `Bearer ${token}` },
    signal,
  })
  if (!response.ok) {
    let failureCode = ''
    let scannedPages: number | null = null
    try {
      const body: unknown = await response.json()
      if (isRecord(body) && typeof body.failureCode === 'string') {
        failureCode = body.failureCode
      }
      if (isRecord(body) && typeof body.pages === 'number') {
        scannedPages = body.pages
      }
    } catch {
      failureCode = ''
    }
    const code = failureCode === '' ? '' : ` ${failureCode}`
    const scanned = scannedPages === null ? '' : `，已扫描 ${scannedPages} 页`
    throw new Error(`资产请求失败（HTTP ${response.status}${code}${scanned}）。失败不会清空已显示的资产。`)
  }
  return response.json()
}

export function listEntities(token: string, signal: AbortSignal): Promise<EntityList> {
  return request('/api/v1/entities', 'GET', token, signal).then(parseEntityList)
}

export function syncZabbixHosts(token: string, signal: AbortSignal): Promise<HostSyncResult> {
  return request('/api/v1/integrations/zabbix/hosts/sync', 'POST', token, signal).then(parseHostSync)
}
