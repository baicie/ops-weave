import { useEffect, useMemo, useRef, useState, type FormEvent } from 'react'
import { PageBody, PageHeader } from '../../components/PageLayout.tsx'
import { readCatalog, type CatalogPage, type ModelDefinition } from '../../api/model-catalog.ts'
import { pageEntities, type EntityItem, type EntityPage } from '../../api/entities.ts'
import { createEntityRelation, pageEntityRelations, type EntityRelationPage } from '../../api/entity-relations.ts'
import { usePlatformSession } from '../../state/platform-session.ts'
import { usePageActive } from '../../state/page-workspace.ts'

type EntityDirectory = ReturnType<typeof useEntityDirectory>

export function RelationInstancesPage() {
  const active = usePageActive()
  const [catalog, setCatalog] = useState<CatalogPage | null>(null), [catalogError, setCatalogError] = useState('')
  const [selected, setSelected] = useState<EntityItem | null>(null), [relations, setRelations] = useState<EntityRelationPage | null>(null)
  const [busy, setBusy] = useState(false), [error, setError] = useState(''), [drawer, setDrawer] = useState(false)
  const request = useRef<AbortController | null>(null), catalogRequest = useRef<AbortController | null>(null), catalogStarted = useRef(false)
  const addButton = useRef<HTMLButtonElement>(null)
  const directoryRef = useRef<EntityDirectory | null>(null)
  const ready = usePlatformSession(() => {
    request.current?.abort(); catalogRequest.current?.abort(); catalogStarted.current = false
    directoryRef.current?.reset()
    setCatalog(null); setRelations(null); setSelected(null); setDrawer(false); setError(''); setCatalogError('')
  })
  const directory = useEntityDirectory(ready && active)
  directoryRef.current = directory
  const entities = ready ? directory.page : null

  function run(work: (signal: AbortSignal) => Promise<void>) {
    request.current?.abort(); const controller = new AbortController(); request.current = controller
    setBusy(true); setError('')
    void work(controller.signal).catch(e => { if (!controller.signal.aborted) setError(e instanceof Error ? e.message : '读取失败') })
      .finally(() => { if (request.current === controller) { request.current = null; setBusy(false) } })
  }
  useEffect(() => () => { request.current?.abort(); catalogRequest.current?.abort() }, [])

  useEffect(() => {
    if (!ready || !active) {
      catalogRequest.current?.abort()
      if (!catalog) catalogStarted.current = false
      return
    }
    if (catalog || catalogStarted.current) return
    catalogStarted.current = true
    const controller = new AbortController(); catalogRequest.current = controller
    setCatalogError('')
    void readCatalog(controller.signal).then(value => { if (!controller.signal.aborted) setCatalog(value) })
      .catch(e => { if (!controller.signal.aborted) setCatalogError(e instanceof Error ? e.message : '关系目录读取失败') })
      .finally(() => { if (catalogRequest.current === controller) catalogRequest.current = null })
    return () => controller.abort()
  }, [ready, active, catalog, catalogError])

  useEffect(() => { if (!selected && entities?.items.length) setSelected(entities.items[0]!) }, [entities, selected])
  useEffect(() => {
    if (!selected || !ready || !active) {
      request.current?.abort()
      setRelations(null)
      return
    }
    setRelations(null)
    run(async signal => { const next = await pageEntityRelations(selected.id, selected.tenantId, null, null, signal); if (!signal.aborted) setRelations(next) })
  }, [selected?.id, ready, active])

  const relationModels = useMemo(() => {
    if (!catalog) return []
    const all = [
      ...catalog.package.definitions.filter(d => d.kind === 'RELATION'),
      ...catalog.published.items.map(entry => entry.definition).filter(d => d.kind === 'RELATION'),
    ]
    const unique = new Map(all.map(model => [`${model.id}@${model.revision}`, model]))
    return [...unique.values()].sort((a, b) => a.label.localeCompare(b.label) || a.id.localeCompare(b.id) || a.revision - b.revision)
  }, [catalog])
  const loading = busy || directory.loading || (!catalog && !catalogError)
  return <section className="model-center relation-instance-page"><PageHeader title="关系实例" description="在已授权资产之间维护已发布关系类型的实例。拓扑图只负责浏览，这里负责受控创建。" actions={<button ref={addButton} type="button" className="button button-primary" disabled={!ready || !catalog} onClick={() => setDrawer(true)}>新建关系</button>} />
    <nav className="model-tabs" aria-label="模型类别"><a href="#/modeling/entities">实体类型</a><a href="#/modeling/metrics">内置指标</a><a href="#/modeling/relations">关系类型</a><a href="#/modeling/relation-instances" aria-current="page">关系实例</a></nav>
    <PageBody className="model-catalog-body">
      {error || catalogError || directory.error ? <p role="alert">{error || catalogError || directory.error}</p> : null}
      {!catalog && catalogError ? <button type="button" className="button button-quiet" onClick={() => { catalogStarted.current = false; setCatalogError(''); }}>重试读取关系目录</button> : null}
      {catalog?.published.truncated ? <p className="model-muted" role="status">已发布模型目录达到返回上限，部分自定义关系类型可能未显示。</p> : null}
      <div className="relation-instance-toolbar">
        <label htmlFor="relation-view-asset">查看端点资产<select id="relation-view-asset" value={selected?.id ?? ''} disabled={busy || !entities} onChange={event => setSelected(entities?.items.find(item => item.id === event.target.value) ?? (selected?.id === event.target.value ? selected : null) ?? null)}>
          {!selected ? <option value="">选择资产</option> : null}
          {selected && !entities?.items.some(item => item.id === selected.id) ? <option value={selected.id}>{selected.name} · {selected.entityType}</option> : null}
          {entities?.items.map(item => <option key={item.id} value={item.id}>{item.name} · {item.entityType}</option>)}
        </select></label>
        <span>{relations ? `当前时点 ${relations.items.length} 条关系` : '选择资产后读取关系'}</span>
      </div>
      <form className="model-search relation-entity-search" onSubmit={directory.search}>
        <label htmlFor="relation-asset-query">搜索授权资产<input id="relation-asset-query" value={directory.input} onChange={event => directory.setInput(event.target.value)} placeholder="按名称搜索" /></label>
        <button aria-label="搜索授权资产" className="button button-quiet" type="submit" disabled={directory.loading}>搜索</button>
      </form>
      <div className="model-search relation-entity-pages" aria-label="授权资产分页">
        <button aria-label="上一页授权资产" className="button button-quiet" type="button" disabled={!directory.hasPrevious || directory.loading} onClick={directory.previous}>上一页</button>
        <span role="status">{directory.loading ? '正在读取资产' : directory.page ? `本页 ${directory.page.items.length} 项${directory.page.nextCursor ? '，结果未完整' : '，已到末页'}` : '正在读取资产'}</span>
        <button aria-label="下一页授权资产" className="button button-quiet" type="button" disabled={!directory.hasNext || directory.loading} onClick={directory.next}>下一页</button>
      </div>
      {!ready ? <div className="model-empty"><h3>请先建立平台会话</h3></div> : null}
      {ready && !loading && entities && !entities.items.length ? <div className="model-empty"><h3>暂无匹配资产</h3><p>{entities.nextCursor ? '当前页为空但还有后续结果，可以继续翻页。' : '调整搜索条件后重试。'}</p></div> : null}
      {relations ? <RelationTable page={relations} busy={busy} onMore={() => {
        if (!selected || !relations.nextCursor) return
        const after = relations.nextCursor
        run(async signal => {
          const next = await pageEntityRelations(selected.id, selected.tenantId, after, relations.asOf, signal)
          if (!signal.aborted) setRelations(current => current?.entityId === next.entityId
            ? { ...next, items: [...current.items, ...next.items] }
            : next)
        })
      }} entities={[...(selected ? [selected] : []), ...(entities?.items ?? [])]} /> : null}
    </PageBody>
    {drawer && ready && active ? <RelationDrawer active={active} models={relationModels} publishedTruncated={catalog?.published.truncated ?? false} onClose={() => setDrawer(false)} onCreated={() => {
      setDrawer(false)
      if (selected) run(async signal => setRelations(await pageEntityRelations(selected.id, selected.tenantId, null, null, signal)))
    }} /> : null}
  </section>
}

function useEntityDirectory(enabled: boolean) {
  const [input, setInput] = useState(''), [query, setQuery] = useState(''), [trail, setTrail] = useState<(string | null)[]>([null])
  const [page, setPage] = useState<EntityPage | null>(null), [loading, setLoading] = useState(false), [error, setError] = useState(''), [reload, setReload] = useState(0)
  const current = trail[trail.length - 1] ?? null
  const active = useRef<AbortController | null>(null)
  useEffect(() => {
    active.current?.abort()
    if (!enabled) { setPage(null); setLoading(false); setError(''); setInput(''); setQuery(''); setTrail([null]); return }
    const controller = new AbortController(); active.current = controller
    setLoading(true); setError('')
    void pageEntities({ q: query, type: '', lifecycle: 'ACTIVE' }, current, controller.signal).then(value => {
      if (!controller.signal.aborted) setPage(value)
    }).catch(e => { if (!controller.signal.aborted) setError(e instanceof Error ? e.message : '资产读取失败') })
      .finally(() => { if (active.current === controller) { active.current = null; setLoading(false) } })
    return () => controller.abort()
  }, [enabled, query, current, reload])
  useEffect(() => () => active.current?.abort(), [])
  function searchNow() { const next = input.trim(); setTrail([null]); setQuery(next) }
  function search(event: FormEvent) { event.preventDefault(); searchNow() }
  function next() { if (!loading && page?.nextCursor) setTrail(values => [...values, page.nextCursor]) }
  function previous() { if (!loading && trail.length > 1) setTrail(values => values.slice(0, -1)) }
  function reset() { active.current?.abort(); setPage(null); setInput(''); setQuery(''); setTrail([null]); setLoading(false); setError(''); setReload(value => value + 1) }
  return { input, setInput, query, page, loading, error, search, searchNow, next, previous, reset, hasNext: !!page?.nextCursor, hasPrevious: trail.length > 1 }
}

function RelationTable(props: { page: EntityRelationPage; entities: EntityItem[]; busy: boolean; onMore: () => void }) {
  const name = (id: string) => props.entities.find(entity => entity.id === id)?.name ?? id
  return <div className="relation-instance-table"><table><caption>已保存关系实例</caption><thead><tr><th>起点</th><th>关系类型</th><th>终点</th><th>生效时间</th><th>来源</th></tr></thead><tbody>{props.page.items.map(item => <tr key={item.id}><td>{name(item.fromEntityId)}</td><td><code>{item.relationType}@{item.relationRevision}</code></td><td>{name(item.toEntityId)}</td><td>{new Date(item.validFrom).toLocaleString()}</td><td>{dataModeLabel(item.dataMode)}</td></tr>)}</tbody></table>{props.page.nextCursor ? <button type="button" className="button button-quiet" disabled={props.busy} onClick={props.onMore}>加载更多关系</button> : null}</div>
}

function dataModeLabel(mode: EntityRelationPage['items'][number]['dataMode']) {
  return ({ fixture: 'Fixture', 'zabbix-jsonrpc': 'Zabbix', import: '导入', unknown: '未知' } as const)[mode]
}

function normalizedType(value: string) { return value.toLowerCase().replace(/[^a-z0-9]/g, '') }
function endpointTypeCandidates(id: string | undefined) {
  if (!id) return new Set<string>()
  const suffix = id.replace(/^(?:builtin|custom)\./, '')
  const pascal = suffix.replace(/(^|[_-])([a-z0-9])/gi, (_match, _separator: string, char: string) => char.toUpperCase())
  return new Set([id, suffix, pascal].map(normalizedType))
}
function matchesEndpoint(entity: EntityItem, modelType: string | undefined) {
  const candidates = endpointTypeCandidates(modelType)
  return candidates.has(normalizedType(entity.entityType))
}

function EndpointPicker(props: { enabled: boolean; label: string; value: EntityItem | null; onChange: (entity: EntityItem | null) => void; modelType?: string }) {
  const directory = useEntityDirectory(props.enabled)
  const compatible = directory.page?.items.filter(entity => matchesEndpoint(entity, props.modelType)) ?? []
  const options = props.value && matchesEndpoint(props.value, props.modelType) && !compatible.some(entity => entity.id === props.value?.id)
    ? [props.value, ...compatible]
    : compatible
  const side = props.label === '起点资产' ? '起点' : '终点'
  return <div className="relation-endpoint-picker">
    <label htmlFor={`relation-${side}-asset`}>{props.label}
      <select id={`relation-${side}-asset`} value={props.value?.id ?? ''} onChange={event => props.onChange(options.find(entity => entity.id === event.target.value) ?? null)}>
        {!props.value ? <option value="">选择资产</option> : null}
        {options.map(entity => <option key={entity.id} value={entity.id}>{entity.name} · {entity.entityType}</option>)}
      </select>
    </label>
    <div className="model-search relation-entity-search" role="search">
      <label htmlFor={`relation-${side}-search`}>搜索{props.label}<input id={`relation-${side}-search`} value={directory.input} onChange={event => directory.setInput(event.target.value)} onKeyDown={event => { if (event.key === 'Enter') { event.preventDefault(); directory.searchNow() } }} placeholder="按名称搜索" /></label>
      <button type="button" aria-label={`搜索${props.label}`} className="button button-quiet" disabled={directory.loading} onClick={directory.searchNow}>搜索</button>
    </div>
    <div className="model-search relation-entity-pages" aria-label={`${props.label}分页`}>
      <button aria-label={`上一页${props.label}`} type="button" className="button button-quiet" disabled={!directory.hasPrevious || directory.loading} onClick={directory.previous}>上一页</button>
      <span role="status">{directory.error ? '资产读取失败' : directory.page ? directory.page.nextCursor
        ? `本页 ${compatible.length} 条符合条件，结果未完整`
        : compatible.length ? `本页 ${compatible.length} 条符合条件，已到末页` : '本页没有符合端点类型的资产；可搜索或翻页'
        : '正在读取资产'}</span>
      <button aria-label={`下一页${props.label}`} type="button" className="button button-quiet" disabled={!directory.hasNext || directory.loading} onClick={directory.next}>下一页</button>
    </div>
  </div>
}

function RelationDrawer(props: { active: boolean; models: ModelDefinition[]; publishedTruncated: boolean; onClose: () => void; onCreated: () => void }) {
  const [modelId, setModelId] = useState(props.models[0] ? `${props.models[0].id}@${props.models[0].revision}` : '')
  const [from, setFrom] = useState<EntityItem | null>(null), [to, setTo] = useState<EntityItem | null>(null)
  const [submitting, setSubmitting] = useState(false), [error, setError] = useState('')
  const panel = useRef<HTMLElement>(null), closeRef = useRef(props.onClose)
  const submittingRef = useRef(submitting)
  closeRef.current = props.onClose; submittingRef.current = submitting
  const model = props.models.find(item => `${item.id}@${item.revision}` === modelId)
  useEffect(() => {
    const previous = document.activeElement instanceof HTMLElement ? document.activeElement : null
    const element = panel.current
    element?.querySelector<HTMLElement>('[data-autofocus]')?.focus()
    function keyboard(event: KeyboardEvent) {
      if (event.key === 'Escape') {
        event.preventDefault()
        if (!submittingRef.current) closeRef.current()
        return
      }
      if (event.key !== 'Tab' || !element) return
      const focusable = [...element.querySelectorAll<HTMLElement>('button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), a[href], [tabindex]:not([tabindex="-1"])')]
        .filter(node => !node.hasAttribute('hidden') && node.getAttribute('aria-hidden') !== 'true')
      if (!focusable.length) { event.preventDefault(); element.focus(); return }
      const first = focusable[0]!, last = focusable[focusable.length - 1]!
      if (event.shiftKey && (document.activeElement === first || !element.contains(document.activeElement))) { event.preventDefault(); last.focus() }
      else if (!event.shiftKey && (document.activeElement === last || !element.contains(document.activeElement))) { event.preventDefault(); first.focus() }
    }
    document.addEventListener('keydown', keyboard, true)
    return () => { document.removeEventListener('keydown', keyboard, true); previous?.focus() }
  }, [])
  useEffect(() => {
    if (from && !matchesEndpoint(from, model?.endpoints?.from.id)) setFrom(null)
    if (to && !matchesEndpoint(to, model?.endpoints?.to.id)) setTo(null)
  }, [modelId])

  async function submit(event: FormEvent) {
    event.preventDefault()
    if (!model || !from || !to || from.id === to.id || !matchesEndpoint(from, model.endpoints?.from.id) || !matchesEndpoint(to, model.endpoints?.to.id)) {
      setError('请选择符合关系端点模型的两项不同资产。'); return
    }
    setSubmitting(true); setError('')
    try {
      const validFrom = new Date().toISOString()
      await createEntityRelation(from.id, from.tenantId, {
        requestId: crypto.randomUUID(), relationType: model.id, relationRevision: model.revision,
        fromEntityId: from.id, toEntityId: to.id, validFrom, validTo: null,
        expectedFromVersion: from.version, expectedToVersion: to.version, sourceRef: 'operator', dataMode: 'unknown',
      }, new AbortController().signal)
      props.onCreated()
    } catch (e) { setError(e instanceof Error ? e.message : '关系创建失败') }
    finally { setSubmitting(false) }
  }

  return <div className="relation-drawer-backdrop" role="presentation" onMouseDown={event => { if (event.currentTarget === event.target && !submitting) props.onClose() }}>
    <aside ref={panel} className="relation-drawer" role="dialog" aria-modal="true" aria-labelledby="relation-drawer-title" tabIndex={-1}>
      <header><div><p className="eyebrow">关系实例</p><h3 id="relation-drawer-title">新建关系</h3></div><button type="button" className="icon-button" aria-label="关闭关系抽屉" disabled={submitting} onClick={props.onClose}>×</button></header>
      <form onSubmit={submit}>
        <p className="model-muted">只允许使用已发布关系类型，服务器会再次校验模型端点和资产版本。</p>
        {props.publishedTruncated ? <p className="model-muted" role="status">已发布模型目录达到返回上限，部分较早的自定义关系类型可能未显示。</p> : null}
        <label>关系类型<select data-autofocus value={modelId} onChange={event => setModelId(event.target.value)}>{props.models.map(item => <option key={`${item.id}@${item.revision}`} value={`${item.id}@${item.revision}`}>{item.label} · {item.id}@{item.revision}</option>)}</select></label>
        {model ? <>
          <EndpointPicker enabled={props.active} label="起点资产" value={from} onChange={setFrom} modelType={model.endpoints?.from.id} />
          <EndpointPicker enabled={props.active} label="终点资产" value={to} onChange={setTo} modelType={model.endpoints?.to.id} />
        </> : <p className="model-muted">当前目录没有可用的已发布关系类型。</p>}
        {error ? <p role="alert">{error}</p> : null}
        <footer><button type="button" className="button button-quiet" disabled={submitting} onClick={props.onClose}>取消</button><button type="submit" className="button button-primary" disabled={submitting || !model || !from || !to || from.id === to.id}>{submitting ? '提交中…' : '创建关系'}</button></footer>
      </form>
    </aside>
  </div>
}
