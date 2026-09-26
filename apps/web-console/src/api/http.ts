import { CredentialSession, platformCredentials, type CredentialTicket } from './credential-session.ts'

type FailureMetadata = { failureCode: string; pages: number | null }
type Options = { signal: AbortSignal; method?: 'GET' | 'POST'; body?: unknown; timeoutMs?: number; responseBytes?: number; bootstrap?: boolean; error?: (status: number, code: string, metadata: FailureMetadata) => Error }
export class TransportError extends Error {
  readonly requestId: string
  constructor(message: string, requestId: string) { super(`${message}（请求 ${requestId}）`); this.requestId = requestId }
}
const MAX_RESPONSE = 2 * 1024 * 1024
const encoder = new TextEncoder()
const uuid = /^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/

/** Fixed same-origin transport. Cookies only in explicit BFF mode; no redirects, retry or remote references. */
export class JsonClient {
  private readonly session: CredentialSession
  private readonly surface: 'platform' | 'fixture-demo'
  private readonly fetcher: typeof fetch
  constructor(session: CredentialSession, surface: 'platform' | 'fixture-demo', fetcher: typeof fetch = fetch) { this.session = session; this.surface = surface; this.fetcher = (input, init) => fetcher(input, init) }
  async request(path: string, options: Options): Promise<unknown> {
    const requestId = crypto.randomUUID()
    const fail = (message: string) => new TransportError(message, requestId)
    const url = new URL(path, 'http://same-origin.invalid')
    if (!path.startsWith(this.surface === 'platform' ? '/api/v1/' : '/agent/api/v1/') || path.length > 8192 || /[\\\x00-\x20#]/.test(path)
      || url.origin !== 'http://same-origin.invalid' || !url.pathname.startsWith(this.surface === 'platform' ? '/api/v1/' : '/agent/api/v1/')) throw fail('请求地址不在允许的 API 范围')
    const timeoutMs = options.timeoutMs ?? 35000
    const responseBytes = options.responseBytes ?? MAX_RESPONSE
    if (!Number.isSafeInteger(timeoutMs) || timeoutMs < 1 || timeoutMs > 80000) throw fail('请求超时预算不正确')
    if (!Number.isSafeInteger(responseBytes) || responseBytes < 1 || responseBytes > MAX_RESPONSE) throw fail('响应大小预算不正确')
    const body = options.body === undefined ? undefined : JSON.stringify(options.body)
    if (body && encoder.encode(body).byteLength > 65536) throw fail('请求正文超过限制')
    if (options.signal.aborted) throw new DOMException('请求已取消', 'AbortError')
    if (options.bootstrap && (this.surface !== 'platform' || !this.session.isCookie() || path !== '/api/v1/auth/session' || body !== undefined || options.method === 'POST')) throw fail('会话读取地址不正确')
    const ticket: CredentialTicket = this.session.capture(Date.now(), options.bootstrap)
    if (ticket.cookie && this.surface !== 'platform') throw fail('浏览器会话不能转发到演示 Runtime')
    const controller = new AbortController(); const unregister = this.session.register(ticket, controller)
    let timedOut = false; let consumed = false; let knownError: Error | undefined
    const cancel = () => controller.abort()
    options.signal.addEventListener('abort', cancel, { once: true })
    const timer = setTimeout(() => { timedOut = true; controller.abort() }, timeoutMs)
    const current = () => {
      this.session.expire()
      if (!this.session.current(ticket) || options.signal.aborted) throw new DOMException('请求已取消或会话已变化', 'AbortError')
      if (timedOut) throw fail('请求超时，提交结果待确认，请按原请求标识查询')
    }
    try {
      const response = await this.fetcher(path, { method: options.method ?? (body === undefined ? 'GET' : 'POST'), body, signal: controller.signal,
        headers: { Accept: 'application/json', ...(ticket.cookie ? (ticket.csrf && !options.bootstrap ? { 'X-CSRF-TOKEN': ticket.csrf } : {}) : { Authorization: `Bearer ${ticket.token}` }), 'X-OpsWeave-Request-Id': requestId, ...(body === undefined ? {} : { 'Content-Type': 'application/json' }) },
        credentials: ticket.cookie ? 'same-origin' : 'omit', mode: 'same-origin', redirect: 'error', cache: 'no-store', referrerPolicy: 'no-referrer' })
      current()
      const echoed = response.headers.get('X-OpsWeave-Request-Id')
      if (echoed !== null && (!uuid.test(echoed) || echoed !== requestId)) throw fail('响应请求标识不匹配')
      if (!response.ok) {
        let code = response.status === 401 ? 'UNAUTHENTICATED' : response.status === 403 ? 'FORBIDDEN' : ''
        const metadata: FailureMetadata = { failureCode: '', pages: null }
        if (![401, 403].includes(response.status)) {
          try {
            const value: unknown = JSON.parse(await readBody(response, 8192, controller.signal))
            if (value && typeof value === 'object') {
              if ('error' in value && typeof value.error === 'string' && /^[A-Z_]{1,64}$/.test(value.error)) code = value.error
              if ('failureCode' in value && typeof value.failureCode === 'string' && /^[A-Z_]{1,64}$/.test(value.failureCode)) metadata.failureCode = value.failureCode
              if ('pages' in value && typeof value.pages === 'number' && Number.isSafeInteger(value.pages) && value.pages >= 0 && value.pages <= 100000) metadata.pages = value.pages
            }
          } catch { /* HTTP status remains authoritative; never render raw error text. */ }
        } else { void response.body?.cancel().catch(() => {}) }
        current()
        const error = options.error?.(response.status, code, metadata) ?? new Error(`请求失败（HTTP ${response.status}）`)
        error.message += `（请求 ${requestId}）`; knownError = error
        this.session.denied(ticket, response.status, error)
        throw error
      }
      if (!/^application\/json(?:\s*;|$)/i.test(response.headers.get('Content-Type') ?? '')) throw fail('响应不是 JSON')
      const text = await readBody(response, responseBytes, controller.signal)
      consumed = true
      current()
      try { return JSON.parse(text) as unknown } catch { throw fail('响应不是有效 JSON') }
    } catch (cause) {
      if (cause === knownError || cause instanceof TransportError) throw cause
      current()
      if (controller.signal.aborted) throw new DOMException('请求已取消', 'AbortError')
      throw fail('网络或响应读取失败，提交结果待确认')
    } finally { clearTimeout(timer); options.signal.removeEventListener('abort', cancel); if (!consumed) controller.abort(); unregister() }
  }
}
async function readBody(response: Response, limit: number, signal: AbortSignal): Promise<string> {
  const advertised = response.headers.get('Content-Length')
  if (advertised !== null && (!/^\d+$/.test(advertised) || Number(advertised) > limit)) { void response.body?.cancel().catch(() => {}); throw new Error('Response limit') }
  if (!response.body) throw new Error('Missing response body')
  const reader = response.body.getReader(); const buffer = new Uint8Array(limit)
  let size = 0; let completed = false
  const cancel = () => { void reader.cancel().catch(() => {}) }
  signal.addEventListener('abort', cancel, { once: true })
  try {
    while (true) {
      if (signal.aborted) throw new DOMException('请求已取消', 'AbortError')
      const { value, done } = await reader.read(); if (done) { completed = true; break }
      if (size + value.byteLength > limit) throw new Error('Response limit')
      buffer.set(value, size); size += value.byteLength
    }
    return new TextDecoder('utf-8', { fatal: true }).decode(buffer.subarray(0, size))
  } finally { signal.removeEventListener('abort', cancel); if (!completed) { void reader.cancel().catch(() => {}) } reader.releaseLock() }
}
export const platformClient = new JsonClient(platformCredentials, 'platform')
