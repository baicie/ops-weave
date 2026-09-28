import { StartPage } from '../pages/start/StartPage.tsx'
import { TopologyPage } from '../pages/inventory/TopologyPage.tsx'
import { PageGuide } from './PageGuide.tsx'
import { WorkflowRunsPage } from '../pages/workflows/WorkflowRunsPage.tsx'
import { SourceCenterPage } from '../pages/integrations/SourceCenterPage.tsx'
import { WorkflowPage } from '../pages/workflows/WorkflowPage.tsx'
import { ModelCatalogPage } from '../pages/modeling/ModelCatalogPage.tsx'
import { createSignal, Show } from '@zeus-js/zeus'
import { AppFooter, AppHeader, AppSidebar } from './layout.tsx'
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
  const [collapsed, setCollapsed] = createSignal(false)
  return (
    <div class={() => collapsed() ? 'app-shell sidebar-collapsed' : 'app-shell'}>
      <button class="skip-link" type="button" onClick={() => document.getElementById('workspace')?.focus()}>跳到主要内容</button>
      <AppSidebar route={route} collapsed={collapsed} />
      <div class="workspace">
      <AppHeader route={route} collapsed={collapsed} toggleSidebar={() => setCollapsed(value => !value)} />
      <main id="workspace" tabindex="-1">
      <Show when={route() !== 'diagnose' && route() !== 'start'}><PlatformSessionBar /></Show>
      <Show when={route() !== 'start'}><PageGuide route={route} /></Show>
      <Show when={route() === 'start'}><StartPage /></Show>
      <Show when={route() === 'topology'}><TopologyPage /></Show>
      <Show when={route() === 'reorganize'}><ReorganizationPage /></Show>
      <Show when={route() === 'model-entities'}><ModelCatalogPage mode="ENTITY" /></Show>
      <Show when={route() === 'model-metrics'}><ModelCatalogPage mode="METRIC" /></Show>
      <Show when={route() === 'model-relations'}><ModelCatalogPage mode="RELATION" /></Show>
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
      <Show when={route() === 'source-center'}><SourceCenterPage /></Show>
      <Show when={route() === 'workflow-runs'}><WorkflowRunsPage /></Show>
      <Show when={route() === 'workflows'}><WorkflowPage /></Show>
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
      </main>
      <AppFooter />
      </div>
    </div>
  )
}
