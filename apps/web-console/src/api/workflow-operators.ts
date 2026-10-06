import { builtInOperatorCatalog } from './generated/workflow-operators.ts'
import type { Definition, WorkflowNode } from './workflows.ts'

export type OperatorCatalog = typeof builtInOperatorCatalog
export type OperatorDescriptor = OperatorCatalog['operators'][number]
export type NodeType = OperatorDescriptor['type']
export type Operator = Exclude<NodeType, 'SOURCE' | 'MAP' | 'VALIDATE' | 'OUTPUT'>
export const OPERATORS = builtInOperatorCatalog.operators.filter(o => o.category !== 'STRUCTURE').map(o => o.type) as Operator[]
export function builtInDescriptor(type: NodeType): OperatorDescriptor { return builtInOperatorCatalog.operators.find(o => o.type === type)! }

function canonical(value: unknown): string {
  if (Array.isArray(value)) return '[' + value.map(canonical).join(',') + ']'
  if (value && typeof value === 'object') return '{' + Object.entries(value).sort(([a], [b]) => a.localeCompare(b)).map(([k, v]) => JSON.stringify(k) + ':' + canonical(v)).join(',') + '}'
  return JSON.stringify(value)
}
export function parseOperatorCatalog(value: unknown): OperatorCatalog {
  // This binary registers exactly this catalog. Unknown executable metadata never becomes a node.
  if (canonical(value) !== canonical(builtInOperatorCatalog)) throw new Error('算子目录与当前控制台版本不兼容，请核对服务版本')
  return value as OperatorCatalog
}
export function pinNode(node: WorkflowNode, catalog: OperatorCatalog): WorkflowNode {
  const operator = catalog.operators.find(o => o.type === node.type && o.version === node.version)
  if (!operator) throw new Error('当前目录未登记该算子版本')
  return { ...node, operatorDigest: operator.digest }
}
export function pinOperators(definition: Definition, catalog: OperatorCatalog): Definition {
  return { ...definition, nodes: definition.nodes.map(node => pinNode(node, catalog)) }
}
export function operatorsPinned(definition: Definition, catalog: OperatorCatalog | undefined): boolean {
  return !!catalog && definition.nodes.every(node => catalog.operators.some(o => o.type === node.type && o.version === node.version && o.digest === node.operatorDigest))
}
