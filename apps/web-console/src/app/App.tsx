import { useState } from 'react'
import { StartPage } from '../pages/start/StartPage.tsx'
import { TopologyPage } from '../pages/inventory/TopologyPage.tsx'
import { PageGuide } from './PageGuide.tsx'
import { WorkflowRunsPage } from '../pages/workflows/WorkflowRunsPage.tsx'
import { SourceCenterPage } from '../pages/integrations/SourceCenterPage.tsx'
import { WorkflowPage } from '../pages/workflows/WorkflowPage.tsx'
import { ModelCatalogPage } from '../pages/modeling/ModelCatalogPage.tsx'
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
import { useHashRoute } from '../state/hash-route.ts'
import { useSessionLifecycle } from '../state/platform-session.ts'
import { PlatformSessionBar } from './PlatformSessionBar.tsx'
import { RetentionPage } from '../pages/retention/RetentionPage.tsx'
import { SourceSnapshotsPage } from '../pages/inventory/SourceSnapshotsPage.tsx'
import { SourceBindingCorrectionsPage } from '../pages/inventory/SourceBindingCorrectionsPage.tsx'
import { SourceScanRunsPage } from '../pages/integrations/SourceScanRunsPage.tsx'

export function App() {
  const route = useHashRoute()
  useSessionLifecycle()
  const [collapsed, setCollapsed] = useState(false)
  return (
    <div className={collapsed ? 'app-shell sidebar-collapsed' : 'app-shell'}>
      <button className="skip-link" type="button" onClick={() => document.getElementById('workspace')?.focus()}>跳到主要内容</button>
      <AppSidebar route={route} collapsed={collapsed} />
      <div className="workspace">
      <AppHeader route={route} collapsed={collapsed} toggleSidebar={() => setCollapsed(value => !value)} />
      <main id="workspace" tabIndex={-1}>
      {route !== 'diagnose' && route !== 'start' ? <PlatformSessionBar /> : null}
      {route !== 'start' ? <PageGuide route={route} /> : null}
      {route === 'start' ? <StartPage /> : null}
      {route === 'topology' ? <TopologyPage /> : null}
      {route === 'reorganize' ? <ReorganizationPage /> : null}
      {route === 'model-entities' ? <ModelCatalogPage mode="ENTITY" /> : null}
      {route === 'model-metrics' ? <ModelCatalogPage mode="METRIC" /> : null}
      {route === 'model-relations' ? <ModelCatalogPage mode="RELATION" /> : null}
      {route === 'retention' ? <RetentionPage /> : null}
      {route === 'source-snapshots' ? <SourceSnapshotsPage /> : null}
      {route === 'source-corrections' ? <SourceBindingCorrectionsPage /> : null}
      {route === 'source-scan-runs' ? <SourceScanRunsPage /> : null}
      {route === 'current-diagnose' ? <CurrentDiagnosisPage /> : null}
      {route === 'incidents' ? <IncidentsPage /> : null}
      {route === 'inventory' ? <InventoryPage /> : null}
      {route === 'metrics' ? <MetricsPage /> : null}
      {route === 'source-center' ? <SourceCenterPage /> : null}
      {route === 'workflow-runs' ? <WorkflowRunsPage /> : null}
      {route === 'workflows' ? <WorkflowPage /> : null}
      {route === 'pipelines' ? <PipelinesPage /> : null}
      {route === 'skills' ? <SkillsPage /> : null}
      {route === 'agent' ? <AgentPage /> : null}
      {route === 'diagnose' ? <DiagnosePage /> : null}
      </main>
      <AppFooter />
      </div>
    </div>
  )
}
