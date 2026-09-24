import { createSignal, For, onCleanup, Show } from '@zeus-js/zeus'
import { LineChart } from '../../adapters/chart/LineChart.tsx'
import { forItem } from '../../adapters/zeus-ui/for-item.ts'
import { ZwButton } from '../../adapters/zeus-ui/ZwButton.tsx'
import { ZwInput } from '../../adapters/zeus-ui/ZwInput.tsx'
import { listEntities, type EntityItem } from '../../api/entities.ts'
import { listMetricDefinitions, queryMetricSeries, type MetricDefinitionItem, type MetricSeriesPage } from '../../api/metrics.ts'

const RANGES = [
  { id: '15m', label: 'Last 15m', seconds: 15 * 60 },
  { id: '30m', label: 'Last 30m', seconds: 30 * 60 },
  { id: '1h', label: 'Last 1h', seconds: 60 * 60 },
] as const

export function MetricsPage() {
  const [token, setToken] = createSignal('')
  const [entities, setEntities] = createSignal<EntityItem[]>([])
  const [metrics, setMetrics] = createSignal<MetricDefinitionItem[]>([])
  const [entityId, setEntityId] = createSignal('')
  const [metricKey, setMetricKey] = createSignal('')
  const [range, setRange] = createSignal<(typeof RANGES)[number]['id']>('1h')
  const [busy, setBusy] = createSignal(false)
  const [loaded, setLoaded] = createSignal(false)
  const [error, setError] = createSignal('')
  const [page, setPage] = createSignal<MetricSeriesPage | null>(null)
  let abort: AbortController | undefined
  let disposed = false
  let requestId = 0

  onCleanup(() => {
    disposed = true
    abort?.abort()
  })

  async function loadCatalog() {
    abort?.abort()
    abort = new AbortController()
    const current = ++requestId
    setBusy(true)
    setError('')
    setPage(null)
    try {
      const [entityList, metricList] = await Promise.all([
        listEntities(token(), abort.signal),
        listMetricDefinitions(token(), abort.signal),
      ])
      if (disposed || current !== requestId) return
      setEntities(entityList.items)
      setMetrics(metricList)
      setEntityId(entityList.items[0]?.id ?? '')
      setMetricKey(metricList[0]?.metricKey ?? '')
      setLoaded(true)
    } catch (cause) {
      if (disposed || current !== requestId) return
      setError(cause instanceof Error ? cause.message : '请求失败')
    } finally {
      if (!disposed && current === requestId) setBusy(false)
    }
  }

  async function loadSeries() {
    abort?.abort()
    abort = new AbortController()
    const current = ++requestId
    const selected = RANGES.find(item => item.id === range()) ?? RANGES[2]
    const till = Math.floor(Date.now() / 1000)
    setBusy(true)
    setError('')
    setPage(null)
    try {
      const result = await queryMetricSeries(token(), entityId(), metricKey(), till - selected.seconds, till, abort.signal)
      if (disposed || current !== requestId) return
      setPage(result)
    } catch (cause) {
      if (disposed || current !== requestId) return
      setError(cause instanceof Error ? cause.message : '请求失败')
    } finally {
      if (!disposed && current === requestId) setBusy(false)
    }
  }

  const selectedEntity = () => entities().find(item => item.id === entityId())
  const selectedMetric = () => metrics().find(item => item.metricKey === metricKey())

  return (
    <section class="panel" data-page="metrics">
      <h2>指标</h2>
      <p>曲线来自已采集的时序库。页面不查询 Zabbix，也不提交查询语句。</p>
      <label>
        平台开发 Token（仅保存在当前页面内存）
        <ZwInput type="password" autocomplete="off" value={token()} onValueChange={setToken} />
      </label>
      <div class="actions">
        <ZwButton variant="outline" disabled={busy() || token().length < 32} loading={busy()} onPress={() => { void loadCatalog() }}>
          读取资产和指标
        </ZwButton>
      </div>
      <Show when={loaded()}>
        <div class="metric-controls">
          <label>
            Host
            <select value={entityId()} onChange={event => setEntityId((event.target as HTMLSelectElement).value)}>
              <For each={entities()}>
                {row => {
                  const item = forItem(row)
                  return <option value={item.id}>{item.name}</option>
                }}
              </For>
            </select>
          </label>
          <label>
            Metric
            <select value={metricKey()} onChange={event => setMetricKey((event.target as HTMLSelectElement).value)}>
              <For each={metrics()}>
                {row => {
                  const item = forItem(row)
                  return <option value={item.metricKey}>{item.displayName}</option>
                }}
              </For>
            </select>
          </label>
          <div class="actions">
            <For each={RANGES}>
              {row => {
                const item = forItem(row)
                return (
                  <ZwButton variant={range() === item.id ? 'primary' : 'outline'} disabled={busy()} onPress={() => setRange(item.id)}>
                    {item.label}
                  </ZwButton>
                )
              }}
            </For>
            <ZwButton variant="primary" disabled={busy() || entityId() === '' || metricKey() === ''} onPress={() => { void loadSeries() }}>
              查询
            </ZwButton>
          </div>
        </div>
      </Show>
      <p role="alert">{error()}</p>
      <Show when={busy()}>
        <p data-state="loading">Loading</p>
      </Show>
      <Show when={page()}>
        <SeriesView entity={selectedEntity()?.name ?? entityId()} metric={selectedMetric()?.displayName ?? metricKey()} page={page() as MetricSeriesPage} />
      </Show>
    </section>
  )
}

function SeriesView(props: { entity: string; metric: string; page: MetricSeriesPage }) {
  const last = props.page.series.flatMap(series => series.points).at(-1)
  const updated = props.page.status.lastPointAt === null ? '—' : new Date(props.page.status.lastPointAt).toLocaleTimeString()
  return (
    <div>
      <p>{`Host: ${props.entity}`}</p>
      <p>{`Metric: ${props.metric}`}</p>
      <Show when={props.page.status.kind === 'NO_DATA'}>
        <p data-state="no-data">No data</p>
      </Show>
      <Show when={props.page.status.kind === 'STALE'}>
        <p data-state="stale">Stale</p>
      </Show>
      <Show when={props.page.status.partial}>
        <p data-state="partial">数据不完整</p>
      </Show>
      <Show when={props.page.status.kind !== 'NO_DATA'}>
        <LineChart series={props.page.series} />
        <p>{`Last: ${last?.value ?? '—'} ${props.page.unit}   Updated: ${updated}`}</p>
        <ul>
          <For each={props.page.series}>
            {row => {
              const series = forItem(row)
              return <li>{`${series.sourceInstanceId} ${Object.entries(series.dimensions).map(([key, value]) => `${key}=${value}`).join(',')}`}</li>
            }}
          </For>
        </ul>
      </Show>
    </div>
  )
}
