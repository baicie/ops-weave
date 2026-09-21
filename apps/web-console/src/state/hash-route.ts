import { createSignal, onCleanup } from '@zeus-js/zeus'
import { routeFromHash, type RouteName } from './routes.ts'

export function createHashRoute() {
  const [route, setRoute] = createSignal<RouteName>(routeFromHash(window.location.hash))
  const onChange = () => setRoute(routeFromHash(window.location.hash))
  window.addEventListener('hashchange', onChange)
  onCleanup(() => window.removeEventListener('hashchange', onChange))
  if (!window.location.hash) window.location.hash = '/incidents/diagnose'
  return { route }
}
