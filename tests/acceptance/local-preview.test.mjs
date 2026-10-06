// Explicit local protocol fixtures. No production identity or real model calls.
import test from 'node:test'
import assert from 'node:assert/strict'
import { createServer, request } from 'node:http'
import { createLocalSessionBridge, localSessionPlugin } from '../../apps/web-console/dev/local-session.mjs'
import { CredentialSession } from '../../apps/web-console/src/api/credential-session.ts'
import { JsonClient } from '../../apps/web-console/src/api/http.ts'

const token = 'fixture-server-credential-'.repeat(3)
const ttl = 30 * 60 * 1000
async function fixture(t, options) {
  let forwarded
  const bridge = createLocalSessionBridge(token, options)
  const server = createServer((req, res) => bridge(req, res, () => {
    forwarded = { auth: req.headers.authorization, nonce: req.headers['x-opsweave-local-session'] }
    res.writeHead(200, { 'Content-Type': 'application/json' }); res.end('{"forwarded":true}')
  }))
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
  t.after(() => { server.closeAllConnections(); return new Promise(resolve => server.close(resolve)) })
  const url = `http://127.0.0.1:${server.address().port}`
  // Raw HTTP lets the negative tests send a forged Host; fetch normalizes it.
  const call = (path, headers = {}, method = 'GET') => new Promise((resolve, reject) => {
    const req = request(url + path, { method, headers }, res => {
      const chunks = []
      res.on('data', chunk => chunks.push(chunk))
      res.on('error', reject)
      res.on('end', () => resolve(new Response(Buffer.concat(chunks), { status: res.statusCode, headers: res.headers })))
    })
    req.on('error', reject); req.end()
  })
  const bootstrap = () => call('/__opsweave/local-session', { 'X-OpsWeave-Local-Session': 'bootstrap' })
  return { call, bootstrap, url, forwarded: () => forwarded }
}

test('local bridge issues a short session and keeps the backend bearer server-side', async t => {
  const f = await fixture(t)
  const missing = await f.call('/api/v1/entities/page')
  assert.equal(missing.status, 401)
  const res = await f.bootstrap(), body = await res.json()
  assert.equal(res.status, 200)
  assert.equal(res.headers.get('cache-control'), 'no-store')
  assert.equal(res.headers.get('set-cookie'), null)
  assert.deepEqual(Object.keys(body).sort(), ['expiresAt', 'sessionNonce'])
  assert.match(body.sessionNonce, /^[A-Za-z0-9_-]{43}$/)
  assert.ok(Date.parse(body.expiresAt) > Date.now() && Date.parse(body.expiresAt) <= Date.now() + ttl)
  assert.ok(!JSON.stringify(body).includes(token))
  const result = await f.call('/api/v1/entities/page', { 'X-OpsWeave-Local-Session': body.sessionNonce })
  assert.equal(result.status, 200)
  assert.deepEqual(f.forwarded(), { auth: `Bearer ${token}`, nonce: undefined })
  assert.ok(!(await result.text()).includes(token))
  assert.notEqual((await (await f.bootstrap()).json()).sessionNonce, body.sessionNonce)
})

test('cross-site, rebound host, missing header and bearer override fail closed', async t => {
  const f = await fixture(t)
  const base = { 'X-OpsWeave-Local-Session': 'bootstrap' }
  for (const headers of [{}, { ...base, Origin: 'https://fixture-attacker.invalid' }, { ...base, Host: 'fixture-rebound.invalid' },
    { ...base, 'Sec-Fetch-Site': 'cross-site' }, { ...base, 'Sec-Fetch-Site': 'same-site' }, { ...base, Authorization: 'Bearer fixture-override' }]) {
    const res = await f.call('/__opsweave/local-session', headers)
    assert.equal(res.status, 403)
    assert.ok(!(await res.text()).includes(token))
  }
  assert.equal((await f.call('/__opsweave/local-session', base, 'POST')).status, 403)
  assert.equal((await f.call('/__opsweave/local-session?extra=1', base)).status, 403)
  const { sessionNonce } = await (await f.bootstrap()).json()
  const headers = { 'X-OpsWeave-Local-Session': sessionNonce }
  assert.equal((await f.call('/api/v1/entities/page', { ...headers, Origin: 'https://fixture-attacker.invalid' })).status, 403)
  assert.equal((await f.call('/api/v1/entities/page', { ...headers, Authorization: 'Bearer fixture-override' })).status, 403)
  assert.equal((await f.call('/api/other', headers)).status, 403)
  assert.equal((await f.call('/api/v2/other', headers)).status, 403)
  assert.equal((await f.call('/api/v2/data-sources', headers)).status, 200)
  assert.equal((await f.call('/api/v2/data-sources/10000000-0000-4000-8000-000000000071', headers, 'PATCH')).status, 200)
  assert.equal((await f.call('/api/v2/metric-bindings', headers)).status, 200)
  assert.equal((await f.call('/api/v2/metric-bindings/fixture-source/20001/mapping', headers, 'POST')).status, 200)
  assert.equal((await f.call('/api/v2/metric-bindings/fixture-source/20001/mapping/commands/10000000-0000-4000-8000-000000000071', headers)).status, 200)
  assert.equal((await f.call('/api/v2/metric-bindings-other', headers)).status, 403)
  await f.call('/agent/api/v1/diagnose', headers)
  assert.equal(f.forwarded().auth, undefined)
})

test('session expiry and capacity are bounded without eviction of active sessions', async t => {
  let now = Date.now()
  const f = await fixture(t, { now: () => now, limit: 2 })
  const first = await (await f.bootstrap()).json()
  await f.bootstrap()
  assert.equal((await f.bootstrap()).status, 429)
  assert.equal((await f.call('/api/v1/entities/page', { 'X-OpsWeave-Local-Session': first.sessionNonce })).status, 200)
  now += ttl
  assert.equal((await f.call('/api/v1/entities/page', { 'X-OpsWeave-Local-Session': first.sessionNonce })).status, 401)
  assert.equal((await f.bootstrap()).status, 200)
})

test('bridge refuses invalid credentials and non-loopback Vite configuration', () => {
  for (const bad of ['short', 'REPLACE_' + 'x'.repeat(32), token + '\n']) assert.throws(() => createLocalSessionBridge(bad))
  const plugin = localSessionPlugin(token)
  assert.equal(plugin.apply, 'serve')
  for (const host of ['0.0.0.0', true, 'localhost']) assert.throws(() => plugin.configureServer({ config: { server: { host } } }))
  assert.throws(() => plugin.configureServer({ config: { server: { host: '127.0.0.1', https: {} } } }))
})

const json = value => new Response(JSON.stringify(value), { headers: { 'Content-Type': 'application/json' } })
test('browser transport uses only the local nonce, fixed bootstrap and no cookies', async () => {
  const session = new CredentialSession(); session.enableLocalPreviewMode()
  const calls = []
  const client = new JsonClient(session, 'platform', async (path, options) => { calls.push({ path, options }); return json({ ok: true }) })
  const signal = new AbortController().signal
  await client.request('/__opsweave/local-session', { signal, bootstrap: true })
  assert.equal(calls[0].options.headers['X-OpsWeave-Local-Session'], 'bootstrap')
  const nonce = 'n'.repeat(43)
  session.acceptLocalSession(nonce, new Date(Date.now() + ttl).toISOString())
  await client.request('/api/v1/entities/page', { signal })
  assert.equal(calls[1].options.headers['X-OpsWeave-Local-Session'], nonce)
  assert.equal(calls[1].options.headers.Authorization, undefined)
  assert.equal(calls[1].options.credentials, 'omit')
  for (const path of ['/api/v1/auth/session', '/__opsweave/local-session?extra=1', 'https://fixture.invalid/__opsweave/local-session']) {
    await assert.rejects(client.request(path, { signal, bootstrap: true }))
  }
  await assert.rejects(client.request('/__opsweave/local-session', { signal, bootstrap: true, method: 'POST' }))
  await assert.rejects(new JsonClient(session, 'fixture-demo', async () => { throw new Error('must not fetch') }).request('/agent/api/v1/diagnose', { signal }))
  assert.equal(calls.length, 2)
})

test('local identity cannot be replaced manually and expiry aborts stale work', () => {
  const session = new CredentialSession(); session.enableLocalPreviewMode()
  const now = Date.now(), nonce = 'n'.repeat(43)
  assert.throws(() => session.replace(token))
  assert.throws(() => session.acceptLocalSession(nonce, new Date(now + ttl + 1).toISOString(), now))
  assert.throws(() => session.acceptLocalSession('invalid', new Date(now + ttl).toISOString(), now))
  session.acceptLocalSession(nonce, new Date(now + ttl).toISOString(), now)
  const ticket = session.capture(now), active = new AbortController()
  session.register(ticket, active)
  session.expire(now + ttl)
  assert.equal(session.ready(), false)
  assert.equal(active.signal.aborted, true)
  assert.equal(session.current(ticket), false)
})

test('401 clears local session; a response after clear cannot restore old data', async () => {
  const session = new CredentialSession(); session.enableLocalPreviewMode()
  session.acceptLocalSession('n'.repeat(43), new Date(Date.now() + ttl).toISOString())
  const denied = new JsonClient(session, 'platform', async () => new Response('', { status: 401 }))
  await assert.rejects(denied.request('/api/v1/entities/page', { signal: new AbortController().signal }))
  assert.equal(session.ready(), false)
  session.acceptLocalSession('m'.repeat(43), new Date(Date.now() + ttl).toISOString())
  let resolve
  const delayed = new JsonClient(session, 'platform', () => new Promise(r => { resolve = r }))
  const pending = delayed.request('/api/v1/entities/page', { signal: new AbortController().signal })
  session.clear('pagehide')
  resolve(json({ stale: true }))
  await assert.rejects(pending, { name: 'AbortError' })
  assert.equal(session.ready(), false)
})
