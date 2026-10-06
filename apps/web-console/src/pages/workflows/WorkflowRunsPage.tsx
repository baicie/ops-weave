import {WorkflowHistorySection} from './WorkflowHistorySection.tsx'
import {historyEligible} from '../../api/workflow-history.ts'
import { outputKind, outputLabel } from '../../api/workflow-output.ts'
import { usePageActive, usePageCloseGuard } from '../../state/page-workspace.ts'
import { PageHeader, QueryToolbar, SummaryGrid, SummaryCard } from '../../components/PageLayout.tsx'
import { Button } from '@/components/ui/button'
import { useEffect, useRef, useState } from 'react'
import { localPreviewMode, oidcMode, usePlatformSession } from '../../state/platform-session.ts'
import { readWorkspace, readWorkflow, type Entry, type Run, type Workspace } from '../../api/workflows.ts'
import { readWorkflowRun, nodeLabels, issueLabels, runOutcome, outcomeLabels, type RunDetail, type TraceRow, type TraceStep } from '../../api/workflow-runs.ts'
const rowLabels = { ACCEPTED: '成功', REJECTED: '失败', FILTERED: '过滤' }
const stepLabels = { OK: '通过', ERROR: '失败', FILTERED: '已过滤', SKIPPED: '未执行' }
export function WorkflowRunsPage() {
  const pageActive = usePageActive()
  const pageActiveRef = useRef(pageActive)
  pageActiveRef.current = pageActive
  const lastReadHash = useRef('')
  const [page, setPage] = useState<Workspace | null>(null)
  const [view,setView]=useState<'HISTORY'|'PREVIEW'>('HISTORY')
  const [historyBusy,setHistoryBusy]=useState(false)
  const [linkedKey,setLinkedKey]=useState<string|null>(null)
  const [linkedEntry,setLinkedEntry]=useState<Entry|null>(null)
  const [sessionGeneration,setSessionGeneration]=useState(0)
  const attempted=useRef(false)
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
    activeRef.current?.abort(); activeRef.current = undefined; busyRef.current = false; attempted.current=change.reason!=='credentials'; if(change.reason==='credentials')setSessionGeneration(value=>value+1); setBusy(false); setHistoryBusy(false); setLinkedKey(null); setLinkedEntry(null); setView('HISTORY'); setPage(null); setDetail(null); setRunId(''); setSearch(''); setFilter('ALL'); setRowFilter('ALL'); setError(change.error?.message ?? '')
  })
  const readyRef = useRef(ready)
  const loadRef = useRef<() => void>(() => {})
  readyRef.current = ready
  function markBusy(value: boolean) { busyRef.current = value; setBusy(value) }
  usePageCloseGuard(busy || historyBusy ? { message: '正在读取运行记录，请等待完成后关闭。', blocked: true } : null)

  async function request(work: (signal: AbortSignal, current: () => boolean) => Promise<void>) {
    if (busyRef.current) return
    if (!readyRef.current) { setError(localPreviewMode ? '本地会话尚未就绪，请使用页面上方的重新连接按钮。' : oidcMode ? '请先登录平台' : '请先填写页面顶部的平台开发 Token'); return }
    const c = new AbortController(); activeRef.current = c; markBusy(true); setError('')
    const current = () => !disposedRef.current && activeRef.current === c && !c.signal.aborted
    try { await work(c.signal, current) }
    catch (e) { if (current()) setError(e instanceof Error ? e.message : '运行记录读取失败') }
    finally { if (current()) markBusy(false) }
  }
  function linkedId() {
    const q = new URLSearchParams(location.hash.split('?')[1] ?? '')
    if ([...q.keys()].some(k => !['runId','workflowId','revision'].includes(k)) || ['runId','workflowId','revision'].some(k=>q.getAll(k).length>1) || q.has('runId')&&(q.has('workflowId')||q.has('revision')) || q.has('workflowId')!==q.has('revision') || q.has('workflowId')&&(!/^[a-z][a-z0-9_-]{0,47}$/.test(q.get('workflowId')!)||!/^([1-9][0-9]{0,3}|10000)$/.test(q.get('revision')!))) throw new Error('运行记录链接参数无效')
    return q.get('runId') ?? ''
  }
  function load() {
    const hash = location.hash
    void request(async (s, current) => {
      setDetail(null)
      const id = linkedId()
      const query=new URLSearchParams(hash.split('?')[1]??'')
      const selection=query.has('workflowId')?query.get('workflowId')+'@'+query.get('revision'):null
      const p = await readWorkspace(s)
      let fixed:Entry|null=null,linkError=''
      if(selection&&!p.published.items.some(e=>e.definition.id+'@'+e.definition.revision===selection)){
        try{fixed=await readWorkflow(query.get('workflowId')!,Number(query.get('revision')),'PUBLISHED',s)}
        catch(failure){if(!current())throw failure;linkError=failure instanceof Error?failure.message:'固定发布版本读取失败'}
      }
      const d = id ? await readWorkflowRun(id, s) : null
      if (current()) { lastReadHash.current = hash; setLinkedKey(selection); setLinkedEntry(fixed); setError(linkError); if(id)setView('PREVIEW'); setPage(p); setDetail(d); setRunId(id); setRowFilter('ALL') }
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
      if (!pageActiveRef.current || location.hash.split('?')[0] !== '#/integrations/workflows/runs' || location.hash === lastReadHash.current) return
      activeRef.current?.abort(); activeRef.current = undefined; markBusy(false); setDetail(null)
      if (readyRef.current) loadRef.current()
    }
    window.addEventListener('hashchange', navigate)
    disposedRef.current=false
    return () => { disposedRef.current = true; activeRef.current?.abort(); window.removeEventListener('hashchange', navigate) }
  }, [])
  useEffect(()=>{if(pageActive&&ready&&!attempted.current){attempted.current=true;loadRef.current()}},[pageActive,ready,sessionGeneration])
  useEffect(()=>{if(!pageActive&&busyRef.current){activeRef.current?.abort();activeRef.current=undefined;markBusy(false);setError('读取已取消，请刷新运行记录。')}},[pageActive])
  const wasActive = useRef(pageActive)
  useEffect(() => {
    const returning = pageActive && !wasActive.current
    wasActive.current = pageActive
    if (returning && ready && page && lastReadHash.current !== location.hash) loadRef.current()
  }, [pageActive, ready])

  const runs = page?.runs.items ?? []
  const visible = runs.filter(r => (filter === 'ALL' || runOutcome(r) === filter) && [r.workflowId, r.receipt.id].some(v => v.includes(search.trim())))
  const counts = runs.reduce((a, r) => ({ accepted: a.accepted + r.receipt.accepted, rejected: a.rejected + r.receipt.rejected, filtered: a.filtered + r.receipt.filtered }), { accepted: 0, rejected: 0, filtered: 0 })
  const rows = detail?.trace?.rows.filter(r => rowFilter === 'ALL' || r.status === rowFilter) ?? []
  return <section className="workflow-runs" data-page="workflow-runs">
    <PageHeader title="工作流运行记录" description="按发布版本查看已记录的检查及节点明细。" actions={<Button variant="outline" disabled={busy||historyBusy||!ready} onClick={load}>{view==='HISTORY'?'刷新版本目录':'刷新运行记录'}</Button>} />

    {!ready ? <p className="source-access-hint">{localPreviewMode ? '本地会话尚未就绪，请使用页面上方的重新连接按钮。' : oidcMode ? '请先登录平台，再查询本人运行记录。' : '请先填写平台开发 Token，再查询本人运行记录。'}</p> : null}
    <p role="alert">{error}</p><p role="status">{busy ? '正在读取…' : ''}</p>
    <div className="workflow-record-views" role="group" aria-label="运行记录类型"><Button variant="ghost" aria-pressed={view==='HISTORY'} disabled={busy||historyBusy} onClick={()=>setView('HISTORY')}>运行检查历史</Button><Button variant="ghost" aria-pressed={view==='PREVIEW'} disabled={busy||historyBusy} onClick={()=>setView('PREVIEW')}>预览与版本测试</Button></div>
    <div hidden={view!=='HISTORY'}>{page?.published.truncated?<p className="model-muted">版本目录仅显示最近20项；更早的固定版本可从工作流详情进入。</p>:null}{page?<WorkflowHistorySection key={linkedKey??'selection'} entries={[...page.published.items,...(linkedEntry?[linkedEntry]:[])].filter(historyEligible)} linkedKey={linkedKey} visible={view==='HISTORY'} onBusy={setHistoryBusy}/>:null}</div>
    <div hidden={view!=='PREVIEW'}>
    <div className="workflow-boundary"><a href="#/integrations/workflows">← 返回数据工作流</a><span className="lifecycle-pill">只读测试运行</span><span>成功表示转换通过，尚未写入实体、时序或日志存储。</span></div>
    <SummaryGrid label="当前列表运行统计"><SummaryCard label="最近运行" value={page ? runs.length : '—'} hint="当前列表" /><SummaryCard label="转换成功" value={page ? counts.accepted : '—'} hint="条记录" tone="success" /><SummaryCard label="转换失败" value={page ? counts.rejected : '—'} hint="条记录" tone="danger" /><SummaryCard label="规则过滤" value={page ? counts.filtered : '—'} hint="条记录 · 非失败" /></SummaryGrid>
    <QueryToolbar><div className="run-filters"><label>转换结果<select aria-label="筛选运行结果" value={filter} onChange={e => setFilter((e.target as HTMLSelectElement).value)}><option value="ALL">全部结果</option><option value="SUCCEEDED">全部通过</option><option value="PARTIAL">部分失败</option><option value="FAILED">全部失败</option><option value="FILTERED">全部过滤</option></select></label><label>筛选当前列表<input aria-label="筛选工作流或运行 ID" placeholder="工作流 ID / 运行 ID" value={search} onInput={e => setSearch((e.target as HTMLInputElement).value)} /></label></div></QueryToolbar>
    <div className="run-table-wrap"><table className="run-table"><caption>本人最近20次工作流预览与版本测试</caption><thead><tr><th>工作流 / 运行 ID</th><th>时间与来源</th><th>转换结果</th><th>成功 / 失败 / 过滤</th><th>操作</th></tr></thead><tbody>{visible.map(run => <HistoryRow key={run.receipt.id} run={run} busy={busy} open={inspect} />)}</tbody></table>{!visible.length ? <p className="run-empty">{page ? '没有符合条件的运行记录。' : '读取后查看已保存的运行记录。'}</p> : null}</div>
    {page?.runs.truncated ? <p>仅展示最近20次；筛选只作用于当前列表。更早的记录可按运行 ID 查询。</p> : null}
    <div className="run-lookup"><label>按运行 ID 回查<input aria-label="查询运行 ID" placeholder="输入完整 UUID" maxLength={36} value={runId} onInput={e => setRunId((e.target as HTMLInputElement).value)} /></label><button type="button" disabled={busy || !runId.trim()} onClick={() => inspect(runId)}>查询详情</button></div>
    {detail ? <section className="run-detail" data-run-detail>
      <header><div><div className="model-eyebrow">RUN DETAIL · 运行明细</div><h3>{detail.run.workflowId + ' · v' + detail.run.revision}</h3><code>{detail.run.receipt.id}</code></div><span className="run-badge" data-outcome={runOutcome(detail.run)}>{outcomeLabels[runOutcome(detail.run)]}</span></header>
      <p>{(detail.run.mode === 'PREVIEW' ? '草稿预览' : '版本测试') + ' · ' + detail.run.receipt.origin + ' · ' + detail.run.receipt.createdAt}</p>
      <div className="workflow-result-counts"><span>{'成功 ' + detail.run.receipt.accepted}</span><span>{'失败 ' + detail.run.receipt.rejected}</span><span>{'过滤 ' + detail.run.receipt.filtered}</span></div>
      {!detail.trace ? <p className="source-access-hint">这是一条旧版计数回执，未保存逐条明细。历史错误原因无法补回；重新测试会产生新的运行记录。</p> : null}
      {detail.trace ? <>
        <dl className="run-meta"><div><dt>来源实例</dt><dd>{detail.trace.source.instanceId}</dd></div><div><dt>输出类型</dt><dd>{outputLabel(detail.trace.target)}</dd></div><div><dt>执行耗时</dt><dd>{detail.trace.durationMillis + ' ms'}</dd></div><div><dt>来源批次</dt><dd>{detail.trace.syncRunId ?? (detail.trace.source.metric?'固定指标即时采样':detail.trace.source.configuration?'固定主机即时读取':'手工样本')}</dd></div></dl>
        {detail.trace.source.metric?<dl className="run-meta"><div><dt>完整来源键</dt><dd><code>{detail.trace.source.metric.sourceKey}</code></dd></div><div><dt>来源主机 / 指标项</dt><dd>{detail.trace.source.metric.hostId+' / '+detail.trace.source.metric.itemId}</dd></div><div><dt>接入配置版本</dt><dd>{'v'+detail.trace.source.configuration!.revision}</dd></div><div><dt>指标元数据摘要</dt><dd><code>{detail.trace.source.metric.digest}</code></dd></div></dl>:null}
        <p>{'来源状态 ' + detail.trace.sourceStatus + (detail.trace.source.metric?' · 来源读入 '+detail.trace.retainedCount+(detail.trace.truncated?'（含截断检测点）':'')+' · 转换 '+detail.trace.rows.length:' · 保留 '+detail.trace.retainedCount) + ' · 缺失 Raw ' + detail.trace.missingRaw}</p>
        {detail.trace.truncated || detail.trace.missingRaw || detail.trace.sourceStatus === 'FAILED' ? <p className="source-access-hint">本次输入存在截断、缺失或上游失败。以下结果仅覆盖实际参与转换的记录，不代表整个来源批次成功。</p> : null}
        <h4>节点统计</h4><div className="run-node-stats">{(detail.trace.rows[0]?.steps ?? []).map(step => <NodeStat key={step.nodeId} step={step} rows={detail.trace!.rows} telemetry={outputKind(detail.trace!.target) !== 'ENTITY'} />)}</div>
        <div className="run-record-heading"><h4>逐条处理记录</h4><label>记录结果<select aria-label="筛选记录结果" value={rowFilter} onChange={e => setRowFilter((e.target as HTMLSelectElement).value)}><option value="ALL">全部记录</option><option value="ACCEPTED">仅成功</option><option value="REJECTED">仅失败</option><option value="FILTERED">仅过滤</option></select></label></div>
        <p className="model-muted">编号对应当次输入顺序。仅保留节点状态、字段与错误码，未保存输入和输出正文。</p>
        {rows.map(row => <RecordRow key={row.index} row={row} telemetry={outputKind(detail.trace!.target) !== 'ENTITY'} />)}{!rows.length ? <p>没有符合条件的处理记录。</p> : null}
      </> : null}
    </section> : null}
    </div>
  </section>
}
function HistoryRow(p: { run: Run; busy: boolean; open: (id: string) => void }) {
  return <tr><td><strong>{p.run.workflowId + ' · v' + p.run.revision}</strong><small>{p.run.receipt.id}</small></td><td><span>{new Date(p.run.receipt.createdAt).toLocaleString()}</span><small>{(p.run.mode === 'PREVIEW' ? '草稿预览' : '版本测试') + ' · ' + p.run.receipt.origin}</small></td><td><span className="run-badge" data-outcome={runOutcome(p.run)}>{outcomeLabels[runOutcome(p.run)]}</span></td><td>{p.run.receipt.accepted + ' / ' + p.run.receipt.rejected + ' / ' + p.run.receipt.filtered}</td><td><button type="button" disabled={p.busy} onClick={() => p.open(p.run.receipt.id)}>查看明细</button></td></tr>
}
function RecordRow(p: { row: TraceRow; telemetry: boolean }) {
  const terminal = p.row.steps.find(s => s.status === 'ERROR' || s.status === 'FILTERED')
  return <details className="run-record" open={p.row.status === 'REJECTED'}><summary><strong>{'记录 ' + (p.row.index + 1)}</strong><span className="run-badge" data-outcome={p.row.status}>{rowLabels[p.row.status]}</span><span>{terminal ? (terminal.type === 'VALIDATE' && p.telemetry ? '格式校验' : nodeLabels[terminal.type]) + ' · ' + terminal.nodeId : '所有节点通过'}</span></summary><ol>{p.row.steps.map(step => <StepRow key={step.nodeId} step={step} telemetry={p.telemetry} />)}</ol></details>
}
function NodeStat(p: { step: TraceStep; rows: TraceRow[]; telemetry: boolean }) {
  return <div><strong>{p.step.type === 'VALIDATE' && p.telemetry ? '格式校验' : nodeLabels[p.step.type]}</strong><small>{p.step.nodeId}</small><span>{(['OK', 'ERROR', 'FILTERED', 'SKIPPED'] as const).map(status => stepLabels[status] + ' ' + p.rows.filter(r => r.steps.find(s => s.nodeId === p.step.nodeId)?.status === status).length).join(' · ')}</span></div>
}
function StepRow(p: { step: TraceStep; telemetry: boolean }) {
  return <li data-step-status={p.step.status}><div><strong>{(p.step.type === 'VALIDATE' && p.telemetry ? '格式校验' : nodeLabels[p.step.type]) + ' · ' + p.step.nodeId}</strong><span>{stepLabels[p.step.status]}</span></div>{p.step.status === 'SKIPPED' ? <p>此前记录已失败或被过滤，此节点未执行。</p> : null}{p.step.status === 'FILTERED' ? <p>不满足配置的保留条件，后续节点不再处理。</p> : null}{p.step.issues.map((issue, i) => <p key={i} className="run-issue">{issue.field + '：' + issueLabels[issue.code] + '（' + issue.code + '）'}</p>)}</li>
}
