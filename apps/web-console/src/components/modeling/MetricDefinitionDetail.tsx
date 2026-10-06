import type { CatalogMetric } from '../../api/model-catalog.ts'

export function MetricDefinitionDetail(props: { metric: CatalogMetric; version: string }) {
  const metric = props.metric
  return <section className="metric-definition-detail" aria-label={'指标定义 ' + metric.key}><header><h3>{metric.label}</h3><code>{metric.key}</code><p>opsweave-core · v{props.version} · 固定映射定义</p></header>
    <dl><dt>完整指标标识</dt><dd><code>{metric.key}</code></dd><dt>显示名称</dt><dd>{metric.label}</dd><dt>实体类型</dt><dd><code>{metric.entityType}</code></dd><dt>单位</dt><dd>{metric.unit}</dd><dt>指标类型</dt><dd>{metric.kind}</dd><dt>完整来源键</dt><dd><code>{metric.sourceKey}</code></dd><dt>转换规则</dt><dd>{metric.transform === 'percent-to-ratio' ? '原始百分数 × 0.01，转换为比例' : metric.transform === 'identity' ? '保留原始数值' : '使用目录固定规则；当前界面未提供此规则解释。'}<code>{metric.transform}</code></dd><dt>映射及版本</dt><dd><code>{metric.mapping}</code></dd></dl>
    <p>这是指标语义与来源映射定义。实际绑定和采样需在指标页面显式读取，定义本身不会启用采集。</p>
  </section>
}
