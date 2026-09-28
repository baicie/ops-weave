import { useEffect, useRef, useState } from 'react'
import { usePlatformSession } from '../../state/platform-session.ts'
import { useViewLocation, useSelectionSessionReset } from '../../state/view-location.ts'
import { metricsDefault, metricsHash, metricsSelection, type MetricsSelection } from '../../state/view-selection.ts'
import { LineChart } from '../../adapters/chart/LineChart.tsx'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { EntityRequestError, getEntity, pageEntities, type EntityItem } from '../../api/entities.ts'
import { MetricRequestError, listMetricDefinitions, queryMetricSeries, type MetricDefinitionItem, type MetricSeriesPage } from '../../api/metrics.ts'
import type { MetricIntent } from '../../state/metric-intent.ts'

const RANGES = [
  { id: '15m', label: 'Last 15m', seconds: 15 * 60 },
  { id: '30m', label: 'Last 30m', seconds: 30 * 60 },
  { id: '1h', label: 'Last 1h', seconds: 60 * 60 },
] as const

export function MetricsPage() {
  const resetOnSession = useSelectionSessionReset()
  const [entities, setEntities] = useState<EntityItem[]>([])
  const [metrics, setMetrics] = useState<MetricDefinitionItem[]>([])
  const [entityId, setEntityId] = useState('')
  const [metricKey, setMetricKey] = useState('')
  const [range, setRange] = useState<(typeof RANGES)[number]['id']>('1h')
  const [busy, setBusy] = useState(false)
  const [loaded, setLoaded] = useState(false)
  const [error, setError] = useState('')
  const [page, setPage] = useState<MetricSeriesPage | null>(null)
  const [seriesView, setSeriesView] = useState<'rate' | 'raw'>('rate')
  const [intent, setIntent] = useState<MetricIntent | null>(null)
  const [intentError, setIntentError] = useState('')
  const [next, setNext] = useState<string | null>(null)
  const [cursor, setCursor] = useState<string | null>(null)
  const [history, setHistory] = useState<(string | null)[]>([])
  const [search, setSearch] = useState('')
  const [requestedEntity, setRequestedEntity] = useState<string | null>(null)
  const [requestedMetric, setRequestedMetric] = useState('')
  const abortRef = useRef<AbortController | undefined>(undefined)
  const disposedRef = useRef(false)
  const requestIdRef = useRef(0)

  function clearQuery() {
    abortRef.current?.abort()
    ++requestIdRef.current
    setBusy(false)
    setError('')
    setPage(null)
    setSeriesView('rate')
  }

  function clearCatalog() {
    clearQuery()
    setLoaded(false)
    setEntities([])
    setMetrics([])
    setEntityId('')
    setMetricKey('')
    setNext(null); setCursor(null); setHistory([])
  }

  function failure(cause: unknown) {
    if ((cause instanceof EntityRequestError || cause instanceof MetricRequestError) && [401, 403].includes(cause.status)) clearCatalog()
    setError(cause instanceof DOMException && cause.name === 'AbortError' ? '指标请求已取消或超时' : cause instanceof Error ? cause.message : '请求失败')
  }

  function selected(): MetricsSelection {
    return { q: search.trim(), after: cursor, entityId: entityId || requestedEntity, metricKey: metricKey || requestedMetric, range, from: intent?.from ?? null, till: intent?.till ?? null }
  }

  function applySelection(value: MetricsSelection) {
    clearCatalog(); setSearch(value.q); setCursor(value.after); setRequestedEntity(value.entityId); setRequestedMetric(value.metricKey); setRange(value.range)
    setIntent(value.entityId && value.from !== null && value.till !== null ? { entityId: value.entityId, from: value.from, till: value.till } : null)
  }

  const authenticated = usePlatformSession(change => {
    const reset = resetOnSession.changed(change)
    const selection = reset ? metricsDefault() : selected()
    applySelection(selection)
    if (reset) { setIntentError(''); location.write(selection, true) }
    if (change.error) setError(change.error.message)
  })

  const location = useViewLocation('/metrics', metricsSelection, metricsHash,
    value => { setIntentError(''); applySelection(value) },
    () => { applySelection(metricsDefault()); setIntentError('指标链接的资产或时间窗口无效，请重置筛选后重试') })

  useEffect(() => () => {
    disposedRef.current = true
    abortRef.current?.abort()
  }, [])

  function resetFilters() { applySelection(metricsDefault()); setIntentError(''); location.write(metricsDefault()) }
  function chooseRange(value: MetricsSelection['range']) {
    clearQuery(); setIntent(null); setRange(value)
    try {
      location.write({ q: search.trim(), after: cursor, entityId: entityId || requestedEntity, metricKey: metricKey || requestedMetric, range: value, from: null, till: null })
    } catch (cause) { failure(cause) }
  }
  function writeSelection(patch?: Partial<MetricsSelection>) {
    try { location.write({ ...selected(), ...patch }) } catch (cause) { failure(cause) }
  }
  function chooseMetric(value: string) { clearQuery(); setMetricKey(value); setRequestedMetric(value); writeSelection({ metricKey: value }) }
  function chooseEntity(value: string) {
    clearQuery()
    setEntityId(value)
    const nextIntent = intent ? { ...intent, entityId: value } : null
    if (nextIntent) setIntent(nextIntent)
    writeSelection({ entityId: value || requestedEntity, from: nextIntent?.from ?? null, till: nextIntent?.till ?? null })
  }
  function browseHosts() {
    clearCatalog(); setRequestedEntity(null); setIntent(null)
    writeSelection({ after: null, entityId: null, metricKey: requestedMetric, from: null, till: null })
  }

  async function loadCatalog(direction: 'first' | 'next' | 'previous' = 'first') {
    resetOnSession.beginRead()
    abortRef.current?.abort()
    const controller = new AbortController(); abortRef.current = controller
    const timeout = window.setTimeout(() => controller.abort(), 15000)
    const current = ++requestIdRef.current
    const after = direction === 'next' ? next : direction === 'previous' ? history.at(-1) ?? null : cursor
    const past = direction === 'next' ? [...history, cursor] : direction === 'previous' ? history.slice(0, -1) : history
    const target = requestedEntity
    const preferredMetric = metricKey || requestedMetric
    const selection = selected()
    setBusy(true)
    setError('')
    setPage(null)
    setLoaded(false)
    setEntities([])
    setMetrics([])
    setEntityId('')
    setMetricKey('')
    try {
      if (intentError) throw new Error(intentError)
      metricsHash({ ...selection, after })
      const [entityList, metricList] = await Promise.all([
        target ? getEntity(target, controller.signal).then(item => ({ items: [item], nextCursor: null }))
          : pageEntities({ q: search, type: 'host', lifecycle: '' }, after, controller.signal),
        listMetricDefinitions(controller.signal),
      ])
      if (disposedRef.current || current !== requestIdRef.current) return
      if (preferredMetric && !metricList.some(item => item.metricKey === preferredMetric)) throw new Error('选中的指标已不可用，请重置筛选后重新选择')
      const nextEntityId = entityList.items[0]?.id ?? ''
      const nextMetricKey = preferredMetric || metricList[0]?.metricKey || ''
      const nextIntent = entityList.items.length === 0 ? null : intent
      setEntities(entityList.items)
      setMetrics(metricList)
      setEntityId(nextEntityId)
      setMetricKey(nextMetricKey)
      if (entityList.items.length === 0) setIntent(null)
      setLoaded(true)
      setNext(entityList.nextCursor); setCursor(after); setHistory(past)
      location.write({
        q: search.trim(),
        after,
        entityId: nextEntityId || target,
        metricKey: nextMetricKey || preferredMetric,
        range,
        from: nextIntent?.from ?? null,
        till: nextIntent?.till ?? null,
      })
    } catch (cause) {
      if (disposedRef.current || current !== requestIdRef.current) return
      failure(cause)
    } finally {
      window.clearTimeout(timeout)
      if (!disposedRef.current && current === requestIdRef.current) setBusy(false)
    }
  }

  async function loadSeries() {
    resetOnSession.beginRead()
    abortRef.current?.abort()
    const controller = new AbortController(); abortRef.current = controller
    const timeout = window.setTimeout(() => controller.abort(), 15000)
    const current = ++requestIdRef.current
    const selectedRange = RANGES.find(item => item.id === range) ?? RANGES[2]
    const till = intent?.till ?? Math.floor(Date.now() / 1000)
    const from = intent?.from ?? till - selectedRange.seconds
    setBusy(true)
    setError('')
    setPage(null)
    setSeriesView('rate')
    try {
      if (intentError) throw new Error(intentError)
      setIntent({ entityId, from, till })
      location.write({ q: search.trim(), after: cursor, entityId: entityId || requestedEntity, metricKey: metricKey || requestedMetric, range, from, till })
      const result = await queryMetricSeries(entityId, metricKey, from, till, controller.signal)
      if (disposedRef.current || current !== requestIdRef.current) return
      setPage(result)
    } catch (cause) {
      if (disposedRef.current || current !== requestIdRef.current) return
      failure(cause)
    } finally {
      window.clearTimeout(timeout)
      if (!disposedRef.current && current === requestIdRef.current) setBusy(false)
    }
  }

  const selectedEntity = entities.find(item => item.id === page?.entityId)
  const selectedMetric = metrics.find(item => item.metricKey === page?.metricKey)

  return (
    <section className="panel" data-page="metrics">
      <h2>指标</h2>
      <p>曲线来自已采集的时序库。页面不查询 Zabbix，也不提交查询语句。</p>
      <p>查询地址保留资源、指标和实际时间窗口。刷新或返回只还原选择，请重新读取目录和查询；选择 Last 时间范围后可查询最近数据。</p>
      {intent ? <p data-metric-window>{`固定时间窗口（UTC）：${new Date(intent.from * 1000).toISOString()} 至 ${new Date(intent.till * 1000).toISOString()}。读取目录会重新验证资产权限。`}</p> : null}
      {requestedEntity ? <p data-metric-selection>{`选中资产 ${requestedEntity} · 指标 ${requestedMetric || '读取目录后选择'}`}</p> : null}
      {!requestedEntity ? (
        <label>筛选 Host 名称或 IP
          <Input value={search} onChange={e => { clearCatalog(); setSearch(e.currentTarget.value); setIntent(null) }} />
        </label>
      ) : null}
      <div className="actions">
        <Button variant="outline" disabled={busy || !authenticated || intentError !== ''} onClick={() => { void loadCatalog() }}>
          读取资产和指标
        </Button>
        <Button variant="outline" disabled={busy} onClick={resetFilters}>重置指标筛选</Button>
        {requestedEntity ? <Button variant="outline" disabled={busy} onClick={browseHosts}>返回 Host 列表</Button> : null}
      </div>
      {loaded ? (
        <div className="metric-controls">
          <label>
            Host
            <select value={entityId} onChange={event => chooseEntity(event.target.value)}>
              {entities.map(item => <EntityOption key={item.id} item={item} />)}
            </select>
          </label>
          {!requestedEntity ? (
            <div className="actions">
              <Button variant="outline" disabled={busy || history.length === 0} onClick={() => { void loadCatalog('previous') }}>上一页 Host</Button>
              <Button variant="outline" disabled={busy || next === null} onClick={() => { void loadCatalog('next') }}>下一页 Host</Button>
            </div>
          ) : null}
          <label>
            Metric
            <select value={metricKey} onChange={event => chooseMetric(event.target.value)}>
              {metrics.map(item => <MetricOption key={item.metricKey} item={item} />)}
            </select>
          </label>
          <div className="actions">
            {RANGES.map(item => (
              <RangeButton key={item.id} item={item} selected={intent ? '' : range} onSelect={chooseRange} />
            ))}
            <Button variant="default" disabled={busy || entityId === '' || metricKey === ''} onClick={() => { void loadSeries() }}>
              查询
            </Button>
          </div>
        </div>
      ) : null}
      <p role="alert">{intentError || error}</p>
      {busy ? <p data-state="loading">Loading</p> : null}
      {page ? (
        <SeriesView
          entity={selectedEntity?.name ?? entityId}
          metric={selectedMetric?.displayName ?? metricKey}
          page={page}
          view={seriesView}
          onView={setSeriesView}
        />
      ) : null}
    </section>
  )
}

function SeriesView(props: { entity: string; metric: string; page: MetricSeriesPage; view: 'rate' | 'raw'; onView: (value: 'rate' | 'raw') => void }) {
  const updated = props.page.status.lastPointAt === null ? '—' : new Date(props.page.status.lastPointAt).toLocaleTimeString()
  const rateView = props.page.derivation !== null && props.view === 'rate'
  const resets = props.page.series.flatMap(row => row.counterRates.filter(rate => rate.counterReset))
  const hasRates = props.page.series.some(row => row.counterRates.length > 0)
  return (
    <div>
      <p>{`Host: ${props.entity}`}</p>
      <p>{`Metric: ${props.metric}`}</p>
      {props.page.derivation ? (
        <>
          <div className="actions" data-derivation="counter-rate">
            <Button variant={props.view === 'rate' ? 'default' : 'outline'} onClick={() => props.onView('rate')}>变化率</Button>
            <Button variant={props.view === 'raw' ? 'default' : 'outline'} onClick={() => props.onView('raw')}>原始累计值</Button>
          </div>
          <p data-derivation-note={props.view}>{props.view === 'rate'
            ? '每秒变化率；计数器重置按零重计（reset-counts-from-zero），重置区间单独标注，原始点保留。'
            : '原始累计值（未做变化率推导）；计数器回绕会表现为下降。'}</p>
        </>
      ) : null}
      {props.page.status.kind === 'NO_DATA' ? <p data-state="no-data">No data</p> : null}
      {!props.page.status.fresh && props.page.status.lastPointAt !== null ? <p data-state="stale">Stale</p> : null}
      {props.page.status.partial ? <p data-state="partial">数据不完整</p> : null}
      {props.page.series.length > 0 ? (
        <>
          <LineChart series={props.page.series} view={rateView ? 'rate' : 'raw'} />
          <p>{`Updated: ${updated}`}</p>
          {rateView && !hasRates ? <p data-state="no-rates">当前窗口没有可推导的变化率区间</p> : null}
          {rateView && resets.length > 0 ? (
            <ul data-counter-resets>
              {resets.map(rate => (
                <li key={rate.at} data-counter-reset={String(rate.at)}>{`计数器重置：${new Date(rate.at).toLocaleTimeString()} 起按零重计，本区间 ${rate.rate}/s`}</li>
              ))}
            </ul>
          ) : null}
          <ul>
            {props.page.series.map(series => (
              <SeriesLegend key={`${series.sourceInstanceId}:${series.externalItemId}:${series.mappingRevision}`} series={series} view={rateView ? 'rate' : 'raw'} />
            ))}
          </ul>
        </>
      ) : null}
    </div>
  )
}

function EntityOption(props: { item: EntityItem }) {
  return <option value={props.item.id}>{props.item.name}</option>
}

function MetricOption(props: { item: MetricDefinitionItem }) {
  return <option value={props.item.metricKey}>{props.item.displayName}</option>
}

function RangeButton(props: { item: (typeof RANGES)[number]; selected: string; onSelect: (value: (typeof RANGES)[number]['id']) => void }) {
  return <Button variant={props.selected === props.item.id ? 'default' : 'outline'} onClick={() => props.onSelect(props.item.id)}>{props.item.label}</Button>
}

function SeriesLegend(props: { series: MetricSeriesPage['series'][number]; view: 'rate' | 'raw' }) {
  const series = props.series
  if (props.view === 'rate') {
    const last = series.counterRates.at(-1)
    const resets = series.counterRates.filter(rate => rate.counterReset).length
    return <li data-series-source={series.sourceInstanceId} data-series-view="rate">{`${series.sourceInstanceId} / ${series.dataMode} / ${series.externalItemId} / mapping ${series.mappingRevision} / ${Object.entries(series.dimensions).map(([key, value]) => `${key}=${value}`).join(',')} — Last rate: ${last?.rate ?? '—'} ${series.unit}/s (${last ? new Date(last.at).toLocaleTimeString() : '—'}) · resets: ${resets}`}</li>
  }
  const last = series.points.at(-1)
  return <li data-series-source={series.sourceInstanceId} data-series-view="raw">{`${series.sourceInstanceId} / ${series.dataMode} / ${series.externalItemId} / mapping ${series.mappingRevision} / ${Object.entries(series.dimensions).map(([key, value]) => `${key}=${value}`).join(',')} — Last: ${last?.value ?? '—'} ${series.unit} (${last ? new Date(last.at).toLocaleTimeString() : '—'})`}</li>
}
