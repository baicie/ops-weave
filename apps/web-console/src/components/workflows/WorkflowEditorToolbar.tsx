import type { ReactNode } from 'react'
import { GitBranch } from 'lucide-react'

export function WorkflowEditorToolbar(props: {
  name: string
  revision: number
  state: 'published' | 'dirty' | 'saved'
  disabled: boolean
  changeName: (name: string) => void
  actions: ReactNode
}) {
  return <div className="studio-editor-toolbar">
    <div className="studio-definition">
      <GitBranch size={18} aria-hidden="true" />
      <input aria-label="工作流名称" value={props.name} disabled={props.disabled} maxLength={80} onChange={e => props.changeName(e.target.value)} />
      <div className="workflow-state"><small>{'v' + props.revision}</small><span className="studio-state" data-state={props.state}>{props.state === 'published' ? '已发布' : props.state === 'dirty' ? '未保存' : '已保存'}</span></div>
    </div>
    {props.actions}
  </div>
}
