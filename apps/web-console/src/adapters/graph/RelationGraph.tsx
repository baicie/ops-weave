import { useEffect, useRef, useState } from 'react'
import type { Graph } from '@antv/g6'
export type RelationNode = { id: string; label: string; detail: string }
export type RelationLink = { id: string; source: string; target: string; label: string }
export function RelationGraph(props: { nodes: RelationNode[]; edges: RelationLink[]; selected: string; select: (id: string) => void; label: string }) {
  const [status, setStatus] = useState('loading')
  const hostRef = useRef<HTMLDivElement | null>(null)
  const graphRef = useRef<Graph | undefined>(undefined)
  const disposedRef = useRef(false)
  const observerRef = useRef<ResizeObserver | undefined>(undefined)
  const themeObserverRef = useRef<MutationObserver | undefined>(undefined)
  const sequenceRef = useRef(0)
  const chainRef = useRef(Promise.resolve())
  const signatureRef = useRef('')
  const selectRef = useRef(props.select)
  const propsRef = useRef(props)
  selectRef.current = props.select
  propsRef.current = props

  function paint() {
    const { nodes, edges, selected } = propsRef.current
    const graph = graphRef.current
    if (!graph || disposedRef.current) return
    const next = JSON.stringify([nodes, edges, selected, document.documentElement.dataset.theme])
    if (next === signatureRef.current) return
    signatureRef.current = next
    const version = ++sequenceRef.current
    const g = graph
    chainRef.current = chainRef.current.then(async () => {
      if (disposedRef.current || version !== sequenceRef.current) return
      setStatus('loading')
      const css = getComputedStyle(document.documentElement)
      g.setOptions({
        node: {
          type: 'rect',
          style: {
            size: [180, 62],
            radius: 9,
            fill: css.getPropertyValue('--ow-surface').trim(),
            stroke: css.getPropertyValue('--ow-line').trim(),
            lineWidth: 1.5,
            labelText: d => String(d.data?.label ?? ''),
            labelFill: css.getPropertyValue('--ow-ink').trim(),
            labelFontSize: 16,
            labelPlacement: 'center',
          },
          state: {
            selected: {
              stroke: css.getPropertyValue('--ow-accent').trim(),
              lineWidth: 2.5,
              fill: css.getPropertyValue('--ow-accent-soft').trim(),
            },
          },
        },
        edge: {
          type: 'cubic-horizontal',
          style: {
            stroke: css.getPropertyValue('--ow-graph-edge').trim(),
            lineWidth: 1.5,
            endArrow: true,
            labelText: d => String(d.data?.label ?? ''),
            labelFontSize: 13,
            labelFill: css.getPropertyValue('--ow-muted').trim(),
            labelBackground: true,
            labelBackgroundFill: css.getPropertyValue('--ow-surface').trim(),
          },
        },
      })
      g.setData({
        nodes: nodes.map(n => ({ id: n.id, data: { label: n.label }, states: n.id === selected ? ['selected'] : [] })),
        edges: edges.map(e => ({ id: e.id, source: e.source, target: e.target, data: { label: e.label } })),
      })
      await g.render()
      if (!disposedRef.current && version === sequenceRef.current) setStatus('ready')
    }).catch(() => { if (!disposedRef.current) setStatus('error') })
  }

  useEffect(() => {
    disposedRef.current = false
    const host = hostRef.current
    if (!host) return
    let cancelled = false
    void (async () => {
      try {
        const { Graph } = await import('@antv/g6')
        if (cancelled || disposedRef.current || !hostRef.current) return
        const graph = new Graph({
          container: host,
          width: Math.max(300, host.clientWidth),
          height: host.clientHeight || 470,
          animation: false,
          padding: 36,
          autoFit: 'view',
          zoomRange: [.2, 2],
          layout: { type: 'antv-dagre', rankdir: 'LR', nodesep: 34, ranksep: 100 },
          behaviors: ['drag-canvas', 'zoom-canvas', 'drag-element'],
        })
        graphRef.current = graph
        graph.on('node:click', e => {
          const target = 'target' in e ? e.target : undefined
          const id = target && 'id' in target ? target.id : undefined
          if (typeof id === 'string') selectRef.current(id)
        })
        const observer = new ResizeObserver(() => {
          if (!disposedRef.current && graphRef.current && hostRef.current?.clientWidth) {
            graphRef.current.setSize(hostRef.current.clientWidth, hostRef.current.clientHeight)
          }
        })
        observer.observe(host)
        observerRef.current = observer
        const themeObserver = new MutationObserver(paint)
        themeObserver.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] })
        themeObserverRef.current = themeObserver
        paint()
      } catch {
        if (!disposedRef.current) setStatus('error')
      }
    })()
    return () => {
      cancelled = true
      disposedRef.current = true
      ++sequenceRef.current
      observerRef.current?.disconnect()
      themeObserverRef.current?.disconnect()
      graphRef.current?.destroy()
      graphRef.current = undefined
    }
  }, [])

  useEffect(() => { paint() }, [props.nodes, props.edges, props.selected])

  function zoom(factor: number) {
    if (graphRef.current && status === 'ready') void graphRef.current.zoomBy(factor, false)
  }

  return <div className="relation-graph-frame" data-engine="g6" data-graph-status={status}>
    <div className="graph-tools">
      <span>{props.nodes.length + ' 个节点 · ' + props.edges.length + ' 条关系'}</span>
      <div>
        <button type="button" aria-label="缩小关系图" disabled={status !== 'ready'} onClick={() => zoom(.8)}>−</button>
        <button type="button" aria-label="放大关系图" disabled={status !== 'ready'} onClick={() => zoom(1.25)}>＋</button>
        <button type="button" disabled={status !== 'ready'} onClick={() => void graphRef.current?.fitView(undefined, false)}>适应视图</button>
      </div>
    </div>
    <div className="relation-graph" role="img" aria-label={props.label} ref={hostRef} />
    {status !== 'ready' ? <div className="graph-message" role="status">{status === 'error' ? '关系图加载失败，请刷新重试。' : '正在绘制关系图…'}</div> : null}
    <div className="graph-caption">点击节点查看详情 · 拖动画布平移 · 滚轮缩放</div>
  </div>
}
