import { useEffect, useRef, useState } from 'react'
import { Button } from '@/components/ui/button'
import { usePlatformSession } from '../../state/platform-session.ts'
import { sourcePresence, type PresencePage } from '../../api/source-snapshots.ts'
import type { EntityItem } from '../../api/entities.ts'

export function SourcePresence(props: { entity: EntityItem }) {
  const [page, setPage] = useState<PresencePage | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const activeRef = useRef<AbortController | undefined>(undefined)
  const seqRef = useRef(0)

  function clear() { activeRef.current?.abort(); seqRef.current++; setPage(null); setBusy(false); setError('') }
  const ready = usePlatformSession(clear)
  useEffect(() => () => { clear() }, [])

  async function read() {
    clear(); const controller = new AbortController(); activeRef.current = controller; const current = seqRef.current; setBusy(true)
    try { const p = await sourcePresence(props.entity, controller.signal); if (seqRef.current === current) setPage(p) }
    catch (e) { if (seqRef.current === current) setError(e instanceof Error ? e.message : '来源状态不可用') }
    finally { if (seqRef.current === current) setBusy(false) }
  }
  return <section data-source-presence><h3>第二来源确认</h3><p>只读展示导入来源的最后确认；过期、缺失或登记已撤销均不能继续作为有效确认。字段选择仍需单独审核。</p>
    <Button variant="outline" disabled={busy || !ready} onClick={() => { void read() }}>读取来源状态</Button><p>{error}</p>
    {page ? <><p>{`判断时间 ${page.evaluatedAt} · import / postgres`}</p>
      {page.items.length === 0 ? <p>尚无第二来源快照，不能推断其他来源已确认或缺失。</p> : null}
      <ul>{page.items.map(row => <li key={`${row.sourceInstanceId}/${row.externalId}`}>{`${row.sourceInstanceId} / ${row.externalId} · ${row.status} · 观测 ${row.observedAt} · 到期 ${row.expiresAt}`}</li>)}</ul></> : null}
  </section>
}
