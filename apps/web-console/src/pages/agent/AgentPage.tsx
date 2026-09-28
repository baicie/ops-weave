import { ModulePlaceholder } from '../../components/ModulePlaceholder.tsx'

export function AgentPage() {
  return (
    <ModulePlaceholder
      title="Agent 控制台"
      capability="控制台基于 React + shadcn/ui，Agent 控制台尚未接入。适配层只定义展示模型，不把 Token、MCP 或模型 Key 传给组件库。"
      next="先完成诊断页联调，再单独验收 Agent 控制台的焦点、卸载和权限边界。"
    />
  )
}
