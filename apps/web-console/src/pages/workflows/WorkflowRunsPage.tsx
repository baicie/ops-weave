import { useEffect, useRef, useState } from 'react'
import { oidcMode, usePlatformSession } from '../../state/platform-session.ts'
import { readWorkspace, type Run, type Workspace } from '../../api/workflows.ts'
import { readWorkflowRun, nodeLabels, issueLabels, runOutcome, outcomeLabels, type RunDetail, type TraceRow, type TraceStep } from '../../api/workflow-runs.ts'
const rowLabels = { ACCEPTED: '成功', REJECTED: '失败', FILTERED: '过滤' }
const stepLabels = { OK: '通过', ERROR: '失败', FILTERED: '已过滤', SKIPPED: '未执行' }
export function WorkflowRunsPage() {
  const [page, setPage] = useState<Workspace | null>(null)
  const [detail, setDetail] = useState<RunDetail | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [filter, setFilter] = useState('ALL')
  const [rowFilter, setRowFilter] = useState('ALL')
  const [search, setSearch] = useState('')
  const [runId, setRunId] = useState('')
  const activeRef = useRef<AbortController | undefined>(undefined)
  const disposedRef = useRef(false)
  const busyRef = useRef(false)
  const ready = usePlatformSession(change => {
    activeRef.current?.abort(); activeRef.current = undefined; busyRef.current = false; setBusy(false); setPage(null); setDetail(null); setRunId(''); setSearch(''); setFilter('ALL'); setRowFilter('ALL'); setError(change.error?.message ?? '')
  })
  const readyRef = useRef(ready)
  const loadRef = useRef<() => void>(() => {})
  readyRef.current = ready
  function markBusy(value: boolean) { busyRef.current = value; setBusy(value) }

  async function request(work: (signal: AbortSignal, current: () => boolean) => Promise<void>) {
    if (busyRef.current) return
    if (!readyRef.current) { setError(oidcMode ? '请先登录平台' : '请先填写页面顶部的平台开发 Token'); return }
    const c = new AbortController(); activeRef.current = c; markBusy(true); setError('')
    const current = () => !disposedRef.current && activeRef.current === c && !c.signal.aborted
    try { await work(c.signal, current) }
    catch (e) { if (current()) setError(e instanceof Error ? e.message : '运行记录读取失败') }
    finally { if (current()) markBusy(false) }
  }
  function linkedId() {
    const q = new URLSearchParams(location.hash.split('?')[1] ?? '')
    if ([...q.keys()].some(k => k !== 'runId') || q.getAll('runId').length > 1) throw new Error('运行记录链接参数无效')
    return q.get('runId') ?? ''
  }
  function load() {
    void request(async (s, current) => {
      setDetail(null)
      const id = linkedId()
      const p = await readWorkspace(s)
      const d = id ? await readWorkflowRun(id, s) : null
      if (current()) { setPage(p); setDetail(d); setRunId(id); setRowFilter('ALL') }
    })
  }
  function inspect(id: string) {
    void request(async (s, current) => {
      setDetail(null)
      const d = await readWorkflowRun(id.trim(), s)
      if (current()) {
        setDetail(d); setRunId(id.trim()); setRowFilter('ALL')
        queueMicrotask(() => document.querySelector('[data-run-detail]')?.scrollIntoView({ block: 'start' }))
      }
    })
  }
  loadRef.current = load

  useEffect(() => {
    function navigate() {
      if (location.hash.split('?')[0] !== '#/integrations/workflows/runs') return
      activeRef.current?.abort(); activeRef.current = undefined; markBusy(false); setDetail(null)
      if (readyRef.current) loadRef.current()
    }
    window.addEventListener('hashchange', navigate)
    queueMicrotask(() => { if (!disposedRef.current && readyRef.current) loadRef.current() })
    return () => { disposedRef.current = true; activeRef.current?.abort(); window.removeEventListener('hashchange', navigate) }
  }, [])

  const runs = page?.runs.items ?? []
  const visible = runs.filter(r => (filter === 'ALL' || runOutcome(r) === filter) && [r.workflowId, r.receipt.id].some(v => v.includes(search.trim())))
  const counts = runs.reduce((a, r) => ({ accepted: a.accepted + r.receipt.accepted, rejected: a.rejected + r.receipt.rejected, filtered: a.filtered + r.receipt.filtered }), { accepted: 0, rejected: 0, filtered: 0 })
  const rows = detail?.trace?.rows.filter(r => rowFilter === 'ALL' || r.status === rowFilter) ?? []
  return <section className="workflow-runs" data-page="workflow-runs">
    <header className="workflow-heading"><div><div className="model-eyebrow">EXECUTION HISTORY · 数据接入</div><h2>工作流运行记录</h2><p>每次转换都有回执，每条结果都能追溯。</p></div><button type="button" disabled={busy} onClick={load}>读取运行记录</button></header>
    <div className="workflow-boundary"><a href="#/integrations/workflows">← 返回数据工作流</a><span className="lifecycle-pill">只读测试运行</span><span>成功表示转换通过，尚未写入实体。</span></div>
    {!ready ? <p className="source-access-hint">{oidcMode ? '请先登录平台，再查询本人运行记录。' : '请先填写平台开发 Token，再查询本人运行记录。'}</p> : null}
    <p role="alert">{error}</p><p role="status">{busy ? '正在读取…' : ''}</p>
    <div className="run-stat-grid"><div><span>最近运行</span><strong>{page ? runs.length : '—'}</strong><small>当前列表</small></div><div><span>转换成功</span><strong>{page ? counts.accepted : '—'}</strong><small>条记录</small></div><div><span>转换失败</span><strong>{page ? counts.rejected : '—'}</strong><small>条记录</small></div><div><span>规则过滤</span><strong>{page ? counts.filtered : '—'}</strong><small>条记录 · 非失败</small></div></div>
    <div className="run-filters"><label>转换结果<select aria-label="筛选运行结果" value={filter} onChange={e => setFilter((e.target as HTMLSelectElement).value)}><option value="ALL">全部结果</option><option value="SUCCEEDED">全部通过</option><option value="PARTIAL">部分失败</option><option value="FAILED">全部失败</option><option value="FILTERED">全部过滤</option></select></label><label>筛选当前列表<input aria-label="筛选工作流或运行 ID" placeholder="工作流 ID / 运行 ID" value={search} onInput={e => setSearch((e.target as HTMLInputElement).value)} /></label></div>
    <div className="run-table-wrap"><table className="run-table"><caption>本人最近20次工作流预览与版本测试</caption><thead><tr><th>工作流 / 运行 ID</th><th>时间与来源</th><th>转换结果</th><th>成功 / 失败 / 过滤</th><th>操作</th></tr></thead><tbody>{visible.map(run => <HistoryRow key={run.receipt.id} run={run} busy={busy} open={inspect} />)}</tbody></table>{!visible.length ? <p className="run-empty">{page ? '没有符合条件的运行记录。' : '读取后查看已保存的运行记录。'}</p> : null}</div>
    {page?.runs.truncated ? <p>仅展示最近20次；筛选只作用于当前列表。更早的记录可按运行 ID 查询。</p> : null}
    <div className="run-lookup"><label>按运行 ID 回查<input aria-label="查询运行 ID" placeholder="输入完整 UUID" maxLength={36} value={runId} onInput={e => setRunId((e.target as HTMLInputElement).value)} /></label><button type="button" disabled={busy || !runId.trim()} onClick={() => inspect(runId)}>查询详情</button></div>
    {detail ? <section className="run-detail" data-run-detail>
      <header><div><div className="model-eyebrow">RUN DETAIL · 运行明细</div><h3>{detail.run.workflowId + ' · v' + detail.run.revision}</h3><code>{detail.run.receipt.id}</code></div><span className="run-badge" data-outcome={runOutcome(detail.run)}>{outcomeLabels[runOutcome(detail.run)]}</span></header>
      <p>{(detail.run.mode === 'PREVIEW' ? '草稿预览' : '版本测试') + ' · ' + detail.run.receipt.origin + ' · ' + detail.run.receipt.createdAt}</p>
      <div className="workflow-result-counts"><span>{'成功 ' + detail.run.receipt.accepted}</span><span>{'失败 ' + detail.run.receipt.rejected}</span><span>{'过滤 ' + detail.run.receipt.filtered}</span></div>
      {!detail.trace ? <p className="source-access-hint">这是一条旧版计数回执，未保存逐条明细。历史错误原因无法补回；重新测试会产生新的运行记录。</p> : null}
      {detail.trace ? <>
        <dl className="run-meta"><div><dt>来源实例</dt><dd>{detail.trace.source.instanceId}</dd></div><div><dt>目标模型</dt><dd>{detail.trace.target.id + ' @ ' + detail.trace.target.revision}</dd></div><div><dt>执行耗时</dt><dd>{detail.trace.durationMillis + ' ms'}</dd></div><div><dt>来源批次</dt><dd>{detail.trace.syncRunId ?? '手工样本'}</dd></div></dl>
        <p>{'来源状态 ' + detail.trace.sourceStatus + ' · 保留 ' + detail.trace.retainedCount + ' · 缺失 Raw ' + detail.trace.missingRaw}</p>
        {detail.trace.truncated || detail.trace.missingRaw || detail.trace.sourceStatus === 'FAILED' ? <p className="source-access-hint">本次输入存在截断、缺失或上游失败。以下结果仅覆盖实际参与转换的记录，不代表整个来源批次成功。</p> : null}
        <h4>节点统计</h4><div className="run-node-stats">{(detail.trace.rows[0]?.steps ?? []).map(step => <NodeStat key={step.nodeId} step={step} rows={detail.trace!.rows} />)}</div>
        <div className="run-record-heading"><h4>逐条处理记录</h4><label>记录结果<select aria-label="筛选记录结果" value={rowFilter} onChange={e => setRowFilter((e.target as HTMLSelectElement).value)}><option value="ALL">全部记录</option><option value="ACCEPTED">仅成功</option><option value="REJECTED">仅失败</option><option value="FILTERED">仅过滤</option></select></label></div>
        <p className="model-muted">编号对应当次输入顺序。仅保留节点状态、字段与错误码，未保存输入和输出正文。</p>
        {rows.map(row => <RecordRow key={row.index} row={row} />)}{!rows.length ? <p>没有符合条件的处理记录。</p> : null}
      </> : null}
    </section> : null}
  </section>
}
function HistoryRow(p: { run: Run; busy: boolean; open: (id: string) => void }) {
  return <tr><td><strong>{p.run.workflowId + ' · v' + p.run.revision}</strong><small>{p.run.receipt.id}</small></td><td><span>{new Date(p.run.receipt.createdAt).toLocaleString()}</span><small>{(p.run.mode === 'PREVIEW' ? '草稿预览' : '版本测试') + ' · ' + p.run.receipt.origin}</small></td><td><span className="run-badge" data-outcome={runOutcome(p.run)}>{outcomeLabels[runOutcome(p.run)]}</span></td><td>{p.run.receipt.accepted + ' / ' + p.run.receipt.rejected + ' / ' + p.run.receipt.filtered}</td><td><button type="button" disabled={p.busy} onClick={() => p.open(p.run.receipt.id)}>查看明细</button></td></tr>
}
function RecordRow(p: { row: TraceRow }) {
  const terminal = p.row.steps.find(s => s.status === 'ERROR' || s.status === 'FILTERED')
  return <details className="run-record" open={p.row.status === 'REJECTED'}><summary><strong>{'记录 ' + (p.row.index + 1)}</strong><span className="run-badge" data-outcome={p.row.status}>{rowLabels[p.row.status]}</span><span>{terminal ? nodeLabels[terminal.type] + ' · ' + terminal.nodeId : '所有节点通过'}</span></summary><ol>{p.row.steps.map(step => <StepRow key={step.nodeId} step={step} />)}</ol></details>
}
function NodeStat(p: { step: TraceStep; rows: TraceRow[] }) {
  return <div><strong>{nodeLabels[p.step.type]}</strong><small>{p.step.nodeId}</small><span>{(['OK', 'ERROR', 'FILTERED', 'SKIPPED'] as const).map(status => stepLabels[status] + ' ' + p.rows.filter(r => r.steps.find(s => s.nodeId === p.step.nodeId)?.status === status).length).join(' · ')}</span></div>
}
function StepRow(p: { step: TraceStep }) {
  return <li data-step-status={p.step.status}><div><strong>{nodeLabels[p.step.type] + ' · ' + p.step.nodeId}</strong><span>{stepLabels[p.step.status]}</span></div>{p.step.status === 'SKIPPED' ? <p>此前记录已失败或被过滤，此节点未执行。</p> : null}{p.step.status === 'FILTERED' ? <p>不满足配置的保留条件，后续节点不再处理。</p> : null}{p.step.issues.map((issue, i) => <p key={i} className="run-issue">{issue.field + '：' + issueLabels[issue.code] + '（' + issue.code + '）'}</p>)}</li>
}
