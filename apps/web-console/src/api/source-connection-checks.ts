import { platformClient } from './http.ts'

export type ConnectionCheckDataMode = 'labeled-fixture' | 'zabbix-jsonrpc' | 'closed'
export type ConnectionCheckStatus = 'labeled-fixture' | 'ok' | 'unreachable' | 'not-configured'
export type SourceConnectionCheck = {
  checkId: string
  sourceInstanceId: string
  actor: string
  checkedAt: string
  dataMode: ConnectionCheckDataMode
  reachable: boolean
  statusCode: ConnectionCheckStatus
  reportedVersion: string | null
}
export type ConnectionCheckReceipt = { schemaVersion:'1.0'; storage:'postgres'|'memory'; dataMode:'connection-check'; tenantId:string; sourceInstanceId:string; check:SourceConnectionCheck }
export type ConnectionCheckPage = { schemaVersion:'1.0'; storage:'postgres'|'memory'; dataMode:'connection-check'; tenantId:string; sourceInstanceId:string; limit:number; items:SourceConnectionCheck[] }

export class ConnectionCheckError extends Error {
  constructor(readonly status: number, code: string) {
    super(status === 400 ? `来源连接自检只接受 limit（1–50）这一个查询参数${code ? `：${code}` : ''}。`
      : status === 403 ? '执行来源连接自检需要来源级 source.sync 权限；菜单或提示词都不代替服务器授权。'
      : status === 503 ? '来源未配置或平台存储不可用；自检失败不会回退成 fixture 成功。'
      : `来源连接自检请求失败（HTTP ${status}）`)
  }
}

const CHECKS = '/api/v1/integrations/zabbix/connection-checks'
const DATA_MODES: ConnectionCheckDataMode[] = ['labeled-fixture', 'zabbix-jsonrpc', 'closed']
const REACHABLE_STATUS: ConnectionCheckStatus[] = ['labeled-fixture', 'ok']
const UNREACHABLE_STATUS: ConnectionCheckStatus[] = ['unreachable', 'not-configured']
const KEYS = ['checkId', 'sourceInstanceId', 'actor', 'checkedAt', 'dataMode', 'reachable', 'statusCode', 'reportedVersion']

function check(value: unknown): asserts value { if (!value) throw new Error('来源连接自检响应结构、范围或时间不正确') }
function exact(value: unknown, keys: string[]): asserts value is Record<string, unknown> {
  check(!!value && typeof value === 'object' && !Array.isArray(value)
    && Object.keys(value).length === keys.length && keys.every(key => key in value))
}
const uuid = (value: unknown): value is string => typeof value === 'string' && /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?![\s\S])/.test(value)
const source = (value: unknown): value is string => typeof value === 'string' && /^[A-Za-z0-9_.:-]{1,128}(?![\s\S])/.test(value)
const label = (value: unknown, max: number): value is string => typeof value === 'string' && value.length >= 1 && value.length <= max
  && !/[\x00-\x1f\x7f-\x9f]/.test(value)
const printable = (value: unknown, max: number): value is string => typeof value === 'string'
  && new RegExp(`^[ -~]{1,${max}}(?![\\s\\S])`).test(value)
function time(value: unknown): bigint {
  check(typeof value === 'string' && /^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,9})?Z(?![\s\S])/.test(value))
  const ms = Date.parse(value)
  check(ms >= 0 && Number.isFinite(ms) && new Date(ms).toISOString().slice(0, 19) === value.slice(0, 19))
  return BigInt(Math.floor(ms / 1000)) * 1000000000n + BigInt((value.match(/\.(\d+)Z$/)?.[1] ?? '').padEnd(9, '0'))
}

/**
 * One receipt says what the source reported at one moment. The status code is a closed set of stable
 * codes (never vendor text), a fixture probe can only report itself as a labeled fixture, and an
 * unreachable or unconfigured check must not carry a version claim.
 */
export function parseConnectionCheck(value: unknown, expectedSource: string): SourceConnectionCheck {
  exact(value, KEYS)
  const item = value as Record<string, unknown>
  check(uuid(item.checkId) && source(item.sourceInstanceId) && item.sourceInstanceId === expectedSource)
  check(label(item.actor, 128) && item.actor.trim() === item.actor)
  time(item.checkedAt)
  check(DATA_MODES.includes(item.dataMode as ConnectionCheckDataMode))
  check(typeof item.reachable === 'boolean')
  check(printable(item.statusCode, 64) && (REACHABLE_STATUS.includes(item.statusCode as ConnectionCheckStatus) || UNREACHABLE_STATUS.includes(item.statusCode as ConnectionCheckStatus)))
  check(item.reportedVersion === null || printable(item.reportedVersion, 32))
  if (item.reachable) check(REACHABLE_STATUS.includes(item.statusCode as ConnectionCheckStatus))
  else check(UNREACHABLE_STATUS.includes(item.statusCode as ConnectionCheckStatus) && item.reportedVersion === null)
  if (item.statusCode === 'labeled-fixture') check(item.reportedVersion === null)
  return item as unknown as SourceConnectionCheck
}

function envelope(value: unknown) {
  check(!!value && typeof value === 'object' && !Array.isArray(value))
  const body = value as Record<string, unknown>
  check(body.schemaVersion === '1.0' && (body.storage === 'postgres' || body.storage === 'memory') && body.dataMode === 'connection-check')
  check(label(body.tenantId, 128) && source(body.sourceInstanceId))
  return body
}

export function parseConnectionCheckReceipt(value: unknown): ConnectionCheckReceipt {
  const body = envelope(value)
  exact(value, ['schemaVersion', 'storage', 'dataMode', 'tenantId', 'sourceInstanceId', 'check'])
  return { ...(body as unknown as ConnectionCheckReceipt), check: parseConnectionCheck(body.check, body.sourceInstanceId as string) }
}

export function parseConnectionCheckPage(value: unknown, limit: number): ConnectionCheckPage {
  const body = envelope(value)
  exact(value, ['schemaVersion', 'storage', 'dataMode', 'tenantId', 'sourceInstanceId', 'limit', 'items'])
  check(body.limit === limit && limit >= 1 && limit <= 50 && Array.isArray(body.items) && body.items.length <= limit)
  const items = body.items.map(item => parseConnectionCheck(item, body.sourceInstanceId as string))
  check(new Set(items.map(item => item.checkId)).size === items.length)
  check(items.every((item, index) => index === 0 || time(items[index - 1].checkedAt) >= time(item.checkedAt)))
  return { ...(body as unknown as ConnectionCheckPage), items }
}

const failure = (status: number, code: string) => new ConnectionCheckError(status, code)

export async function runConnectionCheck(signal: AbortSignal): Promise<ConnectionCheckReceipt> {
  return parseConnectionCheckReceipt(await platformClient.request(CHECKS, { method: 'POST', signal, error: failure, responseBytes: 65536 }))
}

export async function recentConnectionChecks(limit: number, signal: AbortSignal): Promise<ConnectionCheckPage> {
  check(Number.isSafeInteger(limit) && limit >= 1 && limit <= 50)
  return parseConnectionCheckPage(await platformClient.request(`${CHECKS}?limit=${limit}`, { signal, error: failure, responseBytes: 262144 }), limit)
}
