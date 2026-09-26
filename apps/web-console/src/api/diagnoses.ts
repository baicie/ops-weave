import { CredentialSession } from './credential-session.ts'
import { JsonClient } from './http.ts'

export type Finding = {
  kind: 'observation' | 'hypothesis'
  statement: string
  evidenceRefs: string[]
}

export type Evidence = {
  id: string
  kind: string
  summary: string
  sourceRef: string
}

export type DiagnoseResult = {
  runId: string
  dataMode: string
  modelProvider: string
  verification: string
  context: { evidence: Evidence[] }
  insight: {
    summary: string
    findings: Finding[]
    missingData: string[]
    limitations: string[]
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}

function isStringArray(value: unknown): value is string[] {
  return Array.isArray(value) && value.every(item => typeof item === 'string')
}

export function parseDiagnoseResult(value: unknown): DiagnoseResult {
  if (!isRecord(value) || !isRecord(value.insight) || !isRecord(value.context)) {
    throw new Error('接口响应结构不正确')
  }
  const insight = value.insight
  if (!['runId', 'dataMode', 'modelProvider', 'verification'].every(key => typeof value[key] === 'string')) {
    throw new Error('接口响应结构不正确')
  }
  if (
    typeof insight.summary !== 'string'
    || !isStringArray(insight.missingData)
    || !isStringArray(insight.limitations)
    || !Array.isArray(insight.findings)
    || !insight.findings.every(item =>
      isRecord(item)
      && (item.kind === 'observation' || item.kind === 'hypothesis')
      && typeof item.statement === 'string'
      && isStringArray(item.evidenceRefs)
    )
    || !Array.isArray(value.context.evidence)
    || !value.context.evidence.every(item =>
      isRecord(item) && ['id', 'kind', 'summary', 'sourceRef'].every(key => typeof item[key] === 'string')
    )
  ) {
    throw new Error('接口响应结构不正确')
  }
  return value as DiagnoseResult
}

export async function createDiagnosis(
  input: { token: string; question: string; signal: AbortSignal },
): Promise<DiagnoseResult> {
  const to = new Date(Date.now() - 1000)
  const from = new Date(to.getTime() - 30 * 60 * 1000)
  const credentials = new CredentialSession(); credentials.replace(input.token)
  try {
    const value = await new JsonClient(credentials, 'fixture-demo').request('/agent/api/v1/diagnoses', {
      body: {
      incidentId: 'inc-demo',
      question: input.question,
      timeRange: { from: from.toISOString(), to: to.toISOString() },
      asOf: to.toISOString(),
      },
    signal: input.signal,
    error: status => new Error(`诊断失败（HTTP ${status}），检查 Demo 模式、Token 和终端日志。`),
  })
    return parseDiagnoseResult(value)
  } finally { credentials.clear() }
}
