import { ModulePlaceholder } from '../../components/ModulePlaceholder.tsx'

export function AgentPage() {
  return (
    <ModulePlaceholder
      title="Agent 控制台"
      capability="未接入 @zeus-web/agent-console。适配层只定义展示模型，不把 Token、MCP 或模型 Key 传给组件库。"
      next="先完成诊断页联调，再单独验收 chat / agent-console 的 WC 入口、属性绑定与卸载清理。"
    />
  )
}
