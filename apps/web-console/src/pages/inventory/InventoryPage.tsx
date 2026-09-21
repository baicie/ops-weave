import { createSignal, For, onCleanup, Show } from '@zeus-js/zeus'
import { forItem } from '../../adapters/zeus-ui/for-item.ts'
import { ZwButton } from '../../adapters/zeus-ui/ZwButton.tsx'
import { ZwInput } from '../../adapters/zeus-ui/ZwInput.tsx'
import { listEntities, syncZabbixHosts, type EntityItem, type HostSyncResult } from '../../api/entities.ts'

export function InventoryPage() {
  const [token, setToken] = createSignal('')
  const [busy, setBusy] = createSignal(false)
  const [error, setError] = createSignal('')
  const [items, setItems] = createSignal<EntityItem[]>([])
  const [loaded, setLoaded] = createSignal(false)
  const [sync, setSync] = createSignal<HostSyncResult | null>(null)
  let abort: AbortController | undefined
  let disposed = false
  let requestId = 0

  onCleanup(() => {
    disposed = true
    abort?.abort()
  })

  async function run(mode: 'list' | 'sync') {
    abort?.abort()
    abort = new AbortController()
    const currentRequest = ++requestId
    const timeout = window.setTimeout(() => abort?.abort(), 35000)
    setBusy(true)
    setError('')
    try {
      if (mode === 'sync') {
        const result = await syncZabbixHosts(token(), abort.signal)
        if (disposed || currentRequest !== requestId) return
        setSync(result)
      }
      const list = await listEntities(token(), abort.signal)
      if (disposed || currentRequest !== requestId) return
      setItems(list.items)
      setLoaded(true)
    } catch (cause) {
      if (disposed || currentRequest !== requestId) return
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
      <p>列表来自平台 API。租户由服务端 Principal 决定，页面不会提交 tenant 或权限。同步失败时保留当前表格。</p>
      <label>
        平台开发 Token（仅保存在当前页面内存）
        <ZwInput
          type="password"
          autocomplete="off"
          value={token()}
          onValueChange={setToken}
        />
      </label>
      <div class="actions">
        <ZwButton
          variant="primary"
          disabled={busy() || token().length < 32}
          loading={busy()}
          onPress={() => { void run('sync') }}
        >
          {busy() ? '正在同步…' : '同步 Zabbix Host'}
        </ZwButton>
        <ZwButton
          variant="outline"
          disabled={busy() || token().length < 32}
          onPress={() => { void run('list') }}
        >
          刷新列表
        </ZwButton>
      </div>
      <p role="alert">{error()}</p>
      <Show when={sync()}>
        <SyncStatusLine result={sync() as HostSyncResult} />
      </Show>
      <Show when={loaded() && items().length === 0}>
        <p>当前租户没有可见资产。</p>
      </Show>
      <Show when={items().length > 0}>
        <table>
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
            </tr>
          </thead>
          <tbody>
            <For each={items()}>
              {row => <EntityRow item={forItem(row)} />}
            </For>
          </tbody>
        </table>
      </Show>
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

function EntityRow(props: { item: EntityItem }) {
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
    </tr>
  )
}
