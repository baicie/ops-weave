import { useEffect, useRef, useState } from 'react'
import { usePageCloseGuard } from '../../state/page-workspace.ts'
import { Button } from '@/components/ui/button'
import { PageHeader, PageBody } from '../../components/PageLayout.tsx'
import { Input } from '@/components/ui/input'
import { Textarea } from '@/components/ui/textarea'
import { usePlatformSession } from '../../state/platform-session.ts'
import { prepareCorrection, submitCorrection, readCorrection, correctionHistory, CorrectionError, type CorrectionPreview, type CorrectionReceipt, type CorrectionPage } from '../../api/source-binding-corrections.ts'
import { validateSnapshotSubmission } from '../../api/source-snapshots.ts'
import { correctionInput } from '../../api/source-binding-corrections.ts'

export function SourceBindingCorrectionsPage() {
  const [previous, setPrevious] = useState('')
  const [external, setExternal] = useState('')
  const [target, setTarget] = useState('')
  const [observed, setObserved] = useState(new Date().toISOString())
  const [raw, setRaw] = useState('{}')
  const [reason, setReason] = useState('')
  const [preview, setPreview] = useState<CorrectionPreview | null>(null)
  const [receipt, setReceipt] = useState<CorrectionReceipt | null>(null)
  const [page, setPage] = useState<CorrectionPage | null>(null)
  const [confirmed, setConfirmed] = useState(false)
  const [pending, setPending] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [id, setId] = useState(new URLSearchParams(location.hash.split('?')[1]).get('requestId') ?? '')
  const controllerRef = useRef<AbortController | undefined>(undefined)
  const sequenceRef = useRef(0)
  usePageCloseGuard(busy || pending ? { message: pending ? '操作结果待确认，请先查询原请求回执。' : '请求正在处理，请等待结果后关闭。', blocked: true } : !receipt && (previous || external || target || reason || raw !== '{}') ? { message: '来源绑定有尚未提交的修改。' } : null)

  function invalidate() { setPreview(null); setConfirmed(false); setReceipt(null); setPage(null) }
  function clear() {
    controllerRef.current?.abort(); sequenceRef.current++; invalidate(); setPrevious(''); setExternal(''); setTarget(''); setObserved(new Date().toISOString()); setRaw('{}'); setReason(''); setPending(false); setBusy(false); setError('')
  }
  const ready = usePlatformSession(change => { clear(); setError(change.error?.message ?? '') })
  useEffect(() => () => { clear() }, [])

  async function run(work: (signal: AbortSignal, current: () => boolean) => Promise<void>) {
    controllerRef.current?.abort(); controllerRef.current = new AbortController(); const active = controllerRef.current, seq = ++sequenceRef.current; setBusy(true); setError('')
    try { await work(active.signal, () => seq === sequenceRef.current) }
    catch (e) {
      if (seq !== sequenceRef.current) return
      if (e instanceof CorrectionError && [400, 401, 403, 409].includes(e.status)) { setPending(false); setPreview(null); setConfirmed(false) }
      setError(e instanceof Error ? e.message : '更正结果待确认')
    }
    finally { if (seq === sequenceRef.current) setBusy(false) }
  }
  const disabled = busy || pending || !ready
  function prepare() {
    invalidate()
    void run(async (signal, current) => {
      const p = await prepareCorrection(previous.trim(), external.trim(), target.trim(), observed, JSON.parse(raw), reason, signal)
      if (current()) setPreview(p)
    })
  }
  function apply() {
    const p = preview; if (!p || !confirmed || pending) return
    try { validateSnapshotSubmission(correctionInput(p.command), p.config) }
    catch (e) { setError(e instanceof Error ? e.message : '请重新预览'); return }
    setPending(true); setId(p.command.requestId); setConfirmed(false); setReceipt(null); history.replaceState(null, '', `#/integrations/cmdb/corrections?requestId=${p.command.requestId}`)
    void run(async (signal, current) => { const r = await submitCorrection(p, signal); if (current()) { setReceipt(r); setPending(false); setPreview(null) } })
  }
  function read() {
    setReceipt(null)
    void run(async (signal, current) => { const r = await readCorrection(id, signal); if (current()) { setReceipt(r); setPending(false); setPreview(null) } })
  }
  function historyPage(after: string | null = null) {
    void run(async (signal, current) => { const p = await correctionHistory(previous.trim(), after, signal); if (current()) setPage(p) })
  }
  function edit(set: (v: string) => void, value: string) { set(value); invalidate() }
  return <section className="panel" data-page="source-binding-corrections"><PageHeader title="来源绑定更正" description="人工核对错误绑定后，使用目标资产已登记的 UUID 和一份新的来源观测更正当前绑定。原观测、指标、Incident、字段审核和旧回执保留原资产归属；新字段仍需审核。" />
    <PageBody className="console-maintenance">
    <p>这是固定 CMDB 人工导入入口。若原绑定存在生效字段，请先在原资产详情撤销；不会自动搬移或批准字段。</p>
    <label>原资产 ID<Input value={previous} disabled={disabled} onChange={event => edit(setPrevious, event.currentTarget.value)} /></label>
    <label>待更正来源外部 ID<Input value={external} disabled={disabled} onChange={event => edit(setExternal, event.currentTarget.value)} /></label>
    <label>目标已登记资产 UUID<Input value={target} disabled={disabled} onChange={event => edit(setTarget, event.currentTarget.value)} /></label>
    <label>更正来源观测时间（UTC）<Input value={observed} disabled={disabled} onChange={event => edit(setObserved, event.currentTarget.value)} /></label>
    <label>更正来源字段 JSON<Textarea rows={5} value={raw} disabled={disabled} onChange={event => edit(setRaw, event.currentTarget.value)} /></label>
    <p>字段只允许 name、ip、owner、environment，至少一项。观测时间须晚于该来源已保存快照；不会对账其他对象。</p>
    <label>绑定更正原因<Input value={reason} disabled={disabled} onChange={event => edit(setReason, event.currentTarget.value)} /></label>
    <Button variant="outline" disabled={disabled || !previous || !target || !external || !reason.trim()} onClick={prepare}>读取更正预览</Button>
    {preview ? <section data-correction-preview><h3>核对原绑定与目标</h3><p>{`${preview.config.tenantId} · ${preview.config.actor} · ${preview.config.sourceInstanceId} · ${preview.config.namespace}`}</p>
      <p>{`原资产 ${preview.previous.name} · ${preview.previous.id} · 版本 ${preview.previous.version}`}</p><p>{`原确认 ${preview.presence.status} · ${preview.presence.identity.value} · ${preview.presence.observedAt}`}</p>
      <p>{`目标资产 ${preview.target.name} · ${preview.target.id} · 版本 ${preview.target.version} · UUID ${preview.command.targetIdentity.value}`}</p><p>{`新观测 ${preview.command.observedAt} · ${JSON.stringify(preview.command.values)} · 原因 ${preview.command.reason}`}</p>
      <label><input type="checkbox" checked={confirmed} disabled={disabled} onChange={event => setConfirmed(event.currentTarget.checked)} />已核对新旧资产，确认更正当前绑定并保留历史归属</label><Button variant="default" disabled={disabled || !confirmed} onClick={apply}>确认绑定更正</Button>
    </section> : null}
    {pending ? <p data-correction-pending>更正结果待确认。只能查询原请求回执，不会自动重试或提交其他更正。</p> : null}
    <label>更正请求标识<Input value={id} disabled={busy || pending} onChange={event => { setId(event.currentTarget.value); setReceipt(null) }} /></label><Button variant="outline" disabled={busy || !ready || !id} onClick={read}>查询更正回执</Button>
    <p role="alert">{error}</p>{receipt ? <section data-correction-receipt><h3>绑定更正已保存</h3><ReceiptContent receipt={receipt} /></section> : null}
    <h3>资产更正历史</h3><p>按上方原资产 ID 查询参与过的更正，显示当时操作者与新旧归属。每页10条，读取需要当前整源管理权限。</p>
    <Button variant="outline" disabled={disabled || !previous} onClick={() => historyPage()}>读取更正历史</Button><Button variant="outline" disabled={disabled || !page?.nextCursor} onClick={() => historyPage(page?.nextCursor ?? null)}>下一页更正历史</Button>
    {page ? <section data-correction-history><p>{`本页 ${page.items.length} 条；历史记录不会按当前绑定改写。`}</p>{page.items.map(row => <article key={row.command.requestId}><ReceiptContent receipt={row} /></article>)}</section> : null}
    </PageBody>
  </section>
}

function ReceiptContent(props: { receipt: CorrectionReceipt }) {
  const r = props.receipt
  return <div><p>{`${r.command.externalId} · ${r.command.previousEntityId} → ${r.command.targetEntityId}`}</p><p>{`${r.actor} · ${r.snapshot.ingestedAt} · ${r.command.reason}`}</p><p>{`原资产版本 ${r.previousEntityVersionAfter} · 目标版本 ${r.snapshot.resolved[0]?.entityVersion} · 待审 ${r.snapshot.resolved[0]?.reviewId}`}</p><p>{`请求 ${r.command.requestId}；旧快照 ${r.previous.snapshotId} 及其历史归属保留。`}</p></div>
}
