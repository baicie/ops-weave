import { useEffect, useRef, useState } from 'react'
import { readCatalog, type CatalogMetric } from '../../api/model-catalog.ts'
import { usePlatformSession } from '../../state/platform-session.ts'
import { usePageActive } from '../../state/page-workspace.ts'
import { IntegrationSearchField } from '../../components/integrations/IntegrationSearchField.tsx'
import { Button } from '../../components/ui/button.tsx'
import { metricDefinitionHash } from '../../state/metric-definition-selection.ts'
import { useInitialPageRead } from '../../state/initial-page-read.ts'

/** Read the authorized catalog once when its drawer tab is visible. */
export function SourceMetricCatalog({ enabled }: { enabled: boolean }) {
  const [metrics, setMetrics] = useState<CatalogMetric[] | null>(null)
  const [version, setVersion] = useState('')
  const [query, setQuery] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const request = useRef<AbortController | null>(null)
  const active = usePageActive()
  const ready = usePlatformSession(() => { request.current?.abort(); request.current = null; setMetrics(null); setVersion(''); setQuery(''); setBusy(false); setError('') })
  useEffect(() => () => { request.current?.abort(); request.current = null }, [])
  useEffect(() => { if (!active || !enabled) { request.current?.abort(); request.current = null; setBusy(false) } }, [active, enabled])
  async function load() {
    if (!ready || !active || !enabled || busy) return
    const controller = new AbortController(); request.current?.abort(); request.current = controller
    setBusy(true); setError(''); setMetrics(null); setVersion('')
    try {
      const result = await readCatalog(controller.signal)
      if (!controller.signal.aborted && request.current === controller) { setMetrics(result.package.metrics); setVersion(result.package.version) }
    } catch (e) { if (!controller.signal.aborted && request.current === controller) setError(e instanceof Error ? e.message : '指标目录读取失败') }
    finally { if (request.current === controller) setBusy(false) }
  }
  useInitialPageRead({ ready, loaded: metrics !== null, pending: busy, blocked: !enabled, read: () => void load() })
  const shown = metrics?.filter(metric => (metric.label + ' ' + metric.key + ' ' + metric.sourceKey + ' ' + metric.unit).toLowerCase().includes(query.trim().toLowerCase())) ?? []
  return <section className="source-metric-catalog source-reference" aria-label="监控指标参考">
    <header><div><h4>监控指标</h4><p>查看平台已有的指标含义、单位与来源字段映射。</p></div><Button type="button" variant="outline" disabled={!ready || busy || !active} onClick={() => void load()}>{busy ? '正在读取…' : metrics ? '刷新指标目录' : '读取指标目录'}</Button></header>
    <p className="source-capability-note">这是版本化映射目录，不表示这份接入已经启用指标采集。Zabbix 主机工作流只处理实体；JSON 指标输出当前支持有界预览。</p>
    <div className="source-reference-search"><IntegrationSearchField label="搜索监控指标" placeholder="搜索指标名称、来源键或单位" value={query} onChange={setQuery} clearLabel="清除指标搜索" /></div>
    {!metrics ? <div className="source-reference-empty"><strong>{busy ? '正在读取当前授权目录' : error ? '指标目录未读取成功' : '等待授权目录'}</strong><p>{error ? '可使用上方按钮重试读取。' : '会话就绪后自动读取。'}</p></div> : <><p className="source-reference-note">opsweave-core · v{version} · {query ? '筛选结果' : '当前目录'} {shown.length} 项</p><div className="source-reference-table" tabIndex={0} role="region" aria-label="监控指标表"><table><thead><tr><th>指标名称 / 标识</th><th>来源字段</th><th>单位 / 类型</th><th>转换与映射</th></tr></thead><tbody>{shown.map(metric => <tr key={metric.key}><td><strong>{metric.label}</strong><a href={metricDefinitionHash(metric.key)}><code>{metric.key}</code></a></td><td><code>{metric.sourceKey}</code></td><td>{metric.unit}<small>{metric.kind}</small></td><td><span>{metric.transform}</span><small>{metric.mapping}</small></td></tr>)}</tbody></table>{!shown.length ? <p className="source-reference-empty">{metrics.length ? '没有匹配的指标，请调整搜索。' : '平台返回的指标目录为空。'}</p> : null}</div></>}
    {error ? <p role="alert">{error}</p> : null}
    <p className="source-reference-note">目录返回的指标均完整展示，点击完整标识查看具体定义；实际绑定、来源状态和采样数据请到“指标”页面读取。</p>
  </section>
}
