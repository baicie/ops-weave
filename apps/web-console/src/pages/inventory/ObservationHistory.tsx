import { createEffect, createSignal, For, onCleanup, Show } from '@zeus-js/zeus'
import { usePlatformSession } from '../../state/platform-session.ts'
import { readObservations, type ObservationPage, type Observation } from '../../api/observations.ts'
import { ZwButton } from '../../adapters/zeus-ui/ZwButton.tsx'
import { ZwInput } from '../../adapters/zeus-ui/ZwInput.tsx'
import { forItem } from '../../adapters/zeus-ui/for-item.ts'

export function ObservationHistory(props: { entity: { id: string; tenantId: string } }) {
  const [page, setPage] = createSignal<ObservationPage | null>(null), [error, setError] = createSignal(''), [busy, setBusy] = createSignal(false)
  const [days, setDays] = createSignal(7), [source, setSource] = createSignal('')
  let controller: AbortController | undefined; let sequence = 0
  function clear() { controller?.abort(); ++sequence; setPage(null); setError(''); setBusy(false) }
  const authenticated = usePlatformSession(change => { clear(); setSource(''); if (change.error) setError(change.error.message) })
  createEffect(() => { void props.entity.id; clear() }); onCleanup(clear)
  async function load(next = false) {
    controller?.abort(); const active = new AbortController(); controller = active; const current = ++sequence
    const prior = page(); const till = Math.floor(Date.now() / 1000)
    const query = next && prior ? { ...prior.query, after: prior.nextCursor } : { from: till - days() * 86400, till, asOf: null, source: source(), after: null, limit: 25 as const }
    setBusy(true); setError(''); setPage(null)
    try { const value = await readObservations(props.entity, query, active.signal); if (current === sequence) setPage(value) }
    catch (cause) { if (current === sequence) setError(cause instanceof Error ? cause.message : '观测历史不可用') }
    finally { if (current === sequence) setBusy(false) }
  }
  return <section data-observation-history aria-label="观测历史">
    <h3>来源观测历史</h3>
    <p>仅显示已留存观测。按记录 ID 翻页；时间是来源观测与平台接收时间，不是完整来源事件日志。Raw 为追溯标识，不代表原文仍保留。</p>
    <div class="metric-controls"><label>观测范围<select prop:value={String(days())} onChange={event => { clear(); setDays(Number((event.target as HTMLSelectElement).value)) }}>
      <option value="1">最近 24 小时</option><option value="7">最近 7 天</option><option value="30">最近 30 天</option></select></label>
      <label>来源实例筛选<ZwInput value={source()} onValueChange={value => { clear(); setSource(value) }} /></label></div>
    <div class="actions"><ZwButton variant="outline" disabled={busy() || !authenticated()} onPress={() => { void load() }}>读取观测历史</ZwButton>
      <ZwButton variant="outline" disabled={busy() || !page()?.nextCursor} onPress={() => { void load(true) }}>下一页观测</ZwButton></div>
    <p data-observation-error>{error()}</p><Show when={busy()}><p>正在读取观测…</p></Show>
    <Show when={page()}><p data-observation-page>{`${page()?.storage} · 本页 ${page()?.items.length} 条 · 平台知识截止 ${page()?.query.asOf}`}</p>
      <Show when={page()?.items.length === 0}><p>该范围没有已留存观测；不能据此判定来源没有变化。</p></Show>
      <For each={page()?.items ?? []}>{row => <ObservationCard item={forItem(row)} />}</For></Show>
  </section>
}
function ObservationCard(props: { item: Observation }) {
  const item = props.item
  return <article class="panel" data-observation-record>
    <h4>{typeof item.fields.entityName === 'string' ? item.fields.entityName : '历史名称未记录'}</h4>
    <p>{`${item.sourceInstanceId} / ${item.externalType}:${item.externalId} / generation ${item.generation}`}</p>
    <p>{`模式 ${typeof item.fields.dataMode === 'string' ? item.fields.dataMode : '未记录'} · 映射修订 ${item.mappingRevision}`}</p>
    <p>{`观测 ${item.observedAt} · 接收 ${item.ingestedAt} · ${item.timePrecision === 'nanoseconds' ? '纳秒时间' : '旧记录，微秒精度'}`}</p>
    <p>{`Raw ${item.rawRecordRef} · 观测 ID ${item.id}`}</p>
    <Show when={item.gaps.length > 0}><p>{`记录缺口：${item.gaps.join('、')}`}</p></Show>
    <details><summary>查看规范化观测字段</summary><pre>{JSON.stringify(item.fields, null, 2)}</pre></details>
  </article>
}
