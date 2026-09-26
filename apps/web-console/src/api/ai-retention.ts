import { platformClient } from './http.ts'
import { isUuid } from './insights.ts'

export type RetentionPolicy = { tenantId: string; version: string; insightDays: number; evidenceDays: number; auditDays: number; batchSize: number; heldIncidents: string[]; allowPurge: boolean }
export type RetentionBatch = { kind: 'INSIGHT' | 'EVIDENCE' | 'AUDIT'; ids: string[]; logicalBytes: number; hasMore: boolean }
export type RetentionPreview = { tenantId: string; actor: string; policyDigest: string; asOf: string; expiresAt: string; previewDigest: string; batches: RetentionBatch[] }
export type RetentionView = { schemaVersion: '1.0'; storage: 'postgres'; policy: RetentionPolicy; preview: RetentionPreview }
export type RetentionCommand = { requestId: string; policyDigest: string; asOf: string; previewDigest: string }
export type RetentionReceipt = { schemaVersion: '1.0'; storage: 'postgres'; state: 'COMPLETED'; tenantId: string; actor: string; command: RetentionCommand; preview: RetentionPreview; completedAt: string }
export class RetentionError extends Error {
  constructor(readonly status: number) { super(({ 400: '留存请求不正确', 401: '请重新登录', 403: '需要租户全范围的留存管理权限，且策略允许清理', 404: '尚未找到回执，提交结果仍待确认', 409: '策略或待清理内容已变化，请重新预览', 410: '预览已过期，请重新预览', 429: '处理繁忙，请稍后手动查询', 503: '留存功能未配置或不可用；需要 PostgreSQL 与可信策略文件' } as Record<number, string>)[status] ?? '提交结果待确认，请按原请求标识查询回执') }
}
function check(v: unknown): asserts v { if (!v) throw new Error('留存响应结构或摘要不正确') }
function exact(v: unknown, keys: string[]): asserts v is Record<string, unknown> { check(v !== null && typeof v === 'object' && !Array.isArray(v) && Object.keys(v).length === keys.length && keys.every(k => k in v)) }
const int = (v: unknown, max: number, min = 0): v is number => Number.isSafeInteger(v) && Number(v) >= min && Number(v) <= max
const text = (v: unknown): v is string => typeof v === 'string' && [...v].length >= 1 && [...v].length <= 128
const digest = (v: unknown): v is string => typeof v === 'string' && /^sha256:[0-9a-f]{64}(?![\s\S])/.test(v)
const ids = (v: unknown, max: number): v is string[] => Array.isArray(v) && v.length <= max && new Set(v).size === v.length && v.every(id => isUuid(id) && id.length === 36 && id === id.toLowerCase())
const second = (v: unknown): v is string => typeof v === 'string' && /^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ(?![\s\S])/.test(v) && Date.parse(v) >= 0 && new Date(v).toISOString().replace('.000Z', 'Z') === v
const sha = async (s: string) => 'sha256:' + [...new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(s)))].map(n => n.toString(16).padStart(2, '0')).join('')
async function preview(v: unknown): Promise<RetentionPreview> {
  exact(v, ['tenantId', 'actor', 'policyDigest', 'asOf', 'expiresAt', 'previewDigest', 'batches'])
  check(text(v.tenantId) && text(v.actor) && digest(v.policyDigest) && digest(v.previewDigest) && second(v.asOf) && second(v.expiresAt) && Date.parse(v.expiresAt) - Date.parse(v.asOf) === 120000 && Date.parse(v.asOf) <= Date.now() && Array.isArray(v.batches) && v.batches.length === 3)
  let canonical = `ai-retention-preview-v1\n${v.tenantId}\n${v.actor}\n${v.policyDigest}\n${v.asOf}\n`
  for (const [i, b] of v.batches.entries()) { exact(b, ['kind', 'ids', 'logicalBytes', 'hasMore']); check(b.kind === ['INSIGHT', 'EVIDENCE', 'AUDIT'][i] && ids(b.ids, 100) && int(b.logicalBytes, 14000000) && typeof b.hasMore === 'boolean'); canonical += `${b.kind}:${b.logicalBytes}:${b.hasMore}\n${b.ids.map(id => id + '\n').join('')}` }
  check(v.previewDigest === await sha(canonical)); return v as unknown as RetentionPreview
}
export async function parseRetentionView(v: unknown): Promise<RetentionView> {
  exact(v, ['schemaVersion', 'storage', 'policy', 'preview']); check(v.schemaVersion === '1.0' && v.storage === 'postgres'); const p = v.policy
  exact(p, ['tenantId', 'version', 'insightDays', 'evidenceDays', 'auditDays', 'batchSize', 'heldIncidents', 'allowPurge'])
  check(text(p.tenantId) && typeof p.version === 'string' && /^[A-Za-z0-9._-]{1,64}(?![\s\S])/.test(p.version) && int(p.insightDays, 3650, 1) && int(p.evidenceDays, 3650, 1) && int(p.auditDays, 3650, 1) && int(p.batchSize, 100, 1) && ids(p.heldIncidents, 1000) && typeof p.allowPurge === 'boolean')
  const r = await preview(v.preview); const d = await sha(['ai-retention-policy-v1', p.tenantId, p.version, p.insightDays, p.evidenceDays, p.auditDays, p.batchSize, p.allowPurge, [...p.heldIncidents].sort().map(id => id + '\n').join('')].join('\n'))
  check(r.tenantId === p.tenantId && r.policyDigest === d && r.batches.every(b => b.ids.length <= Number(p.batchSize)) && Date.now() < Date.parse(r.expiresAt)); return v as unknown as RetentionView
}
export async function parseRetentionReceipt(v: unknown, id: string, expected?: RetentionCommand): Promise<RetentionReceipt> {
  exact(v, ['schemaVersion', 'storage', 'state', 'tenantId', 'actor', 'command', 'preview', 'completedAt']); check(v.schemaVersion === '1.0' && v.storage === 'postgres' && v.state === 'COMPLETED'); const c = v.command; exact(c, ['requestId', 'policyDigest', 'asOf', 'previewDigest'])
  check(isUuid(c.requestId) && c.requestId === id && digest(c.policyDigest) && digest(c.previewDigest) && second(c.asOf)); const p = await preview(v.preview)
  check(v.tenantId === p.tenantId && v.actor === p.actor && c.policyDigest === p.policyDigest && c.asOf === p.asOf && c.previewDigest === p.previewDigest && typeof v.completedAt === 'string' && /^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,9})?Z(?![\s\S])/.test(v.completedAt) && Date.parse(v.completedAt) >= Date.parse(p.asOf) && Date.parse(v.completedAt) <= Date.now())
  if (expected) check(c.requestId === expected.requestId && c.policyDigest === expected.policyDigest && c.asOf === expected.asOf && c.previewDigest === expected.previewDigest)
  return v as unknown as RetentionReceipt
}
const options = (signal: AbortSignal) => ({ signal, responseBytes: 65536, timeoutMs: 18000, error: (status: number) => new RetentionError(status) })
export async function getRetentionPreview(signal: AbortSignal) { return parseRetentionView(await platformClient.request('/api/v1/ai/retention', options(signal))) }
export async function applyRetention(body: RetentionCommand, signal: AbortSignal) { return parseRetentionReceipt(await platformClient.request('/api/v1/ai/retention/runs', { ...options(signal), body }), body.requestId, body) }
export async function getRetentionReceipt(id: string, signal: AbortSignal) { check(isUuid(id) && id.length === 36); return parseRetentionReceipt(await platformClient.request(`/api/v1/ai/retention/runs/${id}`, options(signal)), id) }
