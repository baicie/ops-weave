import { platformClient } from './http.ts'
// Handwritten adapter for canonical incident-reorganization*.schema.json.
export type ProblemKey = { sourceInstanceId: string; problemEventId: string }
export type ReorganizationRequest = { requestKey: string; kind: 'MERGE' | 'SPLIT'; sourceIncidentId: string; expectedSourceVersion: number;
  targetIncidentId: string; expectedTargetVersion: number; problemKeys: ProblemKey[]; title: string | null; reason: string }
export type Reorganization = { schemaVersion: '1.0'; request: ReorganizationRequest; actor: string; sourceVersion: number; targetVersion: number; movedProblems: ProblemKey[]; occurredAt: string }
export type ReorganizationResult = { storage: 'memory' | 'postgres'; change: Reorganization }
export type ReorganizationPage = { storage: 'memory' | 'postgres'; incidentId: string; after: string | null; limit: number; items: Reorganization[]; nextCursor: string | null }
export class ReorganizationError extends Error {
  constructor(readonly status: number) { super(({ 401: '身份已失效，请重新输入 Token', 403: '无权管理涉及的 Incident 或资产', 404: '未找到当前身份可见的关联记录',
    409: 'Incident 版本、归属或请求内容冲突，请重新读取后预览', 503: '服务不可用，提交结果待确认' } as Record<number, string>)[status] ?? `关联调整请求失败（HTTP ${status}）`) }
}
const obj = (v: unknown): v is Record<string, unknown> => v !== null && typeof v === 'object' && !Array.isArray(v)
export const uuid = (v: unknown): v is string => typeof v === 'string' && /^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(v)
const text = (v: unknown, max: number): v is string => typeof v === 'string' && v.trim().length > 0 && v.length <= max
const integer = (v: unknown, min: number): v is number => typeof v === 'number' && Number.isSafeInteger(v) && v >= min
function check(ok: unknown): asserts ok { if (!ok) throw new Error('关联调整响应结构或范围不正确') }
function exact(v: unknown, keys: string[]): asserts v is Record<string, unknown> { check(obj(v) && Object.keys(v).length === keys.length && keys.every(k => k in v)) }
export function keyOf(v: ProblemKey) { return `${v.sourceInstanceId}:${v.problemEventId}` }
function keys(v: unknown, min: number, max: number): ProblemKey[] {
  check(Array.isArray(v) && v.length >= min && v.length <= max)
  for (const k of v) { exact(k, ['sourceInstanceId', 'problemEventId']); check(typeof k.sourceInstanceId === 'string' && /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/.test(k.sourceInstanceId)
    && typeof k.problemEventId === 'string' && /^[1-9][0-9]{0,19}$/.test(k.problemEventId) && BigInt(k.problemEventId) <= 18446744073709551615n) }
  const result = v as ProblemKey[]; check(new Set(result.map(keyOf)).size === v.length); return result
}
function requestShape(v: unknown): ReorganizationRequest {
  exact(v, ['requestKey', 'kind', 'sourceIncidentId', 'expectedSourceVersion', 'targetIncidentId', 'expectedTargetVersion', 'problemKeys', 'title', 'reason'])
  check(uuid(v.requestKey) && uuid(v.sourceIncidentId) && uuid(v.targetIncidentId) && v.sourceIncidentId !== v.targetIncidentId
    && integer(v.expectedSourceVersion, 1) && v.expectedSourceVersion < Number.MAX_SAFE_INTEGER && integer(v.expectedTargetVersion, 0)
    && v.expectedTargetVersion < Number.MAX_SAFE_INTEGER && text(v.reason, 500))
  const selected = keys(v.problemKeys, 0, 49)
  check(v.kind === 'MERGE' ? v.expectedTargetVersion > 0 && v.title === null && selected.length === 0
    : v.kind === 'SPLIT' && v.expectedTargetVersion === 0 && text(v.title, 300) && selected.length > 0)
  return v as unknown as ReorganizationRequest
}
function sameKeys(a: ProblemKey[], b: ProblemKey[]) { return a.map(keyOf).sort().join('\n') === b.map(keyOf).sort().join('\n') }
function sameRequest(a: ReorganizationRequest, b: ReorganizationRequest) {
  return a.requestKey === b.requestKey && a.kind === b.kind && a.sourceIncidentId === b.sourceIncidentId && a.targetIncidentId === b.targetIncidentId
    && a.expectedSourceVersion === b.expectedSourceVersion && a.expectedTargetVersion === b.expectedTargetVersion && a.title === b.title && a.reason === b.reason && sameKeys(a.problemKeys, b.problemKeys)
}
function change(v: unknown): Reorganization {
  exact(v, ['schemaVersion', 'request', 'actor', 'sourceVersion', 'targetVersion', 'movedProblems', 'occurredAt']); const r = requestShape(v.request)
  check(v.schemaVersion === '1.0' && text(v.actor, 128) && v.sourceVersion === r.expectedSourceVersion + 1 && v.targetVersion === r.expectedTargetVersion + 1
    && typeof v.occurredAt === 'string' && /^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,9})?Z$/.test(v.occurredAt) && Number.isFinite(Date.parse(v.occurredAt)))
  const moved = keys(v.movedProblems, 1, 50); if (r.kind === 'SPLIT') check(sameKeys(moved, r.problemKeys))
  return v as unknown as Reorganization
}
async function call(path: string, signal: AbortSignal, body?: ReorganizationRequest): Promise<unknown> {
  return platformClient.request(path, { signal, body, timeoutMs: 20000, error: status => new ReorganizationError(status) })
}

function result(v: unknown, key: string, expected?: ReorganizationRequest): ReorganizationResult {
  exact(v, ['storage', 'change']); check(v.storage === 'memory' || v.storage === 'postgres'); const c = change(v.change)
  check(c.request.requestKey === key && (!expected || sameRequest(c.request, expected))); return { storage: v.storage, change: c }
}
export async function reorganize(body: ReorganizationRequest, signal: AbortSignal) { requestShape(body); return result(await call('/api/v1/incidents/reorganizations', signal, body), body.requestKey, body) }
export async function getReorganization(key: string, signal: AbortSignal) { check(uuid(key)); return result(await call(`/api/v1/incidents/reorganizations/${key}`, signal), key) }
export async function listReorganizations(id: string, after: string | null, signal: AbortSignal): Promise<ReorganizationPage> {
  check(uuid(id) && (after === null || uuid(after))); const params = new URLSearchParams({ limit: '20' }); if (after) params.set('after', after)
  const v = await call(`/api/v1/incidents/${id}/reorganizations?${params}`, signal)
  exact(v, ['storage', 'incidentId', 'after', 'limit', 'items', 'nextCursor']); check((v.storage === 'memory' || v.storage === 'postgres') && v.incidentId === id && v.after === after && v.limit === 20 && Array.isArray(v.items) && v.items.length <= 20)
  const items = v.items.map(change); let previous = after ?? ''
  for (const item of items) { check((item.request.sourceIncidentId === id || item.request.targetIncidentId === id) && item.request.requestKey > previous); previous = item.request.requestKey }
  check(v.nextCursor === null || (items.length === 20 && v.nextCursor === items.at(-1)?.request.requestKey))
  return { storage: v.storage, incidentId: id, after, limit: 20, items, nextCursor: v.nextCursor as string | null }
}
