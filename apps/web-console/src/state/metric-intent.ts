import { metricsSelection } from './view-selection.ts'
export type MetricIntent = { entityId: string; from: number; till: number }
// A URL carries selection only. The API must authorize the entity again.
export function metricIntent(hash: string): MetricIntent | null {
  const selection = metricsSelection(hash)
  return selection.entityId && selection.from !== null && selection.till !== null ? { entityId: selection.entityId, from: selection.from, till: selection.till } : null
}
