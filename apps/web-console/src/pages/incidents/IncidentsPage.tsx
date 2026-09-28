import { useEffect, useRef, useState } from 'react'
import { usePlatformSession } from '../../state/platform-session.ts'
import { useViewLocation, useSelectionSessionReset } from '../../state/view-location.ts'
import { incidentDefault, incidentHash, incidentSelection, type IncidentSelection } from '../../state/view-selection.ts'
import { ProblemHistory } from './ProblemHistory.tsx'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { EntityRequestError, getEntity, type EntityItem } from '../../api/entities.ts'
import { INCIDENT_STATUSES, IncidentRequestError, getIncident, importProblems, listIncidents, transitionIncident,
  type ImportResult, type IncidentDetail, type IncidentHeader, type IncidentPage, type IncidentStatus, type Problem, type TimelineEntry, type TransitionRequest } from '../../api/incidents.ts'

const nextStatuses: Record<IncidentStatus, IncidentStatus[]> = {
  OPEN: ['INVESTIGATING'], INVESTIGATING: ['MITIGATED'], MITIGATED: ['RESOLVED', 'INVESTIGATING'], RESOLVED: ['CLOSED', 'INVESTIGATING'], CLOSED: [],
}
const gapLabels: Record<string, string> = { LOGS_NOT_CONNECTED: '日志尚未接入', CHANGES_NOT_CONNECTED: '变更尚未接入',
  ENTITY_MAPPING_MISSING: '部分 Host 尚未映射到资产', RECOVERY_EVENT_UNAVAILABLE: '恢复事件明细不可用' }

export function IncidentsPage() {
  const resetOnSession = useSelectionSessionReset()
  const [filter, setFilter] = useState('')
  const [page, setPage] = useState<IncidentPage | null>(null)
  const [cursor, setCursor] = useState<string | null>(null)
  const [history, setHistory] = useState<(string | null)[]>([])
  const [detail, setDetail] = useState<IncidentDetail | null>(null)
  const [asset, setAsset] = useState<EntityItem | null>(null)
  const [imported, setImported] = useState<ImportResult | null>(null)
  const [from, setFrom] = useState(new Date(Date.now() - 3600000).toISOString().replace(/\.\d{3}Z$/, 'Z'))
  const [till, setTill] = useState(new Date().toISOString().replace(/\.\d{3}Z$/, 'Z'))
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [message, setMessage] = useState('')
  const [pending, setPending] = useState<{ id: string; body: TransitionRequest } | null>(null)
  const [linkedIncident, setLinkedIncident] = useState<string | null>(null)
  const [routeError, setRouteError] = useState('')
  const controllerRef = useRef<AbortController | undefined>(undefined)
  const sequenceRef = useRef(0)
  const disposedRef = useRef(false)

  function clearData() {
    setPage(null); setCursor(null); setHistory([]); setDetail(null); setAsset(null); setImported(null); setPending(null); setMessage(''); setLinkedIncident(null)
  }
  function invalidate() {
    controllerRef.current?.abort(); ++sequenceRef.current; setBusy(false); setError(''); clearData()
  }
  function selected(): IncidentSelection {
    return { status: filter, after: cursor, incidentId: linkedIncident }
  }
  function applySelection(value: IncidentSelection) {
    invalidate(); setFilter(value.status); setCursor(value.after); setLinkedIncident(value.incidentId)
  }

  const authenticated = usePlatformSession(change => {
    const reset = resetOnSession.changed(change)
    const selection = reset ? incidentDefault() : selected()
    applySelection(selection)
    if (reset) { setRouteError(''); location.write(selection, true) }
    if (change.error) setError(change.error.message)
  })

  const location = useViewLocation('/incidents', incidentSelection, incidentHash,
    value => { setRouteError(''); applySelection(value) },
    cause => { applySelection(incidentDefault()); setRouteError(cause.message) })

  useEffect(() => () => {
    disposedRef.current = true
    controllerRef.current?.abort()
  }, [])

  function resetFilters() {
    applySelection(incidentDefault()); setRouteError(''); location.write(incidentDefault())
  }
  function changeWindow(value: string, field: 'from' | 'till') {
    controllerRef.current?.abort(); ++sequenceRef.current; setBusy(false); setError(''); setImported(null)
    if (field === 'from') setFrom(value); else setTill(value)
  }
  const disabled = busy || !authenticated || pending !== null || routeError !== ''

  async function run(action: (signal: AbortSignal, current: () => boolean) => Promise<void>) {
    resetOnSession.beginRead()
    controllerRef.current?.abort()
    const active = new AbortController()
    controllerRef.current = active
    const current = ++sequenceRef.current
    const isCurrent = () => !disposedRef.current && sequenceRef.current === current
    const timeout = window.setTimeout(() => active.abort(), 35000)
    setBusy(true); setError(''); setMessage('')
    try {
      if (routeError) throw new Error(routeError)
      await action(active.signal, isCurrent)
    } catch (cause) {
      if (!isCurrent()) return
      if ((cause instanceof IncidentRequestError || cause instanceof EntityRequestError) && [401, 403].includes(cause.status)) clearData()
      setError(cause instanceof DOMException && cause.name === 'AbortError' ? '请求已取消或超时' : cause instanceof Error ? cause.message : '请求失败')
    } finally {
      window.clearTimeout(timeout)
      if (isCurrent()) setBusy(false)
    }
  }

  function list(direction: 'first' | 'next' | 'previous') {
    const after = direction === 'next' ? page?.nextCursor ?? null : direction === 'previous' ? history.at(-1) ?? null : cursor
    const past = direction === 'next' ? [...history, cursor] : direction === 'previous' ? history.slice(0, -1) : history
    setPage(null); setDetail(null); setAsset(null)
    void run(async (signal, current) => {
      location.write({ ...selected(), after, incidentId: null }); setLinkedIncident(null)
      const result = await listIncidents(filter, after, signal)
      if (current()) { setPage(result); setCursor(after); setHistory(past) }
    })
  }

  function open(id: string) {
    setDetail(null); setAsset(null)
    void run(async (signal, current) => {
      setLinkedIncident(id); location.write({ ...selected(), incidentId: id })
      const result = await getIncident(id, signal)
      if (current()) { setDetail(result); setPending(null) }
    })
  }

  function importPage(next: boolean) {
    const afterEventId = next ? imported?.nextAfterEventId ?? null : null
    void run(async (signal, current) => {
      const start = Date.parse(from); const end = Date.parse(till)
      if (!/^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ$/.test(from) || !/^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ$/.test(till)
        || !Number.isFinite(start) || !Number.isFinite(end) || start < 0 || end < start || end - start > 86400000) throw new Error('请输入 UTC 秒级时间，窗口不超过 24 小时')
      const result = await importProblems({ from: start / 1000, till: end / 1000, afterEventId, limit: 100 }, signal)
      if (current()) { setImported(result); setPage(null); setDetail(null); setAsset(null); setCursor(null); setHistory([]); setMessage('本页告警已导入，请刷新 Incident 列表。') }
    })
  }

  function transition(target?: IncidentStatus) {
    const selectedIncident = detail?.record.incident
    const request = pending ?? (selectedIncident && target ? { id: selectedIncident.id, body: { expectedVersion: selectedIncident.version, target, requestKey: crypto.randomUUID() } } : null)
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
    void run(async (signal, current) => {
      const result = await getEntity(id, signal)
      if (current()) setAsset(result)
    })
  }

  return (
    <section className="panel incident-panel" data-page="incidents">
      <h2>Incident 列表</h2>
      <p>查看已导入的外部告警、恢复事实和人工处理状态。来源恢复不会自动关闭 Incident。</p>
      <p>地址保留已应用的状态、游标与选中 Incident；刷新或返回后请重新读取。导入和人工操作不会由地址触发。</p>
      <details className="incident-import">
        <summary>从已配置的 Zabbix 导入告警</summary>
        <p>每次最多 100 条、24 小时。来源与租户由服务端配置；导入失败不会按空快照处理。开发 fixture 的固定窗口可用于本地演示。</p>
        <div className="pipeline-form">
          <label>开始时间（UTC）<Input value={from} disabled={busy || pending !== null} onChange={e => changeWindow(e.currentTarget.value, 'from')} /></label>
          <label>结束时间（UTC）<Input value={till} disabled={busy || pending !== null} onChange={e => changeWindow(e.currentTarget.value, 'till')} /></label>
        </div>
        <div className="actions">
          <Button variant="outline" disabled={busy || pending !== null} onClick={() => { changeWindow('2026-09-21T12:00:00Z', 'from'); changeWindow('2026-09-21T13:00:00Z', 'till') }}>使用 fixture 时间窗口</Button>
          <Button variant="default" disabled={disabled} onClick={() => importPage(false)}>导入告警首批</Button>
          <Button variant="outline" disabled={disabled || !imported?.nextAfterEventId} onClick={() => importPage(true)}>导入下一批</Button>
        </div>
        {imported ? <p data-problem-import>{`${imported.dataMode} / ${imported.storage} / ${imported.sourceInstanceId} · 接收 ${imported.accepted} · 新建 ${imported.createdIncidents} · 更新 ${imported.changedIncidents} · 未映射 Host ${imported.unmappedHosts}`}</p> : null}
      </details>
      <div className="metric-controls">
        <label>Incident 状态
          <select value={filter} disabled={busy || pending !== null} onChange={e => { invalidate(); setFilter(e.target.value) }}>
            <option value="">全部状态</option>
            {INCIDENT_STATUSES.map(status => <option key={status} value={status}>{status}</option>)}
          </select>
        </label>
      </div>
      <div className="actions">
        <Button variant="default" disabled={disabled} onClick={() => list('first')}>刷新 Incident</Button>
        <Button variant="outline" disabled={disabled || history.length === 0} onClick={() => list('previous')}>上一页 Incident</Button>
        <Button variant="outline" disabled={disabled || !page?.nextCursor} onClick={() => list('next')}>下一页 Incident</Button>
        <Button variant="outline" disabled={busy || pending !== null} onClick={resetFilters}>重置 Incident 筛选</Button>
        {linkedIncident && !detail ? <Button variant="outline" disabled={disabled} onClick={() => { const id = linkedIncident; if (id) open(id) }}>读取选中 Incident</Button> : null}
      </div>
      <p role="alert">{routeError || error}</p>
      <p role="status">{message}</p>
      {busy ? <p>正在请求…</p> : null}
      {page ? <p data-incident-page>{`${cursor && history.length === 0 ? '恢复的游标页' : `第 ${history.length + 1} 页`} · 本页 ${page.items.length} 条 · ${page.storage}`}</p> : null}
      {page?.items.length === 0 ? <p>当前筛选范围没有可见 Incident。</p> : null}
      {(page?.items.length ?? 0) > 0 ? (
        <div className="pipeline-table">
          <table>
            <thead><tr><th>标题</th><th>状态</th><th>严重度</th><th>版本</th><th>详情</th></tr></thead>
            <tbody>{page!.items.map(item => <IncidentRow key={item.id} item={item} disabled={disabled} open={open} />)}</tbody>
          </table>
        </div>
      ) : null}
      {pending ? (
        <div data-transition-pending>
          <p>{`状态提交结果待确认：${pending.body.target}，基于版本 ${pending.body.expectedVersion}。重试使用相同请求标识。`}</p>
          <Button variant="outline" disabled={busy} onClick={() => transition()}>重试同一状态请求</Button>
          <Button variant="outline" disabled={busy} onClick={() => { const id = pending.id; if (id) open(id) }}>重新读取当前状态</Button>
        </div>
      ) : null}
      {detail ? <IncidentDetails detail={detail} disabled={disabled} transition={transition} readAsset={readAsset} refresh={open} /> : null}
      {asset ? (
        <section data-incident-asset>
          <h3>{`关联资产：${asset.name}`}</h3>
          <p>{`${asset.id} · ${asset.lifecycle} · 版本 ${asset.version}`}</p>
          <p>{`${asset.attributes.sourceInstanceId} / ${asset.attributes.dataMode} / Host ${asset.attributes.hostId} / IP ${asset.attributes.ip}`}</p>
          <p>这是重新授权读取的当前资产详情，可能与告警发生时不同。</p>
        </section>
      ) : null}
    </section>
  )
}

function IncidentRow(props: { item: IncidentHeader; disabled: boolean; open: (id: string) => void }) {
  return (
    <tr>
      <td>{props.item.title}</td>
      <td>{props.item.status}</td>
      <td>{props.item.severity}</td>
      <td>{props.item.version}</td>
      <td><Button variant="outline" disabled={props.disabled} onClick={() => props.open(props.item.id)}>查看 Incident</Button></td>
    </tr>
  )
}

function IncidentDetails(props: {
  detail: IncidentDetail
  disabled: boolean
  transition: (target: IncidentStatus) => void
  readAsset: (id: string) => void
  refresh: (id: string) => void
}) {
  const record = props.detail.record
  const timeline = [...record.timeline].sort((a, b) => Date.parse(a.occurredAt) - Date.parse(b.occurredAt) || a.id.localeCompare(b.id))
  return (
    <section data-incident-detail>
      <h3>{record.incident.title}</h3>
      {record.organization?.mergedInto ? <p>{`此快照已合并至 ${record.organization?.mergedInto}，后续来源观测跟随目标 Incident。`}</p> : null}
      <p data-incident-state>{`${record.incident.status} · 版本 ${record.incident.version} · ${props.detail.storage}`}</p>
      <p><code>{record.incident.id}</code></p>
      <div className="actions">
        {(record.organization?.mergedInto ? [] : nextStatuses[record.incident.status]).map(status => (
          <Button key={status} variant="outline" disabled={props.disabled} onClick={() => props.transition(status)}>{`转为 ${status}`}</Button>
        ))}
        <Button variant="outline" disabled={props.disabled} onClick={() => props.refresh(record.incident.id)}>刷新当前详情</Button>
        {!record.organization?.mergedInto ? <a href={`#/incidents/current-diagnose?incidentId=${record.incident.id}`}>诊断当前 Incident</a> : null}
        <a href={`#/incidents/reorganize?incidentId=${record.incident.id}`}>人工合并、拆分与记录</a>
      </div>
      <p>处理状态属于人工记录；RESOLVED 要求已关联的全部告警都有恢复事实。</p>
      <h4>数据缺口</h4>
      <ul data-incident-gaps>{record.gaps.map(gap => <li key={gap}>{gapLabels[gap] ?? gap}</li>)}</ul>
      <h4>外部告警</h4>
      {record.problems.map(problem => (
        <ProblemCard key={`${problem.observation.sourceInstanceId}:${problem.observation.problemEventId}`} problem={problem} disabled={props.disabled} readAsset={props.readAsset} />
      ))}
      <ProblemHistory record={record} refresh={() => props.refresh(record.incident.id)} />
      <h4>时间线</h4>
      <p>发生时间与本平台可见时间分别保留。证据关联不等于根因证明。</p>
      <ol className="incident-timeline">{timeline.map(entry => <TimelineRow key={entry.id} entry={entry} />)}</ol>
    </section>
  )
}

function ProblemCard(props: { problem: Problem; disabled: boolean; readAsset: (id: string) => void }) {
  const p = props.problem
  const o = p.observation
  const from = Math.max(0, Math.floor(Date.parse(o.occurredAt) / 1000) - 1800)
  return (
    <article className="incident-problem">
      <h5>{o.title}</h5>
      <p>{`${o.state} · 严重度 ${o.severity} · ${o.suppressed ? '来源已抑制' : '来源未抑制'}`}</p>
      <p><strong>{p.dataMode}</strong>{` / ${o.sourceInstanceId} / ${p.sourceContract} / problem ${o.problemEventId} / trigger ${o.triggerId}`}</p>
      <p>{`发生 ${o.occurredAt} · 本次观测 ${o.observedAt}`}</p>
      <p>{`恢复事件 ${o.recoveryEventId ?? '无'} · 恢复时间 ${o.recoveredAt ?? '未知或未恢复'}`}</p>
      <p>{`首次入库 ${p.firstReceivedAt} · 最近入库 ${p.lastReceivedAt}`}</p>
      {p.entities.length === 0 ? <p>尚无可关联资产，请完成同一来源的 Host 同步后重新导入。</p> : null}
      {p.entities.map(entity => (
        <div key={`${entity.hostId}-${entity.entityId}`} className="incident-link">
          <code>{`Host ${entity.hostId} → ${entity.entityId}`}</code>
          <Button variant="outline" disabled={props.disabled} onClick={() => props.readAsset(entity.entityId)}>查看关联资产</Button>
          <a href={`#/metrics?entityId=${encodeURIComponent(entity.entityId)}&from=${from}&till=${from + 3600}`}>查看发生前后 30 分钟指标</a>
        </div>
      ))}
    </article>
  )
}

function TimelineRow(props: { entry: TimelineEntry }) {
  const e = props.entry
  return (
    <li>
      <strong>{e.kind === 'STATUS_CHANGE' ? `${e.fromStatus} → ${e.toStatus}` : e.kind === 'PROBLEM' ? '外部问题发生' : '来源恢复'}</strong>
      <p>{`发生 ${e.occurredAt} · 可见 ${e.availableAt}`}</p>
      <p>{e.kind === 'STATUS_CHANGE' ? `操作人 ${e.actor}` : `${e.sourceInstanceId} / problem ${e.problemEventId}${e.recoveryEventId ? ` / recovery ${e.recoveryEventId}` : ''}`}</p>
    </li>
  )
}
