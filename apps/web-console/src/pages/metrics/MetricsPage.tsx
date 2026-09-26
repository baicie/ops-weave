import { usePlatformSession } from '../../state/platform-session.ts'
import { useViewLocation, selectionSessionReset } from '../../state/view-location.ts'
import { metricsDefault, metricsHash, metricsSelection, type MetricsSelection } from '../../state/view-selection.ts'
import { createSignal, For, onCleanup, Show } from '@zeus-js/zeus'
import { LineChart } from '../../adapters/chart/LineChart.tsx'
import { forItem } from '../../adapters/zeus-ui/for-item.ts'
import { ZwButton } from '../../adapters/zeus-ui/ZwButton.tsx'
import { ZwInput } from '../../adapters/zeus-ui/ZwInput.tsx'
import { EntityRequestError, getEntity, pageEntities, type EntityItem } from '../../api/entities.ts'
import { MetricRequestError, listMetricDefinitions, queryMetricSeries, type MetricDefinitionItem, type MetricSeriesPage } from '../../api/metrics.ts'
import { type MetricIntent } from '../../state/metric-intent.ts'

const RANGES = [
  { id: '15m', label: 'Last 15m', seconds: 15 * 60 },
  { id: '30m', label: 'Last 30m', seconds: 30 * 60 },
  { id: '1h', label: 'Last 1h', seconds: 60 * 60 },
] as const

export function MetricsPage() {
  const resetOnSession = selectionSessionReset()
  const authenticated = usePlatformSession(change => {
    const reset = resetOnSession.changed(change), selection = reset ? metricsDefault() : selected()
    applySelection(selection)
    if (reset) { setIntentError(''); location.write(selection, true) }
    if (change.error) setError(change.error.message)
  })
  const [entities, setEntities] = createSignal<EntityItem[]>([])
  const [metrics, setMetrics] = createSignal<MetricDefinitionItem[]>([])
  const [entityId, setEntityId] = createSignal('')
  const [metricKey, setMetricKey] = createSignal('')
  const [range, setRange] = createSignal<(typeof RANGES)[number]['id']>('1h')
  const [busy, setBusy] = createSignal(false)
  const [loaded, setLoaded] = createSignal(false)
  const [error, setError] = createSignal('')
  const [page, setPage] = createSignal<MetricSeriesPage | null>(null)
  const [seriesView, setSeriesView] = createSignal<'rate' | 'raw'>('rate')
  const [intent, setIntent] = createSignal<MetricIntent | null>(null)
  const [intentError, setIntentError] = createSignal('')
  const [next, setNext] = createSignal<string | null>(null)
  const [cursor, setCursor] = createSignal<string | null>(null)
  const [history, setHistory] = createSignal<(string | null)[]>([])
  const [search, setSearch] = createSignal('')
  const [requestedEntity, setRequestedEntity] = createSignal<string | null>(null)
  const [requestedMetric, setRequestedMetric] = createSignal('')
  let abort: AbortController | undefined
  let disposed = false
  let requestId = 0
  onCleanup(() => {
    disposed = true
    abort?.abort()
  })

  function clearQuery() {
    abort?.abort()
    ++requestId
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
    return { q: search().trim(), after: cursor(), entityId: entityId() || requestedEntity(), metricKey: metricKey() || requestedMetric(), range: range(), from: intent()?.from ?? null, till: intent()?.till ?? null }
  }
  function applySelection(value: MetricsSelection) {
    clearCatalog(); setSearch(value.q); setCursor(value.after); setRequestedEntity(value.entityId); setRequestedMetric(value.metricKey); setRange(value.range)
    setIntent(value.entityId && value.from !== null && value.till !== null ? { entityId: value.entityId, from: value.from, till: value.till } : null)
  }
  const location = useViewLocation('/metrics', metricsSelection, metricsHash,
    value => { setIntentError(''); applySelection(value) }, () => { applySelection(metricsDefault()); setIntentError('指标链接的资产或时间窗口无效，请重置筛选后重试') })
  function resetFilters() { applySelection(metricsDefault()); setIntentError(''); location.write(metricsDefault()) }
  function chooseRange(value: MetricsSelection['range']) {
    clearQuery(); setIntent(null); setRange(value)
    try { location.write(selected()) } catch (cause) { failure(cause) }
  }
  function writeSelection() { try { location.write(selected()) } catch (cause) { failure(cause) } }
  function chooseMetric(value: string) { clearQuery(); setMetricKey(value); setRequestedMetric(value); writeSelection() }
  function chooseEntity(value: string) { clearQuery(); setEntityId(value); if (intent()) setIntent({ ...intent()!, entityId: value }); writeSelection() }
  function browseHosts() { clearCatalog(); setRequestedEntity(null); setIntent(null); writeSelection() }

  async function loadCatalog(direction: 'first' | 'next' | 'previous' = 'first') {
    resetOnSession.beginRead()
    abort?.abort()
    const controller = new AbortController(); abort = controller
    const timeout = window.setTimeout(() => controller.abort(), 15000)
    const current = ++requestId
    const after = direction === 'next' ? next() : direction === 'previous' ? history().at(-1) ?? null : cursor()
    const past = direction === 'next' ? [...history(), cursor()] : direction === 'previous' ? history().slice(0, -1) : history()
    const target = requestedEntity(), preferredMetric = metricKey() || requestedMetric(), selection = selected()
    setBusy(true)
    setError('')
    setPage(null)
    setLoaded(false)
    setEntities([])
    setMetrics([])
    setEntityId('')
    setMetricKey('')
    try {
      if (intentError()) throw new Error(intentError())
      metricsHash({ ...selection, after })
      const [entityList, metricList] = await Promise.all([
        target ? getEntity(target, controller.signal).then(item => ({ items: [item], nextCursor: null }))
          : pageEntities({ q: search(), type: 'host', lifecycle: '' }, after, controller.signal),
        listMetricDefinitions(controller.signal),
      ])
      if (disposed || current !== requestId) return
      if (preferredMetric && !metricList.some(item => item.metricKey === preferredMetric)) throw new Error('选中的指标已不可用，请重置筛选后重新选择')
      setEntities(entityList.items)
      setMetrics(metricList)
      setEntityId(entityList.items[0]?.id ?? '')
      setMetricKey(preferredMetric || metricList[0]?.metricKey || '')
      if (entityList.items.length === 0) setIntent(null)
      setLoaded(true)
      setNext(entityList.nextCursor); setCursor(after); setHistory(past)
      location.write(selected())
    } catch (cause) {
      if (disposed || current !== requestId) return
      failure(cause)
    } finally {
      window.clearTimeout(timeout)
      if (!disposed && current === requestId) setBusy(false)
    }
  }

  async function loadSeries() {
    resetOnSession.beginRead()
    abort?.abort()
    const controller = new AbortController(); abort = controller
    const timeout = window.setTimeout(() => controller.abort(), 15000)
    const current = ++requestId
    const selectedRange = RANGES.find(item => item.id === range()) ?? RANGES[2]
    const till = intent()?.till ?? Math.floor(Date.now() / 1000)
    const from = intent()?.from ?? till - selectedRange.seconds
    setBusy(true)
    setError('')
    setPage(null)
    setSeriesView('rate')
    try {
      if (intentError()) throw new Error(intentError())
      setIntent({ entityId: entityId(), from, till }); location.write(selected())
      const result = await queryMetricSeries(entityId(), metricKey(), from, till, controller.signal)
      if (disposed || current !== requestId) return
      setPage(result)
    } catch (cause) {
      if (disposed || current !== requestId) return
      failure(cause)
    } finally {
      window.clearTimeout(timeout)
      if (!disposed && current === requestId) setBusy(false)
    }
  }

  const selectedEntity = () => entities().find(item => item.id === page()?.entityId)
  const selectedMetric = () => metrics().find(item => item.metricKey === page()?.metricKey)

  return (
    <section class="panel" data-page="metrics">
      <h2>指标</h2>
      <p>曲线来自已采集的时序库。页面不查询 Zabbix，也不提交查询语句。</p>
      <p>查询地址保留资源、指标和实际时间窗口。刷新或返回只还原选择，请重新读取目录和查询；选择 Last 时间范围后可查询最近数据。</p>
      <Show when={intent()}><p data-metric-window>{`固定时间窗口（UTC）：${new Date((intent()?.from ?? 0) * 1000).toISOString()} 至 ${new Date((intent()?.till ?? 0) * 1000).toISOString()}。读取目录会重新验证资产权限。`}</p></Show>
      <Show when={requestedEntity()}><p data-metric-selection>{`选中资产 ${requestedEntity()} · 指标 ${requestedMetric() || '读取目录后选择'}`}</p></Show>
      <Show when={!requestedEntity()}><label>筛选 Host 名称或 IP<ZwInput value={search()} onValueChange={value => { clearCatalog(); setSearch(value); setIntent(null) }} /></label></Show>
      <div class="actions">
        <ZwButton variant="outline" disabled={busy() || !authenticated() || intentError() !== ''} loading={busy()} onPress={() => { void loadCatalog() }}>
          读取资产和指标
        </ZwButton>
        <ZwButton variant="outline" disabled={busy()} onPress={resetFilters}>重置指标筛选</ZwButton>
        <Show when={requestedEntity()}><ZwButton variant="outline" disabled={busy()} onPress={browseHosts}>返回 Host 列表</ZwButton></Show>
      </div>
      <Show when={loaded()}>
        <div class="metric-controls">
          <label>
            Host
            <select prop:value={entityId()} onChange={event => chooseEntity((event.target as HTMLSelectElement).value)}>
              <For each={entities()}>
                {row => <EntityOption item={forItem(row)} selected={entityId()} />}
              </For>
            </select>
          </label>
          <Show when={!requestedEntity()}><div class="actions">
            <ZwButton variant="outline" disabled={busy() || history().length === 0} onPress={() => { void loadCatalog('previous') }}>上一页 Host</ZwButton>
            <ZwButton variant="outline" disabled={busy() || next() === null} onPress={() => { void loadCatalog('next') }}>下一页 Host</ZwButton>
          </div></Show>
          <label>
            Metric
            <select prop:value={metricKey()} onChange={event => chooseMetric((event.target as HTMLSelectElement).value)}>
              <For each={metrics()}>
                {row => <MetricOption item={forItem(row)} selected={metricKey()} />}
              </For>
            </select>
          </label>
          <div class="actions">
            <For each={RANGES}>
              {row => <RangeButton item={forItem(row)} selected={intent() ? '' : range()} onSelect={chooseRange} />}
            </For>
            <ZwButton variant="primary" disabled={busy() || entityId() === '' || metricKey() === ''} onPress={() => { void loadSeries() }}>
              查询
            </ZwButton>
          </div>
        </div>
      </Show>
      <p role="alert">{intentError() || error()}</p>
      <Show when={busy()}>
        <p data-state="loading">Loading</p>
      </Show>
      <Show when={page()}>
        <SeriesView entity={selectedEntity()?.name ?? entityId()} metric={selectedMetric()?.displayName ?? metricKey()}
          page={page() as MetricSeriesPage} view={seriesView()} onView={setSeriesView} />
      </Show>
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
      <Show when={props.page.derivation}>
        <div class="actions" data-derivation="counter-rate">
          <ZwButton variant={props.view === 'rate' ? 'primary' : 'outline'} onPress={() => props.onView('rate')}>变化率</ZwButton>
          <ZwButton variant={props.view === 'raw' ? 'primary' : 'outline'} onPress={() => props.onView('raw')}>原始累计值</ZwButton>
        </div>
        <p data-derivation-note={props.view}>{props.view === 'rate'
          ? '每秒变化率；计数器重置按零重计（reset-counts-from-zero），重置区间单独标注，原始点保留。'
          : '原始累计值（未做变化率推导）；计数器回绕会表现为下降。'}</p>
      </Show>
      <Show when={props.page.status.kind === 'NO_DATA'}>
        <p data-state="no-data">No data</p>
      </Show>
      <Show when={!props.page.status.fresh && props.page.status.lastPointAt !== null}>
        <p data-state="stale">Stale</p>
      </Show>
      <Show when={props.page.status.partial}>
        <p data-state="partial">数据不完整</p>
      </Show>
      <Show when={props.page.series.length > 0}>
        <LineChart series={props.page.series} view={rateView ? 'rate' : 'raw'} />
        <p>{`Updated: ${updated}`}</p>
        <Show when={rateView && !hasRates}>
          <p data-state="no-rates">当前窗口没有可推导的变化率区间</p>
        </Show>
        <Show when={rateView && resets.length > 0}>
          <ul data-counter-resets>
            <For each={resets}>
              {rate => <li data-counter-reset={String(rate.at)}>{`计数器重置：${new Date(rate.at).toLocaleTimeString()} 起按零重计，本区间 ${rate.rate}/s`}</li>}
            </For>
          </ul>
        </Show>
        <ul>
          <For each={props.page.series}>
            {row => <SeriesLegend series={forItem(row)} view={rateView ? 'rate' : 'raw'} />}
          </For>
        </ul>
      </Show>
    </div>
  )
}

function EntityOption(props: { item: EntityItem; selected: string }) {
  return <option value={props.item.id} prop:selected={props.selected === props.item.id}>{props.item.name}</option>
}

function MetricOption(props: { item: MetricDefinitionItem; selected: string }) {
  return <option value={props.item.metricKey} prop:selected={props.selected === props.item.metricKey}>{props.item.displayName}</option>
}

function RangeButton(props: { item: (typeof RANGES)[number]; selected: string; onSelect: (value: (typeof RANGES)[number]['id']) => void }) {
  return <ZwButton variant={props.selected === props.item.id ? 'primary' : 'outline'} onPress={() => props.onSelect(props.item.id)}>{props.item.label}</ZwButton>
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
