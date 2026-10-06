import { platformClient } from './http.ts'

export type RegisteredProblemScope = {
  sourceId: string
  sourceInstanceId: string
  revision: number
  connectionDigest: string
  hostGroupIds: string[]
}

export type RegisteredProblemQuery = {
  from: number
  till: number
  afterEventId: string | null
  limit: number
}

export type RegisteredProblem = {
  schemaVersion: '2.0'
  tenantId: string
  sourceInstanceId: string
  problemEventId: string
  triggerId: string
  title: string
  severity: number
  occurredAt: string
  observedAt: string
  hostIds: string[]
  suppressed: boolean
  recoveryEventId: string | null
  recoveredAt: string | null
  state: 'ACTIVE' | 'RECOVERED' | 'RECOVERY_UNKNOWN'
  gaps: string[]
}

export type RegisteredProblemPage = {
  storage: 'postgres' | 'memory'
  dataMode: 'zabbix-jsonrpc'
  sourceId: string
  sourceInstanceId: string
  configurationRevision: number
  connectionDigest: string
  hostGroupIds: string[]
  scopeDigest: string
  query: RegisteredProblemQuery
  items: RegisteredProblem[]
  nextAfterEventId: string | null
}

const uuid = /^[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}$/
const digestPattern = /^sha256:[a-f0-9]{64}$/
const positiveId = (value: unknown): value is string => typeof value === 'string' && /^[1-9][0-9]{0,19}$/.test(value) && BigInt(value) <= 18446744073709551615n
const instant = (value: unknown): value is string => typeof value === 'string' && value.length <= 40
  && /^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,9})?Z$/.test(value) && Number.isFinite(Date.parse(value))
const object = (value: unknown): value is Record<string, any> => value !== null && typeof value === 'object' && !Array.isArray(value)
function invalid(): never { throw new Error('注册连接问题页与固定范围不一致，请保留当前页并重新核对') }
function exact(value: unknown, keys: string[]) {
  if (!object(value) || Object.keys(value).length !== keys.length || keys.some(key => !Object.hasOwn(value, key))) invalid()
  return value as Record<string, any>
}
function text(value: unknown, max: number): value is string {
  return typeof value === 'string' && value.length > 0 && value.length <= max && !/[\x00-\x1f\x7f-\x9f]/.test(value)
}
function integer(value: unknown, min: number, max: number): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= min && value <= max
}
async function wireDigest(parts: string[]) {
  const encoder = new TextEncoder(), chunks: Uint8Array[] = []
  for (const part of parts) { const bytes = encoder.encode(part); chunks.push(encoder.encode(`${bytes.byteLength}:`), bytes) }
  const input = new Uint8Array(chunks.reduce((size, chunk) => size + chunk.byteLength, 0)); let offset = 0
  for (const chunk of chunks) { input.set(chunk, offset); offset += chunk.byteLength }
  return 'sha256:' + Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', input)), value => value.toString(16).padStart(2, '0')).join('')
}
function groups(value: unknown): value is string[] {
  return Array.isArray(value) && value.length >= 1 && value.length <= 32
    && value.every(item => typeof item === 'string' && /^[1-9][0-9]{0,18}$/.test(item))
    && new Set(value).size === value.length
    && value.every((item, index) => index === 0 || item.length > value[index - 1].length || item.length === value[index - 1].length && item >= value[index - 1])
}
function errorMessage(status: number, code: string) {
  if (status === 503) return new RegisteredProblemRequestError(status, '登记连接当前不可用；请显式刷新问题页核对结果，不会自动重试。')
  if (status === 403) return new RegisteredProblemRequestError(status, '当前身份没有此登记连接的问题读取权限。')
  if (status === 404) return new RegisteredProblemRequestError(status, '未找到当前连接版本，请刷新实例后核对。')
  if (status === 400) return new RegisteredProblemRequestError(status, '问题页查询窗口或游标无效，请刷新实例后核对。')
  return new RegisteredProblemRequestError(status, code === 'SOURCE_BUSY' ? '登记连接读取并发已满，请显式刷新问题页。' : `问题页读取失败（HTTP ${status}）；不会自动重试。`)
}
export class RegisteredProblemRequestError extends Error {
  constructor(readonly status: number, message: string) { super(message) }
}
function validateQuery(value: unknown): RegisteredProblemQuery {
  const query = exact(value, ['from', 'till', 'afterEventId', 'limit'])
  if (!integer(query.from, 0, 9999999999) || !integer(query.till, query.from, 9999999999) || query.till - query.from > 86400
    || query.till > Math.floor(Date.now() / 1000) || !(query.afterEventId === null || positiveId(query.afterEventId)) || !integer(query.limit, 1, 100)) invalid()
  return query as RegisteredProblemQuery
}
function validateProblem(value: unknown, sourceInstanceId: string): RegisteredProblem {
  const item = exact(value, ['schemaVersion', 'tenantId', 'sourceInstanceId', 'problemEventId', 'triggerId', 'title', 'severity', 'occurredAt', 'observedAt', 'hostIds', 'suppressed', 'recoveryEventId', 'recoveredAt', 'state', 'gaps'])
  if (item.schemaVersion !== '2.0' || !text(item.tenantId, 128) || item.sourceInstanceId !== sourceInstanceId || !positiveId(item.problemEventId)
    || !positiveId(item.triggerId) || !text(item.title, 4096) || !integer(item.severity, 0, 5) || !instant(item.occurredAt) || !instant(item.observedAt)
    || !Array.isArray(item.hostIds) || item.hostIds.length > 20 || !item.hostIds.every(positiveId) || new Set(item.hostIds).size !== item.hostIds.length
    || typeof item.suppressed !== 'boolean' || !(item.recoveryEventId === null || positiveId(item.recoveryEventId))
    || !(item.recoveredAt === null || instant(item.recoveredAt)) || !['ACTIVE', 'RECOVERED', 'RECOVERY_UNKNOWN'].includes(item.state)
    || !Array.isArray(item.gaps) || item.gaps.some((gap: unknown) => gap !== 'RECOVERY_EVENT_UNAVAILABLE')
    || Date.parse(item.occurredAt) > Date.parse(item.observedAt)) invalid()
  const expected = item.recoveryEventId === null ? 'ACTIVE' : item.recoveredAt === null ? 'RECOVERY_UNKNOWN' : 'RECOVERED'
  if (item.state !== expected || JSON.stringify(item.gaps) !== JSON.stringify(expected === 'RECOVERY_UNKNOWN' ? ['RECOVERY_EVENT_UNAVAILABLE'] : [])
    || item.recoveredAt !== null && (item.recoveryEventId === null || Date.parse(item.recoveredAt) < Date.parse(item.occurredAt) || Date.parse(item.recoveredAt) > Date.parse(item.observedAt))) invalid()
  return item as RegisteredProblem
}
export async function parseRegisteredProblemPage(value: unknown, scope: RegisteredProblemScope, expected: RegisteredProblemQuery): Promise<RegisteredProblemPage> {
  if (!uuid.test(scope.sourceId) || !integer(scope.revision, 1, 100) || !digestPattern.test(scope.connectionDigest) || !groups(scope.hostGroupIds)) invalid()
  const page = exact(value, ['schemaVersion', 'storage', 'dataMode', 'sourceId', 'sourceInstanceId', 'configurationRevision', 'connectionDigest', 'hostGroupIds', 'scopeDigest', 'query', 'items', 'nextAfterEventId'])
  if (page.schemaVersion !== '2.0' || !['postgres', 'memory'].includes(page.storage) || page.dataMode !== 'zabbix-jsonrpc'
    || page.sourceId !== scope.sourceId || page.sourceInstanceId !== scope.sourceInstanceId || page.configurationRevision !== scope.revision
    || page.connectionDigest !== scope.connectionDigest || JSON.stringify(page.hostGroupIds) !== JSON.stringify(scope.hostGroupIds) || !digestPattern.test(page.scopeDigest)
    || !Array.isArray(page.items) || page.items.length > expected.limit || !(page.nextAfterEventId === null || positiveId(page.nextAfterEventId))) invalid()
  const calculatedScope = await wireDigest(['registered-problem-scope-v1', scope.sourceId, String(scope.revision), scope.connectionDigest, ...scope.hostGroupIds])
  if (page.scopeDigest !== calculatedScope) invalid()
  const query = validateQuery(page.query)
  if (JSON.stringify(query) !== JSON.stringify(expected)) invalid()
  const items = page.items.map((item: unknown) => validateProblem(item, page.sourceInstanceId))
  let previous = expected.afterEventId === null ? '0' : expected.afterEventId
  const tenantId = items[0]?.tenantId
  for (const item of items) {
    if (BigInt(item.problemEventId) <= BigInt(previous) || item.tenantId !== tenantId
      || Math.floor(Date.parse(item.occurredAt) / 1000) > expected.till
      || item.recoveredAt !== null && Math.floor(Date.parse(item.recoveredAt) / 1000) < expected.from
      || Date.parse(item.observedAt) > Date.now() + 1000) invalid()
    previous = item.problemEventId
  }
  if (page.nextAfterEventId !== null && (items.length !== expected.limit || page.nextAfterEventId !== previous)) invalid()
  return { ...page, query, items } as RegisteredProblemPage
}

export async function readRegisteredProblems(scope: RegisteredProblemScope, query: RegisteredProblemQuery, signal: AbortSignal): Promise<RegisteredProblemPage> {
  if (!uuid.test(scope.sourceId) || !integer(scope.revision, 1, 100) || !digestPattern.test(scope.connectionDigest) || !groups(scope.hostGroupIds)) invalid()
  const checked = validateQuery(query)
  const params = new URLSearchParams({ from: String(checked.from), till: String(checked.till), limit: String(checked.limit) })
  if (checked.afterEventId !== null) params.set('afterEventId', checked.afterEventId)
  const value = await platformClient.request(`/api/v2/data-sources/${scope.sourceId}/connection/${scope.revision}/problems?${params}`, {
    signal, responseBytes: 512 * 1024, error: errorMessage,
  })
  return parseRegisteredProblemPage(value, scope, checked)
}
