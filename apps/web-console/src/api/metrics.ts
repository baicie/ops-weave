import { platformClient } from './http.ts'
export type MetricDefinitionItem = {
  metricKey: string
  displayName: string
  unit: string
}

export type MetricSeriesPoint = {
  at: number
  value: string
}

export type MetricCounterRate = {
  at: number
  rate: string
  counterReset: boolean
}

export type MetricSeriesDerivation = {
  kind: 'counter-rate'
  resetPolicy: 'reset-counts-from-zero'
}

export type MetricSeriesRow = {
  sourceInstanceId: string
  dataMode: string
  externalItemId: string
  mappingRevision: number
  unit: string
  dimensions: Record<string, string>
  points: MetricSeriesPoint[]
  counterRates: MetricCounterRate[]
}

export type MetricSeriesPage = {
  entityId: string
  metricKey: string
  unit: string
  from: number
  till: number
  series: MetricSeriesRow[]
  derivation: MetricSeriesDerivation | null
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
  // Wire contract: contracts/schemas/v1/metric-series-page.schema.json.
  if (!isRecord(value) || !isRecord(value.status) || !Array.isArray(value.series)) throw new Error('接口响应结构不正确')
  const label = (input: unknown): input is string => typeof input === 'string' && /^[a-zA-Z0-9_.:/%\-]{1,128}$/.test(input)
  const integer = (input: unknown): input is number => typeof input === 'number' && Number.isSafeInteger(input) && input >= 0
  const rateText = (input: unknown): input is string => typeof input === 'string' && input.length <= 700
    && /^(0|[1-9][0-9]*)(\.[0-9]+)?$/.test(input) && Number.isFinite(Number(input))
  if (typeof value.entityId !== 'string' || !/^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(value.entityId)
    || !label(value.metricKey) || !label(value.unit) || !integer(value.from) || !integer(value.till)
    || value.till > 9_999_999_999 || value.till < value.from || value.till - value.from > 3600
    || typeof value.status.fresh !== 'boolean' || typeof value.status.partial !== 'boolean') {
    throw new Error('接口响应结构不正确')
  }
  // A derived counter view is only accepted with its stated policy; rates without one are rejected.
  let derivation: MetricSeriesDerivation | null = null
  if (value.derivation !== undefined && value.derivation !== null) {
    if (!isRecord(value.derivation) || Object.keys(value.derivation).length !== 2
      || value.derivation.kind !== 'counter-rate' || value.derivation.resetPolicy !== 'reset-counts-from-zero') {
      throw new Error('接口响应结构不正确')
    }
    derivation = { kind: 'counter-rate', resetPolicy: 'reset-counts-from-zero' }
  }
  const from = value.from
  const till = value.till
  const kind = value.status.kind
  if (kind !== 'AVAILABLE' && kind !== 'NO_DATA' && kind !== 'STALE' && kind !== 'PARTIAL') {
    throw new Error('接口响应结构不正确')
  }
  const series = value.series.map(row => {
    if (!isRecord(row) || !label(row.sourceInstanceId) || !label(row.dataMode) || !label(row.externalItemId)
      || !integer(row.mappingRevision) || row.mappingRevision < 1 || row.unit !== value.unit
      || !Array.isArray(row.points) || row.points.length === 0 || !isRecord(row.dimensions)) {
      throw new Error('接口响应结构不正确')
    }
    const dimensions: Record<string, string> = {}
    for (const [key, dimension] of Object.entries(row.dimensions)) {
      if (!/^[a-zA-Z_][a-zA-Z0-9_]{0,63}$/.test(key) || !label(dimension)) throw new Error('接口响应结构不正确')
      Object.defineProperty(dimensions, key, { value: dimension, enumerable: true })
    }
    const points = row.points.map(point => {
      if (!Array.isArray(point) || point.length !== 2 || !integer(point[0])
        || point[0] < from * 1000 || point[0] >= (till + 1) * 1000
        || typeof point[1] !== 'string' || point[1].length > 700
        || !/^-?(0|[1-9][0-9]*)(\.[0-9]+)?$/.test(point[1]) || !Number.isFinite(Number(point[1]))) {
        throw new Error('接口响应结构不正确')
      }
      return { at: point[0], value: point[1] }
    })
    if (points.some((point, index) => index > 0 && point.at < points[index - 1].at)) throw new Error('接口响应结构不正确')
    if (row.counterRates !== undefined && !Array.isArray(row.counterRates)) throw new Error('接口响应结构不正确')
    if (derivation !== null && row.counterRates === undefined) throw new Error('接口响应结构不正确')
    const counterRates = (row.counterRates ?? []).map(item => {
      if (!isRecord(item) || Object.keys(item).length !== 3 || !integer(item.t) || item.t < from * 1000
        || item.t >= (till + 1) * 1000 || !rateText(item.rate) || typeof item.counterReset !== 'boolean') {
        throw new Error('接口响应结构不正确')
      }
      return { at: item.t, rate: item.rate, counterReset: item.counterReset }
    })
    if (counterRates.some((rate, index) => index > 0 && rate.at <= counterRates[index - 1].at)) throw new Error('接口响应结构不正确')
    // A rate belongs to an interval: its timestamp must be one of the later raw points, never the first.
    if (counterRates.length > Math.max(0, points.length - 1)
      || counterRates.some(rate => !points.some((point, index) => index > 0 && point.at === rate.at))) {
      throw new Error('接口响应结构不正确')
    }
    if (derivation === null && counterRates.length > 0) throw new Error('接口响应结构不正确')
    return { sourceInstanceId: row.sourceInstanceId, dataMode: row.dataMode, externalItemId: row.externalItemId,
      mappingRevision: row.mappingRevision, unit: value.unit as string, dimensions, points, counterRates }
  })
  const points = series.flatMap(row => row.points)
  const lastPointAt = points.length === 0 ? null : Math.max(...points.map(point => point.at))
  const fresh = lastPointAt !== null && lastPointAt >= (till - 300) * 1000
  if (points.length > 500 || value.status.lastPointAt !== lastPointAt || value.status.fresh !== fresh
    || (kind === 'PARTIAL') !== value.status.partial
    || (kind === 'NO_DATA' && points.length !== 0)
    || (kind === 'AVAILABLE' && !fresh) || (kind === 'STALE' && (fresh || points.length === 0))) {
    throw new Error('接口响应结构不正确')
  }
  return {
    entityId: value.entityId,
    metricKey: value.metricKey,
    unit: value.unit,
    from,
    till,
    series,
    derivation,
    status: {
      kind,
      fresh,
      lastPointAt,
      partial: value.status.partial,
    },
  }
}

export async function listMetricDefinitions(signal: AbortSignal): Promise<MetricDefinitionItem[]> {
  return parseMetricDefinitions(await platformClient.request('/api/v1/metrics/definitions', { signal, timeoutMs: 15000,
    error: status => new MetricRequestError(status, `指标目录请求失败（HTTP ${status}）`) }))
}
export async function queryMetricSeries(entityId: string, metricKey: string, from: number, till: number, signal: AbortSignal): Promise<MetricSeriesPage> {
  const path = `/api/v1/entities/${encodeURIComponent(entityId)}/metrics/${encodeURIComponent(metricKey)}/series?from=${from}&till=${till}&maxPoints=500`
  const result = parseMetricSeries(await platformClient.request(path, { signal, timeoutMs: 15000,
    error: (status, code) => new MetricRequestError(status, code ? `指标查询失败（${code}）` : `指标查询失败（HTTP ${status}）`) }))
  if (result.entityId !== entityId || result.metricKey !== metricKey || result.from !== from || result.till !== till) throw new Error('指标响应与查询范围不一致')
  return result
}

export class MetricRequestError extends Error {
  constructor(readonly status: number, message: string) { super(message) }
}
