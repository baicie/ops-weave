import { useEffect, useMemo, useRef, useState } from 'react'
import { Button } from '../../components/ui/button.tsx'
import { readRegisteredItemRun, readRegisteredItemRuns, syncRegisteredItems, type RegisteredItemRun, type RegisteredItemRunPage, type RegisteredItemRunScope } from '../../api/registered-item-sync.ts'
import type { ConnectionConfiguration } from '../../api/source-connections.ts'
import type { SourceInstance } from '../../api/source-instances.ts'

function statusLabel(run: RegisteredItemRun) {
  if (run.status === 'RUNNING') return '运行中'
  return run.status === 'SUCCEEDED' ? '已完成' : '失败'
}

function timeLabel(value: string | null) { return value ? new Date(value).toLocaleString() : '—' }

export function RegisteredItemSyncPanel(props: { active: boolean; instance: SourceInstance | null; connection: ConnectionConfiguration | null; ready: boolean }) {
  const canSync = props.instance?.state === 'ACTIVE'
  const scope = useMemo<RegisteredItemRunScope | null>(() => {
    const connection = props.connection, instance = props.instance
    if (!connection || !instance || connection.connectorVersion !== 'host-jsonrpc-v2' || !connection.hostGroupIds.length
      || connection.sourceId !== instance.id || connection.revision !== instance.configurationRevision
      || connection.connectionDigest !== instance.connectionDigest || instance.source.kind !== 'ZABBIX_HOST'
      || instance.source.instanceId !== 'connection-' + instance.id || instance.dataMode !== 'zabbix-jsonrpc') return null
    return { sourceId: connection.sourceId, revision: connection.revision, connectionDigest: connection.connectionDigest, hostGroupIds: connection.hostGroupIds }
  }, [props.connection?.sourceId, props.connection?.revision, props.connection?.connectionDigest, props.connection?.connectorVersion, props.connection?.hostGroupIds, props.instance?.id, props.instance?.configurationRevision, props.instance?.connectionDigest, props.instance?.source.kind, props.instance?.source.instanceId, props.instance?.dataMode, props.instance?.state])
  const scopeKey = scope ? `${scope.sourceId}:${scope.revision}:${scope.connectionDigest}:${scope.hostGroupIds.join(',')}` : 'unavailable'
  const [page, setPage] = useState<RegisteredItemRunPage | null>(null)
  const [detail, setDetail] = useState<RegisteredItemRun | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const controller = useRef<AbortController | null>(null)
  const disposed = useRef(false)

  useEffect(() => { disposed.current = false; return () => { disposed.current = true; controller.current?.abort() } }, [])

  async function fetchPage(signal: AbortSignal, after: string | null = null, append = false) {
    if (!scope) return
    const result = await readRegisteredItemRuns(scope, { limit: 20, after }, signal)
    if (signal.aborted || disposed.current) return
    setPage(previous => append && previous ? { ...result, items: [...previous.items, ...result.items] } : result)
  }

  useEffect(() => {
    controller.current?.abort(); controller.current = null
    setPage(null); setDetail(null); setError(''); setNotice('')
    if (!props.active || !props.ready || !scope) return
    const request = new AbortController(); controller.current = request; setBusy(true)
    void fetchPage(request.signal).catch(cause => { if (!request.signal.aborted && !disposed.current) setError(cause instanceof Error ? cause.message : '读取指标扫描历史失败') }).finally(() => { if (!request.signal.aborted && !disposed.current) setBusy(false) })
    return () => request.abort()
  }, [props.active, props.ready, scopeKey])

  function begin() {
    if (!scope || !canSync || !props.active || !props.ready || busy) return
    const request = new AbortController(); controller.current?.abort(); controller.current = request; setBusy(true); setError(''); setNotice('')
    void (async () => {
      const run = await syncRegisteredItems(scope, request.signal)
      if (request.signal.aborted || disposed.current) return
      setDetail(run)
      await fetchPage(request.signal)
      if (!request.signal.aborted && !disposed.current) setNotice('本次扫描已完成并记录回执。')
    })().catch(cause => {
      if (!request.signal.aborted && !disposed.current) setError((cause instanceof Error ? cause.message : '指标同步未确认') + ' 刷新扫描历史可核对服务端结果；不会自动重试。')
    }).finally(() => { if (!request.signal.aborted && !disposed.current) setBusy(false) })
  }

  function refresh() {
    if (!scope || !props.ready || busy) return
    const request = new AbortController(); controller.current?.abort(); controller.current = request; setBusy(true); setError(''); setNotice('')
    void fetchPage(request.signal).then(() => { if (!request.signal.aborted && !disposed.current) setNotice('扫描历史已刷新。') }).catch(cause => { if (!request.signal.aborted && !disposed.current) setError(cause instanceof Error ? cause.message : '读取指标扫描历史失败') }).finally(() => { if (!request.signal.aborted && !disposed.current) setBusy(false) })
  }

  function loadMore() {
    if (!scope || !page?.nextCursor || busy) return
    const request = new AbortController(); controller.current?.abort(); controller.current = request; setBusy(true); setError('')
    void fetchPage(request.signal, page.nextCursor, true).catch(cause => { if (!request.signal.aborted && !disposed.current) setError(cause instanceof Error ? cause.message : '读取更多扫描历史失败') }).finally(() => { if (!request.signal.aborted && !disposed.current) setBusy(false) })
  }

  function openDetail(id: string) {
    if (!scope || busy) return
    const request = new AbortController(); controller.current?.abort(); controller.current = request; setBusy(true); setError('')
    void readRegisteredItemRun(scope, id, request.signal).then(run => { if (!request.signal.aborted && !disposed.current) setDetail(run) }).catch(cause => { if (!request.signal.aborted && !disposed.current) setError(cause instanceof Error ? cause.message : '读取扫描详情失败') }).finally(() => { if (!request.signal.aborted && !disposed.current) setBusy(false) })
  }

  return <section className="registered-item-sync" aria-label="指标目录同步">
    <header className="registered-item-sync-heading"><div><h4>同步指标目录</h4><p>仅扫描当前保存版本中的主机组范围；每次点击都会启动一条新扫描。</p></div><div className="registered-item-sync-actions"><Button type="button" variant="outline" disabled={!scope || !props.ready || busy} onClick={refresh}>刷新历史</Button><Button type="button" disabled={!scope || !canSync || !props.ready || busy} onClick={begin}>{busy && !page ? '读取中…' : busy ? '处理中…' : '同步指标目录'}</Button></div></header>
    {scope ? <dl className="registered-item-sync-scope"><dt>来源 UUID</dt><dd><code>{scope.sourceId}</code></dd><dt>配置版本</dt><dd>v{scope.revision}</dd><dt>主机组范围</dt><dd>{scope.hostGroupIds.map(id => <code key={id}>{id}</code>)}</dd></dl> : <p className="registered-item-sync-empty">当前实例没有可用的固定连接版本或主机组范围。</p>}
    {scope && !canSync ? <p className="registered-item-sync-empty">实例已归档，只能查看历史扫描。</p> : null}
    {error ? <p role="alert">{error}</p> : null}{notice ? <p role="status">{notice}</p> : null}
    {scope && page ? <div className="integration-table-wrap registered-item-sync-table"><table className="integration-table" aria-label="指标扫描历史"><thead><tr><th scope="col">开始时间</th><th scope="col">状态</th><th scope="col">页数</th><th scope="col">接受</th><th scope="col">拒绝</th><th scope="col">退休</th><th scope="col">快照</th><th scope="col">详情</th></tr></thead><tbody>{page.items.map(run => <tr key={run.syncRunId}><td><time dateTime={run.startedAt}>{timeLabel(run.startedAt)}</time></td><td>{statusLabel(run)}</td><td>{run.pages}</td><td>{run.accepted}</td><td>{run.rejected}</td><td>{run.retired}</td><td>{run.snapshotComplete ? '完整' : '未完成'}</td><td><Button type="button" variant="outline" disabled={busy} onClick={() => openDetail(run.syncRunId)}>查看</Button></td></tr>)}</tbody></table>{!page.items.length ? <div className="integration-list-empty"><strong>{busy ? '正在读取扫描历史' : '暂无指标扫描记录'}</strong></div> : null}</div> : null}
    {scope && page?.hasMore ? <Button type="button" variant="outline" disabled={busy} onClick={loadMore}>读取更多扫描记录</Button> : null}
    {detail ? <section className="registered-item-sync-detail" aria-label="指标扫描详情"><header><h5>扫描详情</h5><Button type="button" variant="outline" disabled={busy} onClick={() => setDetail(null)}>关闭详情</Button></header><dl><dt>扫描 ID</dt><dd><code>{detail.syncRunId}</code></dd><dt>开始时间</dt><dd>{timeLabel(detail.startedAt)}</dd><dt>完成时间</dt><dd>{timeLabel(detail.completedAt)}</dd><dt>状态</dt><dd>{statusLabel(detail)}</dd><dt>读取对象</dt><dd>{detail.fetched}</dd><dt>扫描一致性</dt><dd>{detail.scanConsistency}</dd><dt>来源模式</dt><dd>{detail.dataMode}</dd><dt>游标</dt><dd><code>{detail.cursor ?? '—'}</code></dd>{detail.failureSummary ? <><dt>失败原因</dt><dd>{detail.failureSummary}</dd></> : null}</dl></section> : null}
  </section>
}
