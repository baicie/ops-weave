import { createEffect, createSignal, For, onCleanup, Show } from '@zeus-js/zeus'
import { usePlatformSession } from '../../state/platform-session.ts'
import { readProblemHistory, type ProblemHistoryPage, type ProblemObservation } from '../../api/problem-history.ts'
import { IncidentRequestError, type IncidentRecord } from '../../api/incidents.ts'
import { ZwButton } from '../../adapters/zeus-ui/ZwButton.tsx'
import { ZwInput } from '../../adapters/zeus-ui/ZwInput.tsx'
import { forItem } from '../../adapters/zeus-ui/for-item.ts'

export function ProblemHistory(props: { record: IncidentRecord; refresh: () => void }) {
  const [page, setPage] = createSignal<ProblemHistoryPage | null>(null), [error, setError] = createSignal(''), [busy, setBusy] = createSignal(false)
  const [days, setDays] = createSignal(7), [source, setSource] = createSignal(''), [eventId, setEventId] = createSignal(''), [stale, setStale] = createSignal(false)
  let controller: AbortController | undefined; let sequence = 0
  function clear() { controller?.abort(); ++sequence; setPage(null); setError(''); setBusy(false) }
  const authenticated = usePlatformSession(change => { clear(); setSource(''); setEventId(''); setDays(7); if (change.error) setError(change.error.message) })
  createEffect(() => { void props.record.incident.id; void props.record.incident.version; clear(); setStale(false) }); onCleanup(clear)
  async function load(next = false) {
    controller?.abort(); const active = new AbortController(); controller = active; const current = ++sequence
    const prior = page(), till = Math.floor(Date.now() / 1000)
    const query = next && prior ? { ...prior.query, after: prior.nextCursor } : { version: props.record.incident.version, from: till - days() * 86400, till, asOf: null, source: source(), eventId: eventId(), after: null, limit: 25 as const }
    setBusy(true); setError(''); setPage(null)
    try { const value = await readProblemHistory(props.record, query, active.signal); if (current === sequence) setPage(value) }
    catch (cause) {
      if (current === sequence) {
        const changed = cause instanceof IncidentRequestError && cause.status === 409; setStale(changed)
        setError(changed ? 'Incident 版本或归属已变化，请刷新当前详情后重新查询。' : cause instanceof Error ? cause.message : '告警观测历史不可用')
      }
    } finally { if (current === sequence) setBusy(false) }
  }
  return <section data-problem-history aria-label="告警观测历史">
    <h4>告警观测历史</h4>
    <p>逐次保留规范化来源输入，恢复信息可能与上方当前合并状态不同。资产映射为首次接收时的记录；历史跟随告警的当前 Incident 归属。</p>
    <p>仅显示已留存记录，按记录 ID 翻页。留存前历史和厂商原始报文未保存；空结果不能证明来源没有发生变化。</p>
    <div class="metric-controls"><label>告警观测范围<select prop:value={String(days())} onChange={event => { clear(); setDays(Number((event.target as HTMLSelectElement).value)) }}>
      <option value="1">最近 24 小时</option><option value="7">最近 7 天</option><option value="30">最近 30 天</option></select></label>
      <label>告警来源筛选<ZwInput placeholder="留空读取全部来源" value={source()} onValueChange={value => { clear(); setSource(value) }} /></label>
      <label>Problem Event ID 筛选<ZwInput placeholder="可选，需同时指定来源" value={eventId()} onValueChange={value => { clear(); setEventId(value) }} /></label></div>
    <Show when={eventId() !== '' && source() === ''}><p>按事件 ID 筛选时，请同时填写来源实例。</p></Show>
    <div class="actions"><ZwButton variant="outline" disabled={busy() || stale() || !authenticated() || (eventId() !== '' && source() === '')} onPress={() => { void load() }}>读取告警观测</ZwButton>
      <ZwButton variant="outline" disabled={busy() || stale() || !page()?.nextCursor} onPress={() => { void load(true) }}>下一页告警观测</ZwButton>
      <Show when={stale()}><ZwButton variant="outline" onPress={props.refresh}>刷新详情后重查</ZwButton></Show></div>
    <p data-problem-history-error>{error()}</p><Show when={busy()}><p>正在读取告警观测…</p></Show>
    <Show when={page()}><p data-problem-history-page>{`${page()?.storage} · 本页 ${page()?.items.length} 条 · Incident 版本 ${page()?.query.version} · 知识截止 ${page()?.query.asOf}`}</p>
      <Show when={page()?.items.length === 0}><p>该范围内没有可见的已留存告警观测。</p></Show>
      <For each={page()?.items ?? []}>{row => <ObservationCard item={forItem(row)} />}</For></Show>
  </section>
}
function ObservationCard(props: { item: ProblemObservation }) {
  const item = props.item, p = item.observation
  return <article class="incident-problem" data-problem-observation>
    <h5>{p.title}</h5><p>{`${p.state} · 严重度 ${p.severity} · ${p.suppressed ? '来源已抑制' : '来源未抑制'}`}</p>
    <p>{`${item.dataMode} · ${p.sourceInstanceId} / problem ${p.problemEventId} / trigger ${p.triggerId}`}</p>
    <p>{`发生 ${p.occurredAt} · 当次观测 ${p.observedAt} · 首次接收 ${item.firstReceivedAt}`}</p>
    <p>{`当次恢复事件 ${p.recoveryEventId ?? '无'} · 当次恢复时间 ${p.recoveredAt ?? '未知或未恢复'}`}</p>
    <Show when={item.gaps.length > 0}><p>首次接收时存在资产映射缺口。</p></Show>
    <For each={item.entities}>{row => <p><code>{`Host ${forItem(row).hostId} → ${forItem(row).entityId}`}</code></p>}</For>
    <details><summary>查看规范化字段与记录 ID</summary><pre>{JSON.stringify(item, null, 2)}</pre></details>
  </article>
}
