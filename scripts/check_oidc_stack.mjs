// Local protocol acceptance only. Real Java/PG/VM/Rust/browser, explicit OIDC fixture + source fixture + model mock.
// No provider token, cookie, authorization code, client secret or delegation is printed or saved.
import assert from 'node:assert/strict'
import { generateKeyPairSync, randomBytes, createHash, sign } from 'node:crypto'
import { spawn } from 'node:child_process'
import { once } from 'node:events'
import { mkdir, readdir, writeFile } from 'node:fs/promises'
import { createRequire } from 'node:module'
import { createServer } from 'node:http'
import net from 'node:net'
import path from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { checkHistoryService } from './lib/check_history_service.mjs'

const root = fileURLToPath(new URL('../', import.meta.url)), webRoot = path.join(root, 'apps/web-console')
const requireWeb = createRequire(path.join(webRoot, 'package.json')), { chromium, expect } = requireWeb('@playwright/test')
const { build, preview } = await import(pathToFileURL(requireWeb.resolve('vite')).href)
const jdbc = process.env.OPSWEAVE_TEST_JDBC_URL, vm = process.env.OPSWEAVE_TEST_VM_URL
for (const value of [jdbc?.replace(/^jdbc:/, ''), vm]) {
  assert(value, 'Explicit owned test PG/VM configuration is required'); const url = new URL(value)
  assert(['127.0.0.1', '[::1]'].includes(url.hostname) && !url.username && !url.password, 'Test storage must be loopback')
}
assert(process.env.OPSWEAVE_TEST_JDBC_USER)
async function port() { const server = net.createServer(); server.listen(0, '127.0.0.1'); await once(server, 'listening'); const value = server.address().port; await new Promise(resolve => server.close(resolve)); return value }
const apiPort = await port(), webPort = await port(), runtimePort = await port()
const api = `http://127.0.0.1:${apiPort}`, origin = `http://127.0.0.1:${webPort}`, runtimeUrl = `http://127.0.0.1:${runtimePort}`
const tenant = `oidc-${randomBytes(8).toString('hex')}`, clientSecret = randomBytes(32).toString('hex'), runtimeKey = randomBytes(32).toString('hex')
const artifacts = path.join(root, '.tmp/oidc-acceptance'), outDir = path.join(root, '.tmp/web-oidc-dist')
const withHistoryService = process.argv.includes('--history-service')
const service = { secret: randomBytes(32).toString('hex'), fault: false, exchanges: 0, attempts: 0 }
await mkdir(artifacts, { recursive: true })
const grantsFile = path.join(artifacts, 'identity-grants.json')
const serviceGrantsFile = path.join(artifacts, 'history-service-grants.json')
if (withHistoryService) await writeFile(serviceGrantsFile, JSON.stringify({ schemaVersion: '1.0', grants: [] }))
const { privateKey, publicKey } = generateKeyPairSync('rsa', { modulusLength: 2048 })
const jwk = { ...publicKey.export({ format: 'jwk' }), kid: 'local-protocol-fixture', use: 'sig', alg: 'RS256' }
const codes = new Map(); let exchanges = 0, issuer
const json = (response, status, value) => { response.writeHead(status, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' }); response.end(JSON.stringify(value)) }
const idp = createServer(async (request, response) => {
  try {
    const url = new URL(request.url, issuer)
    if (request.method === 'GET' && url.pathname === '/jwks') return json(response, 200, { keys: [jwk] })
    if (request.method === 'GET' && url.pathname === '/authorize') {
      const q = url.searchParams
      assert.equal(q.get('client_id'), 'opsweave'); assert.equal(q.get('response_type'), 'code'); assert.equal(q.get('scope'), 'openid')
      assert.equal(q.get('redirect_uri'), origin + '/api/v1/auth/callback'); assert.equal(q.get('code_challenge_method'), 'S256')
      assert(q.get('state') && q.get('nonce') && q.get('code_challenge')); assert(codes.size < 16)
      const code = randomBytes(32).toString('hex'); codes.set(code, { nonce: q.get('nonce'), challenge: q.get('code_challenge'), expires: Date.now() + 60000 })
      const callback = new URL(q.get('redirect_uri')); callback.searchParams.set('code', code); callback.searchParams.set('state', q.get('state')); callback.searchParams.set('iss', issuer)
      response.writeHead(302, { Location: callback.href, 'Cache-Control': 'no-store' }); response.end(); return
    }
    if (request.method === 'POST' && url.pathname === '/token') {
      let input = ''; for await (const part of request) { input += part.toString(); assert(input.length <= 8192) }
      const form = new URLSearchParams(input)
      if (withHistoryService && form.get('grant_type') === 'client_credentials') {
        service.attempts++
        assert.equal(request.headers.authorization, 'Basic ' + Buffer.from('history-worker:' + service.secret).toString('base64'))
        assert.equal(input, 'grant_type=client_credentials&scope=opsweave.history.read')
        if (service.fault) return json(response, 503, { error: 'temporarily_unavailable' })
        const now = Math.floor(Date.now() / 1000), encode = value => Buffer.from(JSON.stringify(value)).toString('base64url')
        const signed = encode({ alg: 'RS256', typ: 'at+jwt', kid: jwk.kid }) + '.' + encode({ iss: issuer, sub: 'history-worker', client_id: 'history-worker', aud: 'opsweave-history',
          scope: 'opsweave.history.read', jti: randomBytes(16).toString('hex'), iat: now, exp: now + 60, tenantId: 'untrusted-service-claim', permissions: ['shell'] })
        service.exchanges++
        return json(response, 200, { token_type: 'Bearer', expires_in: 60, scope: 'opsweave.history.read', access_token: signed + '.' + sign('RSA-SHA256', Buffer.from(signed), privateKey).toString('base64url') })
      }
      assert.equal(request.headers.authorization, 'Basic ' + Buffer.from('opsweave:' + clientSecret).toString('base64'))
      const value = codes.get(form.get('code')); codes.delete(form.get('code'))
      assert(value && Date.now() < value.expires); assert.equal(form.get('grant_type'), 'authorization_code')
      assert.equal(form.get('redirect_uri'), origin + '/api/v1/auth/callback')
      assert.equal(createHash('sha256').update(form.get('code_verifier')).digest('base64url'), value.challenge)
      const now = Math.floor(Date.now() / 1000), encode = value => Buffer.from(JSON.stringify(value)).toString('base64url')
      const signed = encode({ alg: 'RS256', kid: jwk.kid }) + '.' + encode({ iss: issuer, sub: 'local-operator', aud: 'opsweave', iat: now - 1, exp: now + 600, nonce: value.nonce,
        tenantId: 'untrusted-claim', permissions: ['shell'] })
      exchanges++; return json(response, 200, { token_type: 'Bearer', access_token: 'fixture-token-never-forwarded', expires_in: 600,
        id_token: signed + '.' + sign('RSA-SHA256', Buffer.from(signed), privateKey).toString('base64url') })
    }
    json(response, 404, { error: 'unavailable' })
  } catch { json(response, 400, { error: 'invalid_request' }) }
})
idp.listen(0, '127.0.0.1'); await once(idp, 'listening'); issuer = `http://127.0.0.1:${idp.address().port}`
async function grant(enabled = true) { await writeFile(grantsFile, JSON.stringify({ schemaVersion: '1.0', grants: [{ issuer, externalSubject: 'local-operator', subjectId: 'operator', tenantId: tenant, revision: 1, enabled,
  permissions: ['entity.read', 'metric.read', 'source.sync', 'incident.read', 'ai.diagnose', 'evidence.read', 'ai.insight.read'], scope: { tenantWide: true, resources: [] } }] }, null, 2)) }
await grant()
const jarDir = path.join(root, 'apps/platform-api/build/libs'), jars = (await readdir(jarDir)).filter(n => n.endsWith('.jar') && !n.endsWith('-plain.jar')); assert.equal(jars.length, 1)
const java = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java') : 'java'
const platform = spawn(java, ['-jar', path.join(jarDir, jars[0]), '--server.address=127.0.0.1', `--server.port=${apiPort}`], { cwd: root, windowsHide: true, stdio: 'ignore', env: { ...process.env,
  OPSWEAVE_AUTH_MODE: 'oidc', OPSWEAVE_OIDC_LOOPBACK_TEST: 'true', OPSWEAVE_PUBLIC_ORIGIN: origin, OPSWEAVE_OIDC_ISSUER: issuer,
  OPSWEAVE_OIDC_AUTHORIZATION_URI: issuer + '/authorize', OPSWEAVE_OIDC_TOKEN_URI: issuer + '/token', OPSWEAVE_OIDC_JWK_SET_URI: issuer + '/jwks',
  OPSWEAVE_OIDC_CLIENT_ID: 'opsweave', OPSWEAVE_OIDC_CLIENT_SECRET: clientSecret, OPSWEAVE_IDENTITY_GRANTS_FILE: grantsFile,
  OPSWEAVE_HISTORY_SERVICE_ENABLED: String(withHistoryService), OPSWEAVE_HISTORY_SERVICE_LOOPBACK_TEST: 'true',
  OPSWEAVE_HISTORY_SERVICE_ISSUER: issuer, OPSWEAVE_HISTORY_SERVICE_JWK_SET_URI: issuer + '/jwks', OPSWEAVE_HISTORY_SERVICE_GRANTS_FILE: serviceGrantsFile,
  OPSWEAVE_INVENTORY_STORE: 'postgres', OPSWEAVE_JDBC_URL: jdbc, OPSWEAVE_JDBC_USER: process.env.OPSWEAVE_TEST_JDBC_USER, OPSWEAVE_JDBC_PASSWORD: process.env.OPSWEAVE_TEST_JDBC_PASSWORD ?? '',
  OPSWEAVE_VICTORIAMETRICS_URL: vm, OPSWEAVE_ZABBIX_MODE: 'fixture', OPSWEAVE_ZABBIX_SOURCE: 'zabbix-1', OPSWEAVE_RUNTIME_URL: runtimeUrl, OPSWEAVE_RUNTIME_KEY: runtimeKey, OPSWEAVE_PROVIDER: 'mock',
} })
const platformExit = once(platform, 'exit'), delay = ms => new Promise(resolve => setTimeout(resolve, ms))
let runtime, vite, browser
const bodies = new Map(), errors = [], checks = [], browserErrors = []; let bodyBytes = 0
function observe(proxy) {
  proxy.on('proxyRes', (upstream, request) => {
    const id = request.headers['x-opsweave-request-id']; if (!id) return // OAuth navigation has no client correlation header.
    assert(!bodies.has(id) && bodies.size < 128)
    let finish; const record = { body: new Promise(resolve => { finish = resolve }) }; bodies.set(id, record)
    const chunks = []; let size = 0
    upstream.on('data', chunk => { size += chunk.length; bodyBytes += chunk.length; if (size < 1048576 && bodyBytes < 8388608) chunks.push(chunk) })
    upstream.on('end', () => finish(upstream.complete && size < 1048576 && bodyBytes < 8388608 ? Buffer.concat(chunks) : null))
    upstream.on('error', () => finish(null))
  })
}
async function body(response) {
  const id = response.request().headers()['x-opsweave-request-id']; assert(bodies.has(id)); let timer
  try { const bytes = await Promise.race([bodies.get(id).body, new Promise((_, reject) => { timer = setTimeout(() => reject(new Error('Incomplete proxy response')), 18000) })]); assert(bytes); return JSON.parse(bytes.toString()) }
  finally { clearTimeout(timer) }
}
async function ready(url, process) {
  for (let i = 0; i < 90; i++) { assert.equal(process.exitCode, null, 'Owned service exited'); try { if ((await fetch(url, { signal: AbortSignal.timeout(1000) })).ok) return } catch { } await delay(1000) }
  throw new Error('Owned service readiness timed out')
}
try {
  await ready(api + '/actuator/health', platform)
  runtime = spawn(path.join(root, 'target/debug', process.platform === 'win32' ? 'opsweave-agent-runtime.exe' : 'opsweave-agent-runtime'), [], { cwd: root, windowsHide: true, stdio: 'ignore', env: { ...process.env,
    OPSWEAVE_MODE: 'platform-dev', OPSWEAVE_PROVIDER: 'mock', OPSWEAVE_LISTEN: `127.0.0.1:${runtimePort}`, OPSWEAVE_PLATFORM_URL: api, OPSWEAVE_RUNTIME_KEY: runtimeKey,
    OPSWEAVE_SKILL_DIR: path.join(root, 'extensions/skills/incident-diagnosis-current') } })
  await ready(runtimeUrl + '/readyz', runtime)
  await build({ root: webRoot, configFile: path.join(webRoot, 'vite.config.ts'), define: { 'import.meta.env.VITE_PLATFORM_AUTH': JSON.stringify('oidc') }, build: { outDir, emptyOutDir: true } })
  vite = await preview({ root: webRoot, configFile: path.join(webRoot, 'vite.config.ts'), build: { outDir }, preview: { host: '127.0.0.1', port: webPort, strictPort: true, proxy: { '/api': { target: api, configure: observe } } } })
  browser = await chromium.launch({ executablePath: process.env.OPSWEAVE_TEST_CHROMIUM_EXECUTABLE })
  const context = await browser.newContext({ viewport: { width: 1360, height: 1000 } }), page = await context.newPage()
  const business = []; let csrf
  function track(page) {
    page.on('pageerror', error => browserErrors.push(error.message))
    page.on('request', req => {
      const p = new URL(req.url()).pathname; if (!p.startsWith('/api/')) return
      assert(!req.headers().authorization, 'Browser must not receive a bearer credential')
      if (!p.startsWith('/api/v1/auth/')) business.push(p)
    })
    page.on('response', res => {
      const id = res.request().headers()['x-opsweave-request-id']; if (!id || !new URL(res.url()).pathname.startsWith('/api/')) return
      checks.push((async () => { const h = await res.allHeaders(); assert.equal(h['x-opsweave-request-id'], id); assert(h['cache-control'].includes('no-store')); assert.equal(h['x-content-type-options'], 'nosniff') })().catch(error => errors.push(error.message)))
    })
  }
  track(page)
  async function login() { await page.getByRole('link', { name: '通过身份提供方登录' }).click(); await expect(page.locator('[data-oidc-session]')).toContainText(`用户 operator · 租户 ${tenant}`) }
  async function apiRequest(url, method = 'GET', data) {
    const response = await context.request.fetch(origin + url, { method, data, headers: { Origin: origin, ...(csrf ? { 'X-CSRF-TOKEN': csrf } : {}) }, timeout: 80000 })
    assert.equal(response.status(), 200, `Protocol acceptance ${method} ${url} status`); return response.json()
  }
  await page.goto(origin + '/#/inventory'); await expect(page.getByRole('link', { name: '通过身份提供方登录' })).toBeVisible(); assert.deepEqual(business, [])
  await login(); assert.equal(exchanges, 1); assert.deepEqual(business, [])
  const current = await apiRequest('/api/v1/auth/session'); csrf = current.csrfToken
  assert.equal(current.dataMode, 'oidc-protocol-test'); assert.equal(current.principal.tenantId, tenant); assert(!current.principal.permissions.includes('shell'))
  const cookies = await context.cookies(); assert.equal(cookies.length, 1); assert.equal(cookies[0].name, 'opsweave-test-session'); assert(cookies[0].httpOnly); assert.equal(cookies[0].sameSite, 'Lax')
  assert.equal(await page.evaluate(() => document.cookie), ''); assert.equal(await page.evaluate(() => localStorage.length + sessionStorage.length), 0)
  await writeFile(path.join(artifacts, 'browser-session.json'), JSON.stringify({ ...current, csrfToken: 'redacted-protocol-fixture-token-00000000' }, null, 2))
  console.log('PASS: Browser authorization-code + S256 PKCE/nonce -> Java verified JWT -> operator grant; HttpOnly cookie and metadata only, no automatic business reads.')
  await apiRequest('/api/v1/integrations/zabbix/hosts/sync', 'POST'); await apiRequest('/api/v1/integrations/zabbix/items/sync', 'POST')
  const entities = await apiRequest('/api/v1/entities'), entity = entities.items.find(e => e.attributes.hostId === '10084'); assert(entity)
  if (withHistoryService) await checkHistoryService({ root, java, api, jdbc, vm, tenant, entity: entity.id, issuer, service, grantsFile: serviceGrantsFile, artifacts, browserContext: context, browserOrigin: origin })
  const till = Math.floor(Date.now() / 1000), labels = `tenant_id=${tenant},entity_id=${entity.id},metric_key=host.cpu.usage.user,unit=1,mapping_revision=1,data_mode=labeled-fixture,dimension_mode=user,source_instance_id=zabbix-1,external_item_id=20001`
  const points = [0.31, 0.4].map((value, i) => `opsweave_metric,${labels} value=${value} ${(till - 10 + i * 5) * 1000}`).join('\n') + '\n'
  assert.equal((await fetch(new URL('/write?precision=ms', vm), { method: 'POST', body: points, signal: AbortSignal.timeout(10000) })).status, 204)
  let visible = false
  for (let attempt = 0; attempt < 15; attempt++) {
    const series = await apiRequest(`/api/v1/entities/${entity.id}/metrics/host.cpu.usage.user/series?from=${till - 900}&till=${till}&maxPoints=500`)
    if (series.series.some(row => row.points.some(point => point[0] === (till - 5) * 1000 && Number(point[1]) === 0.4))) { visible = true; break }
    await delay(2000)
  }
  assert(visible, 'Owned synthetic VM samples must be visible before browser reads')
  await apiRequest('/api/v1/integrations/zabbix/problems/ingest', 'POST', { from: Date.parse('2026-09-21T12:00:00Z') / 1000, till: Date.parse('2026-09-21T13:00:00Z') / 1000, afterEventId: null, limit: 100 })
  let incident
  for (const row of (await apiRequest('/api/v1/incidents?limit=20')).items) { const d = await apiRequest('/api/v1/incidents/' + row.id); if (d.record.problems.some(p => p.entities.some(e => e.entityId === entity.id))) { incident = row.id; break } }
  assert(incident)
  await page.getByRole('link', { name: '指标', exact: true }).click(); await page.getByRole('button', { name: '读取资产和指标', exact: true }).click()
  await page.getByRole('combobox', { name: 'Host', exact: true }).selectOption(entity.id)
  const chartResponse = page.waitForResponse(res => res.url().includes('/series?'))
  await page.getByRole('button', { name: '查询', exact: true }).click(); assert.equal((await chartResponse).status(), 200)
  await page.getByRole('img', { name: '指标曲线' }).waitFor()
  await page.goto(origin + '/#/incidents/current-diagnose?incidentId=' + incident)
  await expect(page.locator('[data-oidc-session]')).toContainText(`用户 operator · 租户 ${tenant}`)
  const timeRange = { from: new Date((till - 900) * 1000).toISOString(), to: new Date(till * 1000).toISOString() }
  await page.getByRole('textbox', { name: '采样开始（UTC）' }).fill(timeRange.from); await page.getByRole('textbox', { name: '采样结束（UTC）' }).fill(timeRange.to)
  const diagnosis = page.waitForResponse(res => res.url().endsWith('/api/v1/ai/diagnoses'), { timeout: 80000 })
  await page.getByRole('button', { name: '开始只读诊断', exact: true }).click(); const response = await diagnosis
  assert.equal(response.status(), 200, 'OIDC delegated diagnosis'); const saved = await body(response)
  assert.equal(saved.storage, 'postgres'); assert.equal(saved.record.tenantId, tenant); assert.equal(saved.record.model.provider, 'mock-deterministic'); assert.equal(saved.record.evidenceIds.length, 2)
  await page.locator('[data-insight-result]').waitFor()
  const spendResponse = page.waitForResponse(res => res.url().includes('/api/v1/ai/model-calls/'))
  await page.getByRole('button', { name: '读取用量与费用', exact: true }).click()
  const spend = await body(await spendResponse); assert.equal(spend.storage, 'postgres'); assert.equal(spend.record.runId, saved.record.id)
  assert.equal(spend.record.state, 'REPORTED'); assert.equal(spend.record.usage.source, 'mock-no-call'); assert.equal(spend.record.accountedMicros, 0)
  await page.locator('[data-model-spend]').waitFor(); await writeFile(path.join(artifacts, 'model-spend-result.json'), JSON.stringify(spend, null, 2))
  console.log('PASS: OIDC bounded delegation admits one model reservation/report; browser reads durable explicit mock usage with its own live session.')
  await writeFile(path.join(artifacts, 'ai-insight-result.json'), JSON.stringify(saved, null, 2))
  await page.screenshot({ path: path.join(artifacts, 'oidc-insight.png'), fullPage: true })
  console.log('PASS: OIDC cookie + CSRF -> Java -> short-lived bounded Runtime delegation -> Rust mock -> real PG/VM tools/evidence -> PostgreSQL AIInsight.')
  const before = business.length; await page.reload(); await expect(page.locator('[data-oidc-session]')).toContainText(`用户 operator · 租户 ${tenant}`)
  assert.equal(business.length, before); assert.equal(await page.locator('[data-insight-result]').count(), 0)
  const restored = page.waitForResponse(res => res.url().includes('/api/v1/ai/insights/')); await page.getByRole('button', { name: '读取已保存结果', exact: true }).click()
  assert.deepEqual(await body(await restored), saved)
  const evidence = page.waitForResponse(res => res.url().includes('/api/v1/ai/evidence/')); await page.getByRole('button', { name: '读取快照 ' + saved.record.evidenceIds[1], exact: true }).click()
  assert.equal((await body(await evidence)).data.sampleCount, 2)
  console.log('PASS: Refresh restores server session metadata without reading data; explicit result/evidence reads reauthorize and recover persisted mock diagnosis.')
  const tab = await context.newPage(); track(tab); await tab.goto(page.url()); await expect(tab.getByRole('button', { name: '读取已保存结果', exact: true })).toBeEnabled()
  await tab.getByRole('button', { name: '读取已保存结果', exact: true }).click(); await tab.locator('[data-insight-result]').waitFor()
  const logoutResponse = page.waitForResponse(res => res.url().endsWith('/api/v1/auth/logout'))
  await page.getByRole('button', { name: '退出平台登录', exact: true }).click(); await expect(page.getByRole('link', { name: '通过身份提供方登录' })).toBeVisible()
  const loggedOut = await body(await logoutResponse); assert.deepEqual(loggedOut, { loggedOut: true })
  await writeFile(path.join(artifacts, 'browser-logout.json'), JSON.stringify(loggedOut, null, 2))
  await expect(tab.locator('[data-insight-result]')).toHaveCount(0); await expect(tab.getByRole('button', { name: '读取已保存结果', exact: true })).toBeDisabled()
  const denied = await context.request.get(origin + '/api/v1/ai/insights/' + saved.record.id); assert.equal(denied.status(), 401)
  console.log('PASS: Confirmed server logout invalidates the cookie session, clears both tabs and rejects retained result URLs.')
  await login(); await grant(false)
  const revoked = page.waitForResponse(res => res.url().endsWith('/api/v1/auth/session')); await page.getByRole('button', { name: '读取当前会话', exact: true }).click(); assert.equal((await revoked).status(), 401)
  await expect(page.locator('[data-oidc-session]')).toContainText('HTTP 401'); await expect(page.getByRole('button', { name: '退出平台登录' })).toHaveCount(0)
  assert.deepEqual(browserErrors, []); await Promise.all(checks); assert.deepEqual(errors, [])
  console.log(`PASS: Operator revocation is effective on the next request; ${checks.length} browser correlation/no-store/nosniff checks passed; no provider fallback.`)
} finally {
  runtime?.kill(); await browser?.close(); if (vite) await new Promise(resolve => vite.httpServer.close(resolve))
  platform.kill(); await Promise.race([platformExit, delay(10000)]); idp.closeAllConnections(); await new Promise(resolve => idp.close(resolve))
}
