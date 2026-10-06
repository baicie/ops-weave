import type { CatalogMetric } from '../../api/model-catalog.ts'
import { metricDefinitionHash } from '../../state/metric-definition-selection.ts'

export function MetricDefinitionList(props: { metrics: CatalogMetric[] }) {
  return <div className="metric-definition-table-scroll" tabIndex={0} role="region" aria-label="指标列表滚动区域"><table className="metric-definition-table" aria-label="内置指标列表">
    <thead><tr><th scope="col">指标名称 / 完整标识</th><th scope="col">实体类型</th><th scope="col">单位</th><th scope="col">类型</th><th scope="col">来源键</th></tr></thead>
    <tbody>{props.metrics.map(metric => <tr key={metric.key}>
      <td><a href={metricDefinitionHash(metric.key)} aria-label={metric.label}>{metric.label}</a><a className="metric-definition-key" href={metricDefinitionHash(metric.key)}><code>{metric.key}</code></a></td>
      <td><code>{metric.entityType}</code></td><td>{metric.unit}</td><td>{metric.kind}</td><td><code>{metric.sourceKey}</code></td>
    </tr>)}</tbody>
  </table>{!props.metrics.length ? <p className="metric-definition-empty" role="status">没有匹配的指标定义。</p> : null}</div>
}
