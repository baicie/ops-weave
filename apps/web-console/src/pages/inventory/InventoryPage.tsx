import { useEffect, useRef, useState } from 'react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { RouteIcon } from '../../app/navigation.tsx'
import { SourceReviews } from './SourceReviews.tsx'
import { SourcePresence } from './SourcePresence.tsx'
import { AssetIdentities } from './AssetIdentities.tsx'
import { resolveIdentity, identityPin, type IdentityPin } from '../../api/asset-identities.ts'
import { usePlatformSession } from '../../state/platform-session.ts'
import { useViewLocation, useSelectionSessionReset } from '../../state/view-location.ts'
import { inventoryDefault, inventoryHash, inventorySelection, type InventorySelection } from '../../state/view-selection.ts'
import { ObservationHistory } from './ObservationHistory.tsx'
import { pageEntities, getEntity, EntityRequestError, syncZabbixHosts, type EntityItem, type HostSyncResult } from '../../api/entities.ts'

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
  const [linkedEntity, setLinkedEntity] = useState<string | null>(null)
  const [routeError, setRouteError] = useState('')
  const [assetUuid, setAssetUuid] = useState('')
  const [resolvedPin, setResolvedPin] = useState<IdentityPin | undefined>(undefined)
  const abortRef = useRef<AbortController | undefined>(undefined)
  const disposedRef = useRef(false)
  const requestIdRef = useRef(0)

  useEffect(() => () => {
    disposedRef.current = true
    abortRef.current?.abort()
  }, [])

  function invalidate() {
    abortRef.current?.abort(); requestIdRef.current++; setBusy(false); setError(''); setItems([]); setLoaded(false)
    setNext(null); setCursor(null); setPrevious([]); setDetail(null); setStorage(''); setLinkedEntity(null)
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
    applySelection(selection); setSync(null)
    if (reset) { setRouteError(''); location.write(selection, true) }
    if (change.error) setError(change.error.message)
  })
  function resetFilters() { applySelection(inventoryDefault()); setRouteError(''); location.write(inventoryDefault()) }
  function changeAssetUuid(value: string) { abortRef.current?.abort(); requestIdRef.current++; setBusy(false); setError(''); setDetail(null); setResolvedPin(undefined); setAssetUuid(value) }
  async function run(mode: 'list' | 'sync' | 'next' | 'previous' | 'detail' | 'resolve', id?: string) {
    resetOnSession.beginRead()
    abortRef.current?.abort()
    const controller = new AbortController(); abortRef.current = controller
    const currentRequest = ++requestIdRef.current
    const timeout = window.setTimeout(() => controller.abort(), 35000)
    const after = mode === 'next' ? next : mode === 'previous' ? previous.at(-1) ?? null : mode === 'list' ? cursor : null
    const history = mode === 'next' ? [...previous, cursor] : mode === 'previous' ? previous.slice(0, -1) : mode === 'list' ? previous : []
    setBusy(true)
    setError(''); setDetail(null); setResolvedPin(undefined)
    try {
      if (routeError) throw new Error(routeError)
      if (mode === 'resolve') {
        const matched = await resolveIdentity(assetUuid.trim(), controller.signal), entity = await getEntity(matched.identity.entityId, controller.signal)
        if (disposedRef.current || currentRequest !== requestIdRef.current) return
        if (entity.tenantId !== matched.identity.tenantId || entity.version !== matched.entityVersion) throw new Error('定位后资产已变化，请重新按标识读取。')
        setLinkedEntity(entity.id); location.write({ ...selected(), entityId: entity.id }); setResolvedPin(identityPin(matched.identity)); setDetail(entity); return
      }
      if (mode === 'detail') {
        if (!id) throw new Error('请选择资产')
        setLinkedEntity(id); location.write({ ...selected(), entityId: id })
        const result = await getEntity(id, controller.signal)
        if (!disposedRef.current && currentRequest === requestIdRef.current) setDetail(result)
        return
      }
      location.write({ ...selected(), after, entityId: null }); setLinkedEntity(null)
      if (mode === 'sync') {
        const result = await syncZabbixHosts(controller.signal)
        if (disposedRef.current || currentRequest !== requestIdRef.current) return
        setSync(result)
      }
      const list = await pageEntities({ q: search, type: entityType, lifecycle }, after, controller.signal)
      if (disposedRef.current || currentRequest !== requestIdRef.current) return
      setItems(list.items)
      setLoaded(true)
      setNext(list.nextCursor); setCursor(after); setPrevious(history); setStorage(list.storage)
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
      <div className="inventory-heading"><div><h2>资产</h2><p>查看来源资产，关联指标与告警，追溯每一次观测。</p></div><span className="badge">授权范围内</span></div>
      <div className="stat-grid" aria-label="本页资产统计">
        <div className="stat-card"><div className="stat-label">本页资产<RouteIcon name="inventory" /></div><strong className="stat-value" data-stat="total">{loaded ? items.length : '—'}</strong><small>仅统计已读取的当前页</small></div>
        <div className="stat-card"><div className="stat-label">活跃资产<RouteIcon name="metrics" /></div><strong className="stat-value" data-stat="active">{loaded ? items.filter(item => item.lifecycle === 'ACTIVE').length : '—'}</strong><small>本页生命周期为 ACTIVE</small></div>
        <div className="stat-card"><div className="stat-label">来源实例<RouteIcon name="pipelines" /></div><strong className="stat-value" data-stat="sources">{loaded ? new Set(items.map(item => item.attributes.sourceInstanceId).filter(Boolean)).size : '—'}</strong><small>本页已记录的不同来源实例</small></div>
        <div className="stat-card"><div className="stat-label">其他状态<RouteIcon name="source-snapshots" /></div><strong className="stat-value" data-stat="other">{loaded ? items.filter(item => item.lifecycle !== 'ACTIVE').length : '—'}</strong><small>本页非 ACTIVE 生命周期</small></div>
      </div>
      <div className="asset-workspace">
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
        <Button
          variant="outline"
          disabled={busy || !authenticated || routeError !== ''}
          onClick={() => { void run('list') }}
        >
          刷新列表
        </Button>
        <Button variant="outline" disabled={busy} onClick={resetFilters}>重置资产筛选</Button>
        {linkedEntity && !detail ? <Button variant="outline" disabled={busy || !authenticated || routeError !== ''} onClick={() => { void run('detail', linkedEntity ?? undefined) }}>读取选中资产</Button> : null}</div>
      <p role="alert">{routeError || error}</p>
      {sync ? <SyncStatusLine result={sync} /> : null}
      {loaded ? <p data-inventory-page>{`${cursor && previous.length === 0 ? '恢复的游标页' : `第 ${previous.length + 1} 页`} · 本页 ${items.length} 条 · ${storage === 'postgres' ? 'PostgreSQL' : '开发内存'}`}</p> : null}
      {!loaded ? <div className="inventory-empty"><RouteIcon name="inventory" /><strong>准备读取资产</strong><p>配置当前会话后，点击「刷新列表」查看授权范围内的资产。</p></div> : null}
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
      {detail ? <EntityDetails item={detail} identity={resolvedPin} close={() => { setDetail(null); setResolvedPin(undefined); setLinkedEntity(null); location.write({ ...selected(), entityId: null }) }} /> : null}
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
function EntityDetails(props: { item: EntityItem; close: () => void; identity?: IdentityPin }) {
  const [item, setItem] = useState(props.item)
  return <section data-entity-detail aria-label="资产详情">
    <h3>{`资产详情：${item.name}`}</h3>
    <p>{`ID ${item.id} · ${item.entityType} · ${item.lifecycle} · 版本 ${item.version}`}</p>
    <p>{`来源 ${item.attributes.source || '未记录'} / 实例 ${item.attributes.sourceInstanceId || '未记录'} / ${item.attributes.dataMode || '采集模式未记录'}`}</p>
    <p>{`Host ID ${item.attributes.hostId || '未记录'} · IP ${item.attributes.ip || '未记录'} · 状态 ${item.attributes.status || '未记录'}`}</p>
    <p>{`最后观测 ${item.attributes.lastSeen || '未记录'}`}</p>
    <p>{`Raw 引用 ${item.attributes.rawReference || '未记录'}`}</p>
    <p>{item.attributes.pipelineId && item.attributes.pipelineRevision ? `映射 ${item.attributes.pipelineId}@${item.attributes.pipelineRevision} / ${item.attributes.pipelineDigest}` : '历史映射版本未记录'}</p>
    <p>详情是当前资产投影；Raw 引用不代表原始记录仍留存，也不是根因证明。</p>
    <p>{`负责人 ${item.attributes.owner || "未提供"} · 环境 ${item.attributes.environment || "未提供"}`}</p>
    {item.attributes.fieldAuthority ? <p data-entity-field-authority>{`补充字段 ${item.attributes.fieldAuthority.fields.join("、")} · 来源 ${item.attributes.fieldAuthority.sourceInstanceId} · 观测 ${item.attributes.fieldAuthority.observedAt} · 到期 ${item.attributes.fieldAuthority.expiresAt}；过期后仅保留最后已知字段，不能视为新鲜观测。`}</p> : null}
    <ObservationHistory entity={item} />
    <SourcePresence entity={item} />
    <AssetIdentities entity={props.item} changed={setItem} />
    <SourceReviews entity={props.item} changed={setItem} identity={props.identity} />
    <Button variant="outline" onClick={props.close}>关闭详情</Button>
  </section>
}
