import { useEffect, useRef, useState } from 'react'
import { Plus } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { PageHeader, QueryToolbar, SummaryGrid, SummaryCard } from '../../components/PageLayout.tsx'
import { Input } from '@/components/ui/input'
import { RouteIcon } from '../../app/navigation.tsx'
import { SourceReviews } from './SourceReviews.tsx'
import { SourcePresence } from './SourcePresence.tsx'
import { AssetIdentities } from './AssetIdentities.tsx'
import { resolveIdentity, identityPin, type IdentityPin } from '../../api/asset-identities.ts'
import { usePlatformSession } from '../../state/platform-session.ts'
import { useInitialPageRead } from '../../state/initial-page-read.ts'
import { useViewLocation, useSelectionSessionReset } from '../../state/view-location.ts'
import { inventoryDefault, inventoryHash, inventorySelection, type InventorySelection } from '../../state/view-selection.ts'
import { ObservationHistory } from './ObservationHistory.tsx'
import { pageEntities, getEntity, EntityRequestError, syncZabbixHosts, type EntityItem, type EntityModelPin, type HostSyncResult } from '../../api/entities.ts'
import { readCatalog, readModelVersion, type ModelDefinition } from '../../api/model-catalog.ts'
import { EntityInstanceDrawer } from './EntityInstanceDrawer.tsx'

export function InventoryPage() {
  const resetOnSession = useSelectionSessionReset()
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [items, setItems] = useState<EntityItem[]>([])
  const [loaded, setLoaded] = useState(false)
  const [sync, setSync] = useState<HostSyncResult | null>(null)
  const [search, setSearch] = useState('')
  const [lifecycle, setLifecycle] = useState('')
  const [entityType, setEntityType] = useState('host')
  const [next, setNext] = useState<string | null>(null)
  const [cursor, setCursor] = useState<string | null>(null)
  const [previous, setPrevious] = useState<(string | null)[]>([])
  const [storage, setStorage] = useState('')
  const [detail, setDetail] = useState<EntityItem | null>(null)
  const [detailModel, setDetailModel] = useState<ModelDefinition | null>(null)
  const [detailModelError, setDetailModelError] = useState('')
  const [linkedEntity, setLinkedEntity] = useState<string | null>(null)
  const [routeError, setRouteError] = useState('')
  const [assetUuid, setAssetUuid] = useState('')
  const [resolvedPin, setResolvedPin] = useState<IdentityPin | undefined>(undefined)
  const [createOpen, setCreateOpen] = useState(false)
  const [createModels, setCreateModels] = useState<ModelDefinition[] | null>(null)
  const [createCatalogTruncated, setCreateCatalogTruncated] = useState(false)
  const [createCatalogBusy, setCreateCatalogBusy] = useState(false)
  const [createCatalogError, setCreateCatalogError] = useState('')
  const abortRef = useRef<AbortController | undefined>(undefined)
  const catalogAbortRef = useRef<AbortController | undefined>(undefined)
  const catalogRequestRef = useRef(0)
  const disposedRef = useRef(false)
  const requestIdRef = useRef(0)

  useEffect(() => () => {
    disposedRef.current = true
    abortRef.current?.abort()
    catalogAbortRef.current?.abort()
  }, [])

  function invalidate() {
    abortRef.current?.abort(); requestIdRef.current++; setBusy(false); setError(''); setItems([]); setLoaded(false)
    setNext(null); setCursor(null); setPrevious([]); setDetail(null); setDetailModel(null); setDetailModelError(''); setStorage(''); setLinkedEntity(null)
    setAssetUuid(''); setResolvedPin(undefined)
  }
  function selected(): InventorySelection { return { q: search.trim(), type: entityType, lifecycle, after: cursor, entityId: linkedEntity } }
  function applySelection(value: InventorySelection) {
    invalidate(); setSync(null); setSearch(value.q); setLifecycle(value.lifecycle); setEntityType(value.type); setCursor(value.after); setLinkedEntity(value.entityId)
  }
  const location = useViewLocation('/inventory', inventorySelection, inventoryHash,
    value => { setRouteError(''); applySelection(value) }, cause => { applySelection(inventoryDefault()); setRouteError(cause.message) })
  const authenticated = usePlatformSession(change => {
    const reset = resetOnSession.changed(change), selection = reset ? inventoryDefault() : selected()
    catalogRequestRef.current++
    catalogAbortRef.current?.abort(); catalogAbortRef.current = undefined
    setCreateOpen(false); setCreateModels(null); setCreateCatalogTruncated(false); setCreateCatalogBusy(false); setCreateCatalogError('')
    applySelection(selection); setSync(null)
    if (reset) { setRouteError(''); location.write(selection, true) }
    if (change.error) setError(change.error.message)
  })
  useInitialPageRead({
    ready: authenticated,
    loaded,
    pending: busy,
    blocked: routeError !== '',
    rereadOnCredentials: false,
    read: () => { void run('list') },
  })
  function resetFilters() { applySelection(inventoryDefault()); setRouteError(''); location.write(inventoryDefault()) }
  function changeAssetUuid(value: string) { abortRef.current?.abort(); requestIdRef.current++; setBusy(false); setError(''); setDetail(null); setDetailModel(null); setDetailModelError(''); setResolvedPin(undefined); setAssetUuid(value) }
  async function openEntityCreate() {
    if (createModels) { setCreateOpen(true); return }
    resetOnSession.beginRead()
    catalogAbortRef.current?.abort()
    const controller = new AbortController(), current = ++catalogRequestRef.current
    catalogAbortRef.current = controller
    setCreateCatalogBusy(true); setCreateCatalogError('')
    try {
      const catalog = await readCatalog(controller.signal)
      if (disposedRef.current || current !== catalogRequestRef.current) return
      const builtins = catalog.package.definitions.filter(model => model.kind === 'ENTITY')
      const published = catalog.published.items.filter(entry => entry.definition.kind === 'ENTITY').map(entry => entry.definition)
      setCreateModels([...builtins, ...published])
      setCreateCatalogTruncated(catalog.published.truncated)
      setCreateOpen(true)
    } catch (cause) {
      if (disposedRef.current || current !== catalogRequestRef.current || cause instanceof DOMException && cause.name === 'AbortError') return
      setCreateCatalogError(cause instanceof Error ? cause.message : '已发布模型读取失败')
    } finally {
      if (!disposedRef.current && current === catalogRequestRef.current) setCreateCatalogBusy(false)
    }
  }
  async function resolveDetailModel(item: EntityItem, signal: AbortSignal, request: number) {
    if (!item.model) return
    try {
      const model = await readPinnedModel(item.model, signal)
      if (!disposedRef.current && request === requestIdRef.current) setDetailModel(model)
    } catch (cause) {
      if (cause instanceof DOMException && cause.name === 'AbortError') return
      if (!disposedRef.current && request === requestIdRef.current) setDetailModelError('固定模型版本暂不可用；当前保留字段 ID。')
    }
  }
  async function run(mode: 'list' | 'sync' | 'next' | 'previous' | 'detail' | 'resolve', id?: string, preserveDetail?: EntityItem, resetPage = false) {
    resetOnSession.beginRead()
    abortRef.current?.abort()
    const controller = new AbortController(); abortRef.current = controller
    const currentRequest = ++requestIdRef.current
    const timeout = window.setTimeout(() => controller.abort(), 35000)
    const after = resetPage ? null : mode === 'next' ? next : mode === 'previous' ? previous.at(-1) ?? null : mode === 'list' ? cursor : null
    const history = resetPage ? [] : mode === 'next' ? [...previous, cursor] : mode === 'previous' ? previous.slice(0, -1) : mode === 'list' ? previous : []
    const filters = resetPage ? { q: '', type: '', lifecycle: '' } : { q: search, type: entityType, lifecycle }
    setBusy(true)
    setError(''); setDetail(null); if (!preserveDetail) { setDetailModel(null); setDetailModelError('') } setResolvedPin(undefined)
    try {
      if (routeError) throw new Error(routeError)
      if (mode === 'resolve') {
        const matched = await resolveIdentity(assetUuid.trim(), controller.signal), entity = await getEntity(matched.identity.entityId, controller.signal)
        if (disposedRef.current || currentRequest !== requestIdRef.current) return
        if (entity.tenantId !== matched.identity.tenantId || entity.version !== matched.entityVersion) throw new Error('定位后资产已变化，请重新按标识读取。')
        setLinkedEntity(entity.id); location.write({ ...selected(), entityId: entity.id }); setResolvedPin(identityPin(matched.identity)); setDetail(entity)
        await resolveDetailModel(entity, controller.signal, currentRequest)
        return
      }
      if (mode === 'detail') {
        if (!id) throw new Error('请选择资产')
        setLinkedEntity(id); location.write({ ...selected(), entityId: id })
        const result = await getEntity(id, controller.signal)
        if (!disposedRef.current && currentRequest === requestIdRef.current) {
          setDetail(result)
          await resolveDetailModel(result, controller.signal, currentRequest)
        }
        return
      }
      location.write({ ...(resetPage ? filters : selected()), after, entityId: preserveDetail?.id ?? null }); setLinkedEntity(preserveDetail?.id ?? null)
      if (mode === 'sync') {
        const result = await syncZabbixHosts(controller.signal)
        if (disposedRef.current || currentRequest !== requestIdRef.current) return
        setSync(result)
      }
      const list = await pageEntities(filters, after, controller.signal)
      if (disposedRef.current || currentRequest !== requestIdRef.current) return
      setItems(list.items)
      setLoaded(true)
      setNext(list.nextCursor); setCursor(after); setPrevious(history); setStorage(list.storage)
      setDetail(preserveDetail ?? null)
    } catch (cause) {
      if (disposedRef.current || currentRequest !== requestIdRef.current) return
      if (cause instanceof EntityRequestError && [401, 403].includes(cause.status)) {
        setItems([]); setLoaded(false); setNext(null); setCursor(null); setPrevious([]); setSync(null); setStorage('')
      }
      if (cause instanceof DOMException && cause.name === 'AbortError') {
        setError('资产请求已取消或超时')
      } else {
        setError(cause instanceof Error ? cause.message : '请求失败')
      }
    } finally {
      window.clearTimeout(timeout)
      if (!disposedRef.current && currentRequest === requestIdRef.current) setBusy(false)
    }
  }

  return (
    <section className="panel" data-page="inventory">
      <PageHeader title="资产" description="查看来源资产，关联指标与告警，追溯每一次观测。" actions={<span className="badge">授权范围内</span>} />
      <SummaryGrid label="本页资产统计">
        <SummaryCard label="本页资产" icon={<RouteIcon name="inventory" />} stat="total" value={loaded ? items.length : '—'} hint="仅统计已读取的当前页" />
        <SummaryCard label="活跃资产" icon={<RouteIcon name="metrics" />} stat="active" value={loaded ? items.filter(item => item.lifecycle === 'ACTIVE').length : '—'} hint="本页生命周期为 ACTIVE" />
        <SummaryCard label="来源实例" icon={<RouteIcon name="pipelines" />} stat="sources" value={loaded ? new Set(items.map(item => item.attributes.sourceInstanceId).filter(Boolean)).size : '—'} hint="本页已记录的不同来源实例" />
        <SummaryCard label="其他状态" icon={<RouteIcon name="source-snapshots" />} stat="other" value={loaded ? items.filter(item => item.lifecycle !== 'ACTIVE').length : '—'} hint="本页非 ACTIVE 生命周期" />
      </SummaryGrid>
      <div className="asset-workspace">
      <QueryToolbar>
      <div className="metric-controls">
        <label>名称或 IP<Input value={search} onChange={event => { invalidate(); setSearch(event.currentTarget.value) }} /></label>
        <label>生命周期<select value={lifecycle} onChange={event => { invalidate(); setLifecycle(event.currentTarget.value) }}>
          <option value="">全部状态</option><option value="ACTIVE">ACTIVE</option><option value="INACTIVE">INACTIVE</option>
          <option value="DISCOVERED">DISCOVERED</option><option value="DELETED">DELETED</option><option value="ARCHIVED">ARCHIVED</option>
        </select></label>
        <label>资产类型<select value={entityType} onChange={event => { invalidate(); setEntityType(event.currentTarget.value) }}>
          <option value="host">Host</option><option value="">全部类型</option>
        </select></label>
      </div>
      <div className="actions">
        <Button
          variant="default"
          disabled={busy || !authenticated || routeError !== ''}
          onClick={() => { void run('sync') }}
        >
          {busy ? '正在同步…' : '同步 Zabbix Host'}
        </Button>
        <Button variant="outline" disabled={busy || createCatalogBusy || !authenticated || routeError !== ''} onClick={() => { void openEntityCreate() }}>
          <Plus size={15} />{createCatalogBusy ? '读取实体模型…' : '新建实体实例'}
        </Button>
        <Button
          variant="outline"
          disabled={busy || !authenticated || routeError !== ''}
          onClick={() => { void run('list') }}
        >
          刷新列表
        </Button>
        <Button variant="outline" disabled={busy} onClick={resetFilters}>重置资产筛选</Button>
        {linkedEntity && !detail ? <Button variant="outline" disabled={busy || !authenticated || routeError !== ''} onClick={() => { void run('detail', linkedEntity ?? undefined) }}>读取选中资产</Button> : null}</div>
      </QueryToolbar>
      {createCatalogError ? <p data-create-model-error>{createCatalogError}</p> : null}
      {createCatalogError ? <Button variant="outline" disabled={busy || createCatalogBusy || !authenticated} onClick={() => { void openEntityCreate() }}>重试读取已发布模型</Button> : null}
      {createCatalogTruncated ? <p role="status">已发布模型目录达到返回上限，较早的实体模型可能未显示。</p> : null}
      <p role="alert">{routeError || error}</p>
      {sync ? <SyncStatusLine result={sync} /> : null}
      {loaded ? <p data-inventory-page>{`${cursor && previous.length === 0 ? '恢复的游标页' : `第 ${previous.length + 1} 页`} · 本页 ${items.length} 条 · ${storage === 'postgres' ? 'PostgreSQL' : '开发内存'}`}</p> : null}
      {!loaded ? <div className="inventory-empty"><RouteIcon name="inventory" /><strong>{busy ? '正在读取资产' : '准备读取资产'}</strong><p>{busy ? '正在读取授权范围内的资产。' : '建立会话后将自动读取当前列表。'}</p></div> : null}
      {loaded && items.length === 0 ? <div className="inventory-empty"><RouteIcon name="inventory" /><strong>当前筛选范围没有可见资产。</strong><p>可以调整筛选条件后重新读取。</p></div> : null}
      {items.length > 0 ? (
        <div className="pipeline-table"><table>
          <thead>
            <tr>
              <th>Name</th>
              <th>Host ID</th>
              <th>IP</th>
              <th>Status</th>
              <th>Source</th>
              <th>Last Seen</th>
              <th>Raw Reference</th>
              <th>Lifecycle</th>
              <th>详情</th>
            </tr>
          </thead>
          <tbody>
            {items.map(item => <EntityRow key={item.id} item={item} busy={busy} select={id => { void run('detail', id) }} />)}
          </tbody>
        </table></div>
      ) : null}
      <div className="actions inventory-pagination">
        <Button variant="outline" disabled={busy || previous.length === 0} onClick={() => { void run('previous') }}>上一页资产</Button>
        <Button variant="outline" disabled={busy || next === null} onClick={() => { void run('next') }}>下一页资产</Button>
      </div>
      </div>
      <details><summary>按已登记强标识定位</summary><p>输入资产登记系统 UUID，平台在配置的命名空间内查找当前可管理的资产。定位后导入会保留身份依据。</p>
        <label>查找资产 UUID<Input value={assetUuid} onChange={event => changeAssetUuid(event.currentTarget.value)} /></label>
        <Button variant="outline" disabled={busy || !authenticated || !assetUuid.trim() || !!routeError} onClick={() => { void run('resolve') }}>按强标识定位</Button></details>
      <details className="query-notes"><summary>查询范围与会话说明</summary><p>按服务端授权范围筛选和分页，每页最多 25 条。同步来源失败时保留当前表格；切换身份或失去权限会清空旧数据。</p><p>地址保留已应用的筛选、游标与选中资产；刷新或返回后请重新读取，以核对当前权限。</p></details>
      {detail ? <EntityDetails item={detail} model={detailModel} modelError={detailModelError} identity={resolvedPin} close={() => { setDetail(null); setDetailModel(null); setDetailModelError(''); setResolvedPin(undefined); setLinkedEntity(null); location.write({ ...selected(), entityId: null }) }} /> : null}
      {createOpen && createModels ? <EntityInstanceDrawer models={createModels} publishedTruncated={createCatalogTruncated} disabled={busy || !authenticated} onClose={() => setCreateOpen(false)} onCreated={(entity, model) => {
        setCreateOpen(false); setError(''); setResolvedPin(undefined); setDetail(entity); setDetailModel(model); setDetailModelError(''); setLinkedEntity(entity.id)
        setSearch(''); setEntityType(''); setLifecycle('')
        setCursor(null); setPrevious([]); setNext(null); setItems([]); setLoaded(false)
        location.write({ q: '', type: '', lifecycle: '', after: null, entityId: entity.id })
        void run('list', undefined, entity, true)
      }} /> : null}
    </section>
  )
}

function SyncStatusLine(props: { result: HostSyncResult }) {
  return (
    <p>
      <code>{`${props.result.dataMode} / ${props.result.inventoryStore} / pages ${props.result.pages}`}</code>
    </p>
  )
}

function EntityRow(props: { item: EntityItem; busy: boolean; select: (id: string) => void }) {
  const item = props.item
  return (
    <tr>
      <td>{item.name}</td>
      <td>{item.attributes.hostId}</td>
      <td>{item.attributes.ip}</td>
      <td>{item.attributes.status}</td>
      <td className="source-cell">{item.attributes.source}<small>{item.attributes.dataMode || '采集模式未记录'}</small></td>
      <td><time className="table-timestamp" dateTime={item.attributes.lastSeen} title={item.attributes.lastSeen}>{item.attributes.lastSeen.replace('T', ' ').replace(/\.\d+Z$/, 'Z')}</time></td>
      <td><span className="table-reference" title={item.attributes.rawReference}>{item.attributes.rawReference}</span></td>
      <td><span className="lifecycle-pill" data-active={item.lifecycle === 'ACTIVE' ? 'true' : 'false'}>{item.lifecycle}</span></td>
      <td><Button variant="outline" disabled={props.busy} onClick={() => props.select(item.id)}>查看详情</Button></td>
    </tr>
  )
}
async function readPinnedModel(pin: EntityModelPin, signal: AbortSignal): Promise<ModelDefinition> {
  if (pin.id.startsWith('builtin.')) {
    const catalog = await readCatalog(signal)
    const definition = catalog.package.definitions.find(model => model.id === pin.id && model.revision === pin.revision)
    if (!definition) throw new Error('固定实体模型不存在')
    return definition
  }
  const entry = await readModelVersion({ id: pin.id, revision: pin.revision }, signal)
  if (entry.digest !== pin.digest) throw new Error('固定实体模型摘要不匹配')
  return entry.definition
}

function EntityDetails(props: { item: EntityItem; model: ModelDefinition | null; modelError: string; close: () => void; identity?: IdentityPin }) {
  const [item, setItem] = useState(props.item)
  const pinnedModel = props.model && item.model?.id === props.model.id && item.model.revision === props.model.revision ? props.model : null
  const modelFields = pinnedModel ? new Map(pinnedModel.fields.map(field => [field.id, field.label])) : new Map<string, string>()
  return <section data-entity-detail aria-label="资产详情">
    <h3>{`资产详情：${item.name}`}</h3>
    <p>{`ID ${item.id} · ${item.entityType} · ${item.lifecycle} · 版本 ${item.version}`}</p>
    <p>{`来源 ${item.attributes.source || '未记录'} / 实例 ${item.attributes.sourceInstanceId || '未记录'} / ${item.attributes.dataMode || '采集模式未记录'}`}</p>
    <p>{`Host ID ${item.attributes.hostId || '未记录'} · IP ${item.attributes.ip || '未记录'} · 状态 ${item.attributes.status || '未记录'}`}</p>
    <p>{`最后观测 ${item.attributes.lastSeen || '未记录'}`}</p>
    <p>{`Raw 引用 ${item.attributes.rawReference || '未记录'}`}</p>
    <p>{item.attributes.pipelineId && item.attributes.pipelineRevision ? `映射 ${item.attributes.pipelineId}@${item.attributes.pipelineRevision} / ${item.attributes.pipelineDigest}` : '历史映射版本未记录'}</p>
    <p>详情是当前资产投影；Raw 引用不代表原始记录仍留存，也不是根因证明。</p>
    <section data-model-attributes aria-label="模型字段">
      <h4>模型字段</h4>
      {item.model && props.modelError ? <p role="status" data-model-definition-error>{props.modelError}</p> : null}
      {Object.entries(item.modelAttributes).length ? <dl>{Object.entries(item.modelAttributes).map(([key, value]) => <div key={key}><dt><span>{modelFields.get(key) ?? key}</span>{modelFields.has(key) ? <code>{key}</code> : null}</dt><dd>{value === null ? '未设置' : typeof value === 'boolean' ? value ? '是' : '否' : String(value)}</dd></div>)}</dl> : <p>没有可展示的模型字段。</p>}
    </section>
    {item.attributes.fieldAuthority ? <p data-entity-field-authority>{`补充字段 ${item.attributes.fieldAuthority.fields.join("、")} · 来源 ${item.attributes.fieldAuthority.sourceInstanceId} · 观测 ${item.attributes.fieldAuthority.observedAt} · 到期 ${item.attributes.fieldAuthority.expiresAt}；过期后仅保留最后已知字段，不能视为新鲜观测。`}</p> : null}
    <ObservationHistory entity={item} />
    <SourcePresence entity={item} />
    <AssetIdentities entity={props.item} changed={setItem} />
    <SourceReviews entity={props.item} changed={setItem} identity={props.identity} />
    <Button variant="outline" onClick={props.close}>关闭详情</Button>
  </section>
}
