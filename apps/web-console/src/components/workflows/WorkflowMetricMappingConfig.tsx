import { supportsStandardMetricMapping, type OutputTarget } from '../../api/workflow-output.ts'
import { sameMappingPin, type MappingDefinition } from '../../api/metric-mappings.ts'
import { metricDefinitionHash } from '../../state/metric-definition-selection.ts'

/** Definition selection and display; the parent owns loading, edits and undo. */
export function WorkflowMetricMappingConfig(p:{target:OutputTarget;mappings:MappingDefinition[];disabled:boolean;choose:(value:string)=>void}) {
 const pin='mappingPin' in p.target?p.target.mappingPin:null
 const available=p.mappings.filter(supportsStandardMetricMapping)
 const selected=pin?available.find(d=>sameMappingPin(d.mappingPin,pin)&&d.metricKey===('metricKey' in p.target?p.target.metricKey:undefined)):undefined
 const value=pin?selected?'metric-mapping:'+selected.mappingPin.id:'metric-unavailable':'metric-generic'
 return <section className="workflow-metric-mapping" aria-label="标准指标配置">
  <label>指标定义<select aria-label="指标定义" disabled={p.disabled} value={value} onChange={e=>p.choose(e.target.value)}>
   <option value="metric-generic">通用 GAUGE 格式</option>
   {pin&&!selected?<option value="metric-unavailable" disabled>{'metricKey' in p.target?p.target.metricKey:''} · 固定版本不可用</option>:null}
   {available.map(d=><option key={d.mappingPin.id} value={'metric-mapping:'+d.mappingPin.id}>{d.metricKey} · v{d.mappingPin.revision}</option>)}
  </select></label>
  {pin?<><dl className="studio-output-format"><dt>完整指标标识</dt><dd><a href={metricDefinitionHash('metricKey' in p.target?p.target.metricKey:undefined)}><code>{'metricKey' in p.target?p.target.metricKey:''}</code></a></dd>
   <dt>固定映射</dt><dd><code>{pin.id}</code> · v{pin.revision}<details><summary>完整摘要</summary><code>{pin.digest}</code></details></dd>
   {selected?<><dt>完整来源键</dt><dd><code>{selected.sourceKey}</code></dd><dt>单位 / 类型</dt><dd>{selected.unit} · {selected.metricType} · {selected.valueType}</dd><dt>数值转换</dt><dd><code>{selected.valueTransform}</code></dd><dt>有效范围</dt><dd>{selected.minimum??'无下限'} ～ {selected.maximum??'无上限'}</dd><dt>固定维度</dt><dd>{Object.entries(selected.fixedDimensions).map(([key,value])=><code key={key}>{key}={value} </code>)}{!selected.dimensionSchema.length?'无维度':null}</dd></>:null}
  </dl>{!selected?<p role="alert">当前授权目录中没有这份固定映射，请核对版本后重新预览。</p>:null}</>:null}
 </section>
}
