import { platformClient } from './http.ts'
import { isUuid, InsightRequestError, type Insight } from './insights.ts'

// Explicit handwritten boundary for contracts/schemas/v1/model-spend-*. No floating-point billing arithmetic.
type Usage = { inputTokens: number; outputTokens: number; cachedInputTokens: number; source: 'mock-no-call' | 'provider-reported' }
export type ModelSpend = { storage: 'memory' | 'postgres'; record: {
  schemaVersion: '1.0'; tenantId: string; subjectId: string; runId: string; sessionId: string; incidentId: string;
  inputDigest: string; inputBytes: number; reservedAt: string; deadlineAt: string; reportedAt: string | null;
  state: 'RESERVED' | 'UNCERTAIN' | 'REPORTED'; usage: Usage | null; reservedMicros: number; estimatedMicros: number | null; accountedMicros: number;
  currency: 'USD'; maxInputTokens: 81920; maxOutputTokens: 2048; maxInputBytes: 73728; accounting: 'configured-price-estimate';
  policy: { provider: 'mock-deterministic' | 'rig-openai'; model: string; priceVersion: string; inputMicrosPerMillion: number; outputMicrosPerMillion: number; maxCallMicros: number; dailyMicros: number }
} }
function check(value: unknown): asserts value { if (!value) throw new Error('用量响应结构、费用或范围不正确') }
function exact(v: unknown, keys: string[]): asserts v is Record<string, unknown> { check(v !== null && typeof v === 'object' && !Array.isArray(v) && Object.keys(v).length === keys.length && keys.every(k => k in v)) }
const integer = (v: unknown, max: number, min = 0): v is number => Number.isSafeInteger(v) && Number(v) >= min && Number(v) <= max
const uuid = (v: unknown): v is string => isUuid(v) && v.length === 36
const str = (v: unknown, max = 128): v is string => typeof v === 'string' && [...v].length > 0 && [...v].length <= max
const date = (v: unknown): v is string => typeof v === 'string' && /^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,9})?Z(?![\s\S])/.test(v) && Number.isFinite(Date.parse(v))
export function parseModelSpend(v: unknown, runId: string, insight?: Insight): ModelSpend {
  exact(v, ['storage', 'record']); check(['memory', 'postgres'].includes(String(v.storage)))
  const r = v.record; exact(r, ['schemaVersion', 'tenantId', 'subjectId', 'runId', 'sessionId', 'incidentId', 'inputDigest', 'inputBytes', 'policy', 'reservedAt', 'deadlineAt', 'reportedAt', 'usage', 'state', 'currency', 'reservedMicros', 'estimatedMicros', 'accountedMicros', 'maxInputTokens', 'maxOutputTokens', 'maxInputBytes', 'accounting'])
  check(r.schemaVersion === '1.0' && r.runId === runId && uuid(r.runId) && uuid(r.sessionId) && uuid(r.incidentId) && str(r.tenantId) && str(r.subjectId)
    && typeof r.inputDigest === 'string' && /^sha256:[0-9a-f]{64}(?![\s\S])/.test(r.inputDigest) && integer(r.inputBytes, 73728, 1) && r.maxInputBytes === 73728
    && r.maxInputTokens === 81920 && r.maxOutputTokens === 2048 && r.currency === 'USD' && r.accounting === 'configured-price-estimate')
  check(date(r.reservedAt) && date(r.deadlineAt) && Date.parse(r.reservedAt) >= 0 && Date.parse(r.reservedAt) <= Date.now() && Date.parse(r.reservedAt) < Date.parse(r.deadlineAt) && Date.parse(r.deadlineAt) - Date.parse(r.reservedAt) <= 60000)
  const p = r.policy; exact(p, ['provider', 'model', 'priceVersion', 'inputMicrosPerMillion', 'outputMicrosPerMillion', 'maxCallMicros', 'dailyMicros'])
  check(['mock-deterministic', 'rig-openai'].includes(String(p.provider)) && str(p.model) && typeof p.priceVersion === 'string' && /^[a-zA-Z0-9._-]{1,64}(?![\s\S])/.test(p.priceVersion))
  check(integer(p.inputMicrosPerMillion, 1e12) && integer(p.outputMicrosPerMillion, 1e12) && integer(p.maxCallMicros, 1e12) && integer(p.dailyMicros, 1e12) && p.dailyMicros >= p.maxCallMicros)
  if (p.provider === 'mock-deterministic') check(p.model === 'mock-current-v1' && p.priceVersion === 'mock-no-charge' && p.inputMicrosPerMillion === 0 && p.outputMicrosPerMillion === 0 && p.maxCallMicros === 0 && p.dailyMicros === 0)
  else check(v.storage === 'postgres' && p.inputMicrosPerMillion > 0 && p.outputMicrosPerMillion > 0 && p.maxCallMicros > 0)
  const estimate = (input: number, output: number) => Number((BigInt(input) * BigInt(p.inputMicrosPerMillion as number) + BigInt(output) * BigInt(p.outputMicrosPerMillion as number) + 999999n) / 1000000n)
  check(integer(r.reservedMicros, 1e12) && r.reservedMicros === estimate(81920, 2048) && r.reservedMicros <= p.maxCallMicros && integer(r.accountedMicros, 1e12))
  if (r.state === 'REPORTED') {
    check(date(r.reportedAt) && Date.parse(r.reportedAt) >= Date.parse(r.reservedAt) && Date.parse(r.reportedAt) <= Date.now())
    const u = r.usage; exact(u, ['inputTokens', 'outputTokens', 'cachedInputTokens', 'source'])
    check(integer(u.inputTokens, 81920) && integer(u.outputTokens, 2048) && integer(u.cachedInputTokens, u.inputTokens))
    if (p.provider === 'mock-deterministic') check(u.source === 'mock-no-call' && u.inputTokens === 0 && u.outputTokens === 0 && u.cachedInputTokens === 0)
    else check(u.source === 'provider-reported' && u.inputTokens > 0)
    check(r.estimatedMicros === estimate(u.inputTokens, u.outputTokens) && r.accountedMicros === r.estimatedMicros)
  } else check(['RESERVED', 'UNCERTAIN'].includes(String(r.state)) && r.usage === null && r.reportedAt === null && r.estimatedMicros === null && r.accountedMicros === r.reservedMicros && (r.state !== 'UNCERTAIN' || Date.parse(r.deadlineAt) <= Date.now()))
  if (insight) check(r.runId === insight.id && r.sessionId === insight.sessionId && r.tenantId === insight.tenantId && r.subjectId === insight.subjectId && r.incidentId === insight.incidentId && p.provider === insight.model.provider && p.model === insight.model.name && r.state === 'REPORTED')
  return v as unknown as ModelSpend
}
export async function getModelSpend(id: string, signal: AbortSignal, insight?: Insight) {
  check(uuid(id)); const value = await platformClient.request(`/api/v1/ai/model-calls/${id}`, { signal, responseBytes: 16384, timeoutMs: 18000,
    error: status => status === 404 ? new Error('尚未找到用量记录；无法据此确认费用为零，也不会自动重试模型。') : new InsightRequestError(status) })
  return parseModelSpend(value, id, insight)
}
export const dollars = (micros: number) => `USD ${(micros / 1000000).toFixed(6)}`
