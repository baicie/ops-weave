import { useEffect, useRef, useState } from 'react'
import { RelationGraph } from '../../adapters/graph/RelationGraph.tsx'
import { pageEntities, type EntityItem, type EntityPage } from '../../api/entities.ts'
import { originLabel, readTopology, type Topology } from '../../api/topology.ts'
import { usePlatformSession } from '../../state/platform-session.ts'

export function TopologyPage() {
  const [assets, setAssets] = useState<EntityPage | null>(null)
  const [query, setQuery] = useState('')
  const [loadedQuery, setLoadedQuery] = useState('')
  const [topology, setTopology] = useState<Topology | null>(null)
  const [selected, setSelected] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const activeRef = useRef<AbortController | undefined>(undefined)
  const disposedRef = useRef(false)

  const ready = usePlatformSession(() => {
    activeRef.current?.abort()
    setAssets(null); setTopology(null); setSelected(''); setQuery(''); setLoadedQuery(''); setError(''); setBusy(false)
  })
  useEffect(() => () => { disposedRef.current = true; activeRef.current?.abort() }, [])

  async function request(work: (signal: AbortSignal, current: () => boolean) => Promise<void>) {
    activeRef.current?.abort()
    const c = new AbortController(); activeRef.current = c
    const current = () => !disposedRef.current && activeRef.current === c && !c.signal.aborted
    setBusy(true); setError('')
    try { await work(c.signal, current) }
    catch (e) { if (current()) setError(e instanceof Error ? e.message : '读取失败') }
    finally { if (current()) setBusy(false) }
  }
  function load(next = false) {
    if (!ready || busy) return
    const q = next ? loadedQuery : query.trim()
    void request(async (signal, current) => {
      const result = await pageEntities({ q, type: '', lifecycle: '' }, next ? assets?.nextCursor ?? null : null, signal)
      if (current()) { setAssets(result); setLoadedQuery(q) }
    })
  }
  function inspect(asset: EntityItem) {
    if (busy || !ready) return
    setTopology(null); setSelected('')
    void request(async (signal, current) => {
      const result = await readTopology(asset.id, asset.tenantId, signal)
      if (current()) { setTopology(result); setSelected(asset.id) }
    })
  }
  const node = topology?.nodes.find(n => n.id === selected)
  const graphNodes = topology?.nodes.map(n => ({ id: n.id, label: n.name, detail: n.type })) ?? []
  const graphEdges = topology?.edges.map(e => ({ id: e.id, source: e.from, target: e.to, label: e.type })) ?? []

  return (
    <section className="topology-page">
      <header className="page-title">
        <div>
          <span className="eyebrow">资源观测</span>
          <h2>资产关系图</h2>
          <p>选择一项资产，查看它与其他资产之间已保存的直接关系。</p>
        </div>
        <a className="quiet-link" href="#/modeling/relations">定义关系类型 ↗</a>
      </header>
      <p role="alert">{error}</p>
      <div className="topology-workbench">
        <aside className="topology-assets">
          <h3>选择起点资产</h3>
          <form onSubmit={e => { e.preventDefault(); load() }}>
            <label>资产名称或 IP
              <input aria-label="查找关系图资产" type="search" value={query} maxLength={100} placeholder="搜索资产…" onInput={e => setQuery((e.target as HTMLInputElement).value)} />
            </label>
            <button type="submit" disabled={!ready || busy}>读取资产</button>
          </form>
          {!ready ? <p className="empty-note">先在上方建立平台会话，再读取你的资产。</p> : null}
          {assets && !assets.items.length ? <p className="empty-note">没有匹配的受权资产。</p> : null}
          <div className="topology-asset-list">
            {(assets?.items ?? []).map(item => (
              <AssetChoice key={item.id} asset={item} current={topology?.centerId ?? ''} disabled={busy} select={inspect} />
            ))}
          </div>
          {assets?.nextCursor ? <button type="button" disabled={busy} onClick={() => load(true)}>下一页资产</button> : null}
        </aside>
        <div className="topology-main">
          {!topology ? (
            <div className="product-empty">
              <span className="empty-symbol">⌘</span>
              <h3>{busy ? '正在读取…' : '从一项资产开始'}</h3>
              <p>在左侧搜索并选择资产。关系图会显示有权查看的直接关联，并保留来源标记。</p>
              <span>只读浏览 · 最多 50 条关系 · 不推断依赖</span>
            </div>
          ) : null}
          {topology ? (
            <>
              <RelationGraph nodes={graphNodes} edges={graphEdges} selected={selected} select={setSelected} label="资产直接关系图" />
              <div className="topology-coverage">
                <span>{'读取时间 ' + new Date(topology.asOf).toLocaleString()}</span>
                <span>已保存的当前一跳关系</span>
              </div>
              {!topology.edges.length ? <p className="source-access-hint">该资产目前没有可展示的已保存关系。关系类型定义不会自动生成资产之间的关系。</p> : null}
              {topology.truncated ? <p className="source-access-hint">关系超过 50 条，当前图已截断，不代表完整拓扑。</p> : null}
              <div className="topology-detail">
                <section>
                  <h3>选中资产</h3>
                  <strong>{node?.name}</strong>
                  <dl>
                    <dt>类型</dt><dd>{node?.type}</dd>
                    <dt>生命周期</dt><dd>{node?.lifecycle}</dd>
                    <dt>来源</dt><dd>{originLabel(node?.dataMode ?? 'unknown')}</dd>
                    <dt>资产 ID</dt><dd>{node?.id}</dd>
                  </dl>
                </section>
                <section>
                  <h3>图中资产</h3>
                  <div className="graph-node-list">
                    {topology.nodes.map(item => (
                      <button key={item.id} type="button" aria-pressed={selected === item.id ? 'true' : 'false'} onClick={() => setSelected(item.id)}>{item.name}</button>
                    ))}
                  </div>
                  <p className="empty-note">也可通过此列表选择节点，查看名称和来源详情。</p>
                </section>
              </div>
              {topology.edges.length ? (
                <div className="relation-table-wrap">
                  <table>
                    <caption>关系明细 · 箭头从起点指向终点</caption>
                    <thead><tr><th>起点</th><th>关系</th><th>终点</th><th>来源</th></tr></thead>
                    <tbody>
                      {topology.edges.map(item => (
                        <tr key={item.id}>
                          <td>{topology.nodes.find(n => n.id === item.from)?.name}</td>
                          <td>{item.type}</td>
                          <td>{topology.nodes.find(n => n.id === item.to)?.name}</td>
                          <td>{originLabel(item.dataMode)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              ) : null}
            </>
          ) : null}
        </div>
      </div>
    </section>
  )
}

function AssetChoice(props: { asset: EntityItem; current: string; disabled: boolean; select: (a: EntityItem) => void }) {
  return (
    <button type="button" className="topology-asset" aria-pressed={props.current === props.asset.id ? 'true' : 'false'} disabled={props.disabled} onClick={() => props.select(props.asset)}>
      <strong>{props.asset.name}</strong>
      <small>{props.asset.entityType + ' · ' + (props.asset.attributes.ip || '未记录 IP')}</small>
      <span>{originLabel(props.asset.attributes.dataMode === 'labeled-fixture' ? 'fixture' : props.asset.attributes.dataMode)}</span>
    </button>
  )
}
