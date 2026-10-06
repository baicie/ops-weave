import { useEffect, useImperativeHandle, useRef, type ReactNode, type Ref } from 'react'
import { X } from 'lucide-react'

export type WorkflowInspectorHandle = { open: (nodeId?: string) => void; close: () => void }

/** Configuration opens only on an explicit edit action and never takes canvas space. */
export function WorkflowInspector(props: { active: boolean; ref: Ref<WorkflowInspectorHandle>; children: ReactNode; onOpenChange?: (open: boolean) => void }) {
  const panel = useRef<HTMLElement>(null)
  const opener = useRef<HTMLElement | null>(null)
  const openerNode = useRef<string | null>(null)
  useImperativeHandle(props.ref, () => ({
    open: (nodeId) => {
      if (!props.active || panel.current?.matches(':popover-open')) return
      opener.current = document.activeElement instanceof HTMLElement ? document.activeElement : null
      openerNode.current = nodeId ?? opener.current?.dataset.nodeId ?? null
      panel.current?.showPopover()
      panel.current?.querySelector<HTMLButtonElement>('.studio-inspector-close')?.focus()
    },
    close: () => panel.current?.hidePopover(),
  }), [props.active])
  useEffect(() => { if (!props.active && panel.current?.matches(':popover-open')) panel.current.hidePopover() }, [props.active])
  return <aside ref={panel} className="workflow-inspector studio-inspector" popover="auto" role="dialog" aria-modal="false" aria-label="节点配置" onToggle={event => {
    props.onOpenChange?.((event.nativeEvent as ToggleEvent).newState === 'open')
    if ((event.nativeEvent as ToggleEvent).newState !== 'closed' || !props.active || !(document.activeElement === document.body || panel.current?.contains(document.activeElement))) return
    // X6 replaces its HTML node after an edit; restore the corresponding current button.
    const trigger = opener.current?.isConnected && opener.current.getClientRects().length ? opener.current : openerNode.current ? panel.current?.closest('.studio-workbench')?.querySelector<HTMLElement>('button[data-node-id="' + CSS.escape(openerNode.current) + '"]') : null
    trigger?.focus()
  }}>
    <button type="button" className="studio-inspector-close" aria-label="关闭节点配置" autoFocus onClick={() => panel.current?.hidePopover()}><X size={16} /></button>
    {props.children}
  </aside>
}
