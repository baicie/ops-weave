import { type Operator, type OperatorCatalog } from '../../api/workflow-operators.ts'
import { useEffect, useState } from 'react'
import { Search, X, PanelLeftClose, Database, Activity, FileText, Plus } from 'lucide-react'
import { outputNodeNames, type OutputKind } from '../../api/workflow-output.ts'

const glyphs: Record<Operator,string> = { TRIM:'Aa', EMPTY_TO_NULL:'∅', DEFAULT:'＋', ENUM_MAP:'≍', SCALE:'×', FILTER:'▽', MERGE:'⋈' }

export function WorkflowOperatorPalette(props: { catalog?: OperatorCatalog; disabled: boolean; add: (type: Operator) => void; close?: () => void; output: { kind: OutputKind; disabled: Record<OutputKind, boolean>; choose: (kind: OutputKind) => void } }) {
  const [query, setQuery] = useState('')
  useEffect(() => {
    const narrow = window.matchMedia('(max-width: 1000px)')
    const resize = (event: MediaQueryListEvent) => { if (event.matches) props.close?.() }
    narrow.addEventListener('change', resize)
    return () => narrow.removeEventListener('change', resize)
  }, [props.close])
  const operators = Object.fromEntries((props.catalog?.operators ?? []).map(o => [o.type, o]));
  const matches = (props.catalog?.operators ?? []).filter(o => o.category !== 'STRUCTURE' && (o.label + ' ' + o.hint + ' ' + o.type).toLowerCase().includes(query.trim().toLowerCase())).map(o => o.type as Operator)
  const groups = [{ label: '清洗与转换', category: 'TRANSFORM' }, { label: '流程控制', category: 'ROUTING' }]
  const outputs = (['ENTITY', 'METRIC', 'LOG'] as const).filter(kind => (outputNodeNames[kind] + ' ' + kind).toLowerCase().includes(query.trim().toLowerCase()))
  const outputIcons = { ENTITY: Database, METRIC: Activity, LOG: FileText }
  return <aside className="workflow-operator-palette" aria-label="处理算子"><header><strong>节点库</strong>{props.close ? <button type="button" className="icon-button" aria-label="收起节点库" onClick={props.close}><PanelLeftClose size={16} /></button> : null}</header>
    <div className="workflow-library-search"><Search size={14} /><input aria-label="搜索可添加节点" placeholder="搜索节点" value={query} onChange={event => setQuery(event.target.value)} />{query ? <button type="button" className="icon-button" aria-label="清除节点搜索" onClick={() => setQuery('')}><X size={12} /></button> : null}</div>
    {groups.filter(group => matches.some(type => operators[type]?.category === group.category)).map(group => <section key={group.label}><h4>{group.label}</h4>{matches.filter(type => operators[type]?.category === group.category).map(type => <button key={type} type="button" className="workflow-library-item" disabled={props.disabled} draggable={!props.disabled}
      onDragStart={event => { event.dataTransfer.setData('application/x-opsweave-operator', type); event.dataTransfer.effectAllowed = 'copy' }} onClick={() => props.add(type)}>
      <b aria-hidden="true">{type === 'DEFAULT' ? <Plus size={16} /> : glyphs[type]}</b><span>{operators[type]!.label}<small>{operators[type]!.hint}</small></span>
    </button>)}</section>)}
    {outputs.length ? <section><h4>数据输出</h4>{outputs.map(kind => { const Icon = outputIcons[kind]; return <button key={kind} type="button" className="workflow-output-node" aria-pressed={props.output.kind === kind} disabled={props.output.disabled[kind]} title={props.output.kind === kind ? '配置输出节点' : '更换输出会重建字段映射和处理链，可撤销'} onClick={() => props.output.choose(kind)}><Icon size={16} /><span>{outputNodeNames[kind]}</span></button> })}</section> : null}
    {!matches.length && !outputs.length ? <p className="workflow-library-empty">没有匹配的节点。</p> : null}
  </aside>
}
