import { platformClient } from './http.ts'
// Handwritten boundary for canonical current-diagnose-request, ai-insight-result and platform-evidence schemas.
export type DiagnosisRequest = { runId: string; incidentId: string; question: string; timeRange: { from: string; to: string }; knowledgeMode: 'current' }
type Finding = { kind: 'observation' | 'hypothesis'; statement: string; evidenceRefs: string[] }
export type Insight = { schemaVersion: '1.0'; knowledgeMode: 'current'; id: string; tenantId: string; subjectId: string; sessionId: string;
  incidentId: string; incidentVersion: number; entityIds: string[]; queryWindow: { from: string; to: string }; question: string;
  asOf: string; builtAt: string; completedAt: string; savedAt: string; expiresAt: string; skill: { id: string; version: string; digest: string };
  model: { provider: 'mock-deterministic' | 'rig-openai'; name: string }; evidenceIds: string[]; dataModes: string[]; warnings: string[];
  insight: { summary: string; findings: Finding[]; missingData: string[]; limitations: string[] }; verification: 'reference_integrity_only' }
export type InsightResult = { storage: 'memory' | 'postgres'; record: Insight }
export type EvidenceDocument = { schemaVersion: '1.0'; knowledgeMode: 'current'; sessionId: string; incidentVersion: number; entityIds: string[];
  queryWindow: { from: string; to: string }; dataModes: string[]; warnings: string[]; policyVersion: string; producerTool: string;
  evidence: { id: string; tenantId: string; incidentId: string; kind: 'incident' | 'metric'; summary: string; availableAt: string; observedAt: string; expiresAt: string; sourceRef: string }; data: Record<string, unknown> }
export class InsightRequestError extends Error {
  constructor(readonly status: number) { super(({ 401: '身份已失效，请重新输入开发 Token', 403: '当前身份无权执行或读取此诊断',
    404: '尚未找到已保存结果', 409: '请求内容或 Incident 已变化，请重新读取后开始新诊断', 410: '结果或证据已过期，请开始新诊断',
    429: '诊断繁忙或预算已用尽', 503: '诊断服务不可用，请检查本地服务配置', 504: '诊断超时，保存状态待确认' } as Record<number, string>)[status] ?? `诊断请求失败（HTTP ${status}）`) }
}
const obj = (v: unknown): v is Record<string, unknown> => v !== null && typeof v === 'object' && !Array.isArray(v)
export const isUuid = (v: unknown): v is string => typeof v === 'string' && /^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(v)
const str = (v: unknown, max: number): v is string => typeof v === 'string' && [...v].length > 0 && [...v].length <= max
const time = (v: unknown): v is string => typeof v === 'string' && /^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,9})?Z$/.test(v) && Number.isFinite(Date.parse(v))
function check(ok: unknown): asserts ok { if (!ok) throw new Error('诊断响应结构、引用或范围不正确') }
function exact(v: unknown, keys: string[]): asserts v is Record<string, unknown> { check(obj(v) && Object.keys(v).length === keys.length && keys.every(k => k in v)) }
function strings(v: unknown, count: number, length: number): asserts v is string[] { check(Array.isArray(v) && v.length <= count && v.every(s => str(s, length))) }
function ids(v: unknown, min: number, max: number): asserts v is string[] { check(Array.isArray(v) && v.length >= min && v.length <= max && v.every(isUuid) && new Set(v).size === v.length) }
function windowShape(v: unknown): asserts v is { from: string; to: string } {
  exact(v, ['from', 'to']); check(time(v.from) && time(v.to)); const a = Date.parse(v.from), b = Date.parse(v.to)
  check(a >= 0 && a < b && b - a <= 3600000 && a % 1000 === 0 && b % 1000 === 0)
}
function sameWindow(a: { from: string; to: string }, b: { from: string; to: string }) { return Date.parse(a.from) === Date.parse(b.from) && Date.parse(a.to) === Date.parse(b.to) }
export function validateDiagnosis(v: DiagnosisRequest) { check(isUuid(v.runId) && isUuid(v.incidentId) && str(v.question.trim(), 2000) && v.knowledgeMode === 'current'); windowShape(v.timeRange); check(Date.parse(v.timeRange.to) <= Date.now()) }
export function parseInsight(v: unknown, runId: string, request?: DiagnosisRequest): InsightResult {
  exact(v, ['storage', 'record']); check(v.storage === 'memory' || v.storage === 'postgres')
  const r = v.record; exact(r, ['schemaVersion', 'knowledgeMode', 'id', 'tenantId', 'subjectId', 'sessionId', 'incidentId', 'incidentVersion', 'entityIds', 'queryWindow',
    'question', 'asOf', 'builtAt', 'completedAt', 'savedAt', 'expiresAt', 'skill', 'model', 'evidenceIds', 'dataModes', 'warnings', 'insight', 'verification'])
  check(r.schemaVersion === '1.0' && r.knowledgeMode === 'current' && r.id === runId && isUuid(r.id) && isUuid(r.sessionId) && isUuid(r.incidentId)
    && str(r.tenantId, 128) && str(r.subjectId, 128) && Number.isSafeInteger(r.incidentVersion) && Number(r.incidentVersion) >= 1 && str(r.question, 2000)
    && r.verification === 'reference_integrity_only')
  ids(r.entityIds, 0, 5); ids(r.evidenceIds, 2, 2); windowShape(r.queryWindow)
  check(time(r.asOf) && time(r.builtAt) && time(r.completedAt) && time(r.savedAt) && time(r.expiresAt))
  check(Date.parse(r.asOf) <= Date.parse(r.builtAt) && Date.parse(r.builtAt) <= Date.parse(r.completedAt)
    && Date.parse(r.completedAt) <= Date.parse(r.savedAt) && Date.parse(r.savedAt) < Date.parse(r.expiresAt))
  if (Date.parse(r.expiresAt) <= Date.now()) throw new InsightRequestError(410)
  exact(r.skill, ['id', 'version', 'digest']); check(r.skill.id === 'incident.diagnose' && r.skill.version === '2.0.0' && typeof r.skill.digest === 'string' && /^sha256:[0-9a-f]{64}$/.test(r.skill.digest))
  exact(r.model, ['provider', 'name']); check(['mock-deterministic', 'rig-openai'].includes(String(r.model.provider)) && str(r.model.name, 128))
  strings(r.dataModes, 3, 32); check(r.dataModes.length > 0 && r.dataModes.every(m => ['labeled-fixture', 'zabbix-jsonrpc', 'unknown'].includes(m)))
  strings(r.warnings, 32, 64); check(r.warnings.includes('CURRENT_KNOWLEDGE_ONLY'))
  exact(r.insight, ['summary', 'findings', 'missingData', 'limitations']); check(str(r.insight.summary, 4000) && Array.isArray(r.insight.findings) && r.insight.findings.length <= 12)
  const evidenceIds = r.evidenceIds
  for (const finding of r.insight.findings) {
    exact(finding, ['kind', 'statement', 'evidenceRefs']); check(['observation', 'hypothesis'].includes(String(finding.kind)) && str(finding.statement, 2000))
    ids(finding.evidenceRefs, 1, 16); check(finding.evidenceRefs.every(id => evidenceIds.includes(id)))
  }
  strings(r.insight.missingData, 32, 500); strings(r.insight.limitations, 16, 1000)
  if (request) check(r.incidentId === request.incidentId && r.question === request.question && sameWindow(r.queryWindow, request.timeRange))
  return v as unknown as InsightResult
}
function parseEvidence(v: unknown, id: string, r: Insight): EvidenceDocument {
  exact(v, ['schemaVersion', 'knowledgeMode', 'evidence', 'sessionId', 'incidentVersion', 'entityIds', 'queryWindow', 'dataModes', 'warnings', 'data', 'policyVersion', 'producerTool'])
  check(v.schemaVersion === '1.0' && v.knowledgeMode === 'current' && v.sessionId === r.sessionId && v.incidentVersion === r.incidentVersion
    && v.policyVersion === 'readonly-diagnosis-v1' && obj(v.evidence) && obj(v.data))
  ids(v.entityIds, 0, 5); check(v.entityIds.length === r.entityIds.length && v.entityIds.every(id => r.entityIds.includes(id)))
  windowShape(v.queryWindow); check(sameWindow(v.queryWindow, r.queryWindow)); strings(v.dataModes, 3, 32); strings(v.warnings, 32, 64)
  const e = v.evidence; check(e.id === id && e.tenantId === r.tenantId && e.incidentId === r.incidentId && str(e.summary, 4000)
    && e.sourceRef === `/api/v1/ai/evidence/${id}` && time(e.availableAt) && time(e.observedAt) && time(e.expiresAt))
  check(Date.parse(e.availableAt) <= Date.parse(r.asOf) && Date.parse(e.observedAt) <= Date.parse(r.asOf))
  if (Date.parse(e.expiresAt) <= Date.now()) throw new InsightRequestError(410)
  check((e.kind === 'incident' && v.producerTool === 'incident.get@2.0.0') || (e.kind === 'metric' && v.producerTool === 'metric.summary@2.0.0'))
  return v as unknown as EvidenceDocument
}
async function request(path: string, signal: AbortSignal, body?: DiagnosisRequest): Promise<unknown> {
  return platformClient.request(path, { signal, body, responseBytes: 131072, timeoutMs: body ? 80000 : 18000, error: status => new InsightRequestError(status) })
}

export async function diagnose(body: DiagnosisRequest, signal: AbortSignal) { validateDiagnosis(body); return parseInsight(await request('/api/v1/ai/diagnoses', signal, body), body.runId, body) }
export async function getInsight(id: string, signal: AbortSignal) { check(isUuid(id)); return parseInsight(await request(`/api/v1/ai/insights/${id}`, signal), id) }
export async function getEvidence(id: string, result: Insight, signal: AbortSignal) { check(isUuid(id) && result.evidenceIds.includes(id)); return parseEvidence(await request(`/api/v1/ai/evidence/${id}`, signal), id, result) }
