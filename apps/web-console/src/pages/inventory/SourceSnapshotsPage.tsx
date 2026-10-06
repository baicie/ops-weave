import { useEffect, useRef, useState } from 'react'
import { usePageCloseGuard } from '../../state/page-workspace.ts'
import { Button } from '@/components/ui/button'
import { PageHeader, PageBody } from '../../components/PageLayout.tsx'
import { Input } from '@/components/ui/input'
import { Textarea } from '@/components/ui/textarea'
import { usePlatformSession } from '../../state/platform-session.ts'
import { snapshotConfig, snapshotReceipt, submitSnapshot, parseSnapshotInput, validateSnapshotSubmission, SnapshotError, type SnapshotConfig, type SnapshotReceipt } from '../../api/source-snapshots.ts'

export function SourceSnapshotsPage() {
  const [config, setConfig] = useState<SnapshotConfig | null>(null)
  const [receipt, setReceipt] = useState<SnapshotReceipt | null>(null)
  const [busy, setBusy] = useState(false)
  const [pending, setPending] = useState(false)
  const [error, setError] = useState('')
  const [raw, setRaw] = useState('[]')
  const [observed, setObserved] = useState(new Date().toISOString())
  const [complete, setComplete] = useState(false)
  const [confirmed, setConfirmed] = useState(false)
  const [id, setId] = useState(new URLSearchParams(location.hash.split('?')[1]).get('requestId') ?? '')
  const controllerRef = useRef<AbortController | undefined>(undefined)
  const sequenceRef = useRef(0)
  usePageCloseGuard(busy || pending ? { message: pending ? '操作结果待确认，请先查询原请求回执。' : '请求正在处理，请等待结果后关闭。', blocked: true } : !receipt && (raw !== '[]' || complete) ? { message: '快照有尚未提交的内容。' } : null)

  function clear() {
    controllerRef.current?.abort(); sequenceRef.current++; setConfig(null); setReceipt(null); setRaw('[]'); setComplete(false); setConfirmed(false); setPending(false); setBusy(false); setError('')
  }
  const ready = usePlatformSession(change => { clear(); setError(change.error?.message ?? '') })
  useEffect(() => () => { clear() }, [])

  async function run(work: (signal: AbortSignal, current: () => boolean) => Promise<void>) {
    controllerRef.current?.abort(); const active = new AbortController(); controllerRef.current = active; const seq = ++sequenceRef.current; setBusy(true); setError('')
    try { await work(active.signal, () => seq === sequenceRef.current) }
    catch (e) { if (seq !== sequenceRef.current) return; if (e instanceof SnapshotError && [400, 401, 403, 409].includes(e.status)) setPending(false); setError(e instanceof Error ? e.message : '快照结果待确认') }
    finally { if (seq === sequenceRef.current) setBusy(false) }
  }
  function load() { setReceipt(null); void run(async (signal, current) => { const c = await snapshotConfig(signal); if (current()) setConfig(c) }) }
  function apply() {
    if (!config || !confirmed || pending) return
    let input
    try { input = parseSnapshotInput({ requestId: crypto.randomUUID(), observedAt: observed, complete, records: JSON.parse(raw) }); validateSnapshotSubmission(input, config) }
    catch (e) { setError(e instanceof Error ? e.message : 'JSON不正确'); return }
    setId(input.requestId); setPending(true); setConfirmed(false); setReceipt(null); history.replaceState(null, '', `#/integrations/cmdb?requestId=${input.requestId}`); const c = config
    void run(async (signal, current) => { const r = await submitSnapshot(input, c, signal); if (current()) { setReceipt(r); setPending(false) } })
  }
  function read() { void run(async (signal, current) => { const r = await snapshotReceipt(id, signal); if (current()) { setReceipt(r); setPending(false) } }) }
  const disabled = busy || !ready
  return <section className="panel" data-page="source-snapshots"><PageHeader title="CMDB 来源快照" description="导入明确的来源记录，按人工核对并登记的资产 UUID 自动定位。字段进入待审核记录；名称或 IP 不会自动合并资产。" actions={<Button variant="outline" disabled={disabled || pending} onClick={load}>读取快照配置</Button>} />
    <PageBody className="console-maintenance">
    <p>这是人工导入适配，未连接 CMDB 厂商 API。来源确认有效期为 7 天；完整快照会把该来源未出现的已绑定记录标记为缺失，其他来源仍有效的资产会保留。</p>
    {config ? <><p>{`${config.tenantId} · ${config.actor} · ${config.sourceInstanceId} · ${config.namespace} · import / postgres · 最多100条，浏览器正文上限64KiB`}</p>
      <label>来源观测时间（UTC）<Input value={observed} disabled={disabled || pending} onChange={event => { setObserved(event.currentTarget.value); setConfirmed(false) }} /></label>
      <label>来源记录 JSON<Textarea rows={8} value={raw} disabled={disabled || pending} onChange={event => { setRaw(event.currentTarget.value); setConfirmed(false) }} /></label>
      <p>每条包含 externalId、assetUuid、values；values 只允许 name、ip、owner、environment。未登记或冲突的标识会使整批失败。</p>
      <label><input type="checkbox" checked={complete} disabled={disabled || pending} onChange={event => { setComplete(event.currentTarget.checked); setConfirmed(false) }} />我确认这是该来源的完整快照；允许对未出现的记录标记缺失</label>
      <label><input type="checkbox" checked={confirmed} disabled={disabled || pending} onChange={event => setConfirmed(event.currentTarget.checked)} />已核对标识、观测时间与完整性，确认导入本批</label>
      <Button variant="default" disabled={disabled || pending || !confirmed} onClick={apply}>导入来源快照</Button>
    </> : null}
    {pending ? <p>提交结果待确认。请查询原请求回执；不会自动重试或提交下一批。</p> : null}
    <label>快照请求标识<Input value={id} disabled={busy} onChange={event => setId(event.currentTarget.value)} /></label><Button variant="outline" disabled={disabled || !id} onClick={read}>查询快照回执</Button>
    <p role="alert">{error}</p>{receipt ? <section data-snapshot-receipt><h3>快照已保存</h3><p>{`${receipt.tenantId} · ${receipt.actor} · ${receipt.ingestedAt} · 来源标记缺失 ${receipt.markedAbsent} 条`}</p>
      <p>字段值尚未自动采用。请进入资产详情读取补充来源并审核；已有生效字段需先撤销，再导入新快照取得当前版本的待审记录。</p>
      <ul>{receipt.resolved.map(row => <li key={row.reviewId}>{`${row.externalId} → ${row.entityId} · 待审 ${row.reviewId}`}</li>)}</ul></section> : null}
    </PageBody>
  </section>
}
