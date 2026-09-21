import { ModulePlaceholder } from '../../components/ModulePlaceholder.tsx'

export function MetricsPage() {
  return (
    <ModulePlaceholder
      title="指标"
      capability="MetricDefinition 与查询 API 尚未提供。图表适配层只预留目录。"
      next="接入受控 Metric API 后再选择图表实现；不把原值与预测画到同一序列。"
    />
  )
}
