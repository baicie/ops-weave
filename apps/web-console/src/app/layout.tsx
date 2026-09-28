import { createSignal, For, onCleanup, Show } from '@zeus-js/zeus'
import { AppNav, RouteIcon } from './navigation.tsx'
import { forItem } from '../adapters/zeus-ui/for-item.ts'
import { groupFor, pathFor, ROUTES, searchRoutes, type Route, type RouteName } from '../state/routes.ts'
import { oidcMode } from '../state/platform-session.ts'

const uiIcons = {
  sidebar: 'M4 4h16v16H4z M9 4v16',
  search: 'M21 21l-5-5 M18 10a8 8 0 1 1-16 0 8 8 0 0 1 16 0',
  sun: 'M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8 M12 2v2 M12 20v2 M2 12h2 M20 12h2 M5 5l1.5 1.5 M17.5 17.5l1.5 1.5 M5 19l1.5-1.5 M17.5 6.5l1.5-1.5',
  moon: 'M20.5 14A9 9 0 0 1 10 3.5 9 9 0 1 0 20.5 14z',
  close: 'M6 6l12 12 M6 18 18 6',
  shield: 'M12 3 3 7v5c0 5 9 9 9 9s9-4 9-9V7l-9-4z M8 12l3 3 5-6',
}
function UiIcon(props: { name: keyof typeof uiIcons }) {
  return <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d={uiIcons[props.name]} /></svg>
}

function Brand(props: { onNavigate?: () => void }) {
  return <a class="brand" href="#/start" onClick={props.onNavigate} aria-label="观织 OpsWeave 首页">
    <span class="brand-mark" aria-hidden="true"><i /><i /><i /><i /></span>
    <span class="brand-copy"><strong>观织 <small>OpsWeave</small></strong><em>可观测与智能运维</em></span>
  </a>
}
function SidebarContent(props: { route: () => RouteName; onNavigate?: () => void; compact?: () => boolean; surface: 'desktop' | 'mobile' }) {
  return <><Brand onNavigate={props.onNavigate} /><AppNav route={props.route} onNavigate={props.onNavigate} compact={props.compact} surface={props.surface} />
    <div class="sidebar-note"><UiIcon name="shield" /><span class="sidebar-note-copy"><strong>只读诊断</strong><small>关联上下文 · 追溯证据</small></span><span class="sidebar-version">MVP</span></div>
  </>
}
export function AppSidebar(props: { route: () => RouteName; collapsed: () => boolean }) {
  return <aside class="app-sidebar" id="desktop-navigation"><SidebarContent route={props.route} compact={props.collapsed} surface="desktop" /></aside>
}

function initialTheme(): 'light' | 'dark' {
  try {
    const saved = localStorage.getItem('opsweave.ui.theme')
    if (saved === 'light' || saved === 'dark') return saved
  } catch { /* Storage may be unavailable; theme remains local to this page. */ }
  return matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
}

function PageResult(props: { item: Route; close: () => void }) {
  return <a href={pathFor(props.item.name)} onClick={props.close}><RouteIcon name={props.item.name} /><span>{props.item.label}<small>{groupFor(props.item.name).label} · {props.item.description}</small></span><span class="finder-enter" aria-hidden="true">↵</span></a>
}

export function AppHeader(props: { route: () => RouteName; collapsed: () => boolean; toggleSidebar: () => void }) {
  const [theme, setTheme] = createSignal(initialTheme())
  const [query, setQuery] = createSignal('')
  let finder: HTMLDialogElement | null = null
  let drawer: HTMLDialogElement | null = null
  let searchInput: HTMLInputElement | null = null
  document.documentElement.dataset.theme = theme()
  const mobile = matchMedia('(max-width: 760px)')
  const matches = () => searchRoutes(query())
  function toggleTheme() {
    const next = theme() === 'light' ? 'dark' : 'light'
    setTheme(next); document.documentElement.dataset.theme = next
    try { localStorage.setItem('opsweave.ui.theme', next) } catch { /* UI preference only. */ }
  }
  function openFinder() {
    drawer?.close(); setQuery(''); finder?.showModal(); searchInput?.focus()
  }
  function toggleNavigation() { if (mobile.matches) drawer?.showModal(); else props.toggleSidebar() }
  function keyboard(event: KeyboardEvent) {
    if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k' && !event.isComposing) {
      event.preventDefault(); if (finder?.open) finder.close(); else openFinder()
    }
  }
  function moveResult(event: KeyboardEvent) {
    if (event.key !== 'ArrowDown' && event.key !== 'ArrowUp') return
    const links = Array.from(finder?.querySelectorAll<HTMLAnchorElement>('.finder-results a') ?? [])
    if (!links.length) return
    event.preventDefault()
    const current = links.indexOf(document.activeElement as HTMLAnchorElement)
    const next = current < 0 ? (event.key === 'ArrowDown' ? 0 : links.length - 1) : (current + (event.key === 'ArrowDown' ? 1 : -1) + links.length) % links.length
    links[next]?.focus()
  }
  function resized() { if (!mobile.matches) drawer?.close() }
  document.addEventListener('keydown', keyboard)
  mobile.addEventListener('change', resized)
  onCleanup(() => { document.removeEventListener('keydown', keyboard); mobile.removeEventListener('change', resized) })

  return <>
    <header class="app-header">
      <div class="header-leading">
        <button class="icon-button sidebar-toggle" type="button" aria-label="切换导航" title={() => props.collapsed() ? '展开导航' : '收起导航'} onClick={toggleNavigation}><UiIcon name="sidebar" /></button>
        <div class="header-divider" />
        <div class="breadcrumb" aria-label="当前位置"><span>{groupFor(props.route()).label}</span><span class="breadcrumb-separator">/</span><strong>{ROUTES.find(item => item.name === props.route())?.label ?? '工作台'}</strong></div>
      </div>
      <div class="header-tools">
        <button class="search-trigger" type="button" onClick={openFinder} aria-label="搜索页面"><UiIcon name="search" /><span>搜索页面…</span><kbd>⌘ / Ctrl K</kbd></button>
        <button class="icon-button theme-toggle" type="button" onClick={toggleTheme} aria-label={() => theme() === 'light' ? '切换到深色模式' : '切换到浅色模式'} title={() => theme() === 'light' ? '切换到深色模式' : '切换到浅色模式'}><Show when={theme() === 'light'}><UiIcon name="sun" /></Show><Show when={theme() === 'dark'}><UiIcon name="moon" /></Show></button>
        <span class="environment-badge"><span class="status-dot" />{oidcMode ? '受控会话' : '本地开发'}</span>
      </div>
    </header>
    <dialog class="navigation-drawer" aria-label="移动端导航" ref={(node: HTMLDialogElement | null) => { drawer = node }} onClick={(event: MouseEvent) => { if (event.target === drawer) drawer?.close() }}>
      <div class="drawer-content"><button class="icon-button drawer-close" type="button" aria-label="关闭导航" onClick={() => drawer?.close()}><UiIcon name="close" /></button><SidebarContent route={props.route} onNavigate={() => drawer?.close()} surface="mobile" /></div>
    </dialog>
    <dialog class="page-finder" aria-labelledby="finder-title" ref={(node: HTMLDialogElement | null) => { finder = node }} onKeyDown={moveResult} onClick={(event: MouseEvent) => { if (event.target === finder) finder?.close() }}>
      <div class="finder-content">
        <div class="finder-heading"><h2 id="finder-title">跳转到页面</h2><button class="icon-button" type="button" aria-label="关闭页面搜索" onClick={() => finder?.close()}><UiIcon name="close" /></button></div>
        <label class="finder-input"><UiIcon name="search" /><input aria-label="页面名称或路径" placeholder="搜索页面、分类或关键词…" autocomplete="off" ref={node => { searchInput = node }} prop:value={query()} onInput={event => setQuery((event.target as HTMLInputElement).value)} /></label>
        <div class="finder-results"><For each={matches()}>{row => <PageResult item={forItem(row)} close={() => finder?.close()} />}</For></div>
        <Show when={matches().length === 0}><p class="finder-empty">没有匹配的页面，请尝试其他名称。</p></Show>
        <div class="finder-footer"><span aria-live="polite">{matches().length} 个页面</span><span>↑ ↓ 选择 · Enter 打开 · Esc 关闭</span></div>
      </div>
    </dialog>
  </>
}

export function AppFooter() {
  return <footer><span>OpsWeave <span class="footer-dot">·</span> 只读诊断 MVP</span><span>证据引用校验不代表根因已经被证明。开发预览不代表生产验收。</span></footer>
}
