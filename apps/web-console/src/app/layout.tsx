import { useEffect, useLayoutEffect, useRef, useState, type KeyboardEvent } from 'react'
import { AppNav, RouteIcon } from './navigation.tsx'
import { groupFor, pathFor, searchRoutes, type Route, type RouteName } from '../state/routes.ts'
import { oidcMode } from '../state/platform-session.ts'
import { SidebarFooter } from '../components/navigation/SidebarFooter.tsx'
import { LayoutSettings } from '../components/navigation/LayoutSettings.tsx'

const uiIcons = {
  sidebar: 'M4 4h16v16H4z M9 4v16',
  search: 'M21 21l-5-5 M18 10a8 8 0 1 1-16 0 8 8 0 0 1 16 0',
  sun: 'M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8 M12 2v2 M12 20v2 M2 12h2 M20 12h2 M5 5l1.5 1.5 M17.5 17.5l1.5 1.5 M5 19l1.5-1.5 M17.5 6.5l1.5-1.5',
  moon: 'M20.5 14A9 9 0 0 1 10 3.5 9 9 0 1 0 20.5 14z',
  close: 'M6 6l12 12 M6 18 18 6',
  shield: 'M12 3 3 7v5c0 5 9 9 9 9s9-4 9-9V7l-9-4z M8 12l3 3 5-6',
}
function UiIcon(props: { name: keyof typeof uiIcons }) {
  return <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d={uiIcons[props.name]} /></svg>
}

function Brand(props: { onNavigate?: () => void }) {
  return <a className="brand" href="#/start" onClick={props.onNavigate} aria-label="观织 OpsWeave 首页">
    <span className="brand-mark" aria-hidden="true"><i /><i /><i /><i /></span>
    <span className="brand-copy"><strong>观织<span>OpsWeave</span></strong><em>运维管理平台</em></span>
  </a>
}
function SidebarContent(props: { route: RouteName; onNavigate?: () => void; onToggle?: () => void; compact?: boolean; surface: 'desktop' | 'mobile' }) {
  return <><Brand onNavigate={props.onNavigate} /><AppNav route={props.route} onNavigate={props.onNavigate} compact={props.compact} surface={props.surface} />
    <SidebarFooter collapsed={props.compact} onToggle={props.onToggle} environment={oidcMode ? '身份会话模式' : '本地开发环境'} />
  </>
}
export function AppSidebar(props: { route: RouteName; collapsed: boolean; onToggle: () => void }) {
  return <aside className="app-sidebar" id="desktop-navigation"><SidebarContent route={props.route} compact={props.collapsed} onToggle={props.onToggle} surface="desktop" /></aside>
}

function initialTheme(): 'light' | 'dark' {
  try {
    const saved = localStorage.getItem('opsweave.ui.theme')
    if (saved === 'light' || saved === 'dark') return saved
  } catch { /* Storage may be unavailable; theme remains local to this page. */ }
  return matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
}

function applyTheme(theme: 'light' | 'dark') {
  document.documentElement.dataset.theme = theme
  document.documentElement.classList.toggle('dark', theme === 'dark')
}

function PageResult(props: { item: Route; close: () => void }) {
  return <a href={pathFor(props.item.name)} onClick={props.close}><RouteIcon name={props.item.name} /><span>{props.item.label}<small>{groupFor(props.item.name).label} · {props.item.description}</small></span><span className="finder-enter" aria-hidden="true">↵</span></a>
}

export function AppHeader(props: { route: RouteName; collapsed: boolean; toggleSidebar: () => void }) {
  const [theme, setTheme] = useState(initialTheme)
  const [query, setQuery] = useState('')
  const finderRef = useRef<HTMLDialogElement>(null)
  const drawerRef = useRef<HTMLDialogElement>(null)
  const searchInputRef = useRef<HTMLInputElement>(null)
  const mobileRef = useRef(matchMedia('(max-width: 760px)'))
  const matches = searchRoutes(query)

  function toggleTheme() {
    setTheme(current => {
      const next = current === 'light' ? 'dark' : 'light'
      try { localStorage.setItem('opsweave.ui.theme', next) } catch { /* UI preference only. */ }
      return next
    })
  }
  function openFinder() {
    drawerRef.current?.close(); setQuery(''); finderRef.current?.showModal(); searchInputRef.current?.focus()
  }
  function toggleNavigation() { if (mobileRef.current.matches) drawerRef.current?.showModal(); else props.toggleSidebar() }
  function moveResult(event: KeyboardEvent<HTMLDialogElement>) {
    if (event.key !== 'ArrowDown' && event.key !== 'ArrowUp') return
    const links = Array.from(finderRef.current?.querySelectorAll<HTMLAnchorElement>('.finder-results a') ?? [])
    if (!links.length) return
    event.preventDefault()
    const current = links.indexOf(document.activeElement as HTMLAnchorElement)
    const next = current < 0 ? (event.key === 'ArrowDown' ? 0 : links.length - 1) : (current + (event.key === 'ArrowDown' ? 1 : -1) + links.length) % links.length
    links[next]?.focus()
  }

  useLayoutEffect(() => {
    applyTheme(theme)
  }, [theme])

  useEffect(() => {
    const mobile = mobileRef.current
    function keyboard(event: globalThis.KeyboardEvent) {
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k' && !event.isComposing) {
        event.preventDefault()
        if (finderRef.current?.open) finderRef.current.close()
        else {
          drawerRef.current?.close()
          setQuery('')
          finderRef.current?.showModal()
          searchInputRef.current?.focus()
        }
      }
    }
    function resized() { if (!mobile.matches) drawerRef.current?.close() }
    document.addEventListener('keydown', keyboard)
    mobile.addEventListener('change', resized)
    return () => { document.removeEventListener('keydown', keyboard); mobile.removeEventListener('change', resized) }
  }, [])

  const themeLabel = theme === 'light' ? '切换到深色模式' : '切换到浅色模式'

  return <>
    <header className="app-header">
      <div className="header-leading">
        <button className="icon-button sidebar-toggle" type="button" aria-label="切换导航" title={props.collapsed ? '展开导航' : '收起导航'} onClick={toggleNavigation}><UiIcon name="sidebar" /></button>
        <button className="search-trigger" type="button" onClick={openFinder} aria-label="搜索页面"><UiIcon name="search" /><span>搜索页面</span><kbd>Ctrl K</kbd></button>
      </div>
      <div className="header-tools">
        <button className="icon-button theme-toggle" type="button" onClick={toggleTheme} aria-label={themeLabel} title={themeLabel}>{theme === 'light' ? <UiIcon name="sun" /> : null}{theme === 'dark' ? <UiIcon name="moon" /> : null}</button>
        <LayoutSettings />
        <span className="environment-badge"><span className="status-dot" />{oidcMode ? '受控会话' : '本地开发'}</span>
      </div>
    </header>
    <dialog className="navigation-drawer" aria-label="移动端导航" ref={drawerRef} onClick={event => { if (event.target === drawerRef.current) drawerRef.current?.close() }}>
      <div className="drawer-content"><button className="icon-button drawer-close" type="button" aria-label="关闭导航" onClick={() => drawerRef.current?.close()}><UiIcon name="close" /></button><SidebarContent route={props.route} onNavigate={() => drawerRef.current?.close()} surface="mobile" /></div>
    </dialog>
    <dialog className="page-finder" aria-labelledby="finder-title" ref={finderRef} onKeyDown={moveResult} onClick={event => { if (event.target === finderRef.current) finderRef.current?.close() }}>
      <div className="finder-content">
        <div className="finder-heading"><h2 id="finder-title">跳转到页面</h2><button className="icon-button" type="button" aria-label="关闭页面搜索" onClick={() => finderRef.current?.close()}><UiIcon name="close" /></button></div>
        <label className="finder-input"><UiIcon name="search" /><input aria-label="页面名称或路径" placeholder="搜索页面、分类或关键词…" autoComplete="off" ref={searchInputRef} value={query} onInput={event => setQuery((event.target as HTMLInputElement).value)} /></label>
        <div className="finder-results">{matches.map(item => <PageResult key={item.name} item={item} close={() => finderRef.current?.close()} />)}</div>
        {matches.length === 0 ? <p className="finder-empty">没有匹配的页面，请尝试其他名称。</p> : null}
        <div className="finder-footer"><span aria-live="polite">{matches.length} 个页面</span><span>↑ ↓ 选择 · Enter 打开 · Esc 关闭</span></div>
      </div>
    </dialog>
  </>
}

export function AppFooter() {
  return <footer><span>OpsWeave <span className="footer-dot">·</span> 运维管理平台</span><span>{oidcMode ? '平台会话' : '本地开发预览'}</span></footer>
}
