import { InvestigationWorkspace } from '../../components/investigation/InvestigationWorkspace.tsx'
import { useInvestigation } from './useInvestigation.ts'

export function CurrentDiagnosisPage() {
  return <InvestigationWorkspace {...useInvestigation()} />
}
