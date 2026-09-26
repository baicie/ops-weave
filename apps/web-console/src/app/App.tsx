import { Show } from '@zeus-js/zeus'
import { AppFooter, AppHeader } from './layout.tsx'
import { AgentPage } from '../pages/agent/AgentPage.tsx'
import { DiagnosePage } from '../pages/incidents/DiagnosePage.tsx'
import { CurrentDiagnosisPage } from '../pages/incidents/CurrentDiagnosisPage.tsx'
import { ReorganizationPage } from '../pages/incidents/ReorganizationPage.tsx'
import { IncidentsPage } from '../pages/incidents/IncidentsPage.tsx'
import { InventoryPage } from '../pages/inventory/InventoryPage.tsx'
import { MetricsPage } from '../pages/metrics/MetricsPage.tsx'
import { PipelinesPage } from '../pages/pipelines/PipelinesPage.tsx'
import { SkillsPage } from '../pages/skills/SkillsPage.tsx'
import { createHashRoute } from '../state/hash-route.ts'
import { installSessionLifecycle } from '../state/platform-session.ts'
import { PlatformSessionBar } from './PlatformSessionBar.tsx'
import { RetentionPage } from '../pages/retention/RetentionPage.tsx'
import { SourceSnapshotsPage } from '../pages/inventory/SourceSnapshotsPage.tsx'
import { SourceBindingCorrectionsPage } from '../pages/inventory/SourceBindingCorrectionsPage.tsx'
import { SourceScanRunsPage } from '../pages/integrations/SourceScanRunsPage.tsx'

export function App() {
  const { route } = createHashRoute()
  installSessionLifecycle()
  return (
    <div class="app-shell">
      <AppHeader route={route} />
      <Show when={route() !== 'diagnose'}><PlatformSessionBar /></Show>
      <Show when={route() === 'reorganize'}><ReorganizationPage /></Show>
      <Show when={route() === 'retention'}><RetentionPage /></Show>
      <Show when={route() === 'source-snapshots'}><SourceSnapshotsPage /></Show>
      <Show when={route() === 'source-corrections'}><SourceBindingCorrectionsPage /></Show>
      <Show when={route() === 'source-scan-runs'}><SourceScanRunsPage /></Show>
      <Show when={route() === 'current-diagnose'}><CurrentDiagnosisPage /></Show>
      <Show when={route() === 'incidents'}>
        <IncidentsPage />
      </Show>
      <Show when={route() === 'inventory'}>
        <InventoryPage />
      </Show>
      <Show when={route() === 'metrics'}>
        <MetricsPage />
      </Show>
      <Show when={route() === 'pipelines'}>
        <PipelinesPage />
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
