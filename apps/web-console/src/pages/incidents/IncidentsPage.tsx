import { usePlatformSession } from '../../state/platform-session.ts'
import { useViewLocation, selectionSessionReset } from '../../state/view-location.ts'
import { incidentDefault, incidentHash, incidentSelection, type IncidentSelection } from '../../state/view-selection.ts'
import { ProblemHistory } from './ProblemHistory.tsx'
import { createSignal, For, onCleanup, Show } from '@zeus-js/zeus'
import { forItem } from '../../adapters/zeus-ui/for-item.ts'
import { ZwButton } from '../../adapters/zeus-ui/ZwButton.tsx'
import { ZwInput } from '../../adapters/zeus-ui/ZwInput.tsx'
import { EntityRequestError, getEntity, type EntityItem } from '../../api/entities.ts'
import { INCIDENT_STATUSES, IncidentRequestError, getIncident, importProblems, listIncidents, transitionIncident,
  type ImportResult, type IncidentDetail, type IncidentHeader, type IncidentPage, type IncidentStatus, type Problem, type TimelineEntry, type TransitionRequest } from '../../api/incidents.ts'

const nextStatuses: Record<IncidentStatus, IncidentStatus[]> = {
  OPEN: ['INVESTIGATING'], INVESTIGATING: ['MITIGATED'], MITIGATED: ['RESOLVED', 'INVESTIGATING'], RESOLVED: ['CLOSED', 'INVESTIGATING'], CLOSED: [],
}
const gapLabels: Record<string, string> = { LOGS_NOT_CONNECTED: '日志尚未接入', CHANGES_NOT_CONNECTED: '变更尚未接入',
  ENTITY_MAPPING_MISSING: '部分 Host 尚未映射到资产', RECOVERY_EVENT_UNAVAILABLE: '恢复事件明细不可用' }

export function IncidentsPage() {
  const resetOnSession = selectionSessionReset()
  const authenticated = usePlatformSession(change => {
    const reset = resetOnSession.changed(change), selection = reset ? incidentDefault() : selected()
    applySelection(selection)
    if (reset) { setRouteError(''); location.write(selection, true) }
    if (change.error) setError(change.error.message)
  })
  const [filter, setFilter] = createSignal('')
  const [page, setPage] = createSignal<IncidentPage | null>(null)
  const [cursor, setCursor] = createSignal<string | null>(null)
  const [history, setHistory] = createSignal<(string | null)[]>([])
  const [detail, setDetail] = createSignal<IncidentDetail | null>(null)
  const [asset, setAsset] = createSignal<EntityItem | null>(null)
  const [imported, setImported] = createSignal<ImportResult | null>(null)
  const [from, setFrom] = createSignal(new Date(Date.now() - 3600000).toISOString().replace(/\.\d{3}Z$/, 'Z'))
  const [till, setTill] = createSignal(new Date().toISOString().replace(/\.\d{3}Z$/, 'Z'))
  const [busy, setBusy] = createSignal(false)
  const [error, setError] = createSignal('')
  const [message, setMessage] = createSignal('')
  const [pending, setPending] = createSignal<{ id: string; body: TransitionRequest } | null>(null)
  const [linkedIncident, setLinkedIncident] = createSignal<string | null>(null)
  const [routeError, setRouteError] = createSignal('')
  let controller: AbortController | undefined
  let sequence = 0
  let disposed = false
  onCleanup(() => { disposed = true; controller?.abort() })

  function clearData() { setPage(null); setCursor(null); setHistory([]); setDetail(null); setAsset(null); setImported(null); setPending(null); setMessage(''); setLinkedIncident(null) }
  function invalidate() { controller?.abort(); ++sequence; setBusy(false); setError(''); clearData() }
  function selected(): IncidentSelection { return { status: filter(), after: cursor(), incidentId: linkedIncident() } }
  function applySelection(value: IncidentSelection) { invalidate(); setFilter(value.status); setCursor(value.after); setLinkedIncident(value.incidentId) }
  const location = useViewLocation('/incidents', incidentSelection, incidentHash,
    value => { setRouteError(''); applySelection(value) }, cause => { applySelection(incidentDefault()); setRouteError(cause.message) })
  function resetFilters() { applySelection(incidentDefault()); setRouteError(''); location.write(incidentDefault()) }
  function changeWindow(value: string, field: 'from' | 'till') {
    controller?.abort(); ++sequence; setBusy(false); setError(''); setImported(null)
    if (field === 'from') setFrom(value); else setTill(value)
  }
  const disabled = () => busy() || !authenticated() || pending() !== null || routeError() !== ''
  async function run(action: (signal: AbortSignal, current: () => boolean) => Promise<void>) {
    resetOnSession.beginRead()
    controller?.abort(); const active = new AbortController(); controller = active
    const current = ++sequence; const isCurrent = () => !disposed && sequence === current
    const timeout = window.setTimeout(() => active.abort(), 35000)
    setBusy(true); setError(''); setMessage('')
    try { if (routeError()) throw new Error(routeError()); await action(active.signal, isCurrent) }
    catch (cause) {
      if (!isCurrent()) return
      if ((cause instanceof IncidentRequestError || cause instanceof EntityRequestError) && [401, 403].includes(cause.status)) clearData()
      setError(cause instanceof DOMException && cause.name === 'AbortError' ? '请求已取消或超时' : cause instanceof Error ? cause.message : '请求失败')
    } finally { window.clearTimeout(timeout); if (isCurrent()) setBusy(false) }
  }
  function list(direction: 'first' | 'next' | 'previous') {
    const after = direction === 'next' ? page()?.nextCursor ?? null : direction === 'previous' ? history().at(-1) ?? null : cursor()
    const past = direction === 'next' ? [...history(), cursor()] : direction === 'previous' ? history().slice(0, -1) : history()
    setPage(null); setDetail(null); setAsset(null)
    void run(async (signal, current) => {
      location.write({ ...selected(), after, incidentId: null }); setLinkedIncident(null)
      const result = await listIncidents(filter(), after, signal)
      if (current()) { setPage(result); setCursor(after); setHistory(past) }
    })
  }
  function open(id: string) {
    setDetail(null); setAsset(null)
    void run(async (signal, current) => {
      setLinkedIncident(id); location.write(selected())
      const result = await getIncident(id, signal)
      if (current()) { setDetail(result); setPending(null) }
    })
  }
  function importPage(next: boolean) {
    const afterEventId = next ? imported()?.nextAfterEventId ?? null : null
    void run(async (signal, current) => {
      const start = Date.parse(from()); const end = Date.parse(till())
      if (!/^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ$/.test(from()) || !/^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ$/.test(till())
        || !Number.isFinite(start) || !Number.isFinite(end) || start < 0 || end < start || end - start > 86400000) throw new Error('请输入 UTC 秒级时间，窗口不超过 24 小时')
      const result = await importProblems({ from: start / 1000, till: end / 1000, afterEventId, limit: 100 }, signal)
      if (current()) { setImported(result); setPage(null); setDetail(null); setAsset(null); setCursor(null); setHistory([]); setMessage('本页告警已导入，请刷新 Incident 列表。') }
    })
  }
  function transition(target?: IncidentStatus) {
    const selected = detail()?.record.incident
    const request = pending() ?? (selected && target ? { id: selected.id, body: { expectedVersion: selected.version, target, requestKey: crypto.randomUUID() } } : null)
    if (!request) return
    setPending(request)
    void run(async (signal, current) => {
      try {
        await transitionIncident(request.id, request.body, signal)
      } catch (cause) {
        if (current() && cause instanceof IncidentRequestError && cause.status >= 400 && cause.status < 500) {
          setPending(null); setDetail(null); setAsset(null)
          if (cause.status === 409) throw new Error('状态或版本冲突，可能仍有未恢复告警；请重新读取详情后操作。')
        }
        throw cause
      }
      if (!current()) return
      setPending(null); setDetail(null); setAsset(null); setPage(null)
      setMessage('状态已保存，正在重新读取详情。')
      const result = await getIncident(request.id, signal)
      if (current()) { setDetail(result); setMessage('状态已保存。') }
    })
  }
  function readAsset(id: string) {
    setAsset(null)
    void run(async (signal, current) => { const result = await getEntity(id, signal); if (current()) setAsset(result) })
  }
  return <section class="panel incident-panel" data-page="incidents">
    <h2>Incident 列表</h2>
    <p>查看已导入的外部告警、恢复事实和人工处理状态。来源恢复不会自动关闭 Incident。</p>
    <p>地址保留已应用的状态、游标与选中 Incident；刷新或返回后请重新读取。导入和人工操作不会由地址触发。</p>
    <details class="incident-import">
      <summary>从已配置的 Zabbix 导入告警</summary>
      <p>每次最多 100 条、24 小时。来源与租户由服务端配置；导入失败不会按空快照处理。开发 fixture 的固定窗口可用于本地演示。</p>
      <div class="pipeline-form">
        <label>开始时间（UTC）<ZwInput value={from()} disabled={busy() || pending() !== null} onValueChange={v => changeWindow(v, 'from')} /></label>
        <label>结束时间（UTC）<ZwInput value={till()} disabled={busy() || pending() !== null} onValueChange={v => changeWindow(v, 'till')} /></label>
      </div>
      <div class="actions">
        <ZwButton variant="outline" disabled={busy() || pending() !== null} onPress={() => { changeWindow('2026-09-21T12:00:00Z', 'from'); changeWindow('2026-09-21T13:00:00Z', 'till') }}>使用 fixture 时间窗口</ZwButton>
        <ZwButton variant="primary" disabled={disabled()} onPress={() => importPage(false)}>导入告警首批</ZwButton>
        <ZwButton variant="outline" disabled={disabled() || !imported()?.nextAfterEventId} onPress={() => importPage(true)}>导入下一批</ZwButton>
      </div>
      <Show when={imported()}><p data-problem-import>{`${imported()?.dataMode} / ${imported()?.storage} / ${imported()?.sourceInstanceId} · 接收 ${imported()?.accepted} · 新建 ${imported()?.createdIncidents} · 更新 ${imported()?.changedIncidents} · 未映射 Host ${imported()?.unmappedHosts}`}</p></Show>
    </details>
    <div class="metric-controls">
      <label>Incident 状态<select prop:value={filter()} disabled={busy() || pending() !== null} onChange={e => { invalidate(); setFilter((e.target as HTMLSelectElement).value) }}>
        <option value="" prop:selected={filter() === ''}>全部状态</option><For each={INCIDENT_STATUSES}>{row => <option value={forItem(row)} prop:selected={filter() === forItem(row)}>{forItem(row)}</option>}</For>
      </select></label>
    </div>
    <div class="actions">
      <ZwButton variant="primary" disabled={disabled()} onPress={() => list('first')}>刷新 Incident</ZwButton>
      <ZwButton variant="outline" disabled={disabled() || history().length === 0} onPress={() => list('previous')}>上一页 Incident</ZwButton>
      <ZwButton variant="outline" disabled={disabled() || !page()?.nextCursor} onPress={() => list('next')}>下一页 Incident</ZwButton>
      <ZwButton variant="outline" disabled={busy() || pending() !== null} onPress={resetFilters}>重置 Incident 筛选</ZwButton>
      <Show when={linkedIncident() && !detail()}><ZwButton variant="outline" disabled={disabled()} onPress={() => { const id = linkedIncident(); if (id) open(id) }}>读取选中 Incident</ZwButton></Show>
    </div>
    <p role="alert">{routeError() || error()}</p><p role="status">{message()}</p>
    <Show when={busy()}><p>正在请求…</p></Show>
    <Show when={page()}><p data-incident-page>{`${cursor() && history().length === 0 ? '恢复的游标页' : `第 ${history().length + 1} 页`} · 本页 ${page()?.items.length} 条 · ${page()?.storage}`}</p></Show>
    <Show when={page()?.items.length === 0}><p>当前筛选范围没有可见 Incident。</p></Show>
    <Show when={(page()?.items.length ?? 0) > 0}><div class="pipeline-table"><table>
      <thead><tr><th>标题</th><th>状态</th><th>严重度</th><th>版本</th><th>详情</th></tr></thead>
      <tbody><For each={page()?.items ?? []}>{row => <IncidentRow item={forItem(row)} disabled={disabled()} open={open} />}</For></tbody>
    </table></div></Show>
    <Show when={pending()}><div data-transition-pending>
      <p>{`状态提交结果待确认：${pending()?.body.target}，基于版本 ${pending()?.body.expectedVersion}。重试使用相同请求标识。`}</p>
      <ZwButton variant="outline" disabled={busy()} onPress={() => transition()}>重试同一状态请求</ZwButton>
      <ZwButton variant="outline" disabled={busy()} onPress={() => { const id = pending()?.id; if (id) open(id) }}>重新读取当前状态</ZwButton>
    </div></Show>
    <Show when={detail()}><IncidentDetails detail={detail() as IncidentDetail} disabled={disabled()} transition={transition} readAsset={readAsset} refresh={open} /></Show>
    <Show when={asset()}><section data-incident-asset><h3>{`关联资产：${asset()?.name}`}</h3>
      <p>{`${asset()?.id} · ${asset()?.lifecycle} · 版本 ${asset()?.version}`}</p>
      <p>{`${asset()?.attributes.sourceInstanceId} / ${asset()?.attributes.dataMode} / Host ${asset()?.attributes.hostId} / IP ${asset()?.attributes.ip}`}</p>
      <p>这是重新授权读取的当前资产详情，可能与告警发生时不同。</p>
    </section></Show>
  </section>
}
function IncidentRow(props: { item: IncidentHeader; disabled: boolean; open: (id: string) => void }) {
  return <tr><td>{props.item.title}</td><td>{props.item.status}</td><td>{props.item.severity}</td><td>{props.item.version}</td>
    <td><ZwButton variant="outline" disabled={props.disabled} onPress={() => props.open(props.item.id)}>查看 Incident</ZwButton></td></tr>
}
function IncidentDetails(props: { detail: IncidentDetail; disabled: boolean; transition: (target: IncidentStatus) => void; readAsset: (id: string) => void; refresh: (id: string) => void }) {
  const record = props.detail.record
  return <section data-incident-detail>
    <h3>{record.incident.title}</h3>
    <Show when={record.organization?.mergedInto}><p>{`此快照已合并至 ${record.organization?.mergedInto}，后续来源观测跟随目标 Incident。`}</p></Show>
    <p data-incident-state>{`${record.incident.status} · 版本 ${record.incident.version} · ${props.detail.storage}`}</p>
    <p><code>{record.incident.id}</code></p>
    <div class="actions">
      <For each={record.organization?.mergedInto ? [] : nextStatuses[record.incident.status]}>{row => <ZwButton variant="outline" disabled={props.disabled} onPress={() => props.transition(forItem(row))}>{`转为 ${forItem(row)}`}</ZwButton>}</For>
      <ZwButton variant="outline" disabled={props.disabled} onPress={() => props.refresh(record.incident.id)}>刷新当前详情</ZwButton>
      <Show when={!record.organization?.mergedInto}><a href={`#/incidents/current-diagnose?incidentId=${record.incident.id}`}>诊断当前 Incident</a></Show>
      <a href={`#/incidents/reorganize?incidentId=${record.incident.id}`}>人工合并、拆分与记录</a>
    </div>
    <p>处理状态属于人工记录；RESOLVED 要求已关联的全部告警都有恢复事实。</p>
    <h4>数据缺口</h4><ul data-incident-gaps><For each={record.gaps}>{row => <li>{gapLabels[forItem(row)] ?? forItem(row)}</li>}</For></ul>
    <h4>外部告警</h4><For each={record.problems}>{row => <ProblemCard problem={forItem(row)} disabled={props.disabled} readAsset={props.readAsset} />}</For>
    <ProblemHistory record={record} refresh={() => props.refresh(record.incident.id)} />
    <h4>时间线</h4><p>发生时间与本平台可见时间分别保留。证据关联不等于根因证明。</p>
    <ol class="incident-timeline"><For each={[...record.timeline].sort((a, b) => Date.parse(a.occurredAt) - Date.parse(b.occurredAt) || a.id.localeCompare(b.id))}>{row => <TimelineRow entry={forItem(row)} />}</For></ol>
  </section>
}
function ProblemCard(props: { problem: Problem; disabled: boolean; readAsset: (id: string) => void }) {
  const p = props.problem; const o = p.observation
  const from = Math.max(0, Math.floor(Date.parse(o.occurredAt) / 1000) - 1800)
  return <article class="incident-problem">
    <h5>{o.title}</h5><p>{`${o.state} · 严重度 ${o.severity} · ${o.suppressed ? '来源已抑制' : '来源未抑制'}`}</p>
    <p><strong>{p.dataMode}</strong>{` / ${o.sourceInstanceId} / ${p.sourceContract} / problem ${o.problemEventId} / trigger ${o.triggerId}`}</p>
    <p>{`发生 ${o.occurredAt} · 本次观测 ${o.observedAt}`}</p>
    <p>{`恢复事件 ${o.recoveryEventId ?? '无'} · 恢复时间 ${o.recoveredAt ?? '未知或未恢复'}`}</p>
    <p>{`首次入库 ${p.firstReceivedAt} · 最近入库 ${p.lastReceivedAt}`}</p>
    <Show when={p.entities.length === 0}><p>尚无可关联资产，请完成同一来源的 Host 同步后重新导入。</p></Show>
    <For each={p.entities}>{row => <div class="incident-link">
      <code>{`Host ${forItem(row).hostId} → ${forItem(row).entityId}`}</code>
      <ZwButton variant="outline" disabled={props.disabled} onPress={() => props.readAsset(forItem(row).entityId)}>查看关联资产</ZwButton>
      <a href={`#/metrics?entityId=${encodeURIComponent(forItem(row).entityId)}&from=${from}&till=${from + 3600}`}>查看发生前后 30 分钟指标</a>
    </div>}</For>
  </article>
}
function TimelineRow(props: { entry: TimelineEntry }) {
  const e = props.entry
  return <li><strong>{e.kind === 'STATUS_CHANGE' ? `${e.fromStatus} → ${e.toStatus}` : e.kind === 'PROBLEM' ? '外部问题发生' : '来源恢复'}</strong>
    <p>{`发生 ${e.occurredAt} · 可见 ${e.availableAt}`}</p>
    <p>{e.kind === 'STATUS_CHANGE' ? `操作人 ${e.actor}` : `${e.sourceInstanceId} / problem ${e.problemEventId}${e.recoveryEventId ? ` / recovery ${e.recoveryEventId}` : ''}`}</p>
  </li>
}
