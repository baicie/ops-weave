import type { MappingBinding, MappingDefinition } from '../../api/metric-mappings.ts'

/** A selected executable definition, shown with its full semantics before an explicit maintenance command. */
export function MetricMappingDetail(props: { binding: MappingBinding; definition: MappingDefinition | null }) {
  const b = props.binding, d = props.definition
  return <section className="metric-mapping-definition" aria-label="映射规则详情"><dl>
    <dt>完整指标标识</dt><dd><code>{b.metricKey}</code></dd><dt>来源实例 / 监控项</dt><dd><code>{b.sourceInstanceId} / {b.externalItemId}</code></dd>
    <dt>当前绑定版本</dt><dd>v{b.version}</dd><dt>当前映射</dt><dd>{b.mappingPin ? <><code>{b.mappingPin.id}</code> · v{b.mappingPin.revision}<code className="metric-mapping-digest">{b.mappingPin.digest}</code></> : '未固定'}</dd>
    {d ? <><dt>完整来源键</dt><dd><code>{d.sourceKey}</code></dd><dt>目标单位 / 类型</dt><dd>{d.unit} · {d.metricType} · {d.valueType}</dd><dt>转换规则</dt><dd><code>{d.valueTransform}</code></dd><dt>有效数值范围</dt><dd>{d.minimum ?? '无下限'} ～ {d.maximum ?? '无上限'}</dd><dt>维度定义</dt><dd>{d.dimensionSchema.join(' · ') || '无维度'}</dd><dt>固定维度</dt><dd>{Object.entries(d.fixedDimensions).map(([k, v]) => <code key={k}>{k}={v} </code>)}</dd><dt>选定映射</dt><dd><code>{d.mappingPin.id}</code> · v{d.mappingPin.revision}<code className="metric-mapping-digest">{d.mappingPin.digest}</code></dd></> : null}
  </dl>{!d ? <p role="status">当前登记的映射与这份绑定不兼容。</p> : null}</section>
}
