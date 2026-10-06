import type { ComponentType } from 'react'
import type { RouteName } from '../state/routes.ts'
import { StartPage } from '../pages/start/StartPage.tsx'
import { TopologyPage } from '../pages/inventory/TopologyPage.tsx'
import { WorkflowRunsPage } from '../pages/workflows/WorkflowRunsPage.tsx'
import { SourceCenterPage } from '../pages/integrations/SourceCenterPage.tsx'
import { WorkflowPage } from '../pages/workflows/WorkflowPage.tsx'
import { ModelCatalogPage } from '../pages/modeling/ModelCatalogPage.tsx'
import { MetricDefinitionsPage } from '../pages/modeling/MetricDefinitionsPage.tsx'
import { DiagnosePage } from '../pages/incidents/DiagnosePage.tsx'
import { CurrentDiagnosisPage } from '../pages/incidents/CurrentDiagnosisPage.tsx'
import { ReorganizationPage } from '../pages/incidents/ReorganizationPage.tsx'
import { IncidentsPage } from '../pages/incidents/IncidentsPage.tsx'
import { InventoryPage } from '../pages/inventory/InventoryPage.tsx'
import { MetricsPage } from '../pages/metrics/MetricsPage.tsx'
import { PipelinesPage } from '../pages/pipelines/PipelinesPage.tsx'
import { RetentionPage } from '../pages/retention/RetentionPage.tsx'
import { SourceSnapshotsPage } from '../pages/inventory/SourceSnapshotsPage.tsx'
import { SourceBindingCorrectionsPage } from '../pages/inventory/SourceBindingCorrectionsPage.tsx'
import { SourceScanRunsPage } from '../pages/integrations/SourceScanRunsPage.tsx'
import { RelationInstancesPage } from '../pages/modeling/RelationInstancesPage.tsx'

function EntityModels() { return <ModelCatalogPage mode="ENTITY" /> }
function RelationModels() { return <ModelCatalogPage mode="RELATION" /> }
const pages: Record<RouteName, ComponentType> = {
  start: StartPage, topology: TopologyPage, reorganize: ReorganizationPage,
  'model-entities': EntityModels, 'model-metrics': MetricDefinitionsPage, 'model-relations': RelationModels,
  'model-relation-instances': RelationInstancesPage,
  retention: RetentionPage, 'source-snapshots': SourceSnapshotsPage, 'source-corrections': SourceBindingCorrectionsPage,
  'source-scan-runs': SourceScanRunsPage, 'current-diagnose': CurrentDiagnosisPage,
  incidents: IncidentsPage, inventory: InventoryPage, metrics: MetricsPage, pipelines: PipelinesPage,
  'source-center': SourceCenterPage, 'workflow-runs': WorkflowRunsPage, workflows: WorkflowPage, diagnose: DiagnosePage,
}
export function RoutePage(props: { route: RouteName }) {
  const Page = pages[props.route]
  return <Page />
}
