export type MetricDefinitionItem = {
  metricKey: string
  displayName: string
  unit: string
}

export type MetricSeriesPoint = {
  at: number
  value: string
}

export type MetricSeriesRow = {
  sourceInstanceId: string
  dimensions: Record<string, string>
  points: MetricSeriesPoint[]
}

export type MetricSeriesPage = {
  entityId: string
  metricKey: string
  unit: string
  from: number
  till: number
  series: MetricSeriesRow[]
  status: {
    kind: 'AVAILABLE' | 'NO_DATA' | 'STALE' | 'PARTIAL'
    fresh: boolean
    lastPointAt: number | null
    partial: boolean
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}

export function parseMetricDefinitions(value: unknown): MetricDefinitionItem[] {
  if (!isRecord(value) || !Array.isArray(value.items)) throw new Error('接口响应结构不正确')
  return value.items.map(item => {
    if (!isRecord(item) || typeof item.metricKey !== 'string' || typeof item.displayName !== 'string') {
      throw new Error('接口响应结构不正确')
    }
    return { metricKey: item.metricKey, displayName: item.displayName, unit: typeof item.unit === 'string' ? item.unit : '' }
  })
}

export function parseMetricSeries(value: unknown): MetricSeriesPage {
  if (!isRecord(value) || !isRecord(value.status) || !Array.isArray(value.series)) throw new Error('接口响应结构不正确')
  const kind = value.status.kind
  if (kind !== 'AVAILABLE' && kind !== 'NO_DATA' && kind !== 'STALE' && kind !== 'PARTIAL') {
    throw new Error('接口响应结构不正确')
  }
  const series = value.series.map(row => {
    if (!isRecord(row) || typeof row.sourceInstanceId !== 'string' || !Array.isArray(row.points) || !isRecord(row.dimensions)) {
      throw new Error('接口响应结构不正确')
    }
    const dimensions: Record<string, string> = {}
    for (const [key, dimension] of Object.entries(row.dimensions)) {
      if (typeof dimension === 'string') dimensions[key] = dimension
    }
    const points = row.points.map(point => {
      if (!Array.isArray(point) || typeof point[0] !== 'number' || typeof point[1] !== 'string') {
        throw new Error('接口响应结构不正确')
      }
      return { at: point[0], value: point[1] }
    })
    return { sourceInstanceId: row.sourceInstanceId, dimensions, points }
  })
  return {
    entityId: typeof value.entityId === 'string' ? value.entityId : '',
    metricKey: typeof value.metricKey === 'string' ? value.metricKey : '',
    unit: typeof value.unit === 'string' ? value.unit : '',
    from: typeof value.from === 'number' ? value.from : 0,
    till: typeof value.till === 'number' ? value.till : 0,
    series,
    status: {
      kind,
      fresh: value.status.fresh === true,
      lastPointAt: typeof value.status.lastPointAt === 'number' ? value.status.lastPointAt : null,
      partial: value.status.partial === true,
    },
  }
}

export async function listMetricDefinitions(token: string, signal: AbortSignal): Promise<MetricDefinitionItem[]> {
  const response = await fetch('/api/v1/metrics/definitions', { headers: { Authorization: `Bearer ${token}` }, signal })
  if (!response.ok) throw new Error(`指标目录请求失败（HTTP ${response.status}）`)
  return parseMetricDefinitions(await response.json())
}

export async function queryMetricSeries(token: string, entityId: string, metricKey: string, from: number, till: number, signal: AbortSignal): Promise<MetricSeriesPage> {
  const path = `/api/v1/entities/${encodeURIComponent(entityId)}/metrics/${encodeURIComponent(metricKey)}/series?from=${from}&till=${till}&maxPoints=500`
  const response = await fetch(path, { headers: { Authorization: `Bearer ${token}` }, signal })
  if (!response.ok) {
    let error = ''
    try {
      const body: unknown = await response.json()
      if (isRecord(body) && typeof body.error === 'string') error = body.error
    } catch { error = '' }
    throw new Error(error === '' ? `指标查询失败（HTTP ${response.status}）` : `指标查询失败（${error}）`)
  }
  return parseMetricSeries(await response.json())
}
