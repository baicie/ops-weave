import { groupFor, ROUTES, type RouteName } from '../../state/routes.ts'

export function PageBreadcrumb(props: { route: RouteName }) {
  return <nav className="breadcrumb" aria-label="当前位置"><ol>
    <li>{groupFor(props.route).label}</li><li className="breadcrumb-separator" aria-hidden="true">/</li>
    <li><strong aria-current="page">{ROUTES.find(item => item.name === props.route)?.label ?? '工作台'}</strong></li>
  </ol></nav>
}
