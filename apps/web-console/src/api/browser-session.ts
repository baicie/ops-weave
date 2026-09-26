import { platformCredentials, type BrowserSession } from './credential-session.ts'
import { platformClient } from './http.ts'

const permissions = ['entity.read', 'entity.manage', 'metric.read', 'incident.read', 'incident.manage', 'evidence.read', 'ai.diagnose', 'ai.insight.read', 'ai.retention.manage', 'skill.read', 'skill.manage', 'source.sync']
export function parseBrowserSession(value: unknown): BrowserSession {
  const exact = (v: unknown, keys: string[]): v is Record<string, unknown> => v !== null && typeof v === 'object' && !Array.isArray(v) && Object.keys(v).length === keys.length && keys.every(k => k in v)
  if (!exact(value, ['schemaVersion', 'mode', 'dataMode', 'authenticated', 'csrfToken', 'sessionId', 'expiresAt', 'principal', 'loginPath'])
    || value.schemaVersion !== '1.0' || value.mode !== 'oidc' || !['oidc', 'oidc-protocol-test'].includes(String(value.dataMode)) || typeof value.authenticated !== 'boolean'
    || typeof value.csrfToken !== 'string' || !/^[A-Za-z0-9_-]{32,254}={0,2}$/.test(value.csrfToken) || value.loginPath !== '/api/v1/auth/login/opsweave') throw new Error('登录会话响应不正确')
  if (value.authenticated) {
    if (typeof value.sessionId !== 'string' || !/^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(value.sessionId)
      || typeof value.expiresAt !== 'string' || !Number.isFinite(Date.parse(value.expiresAt)) || Date.parse(value.expiresAt) <= Date.now()
      || !exact(value.principal, ['subjectId', 'tenantId', 'permissions'])
      || ![value.principal.subjectId, value.principal.tenantId].every(v => typeof v === 'string' && /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/.test(v))
      || !Array.isArray(value.principal.permissions) || value.principal.permissions.length > permissions.length || new Set(value.principal.permissions).size !== value.principal.permissions.length
      || !value.principal.permissions.every(p => permissions.includes(String(p)))) throw new Error('登录会话身份或有效期不正确')
  } else if (value.sessionId !== null || value.expiresAt !== null || value.principal !== null) throw new Error('未登录响应包含身份')
  return value as BrowserSession
}
export async function readBrowserSession(signal: AbortSignal) {
  const value = parseBrowserSession(await platformClient.request('/api/v1/auth/session', { signal, bootstrap: true, responseBytes: 8192, timeoutMs: 10000 }))
  platformCredentials.acceptBrowserSession(value); return value
}
export async function logoutBrowserSession(signal: AbortSignal) {
  const response = await platformClient.request('/api/v1/auth/logout', { signal, method: 'POST', responseBytes: 1024, timeoutMs: 10000 })
  if (!response || typeof response !== 'object' || Object.keys(response).length !== 1 || !('loggedOut' in response) || response.loggedOut !== true) throw new Error('退出结果待确认，请重试')
  platformCredentials.clear()
}
