import type { MetricSeriesRow } from '../../api/metrics.ts'

const COLORS = ['#1d4f91', '#0f7b6c', '#9a3412', '#6d28d9']

const SVG = 'http://www.w3.org/2000/svg'

/** Replaceable drawing adapter; preserves SVG namespace across component boundaries. */
export function LineChart(props: { series: MetricSeriesRow[]; view?: 'raw' | 'rate'; width?: number; height?: number }) {
  const derived = props.view === 'rate'
  const width = props.width ?? 640
  const height = props.height ?? 180
  const pad = 16
  const svg = document.createElementNS(SVG, 'svg')
  svg.setAttribute('class', 'metric-chart')
  svg.setAttribute('viewBox', `0 0 ${width} ${height}`)
  svg.setAttribute('role', 'img')
  svg.setAttribute('aria-label', derived ? '指标变化率曲线' : '指标曲线')
  const samples = props.series.flatMap(series => derived
    ? series.counterRates.map(rate => ({ at: rate.at, value: Number(rate.rate) }))
    : series.points.map(point => ({ at: point.at, value: Number(point.value) })))
  if (samples.length === 0) return svg
  const minAt = Math.min(...samples.map(point => point.at))
  const maxAt = Math.max(...samples.map(point => point.at))
  const minValue = Math.min(...samples.map(point => point.value))
  const maxValue = Math.max(...samples.map(point => point.value))
  const spanAt = Math.max(1, maxAt - minAt)
  const spanValue = Math.max(1e-9, maxValue - minValue)
  const x = (at: number) => pad + ((at - minAt) / spanAt) * (width - pad * 2)
  const y = (value: number) => height - pad - ((value - minValue) / spanValue) * (height - pad * 2)
  props.series.forEach((series, index) => {
    const color = COLORS[index % COLORS.length] ?? COLORS[0]
    const points = derived
      ? series.counterRates.map(rate => ({ at: rate.at, value: Number(rate.rate) }))
      : series.points.map(point => ({ at: point.at, value: Number(point.value) }))
    if (points.length === 0) return
    const line = document.createElementNS(SVG, 'polyline')
    line.setAttribute('fill', 'none')
    line.setAttribute('stroke', color)
    line.setAttribute('stroke-width', '2')
    line.setAttribute('points', points.map(point => `${x(point.at)},${y(point.value)}`).join(' '))
    svg.append(line)
    if (points.length === 1) {
      const point = points[0]
      const dot = document.createElementNS(SVG, 'circle')
      dot.setAttribute('cx', String(x(point.at)))
      dot.setAttribute('cy', String(y(point.value)))
      dot.setAttribute('r', '3')
      dot.setAttribute('fill', color)
      svg.append(dot)
    }
    if (derived) {
      // A reset is drawn, never smoothed away: the interval is marked where the counter restarted.
      for (const rate of series.counterRates.filter(item => item.counterReset)) {
        const marker = document.createElementNS(SVG, 'line')
        marker.setAttribute('class', 'counter-reset')
        marker.setAttribute('data-counter-reset', 'true')
        marker.setAttribute('x1', String(x(rate.at)))
        marker.setAttribute('x2', String(x(rate.at)))
        marker.setAttribute('y1', String(pad))
        marker.setAttribute('y2', String(height - pad))
        marker.setAttribute('stroke', '#b45309')
        marker.setAttribute('stroke-width', '1')
        marker.setAttribute('stroke-dasharray', '4 4')
        svg.append(marker)
      }
    }
  })
  return svg
}
