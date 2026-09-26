import { expect, test } from '@playwright/test'
import { CredentialSession, type BrowserSession } from '../src/api/credential-session.ts'
import { JsonClient } from '../src/api/http.ts'
import { parseBrowserSession } from '../src/api/browser-session.ts'

const fixture = (authenticated = true): BrowserSession => ({ schemaVersion: '1.0', mode: 'oidc', dataMode: 'oidc-protocol-test', authenticated,
  csrfToken: 'a'.repeat(96), loginPath: '/api/v1/auth/login/opsweave', sessionId: authenticated ? '11111111-1111-4111-8111-111111111111' : null,
  expiresAt: authenticated ? new Date(Date.now() + 600000).toISOString() : null, principal: authenticated ? { tenantId: 'fixture', subjectId: 'operator', permissions: ['entity.read'] } : null })
const signal = () => new AbortController().signal

test('OIDC transport bootstraps only session metadata and never sends bearer or cookies to Runtime', async () => {
  const session = new CredentialSession(); session.enableCookieMode(); const calls: RequestInit[] = []
  const fetcher: typeof fetch = async (_url, init) => { calls.push(init!); return Response.json({ okay: true }) }
  const client = new JsonClient(session, 'platform', fetcher)
  await expect(client.request('/api/v1/entities', { signal: signal() })).rejects.toThrow('先登录')
  for (const path of ['/api/v1/entities', '/api/v1/auth/session?tenantId=other']) await expect(client.request(path, { bootstrap: true, signal: signal() })).rejects.toThrow('会话读取地址')
  await client.request('/api/v1/auth/session', { bootstrap: true, signal: signal() })
  expect(calls[0]).toMatchObject({ credentials: 'same-origin', mode: 'same-origin', redirect: 'error' })
  expect(calls[0]!.headers).not.toHaveProperty('Authorization'); expect(calls[0]!.headers).not.toHaveProperty('X-CSRF-TOKEN')
  session.acceptBrowserSession(fixture()); await client.request('/api/v1/auth/logout', { method: 'POST', signal: signal() })
  expect(calls[1]!.headers).toHaveProperty('X-CSRF-TOKEN', fixture().csrfToken); expect(calls[1]!.headers).not.toHaveProperty('Authorization')
  await expect(new JsonClient(session, 'fixture-demo', fetcher).request('/agent/api/v1/diagnoses', { signal: signal() })).rejects.toThrow('不能转发')
  expect(calls).toHaveLength(2); expect(session.token()).toBe(''); expect(() => session.replace('a'.repeat(32))).toThrow('不接受开发凭据')
})

test('OIDC metadata strictly separates anonymous identity and rejects remote login or malformed authorization', () => {
  const value = fixture()
  expect(parseBrowserSession(value)).toEqual(value); expect(parseBrowserSession(fixture(false)).authenticated).toBe(false)
  for (const bad of [{ ...value, token: 'secret' }, { ...value, loginPath: 'https://evil.invalid' }, { ...value, expiresAt: 'yesterday' },
    { ...value, expiresAt: new Date(Date.now() - 1).toISOString() }, { ...value, csrfToken: '\n' }, { ...value, principal: { ...value.principal, permissions: ['shell'] } },
    { ...value, principal: { ...value.principal, permissions: ['entity.read', 'entity.read'] } }, { ...value, authenticated: false }]) expect(() => parseBrowserSession(bad)).toThrow()
})

test('absolute cookie expiry and remote logout clear identity, cancel pending work, and reject stale responses', async () => {
  const session = new CredentialSession(); session.enableCookieMode(); const value = fixture(); session.acceptBrowserSession(value)
  const ticket = session.capture(); session.capture(Date.parse(value.expiresAt!) - 1); expect(session.current(ticket)).toBe(true)
  session.expire(Date.parse(value.expiresAt!)); expect(session.ready()).toBe(false); expect(session.current(ticket)).toBe(false)
  session.acceptBrowserSession(fixture()); let complete!: (response: Response) => void
  const client = new JsonClient(session, 'platform', async () => new Promise(resolve => { complete = resolve }))
  const pending = client.request('/api/v1/entities', { signal: signal() }); session.clear('unauthenticated'); complete(Response.json({ old: true }))
  await expect(pending).rejects.toThrow('会话已变化'); expect(session.browser()).toBe(null)
})

test('OIDC 401 clears login and 403 clears data without discarding confirmed identity', async () => {
  for (const status of [401, 403]) {
    const session = new CredentialSession(); session.enableCookieMode(); session.acceptBrowserSession(fixture()); const before = session.capture()
    const client = new JsonClient(session, 'platform', async () => Response.json({}, { status }))
    await expect(client.request('/api/v1/entities', { signal: signal() })).rejects.toThrow(`HTTP ${status}`)
    expect(session.ready()).toBe(status === 403); expect(session.current(before)).toBe(false)
  }
})
