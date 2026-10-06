import { useEffect, useRef, useState } from 'react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { usePlatformSession } from '../../state/platform-session.ts'
import { readObservations, type ObservationPage, type Observation } from '../../api/observations.ts'

export function ObservationHistory(props: { entity: { id: string; tenantId: string } }) {
  const [page, setPage] = useState<ObservationPage | null>(null)
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const [days, setDays] = useState(7)
  const [source, setSource] = useState('')
  const controllerRef = useRef<AbortController | undefined>(undefined)
  const sequenceRef = useRef(0)

  function clear() { controllerRef.current?.abort(); ++sequenceRef.current; setPage(null); setError(''); setBusy(false) }
  const authenticated = usePlatformSession(change => { clear(); setSource(''); if (change.error) setError(change.error.message) })
  useEffect(() => {
    clear()
    return () => { clear() }
  }, [props.entity.id])

  async function load(next = false) {
    controllerRef.current?.abort(); const active = new AbortController(); controllerRef.current = active; const current = ++sequenceRef.current
    const prior = page; const till = Math.floor(Date.now() / 1000)
    const query = next && prior ? { ...prior.query, after: prior.nextCursor } : { from: till - days * 86400, till, asOf: null, source, after: null, limit: 25 as const }
    // Keep the last confirmed page visible while a refresh or next-page request is in flight.
    // A failed read must not erase evidence the operator already inspected.
    setBusy(true); setError('')
    try { const value = await readObservations(props.entity, query, active.signal); if (current === sequenceRef.current) setPage(value) }
    catch (cause) { if (current === sequenceRef.current) setError(cause instanceof Error ? cause.message : '观测历史不可用') }
    finally { if (current === sequenceRef.current) setBusy(false) }
  }
  return <section data-observation-history aria-label="观测历史">
    <h3>来源观测历史</h3>
    <p>仅显示已留存观测。按记录 ID 翻页；时间是来源观测与平台接收时间，不是完整来源事件日志。Raw 为追溯标识，不代表原文仍保留。</p>
    <div className="metric-controls"><label>观测范围<select value={String(days)} onChange={event => { clear(); setDays(Number(event.currentTarget.value)) }}>
      <option value="1">最近 24 小时</option><option value="7">最近 7 天</option><option value="30">最近 30 天</option></select></label>
      <label>来源实例筛选<Input value={source} onChange={event => { clear(); setSource(event.currentTarget.value) }} /></label></div>
    <div className="actions"><Button variant="outline" disabled={busy || !authenticated} onClick={() => { void load() }}>读取观测历史</Button>
      <Button variant="outline" disabled={busy || !page?.nextCursor} onClick={() => { void load(true) }}>下一页观测</Button></div>
    <p data-observation-error>{error}</p>{busy ? <p>正在读取观测…</p> : null}
    {page ? <><p data-observation-page>{`${page.storage} · 本页 ${page.items.length} 条 · 平台知识截止 ${page.query.asOf}`}</p>
      {page.items.length === 0 ? <p>该范围没有已留存观测；不能据此判定来源没有变化。</p> : null}
      {page.items.map(row => <ObservationCard key={row.id} item={row} />)}</> : null}
  </section>
}

function ObservationCard(props: { item: Observation }) {
  const item = props.item
  return <article className="panel" data-observation-record>
    <h4>{typeof item.fields.entityName === 'string' ? item.fields.entityName : '历史名称未记录'}</h4>
    <p>{`${item.sourceInstanceId} / ${item.externalType}:${item.externalId} / generation ${item.generation}`}</p>
    <p>{`模式 ${typeof item.fields.dataMode === 'string' ? item.fields.dataMode : '未记录'} · 映射修订 ${item.mappingRevision}`}</p>
    <p>{`观测 ${item.observedAt} · 接收 ${item.ingestedAt} · ${item.timePrecision === 'nanoseconds' ? '纳秒时间' : '旧记录，微秒精度'}`}</p>
    <p>{`Raw ${item.rawRecordRef} · 观测 ID ${item.id}`}</p>
    {item.gaps.length > 0 ? <p>{`记录缺口：${item.gaps.join('、')}`}</p> : null}
    <details><summary>查看规范化观测字段</summary><pre>{JSON.stringify(item.fields, null, 2)}</pre></details>
  </article>
}
