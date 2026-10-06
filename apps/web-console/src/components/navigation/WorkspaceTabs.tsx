import { useLayoutEffect, useRef } from 'react'
import { Ellipsis, X } from 'lucide-react'
import { ROUTES, type RouteName } from '../../state/routes.ts'

export type WorkspaceTab = { route: RouteName; hash: string; key: string }
export function WorkspaceTabs(props: { pages: WorkspaceTab[]; active: RouteName; select: (page: WorkspaceTab) => void; close: (names: RouteName[]) => void }) {
  const tabs = useRef<HTMLDivElement>(null)
  useLayoutEffect(() => {
    const current = tabs.current?.querySelector<HTMLElement>('[aria-selected=true]')
    if (current && tabs.current) {
      const left = current.offsetLeft, right = left + current.offsetWidth
      if (left < tabs.current.scrollLeft) tabs.current.scrollLeft = left
      else if (right > tabs.current.scrollLeft + tabs.current.clientWidth) tabs.current.scrollLeft = right - tabs.current.clientWidth
    }
  }, [props.active, props.pages.length])
  function closeFromMenu(event: React.MouseEvent<HTMLButtonElement>, names: RouteName[]) {
    event.currentTarget.closest<HTMLElement>('[popover]')?.hidePopover()
    props.close(names)
  }
  return <div className="workspace-tabs">
    <div className="workspace-tab-list" role="tablist" aria-label="已打开页面" ref={tabs}>
      {props.pages.map((page, index) => {
        const label = ROUTES.find(route => route.name === page.route)!.label
        return <span className="workspace-tab" key={page.key} data-active={props.active === page.route}>
          <button type="button" data-slot="button" role="tab" id={'workspace-tab-' + page.route} aria-label={label} aria-selected={props.active === page.route} aria-controls={'workspace-pane-' + page.route}
            tabIndex={props.active === page.route ? 0 : -1} onClick={() => props.select(page)} onKeyDown={event => {
              let next: number | undefined
              if (event.key === 'ArrowRight') next = (index + 1) % props.pages.length
              if (event.key === 'ArrowLeft') next = (index - 1 + props.pages.length) % props.pages.length
              if (event.key === 'Home') next = 0
              if (event.key === 'End') next = props.pages.length - 1
              if (next !== undefined) { event.preventDefault(); const target = props.pages[next]!; props.select(target); document.getElementById('workspace-tab-' + target.route)?.focus() }
              if (event.key === 'Delete') { event.preventDefault(); props.close([page.route]) }
            }}>{label}</button>
          <button type="button" data-slot="button" className="workspace-tab-close" aria-label={'关闭' + label + '页面'} title="关闭页面" onClick={() => props.close([page.route])}><X size={12} /></button>
        </span>
      })}
    </div>
    <button className="icon-button" type="button" aria-label="页签操作" popoverTarget="workspace-tab-actions"><Ellipsis size={16} /></button>
    <div popover="auto" id="workspace-tab-actions" className="workspace-tab-menu">
      <button type="button" data-slot="button" disabled={props.pages.length < 2} onClick={event => closeFromMenu(event, props.pages.filter(page => page.route !== props.active).map(page => page.route))}>关闭其他页面</button>
      <button type="button" data-slot="button" onClick={event => closeFromMenu(event, props.pages.map(page => page.route))}>关闭所有页面</button>
    </div>
  </div>
}
