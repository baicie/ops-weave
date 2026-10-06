import type { Definition } from '../api/workflows.ts'

export function orderGraph(d: Definition): Definition {
  const pending = [...d.nodes], ordered: Definition['nodes'] = []
  while (pending.length) {
    const next = pending.findIndex(node => d.edges.filter(edge => edge.to === node.id).every(edge => ordered.some(parent => parent.id === edge.from)))
    if (next < 0) return d
    ordered.push(pending.splice(next, 1)[0]!)
  }
  const middle = ordered.filter(node => !['SOURCE', 'MAP', 'VALIDATE', 'OUTPUT'].includes(node.type))
  const nodes = [...d.nodes.filter(node => node.type === 'SOURCE'), ...d.nodes.filter(node => node.type === 'MAP'), ...middle, ...d.nodes.filter(node => node.type === 'VALIDATE'), ...d.nodes.filter(node => node.type === 'OUTPUT')]
  return { ...d, nodes, edges: [...d.edges].sort((a, b) => nodes.findIndex(n => n.id === a.from) - nodes.findIndex(n => n.id === b.from) || nodes.findIndex(n => n.id === a.to) - nodes.findIndex(n => n.id === b.to)) }
}

export function graphProblems(d: Definition): string[] {
  const problems = new Set<string>(), ids = new Set(d.nodes.map(node => node.id))
  if (d.edges.length > 32) problems.add('连接最多32条')
  if (new Set(d.edges.map(edge => edge.from + ':' + edge.to)).size !== d.edges.length) problems.add('不能重复连接相同节点')
  if (d.edges.some(edge => !ids.has(edge.from) || !ids.has(edge.to) || edge.from === edge.to)) problems.add('连接端点无效')
  const nodes = d.nodes
  if (nodes[0]?.type !== 'SOURCE' || nodes[1]?.type !== 'MAP' || nodes.at(-2)?.type !== 'VALIDATE' || nodes.at(-1)?.type !== 'OUTPUT') problems.add('输入、映射、校验和输出必须保留')
  for (const [index, node] of nodes.entries()) {
    const incoming = d.edges.filter(edge => edge.to === node.id), outgoing = d.edges.filter(edge => edge.from === node.id)
    if (index > 0 && !incoming.length || index < nodes.length - 1 && !outgoing.length) problems.add('存在未连接的节点，请完成输入和输出连接')
    if (node.type !== 'MERGE' && incoming.length > 1) problems.add('多个分支必须先连接到合流节点')
    if (node.type === 'SOURCE' && (incoming.length || outgoing.length !== 1 || outgoing[0]?.to !== nodes[1]?.id)) problems.add('输入必须直接连接字段映射')
    if (node.type === 'OUTPUT' && (outgoing.length || incoming.length !== 1 || incoming[0]?.from !== nodes.at(-2)?.id)) problems.add('输出必须直接连接校验')
    if (node.type === 'VALIDATE' && (outgoing.length !== 1 || outgoing[0]?.to !== nodes.at(-1)?.id)) problems.add('校验必须直接连接输出')
    if (d.edges.some(edge => edge.to === node.id && nodes.findIndex(parent => parent.id === edge.from) >= index)) problems.add('连接存在环或执行顺序无效')
  }
  return [...problems]
}

export function connectionProblem(d: Definition, from: string, to: string): string | null {
  const source = d.nodes.find(node => node.id === from), target = d.nodes.find(node => node.id === to)
  if (!source || !target || from === to) return '请选择两个不同的节点'
  if (d.edges.some(edge => edge.from === from && edge.to === to)) return '这两个节点已经连接'
  if (d.edges.length >= 32) return '连接最多32条'
  if (source.type === 'SOURCE' || source.type === 'VALIDATE' || source.type === 'OUTPUT' || target.type === 'SOURCE' || target.type === 'MAP' || target.type === 'OUTPUT') return '输入/映射与校验/输出的固定连接不能修改'
  if (target.type !== 'MERGE' && d.edges.some(edge => edge.to === to)) return '此节点已有输入；先移除原连接，或使用合流节点'
  const reachable = new Set([to])
  for (let i = 0; i < d.nodes.length; i++) for (const edge of d.edges) if (reachable.has(edge.from)) reachable.add(edge.to)
  return reachable.has(from) ? '不能创建循环连接' : null
}
