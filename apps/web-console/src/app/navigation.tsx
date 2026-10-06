import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import { NAV_GROUPS, pathFor, ROUTES, type Route, type RouteName } from '../state/routes.ts'

const icons: Record<RouteName, string> = {
  start: 'M3 11 12 3l9 8 M5 9v12h5v-7h4v7h5V9',
  topology: 'M4 4h5v5H4z M15 4h5v5h-5z M10 16h5v5h-5z M9 6h6 M6 9v4h7v3 M17 9v4h-4',
  'source-center': 'M4 6c0-4 16-4 16 0s-16 4-16 0 M4 6v12c0 4 16 4 16 0V6 M4 12c0 4 16 4 16 0',
  'workflow-runs': 'M5 3h14v18H5z M8 7h8 M8 12h8 M8 17h4',
  workflows: 'M9 2h6v6H9z M2 16h6v6H2z M16 16h6v6h-6z M12 8v4 M5 16v-4h14v4',
  'model-entities': 'M12 3 3 8v9l9 5 9-5V8l-9-5z M3 8l9 5 9-5 M12 13v9',
  'model-metrics': 'M3 3v18h18 M7 16V9 M12 16V5 M17 16v-4',
  'model-relations': 'M3 3h6v6H3z M15 15h6v6h-6z M9 6h9v9 M15 12l3 3 3-3',
  'model-relation-instances': 'M4 4h5v5H4z M15 15h5v5h-5z M9 6h8v8 M6 9v6h9',
  inventory: 'M3 4h7v7H3z M14 4h7v7h-7z M3 15h7v5H3z M14 15h7v5h-7z',
  metrics: 'M3 3v18h18 M6 15l4-5 4 3 6-8',
  incidents: 'M12 3 2 21h20L12 3z M12 9v5 M12 17v1',
  'current-diagnose': 'M9 3v3 M15 3v3 M9 18v3 M15 18v3 M3 9h3 M3 15h3 M18 9h3 M18 15h3 M6 6h12v12H6z M10 10h4v4h-4z',
  pipelines: 'M3 5h5v5H3z M16 14h5v5h-5z M8 7h5v10h3',
  'source-scan-runs': 'M8 3H3v5 M16 3h5v5 M3 16v5h5 M21 16v5h-5 M7 12h10',
  'source-snapshots': 'M4 4h16v16H4z M4 9h16 M9 9v11',
  'source-corrections': 'M8 7H3l4-4 M3 7h12a5 5 0 0 1 0 10 M16 17h5l-4 4 M21 17H9a5 5 0 0 1 0-10',
  reorganize: 'M9 3h6v5H9z M2 16h6v5H2z M16 16h6v5h-6z M12 8v4 M5 16v-4h14v4',
  retention: 'M4 5h16v15H4z M2 5h20 M9 2h6 M9 10h6 M9 14h6',
  diagnose: 'M9 3h6 M10 3v7L4 20h16l-6-10V3 M8 15h8',
}

export function RouteIcon(props: { name: RouteName }) {
  return <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d={icons[props.name]} /></svg>
}

type NavProps = { route: RouteName; onNavigate?: () => void; compact?: boolean; surface: 'desktop' | 'mobile' }

export function AppNav(props: NavProps) {
  const navRef = useRef<HTMLElement>(null)
  const [flyout, setFlyout] = useState<string | null>(null)
  useEffect(() => { setFlyout(null) }, [props.route, props.compact])
  useEffect(() => {
    if (!flyout) return
    const nav = navRef.current
    function closeOutside(event: PointerEvent) {
      if (!nav?.contains(event.target as Node)) setFlyout(null)
    }
    function keyboard(event: globalThis.KeyboardEvent) {
      if (event.key !== 'Escape') return
      event.preventDefault()
      nav?.querySelector<HTMLButtonElement>(`[data-nav-group="${flyout}"] .nav-group-toggle`)?.focus()
      setFlyout(null)
    }
    function close() { setFlyout(null) }
    document.addEventListener('pointerdown', closeOutside)
    document.addEventListener('keydown', keyboard)
    window.addEventListener('resize', close)
    nav?.addEventListener('scroll', close)
    return () => {
      document.removeEventListener('pointerdown', closeOutside)
      document.removeEventListener('keydown', keyboard)
      window.removeEventListener('resize', close)
      nav?.removeEventListener('scroll', close)
    }
  }, [flyout])
  return <nav className="app-nav" aria-label="产品模块" ref={navRef}>
    {NAV_GROUPS.filter(group => ROUTES.some(item => item.group === group.id && item.navigation !== false)).map(group => (
      <NavGroup key={group.id} group={group} route={props.route} onNavigate={props.onNavigate} compact={props.compact} surface={props.surface}
        flyoutOpen={flyout === group.id} openFlyout={() => setFlyout(group.id)} closeFlyout={() => setFlyout(current => current === group.id ? null : current)} />
    ))}
  </nav>
}

function NavGroup(props: NavProps & { group: typeof NAV_GROUPS[number]; flyoutOpen: boolean; openFlyout: () => void; closeFlyout: () => void }) {
  const routes = ROUTES.filter(item => item.group === props.group.id && item.navigation !== false)
  const containsCurrent = routes.some(item => item.name === props.route)
  const [expanded, setExpanded] = useState(Boolean(props.group.expanded) || containsCurrent)
  const isOpen = props.compact ? props.flyoutOpen : expanded
  const panelId = props.surface + '-nav-' + props.group.id
  const buttonRef = useRef<HTMLButtonElement>(null)
  const panelRef = useRef<HTMLDivElement>(null)
  const focusFirst = useRef(false)
  const closeTimer = useRef<ReturnType<typeof setTimeout> | null>(null)
  const [position, setPosition] = useState({ top: 8, left: 70 })
  const previousRoute = useRef(props.route)
  useEffect(() => {
    const current = props.route
    if (current !== previousRoute.current && containsCurrent) setExpanded(true)
    previousRoute.current = current
  }, [props.route, containsCurrent])
  function cancelClose() {
    if (closeTimer.current) clearTimeout(closeTimer.current)
    closeTimer.current = null
  }
  function openFlyout() {
    cancelClose()
    const button = buttonRef.current
    if (!button) return
    const bounds = button.getBoundingClientRect()
    const sidebar = button.closest('aside')?.getBoundingClientRect()
    const height = panelRef.current?.getBoundingClientRect().height || Math.min(44 + routes.length * 32, innerHeight - 16)
    setPosition({ top: Math.max(8, Math.min(bounds.top, innerHeight - height - 8)), left: (sidebar?.right ?? bounds.right) + 6 })
    props.openFlyout()
  }
  useLayoutEffect(() => {
    if (!props.compact || !props.flyoutOpen || !buttonRef.current || !panelRef.current) return
    const bounds = buttonRef.current.getBoundingClientRect()
    const height = panelRef.current.getBoundingClientRect().height
    setPosition(current => ({ ...current, top: Math.max(8, Math.min(bounds.top, innerHeight - height - 8)) }))
    if (focusFirst.current) {
      focusFirst.current = false
      panelRef.current.querySelector<HTMLAnchorElement>('a')?.focus()
    }
  }, [props.compact, props.flyoutOpen])
  useEffect(() => () => { if (closeTimer.current) clearTimeout(closeTimer.current) }, [])
  return <section className="nav-group" data-nav-group={props.group.id} data-current={containsCurrent ? 'true' : 'false'}
    onPointerEnter={() => { if (props.compact) openFlyout() }}
    onPointerLeave={() => {
      if (props.compact && !panelRef.current?.contains(document.activeElement)) {
        cancelClose(); closeTimer.current = setTimeout(props.closeFlyout, 160)
      }
    }}
    onFocus={() => { if (props.compact) cancelClose() }}
    onBlur={event => { if (props.compact && !event.currentTarget.contains(event.relatedTarget as Node | null)) props.closeFlyout() }}>
    <button className="nav-group-toggle" type="button" aria-label={props.group.label} aria-expanded={isOpen ? 'true' : 'false'} aria-controls={panelId}
      ref={buttonRef} onClick={() => { if (props.compact) openFlyout(); else setExpanded(value => !value) }}
      onKeyDown={event => {
        if (props.compact && (event.key === 'ArrowDown' || event.key === 'ArrowRight')) {
          event.preventDefault()
          focusFirst.current = true
          openFlyout()
          if (props.flyoutOpen) {
            focusFirst.current = false
            panelRef.current?.querySelector<HTMLAnchorElement>('a')?.focus()
          }
        }
      }}>
      <RouteIcon name={routes[0]!.name} /><span>{props.group.label}</span>
      <svg className="nav-chevron" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" aria-hidden="true"><path d="m6 4 4 4-4 4" /></svg>
    </button>
    <div className={props.compact ? 'nav-group-links nav-flyout' : 'nav-group-links'} id={panelId} hidden={!isOpen} ref={panelRef}
      style={props.compact ? position : undefined}>
      {props.compact ? <div className="nav-flyout-title">{props.group.label}</div> : null}
      {routes.map(item => (
        <NavLink key={item.name} item={item} route={props.route} onNavigate={() => { props.closeFlyout(); props.onNavigate?.() }} />
      ))}
    </div>
  </section>
}

function NavLink(props: { item: Route; route: RouteName; onNavigate?: () => void }) {
  return <a href={pathFor(props.item.name)}
    aria-label={props.item.label} title={props.item.label + ' · ' + props.item.description} onClick={props.onNavigate}
    aria-current={props.route === props.item.name ? 'page' : undefined}
    className={props.route === props.item.name ? 'is-active' : undefined}>
    <RouteIcon name={props.item.name} /><span>{props.item.label}</span>
  </a>
}
