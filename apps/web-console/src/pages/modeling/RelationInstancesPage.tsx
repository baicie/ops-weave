import { useEffect, useMemo, useRef, useState, type FormEvent } from 'react'
import { PageBody, PageHeader } from '../../components/PageLayout.tsx'
import { readCatalog, type CatalogPage, type ModelDefinition } from '../../api/model-catalog.ts'
import { pageEntities, type EntityItem, type EntityPage } from '../../api/entities.ts'
import {
  closeEntityRelation, confirmEntityRelationSource, correctEntityRelation, createEntityRelation,
  pageEntityRelationHistory, pageEntityRelations, readEntityRelationConfirmations, withdrawEntityRelationSourceConfirmation,
  type EntityRelation, type EntityRelationCloseCommand, type EntityRelationConfirmationCommand,
  type EntityRelationConfirmationEntry, type EntityRelationConfirmationView, type EntityRelationConfirmationWithdrawCommand,
  type EntityRelationCorrectionCommand, type EntityRelationHistoryEntry,
  type EntityRelationHistoryPage, type EntityRelationPage,
} from '../../api/entity-relations.ts'
import { registerExternalLink, type ExternalLinkRegistrationCommand, type ExternalLinkRegistrationReceipt } from '../../api/external-links.ts'
import { usePlatformSession } from '../../state/platform-session.ts'
import { usePageActive } from '../../state/page-workspace.ts'

type EntityDirectory = ReturnType<typeof useEntityDirectory>
type RelationRow = EntityRelationHistoryEntry

export function RelationInstancesPage() {
  const active = usePageActive()
  const [catalog, setCatalog] = useState<CatalogPage | null>(null), [catalogError, setCatalogError] = useState('')
  const [selected, setSelected] = useState<EntityItem | null>(null), [relations, setRelations] = useState<EntityRelationPage | null>(null)
  const [busy, setBusy] = useState(false), [error, setError] = useState(''), [drawer, setDrawer] = useState(false)
  const [registering, setRegistering] = useState(false)
  const [view, setView] = useState<'current' | 'history'>('current'), [history, setHistory] = useState<EntityRelationHistoryPage | null>(null)
  const [detail, setDetail] = useState<RelationRow | null>(null), [closing, setClosing] = useState<EntityRelation | null>(null)
  const [correcting, setCorrecting] = useState<EntityRelation | null>(null)
  const [confirming, setConfirming] = useState<{ relation: EntityRelation; preset?: EntityRelationConfirmationEntry } | null>(null)
  const [confirmations, setConfirmations] = useState<EntityRelationConfirmationView | null>(null), [confirmationsError, setConfirmationsError] = useState('')
  const request = useRef<AbortController | null>(null), catalogRequest = useRef<AbortController | null>(null), catalogStarted = useRef(false)
  const confirmationsRequest = useRef<AbortController | null>(null)
  const addButton = useRef<HTMLButtonElement>(null)
  const directoryRef = useRef<EntityDirectory | null>(null)
  const ready = usePlatformSession(() => {
    request.current?.abort(); catalogRequest.current?.abort(); catalogStarted.current = false
    confirmationsRequest.current?.abort()
    directoryRef.current?.reset()
    setCatalog(null); setRelations(null); setHistory(null); setSelected(null); setDrawer(false); setDetail(null); setClosing(null)
    setCorrecting(null); setConfirming(null); setConfirmations(null); setConfirmationsError('')
    setView('current'); setError(''); setCatalogError(''); setRegistering(false)
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
  useEffect(() => () => { request.current?.abort(); catalogRequest.current?.abort(); confirmationsRequest.current?.abort() }, [])

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
      setRelations(null); setHistory(null)
      return
    }
    setRelations(null); setHistory(null)
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

  function loadHistory(after: string | null) {
    if (!selected) return
    run(async signal => {
      const next = await pageEntityRelationHistory(selected.id, selected.tenantId, after, signal)
      if (signal.aborted) return
      setHistory(current => after && current?.entityId === next.entityId
        ? { ...next, items: [...current.items, ...next.items] }
        : next)
    })
  }
  function loadConfirmations(relationId: string) {
    if (!selected || !ready) return
    confirmationsRequest.current?.abort()
    const controller = new AbortController(); confirmationsRequest.current = controller
    setConfirmationsError('')
    void readEntityRelationConfirmations(selected.id, relationId, selected.tenantId, controller.signal)
      .then(value => { if (!controller.signal.aborted) setConfirmations(value) })
      .catch(cause => { if (!controller.signal.aborted) { setConfirmations(null); setConfirmationsError(cause instanceof Error ? cause.message : '确认视图读取失败') } })
      .finally(() => { if (confirmationsRequest.current === controller) confirmationsRequest.current = null })
  }
  useEffect(() => {
    if (!detail || !ready || !active) {
      confirmationsRequest.current?.abort()
      setConfirmations(null); setConfirmationsError('')
      return
    }
    loadConfirmations(detail.relation.id)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [detail?.relation.id, ready, active])
  function switchView(next: 'current' | 'history') {
    setView(next)
    if (next === 'history' && !history && selected && !busy) loadHistory(null)
  }
  function refreshAll() {
    if (!selected) return
    setHistory(null)
    if (detail) loadConfirmations(detail.relation.id)
    if (view === 'history') { loadHistory(null); return }
    run(async signal => {
      const next = await pageEntityRelations(selected.id, selected.tenantId, null, null, signal)
      if (!signal.aborted) setRelations(next)
    })
  }

  const loading = busy || directory.loading || (!catalog && !catalogError)
  const rows: RelationRow[] = view === 'history' ? history?.items ?? [] : relations?.items.map(relation => ({ relation, closeReason: null })) ?? []
  const nextCursor = view === 'history' ? history?.nextCursor ?? null : relations?.nextCursor ?? null
  return <section className="model-center relation-instance-page"><PageHeader title="关系实例" description="在已授权资产之间维护已发布关系类型的实例。拓扑图只负责浏览，这里负责受控创建、终止与历史追溯。" actions={<><button ref={addButton} type="button" className="button button-primary" disabled={!ready || !catalog} onClick={() => setDrawer(true)}>新建关系</button><button type="button" className="button button-quiet" disabled={!ready} onClick={() => setRegistering(true)}>登记外部链接</button></>} />
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
        <span className="relation-view-tabs" role="group" aria-label="关系视图">
          <button type="button" className="button button-quiet" aria-pressed={view === 'current'} onClick={() => switchView('current')}>当前关系</button>
          <button type="button" className="button button-quiet" aria-pressed={view === 'history'} onClick={() => switchView('history')}>维护历史</button>
        </span>
        {view === 'history' && !history && !busy ? <button type="button" className="button button-quiet" onClick={() => loadHistory(null)}>重新读取关系历史</button> : null}
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
      {view === 'current' && relations ? <RelationTable caption="已保存关系实例" rows={rows} nextCursor={nextCursor} busy={busy} onMore={() => {
        if (!selected || !relations.nextCursor) return
        const after = relations.nextCursor
        run(async signal => {
          const next = await pageEntityRelations(selected.id, selected.tenantId, after, relations.asOf, signal)
          if (!signal.aborted) setRelations(current => current?.entityId === next.entityId
            ? { ...next, items: [...current.items, ...next.items] }
            : next)
        })
      }} entities={[...(selected ? [selected] : []), ...(entities?.items ?? [])]} onDetail={setDetail} onCorrect={setCorrecting} onClose={setClosing} /> : null}
      {view === 'history' && history ? <RelationTable caption="关系维护历史" rows={rows} nextCursor={nextCursor} busy={busy} onMore={() => {
        if (!selected || !history.nextCursor) return
        loadHistory(history.nextCursor)
      }} entities={[...(selected ? [selected] : []), ...(entities?.items ?? [])]} onDetail={setDetail} onCorrect={setCorrecting} onClose={setClosing} /> : null}
    </PageBody>
    {drawer && ready && active ? <RelationDrawer active={active} models={relationModels} publishedTruncated={catalog?.published.truncated ?? false} onClose={() => setDrawer(false)} onCreated={() => {
      setDrawer(false)
      refreshAll()
    }} /> : null}
    {detail ? <RelationDetailDrawer row={detail} entities={[...(selected ? [selected] : []), ...(entities?.items ?? [])]} confirmations={confirmations} confirmationsError={confirmationsError}
      onCorrect={() => setCorrecting(detail.relation)} onConfirm={() => setConfirming({ relation: detail.relation })}
      onWithdraw={entry => setConfirming({ relation: detail.relation, preset: entry })}
      onRetryConfirmations={() => loadConfirmations(detail.relation.id)} onClose={() => setDetail(null)} /> : null}
    {closing && selected && ready ? <RelationCloseDrawer relation={closing} entityId={selected.id} tenantId={selected.tenantId} onClose={() => setClosing(null)} onClosed={() => { setClosing(null); refreshAll() }} /> : null}
    {correcting && selected && ready && active ? <RelationCorrectDrawer relation={correcting} entityId={selected.id} tenantId={selected.tenantId} models={relationModels}
      entities={[...(selected ? [selected] : []), ...(entities?.items ?? [])]} onClose={() => setCorrecting(null)} onCorrected={() => { setCorrecting(null); setDetail(null); refreshAll() }} /> : null}
    {confirming && selected && ready && active ? <RelationConfirmDrawer relation={confirming.relation} preset={confirming.preset ?? null} entityId={selected.id} tenantId={selected.tenantId}
      onClose={() => setConfirming(null)} onChanged={() => { setConfirming(null); refreshAll() }} /> : null}
    {registering && ready && active ? <ExternalLinkRegistrationDrawer active={active} entities={entities ? [...(selected ? [selected] : []), ...entities.items] : (selected ? [selected] : [])} defaultNamespace="workflow." onClose={() => setRegistering(false)} onRegistered={() => { setRegistering(false); refreshAll() }} /> : null}
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

function RelationTable(props: { caption: string; rows: RelationRow[]; nextCursor: string | null; entities: EntityItem[]; busy: boolean; onMore: () => void; onDetail: (row: RelationRow) => void; onCorrect: (relation: EntityRelation) => void; onClose: (relation: EntityRelation) => void }) {
  const name = (id: string) => props.entities.find(entity => entity.id === id)?.name ?? id
  return <div className="relation-instance-table"><table><caption>{props.caption}</caption><thead><tr><th>起点</th><th>关系类型</th><th>终点</th><th>生效时间</th><th>来源</th><th>状态</th><th>操作</th></tr></thead><tbody>{props.rows.map(row => {
    const item = row.relation
    const terminated = item.validTo !== null && Date.parse(item.validTo) <= Date.now()
    return <tr key={item.id}>
      <td>{name(item.fromEntityId)}</td>
      <td><code>{item.relationType}@{item.relationRevision}</code></td>
      <td>{name(item.toEntityId)}</td>
      <td>{new Date(item.validFrom).toLocaleString()}</td>
      <td>{dataModeLabel(item.dataMode)}</td>
      <td>{terminated ? '已终止' : '生效中'}</td>
      <td>
        <button type="button" className="button button-quiet" onClick={() => props.onDetail(row)}>详情</button>
        {terminated ? null : <button type="button" className="button button-quiet" onClick={() => props.onCorrect(item)}>更正关系</button>}
        {terminated ? null : <button type="button" className="button button-quiet" onClick={() => props.onClose(item)}>终止关系</button>}
      </td>
    </tr>
  })}</tbody></table>{props.nextCursor ? <button type="button" className="button button-quiet" disabled={props.busy} onClick={props.onMore}>{props.caption === '关系维护历史' ? '加载更多历史' : '加载更多关系'}</button> : null}</div>
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

function useDialogKeyboard(onClose: () => void, locked: boolean) {
  const panel = useRef<HTMLElement>(null)
  const closeRef = useRef(onClose), lockedRef = useRef(locked)
  closeRef.current = onClose; lockedRef.current = locked
  useEffect(() => {
    const previous = document.activeElement instanceof HTMLElement ? document.activeElement : null
    const element = panel.current
    element?.querySelector<HTMLElement>('[data-autofocus]')?.focus()
    function keyboard(event: KeyboardEvent) {
      if (event.key === 'Escape') {
        event.preventDefault()
        if (!lockedRef.current) closeRef.current()
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
  return panel
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
  const submittingRef = useRef(submitting)
  submittingRef.current = submitting
  const panel = useDialogKeyboard(props.onClose, submitting)
  const model = props.models.find(item => `${item.id}@${item.revision}` === modelId)
  useEffect(() => {
    if (from && !matchesEndpoint(from, model?.endpoints?.from.id)) setFrom(null)
    if (to && !matchesEndpoint(to, model?.endpoints?.to.id)) setTo(null)
  }, [modelId])

  async function submit(event: FormEvent) {
    event.preventDefault()
    if (!model || !from || !to || from.id === to.id || !matchesEndpoint(from, model.endpoints?.from.id) || !matchesEndpoint(to, model.endpoints?.to.id)) {
      setError('请选择符合关系端点模型的不同资产'); return
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
        <footer><button type="button" className="button button-quiet" disabled={submitting} onClick={props.onClose}>取消</button><button type="submit" className="button button-primary" disabled={submitting || !model || !from || !to || from.id === to.id}>{submitting ? '提交中' : '创建关系'}</button></footer>
      </form>
    </aside>
  </div>
}

function RelationDetailDrawer(props: {
  row: RelationRow; entities: EntityItem[]; confirmations: EntityRelationConfirmationView | null; confirmationsError: string
  onCorrect: () => void; onConfirm: () => void; onWithdraw: (entry: EntityRelationConfirmationEntry) => void
  onRetryConfirmations: () => void; onClose: () => void
}) {
  const panel = useDialogKeyboard(props.onClose, false)
  const item = props.row.relation
  const name = (id: string) => props.entities.find(entity => entity.id === id)?.name ?? id
  const terminated = item.validTo !== null && Date.parse(item.validTo) <= Date.now()
  return <div className="relation-drawer-backdrop" role="presentation" onMouseDown={event => { if (event.currentTarget === event.target) props.onClose() }}>
    <aside ref={panel} className="relation-drawer" role="dialog" aria-modal="true" aria-labelledby="relation-detail-title" tabIndex={-1}>
      <header><div><p className="eyebrow">关系实例</p><h3 id="relation-detail-title">关系详情</h3></div><button type="button" className="icon-button" aria-label="关闭关系详情" onClick={props.onClose}>×</button></header>
      <dl className="relation-detail-list">
        <div><dt>关系标识</dt><dd><code>{item.id}</code></dd></div>
        <div><dt>关系类型</dt><dd><code>{item.relationType}@{item.relationRevision}</code></dd></div>
        <div><dt>起点</dt><dd>{name(item.fromEntityId)}</dd></div>
        <div><dt>终点</dt><dd>{name(item.toEntityId)}</dd></div>
        <div><dt>生效时间</dt><dd>{new Date(item.validFrom).toLocaleString()}</dd></div>
        <div><dt>终止时间</dt><dd>{item.validTo ? new Date(item.validTo).toLocaleString() : '未终止'}</dd></div>
        <div><dt>状态</dt><dd>{terminated ? '已终止' : '生效中'}</dd></div>
        <div><dt>来源</dt><dd>{dataModeLabel(item.dataMode)}</dd></div>
        <div><dt>对象版本</dt><dd>{item.version}</dd></div>
        <div><dt>终止理由</dt><dd>{props.row.closeReason ?? '—'}</dd></div>
      </dl>
      <section className="relation-confirmations" aria-labelledby="relation-confirmations-title">
        <h4 id="relation-confirmations-title">多来源确认</h4>
        {props.confirmationsError ? <><p role="alert">{props.confirmationsError}</p><button type="button" className="button button-quiet" onClick={props.onRetryConfirmations}>重新读取确认视图</button></>
          : props.confirmations ? <>
            <p className="model-muted">{props.confirmations.relationState === 'ACTIVE' ? '关系生效中' : '关系已终止'} · 确认条目 {props.confirmations.entries.length} 条；单一来源失效只撤回自己的确认。</p>
            {props.confirmations.entries.length === 0 ? <p className="model-muted">尚无确认条目。</p> : <div className="relation-instance-table"><table><caption>确认条目</caption><thead><tr><th>类别</th><th>来源标识</th><th>批次标识</th><th>数据模式</th><th>状态</th><th>确认时间</th><th>撤回理由</th><th>操作</th></tr></thead><tbody>
              {props.confirmations.entries.map(entry => <tr key={`${entry.sourceKind}|${entry.sourceKey}|${entry.batchRef}`}>
                <td>{entry.sourceKind === 'manual' ? '人工断言' : '来源确认'}</td>
                <td><code>{entry.sourceKey}</code></td>
                <td><code>{entry.batchRef}</code></td>
                <td>{dataModeLabel(entry.dataMode)}</td>
                <td>{entry.state === 'CONFIRMED' ? '仍确认' : '已撤回'}</td>
                <td>{new Date(entry.confirmedAt).toLocaleString()}</td>
                <td>{entry.withdrawReason ?? '—'}</td>
                <td>{entry.state === 'CONFIRMED' && props.confirmations?.relationState === 'ACTIVE'
                  ? <button type="button" className="button button-quiet" onClick={() => props.onWithdraw(entry)}>撤回</button> : null}</td>
              </tr>)}
            </tbody></table></div>}
          </> : <p role="status">正在读取确认视图</p>}
      </section>
      <footer>
        {terminated ? null : <button type="button" className="button button-quiet" onClick={props.onConfirm}>登记来源确认</button>}
        {terminated ? null : <button type="button" className="button button-quiet" onClick={props.onCorrect}>更正关系</button>}
        <button data-autofocus type="button" className="button button-primary" onClick={props.onClose}>关闭</button>
      </footer>
    </aside>
  </div>
}

function errorMessage(cause: unknown): string {
  if (cause instanceof Error) return cause.message
  return typeof cause === 'string' ? cause : '关系终止失败'
}

function hasDefiniteHttpStatus(cause: unknown): boolean {
  if (!cause || typeof cause !== 'object' || !('status' in cause)) return false
  const status = (cause as { status?: unknown }).status
  return typeof status === 'number' && Number.isInteger(status) && status >= 400 && status < 500 && status !== 408
}

function RelationCloseDrawer(props: { relation: EntityRelation; entityId: string; tenantId: string; onClose: () => void; onClosed: () => void }) {
  const [reason, setReason] = useState(''), [when, setWhen] = useState('')
  const [submitting, setSubmitting] = useState(false), [error, setError] = useState('')
  const [pending, setPending] = useState<EntityRelationCloseCommand | null>(null)
  const abort = useRef<AbortController | null>(null)
  const panel = useDialogKeyboard(props.onClose, submitting || pending !== null)

  useEffect(() => () => abort.current?.abort(), [])

  async function send(command: EntityRelationCloseCommand) {
    if (submitting) return
    const controller = new AbortController()
    abort.current?.abort(); abort.current = controller
    setSubmitting(true); setError('')
    try {
      const receipt = await closeEntityRelation(props.entityId, props.tenantId, command, controller.signal)
      if (receipt.relation.id !== command.relationId) throw new Error('关系终止回执与本次请求不一致')
      setPending(null)
      props.onClosed()
    } catch (cause) {
      const message = errorMessage(cause)
      if (hasDefiniteHttpStatus(cause)) {
        setPending(null)
        setError(message)
      } else {
        setPending(command)
        setError(message)
      }
    } finally {
      setSubmitting(false)
    }
  }

  function submit(event: FormEvent) {
    event.preventDefault()
    const trimmed = reason.trim()
    if (!trimmed) { setError('请填写终止理由'); return }
    const validTo = when ? new Date(when).toISOString() : null
    void send({ requestId: crypto.randomUUID(), relationId: props.relation.id, reason: trimmed, validTo, expectedVersion: props.relation.version })
  }

  return <div className="relation-drawer-backdrop" role="presentation" onMouseDown={event => { if (event.currentTarget === event.target && !submitting && !pending) props.onClose() }}>
    <aside ref={panel} className="relation-drawer" role="dialog" aria-modal="true" aria-labelledby="relation-close-title" tabIndex={-1}>
      <header><div><p className="eyebrow">关系实例</p><h3 id="relation-close-title">终止关系</h3></div><button type="button" className="icon-button" aria-label="关闭终止抽屉" disabled={submitting || pending !== null} onClick={props.onClose}>×</button></header>
      <form onSubmit={submit}>
        <p className="model-muted">终止会写入有效结束时间并保留历史记录；理由会作为人工断言依据留存。</p>
        <p className="model-muted"><code>{props.relation.relationType}@{props.relation.relationRevision}</code> · 对象版本 {props.relation.version}</p>
        <label>终止理由<textarea data-autofocus maxLength={256} rows={3} value={reason} onChange={event => setReason(event.target.value)} placeholder="例如：接口下线，业务确认解除包含关系" /></label>
        <label>结束时间（可选，默认当前时间）<input type="datetime-local" value={when} onChange={event => setWhen(event.target.value)} /></label>
        {error ? <p role="alert">{error}</p> : null}
        {pending ? <section className="source-pending" role="status">
          <p>原请求结果未知，请求编号 <code>{pending.requestId}</code> 已保留。</p>
        </section> : null}
        <footer>
          <button type="button" className="button button-quiet" disabled={submitting || pending !== null} onClick={props.onClose}>{pending ? '关闭' : '取消'}</button>
          {pending ? <button type="button" className="button button-primary" disabled={submitting} onClick={() => void send(pending)}>查询原请求结果（原样重试）</button>
            : <button type="submit" className="button button-primary" disabled={submitting}>{submitting ? '提交中' : '终止关系'}</button>}
        </footer>
      </form>
    </aside>
  </div>
}

function RelationCorrectDrawer(props: { relation: EntityRelation; entityId: string; tenantId: string; models: ModelDefinition[]; entities: EntityItem[]; onClose: () => void; onCorrected: () => void }) {
  const [modelId, setModelId] = useState(`${props.relation.relationType}@${props.relation.relationRevision}`)
  const [from, setFrom] = useState<EntityItem | null>(props.entities.find(entity => entity.id === props.relation.fromEntityId) ?? null)
  const [to, setTo] = useState<EntityItem | null>(props.entities.find(entity => entity.id === props.relation.toEntityId) ?? null)
  const [reason, setReason] = useState('')
  const [submitting, setSubmitting] = useState(false), [error, setError] = useState('')
  const [pending, setPending] = useState<EntityRelationCorrectionCommand | null>(null)
  const abort = useRef<AbortController | null>(null)
  const panel = useDialogKeyboard(props.onClose, submitting || pending !== null)
  const model = props.models.find(item => `${item.id}@${item.revision}` === modelId)

  useEffect(() => () => abort.current?.abort(), [])
  const appliedModel = useRef(modelId)
  useEffect(() => {
    if (appliedModel.current === modelId) return
    appliedModel.current = modelId
    if (from && model && !matchesEndpoint(from, model.endpoints?.from.id)) setFrom(null)
    if (to && model && !matchesEndpoint(to, model.endpoints?.to.id)) setTo(null)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [modelId])

  async function send(command: EntityRelationCorrectionCommand) {
    if (submitting) return
    const controller = new AbortController()
    abort.current?.abort(); abort.current = controller
    setSubmitting(true); setError('')
    try {
      const receipt = await correctEntityRelation(props.entityId, props.tenantId, command, controller.signal)
      if (receipt.previousRelationId !== command.relationId) throw new Error('关系更正回执与本次请求不一致')
      setPending(null)
      props.onCorrected()
    } catch (cause) {
      const message = errorMessage(cause)
      if (hasDefiniteHttpStatus(cause)) { setPending(null); setError(message) } else { setPending(command); setError(message) }
    } finally {
      setSubmitting(false)
    }
  }

  function submit(event: FormEvent) {
    event.preventDefault()
    const trimmed = reason.trim()
    if (!model || !from || !to || from.id === to.id || !matchesEndpoint(from, model.endpoints?.from.id) || !matchesEndpoint(to, model.endpoints?.to.id)) {
      setError('请选择符合关系端点模型的不同资产'); return
    }
    if (!trimmed) { setError('请填写更正理由'); return }
    const unchanged = model.id === props.relation.relationType && model.revision === props.relation.relationRevision
      && from.id === props.relation.fromEntityId && to.id === props.relation.toEntityId
    if (unchanged) { setError('更正必须改变端点或关系类型'); return }
    void send({
      requestId: crypto.randomUUID(), relationId: props.relation.id, reason: trimmed, expectedVersion: props.relation.version,
      fromEntityId: from.id, toEntityId: to.id, relationType: model.id, relationRevision: model.revision,
    })
  }

  return <div className="relation-drawer-backdrop" role="presentation" onMouseDown={event => { if (event.currentTarget === event.target && !submitting && !pending) props.onClose() }}>
    <aside ref={panel} className="relation-drawer" role="dialog" aria-modal="true" aria-labelledby="relation-correct-title" tabIndex={-1}>
      <header><div><p className="eyebrow">关系实例</p><h3 id="relation-correct-title">更正关系</h3></div><button type="button" className="icon-button" aria-label="关闭更正抽屉" disabled={submitting || pending !== null} onClick={props.onClose}>×</button></header>
      <form onSubmit={submit}>
        <p className="model-muted">更正会保留原关系记录与历史：原记录写入有效结束时间并关联到更正后的新记录，理由作为人工断言依据留存。已终止的关系不接受更正。</p>
        <p className="model-muted"><code>{props.relation.relationType}@{props.relation.relationRevision}</code> · 对象版本 {props.relation.version}</p>
        <label>关系类型<select value={modelId} onChange={event => setModelId(event.target.value)}>{props.models.map(item => <option key={`${item.id}@${item.revision}`} value={`${item.id}@${item.revision}`}>{item.label} · {item.id}@{item.revision}</option>)}</select></label>
        {model ? <>
          <EndpointPicker enabled label="起点资产" value={from} onChange={setFrom} modelType={model.endpoints?.from.id} />
          <EndpointPicker enabled label="终点资产" value={to} onChange={setTo} modelType={model.endpoints?.to.id} />
        </> : <p className="model-muted">当前目录没有可用的已发布关系类型。</p>}
        <label>更正理由<textarea data-autofocus maxLength={256} rows={3} value={reason} onChange={event => setReason(event.target.value)} placeholder="例如：端点录入错误，改为实际接口资产" /></label>
        {error ? <p role="alert">{error}</p> : null}
        {pending ? <section className="source-pending" role="status">
          <p>原请求结果未知，请求编号 <code>{pending.requestId}</code> 已保留。</p>
        </section> : null}
        <footer>
          <button type="button" className="button button-quiet" disabled={submitting || pending !== null} onClick={props.onClose}>{pending ? '关闭' : '取消'}</button>
          {pending ? <button type="button" className="button button-primary" disabled={submitting} onClick={() => void send(pending)}>查询原请求结果（原样重试）</button>
            : <button type="submit" className="button button-primary" disabled={submitting || !model || !from || !to}>{submitting ? '提交中' : '更正关系'}</button>}
        </footer>
      </form>
    </aside>
  </div>
}

function RelationConfirmDrawer(props: { relation: EntityRelation; preset: EntityRelationConfirmationEntry | null; entityId: string; tenantId: string; onClose: () => void; onChanged: () => void }) {
  const [sourceKind, setSourceKind] = useState<'manual' | 'source'>('source')
  const [sourceKey, setSourceKey] = useState(''), [batchRef, setBatchRef] = useState(''), [dataMode, setDataMode] = useState<EntityRelation['dataMode']>('unknown')
  const [withdrawKind, setWithdrawKind] = useState<'manual' | 'source'>(props.preset?.sourceKind ?? 'source')
  const [withdrawKey, setWithdrawKey] = useState(props.preset?.sourceKey ?? ''), [withdrawBatch, setWithdrawBatch] = useState(props.preset?.batchRef ?? '')
  const [reason, setReason] = useState('')
  const [submitting, setSubmitting] = useState(false), [error, setError] = useState('')
  const [pendingConfirm, setPendingConfirm] = useState<EntityRelationConfirmationCommand | null>(null)
  const [pendingWithdraw, setPendingWithdraw] = useState<EntityRelationConfirmationWithdrawCommand | null>(null)
  const abort = useRef<AbortController | null>(null)
  const panel = useDialogKeyboard(props.onClose, submitting || pendingConfirm !== null || pendingWithdraw !== null)

  useEffect(() => () => abort.current?.abort(), [])

  async function sendConfirm(command: EntityRelationConfirmationCommand) {
    if (submitting) return
    const controller = new AbortController()
    abort.current?.abort(); abort.current = controller
    setSubmitting(true); setError('')
    try {
      await confirmEntityRelationSource(props.entityId, props.relation.id, command, controller.signal)
      setPendingConfirm(null)
      props.onChanged()
    } catch (cause) {
      const message = errorMessage(cause)
      if (hasDefiniteHttpStatus(cause)) { setPendingConfirm(null); setError(message) } else { setPendingConfirm(command); setError(message) }
    } finally {
      setSubmitting(false)
    }
  }

  async function sendWithdraw(command: EntityRelationConfirmationWithdrawCommand) {
    if (submitting) return
    const controller = new AbortController()
    abort.current?.abort(); abort.current = controller
    setSubmitting(true); setError('')
    try {
      await withdrawEntityRelationSourceConfirmation(props.entityId, props.relation.id, command, controller.signal)
      setPendingWithdraw(null)
      props.onChanged()
    } catch (cause) {
      const message = errorMessage(cause)
      if (hasDefiniteHttpStatus(cause)) { setPendingWithdraw(null); setError(message) } else { setPendingWithdraw(command); setError(message) }
    } finally {
      setSubmitting(false)
    }
  }

  function submitConfirm(event: FormEvent) {
    event.preventDefault()
    if (!sourceKey.trim() || !batchRef.trim()) { setError('请填写来源标识与批次标识'); return }
    void sendConfirm({ requestId: crypto.randomUUID(), sourceKind, sourceKey: sourceKey.trim(), batchRef: batchRef.trim(), dataMode })
  }

  function submitWithdraw(event: FormEvent) {
    event.preventDefault()
    const trimmed = reason.trim()
    if (!withdrawKey.trim() || !withdrawBatch.trim()) { setError('请填写要撤回的来源标识与批次标识'); return }
    if (!trimmed) { setError('请填写撤回理由'); return }
    void sendWithdraw({ requestId: crypto.randomUUID(), sourceKind: withdrawKind, sourceKey: withdrawKey.trim(), batchRef: withdrawBatch.trim(), reason: trimmed })
  }

  return <div className="relation-drawer-backdrop" role="presentation" onMouseDown={event => { if (event.currentTarget === event.target && !submitting && !pendingConfirm && !pendingWithdraw) props.onClose() }}>
    <aside ref={panel} className="relation-drawer" role="dialog" aria-modal="true" aria-labelledby="relation-confirm-title" tabIndex={-1}>
      <header><div><p className="eyebrow">关系实例</p><h3 id="relation-confirm-title">来源确认维护</h3></div><button type="button" className="icon-button" aria-label="关闭确认抽屉" disabled={submitting || pendingConfirm !== null || pendingWithdraw !== null} onClick={props.onClose}>×</button></header>
      <p className="model-muted">每条来源确认独立记录；单一来源失效只撤回它自己的确认，其他来源仍确认时关系保持有效。人工断言与来源确认互不改写。</p>
      <form onSubmit={submitConfirm}>
        <h4>登记来源确认</h4>
        <label>确认类别<select value={sourceKind} onChange={event => setSourceKind(event.target.value === 'manual' ? 'manual' : 'source')}><option value="source">来源确认</option><option value="manual">人工断言</option></select></label>
        <label>来源标识<input data-autofocus maxLength={128} value={sourceKey} onChange={event => setSourceKey(event.target.value)} placeholder="例如：workflow.9f2c…（显式来源身份）" /></label>
        <label>批次标识<input maxLength={128} value={batchRef} onChange={event => setBatchRef(event.target.value)} placeholder="来源批次/执行标识" /></label>
        <label>数据模式<select value={dataMode} onChange={event => setDataMode(event.target.value as EntityRelation['dataMode'])}><option value="unknown">未知</option><option value="fixture">Fixture</option><option value="zabbix-jsonrpc">Zabbix</option><option value="import">导入</option></select></label>
        <footer>{pendingConfirm
          ? <>
            <section className="source-pending" role="status"><p>原请求结果未知，请求编号 <code>{pendingConfirm.requestId}</code> 已保留。</p></section>
            <button type="button" className="button button-primary" disabled={submitting} onClick={() => void sendConfirm(pendingConfirm)}>查询原请求结果（原样重试）</button>
          </>
          : <button type="submit" className="button button-primary" disabled={submitting}>{submitting ? '提交中' : '登记来源确认'}</button>}</footer>
      </form>
      <form onSubmit={submitWithdraw}>
        <h4>撤回来源确认</h4>
        <label>撤回确认类别<select value={withdrawKind} onChange={event => setWithdrawKind(event.target.value === 'manual' ? 'manual' : 'source')}><option value="source">来源确认</option><option value="manual">人工断言</option></select></label>
        <label>撤回来源标识<input maxLength={128} value={withdrawKey} onChange={event => setWithdrawKey(event.target.value)} /></label>
        <label>撤回批次标识<input maxLength={128} value={withdrawBatch} onChange={event => setWithdrawBatch(event.target.value)} /></label>
        <label>撤回理由<textarea maxLength={256} rows={2} value={reason} onChange={event => setReason(event.target.value)} placeholder="例如：来源批次撤销" /></label>
        {error ? <p role="alert">{error}</p> : null}
        <footer>{pendingWithdraw
          ? <>
            <section className="source-pending" role="status"><p>原请求结果未知，请求编号 <code>{pendingWithdraw.requestId}</code> 已保留。</p></section>
            <button type="button" className="button button-primary" disabled={submitting} onClick={() => void sendWithdraw(pendingWithdraw)}>查询原请求结果（原样重试）</button>
          </>
          : <button type="submit" className="button button-primary" disabled={submitting}>{submitting ? '提交中' : '撤回来源确认'}</button>}</footer>
      </form>
      <footer><button type="button" className="button button-quiet" disabled={submitting || pendingConfirm !== null || pendingWithdraw !== null} onClick={props.onClose}>关闭</button></footer>
    </aside>
  </div>
}

function ExternalLinkRegistrationDrawer(props: { active: boolean; entities: EntityItem[]; defaultNamespace: string; onClose: () => void; onRegistered: () => void }) {
  const [namespace, setNamespace] = useState(props.defaultNamespace || 'workflow.')
  const [objectType, setObjectType] = useState('entity')
  const [externalKey, setExternalKey] = useState('')
  const [targetId, setTargetId] = useState('')
  const [generation, setGeneration] = useState('')
  const [reason, setReason] = useState('')
  const [submitting, setSubmitting] = useState(false), [error, setError] = useState('')
  const [pending, setPending] = useState<ExternalLinkRegistrationCommand | null>(null)
  const [receipt, setReceipt] = useState<ExternalLinkRegistrationReceipt | null>(null)
  const abort = useRef<AbortController | null>(null)
  const panel = useDialogKeyboard(props.onClose, submitting || pending !== null)
  useEffect(() => () => abort.current?.abort(), [])

  async function send(command: ExternalLinkRegistrationCommand) {
    if (submitting) return
    const controller = new AbortController()
    abort.current?.abort(); abort.current = controller
    setSubmitting(true); setError('')
    try {
      const result = await registerExternalLink(command, controller.signal)
      if (result.link.targetId.toLowerCase() !== command.targetId.toLowerCase()) throw new Error('登记回执与本次请求不一致')
      setPending(null); setReceipt(result)
    } catch (cause) {
      const message = errorMessage(cause)
      if (hasDefiniteHttpStatus(cause)) { setPending(null); setError(message) } else { setPending(command); setError(message) }
    } finally { setSubmitting(false) }
  }

  function submit(event: FormEvent) {
    event.preventDefault()
    const trimmedKey = externalKey.trim(), trimmedNamespace = namespace.trim(), trimmedType = objectType.trim()
    if (!trimmedNamespace || !trimmedType || !trimmedKey || !targetId) { setError('请填写命名空间、对象类型、外部键并选择目标实体'); return }
    const trimmedReason = reason.trim()
    const command: ExternalLinkRegistrationCommand = {
      requestId: crypto.randomUUID(), namespace: trimmedNamespace, objectType: trimmedType, externalKey: trimmedKey, targetId,
      ...(generation.trim() ? { generation: generation.trim() } : {}), ...(trimmedReason ? { reason: trimmedReason } : {}),
    }
    void send(command)
  }

  if (receipt) {
    return <div className="relation-drawer-backdrop" role="presentation" onMouseDown={event => { if (event.currentTarget === event.target) props.onRegistered() }}>
      <aside ref={panel} className="relation-drawer" role="dialog" aria-modal="true" aria-labelledby="external-link-receipt-title" tabIndex={-1}>
        <header><div><p className="eyebrow">外部链接</p><h3 id="external-link-receipt-title">登记回执</h3></div><button type="button" className="icon-button" aria-label="关闭登记回执" onClick={props.onRegistered}>×</button></header>
        <dl className="relation-detail-list">
          <div><dt>请求编号</dt><dd><code>{receipt.requestId}</code></dd></div>
          <div><dt>结果</dt><dd>{receipt.replayed ? '重复请求重放原回执' : '已登记'}</dd></div>
          <div><dt>命名空间</dt><dd><code>{receipt.link.namespace}</code></dd></div>
          <div><dt>对象类型</dt><dd><code>{receipt.link.objectType}</code></dd></div>
          <div><dt>外部键</dt><dd><code>{receipt.link.externalKey}</code></dd></div>
          <div><dt>代次</dt><dd><code>{receipt.link.generation}</code></dd></div>
          <div><dt>目标实体</dt><dd><code>{receipt.link.targetId}</code></dd></div>
        </dl>
        <p className="model-muted">端点解析只经此确定性绑定，不按名字/IP 猜测；未登记的端点保持待处理。</p>
        <footer><button data-autofocus type="button" className="button button-primary" onClick={props.onRegistered}>完成</button></footer>
      </aside>
    </div>
  }

  return <div className="relation-drawer-backdrop" role="presentation" onMouseDown={event => { if (event.currentTarget === event.target && !submitting && !pending) props.onClose() }}>
    <aside ref={panel} className="relation-drawer" role="dialog" aria-modal="true" aria-labelledby="external-link-drawer-title" tabIndex={-1}>
      <header><div><p className="eyebrow">外部链接</p><h3 id="external-link-drawer-title">登记外部链接</h3></div><button type="button" className="icon-button" aria-label="关闭登记抽屉" disabled={submitting || pending !== null} onClick={props.onClose}>×</button></header>
      <form onSubmit={submit}>
        <p className="model-muted">把一个显式外部对象键绑定到已授权实体，使待处理的工作流关系端点可确定解析；不解析名字/IP，不改写既有实体归属。</p>
        <label>命名空间<input data-autofocus value={namespace} maxLength={96} onInput={event => setNamespace((event.target as HTMLInputElement).value)} placeholder="workflow.工作流id" /></label>
        <label>对象类型<input value={objectType} maxLength={64} onInput={event => setObjectType((event.target as HTMLInputElement).value)} placeholder="entity" /></label>
        <label>外部键<input value={externalKey} maxLength={512} onInput={event => setExternalKey((event.target as HTMLInputElement).value)} placeholder="来源显式身份字段值" /></label>
        <label>目标实体<select value={targetId} onChange={event => setTargetId(event.target.value)}>
          <option value="">选择实体</option>
          {props.entities.map(entity => <option key={entity.id} value={entity.id}>{entity.name} · {entity.entityType}</option>)}
        </select></label>
        <label>代次（可选，默认取目标实体模型）<input value={generation} maxLength={128} onInput={event => setGeneration((event.target as HTMLInputElement).value)} placeholder="留空则使用目标实体已固定模型" /></label>
        <label>理由（可选）<textarea maxLength={256} rows={2} value={reason} onInput={event => setReason((event.target as HTMLTextAreaElement).value)} placeholder="例如：确认来源身份对应既有主机" /></label>
        {error ? <p role="alert">{error}</p> : null}
        {pending ? <section className="source-pending" role="status">
          <p>原请求结果未知，请求编号 <code>{pending.requestId}</code> 已保留。</p>
        </section> : null}
        <footer>
          <button type="button" className="button button-quiet" disabled={submitting || pending !== null} onClick={props.onClose}>{pending ? '关闭' : '取消'}</button>
          {pending ? <button type="button" className="button button-primary" disabled={submitting} onClick={() => void send(pending)}>查询原请求结果（原样重试）</button>
            : <button type="submit" className="button button-primary" disabled={submitting || !targetId}>{submitting ? '提交中' : '登记链接'}</button>}
        </footer>
      </form>
    </aside>
  </div>
}
