import { useEffect, useRef, useState } from 'react'
import { ArrowLeft, X } from 'lucide-react'
import { Button } from '../ui/button.tsx'
import { MetricMappingDetail } from './MetricMappingDetail.tsx'
import { MetricMappingTable } from './MetricMappingTable.tsx'
import { maintainMapping, readMappingBinding, readMappingBindings, readMappingReceipt, sameMappingPin, MappingRejectedError, type MappingPage, type MappingView, type PendingMapping } from '../../api/metric-mappings.ts'
import { metricDefinitionHash } from '../../state/metric-definition-selection.ts'
import { usePlatformSession } from '../../state/platform-session.ts'
import { usePageActive, usePageCloseGuard } from '../../state/page-workspace.ts'
import './metric-mapping.css'

export function MetricMappingPanel() {
  const [page, setPage] = useState<MappingPage | null>(null), [selected, setSelected] = useState<MappingView | null>(null)
  const [choice, setChoice] = useState(''), [pending, setPending] = useState<PendingMapping | null>(null)
  const [stale, setStale] = useState(false)
  const [busy, setBusy] = useState(false), [error, setError] = useState(''), [notice, setNotice] = useState('')
  const dialog = useRef<HTMLDialogElement>(null), request = useRef<AbortController | null>(null), attempted = useRef(false), disposed = useRef(false)
  const active = usePageActive()
  const ready = usePlatformSession(change => { request.current?.abort(); request.current = null; attempted.current = false; setStale(false); setBusy(false); setPending(null); setPage(null); setSelected(null); setChoice(''); setError(change.error?.message ?? ''); setNotice(''); dialog.current?.close() })
  usePageCloseGuard(pending ? { message: '映射提交结果待确认，请先查询原请求。', blocked: true } : busy ? { message: '映射请求正在处理。', blocked: true } : null)
  useEffect(() => { disposed.current = false; return () => { disposed.current = true; request.current?.abort() } }, [])
  useEffect(() => { if (!active && !pending) { request.current?.abort(); request.current = null; setBusy(false); dialog.current?.close() } }, [active, pending])
  async function run(work: (signal: AbortSignal, current: () => boolean) => Promise<void>) {
    if (!ready || !active || request.current) return
    const controller = new AbortController(); request.current = controller; setBusy(true); setError(''); setNotice('')
    const current = () => !disposed.current && !controller.signal.aborted && request.current === controller
    try { await work(controller.signal, current) }
    catch (cause) { if (current()) { setError(cause instanceof Error ? cause.message : '映射请求失败'); if (cause instanceof MappingRejectedError) { setPending(null); setStale(true) } } }
    finally { if (request.current === controller) { request.current = null; setBusy(false) } }
  }
  function load() { if (pending) return; attempted.current = true; void run(async (signal, current) => { const value = await readMappingBindings(signal); if (current()) { setPage(value); setSelected(null) } }) }
  function open() { dialog.current?.showModal(); if (!attempted.current) load() }
  function adoptView(view: MappingView) { setStale(false); setSelected(view); setChoice(view.candidates.find(d => sameMappingPin(d.mappingPin, view.binding.mappingPin))?.mappingPin.id ?? view.candidates[0]?.mappingPin.id ?? '') }
  function inspect(view: MappingView) { void run(async (signal, current) => { const value = await readMappingBinding(view.binding, signal); if (current()) adoptView(value) }) }
  function confirmed(binding: MappingView['binding']) { setPending(null); setSelected(previous => previous ? { ...previous, binding } : null); setPage(previous => previous ? { ...previous, items: previous.items.map(v => v.binding.sourceInstanceId === binding.sourceInstanceId && v.binding.externalItemId === binding.externalItemId ? { ...v, binding } : v) } : null); setNotice('映射维护结果已确认') }
  const definition = selected?.candidates.find(d => d.mappingPin.id === choice) ?? null
  function submit() {
    if (!ready || !active || !selected || !definition || pending || busy || request.current || stale || !selected.canConfigure || selected.binding.lifecycle !== 'ACTIVE') return
    const operation: PendingMapping = { previous: structuredClone(selected.binding), definition: structuredClone(definition), command: { requestId: crypto.randomUUID(), expectedBindingVersion: selected.binding.version, mappingPin: definition.mappingPin } }
    setPending(operation); void run(async (signal, current) => { const b = await maintainMapping(operation, signal); if (current()) confirmed(b) })
  }
  function lookup() { if (!pending) return; const operation = pending; void run(async (signal, current) => { const b = await readMappingReceipt(operation, signal); if (current()) confirmed(b) }) }
  const disabled = busy || !!pending || !ready
  return <><Button variant="outline" disabled={!ready || !active} onClick={open}>来源绑定</Button>
    <dialog ref={dialog} className="model-drawer source-drawer metric-mapping-drawer" aria-label="指标来源绑定" onCancel={event => { if (busy || pending) event.preventDefault() }}>
      <header className="model-drawer-heading"><div><h3>指标来源绑定</h3><p>查看并固定采样使用的映射规则</p></div><Button variant="ghost" size="icon" aria-label="关闭指标来源绑定" disabled={disabled} onClick={() => dialog.current?.close()}><X size={18}/></Button></header>
      <div className="model-drawer-body metric-mapping-body">
        {selected ? <><Button variant="ghost" disabled={disabled} onClick={() => { setSelected(null); setNotice(''); setError('') }}><ArrowLeft size={15}/>返回来源绑定</Button>
          <label className="metric-mapping-selector">选定映射<select aria-label="选定指标映射" value={choice} disabled={disabled || !selected.canConfigure || selected.binding.lifecycle !== 'ACTIVE'} onChange={event => setChoice(event.target.value)}>{selected.candidates.length ? selected.candidates.map(d => <option key={d.mappingPin.id} value={d.mappingPin.id}>{d.mappingPin.id} · v{d.mappingPin.revision}</option>) : <option value="">无兼容映射</option>}</select></label>
          <MetricMappingDetail binding={selected.binding} definition={definition}/>
          {!pending ? <a href={metricDefinitionHash(selected.binding.metricKey)} onClick={() => dialog.current?.close()}>查看指标定义</a> : null}
          <p className="model-muted">变更用于后续采样，历史数据保留原有标记。本次维护不补采数据。</p>
        </> : <>{page ? <MetricMappingTable page={page} disabled={disabled} inspect={inspect}/> : <p role="status">{busy ? '正在读取来源绑定…' : ready ? '来源绑定尚未读取' : '请先建立平台会话'}</p>}</>}
        {error ? <p role="alert">{error}</p> : null}<p role="status">{busy ? '正在处理…' : notice}</p>
        {pending ? <p>结果待确认 · 请求 <code>{pending.command.requestId}</code></p> : null}
      </div><footer className="model-drawer-footer">
        {pending ? <Button variant="outline" disabled={busy || !ready} onClick={lookup}>查询原映射请求</Button> : <><Button variant="outline" disabled={disabled} onClick={() => selected ? inspect(selected) : load()}>{selected ? '读取最新绑定' : error ? '重试读取' : '刷新来源绑定'}</Button>{selected?.canConfigure ? <Button disabled={disabled || stale || !definition || selected.binding.lifecycle !== 'ACTIVE' || sameMappingPin(definition.mappingPin, selected.binding.mappingPin)} onClick={submit}>{selected.binding.mappingPin ? '确认更新映射' : '确认固定映射'}</Button> : null}</>}
      </footer>
    </dialog></>
}
