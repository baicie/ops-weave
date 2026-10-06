import { Button } from '../ui/button.tsx'
import type { MetricSourcePage } from '../../api/workflow-metric-sources.ts'
import type { MappingDefinition } from '../../api/metric-mappings.ts'
import { metricDefinitionHash } from '../../state/metric-definition-selection.ts'

export function WorkflowMetricSourceList(p:{page:MetricSourcePage|null;mapping:MappingDefinition;selectedId:string;busy:boolean;error:string;choose:(id:string)=>void;bind:()=>void;retry:()=>void}){
 const items=p.page?.items.filter(i=>i.item.mapping?.id===p.mapping.mappingPin.id&&i.item.mapping.revision===p.mapping.mappingPin.revision&&i.item.mapping.digest===p.mapping.mappingPin.digest&&i.item.mapping.metricKey===p.mapping.metricKey&&i.item.sourceKey===p.mapping.sourceKey)??[]
 const selected=items.find(i=>i.source.metric?.itemId===p.selectedId),expired=!!selected&&Date.now()>=Date.parse(selected.expiresAt)
 return <section className="workflow-source-picker" aria-label="指标来源选择">
  {p.busy?<p role="status">正在读取已发现的指标…</p>:null}
  {p.error?<p role="alert">{p.error}</p>:null}
  {p.page?<><label>来源指标<select aria-label="工作流来源指标" value={p.selectedId} disabled={p.busy} onChange={e=>p.choose(e.target.value)}><option value="">请选择来源指标</option>{items.map(i=><option key={i.item.itemId} value={i.item.itemId} disabled={Date.now()>=Date.parse(i.expiresAt)}>{i.item.name+' · 主机 '+i.item.hostId+' · '+i.item.itemId}</option>)}</select></label>
   {!items.length?<p>没有与当前映射匹配的有效发现记录。<a href="#/integrations/sources">前往来源中心发现指标</a></p>:null}
   {p.page.truncated?<p>清单已截断，可到来源中心查看其他发现记录。</p>:null}
   {items.length>0&&items.every(i=>Date.now()>=Date.parse(i.expiresAt))?<p role="alert">发现记录已过期，请到来源中心重新发现指标。</p>:null}
   {selected?<dl><dt>完整来源键</dt><dd><code>{selected.item.sourceKey}</code></dd><dt>标准指标</dt><dd><a href={metricDefinitionHash(p.mapping.metricKey)}>{p.mapping.metricKey}</a></dd><dt>类型与单位</dt><dd>{selected.item.sourceValueType} · {selected.item.sourceUnit||'未声明单位'}</dd><dt>发现时间</dt><dd>{new Date(selected.asOf).toLocaleString()}</dd></dl>:null}
   {expired?<p role="alert">发现记录已过期，请重新发现后选择。</p>:null}
   <Button disabled={p.busy||!!p.error||!selected||expired} onClick={p.bind}>使用此指标来源</Button>
  </>:null}
  {p.error||p.page?<Button variant="outline" disabled={p.busy} onClick={p.retry}>重新读取指标清单</Button>:null}
 </section>
}
