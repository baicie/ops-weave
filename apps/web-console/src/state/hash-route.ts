import { useEffect, useState } from 'react'
import { routeFromHash, type RouteName } from './routes.ts'

export function useHashRoute(): RouteName {
  const [route, setRoute] = useState<RouteName>(() => routeFromHash(window.location.hash))
  useEffect(() => {
    if (!window.location.hash) window.location.hash = '/start'
    const onChange = () => setRoute(routeFromHash(window.location.hash))
    window.addEventListener('hashchange', onChange)
    return () => window.removeEventListener('hashchange', onChange)
  }, [])
  return route
}
