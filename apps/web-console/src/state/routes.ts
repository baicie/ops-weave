export type RouteName = 'diagnose' | 'inventory' | 'metrics' | 'skills' | 'agent'

export type Route = {
  name: RouteName
  path: string
  label: string
}

export const ROUTES: Route[] = [
  { name: 'diagnose', path: '/incidents/diagnose', label: 'Incident 诊断' },
  { name: 'inventory', path: '/inventory', label: '资产' },
  { name: 'metrics', path: '/metrics', label: '指标' },
  { name: 'skills', path: '/skills', label: 'Skill' },
  { name: 'agent', path: '/agent', label: 'Agent 控制台' },
]

export function pathFor(name: RouteName): string {
  const route = ROUTES.find(item => item.name === name)
  return route ? `#${route.path}` : '#/incidents/diagnose'
}

export function routeFromHash(hash: string): RouteName {
  const path = hash.replace(/^#/, '') || '/incidents/diagnose'
  const exact = ROUTES.find(item => item.path === path)
  if (exact) return exact.name
  if (path === '/' || path === '/incidents') return 'diagnose'
  return 'diagnose'
}
