import { useEffect, useId, useRef, type ReactNode } from 'react'
import { Ellipsis } from 'lucide-react'

/** Secondary actions keep their page-owned guards; opening this menu performs no request. */
export function WorkflowMoreActions(props: { active: boolean; children: ReactNode }) {
  const id = useId(), panel = useRef<HTMLDivElement>(null), trigger = useRef<HTMLButtonElement>(null)
  useEffect(() => { if (!props.active && panel.current?.matches(':popover-open')) panel.current.hidePopover() }, [props.active])
  return <div className="workflow-more-actions"><button ref={trigger} type="button" popoverTarget={id} aria-label="更多工作流操作" title="更多操作"><Ellipsis size={17} /></button>
    <div ref={panel} id={id} popover="auto" className="workflow-more-panel" aria-label="更多工作流操作" onBeforeToggle={event => {
      if ((event.nativeEvent as ToggleEvent).newState !== 'open' || !trigger.current || !panel.current) return
      const box = trigger.current.getBoundingClientRect(), below = window.innerHeight - box.bottom - 18
      panel.current.style.right = Math.max(12, window.innerWidth - box.right) + 'px'
      panel.current.style.top = below >= 180 ? box.bottom + 6 + 'px' : 'auto'
      panel.current.style.bottom = below >= 180 ? 'auto' : window.innerHeight - box.top + 6 + 'px'
      panel.current.style.maxHeight = Math.max(80, below >= 180 ? below : box.top - 18) + 'px'
    }} onClick={event => {
      const action = (event.target as HTMLElement).closest('button,a')
      if (action && !(action instanceof HTMLButtonElement && action.disabled) && panel.current?.matches(':popover-open')) panel.current.hidePopover()
    }} onToggle={event => {
      if ((event.nativeEvent as ToggleEvent).newState === 'open') panel.current?.querySelector<HTMLElement>('button:not(:disabled),a')?.focus()
      if ((event.nativeEvent as ToggleEvent).newState === 'closed' && props.active && (document.activeElement === document.body || panel.current?.contains(document.activeElement))) trigger.current?.focus()
    }}>{props.children}</div>
  </div>
}
