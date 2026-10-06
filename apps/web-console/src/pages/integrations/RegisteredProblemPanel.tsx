import { useEffect, useMemo, useRef, useState } from 'react'
import { Button } from '../../components/ui/button.tsx'
import { readRegisteredProblems, type RegisteredProblemPage, type RegisteredProblemQuery, type RegisteredProblemScope } from '../../api/registered-problems.ts'
import type { ConnectionConfiguration } from '../../api/source-connections.ts'
import type { SourceInstance } from '../../api/source-instances.ts'

function timeLabel(value: string) { return new Date(value).toLocaleString() }
function stateLabel(value: RegisteredProblemPage['items'][number]['state']) { return value === 'ACTIVE' ? '活动' : value === 'RECOVERED' ? '已恢复' : '恢复未知' }
function localInput(epochSeconds: number) {
  const date = new Date(epochSeconds * 1000 - new Date(epochSeconds * 1000).getTimezoneOffset() * 60000)
  return date.toISOString().slice(0, 16)
}
function epochSeconds(value: string) {
  const parsed = Date.parse(value)
  if (!value || !Number.isFinite(parsed)) return null
  return Math.floor(parsed / 1000)
}
function scopeFor(instance: SourceInstance | null, connection: ConnectionConfiguration | null): RegisteredProblemScope | null {
  if (!instance || !connection || instance.state !== 'ACTIVE' || instance.source.kind !== 'ZABBIX_HOST' || !instance.source.instanceId
    || instance.dataMode !== 'zabbix-jsonrpc' || connection.connectorVersion !== 'host-jsonrpc-v2' || connection.sourceId !== instance.id
    || connection.revision !== instance.configurationRevision || connection.connectionDigest !== instance.connectionDigest || !connection.hostGroupIds.length) return null
  return { sourceId: connection.sourceId, sourceInstanceId: instance.source.instanceId, revision: connection.revision, connectionDigest: connection.connectionDigest, hostGroupIds: connection.hostGroupIds }
}

export function RegisteredProblemPanel(props: { active: boolean; instance: SourceInstance | null; connection: ConnectionConfiguration | null; ready: boolean }) {
  const scope = useMemo(() => scopeFor(props.instance, props.connection), [props.instance?.id, props.instance?.state, props.instance?.source.kind, props.instance?.source.instanceId, props.instance?.dataMode, props.instance?.configurationRevision, props.instance?.connectionDigest, props.connection?.sourceId, props.connection?.revision, props.connection?.connectionDigest, props.connection?.connectorVersion, props.connection?.hostGroupIds])
  const scopeKey = scope ? `${scope.sourceId}:${scope.sourceInstanceId}:${scope.revision}:${scope.connectionDigest}:${scope.hostGroupIds.join(',')}` : 'unavailable'
  const [page, setPage] = useState<RegisteredProblemPage | null>(null)
  const [query, setQuery] = useState<RegisteredProblemQuery | null>(null)
  const [fromLocal, setFromLocal] = useState(() => localInput(Math.floor(Date.now() / 1000) - 3600))
  const [tillLocal, setTillLocal] = useState(() => localInput(Math.floor(Date.now() / 1000)))
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const controller = useRef<AbortController | null>(null)
  const disposed = useRef(false)

  useEffect(() => { disposed.current = false; return () => { disposed.current = true; controller.current?.abort() } }, [])

  function selectedQuery(afterEventId: string | null = null): RegisteredProblemQuery | null {
    const from = epochSeconds(fromLocal), till = epochSeconds(tillLocal)
    if (from === null || till === null || from < 0 || till < from || till > Math.floor(Date.now() / 1000) || till - from > 86400) {
      setError('请选择不晚于当前时间、跨度不超过 24 小时的有效时间窗。')
      return null
    }
    return { from, till, afterEventId, limit: 25 }
  }

  async function read(fixedQuery?: RegisteredProblemQuery) {
    if (!scope || !props.ready || !props.active || busy) return
    const nextQuery = fixedQuery ?? selectedQuery()
    if (!nextQuery) return
    const request = new AbortController(); controller.current?.abort(); controller.current = request; setBusy(true); setError('')
    try {
      const result = await readRegisteredProblems(scope, nextQuery, request.signal)
      if (!request.signal.aborted && !disposed.current) { setQuery(nextQuery); setPage(result) }
    } catch (cause) {
      if (!request.signal.aborted && !disposed.current) setError(cause instanceof Error ? cause.message : '读取登记连接问题失败；不会自动重试。')
    } finally { if (!request.signal.aborted && !disposed.current) setBusy(false) }
  }

  useEffect(() => {
    controller.current?.abort(); controller.current = null; setPage(null); setQuery(null); setError('')
    if (!props.active || !props.ready || !scope) return
    const till = Math.floor(Date.now() / 1000)
    const from = Math.max(0, till - 3600)
    setFromLocal(localInput(from)); setTillLocal(localInput(till))
    void read({ from, till, afterEventId: null, limit: 25 })
    return () => controller.current?.abort()
  }, [props.active, props.ready, scopeKey])

  function refresh() { if (!scope || busy) return; const nextQuery = selectedQuery(); if (nextQuery) void read(nextQuery) }
  function nextPage() {
    if (!scope || !page?.nextAfterEventId || !query || busy) return
    void read({ ...query, afterEventId: page.nextAfterEventId })
  }

  return <section className="registered-problem-panel" aria-label="登记连接问题">
    <header className="registered-problem-heading"><div><h4>登记连接问题</h4><p>只读读取固定连接版本的问题页，不创建 Incident、通知或工作流动作。</p></div><Button type="button" variant="outline" disabled={!scope || !props.ready || busy} onClick={refresh}>{busy ? '读取中…' : '按时间窗刷新'}</Button></header>
    <div className="registered-problem-window"><label>开始时间<input type="datetime-local" step="1" value={fromLocal} disabled={busy} onChange={event => setFromLocal(event.target.value)}/></label><label>结束时间<input type="datetime-local" step="1" value={tillLocal} disabled={busy} onChange={event => setTillLocal(event.target.value)}/></label><span>按本机时区录入；查询使用 UTC 秒，最多 24 小时。</span></div>
    {scope ? <dl className="registered-problem-scope"><dt>来源 UUID</dt><dd><code>{scope.sourceId}</code></dd><dt>配置版本</dt><dd>v{scope.revision}</dd><dt>主机组范围</dt><dd>{scope.hostGroupIds.map(id => <code key={id}>{id}</code>)}</dd><dt>连接摘要</dt><dd><code>{scope.connectionDigest}</code></dd>{page ? <><dt>范围摘要</dt><dd><code>{page.scopeDigest}</code></dd><dt>固定时间窗</dt><dd>{page.query.from} – {page.query.till} UTC · 每页 {page.query.limit} 条</dd></> : null}</dl> : <p className="registered-problem-empty">当前实例没有可用的固定连接版本或主机组范围。</p>}
    {error ? <p role="alert">{error}</p> : null}
    {page ? <>
      <div className="integration-table-wrap registered-problem-table"><table className="integration-table" aria-label="登记连接问题列表"><thead><tr><th scope="col">事件 ID</th><th scope="col">问题</th><th scope="col">严重度</th><th scope="col">状态</th><th scope="col">发生时间</th><th scope="col">主机</th></tr></thead><tbody>{page.items.map(item => <tr key={item.problemEventId}><td><code>{item.problemEventId}</code></td><td>{item.title}</td><td>{item.severity}</td><td>{stateLabel(item.state)}{item.gaps.length ? ' · 数据缺口' : ''}</td><td><time dateTime={item.occurredAt}>{timeLabel(item.occurredAt)}</time></td><td>{item.hostIds.length ? item.hostIds.join(', ') : '—'}</td></tr>)}</tbody></table>{!page.items.length ? <div className="integration-list-empty"><strong>当前时间窗没有登记问题</strong><p>空页不代表完整来源快照。</p></div> : null}</div>
      <nav className="registered-problem-pagination" aria-label="登记连接问题分页"><span aria-live="polite">本页 {page.items.length} 条{page.query.afterEventId ? ` · 游标 ${page.query.afterEventId}` : ''} · 时间窗保持不变</span><Button type="button" variant="outline" disabled={busy || !page.nextAfterEventId} onClick={nextPage}>下一页问题</Button></nav>
    </> : null}
  </section>
}
