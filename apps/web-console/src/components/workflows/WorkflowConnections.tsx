import { useEffect, useId, useRef, useState } from 'react'
import type { Definition } from '../../api/workflows.ts'
import { nodeLabels } from '../../api/workflow-runs.ts'

export function WorkflowConnections(props: { active: boolean; definition: Definition; locked: boolean; connect: (from: string, to: string) => void; remove: (from: string, to: string) => void }) {
  const [from, setFrom] = useState(''), [to, setTo] = useState('')
  const id = useId(), panel = useRef<HTMLDivElement>(null)
  useEffect(() => { if (!props.active && panel.current?.matches(':popover-open')) panel.current.hidePopover() }, [props.active])
  const nodes = props.definition.nodes
  const label = (id: string) => { const node = nodes.find(node => node.id === id); return node ? nodeLabels[node.type] + ' · ' + id : id }
  return <><button type="button" popoverTarget={id} aria-haspopup="dialog">连接与分支 <small>{props.definition.edges.length}</small></button>
    <div id={id} ref={panel} popover="auto" role="dialog" aria-label="连接管理" className="workflow-connection-editor"><header><strong>连接管理 · {props.definition.edges.length} 条</strong><button type="button" aria-label="关闭连接管理" popoverTarget={id} popoverTargetAction="hide" autoFocus>×</button></header>
    <div className="workflow-connection-form"><label>连接起点<select aria-label="连接起点" disabled={props.locked} value={from} onChange={e => setFrom(e.target.value)}><option value="">选择节点</option>{nodes.filter(node => !['SOURCE', 'VALIDATE', 'OUTPUT'].includes(node.type)).map(node => <option key={node.id} value={node.id}>{label(node.id)}</option>)}</select></label>
      <label>连接终点<select aria-label="连接终点" disabled={props.locked} value={to} onChange={e => setTo(e.target.value)}><option value="">选择节点</option>{nodes.filter(node => !['SOURCE', 'MAP', 'OUTPUT'].includes(node.type)).map(node => <option key={node.id} value={node.id}>{label(node.id)}</option>)}</select></label>
      <button type="button" disabled={props.locked || !from || !to} onClick={() => props.connect(from, to)}>添加连接</button></div>
    <ul>{props.definition.edges.map(edge => <li key={edge.from + ':' + edge.to}><span>{label(edge.from)} → {label(edge.to)}</span><button type="button" aria-label={'移除连接 ' + edge.from + ' → ' + edge.to} disabled={props.locked || nodes.find(n => n.id === edge.from)?.type === 'SOURCE' || nodes.find(n => n.id === edge.to)?.type === 'OUTPUT'} onClick={() => props.remove(edge.from, edge.to)}>移除</button></li>)}</ul>
  </div></>
}
