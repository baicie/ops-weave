import {WorkflowReplayPanel,type WorkflowReplayPanelProps} from './WorkflowReplayPanel.tsx'
import type {ReplayPlan} from '../../api/workflow-metric-replay.ts'
export function WorkflowMetricReplayPanel(p:Omit<WorkflowReplayPanelProps,'plans'|'plan'>&{plans:ReplayPlan[];plan:ReplayPlan|null}){return <WorkflowReplayPanel {...p} kind="metric"/>}
