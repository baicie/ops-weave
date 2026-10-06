import type { MetricPage } from '../../api/source-inspections.ts'
import { Button } from '../ui/button.tsx'
import { SourceMetricTable } from './SourceMetricTable.tsx'

export function SourceMetricPageResults(p: { value: MetricPage; previousId: string | null; busy: boolean; canNext: boolean; previous: () => void; next: () => void }) {
  const v = p.value, m = v.manifest
  const failed = { MEMBERSHIP_CHANGED: '来源指标清单已变化，请重新发现。', CAPACITY: '来源指标超过 1000 项发现上限。', UNREACHABLE: '指标元数据读取失败。' }
  return <section aria-label="来源指标分页发现">
    <p>{v.statusCode === 'READ_VERIFIED' ? v.complete ? '已覆盖本次授权可见的指标清单' : '尚未覆盖全部指标' : failed[v.statusCode]}</p>
    <SourceMetricTable items={v.items}/>
    {v.statusCode === 'READ_VERIFIED' && !v.items.length ? <p>本次授权范围内未发现指标。</p> : null}
    <nav className="source-inspection-actions" aria-label="指标清单分页">
      <Button type="button" variant="outline" disabled={p.busy || !p.previousId} onClick={p.previous}>上一页指标</Button>
      <span aria-live="polite">{m ? v.items.length ? `${v.offset + 1}–${v.offset + v.items.length} / ${m.total} 项` : `本页未读取 · 清单 ${m.total} 项` : '清单未确认'}</span>
      <Button type="button" variant="outline" disabled={p.busy || !p.canNext || v.nextOffset === null} onClick={p.next}>下一页指标</Button>
    </nav>
    <details><summary>发现指纹</summary><code className="source-inspection-fingerprint">{v.fingerprint}</code>{m ? <code className="source-inspection-fingerprint">{m.fingerprint}</code> : null}</details>
  </section>
}
