import { usePlatformSession } from '../../state/platform-session.ts'
import { createSignal, For, onCleanup, Show } from '@zeus-js/zeus'
import { forItem } from '../../adapters/zeus-ui/for-item.ts'
import { ZwButton } from '../../adapters/zeus-ui/ZwButton.tsx'
import { ZwInput } from '../../adapters/zeus-ui/ZwInput.tsx'
import { getIncident, IncidentRequestError, type IncidentDetail } from '../../api/incidents.ts'
import { reorganize, getReorganization, listReorganizations, keyOf, uuid, ReorganizationError,
  type ProblemKey, type ReorganizationRequest, type ReorganizationResult, type ReorganizationPage as HistoryPage } from '../../api/reorganizations.ts'

export function ReorganizationPage() {
  const params = new URLSearchParams(location.hash.split('?')[1])
  const authenticated = usePlatformSession(change => { ++sequence; active?.abort(); setBusy(false); setError(''); clear(); setTitle(''); setReason(''); if (change.error) setError(change.error.message) }); const [sourceId, setSourceId] = createSignal(params.get('incidentId') ?? '')
  const [targetId, setTargetId] = createSignal(''); const [kind, setKind] = createSignal<'MERGE' | 'SPLIT'>('MERGE')
  const [source, setSource] = createSignal<IncidentDetail | null>(null); const [target, setTarget] = createSignal<IncidentDetail | null>(null)
  const [selected, setSelected] = createSignal<ProblemKey[]>([]); const [title, setTitle] = createSignal(''); const [reason, setReason] = createSignal('')
  const [review, setReview] = createSignal<ReorganizationRequest | null>(null); const [pending, setPending] = createSignal(false)
  const [receipt, setReceipt] = createSignal<ReorganizationResult | null>(null); const [historyPage, setHistoryPage] = createSignal<HistoryPage | null>(null)
  const [requestKey, setRequestKey] = createSignal(params.get('requestKey') ?? '')
  const [busy, setBusy] = createSignal(false); const [error, setError] = createSignal('')
  let active: AbortController | undefined; let sequence = 0; let disposed = false
  onCleanup(() => { disposed = true; ++sequence; active?.abort() })
  const disabled = () => busy() || !authenticated()
  const editingDisabled = () => busy() || pending()
  function clear() { setSource(null); setTarget(null); setSelected([]); setReview(null); setReceipt(null); setHistoryPage(null); setPending(false) }
  function edit(set: (value: string) => void, value: string) { setReview(null); setReceipt(null); set(set === setSourceId ? value.trim() : value); if (set === setSourceId) { setSource(null); setSelected([]); setHistoryPage(null) } if (set === setTargetId) setTarget(null) }
  async function run(work: (signal: AbortSignal, current: () => boolean) => Promise<void>) {
    active?.abort(); const controller = new AbortController(); active = controller; const seq = ++sequence; const current = () => !disposed && seq === sequence
    const timer = window.setTimeout(() => controller.abort(), 20000); setBusy(true); setError('')
    try { await work(controller.signal, current) }
    catch (cause) {
      if (!current()) return
      if ((cause instanceof IncidentRequestError || cause instanceof ReorganizationError) && [401, 403].includes(cause.status)) clear()
      if (cause instanceof ReorganizationError && cause.status === 409) { setSource(null); setTarget(null); setReview(null); setPending(false) }
      setError(cause instanceof DOMException && cause.name === 'AbortError' ? '请求超时；若已提交，请先读取关联记录确认结果。' : cause instanceof Error ? cause.message : '请求失败')
    } finally { window.clearTimeout(timer); if (current()) setBusy(false) }
  }
  function load(which: 'source' | 'target') {
    setReview(null); setReceipt(null)
    const id = which === 'source' ? sourceId() : targetId()
    if (which === 'source') setSource(null); else setTarget(null)
    void run(async (signal, current) => { const value = await getIncident(id, signal); if (current()) { if (which === 'source') { setSource(value); setSelected([]) } else setTarget(value) } })
  }
  function preview() {
    setError(''); const s = source(), t = target()
    if (!s || s.record.organization?.mergedInto || s.record.incident.status === 'CLOSED') { setError('请读取可调整的来源 Incident'); return }
    if (!reason().trim() || reason().trim().length > 500) { setError('请填写 1–500 字的人工调整原因'); return }
    if (kind() === 'MERGE' && (!t || t.record.incident.id === s.record.incident.id || t.record.incident.tenantId !== s.record.incident.tenantId
      || t.record.organization?.mergedInto || !['OPEN', 'INVESTIGATING'].includes(t.record.incident.status))) { setError('请选择同租户、未合并且处于 OPEN / INVESTIGATING 的目标'); return }
    if (kind() === 'SPLIT' && (!title().trim() || title().trim().length > 300 || selected().length === 0 || selected().length >= s.record.problems.length)) { setError('请填写 1–300 字的新标题并选择部分问题；来源至少保留一个问题'); return }
    const input: ReorganizationRequest = { requestKey: crypto.randomUUID(), kind: kind(), sourceIncidentId: s.record.incident.id, expectedSourceVersion: s.record.incident.version,
      targetIncidentId: kind() === 'MERGE' ? t!.record.incident.id : crypto.randomUUID(), expectedTargetVersion: kind() === 'MERGE' ? t!.record.incident.version : 0,
      problemKeys: kind() === 'SPLIT' ? [...selected()] : [], title: kind() === 'SPLIT' ? title().trim() : null, reason: reason().trim() }
    setReview(input); setRequestKey(input.requestKey); setReceipt(null)
  }
  function save() {
    const input = review(); if (!input) return; setPending(true)
    window.history.replaceState(null, '', `#/incidents/reorganize?incidentId=${input.sourceIncidentId}&requestKey=${input.requestKey}`)
    void run(async (signal, current) => { const result = await reorganize(input, signal); if (current()) finish(result) })
  }
  function finish(value: ReorganizationResult) { setReceipt(value); setPending(false); setReview(null); setSource(null); setTarget(null); setHistoryPage(null); setSelected([]); setRequestKey(value.change.request.requestKey) }
  function readResult() { const key = requestKey(); void run(async (signal, current) => { const value = await getReorganization(key, signal); if (current()) finish(value) }) }
  function history(next: boolean) { const id = sourceId(), after = next ? historyPage()?.nextCursor ?? null : null; void run(async (signal, current) => { const value = await listReorganizations(id, after, signal); if (current()) setHistoryPage(value) }) }
  function toggle(key: ProblemKey, checked: boolean) { setReview(null); setSelected(checked ? [...selected(), key] : selected().filter(k => keyOf(k) !== keyOf(key))) }
  return <section class="panel" data-page="reorganization">
    <h2>人工调整 Incident 归属</h2><p>合并将问题的后续观测交给目标，来源保留历史快照；拆分将选中问题移入新的 OPEN Incident。关联版本变化会使旧诊断证据失效。</p>
    <label>来源 Incident ID<ZwInput value={sourceId()} disabled={editingDisabled()} onValueChange={v => edit(setSourceId, v)} /></label>
    <div class="actions"><ZwButton variant="outline" disabled={disabled() || pending() || !uuid(sourceId())} onPress={() => load('source')}>读取来源 Incident</ZwButton>
      <ZwButton variant="outline" disabled={disabled() || !uuid(sourceId())} onPress={() => history(false)}>读取关联历史</ZwButton></div>
    <Show when={source()}><section data-reorganization-source><h3>{source()?.record.incident.title}</h3><p>{`${source()?.record.incident.status} · 版本 ${source()?.record.incident.version} · ${source()?.storage}`}</p>
      <Show when={source()?.record.organization?.mergedInto}><p>{`已合并至 ${source()?.record.organization?.mergedInto}`}</p></Show>
      <p>{`问题 ${source()?.record.problems.length} · 来源 ${source()?.record.problems.map(p => `${p.observation.sourceInstanceId}/${p.dataMode}`).join(', ')}`}</p></section></Show>
    <label>调整方式<select prop:value={kind()} disabled={editingDisabled()} onChange={e => { setKind((e.target as HTMLSelectElement).value as 'MERGE' | 'SPLIT'); setReview(null); setSelected([]); setTarget(null) }}>
      <option value="MERGE">合并到现有 Incident</option><option value="SPLIT">拆分到新 Incident</option></select></label>
    <Show when={kind() === 'MERGE'}><label>目标 Incident ID<ZwInput value={targetId()} disabled={editingDisabled()} onValueChange={v => edit(setTargetId, v)} /></label>
      <ZwButton variant="outline" disabled={disabled() || pending() || !uuid(targetId())} onPress={() => load('target')}>读取目标 Incident</ZwButton>
      <Show when={target()}><p data-reorganization-target>{`${target()?.record.incident.title} · ${target()?.record.incident.status} · 版本 ${target()?.record.incident.version} · 问题 ${target()?.record.problems.length}`}</p></Show>
    </Show>
    <Show when={kind() === 'SPLIT'}><label>新 Incident 标题<ZwInput value={title()} disabled={editingDisabled()} onValueChange={v => edit(setTitle, v)} /></label>
      <fieldset><legend>选择要移出的外部问题</legend><For each={source()?.record.problems ?? []}>{row => <label class="incident-link"><input type="checkbox" disabled={editingDisabled()}
        prop:checked={selected().some(k => keyOf(k) === `${forItem(row).observation.sourceInstanceId}:${forItem(row).observation.problemEventId}`)}
        onChange={e => toggle({ sourceInstanceId: forItem(row).observation.sourceInstanceId, problemEventId: forItem(row).observation.problemEventId }, (e.target as HTMLInputElement).checked)} />
        {`${forItem(row).observation.sourceInstanceId}:${forItem(row).observation.problemEventId} · ${forItem(row).observation.title} · ${forItem(row).dataMode}`}</label>}</For></fieldset>
    </Show>
    <label>人工调整原因<ZwInput value={reason()} disabled={editingDisabled()} onValueChange={v => edit(setReason, v)} /></label>
    <ZwButton variant="primary" disabled={disabled() || pending() || !source()} onPress={preview}>预览关联调整</ZwButton>
    <Show when={review()}><section data-reorganization-review><h3>待确认调整</h3><p>{`${review()?.kind} · 来源 ${review()?.sourceIncidentId}（版本 ${review()?.expectedSourceVersion}） → 目标 ${review()?.targetIncidentId}（版本 ${review()?.expectedTargetVersion}）`}</p>
      <p>{`移出问题：${review()?.kind === 'MERGE' ? '来源全部问题' : review()?.problemKeys.map(keyOf).join(', ')} · ${review()?.reason}`}</p>
      <p>这会改变问题的后续入库归属，并使变更前的诊断证据不可继续读取。不会宣告来源问题已恢复。</p>
      <ZwButton variant="primary" disabled={disabled()} onPress={save}>{pending() ? '重试同一关联请求' : '确认关联调整'}</ZwButton>
    </section></Show>
    <Show when={pending()}><p data-reorganization-pending>提交结果待确认。可以用相同请求标识重试，或先读取关联记录；刷新页面保留请求标识，Token 需要重新输入。</p></Show>
    <label>关联请求标识<ZwInput value={requestKey()} disabled={editingDisabled()} onValueChange={setRequestKey} /></label>
    <ZwButton variant="outline" disabled={disabled() || !uuid(requestKey())} onPress={readResult}>读取关联记录</ZwButton>
    <p role="alert">{error()}</p><Show when={busy()}><p role="status">正在请求…</p></Show>
    <Show when={receipt()}><section data-reorganization-result><h3>已保存关联调整</h3><p>{`${receipt()?.change.request.kind} · ${receipt()?.storage} · ${receipt()?.change.actor} · ${receipt()?.change.occurredAt}`}</p>
      <p>{`${receipt()?.change.request.sourceIncidentId}（版本 ${receipt()?.change.sourceVersion}） → ${receipt()?.change.request.targetIncidentId}（版本 ${receipt()?.change.targetVersion}）`}</p><p>{receipt()?.change.request.reason}</p>
      <p>{receipt()?.change.movedProblems.map(keyOf).join(', ')}</p><ZwButton variant="outline" disabled={disabled()} onPress={() => { const id = receipt()?.change.request.targetIncidentId; if (id) { setSourceId(id); setHistoryPage(null); load('source') } }}>读取调整后的目标</ZwButton>
    </section></Show>
    <Show when={historyPage()}><section data-reorganization-history><h3>关联调整历史</h3><p>{`${historyPage()?.storage} · 本页 ${historyPage()?.items.length} 条`}</p>
      <For each={historyPage()?.items ?? []}>{row => <article class="incident-problem"><p>{`${forItem(row).request.kind} · ${forItem(row).actor} · ${forItem(row).occurredAt}`}</p><p>{forItem(row).request.reason}</p>
        <p>{`${forItem(row).request.sourceIncidentId} → ${forItem(row).request.targetIncidentId}`}</p><p>{`请求 ${forItem(row).request.requestKey}`}</p></article>}</For>
      <ZwButton variant="outline" disabled={disabled() || !historyPage()?.nextCursor} onPress={() => history(true)}>下一页关联历史</ZwButton>
    </section></Show>
  </section>
}
