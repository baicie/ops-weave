// An explicit empty selection clears a cached detail; bare navigation preserves its tab address.
export function metricDefinitionHash(key?: string) { return '#/modeling/metrics' + (key === undefined ? '' : '?metricKey=' + encodeURIComponent(key)) }
export function metricDefinitionSelection(hash: string): string {
  const [path, query = ''] = hash.split('?')
  const params = new URLSearchParams(query)
  const key = params.get('metricKey') ?? ''
  if (path !== '#/modeling/metrics' || [...params.keys()].some(name => name !== 'metricKey') || params.getAll('metricKey').length > 1 || key && !/^[a-zA-Z][a-zA-Z0-9_.:/-]{0,127}$/.test(key)) throw new Error('指标定义链接无效')
  return key
}
