import { platformCredentials } from './credential-session.ts'
import { platformClient } from './http.ts'

// Handwritten boundary for the canonical ai-run, ai-run-page, ai-run-event and ai-run-event-page schemas.
// Events are records: reading or replaying them never executes anything, and the payloads are closed —
// no hidden reasoning, no credential, no raw log or evidence body ever crosses this boundary.

export type RunStatus = 'PENDING' | 'RUNNING' | 'WAITING_TOOL' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED'
export type RunReason = 'RUNTIME_UNAVAILABLE' | 'DEADLINE' | 'BUDGET_EXHAUSTED' | 'AUTHORIZATION_REVOKED'
  | 'RESULT_LOST' | 'EXECUTION_FAILED' | 'CANCELLED_BY_REQUEST'
export const RUN_STATUSES: RunStatus[] = ['PENDING', 'RUNNING', 'WAITING_TOOL', 'SUCCEEDED', 'FAILED', 'CANCELLED']
export const RUN_REASONS: RunReason[] = ['RUNTIME_UNAVAILABLE', 'DEADLINE', 'BUDGET_EXHAUSTED', 'AUTHORIZATION_REVOKED',
  'RESULT_LOST', 'EXECUTION_FAILED', 'CANCELLED_BY_REQUEST']

export type Reconciliation = {
  adoptedPriorResult: boolean
  rerun: boolean
  priorModelCalls: number
  uncertainPriorFees: number
  duplicateFeeRisk: boolean
}

export type AiRun = {
  schemaVersion: '2.0'
  runId: string
  requestId: string
  commandDigest: string
  status: RunStatus
  statusVersion: number
  incidentId: string
  question: string
  timeRange: { from: string; to: string }
  knowledgeMode: 'current'
  skill: { id: string; version: string; digest: string }
  attempt: number
  cancelRequestedAt?: string
  reason?: RunReason
  disclosure?: Reconciliation
  createdAt: string
  updatedAt: string
}

export type AiRunPage = { schemaVersion: '2.0'; items: AiRun[]; nextCursor: string | null; hasMore: boolean }

export type RunEventType = 'SUBMITTED' | 'CLAIMED' | 'STATUS_CHANGED' | 'TOOL_WAIT' | 'CANCEL_REQUESTED' | 'RECONCILED' | 'FINISHED'
export const RUN_EVENT_TYPES: RunEventType[] = ['SUBMITTED', 'CLAIMED', 'STATUS_CHANGED', 'TOOL_WAIT', 'CANCEL_REQUESTED', 'RECONCILED', 'FINISHED']

export type RunEvent = {
  runId: string
  seq: number
  type: RunEventType
  occurredAt: string
  payloadVersion: 1
  payload: { status?: RunStatus; attempt?: number; reason?: RunReason; disclosure?: Reconciliation }
}

export type RunSnapshot = { status: RunStatus; statusVersion: number; attempt: number; updatedAt: string }

export type RunEventPage = {
  schemaVersion: '2.0'
  runId: string
  items: RunEvent[]
  nextCursor: string | null
  hasMore: boolean
  gap: boolean
  snapshot: RunSnapshot
}

export type CancelReceipt = {
  schemaVersion: '2.0'; requestId: string; runId: string; requestedAt: string; recorded: boolean; status: RunStatus
}
export type RetryReceipt = {
  schemaVersion: '2.0'; requestId: string; runId: string; requestedAt: string; attempt: number; status: RunStatus; reconciliation: Reconciliation
}

export class AiRunError extends Error {
  constructor(readonly status: number, readonly code: string) {
    super(({ 400: '请求不闭合或参数越界', 401: '身份已失效，请重新建立平台会话', 403: '当前身份无权读取或操作此运行',
      404: '运行不存在或对当前身份不可见', 409: code === 'AI_RUN_REQUEST_REUSED' ? '同一请求标识已用于其他内容'
        : '运行状态不允许该操作', 503: '运行登记服务不可用' } as Record<number, string>)[status] ?? `请求失败（HTTP ${status}）`)
  }
}

const obj = (v: unknown): v is Record<string, unknown> => v !== null && typeof v === 'object' && !Array.isArray(v)
export const isUuid = (v: unknown): v is string => typeof v === 'string' && /^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(v)
const str = (v: unknown, max: number): v is string => typeof v === 'string' && [...v].length > 0 && [...v].length <= max
const time = (v: unknown): v is string => typeof v === 'string' && /^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,9})?Z$/.test(v) && Number.isFinite(Date.parse(v))
const int = (v: unknown, max: number): v is number => typeof v === 'number' && Number.isSafeInteger(v) && v >= 0 && v <= max
const seq = (v: unknown): v is number => typeof v === 'number' && Number.isSafeInteger(v) && v >= 1 && v <= 1_000_000_000_000
function check(ok: unknown): asserts ok { if (!ok) throw new Error('运行登记响应结构不正确') }
function exact(v: unknown, keys: string[]): asserts v is Record<string, unknown> { check(obj(v) && Object.keys(v).length === keys.length && keys.every(k => k in v)) }

function parseReconciliation(v: unknown): Reconciliation {
  exact(v, ['adoptedPriorResult', 'rerun', 'priorModelCalls', 'uncertainPriorFees', 'duplicateFeeRisk'])
  check(typeof v.adoptedPriorResult === 'boolean' && typeof v.rerun === 'boolean' && typeof v.duplicateFeeRisk === 'boolean'
    && int(v.priorModelCalls, 8) && int(v.uncertainPriorFees, 8) && v.uncertainPriorFees <= v.priorModelCalls)
  check(!(v.adoptedPriorResult && v.rerun))
  check(!v.rerun || (v.priorModelCalls === 0 && v.uncertainPriorFees === 0 && !v.duplicateFeeRisk))
  check(!v.duplicateFeeRisk || v.priorModelCalls > 0)
  return v as unknown as Reconciliation
}

function parseReason(v: unknown): RunReason { check(typeof v === 'string' && RUN_REASONS.includes(v as RunReason)); return v as RunReason }
function parseStatus(v: unknown): RunStatus { check(typeof v === 'string' && RUN_STATUSES.includes(v as RunStatus)); return v as RunStatus }

export function parseAiRun(v: unknown): AiRun {
  check(obj(v))
  const r = v as Record<string, unknown>
  const required = ['schemaVersion', 'runId', 'requestId', 'commandDigest', 'status', 'statusVersion', 'incidentId', 'question',
    'timeRange', 'knowledgeMode', 'skill', 'attempt', 'createdAt', 'updatedAt']
  const optional = ['cancelRequestedAt', 'reason', 'disclosure']
  const keys = Object.keys(r)
  check(keys.every(k => required.includes(k) || optional.includes(k)) && required.every(k => keys.includes(k)))
  check(r.schemaVersion === '2.0' && isUuid(r.runId) && isUuid(r.requestId) && isUuid(r.incidentId)
    && typeof r.commandDigest === 'string' && /^sha256:[0-9a-f]{64}$/.test(r.commandDigest)
    && int(r.statusVersion, 1_000_000) && int(r.attempt, 1_000_000) && r.knowledgeMode === 'current'
    && str(r.question, 2000) && time(r.createdAt) && time(r.updatedAt))
  check(obj(r.timeRange) && Object.keys(r.timeRange).length === 2 && time(r.timeRange.from) && time(r.timeRange.to))
  check(obj(r.skill) && Object.keys(r.skill).length === 3 && r.skill.id === 'incident.diagnose' && r.skill.version === '2.0.0'
    && typeof r.skill.digest === 'string' && /^sha256:[0-9a-f]{64}$/.test(r.skill.digest))
  const status = parseStatus(r.status)
  if (keys.includes('cancelRequestedAt')) check(time(r.cancelRequestedAt))
  if (keys.includes('reason')) check(status === 'FAILED' || status === 'CANCELLED'); else check(status !== 'FAILED' && status !== 'CANCELLED')
  if (keys.includes('disclosure')) parseReconciliation(r.disclosure)
  return v as AiRun
}

export function parseAiRunPage(v: unknown): AiRunPage {
  exact(v, ['schemaVersion', 'items', 'nextCursor', 'hasMore'])
  check(v.schemaVersion === '2.0' && Array.isArray(v.items) && v.items.length <= 50 && typeof v.hasMore === 'boolean')
  check((v.nextCursor === null) === !v.hasMore)
  if (v.hasMore) check(v.items.length > 0 && typeof v.nextCursor === 'string' && /^[a-f0-9]{8}(-[0-9a-f]{4}){3}-[a-f0-9]{12}$/.test(v.nextCursor))
  const runIds = new Set<string>()
  for (const item of v.items as unknown[]) {
    const run = parseAiRun(item)
    check(!runIds.has(run.runId))
    runIds.add(run.runId)
  }
  return v as AiRunPage
}

function parseEvent(v: unknown): RunEvent {
  exact(v, ['runId', 'seq', 'type', 'occurredAt', 'payloadVersion', 'payload'])
  check(isUuid(v.runId) && seq(v.seq) && time(v.occurredAt) && v.payloadVersion === 1
    && typeof v.type === 'string' && RUN_EVENT_TYPES.includes(v.type as RunEventType))
  const payload = v.payload
  check(obj(payload) && Object.keys(payload).length <= 4
    && Object.keys(payload).every(k => ['status', 'attempt', 'reason', 'disclosure'].includes(k)))
  if ('status' in payload) parseStatus(payload.status)
  if ('attempt' in payload) check(int(payload.attempt, 1_000_000))
  if ('reason' in payload) parseReason(payload.reason)
  if ('disclosure' in payload) parseReconciliation(payload.disclosure)
  // The type and the payload close each other, exactly as the run registry records them.
  const closed = {
    SUBMITTED: payload.status === 'PENDING' && payload.attempt === 0 && !('reason' in payload) && !('disclosure' in payload),
    CLAIMED: payload.status === 'RUNNING' && typeof payload.attempt === 'number' && payload.attempt >= 1
      && !('reason' in payload) && !('disclosure' in payload),
    TOOL_WAIT: payload.status === 'WAITING_TOOL' && !('attempt' in payload) && !('reason' in payload) && !('disclosure' in payload),
    STATUS_CHANGED: (payload.status === 'RUNNING' || payload.status === 'WAITING_TOOL')
      && !('attempt' in payload) && !('reason' in payload) && !('disclosure' in payload),
    CANCEL_REQUESTED: Object.keys(payload).length === 0,
    RECONCILED: 'disclosure' in payload && !('status' in payload) && !('attempt' in payload) && !('reason' in payload),
    FINISHED: (payload.status === 'SUCCEEDED' || payload.status === 'FAILED' || payload.status === 'CANCELLED')
      && !('attempt' in payload) && (payload.status === 'SUCCEEDED' ? !('reason' in payload) : 'reason' in payload),
  } as Record<string, boolean>
  check(closed[v.type as RunEventType])
  return v as RunEvent
}

export function parseRunEventPage(v: unknown): RunEventPage {
  exact(v, ['schemaVersion', 'runId', 'items', 'nextCursor', 'hasMore', 'gap', 'snapshot'])
  check(v.schemaVersion === '2.0' && isUuid(v.runId) && Array.isArray(v.items) && v.items.length <= 50
    && typeof v.hasMore === 'boolean' && typeof v.gap === 'boolean')
  check((v.nextCursor === null) === !v.hasMore)
  if (v.hasMore) check(v.items.length > 0 && typeof v.nextCursor === 'string' && /^[1-9][0-9]{0,17}$/.test(v.nextCursor))
  let previous = 0
  for (const item of v.items as unknown[]) {
    const event = parseEvent(item)
    check(event.runId === v.runId && event.seq > previous)
    previous = event.seq
  }
  const snapshot = v.snapshot
  exact(snapshot, ['status', 'statusVersion', 'attempt', 'updatedAt'])
  check(int(snapshot.statusVersion, 1_000_000) && int(snapshot.attempt, 1_000_000) && time(snapshot.updatedAt))
  parseStatus(snapshot.status)
  return v as RunEventPage
}

function parseCancelReceipt(v: unknown): CancelReceipt {
  exact(v, ['schemaVersion', 'requestId', 'runId', 'requestedAt', 'recorded', 'status'])
  check(v.schemaVersion === '2.0' && isUuid(v.requestId) && isUuid(v.runId) && time(v.requestedAt) && typeof v.recorded === 'boolean')
  parseStatus(v.status)
  return v as CancelReceipt
}

function parseRetryReceipt(v: unknown): RetryReceipt {
  exact(v, ['schemaVersion', 'requestId', 'runId', 'requestedAt', 'attempt', 'status', 'reconciliation'])
  check(v.schemaVersion === '2.0' && isUuid(v.requestId) && isUuid(v.runId) && time(v.requestedAt) && int(v.attempt, 1_000_000) && v.attempt >= 1)
  parseStatus(v.status)
  parseReconciliation(v.reconciliation)
  return v as RetryReceipt
}

async function request(path: string, signal: AbortSignal, body?: unknown): Promise<unknown> {
  return platformClient.request(path, { signal, body, responseBytes: 262144, timeoutMs: body ? 20000 : 18000,
    error: (status, code) => new AiRunError(status, code) })
}

export async function listAiRuns(signal: AbortSignal, after?: string): Promise<AiRunPage> {
  if (after !== undefined && !isUuid(after)) throw new Error('运行游标不正确')
  return parseAiRunPage(await request(`/api/v1/ai/runs${after ? `?after=${after}` : ''}`, signal))
}

export async function readAiRun(runId: string, signal: AbortSignal): Promise<AiRun> {
  check(isUuid(runId))
  return parseAiRun(await request(`/api/v1/ai/runs/${runId}`, signal))
}

export async function readAiRunEvents(runId: string, afterSeq: number, signal: AbortSignal, limit = 20): Promise<RunEventPage> {
  check(isUuid(runId) && Number.isSafeInteger(afterSeq) && afterSeq >= 0 && Number.isSafeInteger(limit) && limit >= 1 && limit <= 50)
  return parseRunEventPage(await request(`/api/v1/ai/runs/${runId}/events?afterSeq=${afterSeq}&limit=${limit}`, signal))
}

/** The idempotent control command: a replay of the same requestId answers with the original receipt. */
export async function cancelAiRun(runId: string, requestId: string, signal: AbortSignal): Promise<CancelReceipt> {
  check(isUuid(runId) && isUuid(requestId))
  return parseCancelReceipt(await request(`/api/v1/ai/runs/${runId}/cancel`, signal, { requestId }))
}

export async function retryAiRun(runId: string, requestId: string, signal: AbortSignal): Promise<RetryReceipt> {
  check(isUuid(runId) && isUuid(requestId))
  return parseRetryReceipt(await request(`/api/v1/ai/runs/${runId}/retries`, signal, { requestId }))
}

/** Polling is the documented fallback for clients without event streaming; same cursor protocol. */
export function supportsEventStream(): boolean {
  return typeof fetch === 'function' && typeof ReadableStream === 'function' && typeof TextDecoder === 'function'
}

/**
 * One SSE connection of the push channel. The frames carry the same readback pages as the cursor
 * API — a reconnect resumes from the last delivered sequence number with zero side effects. This
 * function only reads; it never executes, retries or notifies anything.
 */
export async function streamRunEvents(input: { runId: string; afterSeq: number; signal: AbortSignal },
  onPage: (page: RunEventPage) => void): Promise<void> {
  check(isUuid(input.runId) && Number.isSafeInteger(input.afterSeq) && input.afterSeq >= 0)
  const requestId = crypto.randomUUID()
  const ticket = platformCredentials.capture(Date.now())
  const controller = new AbortController()
  const unregister = platformCredentials.register(ticket, controller)
  const cancel = () => controller.abort()
  input.signal.addEventListener('abort', cancel, { once: true })
  const linked = () => {
    if (!platformCredentials.current(ticket) || input.signal.aborted) throw new DOMException('请求已取消或会话已变化', 'AbortError')
  }
  try {
    const response = await fetch(`/api/v1/ai/runs/${input.runId}/events/stream?afterSeq=${input.afterSeq}`, {
      method: 'GET',
      headers: { Accept: 'text/event-stream', 'X-OpsWeave-Request-Id': requestId,
        ...(ticket.local ? { 'X-OpsWeave-Local-Session': ticket.token }
          : ticket.cookie ? (ticket.csrf ? { 'X-CSRF-TOKEN': ticket.csrf } : {})
            : { Authorization: `Bearer ${ticket.token}` }) },
      credentials: ticket.cookie ? 'same-origin' : 'omit',
      mode: 'same-origin', redirect: 'error', cache: 'no-store', referrerPolicy: 'no-referrer', signal: controller.signal,
    })
    linked()
    if (!response.ok) {
      platformCredentials.denied(ticket, response.status, new AiRunError(response.status, ''))
      throw new AiRunError(response.status, '')
    }
    if (!/^text\/event-stream(?:\s*;|$)/i.test(response.headers.get('Content-Type') ?? '')) throw new Error('响应不是事件流')
    if (!response.body) throw new Error('事件流没有内容')
    const reader = response.body.getReader()
    const decoder = new TextDecoder('utf-8', { fatal: true })
    let buffer = ''
    try {
      while (true) {
        linked()
        const { value, done } = await reader.read()
        if (done) break
        buffer += decoder.decode(value, { stream: true })
        let split = buffer.indexOf('\n\n')
        while (split >= 0) {
          const frame = buffer.slice(0, split)
          buffer = buffer.slice(split + 2)
          const data = frame.split('\n').filter(line => line.startsWith('data: ')).map(line => line.slice(6)).join('\n')
          if (data) onPage(parseRunEventPage(JSON.parse(data)))
          split = buffer.indexOf('\n\n')
        }
      }
    } finally {
      reader.releaseLock()
      void response.body.cancel().catch(() => {})
    }
    linked()
  } finally {
    input.signal.removeEventListener('abort', cancel)
    unregister()
  }
}
