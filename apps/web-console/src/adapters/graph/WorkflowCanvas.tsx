import { outputKind, outputNodeNames } from '../../api/workflow-output.ts'
import { useEffect, useRef, useState } from 'react'
import type { Graph, Node } from '@antv/x6'
import { OPERATORS, type Definition, type Layout, type NodeType, type Step, type Operator } from '../../api/workflows.ts'
import { connectionProblem } from '../../state/workflow-graph.ts'
import { WorkflowNodeMenu, type WorkflowNodeMenuHandle } from '../../components/workflows/WorkflowNodeMenu.tsx'

const labels: Record<NodeType, string> = { SOURCE: '数据输入', MAP: '字段映射', TRIM: '去除空白', EMPTY_TO_NULL: '空串转空值', DEFAULT: '补充默认值', ENUM_MAP: '枚举替换', SCALE: '数值换算', FILTER: '条件过滤', MERGE: '分支合流', VALIDATE: '模型校验', OUTPUT: '输出预览' }
const glyphs: Record<NodeType, string> = { SOURCE: 'IN', MAP: '⇄', TRIM: 'Aa', EMPTY_TO_NULL: '∅', DEFAULT: '＋', ENUM_MAP: '≍', SCALE: '×', FILTER: '▽', MERGE: '⋈', VALIDATE: '✓', OUTPUT: 'OUT' }
let engine: Promise<typeof import('@antv/x6')> | undefined
function loadEngine() {
  return engine ??= import('@antv/x6').then(api => {
    api.Shape.HTML.register({
      shape: 'opsweave-workflow-node', width: 200, height: 96, effect: ['data'], html(cell) {
        const data = cell.getData()
        const button = document.createElement('button')
        button.type = 'button'
        button.className = 'workflow-node' + (data.selected ? ' is-selected' : '')
        button.dataset.nodeId = cell.id
        button.setAttribute('aria-label', data.label + '节点 ' + cell.id)
        button.setAttribute('aria-pressed', String(data.selected))
        button.dataset.kind = data.type
        const header = document.createElement('span'); header.className = 'canvas-node-header'
        const label = document.createElement('strong'); label.textContent = data.label
        const number = document.createElement('small'); number.textContent = data.number
        header.append(label, number)
        const subtitle = document.createElement('span'); subtitle.className = 'canvas-node-summary'; subtitle.textContent = data.subtitle
        const footer = document.createElement('span'); footer.className = 'canvas-node-footer'
        const icon = document.createElement('b'); icon.textContent = glyphs[data.type as NodeType]
        const phase = document.createElement('small'); phase.textContent = data.phase
        footer.append(icon, phase)
        if (data.status) {
          const result = document.createElement('small'); result.className = 'canvas-node-result'; result.dataset.status = data.status
          result.textContent = data.status === 'OK' ? '通过' : data.status === 'ERROR' ? '失败' : data.status === 'FILTERED' ? '过滤' : '跳过'
          footer.append(result)
        }
        button.append(header, subtitle, footer)
        button.addEventListener('click', () => data.select(cell.id))
        button.addEventListener('contextmenu', e => {
          e.preventDefault(); e.stopPropagation()
          data.menu({ id: cell.id, x: e.clientX, y: e.clientY, trigger: button })
        })
        button.addEventListener('keydown', (e) => {
          if (e.key === 'ContextMenu' || e.key === 'F10' && e.shiftKey) {
            e.preventDefault(); e.stopPropagation()
            const box = button.getBoundingClientRect()
            data.menu({ id: cell.id, x: box.left + 24, y: box.top + 24, trigger: button })
            return
          }
          if (!['ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight'].includes(e.key) || data.locked()) return
          e.preventDefault()
          const p = (cell as Node).position()
          data.move(cell.id, p.x + (e.key === 'ArrowRight' ? 10 : e.key === 'ArrowLeft' ? -10 : 0), p.y + (e.key === 'ArrowDown' ? 10 : e.key === 'ArrowUp' ? -10 : 0))
        })
        return button
      },
    })
    return api
  }).catch(error => { engine = undefined; throw error })
}

export function WorkflowCanvas(props: {
  definition: Definition
  layout: Layout
  selected: string
  select: (id: string) => void
  zoom: number
  onZoom: (z: number) => void
  fit: number
  steps?: Step[]
  outputTitle: string
  sourceTitle: string
  locked: boolean
  move: (id: string, x: number, y: number) => void
  add: (type: Operator, position: { x: number; y: number }) => void
  connect: (from: string, to: string) => void
  removeEdge: (from: string, to: string) => void
}) {
  const [status, setStatus] = useState('loading')
  const [selectedEdge, setSelectedEdge] = useState<{ from: string; to: string } | null>(null)
  const hostRef = useRef<HTMLDivElement | null>(null)
  const viewportRef = useRef<HTMLDivElement | null>(null)
  const graphRef = useRef<Graph | undefined>(undefined)
  const disposedRef = useRef(false)
  const resizingRef = useRef<ResizeObserver | undefined>(undefined)
  const syncingRef = useRef(false)
  const signatureRef = useRef('')
  const fitRequestRef = useRef(props.fit)
  const selectRef = useRef(props.select)
  const lockedRef = useRef(props.locked)
  const moveRef = useRef(props.move)
  const onZoomRef = useRef(props.onZoom)
  const propsRef = useRef(props)
  const menuRef = useRef<WorkflowNodeMenuHandle>(null)
  selectRef.current = props.select
  lockedRef.current = props.locked
  moveRef.current = props.move
  onZoomRef.current = props.onZoom
  propsRef.current = props

  const move = (id: string, x: number, y: number) => {
    if (!lockedRef.current) moveRef.current(id, Math.max(0, Math.min(4000, Math.round(x))), Math.max(0, Math.min(4000, Math.round(y))))
  }

  function fitView() {
    graphRef.current?.zoomToFit({ padding: 32, maxScale: 1 })
    if (graphRef.current) onZoomRef.current(graphRef.current.zoom())
  }

  function sync() {
    const { definition: d, layout: positions, selected, zoom, fit: nextFit } = propsRef.current
    const graph = graphRef.current
    if (!graph || disposedRef.current) return
    syncingRef.current = true
    const next = d.id + '@' + d.revision + ':' + d.nodes.map(n => n.id).join(',') + ':' + d.edges.map(e => e.from + '>' + e.to).join(',')
    // Hot updates preserve refs across graph replacement; rebuild missing cells without losing edits.
    if (signatureRef.current !== next || d.nodes.some(n => !graph.getCellById(n.id))) {
      signatureRef.current = next
      graph.clearCells()
      graph.addNodes(d.nodes.map(n => ({ id: n.id, shape: 'opsweave-workflow-node', ...positions[n.id], data: {}, ports: {
        groups: { in: { position: 'top', attrs: { circle: { r: 5, magnet: 'passive', stroke: 'var(--ow-accent)', fill: 'var(--ow-surface)' } } }, out: { position: 'bottom', attrs: { circle: { r: 5, magnet: true, stroke: 'var(--ow-accent)', fill: 'var(--ow-surface)' } } } },
        items: [...(n.type === 'SOURCE' ? [] : [{ id: 'in', group: 'in' }]), ...(n.type === 'OUTPUT' ? [] : [{ id: 'out', group: 'out' }])],
      } })))
      graph.addEdges(d.edges.map((e, i) => ({
        id: 'edge-' + i,
        source: { cell: e.from, port: 'out', anchor: 'center', connectionPoint: 'anchor' },
        target: { cell: e.to, port: 'in', anchor: 'center', connectionPoint: { name: 'anchor', args: { offset: 6 } } },
        connector: { name: 'rounded' },
        // Tight legacy rows must not inflate two port circles into overlapping obstacles.
        router: d.edges.length > d.nodes.length - 1
          ? { name: 'manhattan', args: { padding: 8, startDirections: ['bottom'], endDirections: ['top'], perpendicular: false } }
          : { name: 'orth', args: { padding: 4 } },
        attrs: { line: { stroke: 'var(--ow-graph-edge)', strokeWidth: 1.3, targetMarker: { name: 'classic', size: 5 } } },
        zIndex: 0,
      })))
    }
    for (const n of d.nodes) {
      const cell = graph.getCellById(n.id) as Node
      cell.resize(200, 96)
      const p = positions[n.id] ?? { x: 0, y: 0 }
      cell.position(p.x, p.y)
      const subtitle = n.type === 'OUTPUT' ? propsRef.current.outputTitle + ' · 样本预览' : n.type === 'SOURCE' ? propsRef.current.sourceTitle : n.type === 'MAP' ? Object.keys(n.config).length + ' 个字段映射' : n.type === 'VALIDATE' ? propsRef.current.outputTitle + ' · 字段与类型' : n.type === 'TRIM' ? '去除文本首尾空白' : n.config.field ? '字段：' + n.config.field : '按配置处理记录'
      const status = propsRef.current.steps?.find(step => step.nodeId === n.id)?.status
      const data = cell.getData()
      if (data.selected !== (n.id === selected) || data.subtitle !== subtitle || data.type !== n.type || data.status !== status) {
        cell.setData({
          type: n.type,
          label: n.type === 'OUTPUT' ? outputNodeNames[outputKind(d.target)] : n.type === 'VALIDATE' && outputKind(d.target) !== 'ENTITY' ? 'mappingPin' in d.target && d.target.mappingPin ? '标准指标校验' : '格式校验' : labels[n.type],
          subtitle,
          number: String(d.nodes.indexOf(n) + 1).padStart(2, '0'),
          phase: n.type === 'SOURCE' ? '数据来源' : n.type === 'OUTPUT' ? '输出' : n.type === 'VALIDATE' ? '校验' : '处理',
          status,
          selected: n.id === selected,
          select: (id: string) => selectRef.current(id),
          menu: (entry: Parameters<WorkflowNodeMenuHandle['open']>[0]) => menuRef.current?.open(entry),
          locked: () => lockedRef.current,
          move,
        })
      }
    }
    if (Math.abs(graph.zoom() - zoom) > .001) graph.zoomTo(zoom)
    if (nextFit !== fitRequestRef.current) { fitRequestRef.current = nextFit; fitView() }
    syncingRef.current = false
  }

  useEffect(() => {
    disposedRef.current = false
    const host = hostRef.current
    const viewport = viewportRef.current
    if (!host || !viewport) return
    let cancelled = false
    void (async () => {
      try {
        const api = await loadEngine()
        if (cancelled || disposedRef.current || !hostRef.current) return
        const graph = new api.Graph({
          container: host,
          // Replacing a small, bounded graph must remove old HTML views before IDs are reused.
          async: false,
          width: viewport.clientWidth,
          height: viewport.clientHeight,
          grid: { size: 20, visible: false },
          background: { color: 'transparent' },
          panning: { enabled: true, eventTypes: ['leftMouseDown'] },
          mousewheel: { enabled: true, modifiers: ['ctrl', 'meta'], minScale: .25, maxScale: 1.5 },
          scaling: { min: .25, max: 1.5 },
          interacting: () => ({ nodeMovable: !lockedRef.current, edgeMovable: false, magnetConnectable: !lockedRef.current, arrowheadMovable: false }),
          connecting: { allowBlank: false, allowLoop: false, allowEdge: false, anchor: 'center', connectionPoint: 'anchor', snap: { radius: 24 },
            createEdge: () => new api.Shape.Edge({ attrs: { line: { stroke: 'var(--ow-accent)', strokeWidth: 1.5, targetMarker: { name: 'classic', size: 5 } } } }),
            validateConnection: ({ sourceCell, targetCell, sourcePort, targetPort }) => !lockedRef.current && sourcePort === 'out' && targetPort === 'in' && Boolean(sourceCell && targetCell) && !connectionProblem(propsRef.current.definition, sourceCell!.id, targetCell!.id),
          },
        })
        graphRef.current = graph
        graph.use(new api.Snapline({ enabled: true }))
        graph.on('node:click', ({ node }) => selectRef.current(node.id))
        graph.on('node:moved', ({ node }) => { if (!syncingRef.current) { const p = node.position(); move(node.id, p.x, p.y) } })
        graph.on('edge:connected', ({ edge }) => { if (!syncingRef.current && !lockedRef.current) { propsRef.current.connect(edge.getSourceCellId(), edge.getTargetCellId()); graph.removeEdge(edge); sync() } })
        graph.on('edge:click', ({ edge }) => setSelectedEdge({ from: edge.getSourceCellId(), to: edge.getTargetCellId() }))
        graph.on('scale', ({ sx }) => { if (!syncingRef.current) onZoomRef.current(sx) })
        let width = viewport.clientWidth
        let height = viewport.clientHeight
        const resizing = new ResizeObserver(() => {
          const current = viewportRef.current
          if (graphRef.current && current?.clientWidth) {
            const changed = width !== current.clientWidth || height !== current.clientHeight
            width = current.clientWidth; height = current.clientHeight
            graphRef.current.resize(width, height)
            if (changed) fitView()
          }
        })
        resizing.observe(viewport)
        resizingRef.current = resizing
        sync()
        fitView()
        setStatus('ready')
      } catch {
        if (!disposedRef.current) setStatus('error')
      }
    })()
    return () => {
      cancelled = true
      disposedRef.current = true
      resizingRef.current?.disconnect()
      graphRef.current?.dispose()
      graphRef.current = undefined
    }
  }, [])

  useEffect(() => { sync() }, [props.definition, props.layout, props.selected, props.zoom, props.fit, props.locked, props.steps, props.outputTitle, props.sourceTitle])

  return <div className="graph-frame" data-engine="x6" data-graph-status={status}>
    <WorkflowNodeMenu ref={menuRef} readOnly={props.locked} edit={props.select} />
    <div className="workflow-canvas-viewport" ref={viewportRef}
      onDragOver={event => { if (!props.locked && event.dataTransfer.types.includes('application/x-opsweave-operator')) { event.preventDefault(); event.dataTransfer.dropEffect = 'copy' } }}
      onDrop={event => { event.preventDefault(); const type = event.dataTransfer.getData('application/x-opsweave-operator') as Operator; if (props.locked || !OPERATORS.includes(type) || !graphRef.current) return; const point = graphRef.current.clientToLocal(event.clientX, event.clientY); props.add(type, { x: Math.max(0, Math.min(3800, Math.round(point.x - 100))), y: Math.max(0, Math.min(3904, Math.round(point.y - 48))) }) }}>
      <div className="workflow-canvas x6-canvas" aria-label="工作流画布" ref={hostRef} /></div>
    {status !== 'ready' ? <div className="graph-message" role="status">{status === 'error' ? '画布加载失败，请刷新页面重试。' : '正在加载工作流画布…'}</div> : null}
    {selectedEdge && props.definition.edges.some(edge => edge.from === selectedEdge.from && edge.to === selectedEdge.to) ? <div className="workflow-selected-edge"><span>{selectedEdge.from} → {selectedEdge.to}</span><button type="button" disabled={props.locked || props.definition.nodes.find(node => node.id === selectedEdge.from)?.type === 'SOURCE' || props.definition.nodes.find(node => node.id === selectedEdge.to)?.type === 'OUTPUT'} onClick={() => { props.removeEdge(selectedEdge.from, selectedEdge.to); setSelectedEdge(null) }}>移除选中连接</button></div> : null}
  </div>
}
