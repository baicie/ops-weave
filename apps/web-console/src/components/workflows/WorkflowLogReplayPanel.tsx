import {WorkflowReplayPanel,type WorkflowReplayPanelProps} from './WorkflowReplayPanel.tsx'
import type {ReplayPlan} from '../../api/workflow-log-replay.ts'
export function WorkflowLogReplayPanel(p:Omit<WorkflowReplayPanelProps,'plans'|'plan'>&{plans:ReplayPlan[];plan:ReplayPlan|null}){return <WorkflowReplayPanel {...p} kind="log"/>}
