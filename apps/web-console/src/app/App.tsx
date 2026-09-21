import { Show } from '@zeus-js/zeus'
import { AppFooter, AppHeader } from './layout.tsx'
import { AgentPage } from '../pages/agent/AgentPage.tsx'
import { DiagnosePage } from '../pages/incidents/DiagnosePage.tsx'
import { InventoryPage } from '../pages/inventory/InventoryPage.tsx'
import { MetricsPage } from '../pages/metrics/MetricsPage.tsx'
import { SkillsPage } from '../pages/skills/SkillsPage.tsx'
import { createHashRoute } from '../state/hash-route.ts'

export function App() {
  const { route } = createHashRoute()
  return (
    <div class="app-shell">
      <AppHeader route={route} />
      <Show when={route() === 'inventory'}>
        <InventoryPage />
      </Show>
      <Show when={route() === 'metrics'}>
        <MetricsPage />
      </Show>
      <Show when={route() === 'skills'}>
        <SkillsPage />
      </Show>
      <Show when={route() === 'agent'}>
        <AgentPage />
      </Show>
      <Show when={route() === 'diagnose'}>
        <DiagnosePage />
      </Show>
      <AppFooter />
    </div>
  )
}
