import { platformClient } from './http.ts'

export type ScanObjectType = 'host' | 'item'
export type ScanStatus = 'RUNNING' | 'SUCCEEDED' | 'FAILED'
export type ScanDataMode = 'labeled-fixture' | 'zabbix-jsonrpc' | 'closed'
export type ScanConsistency = 'offset-scan-attempt' | 'hostid-watermark-snapshot' | 'itemid-watermark-snapshot'
export type ScanFailureCode = 'SOURCE_SCAN_BUSY' | 'SOURCE_SCAN_LOST' | 'SOURCE_SCAN_DEADLINE' | 'SOURCE_SCAN_LIMIT'
  | 'SOURCE_SCAN_UNVERIFIED' | 'SOURCE_FETCH_FAILED' | 'RAW_PERSIST_FAILED' | 'MAPPING_FAILED' | 'INVENTORY_WRITE_FAILED'
  | 'CHECKPOINT_FAILED' | 'PAGE_NOT_ADVANCED' | 'PAGE_LIMIT_EXCEEDED'
export type ScanPipelineRef = { id: string; revision: number; digest: string }
export type ScanRun = {
  syncRunId: string
  objectType: ScanObjectType
  status: ScanStatus
  startedAt: string
  completedAt: string | null
  cursor: string | null
  pages: number
  fetched: number
  accepted: number
  rejected: number
  snapshotComplete: boolean
  dataMode: ScanDataMode
  scanConsistency: ScanConsistency
  failureCode?: ScanFailureCode
  failureSummary?: string
  pipelineVersion?: ScanPipelineRef
}
export type ScanRetention = { maxRunsPerScope: number; maxRunsPerTenant: number; retained: number }
export type ScanRunPage = { schemaVersion:'1.0'; storage:'postgres'|'memory'; dataMode:'scan-log'; tenantId:string; sourceInstanceId:string; objectType:ScanObjectType; limit:number; after:string|null; hasMore:boolean; nextCursor:string|null; retention:ScanRetention; items:ScanRun[] }
export type ScanRunRead = { schemaVersion:'1.0'; storage:'postgres'|'memory'; dataMode:'scan-log'; tenantId:string; sourceInstanceId:string; objectType:ScanObjectType; run:ScanRun }

/** Fixed summaries mirror SyncFailureCode.safeSummary(); any other text is refused, never rendered. */
const FAILURE_SUMMARY: Record<ScanFailureCode,string> = {
  SOURCE_SCAN_BUSY: 'Another source scan owns this scope. No source request was started.',
  SOURCE_SCAN_LOST: 'Source scan lease was lost. This scan cannot write or reconcile missing objects.',
  SOURCE_SCAN_DEADLINE: 'Source scan exceeded its five minute deadline. This scan cannot write or reconcile missing objects.',
  SOURCE_SCAN_LIMIT: 'Source scan fencing counter is exhausted. No source request was started.',
  SOURCE_SCAN_UNVERIFIED: 'The scan ended without a verified snapshot. Existing entities were kept and nothing was reconciled.',
  SOURCE_FETCH_FAILED: 'Source request failed. Existing entities were kept.',
  RAW_PERSIST_FAILED: 'Raw record could not be stored. Existing entities were kept.',
  MAPPING_FAILED: 'A record could not be mapped. Existing entities were kept.',
  INVENTORY_WRITE_FAILED: 'Entity write failed. Existing entities were kept.',
  CHECKPOINT_FAILED: 'Sync checkpoint could not be stored. Previously committed inventory changes may remain.',
  PAGE_NOT_ADVANCED: 'Page cursor did not advance. Existing entities were kept.',
  PAGE_LIMIT_EXCEEDED: 'Page limit was reached before the snapshot completed. Existing entities were kept.',
}

export class ScanRunError extends Error {
  constructor(readonly status: number, code: string) {
    super(status === 400 ? `扫描记录查询参数不正确${code ? `：${code}` : ''}。`
      : status === 403 ? '读取扫描日志需要来源级 source.sync 权限；只有 entity.read 不足以读取。'
      : status === 404 ? '未找到该扫描运行；跨租户、跨来源或跨对象类型的运行不会被返回。'
      : status === 503 ? '扫描日志需要已配置的来源与 PostgreSQL；当前不可用。'
      : `扫描记录请求失败（HTTP ${status}）`)
  }
}

function check(value: unknown): asserts value { if (!value) throw new Error('扫描记录响应结构、范围或时间不正确') }
function exact(value: unknown, keys: string[]): asserts value is Record<string, unknown> {
  check(!!value && typeof value === 'object' && !Array.isArray(value)
    && Object.keys(value).length === keys.length && keys.every(key => key in value))
}
const uuid = (value: unknown): value is string => typeof value === 'string' && /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?![\s\S])/.test(value)
const text = (value: unknown, max = 128): value is string => typeof value === 'string' && !!value.trim() && value.length <= max && !/[\x00-\x1f\x7f-\x9f]/.test(value)
const source = (value: unknown): value is string => typeof value === 'string' && /^[A-Za-z0-9_.:-]{1,128}(?![\s\S])/.test(value)
const cursor = (value: unknown): value is string => typeof value === 'string' && /^[A-Za-z0-9_-]{1,192}(?![\s\S])/.test(value)
const digest = (value: unknown): value is string => typeof value === 'string' && /^sha256:[0-9a-f]{64}(?![\s\S])/.test(value)
const count = (value: unknown): value is number => typeof value === 'number' && Number.isSafeInteger(value) && value >= 0
function time(value: unknown): bigint {
  check(typeof value === 'string' && /^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,9})?Z(?![\s\S])/.test(value))
  const ms = Date.parse(value)
  check(ms >= 0 && Number.isFinite(ms) && new Date(ms).toISOString().slice(0, 19) === value.slice(0, 19))
  return BigInt(Math.floor(ms / 1000)) * 1000000000n + BigInt((value.match(/\.(\d+)Z$/)?.[1] ?? '').padEnd(9, '0'))
}
const OBJECT_TYPES: ScanObjectType[] = ['host', 'item']
const STATUSES: ScanStatus[] = ['RUNNING', 'SUCCEEDED', 'FAILED']
const DATA_MODES: ScanDataMode[] = ['labeled-fixture', 'zabbix-jsonrpc', 'closed']
const CONSISTENCIES: ScanConsistency[] = ['offset-scan-attempt', 'hostid-watermark-snapshot', 'itemid-watermark-snapshot']
const REQUIRED = ['syncRunId','objectType','status','startedAt','completedAt','cursor','pages','fetched','accepted','rejected','snapshotComplete','dataMode','scanConsistency']
const OPTIONAL = ['failureCode','failureSummary','pipelineVersion']

function parseRef(value: unknown): ScanPipelineRef {
  exact(value, ['id', 'revision', 'digest'])
  check(typeof value.id === 'string' && /^[A-Za-z][A-Za-z0-9_-]{0,63}(?![\s\S])/.test(value.id)
    && typeof value.revision === 'number' && Number.isSafeInteger(value.revision) && value.revision >= 1 && value.revision <= 2147483647
    && digest(value.digest))
  return value as unknown as ScanPipelineRef
}

export function parseScanRun(value: unknown, objectType: ScanObjectType): ScanRun {
  check(!!value && typeof value === 'object' && !Array.isArray(value))
  const keys = Object.keys(value)
  check(keys.length >= REQUIRED.length && REQUIRED.every(key => keys.includes(key)) && keys.every(key => [...REQUIRED, ...OPTIONAL].includes(key)))
  const run = value as Record<string, unknown>
  check(uuid(run.syncRunId) && OBJECT_TYPES.includes(run.objectType as ScanObjectType) && run.objectType === objectType)
  check(STATUSES.includes(run.status as ScanStatus) && DATA_MODES.includes(run.dataMode as ScanDataMode))
  check(CONSISTENCIES.includes(run.scanConsistency as ScanConsistency))
  check(run.cursor === null || text(run.cursor, 256))
  check(count(run.pages) && count(run.fetched) && count(run.accepted) && count(run.rejected))
  check(typeof run.snapshotComplete === 'boolean')
  const started = time(run.startedAt)
  if (run.completedAt !== null) check(time(run.completedAt) >= started)
  if (run.status === 'RUNNING') check(run.snapshotComplete === false && run.completedAt === null)
  else check(run.snapshotComplete === (run.status === 'SUCCEEDED') && typeof run.completedAt === 'string')
  const hasCode = 'failureCode' in run
  const hasSummary = 'failureSummary' in run
  check(hasCode === hasSummary)
  if (hasCode) {
    const code = run.failureCode as ScanFailureCode
    check(code in FAILURE_SUMMARY && run.failureSummary === FAILURE_SUMMARY[code])
  }
  if ('pipelineVersion' in run) parseRef(run.pipelineVersion)
  return run as unknown as ScanRun
}

function base(value: unknown, objectType: ScanObjectType) {
  check(!!value && typeof value === 'object' && !Array.isArray(value))
  const page = value as Record<string, unknown>
  check(page.schemaVersion === '1.0' && (page.storage === 'postgres' || page.storage === 'memory') && page.dataMode === 'scan-log')
  check(text(page.tenantId) && source(page.sourceInstanceId) && page.objectType === objectType)
  return page
}

/** The published storage budget. A stricter deployment may only lower these, never raise them. */
const MAX_RUNS_PER_SCOPE = 1000
const MAX_RUNS_PER_TENANT = 5000

function parseRetention(value: unknown): ScanRetention {
  exact(value, ['maxRunsPerScope', 'maxRunsPerTenant', 'retained'])
  const retention = value as Record<string, unknown>
  check(count(retention.maxRunsPerScope) && retention.maxRunsPerScope >= 1 && retention.maxRunsPerScope <= MAX_RUNS_PER_SCOPE)
  check(count(retention.maxRunsPerTenant) && retention.maxRunsPerTenant >= 1 && retention.maxRunsPerTenant <= MAX_RUNS_PER_TENANT)
  check(retention.maxRunsPerTenant >= retention.maxRunsPerScope)
  check(count(retention.retained) && retention.retained <= MAX_RUNS_PER_SCOPE)
  return value as unknown as ScanRetention
}

export function parseScanRunPage(value: unknown, objectType: ScanObjectType, expected: { limit: number; after: string | null }): ScanRunPage {
  const page = base(value, objectType)
  exact(value, ['schemaVersion','storage','dataMode','tenantId','sourceInstanceId','objectType','limit','after','hasMore','nextCursor','retention','items'])
  check(page.limit === expected.limit && page.limit >= 1 && page.limit <= 50 && page.after === expected.after)
  check(typeof page.hasMore === 'boolean' && Array.isArray(page.items) && page.items.length <= page.limit)
  check(page.hasMore ? cursor(page.nextCursor) : page.nextCursor === null)
  const retention = parseRetention(page.retention)
  const items = page.items.map(item => parseScanRun(item, objectType))
  check(new Set(items.map(item => item.syncRunId)).size === items.length)
  check(retention.retained >= items.length)
  return { ...(page as unknown as ScanRunPage), retention, items }
}

export function parseScanRunRead(value: unknown, objectType: ScanObjectType): ScanRunRead {
  const body = base(value, objectType)
  exact(value, ['schemaVersion','storage','dataMode','tenantId','sourceInstanceId','objectType','run'])
  return { ...(body as unknown as ScanRunRead), run: parseScanRun(body.run, objectType) }
}

const failure = (status: number, code: string) => new ScanRunError(status, code)
const root = '/api/v1/integrations/zabbix'

export async function sourceScanRuns(objectType: ScanObjectType, options: { limit?: number; after?: string | null }, signal: AbortSignal): Promise<ScanRunPage> {
  const limit = options.limit ?? 20
  const after = options.after ?? null
  check(OBJECT_TYPES.includes(objectType) && Number.isSafeInteger(limit) && limit >= 1 && limit <= 50 && (after === null || cursor(after)))
  const query = `limit=${limit}${after === null ? '' : `&after=${encodeURIComponent(after)}`}`
  return parseScanRunPage(await platformClient.request(`${root}/${objectType}s/runs?${query}`, { signal, error: failure, responseBytes: 262144 }), objectType, { limit, after })
}

export async function sourceScanRun(objectType: ScanObjectType, syncRunId: string, signal: AbortSignal): Promise<ScanRunRead> {
  check(OBJECT_TYPES.includes(objectType) && uuid(syncRunId))
  return parseScanRunRead(await platformClient.request(`${root}/${objectType}s/runs/${syncRunId}`, { signal, error: failure, responseBytes: 262144 }), objectType)
}
