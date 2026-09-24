import { For } from '@zeus-js/zeus'
import { forItem } from '../zeus-ui/for-item.ts'
import type { MetricSeriesRow } from '../../api/metrics.ts'

const COLORS = ['#1d4f91', '#0f7b6c', '#9a3412', '#6d28d9']

type DrawnLine = { color: string; points: string }

/** Replaceable drawing adapter. It plots supplied points and does not query or aggregate. */
export function LineChart(props: { series: MetricSeriesRow[]; width?: number; height?: number }) {
  const width = props.width ?? 640
  const height = props.height ?? 180
  const pad = 16
  const samples = props.series.flatMap(series => series.points.map(point => ({ at: point.at, value: Number(point.value) })))
    .filter(point => Number.isFinite(point.value))
  if (samples.length === 0) return <svg class="metric-chart" viewBox={`0 0 ${width} ${height}`} />
  const minAt = Math.min(...samples.map(point => point.at))
  const maxAt = Math.max(...samples.map(point => point.at))
  const minValue = Math.min(...samples.map(point => point.value))
  const maxValue = Math.max(...samples.map(point => point.value))
  const spanAt = Math.max(1, maxAt - minAt)
  const spanValue = Math.max(1e-9, maxValue - minValue)
  const x = (at: number) => pad + ((at - minAt) / spanAt) * (width - pad * 2)
  const y = (value: number) => height - pad - ((value - minValue) / spanValue) * (height - pad * 2)
  const lines: DrawnLine[] = props.series.map((series, index) => ({
    color: COLORS[index % COLORS.length] ?? COLORS[0],
    points: series.points
      .filter(point => Number.isFinite(Number(point.value)))
      .map(point => `${x(point.at)},${y(Number(point.value))}`)
      .join(' '),
  }))
  return (
    <svg class="metric-chart" viewBox={`0 0 ${width} ${height}`} role="img" aria-label="指标曲线">
      <For each={lines}>
        {row => {
          const line = forItem(row)
          return <polyline fill="none" stroke={line.color} stroke-width="2" points={line.points} />
        }}
      </For>
    </svg>
  )
}
