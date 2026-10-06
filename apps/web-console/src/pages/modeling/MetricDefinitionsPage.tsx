import { useEffect, useRef, useState } from 'react'
import { readCatalog, type CatalogPage } from '../../api/model-catalog.ts'
import { usePlatformSession } from '../../state/platform-session.ts'
import { usePageActive } from '../../state/page-workspace.ts'
import { useViewLocation, useSelectionSessionReset } from '../../state/view-location.ts'
import { metricDefinitionHash, metricDefinitionSelection } from '../../state/metric-definition-selection.ts'
import { PageHeader, PageBody } from '../../components/PageLayout.tsx'
import { IntegrationSearchField } from '../../components/integrations/IntegrationSearchField.tsx'
import { MetricDefinitionDetail } from '../../components/modeling/MetricDefinitionDetail.tsx'
import { MetricDefinitionList } from '../../components/modeling/MetricDefinitionList.tsx'
import { useInitialPageRead } from '../../state/initial-page-read.ts'
import { Button } from '../../components/ui/button.tsx'
import { MetricMappingPanel } from '../../components/modeling/MetricMappingPanel.tsx'

export function MetricDefinitionsPage() {
  const [catalog, setCatalog] = useState<CatalogPage | null>(null), [key, setKey] = useState(''), [query, setQuery] = useState('')
  const [busy, setBusy] = useState(false), [error, setError] = useState(''), [linkError, setLinkError] = useState('')
  const request = useRef<AbortController | null>(null)
  const active = usePageActive(), selectionSession = useSelectionSessionReset()
  function cancel() { request.current?.abort(); request.current = null; setBusy(false) }
  const ready = usePlatformSession(change => {
    cancel(); setCatalog(null); setQuery(''); setError(change.error?.message ?? '')
    if (selectionSession.changed(change)) { setKey(''); setLinkError(''); view.write('', true) }
  })
  const view = useViewLocation('/modeling/metrics', metricDefinitionSelection, metricDefinitionHash,
    value => { setKey(value); setLinkError('') },
    () => { cancel(); setKey(''); setCatalog(null); setLinkError('指标定义链接无效，请返回目录后重新选择。') })
  async function load() {
    if (!ready || !active || request.current || linkError) return
    selectionSession.beginRead()
    const controller = new AbortController(); request.current = controller; setBusy(true); setError(''); setCatalog(null)
    try { const data = await readCatalog(controller.signal); if (!controller.signal.aborted && request.current === controller) setCatalog(data) }
    catch (cause) { if (!controller.signal.aborted && request.current === controller) setError(cause instanceof Error ? cause.message : '指标定义读取失败') }
    finally { if (request.current === controller) { request.current = null; setBusy(false) } }
  }
  useEffect(() => () => { request.current?.abort(); request.current = null }, [])
  useEffect(() => { if (!active) cancel() }, [active])
  let invalidAddress = false
  if (active) { try { metricDefinitionSelection(window.location.hash) } catch { invalidAddress = true } }
  useInitialPageRead({ ready, loaded: !!catalog, pending: busy, blocked: !!linkError || invalidAddress, read: () => void load() })
  const metric = catalog?.package.metrics.find(item => item.key === key)
  const shown = catalog?.package.metrics.filter(item => (item.key + ' ' + item.label + ' ' + item.sourceKey).toLowerCase().includes(query.trim().toLowerCase())) ?? []
  return <section className="model-center" data-page="model-catalog"><PageHeader title="指标定义" description="查看完整指标标识、来源字段与固定映射版本。" actions={<><MetricMappingPanel/><Button variant="outline" aria-label="刷新指标目录" disabled={!ready || busy || !active || !!linkError} onClick={() => void load()}>{busy ? '加载中…' : error ? '重试' : '刷新'}</Button></>} />
    <nav className="model-tabs" aria-label="模型类别"><a href="#/modeling/entities">实体类型</a><a href={metricDefinitionHash('')} aria-current="page">内置指标</a><a href="#/modeling/relations">关系类型</a></nav>
    <PageBody className="model-catalog-body"><p role="alert">{linkError || error}</p>
      {key ? <><a href={metricDefinitionHash('')}>← 返回指标目录</a><p className="metric-definition-requested">完整指标标识：<code>{key}</code></p></> : null}
      {!catalog && !error && !linkError ? <div className="model-empty" role="status"><h3>{busy ? '正在加载指标定义' : ready ? '准备加载指标定义' : '请先建立平台会话'}</h3></div> : null}
      {metric && catalog ? <MetricDefinitionDetail metric={metric} version={catalog.package.version} /> : key && catalog ? <p role="status">当前授权目录中没有此指标定义，未替换为其他指标。</p> : null}
      {!key && catalog ? <><IntegrationSearchField label="搜索指标定义" placeholder="搜索名称、完整指标键或来源键" value={query} onChange={setQuery} clearLabel="清除指标定义搜索" /><p className="model-muted">当前目录 {catalog.package.metrics.length} 项 · 筛选 {shown.length} 项 · 定义不代表已采集</p><MetricDefinitionList metrics={shown} /></> : null}
      {catalog ? <details className="model-foundation-details"><summary>默认清洗规则与来源版本说明</summary><p>固定目录 opsweave-core · v{catalog.package.version}</p><p>其他版本：尚未验证。不能仅凭版本号认定兼容。</p></details> : null}
    </PageBody></section>
}
