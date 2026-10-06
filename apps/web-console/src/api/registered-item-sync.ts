import { platformClient } from './http.ts'
import type { ConnectionConfiguration } from './source-connections.ts'

export type RegisteredItemRun = {
  syncRunId: string
  sourceId: string
  configurationRevision: number
  connectionDigest: string
  scopeDigest: string
  objectType: 'item'
  status: 'RUNNING' | 'SUCCEEDED' | 'FAILED'
  startedAt: string
  completedAt: string | null
  cursor: string | null
  pages: number
  fetched: number
  accepted: number
  rejected: number
  retired: number
  snapshotComplete: boolean
  dataMode: 'labeled-fixture' | 'zabbix-jsonrpc' | 'closed'
  scanConsistency: 'offset-scan-attempt' | 'itemid-watermark-snapshot'
  failureCode?: string
  failureSummary?: string
}
export type RegisteredItemRunPage = { storage: 'postgres' | 'memory'; limit: number; after: string | null; hasMore: boolean; nextCursor: string | null; items: RegisteredItemRun[] }
export type RegisteredItemRunScope = Pick<ConnectionConfiguration, 'sourceId' | 'revision' | 'connectionDigest' | 'hostGroupIds'>

const failureSummaries: Record<string, string> = {
  SOURCE_SCAN_BUSY: 'Another source scan owns this scope. No source request was started.',
  SOURCE_SCAN_LOST: 'Source scan lease was lost. This scan cannot write or reconcile missing objects.',
  SOURCE_SCAN_DEADLINE: 'Source scan exceeded its five minute deadline. This scan cannot write or reconcile missing objects.',
  SOURCE_SCAN_LIMIT: 'Host scan fencing counter is exhausted. No source request was started.',
  SOURCE_FETCH_FAILED: 'Source request failed. Existing entities were kept.',
  RAW_PERSIST_FAILED: 'Raw record could not be stored. Existing entities were kept.',
  MAPPING_FAILED: 'A record could not be mapped. Existing entities were kept.',
  INVENTORY_WRITE_FAILED: 'Entity write failed. Existing entities were kept.',
  CHECKPOINT_FAILED: 'Sync checkpoint could not be stored. Previously committed inventory changes may remain.',
  PAGE_NOT_ADVANCED: 'Page cursor did not advance. Existing entities were kept.',
  PAGE_LIMIT_EXCEEDED: 'Page limit was reached before the snapshot completed. Existing entities were kept.',
  SOURCE_SCAN_UNVERIFIED: 'The scan ended without a verified snapshot. Existing entities were kept and nothing was reconciled.',
}

const uuid = /^[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}$/
const digest = /^sha256:[a-f0-9]{64}$/
function invalid(): never { throw new Error('指标扫描回执与当前连接版本不一致') }
function object(value: unknown): Record<string, unknown> { if (!value || typeof value !== 'object' || Array.isArray(value)) invalid(); return value as Record<string, unknown> }
function exact(value: unknown, keys: string[]) { const row = object(value); if (Object.keys(row).length !== keys.length || keys.some(key => !Object.hasOwn(row, key))) invalid(); return row }
function integer(value: unknown, max = Number.MAX_SAFE_INTEGER): value is number { return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0 && value <= max }
function time(value: unknown): value is string { return typeof value === 'string' && value.length <= 40 && /^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,9})?Z$/.test(value) && Number.isFinite(Date.parse(value)) }
function verifyScope(value: unknown, scope: RegisteredItemRunScope) {
  const envelope = object(value)
  if (envelope.schemaVersion !== '2.0' || !['postgres', 'memory'].includes(String(envelope.storage)) || envelope.dataMode !== 'scan-log'
    || envelope.sourceId !== scope.sourceId || envelope.sourceInstanceId !== 'connection-' + scope.sourceId
    || envelope.configurationRevision !== scope.revision || envelope.connectionDigest !== scope.connectionDigest
    || !Array.isArray(envelope.hostGroupIds) || envelope.hostGroupIds.length !== scope.hostGroupIds.length
    || envelope.hostGroupIds.some((id, index) => id !== scope.hostGroupIds[index])
    || typeof envelope.tenantId !== 'string' || !envelope.tenantId.trim()) invalid()
  return envelope
}
function parseRun(value: unknown): RegisteredItemRun {
  const row = object(value)
  const required = ['syncRunId','sourceId','configurationRevision','connectionDigest','scopeDigest','objectType','status','startedAt','completedAt','cursor','pages','fetched','accepted','rejected','retired','snapshotComplete','dataMode','scanConsistency']
  const optional = ['failureCode','failureSummary']
  if (required.some(key => !Object.hasOwn(row, key)) || Object.keys(row).some(key => ![...required, ...optional].includes(key))) invalid()
  const revision = row.configurationRevision
  if (typeof row.syncRunId !== 'string' || !uuid.test(row.syncRunId) || typeof row.sourceId !== 'string' || !uuid.test(row.sourceId)
    || !integer(revision, 100) || revision < 1
    || typeof row.connectionDigest !== 'string' || !digest.test(row.connectionDigest) || typeof row.scopeDigest !== 'string' || !digest.test(row.scopeDigest)
    || row.objectType !== 'item'
    || !['RUNNING', 'SUCCEEDED', 'FAILED'].includes(String(row.status)) || !time(row.startedAt)
    || !(row.completedAt === null || time(row.completedAt)) || !(row.cursor === null || typeof row.cursor === 'string' && row.cursor.length <= 256)
    || !integer(row.pages) || !integer(row.fetched) || !integer(row.accepted) || !integer(row.rejected) || !integer(row.retired)
    || typeof row.snapshotComplete !== 'boolean' || !['labeled-fixture', 'zabbix-jsonrpc', 'closed'].includes(String(row.dataMode))
    || !['offset-scan-attempt', 'itemid-watermark-snapshot'].includes(String(row.scanConsistency))) invalid()
  if (row.status === 'RUNNING' ? row.completedAt !== null || row.snapshotComplete : row.completedAt === null || row.snapshotComplete !== (row.status === 'SUCCEEDED')) invalid()
  if (('failureCode' in row) !== ('failureSummary' in row) || 'failureCode' in row && (typeof row.failureCode !== 'string' || !(row.failureCode in failureSummaries) || row.failureSummary !== failureSummaries[row.failureCode])) invalid()
  if (row.completedAt && Date.parse(row.completedAt) < Date.parse(row.startedAt)) invalid()
  return row as unknown as RegisteredItemRun
}
function errors(status: number, code: string) {
  return new Error(status === 400 ? '扫描请求无效，请重新读取当前连接配置。'
    : status === 403 ? '当前身份没有此连接的指标同步权限。'
    : status === 404 ? '未找到当前连接版本或扫描记录。'
    : status === 409 ? '当前范围已有扫描或连接配置已变化，请刷新后核对。'
    : status === 503 ? code === 'SOURCE_UNAVAILABLE' ? '登记连接当前不可用；请刷新扫描历史确认结果，不会自动重试。' : '指标同步暂不可用；请刷新扫描历史确认结果，不会自动重试。'
    : `指标扫描请求失败（HTTP ${status}）；请刷新扫描历史确认结果，不会自动重试。`)
}
function path(scope: RegisteredItemRunScope) {
  if (!uuid.test(scope.sourceId) || !Number.isSafeInteger(scope.revision) || scope.revision < 1 || scope.revision > 100 || !digest.test(scope.connectionDigest)
    || !Array.isArray(scope.hostGroupIds) || scope.hostGroupIds.length < 1 || scope.hostGroupIds.length > 32
    || scope.hostGroupIds.some(id => typeof id !== 'string' || !/^[1-9][0-9]{0,18}$/.test(id))) invalid()
  return `/api/v2/data-sources/${scope.sourceId}/connection/${scope.revision}/items`
}

function verifyRunScope(run: RegisteredItemRun, scope: RegisteredItemRunScope) {
  if (run.sourceId !== scope.sourceId || run.configurationRevision !== scope.revision || run.connectionDigest !== scope.connectionDigest) invalid()
}
export async function readRegisteredItemRuns(scope: RegisteredItemRunScope, options: { limit?: number; after?: string | null }, signal: AbortSignal): Promise<RegisteredItemRunPage> {
  const root = path(scope), limit = options.limit ?? 20, after = options.after ?? null
  if (!Number.isSafeInteger(limit) || limit < 1 || limit > 50 || after !== null && (typeof after !== 'string' || !/^[A-Za-z0-9_-]{1,192}$/.test(after))) invalid()
  const query = `limit=${limit}${after === null ? '' : `&after=${encodeURIComponent(after)}`}`
  const value = await platformClient.request(`${root}/runs?${query}`, { signal, responseBytes: 262144, error: errors })
  const envelope = verifyScope(value, scope)
  exact(value, ['schemaVersion','storage','dataMode','tenantId','sourceId','sourceInstanceId','configurationRevision','connectionDigest','hostGroupIds','limit','after','hasMore','nextCursor','items'])
  if (envelope.limit !== limit || envelope.after !== after || typeof envelope.hasMore !== 'boolean' || !Array.isArray(envelope.items) || envelope.items.length > limit
    || (envelope.hasMore ? typeof envelope.nextCursor !== 'string' || !/^[A-Za-z0-9_-]{1,192}$/.test(String(envelope.nextCursor)) : envelope.nextCursor !== null)) invalid()
  const items = envelope.items.map(parseRun); items.forEach(run => verifyRunScope(run, scope))
  if (new Set(items.map(run => run.syncRunId)).size !== items.length) invalid()
  return { storage: envelope.storage as 'postgres' | 'memory', limit, after, hasMore: envelope.hasMore, nextCursor: envelope.nextCursor as string | null, items }
}

export async function readRegisteredItemRun(scope: RegisteredItemRunScope, id: string, signal: AbortSignal): Promise<RegisteredItemRun> {
  const root = path(scope)
  if (!uuid.test(id)) invalid()
  const value = await platformClient.request(`${root}/runs/${id}`, { signal, responseBytes: 65536, error: errors })
  const envelope = verifyScope(value, scope)
  exact(value, ['schemaVersion','storage','dataMode','tenantId','sourceId','sourceInstanceId','configurationRevision','connectionDigest','hostGroupIds','run'])
  const run = parseRun(envelope.run)
  if (run.syncRunId !== id) invalid()
  verifyRunScope(run, scope)
  return run
}

export async function syncRegisteredItems(scope: RegisteredItemRunScope, signal: AbortSignal): Promise<RegisteredItemRun> {
  const root = path(scope)
  const value = await platformClient.request(`${root}/sync`, { method: 'POST', signal, responseBytes: 65536, error: errors })
  const row = object(value)
  const required = ['schemaVersion','storage','dataMode','sourceId','sourceInstanceId','configurationRevision','connectionDigest','hostGroupIds','scopeDigest','pages','fetched','accepted','rejected','retired','snapshotComplete','scanConsistency','syncRunId']
  if (required.some(key => !Object.hasOwn(row, key)) || Object.keys(row).some(key => ![...required, 'failureCode', 'summary', 'error'].includes(key))) invalid()
  if (row.schemaVersion !== '2.0' || !['postgres', 'memory'].includes(String(row.storage)) || row.sourceId !== scope.sourceId || row.sourceInstanceId !== 'connection-' + scope.sourceId
    || row.configurationRevision !== scope.revision || row.connectionDigest !== scope.connectionDigest || !digest.test(String(row.scopeDigest))
    || !Array.isArray(row.hostGroupIds) || row.hostGroupIds.length !== scope.hostGroupIds.length || row.hostGroupIds.some((id, index) => id !== scope.hostGroupIds[index])
    || !['labeled-fixture', 'zabbix-jsonrpc', 'closed'].includes(String(row.dataMode)) || !integer(row.pages) || !integer(row.fetched) || !integer(row.accepted)
    || !integer(row.rejected) || !integer(row.retired) || row.snapshotComplete !== true
    || !['offset-scan-attempt', 'itemid-watermark-snapshot'].includes(String(row.scanConsistency)) || typeof row.syncRunId !== 'string' || !uuid.test(row.syncRunId)) invalid()
  const created = await readRegisteredItemRun(scope, row.syncRunId, signal)
  if (created.status !== 'SUCCEEDED' || created.accepted !== row.accepted || created.rejected !== row.rejected || created.retired !== row.retired || !created.snapshotComplete) invalid()
  return created
}
