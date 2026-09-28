import type { MetricSeriesRow } from '../../api/metrics.ts'

const COLORS = ['var(--ow-chart-1, #1d4f91)', 'var(--ow-chart-2, #0f7b6c)', 'var(--ow-chart-3, #9a3412)', 'var(--ow-chart-4, #6d28d9)']

/** Replaceable drawing adapter; preserves SVG namespace across component boundaries. */
export function LineChart(props: { series: MetricSeriesRow[]; view?: 'raw' | 'rate'; width?: number; height?: number }) {
  const derived = props.view === 'rate'
  const width = props.width ?? 640
  const height = props.height ?? 180
  const pad = 16
  const ariaLabel = derived ? '指标变化率曲线' : '指标曲线'
  const samples = props.series.flatMap(series => derived
    ? series.counterRates.map(rate => ({ at: rate.at, value: Number(rate.rate) }))
    : series.points.map(point => ({ at: point.at, value: Number(point.value) })))
  if (samples.length === 0) {
    return <svg className="metric-chart" viewBox={`0 0 ${width} ${height}`} role="img" aria-label={ariaLabel} />
  }
  const minAt = Math.min(...samples.map(point => point.at))
  const maxAt = Math.max(...samples.map(point => point.at))
  const minValue = Math.min(...samples.map(point => point.value))
  const maxValue = Math.max(...samples.map(point => point.value))
  const spanAt = Math.max(1, maxAt - minAt)
  const spanValue = Math.max(1e-9, maxValue - minValue)
  const x = (at: number) => pad + ((at - minAt) / spanAt) * (width - pad * 2)
  const y = (value: number) => height - pad - ((value - minValue) / spanValue) * (height - pad * 2)
  return (
    <svg className="metric-chart" viewBox={`0 0 ${width} ${height}`} role="img" aria-label={ariaLabel}>
      {props.series.map((series, index) => {
        const color = COLORS[index % COLORS.length] ?? COLORS[0]
        const points = derived
          ? series.counterRates.map(rate => ({ at: rate.at, value: Number(rate.rate) }))
          : series.points.map(point => ({ at: point.at, value: Number(point.value) }))
        if (points.length === 0) return null
        const key = `${series.sourceInstanceId}:${series.externalItemId}:${series.mappingRevision}:${index}`
        return (
          <g key={key}>
            <polyline
              fill="none"
              stroke={color}
              strokeWidth="2"
              points={points.map(point => `${x(point.at)},${y(point.value)}`).join(' ')}
            />
            {points.length === 1 ? (
              <circle cx={x(points[0].at)} cy={y(points[0].value)} r="3" fill={color} />
            ) : null}
            {derived
              ? series.counterRates.filter(item => item.counterReset).map(rate => (
                // A reset is drawn, never smoothed away: the interval is marked where the counter restarted.
                <line
                  key={rate.at}
                  className="counter-reset"
                  data-counter-reset="true"
                  x1={x(rate.at)}
                  x2={x(rate.at)}
                  y1={pad}
                  y2={height - pad}
                  stroke="#b45309"
                  strokeWidth="1"
                  strokeDasharray="4 4"
                />
              ))
              : null}
          </g>
        )
      })}
    </svg>
  )
}
