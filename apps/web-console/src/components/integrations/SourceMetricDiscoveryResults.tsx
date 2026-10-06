import type { MetricDiscovery } from '../../api/source-inspections.ts'
import { SourceMetricTable } from './SourceMetricTable.tsx'
export function SourceMetricDiscoveryResults({ value }: { value: MetricDiscovery }) {
  return <section aria-label="来源指标发现结果"><p>{value.statusCode === 'UNREACHABLE' ? '指标元数据读取失败' : value.complete ? '已覆盖本次授权可见的指标清单' : value.scanConsistency === 'UNVERIFIED' ? '两次读取内容有变化，请重新发现' : '仅展示首个分页，尚未覆盖全部指标'} · {value.items.length} 项，最多 {value.limit} 项</p>
    <SourceMetricTable items={value.items}/>
    {!value.items.length && value.statusCode !== 'UNREACHABLE' ? <p>本次授权范围内未发现指标。</p> : null}
    <details><summary>发现指纹</summary><code className="source-inspection-fingerprint">{value.fingerprint}</code></details>
  </section>
}
