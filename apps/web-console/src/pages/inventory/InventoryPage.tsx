import { SourceReviews } from './SourceReviews.tsx'
import { SourcePresence } from './SourcePresence.tsx'
import { AssetIdentities } from './AssetIdentities.tsx'
import { resolveIdentity, identityPin, type IdentityPin } from '../../api/asset-identities.ts'
import { usePlatformSession } from '../../state/platform-session.ts'
import { useViewLocation, selectionSessionReset } from '../../state/view-location.ts'
import { inventoryDefault, inventoryHash, inventorySelection, type InventorySelection } from '../../state/view-selection.ts'
import { ObservationHistory } from './ObservationHistory.tsx'
import { createSignal, For, onCleanup, Show } from '@zeus-js/zeus'
import { forItem } from '../../adapters/zeus-ui/for-item.ts'
import { ZwButton } from '../../adapters/zeus-ui/ZwButton.tsx'
import { ZwInput } from '../../adapters/zeus-ui/ZwInput.tsx'
import { pageEntities, getEntity, EntityRequestError, syncZabbixHosts, type EntityItem, type HostSyncResult } from '../../api/entities.ts'

export function InventoryPage() {
  const resetOnSession = selectionSessionReset()
  const authenticated = usePlatformSession(change => {
    const reset = resetOnSession.changed(change), selection = reset ? inventoryDefault() : selected()
    applySelection(selection); setSync(null)
    if (reset) { setRouteError(''); location.write(selection, true) }
    if (change.error) setError(change.error.message)
  })
  const [busy, setBusy] = createSignal(false)
  const [error, setError] = createSignal('')
  const [items, setItems] = createSignal<EntityItem[]>([])
  const [loaded, setLoaded] = createSignal(false)
  const [sync, setSync] = createSignal<HostSyncResult | null>(null)
  const [search, setSearch] = createSignal('')
  const [lifecycle, setLifecycle] = createSignal('')
  const [entityType, setEntityType] = createSignal('host')
  const [next, setNext] = createSignal<string | null>(null)
  const [cursor, setCursor] = createSignal<string | null>(null)
  const [previous, setPrevious] = createSignal<(string | null)[]>([])
  const [storage, setStorage] = createSignal('')
  const [detail, setDetail] = createSignal<EntityItem | null>(null)
  const [linkedEntity, setLinkedEntity] = createSignal<string | null>(null)
  const [routeError, setRouteError] = createSignal('')
  const [assetUuid,setAssetUuid] = createSignal(''), [resolvedPin,setResolvedPin] = createSignal<IdentityPin | undefined>(undefined)
  let abort: AbortController | undefined
  let disposed = false
  let requestId = 0

  onCleanup(() => {
    disposed = true
    abort?.abort()
  })

  function invalidate() {
    abort?.abort(); requestId++; setBusy(false); setError(''); setItems([]); setLoaded(false)
    setNext(null); setCursor(null); setPrevious([]); setDetail(null); setStorage(''); setLinkedEntity(null)
    setAssetUuid(''); setResolvedPin(undefined)
  }
  function selected(): InventorySelection { return { q: search().trim(), type: entityType(), lifecycle: lifecycle(), after: cursor(), entityId: linkedEntity() } }
  function applySelection(value: InventorySelection) {
    invalidate(); setSync(null); setSearch(value.q); setLifecycle(value.lifecycle); setEntityType(value.type); setCursor(value.after); setLinkedEntity(value.entityId)
  }
  const location = useViewLocation('/inventory', inventorySelection, inventoryHash,
    value => { setRouteError(''); applySelection(value) }, cause => { applySelection(inventoryDefault()); setRouteError(cause.message) })
  function resetFilters() { applySelection(inventoryDefault()); setRouteError(''); location.write(inventoryDefault()) }
  function changeAssetUuid(value: string) { abort?.abort(); requestId++; setBusy(false); setError(''); setDetail(null); setResolvedPin(undefined); setAssetUuid(value) }
  async function run(mode: 'list' | 'sync' | 'next' | 'previous' | 'detail' | 'resolve', id?: string) {
    resetOnSession.beginRead()
    abort?.abort()
    const controller = new AbortController(); abort = controller
    const currentRequest = ++requestId
    const timeout = window.setTimeout(() => controller.abort(), 35000)
    const after = mode === 'next' ? next() : mode === 'previous' ? previous().at(-1) ?? null : mode === 'list' ? cursor() : null
    const history = mode === 'next' ? [...previous(), cursor()] : mode === 'previous' ? previous().slice(0, -1) : mode === 'list' ? previous() : []
    setBusy(true)
    setError(''); setDetail(null); setResolvedPin(undefined)
    try {
      if (routeError()) throw new Error(routeError())
      if(mode === 'resolve') {
        const matched = await resolveIdentity(assetUuid().trim(),controller.signal), entity = await getEntity(matched.identity.entityId,controller.signal)
        if(disposed || currentRequest !== requestId) return
        if(entity.tenantId !== matched.identity.tenantId || entity.version !== matched.entityVersion) throw new Error('定位后资产已变化，请重新按标识读取。')
        setLinkedEntity(entity.id); location.write(selected()); setResolvedPin(identityPin(matched.identity)); setDetail(entity); return
      }
      if (mode === 'detail') {
        if (!id) throw new Error('请选择资产')
        setLinkedEntity(id); location.write(selected())
        const result = await getEntity(id, controller.signal)
        if (!disposed && currentRequest === requestId) setDetail(result)
        return
      }
      location.write({ ...selected(), after, entityId: null }); setLinkedEntity(null)
      if (mode === 'sync') {
        const result = await syncZabbixHosts(controller.signal)
        if (disposed || currentRequest !== requestId) return
        setSync(result)
      }
      const list = await pageEntities({ q: search(), type: entityType(), lifecycle: lifecycle() }, after, controller.signal)
      if (disposed || currentRequest !== requestId) return
      setItems(list.items)
      setLoaded(true)
      setNext(list.nextCursor); setCursor(after); setPrevious(history); setStorage(list.storage)
    } catch (cause) {
      if (disposed || currentRequest !== requestId) return
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
      if (!disposed && currentRequest === requestId) setBusy(false)
    }
  }

  return (
    <section class="panel" data-page="inventory">
      <h2>资产</h2>
      <p>按服务端授权范围筛选和分页，每页最多 25 条。同步来源失败时保留当前表格；切换身份或失去权限会清空旧数据。</p>
      <p>地址保留已应用的筛选、游标与选中资产；刷新或返回后请重新读取，以核对当前权限。</p>
      <details><summary>按已登记强标识定位</summary><p>输入资产登记系统 UUID，平台在配置的命名空间内查找当前可管理的资产。定位后导入会保留身份依据。</p>
        <label>查找资产 UUID<ZwInput value={assetUuid()} onValueChange={changeAssetUuid} /></label>
        <ZwButton variant="outline" disabled={busy() || !authenticated() || !assetUuid().trim() || !!routeError()} onPress={() => { void run('resolve') }}>按强标识定位</ZwButton></details>
      <div class="metric-controls">
        <label>名称或 IP<ZwInput value={search()} onValueChange={value => { invalidate(); setSearch(value) }} /></label>
        <label>生命周期<select prop:value={lifecycle()} onChange={event => { invalidate(); setLifecycle((event.target as HTMLSelectElement).value) }}>
          <option value="">全部状态</option><option value="ACTIVE">ACTIVE</option><option value="INACTIVE">INACTIVE</option>
          <option value="DISCOVERED">DISCOVERED</option><option value="DELETED">DELETED</option><option value="ARCHIVED">ARCHIVED</option>
        </select></label>
        <label>资产类型<select prop:value={entityType()} onChange={event => { invalidate(); setEntityType((event.target as HTMLSelectElement).value) }}>
          <option value="host">Host</option><option value="">全部类型</option>
        </select></label>
      </div>
      <div class="actions">
        <ZwButton
          variant="primary"
          disabled={busy() || !authenticated() || routeError() !== ''}
          loading={busy()}
          onPress={() => { void run('sync') }}
        >
          {busy() ? '正在同步…' : '同步 Zabbix Host'}
        </ZwButton>
        <ZwButton
          variant="outline"
          disabled={busy() || !authenticated() || routeError() !== ''}
          onPress={() => { void run('list') }}
        >
          刷新列表
        </ZwButton>
      </div>
      <div class="actions"><ZwButton variant="outline" disabled={busy()} onPress={resetFilters}>重置资产筛选</ZwButton>
        <Show when={linkedEntity() && !detail()}><ZwButton variant="outline" disabled={busy() || !authenticated() || routeError() !== ''} onPress={() => { void run('detail', linkedEntity() ?? undefined) }}>读取选中资产</ZwButton></Show></div>
      <p role="alert">{routeError() || error()}</p>
      <Show when={sync()}>
        <SyncStatusLine result={sync() as HostSyncResult} />
      </Show>
      <Show when={loaded()}><p data-inventory-page>{`${cursor() && previous().length === 0 ? '恢复的游标页' : `第 ${previous().length + 1} 页`} · 本页 ${items().length} 条 · ${storage() === 'postgres' ? 'PostgreSQL' : '开发内存'}`}</p></Show>
      <div class="actions">
        <ZwButton variant="outline" disabled={busy() || previous().length === 0} onPress={() => { void run('previous') }}>上一页资产</ZwButton>
        <ZwButton variant="outline" disabled={busy() || next() === null} onPress={() => { void run('next') }}>下一页资产</ZwButton>
      </div>
      <Show when={loaded() && items().length === 0}>
        <p>当前筛选范围没有可见资产。</p>
      </Show>
      <Show when={items().length > 0}>
        <div class="pipeline-table"><table>
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
            <For each={items()}>
              {row => <EntityRow item={forItem(row)} busy={busy()} select={id => { void run('detail', id) }} />}
            </For>
          </tbody>
        </table></div>
      </Show>
      <Show when={detail()}><EntityDetails item={detail() as EntityItem} identity={resolvedPin()} close={() => { setDetail(null); setResolvedPin(undefined); setLinkedEntity(null); location.write(selected()) }} /></Show>
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
      <td>{item.attributes.source}</td>
      <td>{item.attributes.lastSeen}</td>
      <td>{item.attributes.rawReference}</td>
      <td>{item.lifecycle}</td>
      <td><ZwButton variant="outline" disabled={props.busy} onPress={() => props.select(item.id)}>查看详情</ZwButton></td>
    </tr>
  )
}
function EntityDetails(props: { item: EntityItem; close: () => void; identity?: IdentityPin }) {
  const [item, setItem] = createSignal(props.item)
  return <section data-entity-detail aria-label="资产详情">
    <h3>{`资产详情：${item().name}`}</h3>
    <p>{`ID ${item().id} · ${item().entityType} · ${item().lifecycle} · 版本 ${item().version}`}</p>
    <p>{`来源 ${item().attributes.source || '未记录'} / 实例 ${item().attributes.sourceInstanceId || '未记录'} / ${item().attributes.dataMode || '采集模式未记录'}`}</p>
    <p>{`Host ID ${item().attributes.hostId || '未记录'} · IP ${item().attributes.ip || '未记录'} · 状态 ${item().attributes.status || '未记录'}`}</p>
    <p>{`最后观测 ${item().attributes.lastSeen || '未记录'}`}</p>
    <p>{`Raw 引用 ${item().attributes.rawReference || '未记录'}`}</p>
    <p>{item().attributes.pipelineId && item().attributes.pipelineRevision ? `映射 ${item().attributes.pipelineId}@${item().attributes.pipelineRevision} / ${item().attributes.pipelineDigest}` : '历史映射版本未记录'}</p>
    <p>详情是当前资产投影；Raw 引用不代表原始记录仍留存，也不是根因证明。</p>
    <p>{`负责人 ${item().attributes.owner || "未提供"} · 环境 ${item().attributes.environment || "未提供"}`}</p>
    <Show when={item().attributes.fieldAuthority}><p data-entity-field-authority>{`补充字段 ${item().attributes.fieldAuthority?.fields.join("、")} · 来源 ${item().attributes.fieldAuthority?.sourceInstanceId} · 观测 ${item().attributes.fieldAuthority?.observedAt} · 到期 ${item().attributes.fieldAuthority?.expiresAt}；过期后仅保留最后已知字段，不能视为新鲜观测。`}</p></Show>
    <ObservationHistory entity={item()} />
    <SourcePresence entity={item()} />
    <AssetIdentities entity={props.item} changed={setItem} />
    <SourceReviews entity={props.item} changed={setItem} identity={props.identity} />
    <ZwButton variant="outline" onPress={props.close}>关闭详情</ZwButton>
  </section>
}
