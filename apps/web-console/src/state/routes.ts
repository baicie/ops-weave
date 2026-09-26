export type RouteName = 'diagnose' | 'current-diagnose' | 'reorganize' | 'incidents' | 'inventory' | 'metrics' | 'pipelines' | 'source-scan-runs' | 'skills' | 'agent' | 'retention' | 'source-snapshots' | 'source-corrections'

export type Route = {
  name: RouteName
  path: string
  label: string
}

export const ROUTES: Route[] = [
  { name: 'source-snapshots', path: '/integrations/cmdb', label: 'CMDB 快照' },
  { name: 'source-corrections', path: '/integrations/cmdb/corrections', label: '来源绑定更正' },
  { name: 'incidents', path: '/incidents', label: 'Incident 列表' },
  { name: 'reorganize', path: '/incidents/reorganize', label: 'Incident 归属' },
  { name: 'current-diagnose', path: '/incidents/current-diagnose', label: '平台诊断' },
  { name: 'diagnose', path: '/incidents/diagnose', label: 'Fixture 诊断演示' },
  { name: 'inventory', path: '/inventory', label: '资产' },
  { name: 'metrics', path: '/metrics', label: '指标' },
  { name: 'pipelines', path: '/integrations/pipelines', label: '接入流水线' },
  { name: 'source-scan-runs', path: '/integrations/zabbix/runs', label: '来源扫描' },
  { name: 'skills', path: '/skills', label: 'Skill' },
  { name: 'agent', path: '/agent', label: 'Agent 控制台' },
  { name: 'retention', path: '/ai/retention', label: 'AI 数据留存' },
]

export function pathFor(name: RouteName): string {
  const route = ROUTES.find(item => item.name === name)
  return route ? `#${route.path}` : '#/incidents/diagnose'
}

export function routeFromHash(hash: string): RouteName {
  const path = hash.replace(/^#/, '').split('?')[0] || '/incidents/diagnose'
  const exact = ROUTES.find(item => item.path === path)
  if (exact) return exact.name
  if (path === '/' || path === '/incidents') return 'diagnose'
  return 'diagnose'
}
