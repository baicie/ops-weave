import { useEffect, useRef, useState } from 'react'
import { usePlatformSession } from '../../state/platform-session.ts'
import { Button } from '@/components/ui/button'
import { PageHeader, PageBody, QueryToolbar, SummaryGrid, SummaryCard } from '../../components/PageLayout.tsx'
import { Input } from '@/components/ui/input'
import { sourceScanRuns, sourceScanRun, ScanRunError, type ScanObjectType, type ScanRun, type ScanRunPage, type ScanRunRead } from '../../api/source-scan-runs.ts'
import { SourceConnectionCheckPanel } from './SourceConnectionCheckPanel.tsx'

const statusLabel: Record<ScanRun['status'], string> = { RUNNING: '进行中', SUCCEEDED: '成功', FAILED: '失败' }
const objectLabel: Record<ScanObjectType, string> = { host: 'Host', item: 'Item' }
function formatTime(value: string | null) { return value ? new Date(value).toLocaleString() : '进行中' }
function elapsed(run: ScanRun) {
  if (!run.completedAt) return '进行中'
  const millis = Math.max(0, Date.parse(run.completedAt) - Date.parse(run.startedAt))
  return millis < 1000 ? `${millis} ms` : `${(millis / 1000).toFixed(1)} s`
}
function consistencyLabel(value: ScanRun['scanConsistency']) {
  return value === 'hostid-watermark-snapshot' ? '已验证 hostid 水位快照'
    : value === 'itemid-watermark-snapshot' ? '已验证 itemid 水位快照' : 'offset 尝试（无快照证明）'
}

export function SourceScanRunsPage() {
  const [objectType, setObjectType] = useState<ScanObjectType>('host')
  const [limit, setLimit] = useState(20)
  const [page, setPage] = useState<ScanRunPage | null>(null)
  const [read, setRead] = useState<ScanRunRead | null>(null)
  const [id, setId] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const controllerRef = useRef<AbortController | undefined>(undefined)
  const sequenceRef = useRef(0)
  const detailRef = useRef<HTMLDialogElement>(null)
  function results() { setPage(null); setRead(null); detailRef.current?.close() }
  function clear() { controllerRef.current?.abort(); sequenceRef.current++; results(); setId(''); setBusy(false); setError('') }
  const ready = usePlatformSession(change => { clear(); setError(change.error?.message ?? '') })
  useEffect(() => () => clear(), [])
  useEffect(() => { if (!ready) { controllerRef.current?.abort(); sequenceRef.current++; setPage(null); setRead(null); setId(''); setBusy(false); detailRef.current?.close() } }, [ready])
  // Keep the read-only drawer non-modal so the session bar can still revoke the
  // current credential while a detail view is open.
  useEffect(() => { if (read && detailRef.current && !detailRef.current.open) detailRef.current.show(); if (!read && detailRef.current?.open) detailRef.current.close() }, [read])
  async function run(work: (signal: AbortSignal, current: () => boolean) => Promise<void>) {
    controllerRef.current?.abort(); const active = new AbortController(); controllerRef.current = active; const seq = ++sequenceRef.current
    setBusy(true); setError('')
    try { await work(active.signal, () => seq === sequenceRef.current) }
    catch (e) { if (seq !== sequenceRef.current) return; if (e instanceof ScanRunError && [400, 401, 403, 404, 503].includes(e.status)) results(); setError(e instanceof Error ? e.message : '扫描记录读取待确认') }
    finally { if (seq === sequenceRef.current) setBusy(false) }
  }
  function choose(type: ScanObjectType) { if (type === objectType) return; setObjectType(type); results() }
  function size(value: number) { if (value === limit) return; setLimit(value); results() }
  function load(after: string | null = null) { setRead(null); void run(async (signal, current) => { const p = await sourceScanRuns(objectType, { limit, after }, signal); if (current()) setPage(p) }) }
  function find() { setRead(null); void run(async (signal, current) => { const r = await sourceScanRun(objectType, id.trim(), signal); if (current()) setRead(r) }) }
  function inspect(item: ScanRun) { setId(item.syncRunId); void run(async (signal, current) => { const r = await sourceScanRun(objectType, item.syncRunId, signal); if (current()) setRead(r) }) }
  const disabled = busy || !ready
  const items = page?.items ?? []
  const counts = items.reduce((result, item) => { result.total++; result[item.status]++; return result }, { total: 0, RUNNING: 0, SUCCEEDED: 0, FAILED: 0 })
  return <section className="panel source-scan-runs" data-page="source-scan-runs" data-object-type={objectType} data-limit={limit}>
    <PageHeader title="采集运行记录" description="查看已保存的来源扫描批次、快照完整性与入库结果。记录为只读，不会因查看而启动扫描、重试或清理数据。" actions={<Button variant="outline" disabled={disabled} onClick={() => load()}>{page ? '刷新记录' : '读取扫描运行'}</Button>} />
    {!ready ? <p className="source-access-hint">请先完成平台会话，再查询本人可见的采集运行。</p> : null}
    <p role="alert">{error}</p><p role="status">{busy ? '正在读取…' : ''}</p>
    <PageBody>
      <div className="source-scan-toolbar"><QueryToolbar label="采集运行筛选">
        <div className="source-scan-segment" role="group" aria-label="扫描对象类型"><Button variant={objectType === 'host' ? 'default' : 'outline'} disabled={disabled} onClick={() => choose('host')}>Host 扫描</Button><Button variant={objectType === 'item' ? 'default' : 'outline'} disabled={disabled} onClick={() => choose('item')}>Item 扫描</Button></div>
        <div className="source-scan-segment" role="group" aria-label="每页数量"><Button variant={limit === 10 ? 'default' : 'outline'} disabled={disabled} onClick={() => size(10)}>每页 10 条</Button><Button variant={limit === 20 ? 'default' : 'outline'} disabled={disabled} onClick={() => size(20)}>每页 20 条</Button><Button variant={limit === 50 ? 'default' : 'outline'} disabled={disabled} onClick={() => size(50)}>每页 50 条</Button></div>
      </QueryToolbar></div>
      {page ? <>
        <div className="source-scan-context"><div><strong>{objectLabel[page.objectType]} 采集批次</strong><span>{page.sourceInstanceId} · {page.storage} · {page.dataMode}</span></div><span>本页 {page.items.length} 条</span></div>
        <SummaryGrid label="采集运行统计"><SummaryCard label="本页记录" value={counts.total} hint="当前分页" /><SummaryCard label="成功" value={counts.SUCCEEDED} hint="完整快照" tone="success" /><SummaryCard label="失败" value={counts.FAILED} hint="保留失败原因" tone="danger" /><SummaryCard label="进行中" value={counts.RUNNING} hint="受保护记录" /></SummaryGrid>
        <section className="source-scan-list" data-scan-run-list>
          <div className="source-scan-list-head"><div><h3>最近采集批次</h3><p>按开始时间倒序排列；选择一条记录查看完整边界、分页和映射版本。</p></div><Button variant="outline" disabled={disabled || !page.nextCursor} onClick={() => load(page.nextCursor ?? null)}>下一页扫描运行</Button></div>
          <div className="source-scan-legacy-text" aria-hidden="true">{`${page.tenantId} · ${page.sourceInstanceId} · ${page.objectType} · ${page.storage} · 本页 ${page.items.length} 条`}</div>
          {page.items.length === 0 ? <p data-scan-run-empty>本页没有存储的扫描运行；这不代表该来源从未被扫描过。</p> : null}
          {page.items.map(item => <ScanRunRow key={item.syncRunId} run={item} open={inspect} />)}
        </section>
        <details className="source-scan-retention"><summary>查看存储与保留边界</summary><p data-scan-run-retention>{`可清理扫描记录保留预算：本范围 ${page.retention.maxRunsPerScope} 条 / 本租户 ${page.retention.maxRunsPerTenant} 条；当前本范围计入预算 ${page.retention.retained} 条。读取不会清理记录；下一次扫描开始时按预算清理最旧的可清理记录。仍在运行或被映射版本钉住的运行不会被删除，也不计入此数量；本页可包含这些受保护记录，此预算不代表总存储量上限。`}</p></details>
      </> : <div className="source-scan-empty-state"><strong>选择对象类型并读取采集记录</strong><span>记录仅来自平台已持久化的扫描日志，按需读取不会触发新的来源请求。</span></div>}
      <details open className="source-scan-lookup"><summary>按运行 ID 查询</summary><div className="source-scan-lookup-form"><label>扫描运行标识<Input aria-label="扫描运行标识" value={id} disabled={disabled} onChange={e => { setId(e.currentTarget.value); setRead(null) }} placeholder="输入完整 UUID" /></label><Button variant="outline" disabled={disabled || !id.trim()} onClick={find}>查询扫描运行</Button></div><p>未知、跨租户、跨来源或跨对象类型的运行一律按未找到处理，不泄漏存在性。</p></details>
      <details open className="source-scan-connection-check"><summary>来源连接自检与回执</summary><SourceConnectionCheckPanel /></details>
    </PageBody>
    <dialog ref={detailRef} className="model-drawer source-drawer source-scan-detail" aria-label="采集运行详情" data-scan-run-read={read ? true : undefined} onClose={() => setRead(null)}>
      <header className="model-drawer-heading"><div><div className="model-eyebrow">采集运行详情</div><h3>{read ? `${objectLabel[read.run.objectType]} · ${statusLabel[read.run.status]}` : '采集运行详情'}</h3>{read ? <code>{read.run.syncRunId}</code> : null}</div><button type="button" className="model-close" aria-label="关闭采集运行详情" onClick={() => detailRef.current?.close()}>×</button></header>
      <div className="model-drawer-body">{read ? <><ScanRunDetails run={read.run} /><div className="source-scan-legacy-text" aria-hidden="true">{`${read.run.objectType} · ${read.run.status} · ${read.run.startedAt} → ${read.run.completedAt ?? '未完成'}`}<br />{`游标 ${read.run.cursor ?? '无'} · 页数 ${read.run.pages} · 抓取 ${read.run.fetched} · 采纳 ${read.run.accepted} · 拒绝 ${read.run.rejected} · 完整快照 ${read.run.snapshotComplete ? '是' : '否'} · ${read.run.dataMode}`}<br />{`边界 ${consistencyLabel(read.run.scanConsistency)}`}</div></> : null}</div>
      <footer className="model-drawer-footer"><span>只读记录，不会修改来源或扫描状态</span><Button variant="outline" onClick={() => detailRef.current?.close()}>关闭</Button></footer>
    </dialog>
  </section>
}

function ScanRunRow(props: { run: ScanRun; open: (run: ScanRun) => void }) {
  const run = props.run
  return <article data-scan-run-id={run.syncRunId} className="source-scan-row">
    <div className="source-scan-row-main"><div><div className="source-scan-row-title"><span className="run-badge" data-outcome={run.status}>{statusLabel[run.status]}</span><strong>{objectLabel[run.objectType]} 采集</strong></div><code>{run.syncRunId}</code></div><div className="source-scan-row-time"><time dateTime={run.startedAt}>{formatTime(run.startedAt)}</time><span>{elapsed(run)}</span></div></div>
    <div className="source-scan-row-stats"><span>页数 <b>{run.pages}</b></span><span>抓取 <b>{run.fetched}</b></span><span>采纳 <b>{run.accepted}</b></span><span>拒绝 <b>{run.rejected}</b></span><span>快照 <b>{run.snapshotComplete ? '完整' : '未完成'}</b></span></div>
    <div className="source-scan-row-foot"><span>{consistencyLabel(run.scanConsistency)} · {run.dataMode}</span><Button variant="ghost" onClick={() => props.open(run)}>查看详情</Button></div>
    {run.failureCode ? <div className="source-scan-row-failure" data-scan-run-failure><strong>{run.failureCode}：</strong><span>{run.failureSummary}</span></div> : null}
    {run.pipelineVersion ? <div className="source-scan-row-pipeline">映射版本 {run.pipelineVersion.id} · 修订 {run.pipelineVersion.revision}</div> : null}
    <div className="source-scan-legacy-text" aria-hidden="true">{`${run.objectType} · ${run.status} · ${run.startedAt} → ${run.completedAt ?? '未完成'}`}<br />{`游标 ${run.cursor ?? '无'} · 页数 ${run.pages} · 抓取 ${run.fetched} · 采纳 ${run.accepted} · 拒绝 ${run.rejected} · 完整快照 ${run.snapshotComplete ? '是' : '否'} · ${run.dataMode}`}<br />{`边界 ${consistencyLabel(run.scanConsistency)}`}{run.failureCode ? <><br /><span data-scan-run-failure>{`${run.failureCode}：${run.failureSummary}`}</span></> : null}{run.pipelineVersion ? <><br />{`映射版本 ${run.pipelineVersion.id} 修订 ${run.pipelineVersion.revision} · ${run.pipelineVersion.digest}`}</> : null}</div>
  </article>
}

function ScanRunDetails({ run }: { run: ScanRun }) {
  return <div className="source-scan-detail-content"><div className="source-scan-detail-status"><span className="run-badge" data-outcome={run.status}>{statusLabel[run.status]}</span><span>{formatTime(run.startedAt)} → {formatTime(run.completedAt)}</span></div><dl className="source-scan-meta"><div><dt>对象类型</dt><dd>{objectLabel[run.objectType]}</dd></div><div><dt>执行耗时</dt><dd>{elapsed(run)}</dd></div><div><dt>数据模式</dt><dd>{run.dataMode}</dd></div><div><dt>扫描边界</dt><dd>{consistencyLabel(run.scanConsistency)}</dd></div><div><dt>游标</dt><dd><code>{run.cursor ?? '无'}</code></dd></div><div><dt>快照状态</dt><dd>{run.snapshotComplete ? '完整快照' : '尚未完成'}</dd></div></dl><div className="source-scan-counts"><span><b>{run.pages}</b>页</span><span><b>{run.fetched}</b>抓取</span><span><b>{run.accepted}</b>采纳</span><span><b>{run.rejected}</b>拒绝</span></div>{run.failureCode ? <div className="source-scan-failure"><strong>{run.failureCode}</strong><p>{run.failureSummary}</p></div> : null}{run.pipelineVersion ? <div className="source-scan-pipeline"><span>映射版本</span><strong>{run.pipelineVersion.id} · 修订 {run.pipelineVersion.revision}</strong><code>{run.pipelineVersion.digest}</code></div> : null}<p className="source-scan-read-note">扫描记录仅保存批次元数据与结果计数，未保存来源正文。查看不会清理记录，也不会改变来源状态。</p></div>
}
