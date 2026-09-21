import { For } from '@zeus-js/zeus'
import { forItem } from '../adapters/zeus-ui/for-item.ts'
import { pathFor, ROUTES, type Route, type RouteName } from '../state/routes.ts'

export function AppNav(props: { route: () => RouteName }) {
  return (
    <nav class="app-nav" aria-label="产品模块">
      <For each={ROUTES}>
        {row => <NavLink item={forItem(row)} route={props.route} />}
      </For>
    </nav>
  )
}

function NavLink(props: { item: Route; route: () => RouteName }) {
  return (
    <a
      href={pathFor(props.item.name)}
      class={() => (props.route() === props.item.name ? 'is-active' : undefined)}
    >
      {props.item.label}
    </a>
  )
}
