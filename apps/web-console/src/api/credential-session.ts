// Browser request lifetime only. Authentication and authorization belong to Java.
export type SessionChange = { reason: 'credentials' | 'logout' | 'unauthenticated' | 'forbidden' | 'expired' | 'pagehide'; error?: Error }
export type CredentialTicket = { readonly token: string; readonly revision: number; readonly cookie?: boolean; readonly csrf?: string }
export type BrowserSession = { schemaVersion: '1.0'; mode: 'oidc'; dataMode: 'oidc' | 'oidc-protocol-test'; authenticated: boolean; csrfToken: string; sessionId: string | null; expiresAt: string | null;
  principal: { tenantId: string; subjectId: string; permissions: string[] } | null; loginPath: '/api/v1/auth/login/opsweave' }
export class CredentialSession {
  private credential = ''
  private revision = 0
  private deadline = 0
  private cookieMode = false
  private browserSession: BrowserSession | null = null
  private readonly listeners = new Set<(change: SessionChange) => void>()
  private readonly requests = new Set<AbortController>()
  token() { return this.credential }
  isCookie() { return this.cookieMode }
  browser() { return this.browserSession }
  ready() { return this.cookieMode ? this.browserSession?.authenticated === true : this.credential.length >= 32 && !/\s/.test(this.credential) }
  enableCookieMode() { this.cookieMode = true; this.clear() }
  acceptBrowserSession(value: BrowserSession) {
    if (!this.cookieMode) throw new Error('当前未启用浏览器会话')
    this.browserSession = value; this.deadline = value.authenticated ? Date.parse(value.expiresAt!) : 0
    this.invalidate({ reason: 'credentials' })
  }
  expiresAt() { return this.deadline }
  subscribe(listener: (change: SessionChange) => void) { this.listeners.add(listener); return () => { this.listeners.delete(listener) } }
  replace(value: string, now = Date.now()) {
    if (this.cookieMode) throw new Error('浏览器会话不接受开发凭据')
    if (value.length > 4096 || /[\r\n\0]/.test(value)) { this.clear(); throw new Error('开发凭据格式不正确') }
    if (value === this.credential) return
    this.credential = value; this.deadline = value ? now + 30 * 60 * 1000 : 0
    this.invalidate({ reason: 'credentials' })
  }
  clear(reason: 'logout' | 'unauthenticated' | 'expired' | 'pagehide' = 'logout') {
    this.credential = ''; this.browserSession = null; this.deadline = 0; this.invalidate({ reason })
  }
  expire(now = Date.now()) { if (this.deadline && now >= this.deadline) this.clear('expired') }
  capture(now = Date.now(), bootstrap = false): CredentialTicket {
    this.expire(now)
    if (this.cookieMode) {
      if (!bootstrap && !this.ready()) throw new Error('请先登录或读取当前会话')
      return { token: '', revision: this.revision, cookie: true, csrf: this.browserSession?.csrfToken }
    }
    if (this.credential.length < 32 || /\s/.test(this.credential)) throw new Error('请先输入有效长度的开发凭据')
    return { token: this.credential, revision: this.revision }
  }
  current(ticket: CredentialTicket) { return ticket.revision === this.revision && ticket.token === this.credential }
  register(ticket: CredentialTicket, controller: AbortController) {
    if (!this.current(ticket)) throw new DOMException('会话已变化', 'AbortError')
    if (this.requests.size >= 6) throw new Error('并发请求已达上限，请等待当前请求完成')
    this.requests.add(controller); return () => { this.requests.delete(controller) }
  }
  denied(ticket: CredentialTicket, status: number, error: Error) {
    if (!this.current(ticket) || ![401, 403].includes(status)) return
    if (status === 401) { this.credential = ''; this.browserSession = null; this.deadline = 0 }
    this.invalidate({ reason: status === 401 ? 'unauthenticated' : 'forbidden', error })
  }
  private invalidate(change: SessionChange) {
    ++this.revision
    for (const controller of this.requests) controller.abort()
    // Slots are released by request finally blocks, including response-body cancellation.
    for (const listener of this.listeners) listener(change)
  }
}
export const platformCredentials = new CredentialSession()
