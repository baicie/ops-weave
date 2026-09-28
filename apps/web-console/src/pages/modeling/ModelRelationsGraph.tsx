import { useState } from 'react'
import { RelationGraph } from '../../adapters/graph/RelationGraph.tsx'
import type { CatalogPage } from '../../api/model-catalog.ts'
export function ModelRelationsGraph(props: { catalog: CatalogPage | null }) {
  const [selected, setSelected] = useState('')
  const definitions = [...(props.catalog?.package.definitions ?? []), ...(props.catalog?.published.items.map(e => e.definition) ?? [])]
  const entities = definitions.filter(d => d.kind === 'ENTITY')
  const key = (v: { id: string; revision: number }) => v.id + '@' + v.revision
  const relations = definitions.filter(d => d.kind === 'RELATION' && d.endpoints)
  const shown = relations.filter(r => entities.some(e => key(e) === key(r.endpoints!.from)) && entities.some(e => key(e) === key(r.endpoints!.to)))
  const current = entities.find(e => key(e) === selected)
  return <section className="model-graph-panel">
    <div className="section-label">
      <div><h3>关系类型示意</h3><p>展示已定义的类型连接；实际资产之间的关系请到资产关系图查看。</p></div>
      <a href="#/inventory/topology">查看资产关系 ↗</a>
    </div>
    <RelationGraph
      nodes={entities.map(e => ({ id: key(e), label: e.label + ' · v' + e.revision, detail: e.id }))}
      edges={shown.map(r => ({ id: key(r), source: key(r.endpoints!.from), target: key(r.endpoints!.to), label: r.label }))}
      selected={selected}
      select={setSelected}
      label="已发布模型关系类型示意图"
    />
    {current ? <p className="model-graph-selection">{current.label + ' · ' + current.id + ' · ' + current.fields.length + ' 个字段'}</p> : null}
    {shown.length !== relations.length ? <p className="source-access-hint">部分关系的固定端点版本不在当前目录中，未绘制对应连线。</p> : null}
  </section>
}
