import { useEffect, useImperativeHandle, useLayoutEffect, useRef, useState, type Ref } from 'react'
import { Pencil, Eye } from 'lucide-react'
import { usePageActive } from '../../state/page-workspace.ts'

type Selection = { id: string; x: number; y: number; trigger: HTMLElement }
export type WorkflowNodeMenuHandle = { open: (selection: Selection) => void }

export function WorkflowNodeMenu(props: { ref: Ref<WorkflowNodeMenuHandle>; readOnly: boolean; edit: (id: string) => void }) {
  const active = usePageActive()
  const panel = useRef<HTMLDivElement>(null)
  const [selection, setSelection] = useState<Selection | null>(null)
  useImperativeHandle(props.ref, () => ({ open: entry => { if (active) setSelection(entry) } }), [active])
  useLayoutEffect(() => {
    if (!selection || !active || !panel.current) return
    const menu = panel.current
    menu.style.left = Math.max(8, Math.min(selection.x, window.innerWidth - 188)) + 'px'
    menu.style.top = Math.max(8, Math.min(selection.y, window.innerHeight - 64)) + 'px'
    if (!menu.matches(':popover-open')) menu.showPopover()
    menu.querySelector<HTMLButtonElement>('button')?.focus()
  }, [selection, active])
  useEffect(() => { if (!active) { panel.current?.hidePopover(); setSelection(null) } }, [active])
  return <div ref={panel} popover="auto" role="menu" aria-label="节点操作" className="workflow-node-menu" onToggle={event => {
    if ((event.nativeEvent as ToggleEvent).newState !== 'closed') return
    if (active && document.activeElement === document.body && selection?.trigger.isConnected) selection.trigger.focus()
    setSelection(null)
  }}>
    <button type="button" role="menuitem" data-node-id={selection?.id} disabled={!selection} onClick={() => {
      if (!selection) return
      panel.current?.hidePopover()
      props.edit(selection.id)
    }}>{props.readOnly ? <Eye size={14} /> : <Pencil size={14} />}{props.readOnly ? '查看节点' : '编辑节点'}</button>
  </div>
}
