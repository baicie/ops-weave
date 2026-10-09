import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState, type MutableRefObject } from 'react'
import { PageWorkspaceContext, type PageCloseReason } from '../state/page-workspace.ts'
import { useConsoleLayout } from '../state/layout-preference.ts'
import { pathFor, routeFromHash, ROUTES, type RouteName } from '../state/routes.ts'
import { usePlatformSession } from '../state/platform-session.ts'
import { WorkspaceTabs, type WorkspaceTab } from '../components/navigation/WorkspaceTabs.tsx'
import { PageBreadcrumb } from '../components/navigation/PageBreadcrumb.tsx'
import { RoutePage } from './page-registry.tsx'
import { PlatformSessionBar } from './PlatformSessionBar.tsx'

type Guards = MutableRefObject<Map<RouteName, () => PageCloseReason>>
const entry = (route: RouteName, hash: string): WorkspaceTab => ({ route, hash, key: crypto.randomUUID() })

function PagePane(props: { page: WorkspaceTab; active: boolean; tabbed: boolean; guards: Guards }) {
  const { route } = props.page
  const pane = useRef<HTMLDivElement>(null)
  const suspendedDialogs = useRef(new Set<HTMLDialogElement>())
  useLayoutEffect(() => {
    if (props.active) {
      for (const dialog of suspendedDialogs.current) if (dialog.isConnected && !dialog.open) dialog.showModal()
      suspendedDialogs.current.clear()
      return
    }
    // A hidden native modal still makes the document inert. Suspend its presentation, keeping page state.
    const suspend = () => {
      pane.current?.querySelectorAll<HTMLDialogElement>('dialog[open]').forEach(dialog => {
        suspendedDialogs.current.add(dialog)
        dialog.close()
      })
    }
    suspend()
    const observer = new MutationObserver(suspend)
    if (pane.current) observer.observe(pane.current, { subtree: true, attributes: true, attributeFilter: ['open'] })
    return () => observer.disconnect()
  }, [props.active])
  const registerGuard = useCallback((guard: () => PageCloseReason) => {
    props.guards.current.set(route, guard)
    return () => { if (props.guards.current.get(route) === guard) props.guards.current.delete(route) }
  }, [route, props.guards])
  const context = useMemo(() => ({ active: props.active, registerGuard }), [props.active, registerGuard])
  return <div ref={pane} className={'console-workspace page-pane' + (route === 'source-center' || route === 'workflows' ? ' integration-workspace' : '')}
    id={'workspace-pane-' + route} hidden={!props.active} inert={!props.active} data-page-route={route}
    role={props.tabbed ? 'tabpanel' : undefined} aria-labelledby={props.tabbed ? 'workspace-tab-' + route : undefined}>
    <PageWorkspaceContext.Provider value={context}>
      {route !== 'diagnose' && route !== 'start' ? <PlatformSessionBar /> : null}
      <RoutePage route={route} />
    </PageWorkspaceContext.Provider>
  </div>
}

export function PageWorkspace(props: { route: RouteName }) {
  const layout = useConsoleLayout()
  const [active, setActive] = useState(() => ({ route: props.route, hash: location.hash || pathFor(props.route) }))
  const [pages, setPages] = useState(() => [entry(active.route, active.hash)])
  const activeRef = useRef(active)
  const pagesRef = useRef(pages)
  const cache = useRef(layout === 'tabs')
  const viewport = useRef<HTMLElement>(null)
  const scroll = useRef(new Map<RouteName, number>())
  const guards = useRef(new Map<RouteName, () => PageCloseReason>())
  const dialog = useRef<HTMLDialogElement>(null)
  const [closing, setClosing] = useState<{ names: RouteName[]; reasons: Exclude<PageCloseReason, null>[] } | null>(null)
  if (layout === 'tabs') cache.current = true
  activeRef.current = active
  pagesRef.current = pages
  usePlatformSession(() => {
    setPages(current => current.filter(page => page.route === activeRef.current.route))
    scroll.current.clear()
    dialog.current?.close(); setClosing(null)
  })

  useEffect(() => {
    function navigate(event: Event) {
      const hash = location.hash || '#/start'
      const route = routeFromHash(hash)
      const previous = activeRef.current
      const oldHash = event instanceof HashChangeEvent ? new URL(event.oldURL).hash : previous.hash
      if (viewport.current) scroll.current.set(previous.route, viewport.current.scrollTop)
      setPages(current => {
        const remembered = current.map(page => page.route === previous.route && routeFromHash(oldHash) === previous.route ? { ...page, hash: oldHash } : page)
        const exists = remembered.find(page => page.route === route)
        const next = exists ? { ...exists, hash } : entry(route, hash)
        return cache.current ? exists ? remembered.map(page => page.route === route ? next : page) : [...remembered, next] : [next]
      })
      const next = { route, hash }; activeRef.current = next; setActive(next)
    }
    function intercept(event: MouseEvent) {
      if (!cache.current || event.defaultPrevented || event.button !== 0 || event.ctrlKey || event.metaKey || event.altKey || event.shiftKey) return
      const anchor = (event.target as Element)?.closest<HTMLAnchorElement>('a[href]')
      if (!anchor?.closest('.app-shell') || anchor.target || anchor.hasAttribute('download')) return
      const hash = anchor.getAttribute('href') ?? ''
      const route = ROUTES.find(page => hash === pathFor(page.name))?.name
      const existing = pagesRef.current.find(page => page.route === route)
      if (!existing) return
      event.preventDefault()
      const remembered = existing.route === activeRef.current.route ? location.hash : existing.hash
      if (location.hash !== remembered) location.hash = remembered
    }
    window.addEventListener('hashchange', navigate)
    document.addEventListener('click', intercept, true)
    return () => { window.removeEventListener('hashchange', navigate); document.removeEventListener('click', intercept, true) }
  }, [])

  useLayoutEffect(() => { if (viewport.current) viewport.current.scrollTop = scroll.current.get(active.route) ?? 0 }, [active.route])
  function select(page: WorkspaceTab) { if (location.hash !== page.hash) location.hash = page.hash }
  function close(names: RouteName[]) {
    const current = pagesRef.current
    const remaining = current.filter(page => !names.includes(page.route))
    if (!remaining.length) remaining.push(entry('start', '#/start'))
    const activeIndex = current.findIndex(page => page.route === activeRef.current.route)
    const next = remaining[Math.min(Math.max(0, activeIndex - 1), remaining.length - 1)]!
    setPages(remaining)
    for (const name of names) scroll.current.delete(name)
    if (names.includes(activeRef.current.route)) {
      const address = { route: next.route, hash: next.hash }; activeRef.current = address; setActive(address); select(next)
      queueMicrotask(() => document.getElementById('workspace-tab-' + next.route)?.focus())
    }
  }
  function requestClose(names: RouteName[]) {
    if (!names.length) return
    const reasons = names.map(name => guards.current.get(name)?.()).filter((reason): reason is Exclude<PageCloseReason, null> => Boolean(reason))
    if (!reasons.length) { close(names); return }
    setClosing({ names, reasons })
  }
  useLayoutEffect(() => { if (closing) dialog.current?.showModal() }, [closing])
  return <>
    {layout === 'tabs' ? <WorkspaceTabs pages={pages} active={active.route} select={select} close={requestClose} /> : null}
    <PageBreadcrumb route={active.route} />
    <main id="workspace" tabIndex={-1} ref={viewport} data-route={active.route} data-layout={layout} className="workspace-pages"
      onScroll={event => scroll.current.set(activeRef.current.route, event.currentTarget.scrollTop)}>
      {pages.map(page => <PagePane key={page.key} page={page} active={page.route === active.route} tabbed={layout === 'tabs'} guards={guards} />)}
    </main>
    <dialog className="page-close-dialog" ref={dialog} aria-labelledby="page-close-title" onClose={() => setClosing(null)}>
      <div className="page-close-content">
        <h2 id="page-close-title">{closing?.reasons.some(reason => reason.blocked) ? '页面暂时不能关闭' : '关闭页面前确认'}</h2>
        <p>{closing?.names.map(name => ROUTES.find(route => route.name === name)!.label).join('、')}</p>
        {closing?.reasons.map((reason, index) => <p key={index}>{reason.message}</p>)}
        {!closing?.reasons.some(reason => reason.blocked) ? <p>关闭会丢弃本地编辑，已保存的服务端数据不受影响。</p> : null}
        <div><button type="button" data-slot="button" autoFocus onClick={() => dialog.current?.close()}>保留页面</button>
          <button type="button" data-slot="button" disabled={closing?.reasons.some(reason => reason.blocked)} onClick={() => { if (closing) close(closing.names); dialog.current?.close() }}>关闭页面</button></div>
      </div>
    </dialog>
  </>
}
