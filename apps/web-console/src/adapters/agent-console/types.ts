/** Display models for a future Zeus UI agent-console adapter. Not a platform DTO. */
export type AgentConsoleView = {
  title: string
  status: 'idle' | 'running' | 'succeeded' | 'failed' | 'cancelled'
  messages: AgentConsoleMessage[]
  limitations: string[]
}

export type AgentConsoleMessage = {
  id: string
  role: 'system' | 'user' | 'assistant' | 'tool'
  text: string
  evidenceIds: string[]
}

/**
 * Maps OpsWeave diagnose results into a console view.
 * Zeus UI must not receive tenant, token, model keys, or MCP endpoints.
 */
export function toAgentConsoleView(input: {
  runId: string
  summary: string
  findings: Array<{ kind: string; statement: string; evidenceRefs: string[] }>
  limitations: string[]
}): AgentConsoleView {
  return {
    title: input.summary,
    status: 'succeeded',
    limitations: input.limitations,
    messages: [
      {
        id: input.runId,
        role: 'assistant',
        text: input.summary,
        evidenceIds: input.findings.flatMap(item => item.evidenceRefs),
      },
    ],
  }
}
