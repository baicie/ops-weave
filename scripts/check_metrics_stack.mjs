// Development acceptance only: real Java HTTP + PostgreSQL + VictoriaMetrics + browser,
// with explicitly synthetic source data. No external Zabbix or model calls.
import assert from 'node:assert/strict'
import { randomBytes } from 'node:crypto'
import { spawn } from 'node:child_process'
import { once } from 'node:events'
import { mkdir, readdir, writeFile, unlink } from 'node:fs/promises'
import { createRequire } from 'node:module'
import net from 'node:net'
import path from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { checkAssetIdentity } from './lib/check_asset_identity.mjs'
import { checkSourceSnapshot } from './lib/check_source_snapshot.mjs'
import { checkSourceBindingCorrection } from './lib/check_source_binding_correction.mjs'
import { checkSourceScanRuns } from './lib/check_source_scan_runs.mjs'
import { checkSourceConnection } from './lib/check_source_connection.mjs'

const root = fileURLToPath(new URL('../', import.meta.url))
const webRoot = path.join(root, 'apps/web-console')
const requireWeb = createRequire(path.join(webRoot, 'package.json'))
const { chromium, expect } = requireWeb('@playwright/test')
const { preview } = await import(pathToFileURL(requireWeb.resolve('vite')).href)
const vm = process.env.OPSWEAVE_TEST_VM_URL
const jdbc = process.env.OPSWEAVE_TEST_JDBC_URL
for (const target of [vm, jdbc?.replace(/^jdbc:/, '')]) {
  assert(target, 'Explicit OPSWEAVE_TEST_VM_URL and OPSWEAVE_TEST_JDBC_URL are required')
  const url = new URL(target)
  assert(['127.0.0.1', '[::1]'].includes(url.hostname) && !url.username && !url.password, 'Only explicit loopback test storage is allowed')
}
assert(process.env.OPSWEAVE_TEST_JDBC_USER, 'Explicit test database user is required')
const token = randomBytes(32).toString('hex')
// A fixed tenant for this owned process, unique per acceptance run to avoid reusing lifecycle state.
const tenant = `acceptance-${randomBytes(8).toString('hex')}`
await mkdir(path.join(root, '.tmp'), { recursive: true })
const retentionPolicies = path.join(root, '.tmp', `${tenant}-retention-policies.json`)
await writeFile(retentionPolicies, JSON.stringify({ schemaVersion: '1.0', policies: [{ tenantId: tenant, version: 'acceptance-fixture-v1', insightDays: 30, evidenceDays: 7, auditDays: 90, batchSize: 100, heldIncidents: [], allowPurge: true }] }))
const jarDir = path.join(root, 'apps/platform-api/build/libs')
const jars = (await readdir(jarDir)).filter(name => name.endsWith('.jar') && !name.endsWith('-plain.jar'))
assert.equal(jars.length, 1, 'Build platform-api:bootJar first')
const probe = net.createServer()
probe.listen(0, '127.0.0.1')
await once(probe, 'listening')
const port = probe.address().port
await new Promise(resolve => probe.close(resolve))
const platformUrl = `http://127.0.0.1:${port}`
const runtimeProbe = net.createServer(); runtimeProbe.listen(0, '127.0.0.1'); await once(runtimeProbe, 'listening')
const runtimePort = runtimeProbe.address().port; await new Promise(resolve => runtimeProbe.close(resolve))
const runtimeUrl = `http://127.0.0.1:${runtimePort}`
const runtimeKey = randomBytes(32).toString('hex')
const java = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java') : 'java'
const platform = spawn(java, ['-jar', path.join(jarDir, jars[0]), '--server.address=127.0.0.1', `--server.port=${port}`], {
  cwd: root, windowsHide: true, stdio: 'ignore', env: {
    ...process.env,
    OPSWEAVE_AUTH_MODE: 'dev', OPSWEAVE_DEV_TOKEN: token, OPSWEAVE_DEV_TENANT: tenant,
    OPSWEAVE_DEV_PERMISSIONS: 'entity.read,entity.manage,metric.read,source.sync,incident.read,incident.manage,ai.diagnose,evidence.read,ai.insight.read,ai.retention.manage', OPSWEAVE_DEV_ENTITY_IDS: '',
    OPSWEAVE_AI_RETENTION_POLICIES_FILE: retentionPolicies,
    OPSWEAVE_CMDB_IMPORT_SOURCE: 'cmdb-import-dev',
    OPSWEAVE_IDENTITY_NAMESPACE: 'acceptance-assets',
    OPSWEAVE_RUNTIME_URL: process.argv.includes('--runtime') ? runtimeUrl : '', OPSWEAVE_RUNTIME_KEY: runtimeKey, OPSWEAVE_PROVIDER: 'mock',
    OPSWEAVE_INVENTORY_STORE: 'postgres', OPSWEAVE_JDBC_URL: jdbc,
    OPSWEAVE_JDBC_USER: process.env.OPSWEAVE_TEST_JDBC_USER,
    OPSWEAVE_JDBC_PASSWORD: process.env.OPSWEAVE_TEST_JDBC_PASSWORD ?? '',
    OPSWEAVE_ZABBIX_MODE: 'fixture', OPSWEAVE_ZABBIX_SOURCE: 'zabbix-1', OPSWEAVE_VICTORIAMETRICS_URL: vm,
  },
})
const exited = once(platform, 'exit')
const delay = ms => new Promise(resolve => setTimeout(resolve, ms))
let browser
let vite
let runtime
// Record the actual proxy response by the browser's unique request ID. Chromium can
// discard CDP response bodies after a Fetch stream is fully consumed. This observes
// the existing request; it neither sends a second request nor changes the response.
const browserBodies = new Map()
let recordedBytes = 0
function observeBrowserResponses(proxy) {
  proxy.on('proxyRes', (upstream, incoming) => {
    const id = incoming.headers['x-opsweave-request-id']
    assert.equal(typeof id, 'string'); assert(!browserBodies.has(id)); assert(browserBodies.size < 256)
    let finish
    const record = { path: incoming.url, status: upstream.statusCode, body: new Promise(resolve => { finish = resolve }) }
    browserBodies.set(id, record)
    let size = 0, overflow = false, ended = false; const chunks = []
    upstream.on('data', chunk => {
      size += chunk.length; recordedBytes += chunk.length
      if (size > 2 * 1024 * 1024 || recordedBytes > 16 * 1024 * 1024) { overflow = true; return }
      chunks.push(chunk)
    })
    upstream.on('end', () => { ended = true; finish({ complete: upstream.complete, overflow, data: Buffer.concat(chunks) }) })
    upstream.on('error', () => finish({ complete: false }))
    upstream.on('close', () => { if (!ended) finish({ complete: false }) })
  })
}
async function browserJson(response) {
  const id = response.request().headers()['x-opsweave-request-id']
  assert.equal(await response.headerValue('x-opsweave-request-id'), id)
  const recorded = browserBodies.get(id); assert(recorded, 'Browser response must match an observed proxy request')
  const url = new URL(response.url())
  assert.equal(recorded.path, url.pathname + url.search); assert.equal(recorded.status, response.status())
  let timer
  try {
    const value = await Promise.race([recorded.body, new Promise((_, reject) => { timer = setTimeout(() => reject(new Error('Proxy response did not complete')), 18000) })])
    assert(value.complete && !value.overflow, 'Proxy response must finish within the byte budget')
    return JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(value.data))
  } finally { clearTimeout(timer) }
}
async function request(pathname, method = 'GET', body, headers = {}) {
  const response = await fetch(platformUrl + pathname, {
    method, headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json', ...headers },
    body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(18000),
  })
  assert.equal(response.status, 200, `${method} ${pathname} failed with ${response.status}`)
  return response.json()
}
try {
  let ready = false
  for (let attempt = 0; attempt < 90; attempt++) {
    assert.equal(platform.exitCode, null, 'Platform exited before readiness')
    try {
      const response = await fetch(platformUrl + '/actuator/health', { signal: AbortSignal.timeout(1000) })
      if (response.ok) { ready = true; break }
    } catch { /* bounded startup wait */ }
    await delay(1000)
  }
  assert(ready, 'Platform readiness timed out')
  if (process.argv.includes('--runtime')) {
    runtime = spawn(path.join(root, 'target/debug', process.platform === 'win32' ? 'opsweave-agent-runtime.exe' : 'opsweave-agent-runtime'), [], {
      cwd: root, windowsHide: true, stdio: ['ignore', 'ignore', 'pipe'], env: { ...process.env, OPSWEAVE_MODE: 'platform-dev', OPSWEAVE_PROVIDER: 'mock',
        OPSWEAVE_LISTEN: `127.0.0.1:${runtimePort}`, OPSWEAVE_PLATFORM_URL: platformUrl, OPSWEAVE_RUNTIME_KEY: runtimeKey,
        OPSWEAVE_SKILL_DIR: path.join(root, 'extensions/skills/incident-diagnosis-current') },
    })
    // Startup failures are reported on stderr; keep the tail so a non-zero exit is diagnosable.
    let runtimeStderr = ''
    runtime.stderr.setEncoding('utf8')
    runtime.stderr.on('data', chunk => { runtimeStderr = (runtimeStderr + chunk).slice(-8192) })
    let runtimeReady = false
    for (let attempt = 0; attempt < 30; attempt++) {
      assert.equal(runtime.exitCode, null, `Runtime exited before readiness with code ${runtime.exitCode}: ${runtimeStderr.trim()}`)
      try { if ((await fetch(runtimeUrl + '/readyz', { signal: AbortSignal.timeout(1000) })).ok) { runtimeReady = true; break } } catch { /* bounded startup wait */ }
      await delay(1000)
    }
    assert(runtimeReady, `Runtime readiness timed out: ${runtimeStderr.trim()}`)
  }
  const hostSync = await request('/api/v1/integrations/zabbix/hosts/sync', 'POST')
  assert.equal(hostSync.dataMode, 'labeled-fixture')
  assert.equal((await request('/api/v1/integrations/zabbix/items/sync', 'POST')).dataMode, 'labeled-fixture')
  const entities = await request('/api/v1/entities')
  const entity = entities.items.find(item => item.attributes.hostId === '10084')
  assert(entity, 'Fixture host must exist')
  const till = Math.floor(Date.now() / 1000)
  const labels = `tenant_id=${tenant},entity_id=${entity.id},metric_key=host.cpu.usage.user,unit=1,mapping_revision=1,data_mode=labeled-fixture,dimension_mode=user`
  const points = [
    `opsweave_metric,${labels},source_instance_id=zabbix-1,external_item_id=20001 value=0.31 ${(till - 10) * 1000}`,
    `opsweave_metric,${labels},source_instance_id=zabbix-1,external_item_id=20001 value=0.4 ${(till - 5) * 1000}`,
  ].join('\n') + '\n'
  const written = await fetch(new URL('/write?precision=ms', vm), {
    method: 'POST', body: points, headers: { 'Content-Type': 'text/plain' }, signal: AbortSignal.timeout(10000),
  })
  assert.equal(written.status, 204)
  const seriesPath = `/api/v1/entities/${entity.id}/metrics/host.cpu.usage.user/series?from=${till - 900}&till=${till}&maxPoints=500`
  let series
  for (let attempt = 0; attempt < 15; attempt++) {
    series = await request(seriesPath)
    if (series.series.some(row => row.points.some(point => point[0] === (till - 5) * 1000 && Number(point[1]) === 0.4))) break
    await delay(2000)
  }
  assert(series.series.some(row => row.points.some(point => point[0] === (till - 5) * 1000 && Number(point[1]) === 0.4)), 'Synthetic VM point was not visible through Java HTTP')
  vite = await preview({ root: webRoot, configFile: path.join(webRoot, 'vite.config.ts'),
    preview: { host: '127.0.0.1', port: 0, proxy: { '/api': { target: platformUrl, configure: observeBrowserResponses } } } })
  const webPort = vite.httpServer.address().port
  browser = await chromium.launch({ executablePath: process.env.OPSWEAVE_TEST_CHROMIUM_EXECUTABLE })
  const page = await browser.newPage({ viewport: { width: 1280, height: 900 } })
  const browserErrors = []
  let browserRequests = 0
  page.on('request', request => { if (new URL(request.url()).pathname.startsWith('/api/v1/')) browserRequests++ })
  async function reloadSelection(name, defaults) {
    const savedUrl = page.url(), query = new URLSearchParams(new URL(savedUrl).hash.split('?')[1])
    const selection = { ...defaults }
    for (const [key, value] of query) {
      assert(Object.hasOwn(defaults, key)); assert.equal(query.getAll(key).length, 1)
      selection[key] = ['from', 'till'].includes(key) ? Number(value) : value
    }
    await writeFile(path.join(root, '.tmp/metrics-acceptance', `${name}-view-selection.json`), JSON.stringify(selection, null, 2))
    const before = browserRequests
    await page.reload()
    await expect(page.getByRole('textbox', { name: '平台开发 Token（仅保存在当前标签页内存）' })).toHaveValue('')
    assert.equal(page.url(), savedUrl); assert.equal(browserRequests, before, 'Restoring selection must not read data')
    await page.getByRole('textbox', { name: '平台开发 Token（仅保存在当前标签页内存）' }).fill(token)
    assert.equal(page.url(), savedUrl); assert.equal(browserRequests, before, 'Entering a credential must not read data')
    return selection
  }
  const boundaryChecks = []; const boundaryFailures = []
  page.on('pageerror', error => browserErrors.push(error.message))
  page.on('response', response => {
    if (!new URL(response.url()).pathname.startsWith('/api/v1/')) return
    boundaryChecks.push((async () => {
      const headers = await response.allHeaders(), requestId = response.request().headers()['x-opsweave-request-id']
      assert.match(requestId, /^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/)
      assert.equal(headers['x-opsweave-request-id'], requestId)
      assert(headers['cache-control'].includes('no-store')); assert.equal(headers['x-content-type-options'], 'nosniff')
    })().catch(error => { boundaryFailures.push(error.message) }))
  })
  await page.goto(`http://127.0.0.1:${webPort}/#/metrics`)
  await page.getByRole('textbox', { name: '平台开发 Token（仅保存在当前标签页内存）' }).fill(token)
  await page.getByRole('button', { name: '读取资产和指标' }).click()
  await page.getByRole('combobox', { name: 'Host' }).selectOption(entity.id)
  await page.getByRole('button', { name: 'Last 15m', exact: true }).click()
  const responsePromise = page.waitForResponse(response => response.url().includes('/series?'))
  await page.getByRole('button', { name: '查询', exact: true }).click()
  const response = await responsePromise
  assert.equal(response.status(), 200)
  await page.getByRole('img', { name: '指标曲线' }).waitFor()
  assert((await page.locator('[data-series-source="zabbix-1"]').textContent()).includes('labeled-fixture'))
  assert.equal(await page.locator('.metric-chart polyline').first().evaluate(node => node.namespaceURI), 'http://www.w3.org/2000/svg')
  assert.deepEqual(browserErrors, [])
  await mkdir(path.join(root, '.tmp/metrics-acceptance'), { recursive: true })
  await page.screenshot({ path: path.join(root, '.tmp/metrics-acceptance/metrics.png'), fullPage: true })
  console.log('PASS: Chromium -> Vite proxy -> Java HTTP -> real PostgreSQL metadata + VictoriaMetrics samples (labeled fixture source).')
  const metricsSelection = await reloadSelection('metrics', { q: '', after: null, entityId: null, metricKey: '', range: '1h', from: null, till: null })
  assert.equal(metricsSelection.entityId, entity.id)
  await page.getByRole('button', { name: '读取资产和指标', exact: true }).click()
  await expect(page.getByRole('combobox', { name: 'Host', exact: true })).toHaveValue(entity.id)
  await expect(page.getByRole('combobox', { name: 'Metric', exact: true })).toHaveValue(metricsSelection.metricKey)
  const restoredSeriesResponse = page.waitForResponse(response => response.url().includes('/series?'))
  await page.getByRole('button', { name: '查询', exact: true }).click()
  const restoredSeriesHttp = await restoredSeriesResponse; assert.equal(restoredSeriesHttp.status(), 200)
  const restoredSeries = await browserJson(restoredSeriesHttp)
  assert.equal(restoredSeries.from, metricsSelection.from); assert.equal(restoredSeries.till, metricsSelection.till)
  assert.equal(restoredSeries.entityId, entity.id); assert.equal(restoredSeries.metricKey, metricsSelection.metricKey)
  await page.getByRole('img', { name: '指标曲线' }).waitFor()
  await page.screenshot({ path: path.join(root, '.tmp/metrics-acceptance/restored-metrics.png'), fullPage: true })
  await page.getByRole('link', { name: '资产', exact: true }).click()
  assert.equal(await page.getByRole('textbox', { name: '平台开发 Token（仅保存在当前标签页内存）' }).inputValue(), token)
  await page.getByRole('textbox', { name: '名称或 IP' }).fill(entity.attributes.ip)
  await page.getByRole('combobox', { name: '生命周期' }).selectOption('ACTIVE')
  const entityPageResponse = page.waitForResponse(response => response.url().includes('/entities/page?'))
  await page.getByRole('button', { name: '刷新列表' }).click()
  const entityPage = await entityPageResponse
  assert.equal(entityPage.status(), 200)
  const filtered = await browserJson(entityPage)
  assert.equal(filtered.storage, 'postgres')
  assert.equal(filtered.items.length, 1)
  assert.equal(filtered.items[0].id, entity.id)
  await page.getByRole('button', { name: '查看详情' }).click()
  await page.locator('[data-entity-detail]').waitFor()
  assert((await page.locator('[data-entity-detail]').textContent()).includes('labeled-fixture'))
  assert((await page.locator('[data-entity-detail]').textContent()).includes('zabbix-host-default@1'))
  const inventorySelection = await reloadSelection('inventory', { q: '', type: 'host', lifecycle: '', after: null, entityId: null })
  assert.equal(inventorySelection.entityId, entity.id)
  await expect(page.getByRole('textbox', { name: '名称或 IP' })).toHaveValue(entity.attributes.ip)
  await expect(page.getByRole('combobox', { name: '生命周期' })).toHaveValue('ACTIVE')
  await page.getByRole('button', { name: '读取选中资产', exact: true }).click()
  await page.locator('[data-entity-detail]').waitFor()
  assert((await page.locator('[data-entity-detail]').textContent()).includes(entity.id))
  const observationsResponse = page.waitForResponse(response => new URL(response.url()).pathname.endsWith('/observations'))
  await page.getByRole('button', { name: '读取观测历史', exact: true }).click()
  const observationsHttp = await observationsResponse; assert.equal(observationsHttp.status(), 200)
  const observedPage = await browserJson(observationsHttp)
  assert.equal(observedPage.storage, 'postgres'); assert.equal(observedPage.entityId, entity.id); assert.equal(observedPage.tenantId, tenant)
  assert.equal(observedPage.items.length, 1); assert.equal(observedPage.items[0].fields.dataMode, 'labeled-fixture')
  assert.equal(observedPage.items[0].timePrecision, 'nanoseconds'); assert.equal(observedPage.items[0].fields.entityName, entity.name)
  assert.equal(observedPage.items[0].fields.pipelineDigest, entity.attributes.pipelineDigest)
  await page.locator('[data-observation-record]').waitFor()
  for (const [name, value] of Object.entries({ 'observation-page': observedPage, observation: observedPage.items[0] })) {
    await writeFile(path.join(root, '.tmp/metrics-acceptance', `${name}.json`), JSON.stringify(value, null, 2))
  }
  await page.screenshot({ path: path.join(root, '.tmp/metrics-acceptance/observations.png'), fullPage: true })
  console.log('PASS: Browser authorized entity -> PostgreSQL retained observation -> source/mapping/raw reference and exact observation/ingestion times; explicit fixture and retained-only coverage.')
  // Human-only supplemental import uses real API/storage. Keep telemetry identity stable.
  await page.getByRole('button', { name: '读取补充来源', exact: true }).click()
  await page.getByText('导入一条补充记录', { exact: true }).click()
  await page.getByRole('textbox', { name: 'CMDB 外部编号' }).fill('fixture-cmdb-host-10084')
  await page.getByRole('textbox', { name: '导入名称', exact: true }).fill('CMDB fixture reviewed host')
  await page.getByRole('textbox', { name: '导入负责人', exact: true }).fill('Fixture Operations')
  const stagedResponse = page.waitForResponse(r => r.request().method() === 'POST' && new URL(r.url()).pathname.endsWith('/source-reviews'))
  await page.getByRole('button', { name: '暂存并预览' }).click()
  const stagedHttp = await stagedResponse; assert.equal(stagedHttp.status(), 200)
  const stagedReview = await browserJson(stagedHttp)
  assert.equal(stagedReview.status, 'PENDING'); assert.equal(stagedReview.dataMode, 'import'); assert.equal(stagedReview.entityId, entity.id)
  assert.equal((await request(`/api/v1/entities/${entity.id}`)).name, entity.name)
  await page.getByRole('combobox', { name: '选择名称来源' }).selectOption('SUPPLEMENTAL')
  await page.getByRole('combobox', { name: '选择负责人来源' }).selectOption('SUPPLEMENTAL')
  await page.getByRole('textbox', { name: '核对或撤销原因' }).fill('Fixture acceptance: manually verified external object')
  const acceptedResponse = page.waitForResponse(r => r.request().method() === 'POST' && new URL(r.url()).pathname.endsWith('/decisions'))
  await page.getByRole('button', { name: '确认字段选择' }).click()
  const acceptedHttp = await acceptedResponse; assert.equal(acceptedHttp.status(), 200)
  const acceptedReview = await browserJson(acceptedHttp); assert.equal(acceptedReview.status, 'ACCEPTED')
  await page.locator('[data-active-source]').waitFor()
  assert.equal((await request(`/api/v1/entities/${entity.id}`)).name, 'CMDB fixture reviewed host')
  await request('/api/v1/integrations/zabbix/hosts/sync', 'POST')
  const projected = await request(`/api/v1/entities/${entity.id}`)
  assert.equal(projected.name, 'CMDB fixture reviewed host'); assert.equal(projected.attributes.owner, 'Fixture Operations')
  assert.equal(projected.id, entity.id); assert.equal(projected.attributes.hostId, '10084')
  assert((await request(seriesPath)).series.some(row => row.points.some(point => point[0] === (till - 5) * 1000 && Number(point[1]) === 0.4)), 'Primary metric binding must still resolve after field review')
  await page.getByRole('button', { name: '读取补充来源', exact: true }).click()
  await page.getByRole('button', { name: '查看生效字段与撤销' }).click()
  await page.getByRole('textbox', { name: '核对或撤销原因' }).fill('Fixture acceptance: restore the latest primary source')
  await page.screenshot({ path: path.join(root, '.tmp/metrics-acceptance/source-review.png'), fullPage: true })
  const revokedResponse = page.waitForResponse(r => r.request().method() === 'POST' && new URL(r.url()).pathname.endsWith('/decisions'))
  await page.getByRole('button', { name: '撤销并恢复最新主来源' }).click()
  const revokedHttp = await revokedResponse; assert.equal(revokedHttp.status(), 200)
  const revokedReview = await browserJson(revokedHttp); assert.equal(revokedReview.status, 'REVOKED')
  const restored = await request(`/api/v1/entities/${entity.id}`)
  assert.equal(restored.name, entity.name); assert.equal(restored.attributes.fieldAuthority, undefined)
  const reviewPage = await request(`/api/v1/entities/${entity.id}/source-reviews?limit=25`)
  assert.equal(reviewPage.active, null); assert.equal(reviewPage.items.length, 1); assert.equal(reviewPage.storage, 'postgres')
  for (const [name, value] of Object.entries({ 'source-review': revokedReview, 'source-review-page': reviewPage,
      'cmdb-import-pipeline': reviewPage.mapping, 'source-review-import': stagedHttp.request().postDataJSON(), 'source-review-decision': acceptedHttp.request().postDataJSON() })) {
    await writeFile(path.join(root, '.tmp/metrics-acceptance', `${name}.json`), JSON.stringify(value, null, 2))
  }
  // Wait for the browser's post-decision reads before clearing its credential.
  await expect(page.getByRole('button', { name: '读取补充来源', exact: true })).toBeEnabled()
  console.log('PASS: Browser manual CMDB import -> PostgreSQL staged preview -> per-field acceptance -> primary resync preserves fields -> revoke restores latest primary with stable telemetry identity.')
  await checkAssetIdentity({ page, expect, request, browserJson, entity, tenant, root })
  await page.screenshot({ path: path.join(root, '.tmp/metrics-acceptance/inventory.png'), fullPage: true })
  await page.getByRole('textbox', { name: '平台开发 Token（仅保存在当前标签页内存）' }).fill('')
  assert.equal(await page.getByRole('table').count(), 0)
  assert.equal(await page.locator('[data-entity-detail]').count(), 0)
  assert.deepEqual(browserErrors, [])
  console.log('PASS: Browser authorized inventory filter -> PostgreSQL -> freshly authorized detail with fixture provenance; identity change clears data.')
  await page.goto(`http://127.0.0.1:${webPort}/#/incidents`)
  await page.getByRole('textbox', { name: '平台开发 Token（仅保存在当前标签页内存）' }).fill(token)
  await page.locator('summary').click()
  await page.getByRole('button', { name: '使用 fixture 时间窗口' }).click()
  const importResponse = page.waitForResponse(response => response.url().endsWith('/problems/ingest'))
  await page.getByRole('button', { name: '导入告警首批' }).click()
  const importedResponse = await importResponse
  assert.equal(importedResponse.status(), 200)
  const imported = await browserJson(importedResponse)
  assert.equal(imported.storage, 'postgres'); assert.equal(imported.dataMode, 'labeled-fixture')
  assert.equal(imported.accepted, 2); assert.equal(imported.createdIncidents, 2); assert.equal(imported.unmappedHosts, 0)
  await page.locator('[data-problem-import]').waitFor()
  const repeatImport = page.waitForResponse(response => response.url().endsWith('/problems/ingest'))
  await page.getByRole('button', { name: '导入告警首批' }).click()
  assert.equal((await browserJson(await repeatImport)).createdIncidents, 0)
  await page.getByRole('button', { name: '刷新 Incident', exact: true }).click()
  await page.getByRole('button', { name: '查看 Incident' }).first().click()
  await page.locator('[data-incident-detail]').waitFor()
  assert((await page.locator('[data-incident-detail]').textContent()).includes('labeled-fixture'))
  const incidentId = (await page.locator('[data-incident-detail] > p > code').textContent()).trim()
  // Source observations are read through the browser's authorized API boundary, not the live projection.
  // Query endpoints use whole-second bounds; allow the just-ingested nanosecond observations into that window.
  await delay(1100)
  const problemHistoryResponse = page.waitForResponse(response => response.url().includes(`/${incidentId}/problem-observations?`))
  await page.getByRole('button', { name: '读取告警观测', exact: true }).click()
  const problemHistoryHttp = await problemHistoryResponse; assert.equal(problemHistoryHttp.status(), 200)
  const problemHistory = await browserJson(problemHistoryHttp)
  assert.equal(problemHistory.storage, 'postgres'); assert.equal(problemHistory.query.version, 1)
  assert.equal(problemHistory.coverage, 'retained-normalized-current-ownership'); assert(problemHistory.items.length >= 1)
  assert(problemHistory.items.every(item => item.dataMode === 'labeled-fixture'))
  await expect(page.locator('[data-problem-observation]')).toHaveCount(problemHistory.items.length)
  await page.locator('[data-problem-history]').screenshot({ path: path.join(root, '.tmp/metrics-acceptance/problem-history.png') })
  for (const [name, value] of Object.entries({ 'problem-observation-page': problemHistory, 'problem-observation': problemHistory.items[0], 'problem-observation-query': problemHistory.query })) {
    await writeFile(path.join(root, '.tmp/metrics-acceptance', `${name}.json`), JSON.stringify(value, null, 2))
  }
  console.log('PASS: Browser authorized normalized problem history -> PG immutable source inputs, current Incident version and explicit retained-only gaps (fixture).')
  const transitionResponse = page.waitForResponse(response => response.url().endsWith('/transitions'))
  await page.getByRole('button', { name: '转为 INVESTIGATING' }).click()
  assert.equal((await transitionResponse).status(), 200)
  await page.getByRole('button', { name: '转为 MITIGATED' }).waitFor()
  assert((await page.locator('[data-incident-state]').textContent()).includes('INVESTIGATING · 版本 2'))
  await page.getByRole('combobox', { name: 'Incident 状态' }).selectOption('INVESTIGATING')
  await page.getByRole('button', { name: '刷新 Incident', exact: true }).click()
  await page.getByRole('button', { name: '查看 Incident' }).click()
  await page.locator('[data-incident-detail]').waitFor()
  const incidentSelection = await reloadSelection('incident', { status: '', after: null, incidentId: null })
  assert.equal(incidentSelection.incidentId, incidentId)
  await expect(page.getByRole('combobox', { name: 'Incident 状态' })).toHaveValue('INVESTIGATING')
  await page.getByRole('button', { name: '读取选中 Incident', exact: true }).click()
  await page.locator('[data-incident-detail]').waitFor()
  assert((await page.locator('[data-incident-detail]').textContent()).includes(incidentId))
  assert((await page.locator('.incident-timeline').textContent()).includes('OPEN → INVESTIGATING'))
  await page.getByRole('button', { name: '查看关联资产' }).click()
  await page.locator('[data-incident-asset]').waitFor()
  await page.screenshot({ path: path.join(root, '.tmp/metrics-acceptance/incident.png'), fullPage: true })
  await page.getByRole('link', { name: '查看发生前后 30 分钟指标' }).click()
  await page.locator('[data-metric-window]').waitFor()
  const intentQuery = new URLSearchParams(new URL(page.url()).hash.split('?')[1])
  await page.getByRole('textbox', { name: '平台开发 Token（仅保存在当前标签页内存）' }).fill(token)
  await page.getByRole('button', { name: '读取资产和指标' }).click()
  await page.getByRole('button', { name: '查询', exact: true }).waitFor()
  const windowResponse = page.waitForResponse(response => response.url().includes('/series?'))
  await page.getByRole('button', { name: '查询', exact: true }).click()
  const windowResult = await windowResponse
  assert.equal(windowResult.status(), 200)
  const windowPage = await browserJson(windowResult)
  assert.equal(windowPage.entityId, intentQuery.get('entityId'))
  assert.equal(windowPage.from, Number(intentQuery.get('from'))); assert.equal(windowPage.till, Number(intentQuery.get('till')))
  assert.equal(windowPage.status.kind, 'NO_DATA') // No historic fixture points were written for this window.
  await page.locator('[data-state="no-data"]').waitFor()
  assert.deepEqual(browserErrors, [])
  console.log('PASS: Browser fixture problem import -> idempotent PG Incident -> human transition -> reload/timeline -> authorized asset + same-window VM query; missing historic metrics remain NO_DATA.')
  console.log('PASS: Applied inventory and Incident selections plus exact metric window survive reload; no automatic read or restored credential; explicit reads reauthorize through Java/PG/VM.')
  const incidentPage = await request('/api/v1/incidents?limit=20')
  let linkedIncident
  for (const row of incidentPage.items) {
    const detail = await request(`/api/v1/incidents/${row.id}`)
    if (detail.record.problems.some(problem => problem.entities.some(link => link.entityId === entity.id))) { linkedIncident = row.id; break }
  }
  assert(linkedIncident, 'Imported problem must link to the sampled fixture host')
  const timeRange = { from: new Date((till - 900) * 1000).toISOString(), to: new Date(till * 1000).toISOString() }
  const readSession = await request('/api/v1/ai/read-sessions', 'POST', { incidentId: linkedIncident, timeRange, knowledgeMode: 'current' })
  let savedInsight
  assert.equal(readSession.storage, 'postgres'); assert.equal(readSession.tenantId, tenant)
  const sessionHeaders = { 'X-OpsWeave-Read-Session': readSession.id }
  const [incidentTool, metricTool] = await Promise.all([
    request('/api/v1/tools/incident.get/2.0.0', 'POST', { incidentId: linkedIncident }, sessionHeaders),
    request('/api/v1/tools/metric.summary/2.0.0', 'POST', { incidentId: linkedIncident, metric: 'host.cpu.usage.user', timeRange, maxPoints: 500 }, sessionHeaders),
  ])
  assert.equal(metricTool.data.data.sampleCount, 2)
  const summary = metricTool.data.data.entities.find(row => row.entityId === entity.id).series[0]
  assert.equal(summary.mean, '0.355'); assert.equal(summary.unit, '1'); assert.equal(summary.dataMode, 'labeled-fixture')
  assert(metricTool.warnings.includes('METRIC_INGEST_TIME_UNAVAILABLE'))
  for (const result of [incidentTool, metricTool]) {
    assert.equal(result.data.knowledgeMode, 'current')
    assert(Date.parse(result.data.evidence.availableAt) > till * 1000)
    assert.deepEqual(await request(result.data.evidence.sourceRef), result.data)
    const checked = await request('/api/v1/tools/evidence.get/2.0.0', 'POST', { evidenceId: result.data.evidence.id }, sessionHeaders)
    assert.deepEqual(checked, result)
  }
  const exhausted = await fetch(platformUrl + '/api/v1/tools/incident.get/2.0.0', {
    method: 'POST', headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json', ...sessionHeaders },
    body: JSON.stringify({ incidentId: linkedIncident }), signal: AbortSignal.timeout(18000),
  })
  assert.equal(exhausted.status, 429)
  for (const [name, value] of Object.entries({ 'tool-read-session': readSession, 'tool-result': metricTool, 'platform-evidence': metricTool.data })) {
    await writeFile(path.join(root, '.tmp/metrics-acceptance', `${name}.json`), JSON.stringify(value, null, 2))
  }
  console.log('PASS: Java Tool Gateway -> real PostgreSQL session/evidence + VictoriaMetrics gauge summary -> authorized rechecks; shared four-call budget exhausted (fixture provenance, current knowledge).')
  if (process.argv.includes('--runtime')) {
    const probe = spawn(path.join(root, 'target/debug/examples', process.platform === 'win32' ? 'platform_read_probe.exe' : 'platform_read_probe'), [], {
      cwd: root, windowsHide: true, stdio: ['ignore', 'inherit', 'inherit'], env: { ...process.env,
        OPSWEAVE_PLATFORM_URL: platformUrl, OPSWEAVE_PLATFORM_TOKEN: token,
        OPSWEAVE_PROBE_INCIDENT: linkedIncident, OPSWEAVE_PROBE_FROM: String(till - 900), OPSWEAVE_PROBE_TILL: String(till) },
    })
    const [code] = await once(probe, 'exit')
    assert.equal(code, 0, 'Rust platform probe failed')
    await page.goto(`http://127.0.0.1:${webPort}/#/incidents/current-diagnose?incidentId=${linkedIncident}`)
    await page.getByRole('textbox', { name: '平台开发 Token（仅保存在当前标签页内存）' }).fill(token)
    await page.getByRole('textbox', { name: '采样开始（UTC）' }).fill(timeRange.from)
    await page.getByRole('textbox', { name: '采样结束（UTC）' }).fill(timeRange.to)
    const diagnosedResponse = page.waitForResponse(response => response.url().endsWith('/api/v1/ai/diagnoses'), { timeout: 80000 })
    await page.getByRole('button', { name: '开始只读诊断', exact: true }).click()
    const diagnosed = await diagnosedResponse; assert.equal(diagnosed.status(), 200)
    const saved = await browserJson(diagnosed); assert.equal(saved.storage, 'postgres'); assert.equal(saved.record.model.provider, 'mock-deterministic')
    savedInsight = saved
    assert.equal(saved.record.incidentId, linkedIncident); assert.equal(saved.record.tenantId, tenant); assert.equal(saved.record.evidenceIds.length, 2)
    await page.locator('[data-insight-result]').waitFor(); assert((await page.locator('[data-insight-result]').textContent()).includes('labeled-fixture'))
    const spendResponse = page.waitForResponse(response => response.url().includes('/api/v1/ai/model-calls/'))
    await page.getByRole('button', { name: '读取用量与费用', exact: true }).click()
    const spend = await browserJson(await spendResponse); assert.equal(spend.storage, 'postgres'); assert.equal(spend.record.state, 'REPORTED')
    assert.equal(spend.record.runId, saved.record.id); assert.equal(spend.record.sessionId, saved.record.sessionId)
    assert.equal(spend.record.usage.source, 'mock-no-call'); assert.equal(spend.record.accountedMicros, 0)
    await page.locator('[data-model-spend]').waitFor(); assert((await page.locator('[data-model-spend]').textContent()).includes('显式 mock'))
    await writeFile(path.join(root, '.tmp/metrics-acceptance/model-spend-result.json'), JSON.stringify(spend, null, 2))
    await page.screenshot({ path: path.join(root, '.tmp/metrics-acceptance/model-spend.png'), fullPage: true })
    const retry = await fetch(platformUrl + '/api/v1/ai/diagnoses', { method: 'POST', headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ runId: saved.record.id, incidentId: linkedIncident, question: saved.record.question, timeRange, knowledgeMode: 'current' }), signal: AbortSignal.timeout(80000) })
    assert.equal(retry.status, 200); assert.deepEqual(await retry.json(), saved)
    await page.reload(); assert.equal(await page.locator('[data-insight-result]').count(), 0)
    await page.getByRole('textbox', { name: '平台开发 Token（仅保存在当前标签页内存）' }).fill(token)
    const reloadedResponse = page.waitForResponse(response => response.url().includes('/api/v1/ai/insights/'))
    await page.getByRole('button', { name: '读取已保存结果', exact: true }).click()
    assert.deepEqual(await browserJson(await reloadedResponse), saved)
    const spendReload = page.waitForResponse(response => response.url().includes('/api/v1/ai/model-calls/'))
    await page.getByRole('button', { name: '读取用量与费用', exact: true }).click(); assert.deepEqual(await browserJson(await spendReload), spend)
    await page.locator('[data-insight-result]').waitFor()
    const evidenceResponse = page.waitForResponse(response => response.url().includes('/api/v1/ai/evidence/'))
    await page.getByRole('button', { name: `读取快照 ${saved.record.evidenceIds[1]}`, exact: true }).click()
    const doc = await browserJson(await evidenceResponse); assert.equal(doc.data.sampleCount, 2)
    await page.locator('[data-insight-evidence]').waitFor(); assert((await page.locator('[data-insight-evidence]').textContent()).includes('0.355'))
    await writeFile(path.join(root, '.tmp/metrics-acceptance/ai-insight-result.json'), JSON.stringify(saved, null, 2))
    await writeFile(path.join(root, '.tmp/metrics-acceptance/ai-insight.json'), JSON.stringify(saved.record, null, 2))
    await page.screenshot({ path: path.join(root, '.tmp/metrics-acceptance/insight.png'), fullPage: true })
    await page.getByRole('textbox', { name: '平台开发 Token（仅保存在当前标签页内存）' }).fill('')
    assert.equal(await page.locator('[data-insight-result]').count(), 0); assert.equal(await page.locator('[data-insight-evidence]').count(), 0)
    assert.deepEqual(browserErrors, [])
    console.log('PASS: Browser -> Java authorization -> Rust current structured context + explicit mock -> two fresh evidence rechecks -> PG AIInsight -> idempotent retry -> reload + authorized evidence; identity change clears results.')
    console.log('PASS: Runtime spend reservation/report -> PostgreSQL explicit mock no-call receipt -> authorized browser usage read and reload; no real provider billing claimed.')
  }
  if (process.argv.includes('--pipeline')) {
    const baseline = await request('/api/v1/entities')
    await page.goto(`http://127.0.0.1:${webPort}/#/integrations/pipelines`)
    await page.getByRole('textbox', { name: '平台开发 Token（仅保存在当前标签页内存）' }).fill(token)
    await page.getByRole('textbox', { name: '流水线 ID', exact: true }).fill(`acceptance-${randomBytes(6).toString('hex')}`)
    await page.getByRole('textbox', { name: '同步批次 ID' }).fill(hostSync.syncRunId)
    await page.getByRole('combobox', { name: '主机显示名' }).selectOption('host')
    const draftResponse = page.waitForResponse(response => response.url().endsWith('/pipeline/drafts'))
    await page.getByRole('button', { name: '保存草稿', exact: true }).click()
    const draftSaved = await draftResponse
    assert.equal(draftSaved.status(), 200)
    const draft = await browserJson(draftSaved)
    assert.equal(draft.storage, 'postgres')
    assert.equal(draft.state, 'DRAFT')
    assert.equal(draft.editVersion, 1)
    assert.deepEqual(await request('/api/v1/entities'), baseline)
    await page.reload()
    await page.getByRole('textbox', { name: '平台开发 Token（仅保存在当前标签页内存）' }).fill(token)
    await page.getByRole('button', { name: '读取最近草稿', exact: true }).click()
    await page.locator('[data-draft-list]').getByRole('button', { name: '载入草稿', exact: true }).first().click()
    await page.locator('[data-draft]').waitFor()
    assert.equal(await page.getByRole('textbox', { name: '流水线 ID', exact: true }).inputValue(), draft.definition.id)
    assert.equal(await page.getByRole('combobox', { name: '主机显示名' }).inputValue(), 'host')
    assert.equal(await page.getByRole('button', { name: '发布预览版本' }).isDisabled(), true)
    await page.getByRole('textbox', { name: '同步批次 ID' }).fill(hostSync.syncRunId)
    const previewResponse = page.waitForResponse(response => response.url().endsWith('/pipeline/preview'))
    await page.getByRole('button', { name: '预览当前定义' }).click()
    const previewed = await previewResponse
    assert.equal(previewed.status(), 200)
    const evaluation = await browserJson(previewed)
    assert.equal(evaluation.accepted, 2)
    assert.equal(evaluation.dataMode, 'labeled-fixture')
    assert.equal(evaluation.targetVersion.digest, draft.digest)
    assert(evaluation.changed > 0)
    await page.getByRole('heading', { name: '预览结果' }).waitFor()
    const publicationResponse = page.waitForResponse(response => response.url().endsWith('/pipeline/versions'))
    await page.getByRole('button', { name: '发布预览版本' }).click()
    const publication = await publicationResponse
    assert.equal(publication.status(), 200)
    assert.equal((await browserJson(publication)).digest, evaluation.targetVersion.digest)
    await page.locator('[data-published]').waitFor()
    const replayResponse = page.waitForResponse(response => response.url().endsWith('/pipeline/replay-runs'))
    await page.getByRole('button', { name: '只读重放', exact: true }).click()
    const replayed = await replayResponse
    assert.equal(replayed.status(), 200)
    const saved = await browserJson(replayed)
    assert.equal(saved.storage, 'postgres')
    assert.equal(saved.run.state, 'SUCCEEDED')
    assert.equal(saved.run.attempt, 1)
    const replay = saved.report
    assert.deepEqual(replay.rows, evaluation.rows)
    assert.equal(replay.dryRun, true)
    assert.equal(replay.writesPerformed, false)
    assert.deepEqual(await request('/api/v1/entities'), baseline)
    await page.getByRole('heading', { name: '只读重放结果' }).waitFor()
    await page.reload()
    await page.getByRole('textbox', { name: '平台开发 Token（仅保存在当前标签页内存）' }).fill(token)
    await page.getByRole('button', { name: '读取重放记录', exact: true }).click()
    await page.locator('[data-replay-history]').getByRole('button', { name: '查看记录', exact: true }).first().click()
    await page.getByRole('heading', { name: '只读重放结果' }).waitFor()
    assert((await page.locator('[data-replay-detail]').textContent()).includes(saved.run.id))
    assert.deepEqual(await request('/api/v1/entities'), baseline)
    assert.deepEqual(browserErrors, [])
    await page.screenshot({ path: path.join(root, '.tmp/metrics-acceptance/pipeline.png'), fullPage: true })
    console.log('PASS: Browser draft save -> page reload/load -> preview -> immutable publication -> durable replay -> reload/report recovery from PostgreSQL; inventory unchanged (labeled fixture source).')
  }
  const sourceIncident = incidentPage.items.find(row => row.id !== linkedIncident).id
  const originalSource = await request(`/api/v1/incidents/${sourceIncident}`)
  const originalTarget = await request(`/api/v1/incidents/${linkedIncident}`)
  await page.goto(`http://127.0.0.1:${webPort}/#/incidents/reorganize?incidentId=${sourceIncident}`)
  await page.getByRole('textbox', { name: '平台开发 Token（仅保存在当前标签页内存）' }).fill(token)
  await page.getByRole('button', { name: '读取来源 Incident', exact: true }).click()
  await page.locator('[data-reorganization-source]').waitFor()
  await page.getByRole('textbox', { name: '目标 Incident ID' }).fill(linkedIncident)
  await page.getByRole('button', { name: '读取目标 Incident', exact: true }).click()
  await page.locator('[data-reorganization-target]').waitFor()
  await page.getByRole('textbox', { name: '人工调整原因' }).fill('Fixture acceptance: operator reviewed a shared investigation')
  await page.getByRole('button', { name: '预览关联调整' }).click()
  await page.locator('[data-reorganization-review]').waitFor()
  assert.deepEqual(await request(`/api/v1/incidents/${linkedIncident}`), originalTarget)
  const mergeResponse = page.waitForResponse(response => response.url().endsWith('/incidents/reorganizations') && response.request().method() === 'POST')
  await page.getByRole('button', { name: '确认关联调整' }).click()
  const mergeHttp = await mergeResponse; assert.equal(mergeHttp.status(), 200)
  const merged = await browserJson(mergeHttp); assert.equal(merged.storage, 'postgres')
  assert.equal(merged.change.sourceVersion, originalSource.record.incident.version + 1)
  assert.equal(merged.change.targetVersion, originalTarget.record.incident.version + 1)
  await page.locator('[data-reorganization-result]').waitFor()
  assert.deepEqual(await request('/api/v1/incidents/reorganizations', 'POST', merged.change.request), merged)
  const archived = await request(`/api/v1/incidents/${sourceIncident}`)
  assert.equal(archived.record.organization.mergedInto, linkedIncident)
  assert.deepEqual(archived.record.problems, originalSource.record.problems)
  assert.equal((await request('/api/v1/incidents?limit=20')).items.length, 1)
  assert.equal((await request(`/api/v1/incidents/${linkedIncident}`)).record.problems.length, 2)
  for (const pathname of [metricTool.data.evidence.sourceRef, ...(savedInsight ? [`/api/v1/ai/insights/${savedInsight.record.id}`] : [])]) {
    const rejected = await fetch(platformUrl + pathname, { headers: { Authorization: `Bearer ${token}` }, signal: AbortSignal.timeout(18000) })
    assert.equal(rejected.status, 409, 'Old evidence/insight must be invalidated after association changes')
  }
  await page.reload(); await page.getByRole('textbox', { name: '平台开发 Token（仅保存在当前标签页内存）' }).fill(token)
  const receiptResponse = page.waitForResponse(response => response.url().includes('/incidents/reorganizations/'))
  await page.getByRole('button', { name: '读取关联记录', exact: true }).click()
  assert.deepEqual(await browserJson(await receiptResponse), merged)
  await page.locator('[data-reorganization-result]').waitFor()
  await page.getByRole('button', { name: '读取调整后的目标' }).click()
  await page.locator('[data-reorganization-source]').waitFor()
  await page.getByRole('combobox', { name: '调整方式' }).selectOption('SPLIT')
  await page.getByRole('textbox', { name: '新 Incident 标题' }).fill('Fixture: separately reviewed incident')
  const movedKey = merged.change.movedProblems[0]
  await page.getByRole('checkbox', { name: `${movedKey.sourceInstanceId}:${movedKey.problemEventId}`, exact: false }).check()
  await page.getByRole('textbox', { name: '人工调整原因' }).fill('Fixture acceptance: operator separated the problem after review')
  await page.getByRole('button', { name: '预览关联调整' }).click()
  await page.locator('[data-reorganization-review]').waitFor()
  const splitResponse = page.waitForResponse(response => response.url().endsWith('/incidents/reorganizations') && response.request().method() === 'POST')
  await page.getByRole('button', { name: '确认关联调整' }).click()
  const splitHttp = await splitResponse; assert.equal(splitHttp.status(), 200)
  const split = await browserJson(splitHttp); await page.locator('[data-reorganization-result]').waitFor()
  const child = await request(`/api/v1/incidents/${split.change.request.targetIncidentId}`)
  assert.equal(child.record.incident.status, 'OPEN'); assert.equal(child.record.incident.version, 1)
  assert.deepEqual(child.record.problems, originalSource.record.problems)
  assert.deepEqual(child.record.timeline, originalSource.record.timeline.filter(event => event.kind !== 'STATUS_CHANGE'))
  const importedAgain = await request('/api/v1/integrations/zabbix/problems/ingest', 'POST', { from: Date.parse('2026-09-21T12:00:00Z') / 1000,
    till: Date.parse('2026-09-21T13:00:00Z') / 1000, afterEventId: null, limit: 100 })
  assert.equal(importedAgain.createdIncidents, 0); assert.equal((await request('/api/v1/incidents?limit=20')).items.length, 2)
  assert.equal((await request(`/api/v1/incidents/${linkedIncident}`)).record.problems.length, 1)
  const historyResponse = page.waitForResponse(response => response.url().includes(`/${linkedIncident}/reorganizations?`))
  await page.getByRole('button', { name: '读取关联历史' }).click()
  const history = await browserJson(await historyResponse); assert.equal(history.items.length, 2)
  await page.locator('[data-reorganization-history]').waitFor()
  await page.screenshot({ path: path.join(root, '.tmp/metrics-acceptance/reorganization.png'), fullPage: true })
  for (const [name, value] of Object.entries({ 'incident-reorganization-result': split, 'incident-reorganization': split.change, 'incident-reorganization-page': history, 'incident-detail': archived })) {
    await writeFile(path.join(root, '.tmp/metrics-acceptance', `${name}.json`), JSON.stringify(value, null, 2))
  }
  await page.getByRole('textbox', { name: '平台开发 Token（仅保存在当前标签页内存）' }).fill('')
  assert.equal(await page.locator('[data-reorganization-result]').count(), 0); assert.equal(await page.locator('[data-reorganization-history]').count(), 0)
  assert.deepEqual(browserErrors, [])
  console.log('PASS: Browser preview -> PG atomic merge -> same-key receipt/reload -> old evidence/AIInsight rejected -> split to OPEN -> reimport follows ownership -> authorized history and identity clearing (fixture).')
  await page.getByRole('link', { name: 'AI 数据留存', exact: true }).click()
  await page.getByRole('textbox', { name: '平台开发 Token（仅保存在当前标签页内存）' }).fill(token)
  const retentionPreviewResponse = page.waitForResponse(r => r.url().endsWith('/api/v1/ai/retention') && r.request().method() === 'GET')
  retentionPreviewResponse.catch(() => {})
  await page.getByRole('button', { name: '预览留存清理' }).click()
  const retentionPreview = await browserJson(await retentionPreviewResponse)
  await page.locator('[data-retention-preview]').waitFor()
  await expect(page.locator('[data-retention-preview] li')).toHaveCount(3)
  assert.equal(retentionPreview.policy.tenantId, tenant); assert(retentionPreview.preview.batches.every(b => b.ids.length === 0), 'Fresh acceptance records must remain retained')
  assert.equal(await page.getByRole('button', { name: '确认清理本批内容' }).isDisabled(), true)
  await page.getByRole('checkbox').check()
  const retentionApplyResponse = page.waitForResponse(r => r.url().endsWith('/api/v1/ai/retention/runs') && r.request().method() === 'POST')
  retentionApplyResponse.catch(() => {})
  await page.getByRole('button', { name: '确认清理本批内容' }).click()
  const retentionReceipt = await browserJson(await retentionApplyResponse)
  await page.locator('[data-retention-receipt]').waitFor()
  await expect(page.locator('[data-retention-receipt] li')).toHaveCount(3)
  assert.equal(retentionReceipt.preview.previewDigest, retentionPreview.preview.previewDigest)
  await page.screenshot({ path: path.join(root, '.tmp/metrics-acceptance/ai-retention.png'), fullPage: true })
  await page.reload(); await page.getByRole('textbox', { name: '平台开发 Token（仅保存在当前标签页内存）' }).fill(token)
  const retentionReadResponse = page.waitForResponse(r => r.url().includes('/api/v1/ai/retention/runs/') && r.request().method() === 'GET')
  retentionReadResponse.catch(() => {})
  await page.getByRole('button', { name: '查询清理回执' }).click()
  assert.deepEqual(await browserJson(await retentionReadResponse), retentionReceipt)
  await page.locator('[data-retention-receipt]').waitFor()
  await writeFile(path.join(root, '.tmp/metrics-acceptance/ai-retention-preview.json'), JSON.stringify(retentionPreview, null, 2))
  await writeFile(path.join(root, '.tmp/metrics-acceptance/ai-retention-receipt.json'), JSON.stringify(retentionReceipt, null, 2))
  await page.getByRole('textbox', { name: '平台开发 Token（仅保存在当前标签页内存）' }).fill('')
  assert.equal(await page.locator('[data-retention-receipt]').count(), 0)
  console.log('PASS: Browser -> authorized PostgreSQL retention preview -> explicit empty-batch commit -> durable receipt/reload; all fresh fixture content retained and identity clearing enforced. Actual aged payload erasure is covered by PostgreSQL integration tests.')
  await checkSourceSnapshot({page,expect,request,browserJson,entity,tenant,token,root})
  await checkSourceBindingCorrection({page,expect,request,browserJson,entity,target:entities.items.find(item=>item.id!==entity.id),tenant,token,root})
  await checkSourceScanRuns({page,expect,request,browserJson,tenant,token,root,hostRunId:hostSync.syncRunId})
  await checkSourceConnection({page,expect,request,browserJson,tenant,token,root})
  await page.getByRole('link', { name: 'Incident 归属', exact: true }).click()
  await page.getByRole('textbox', { name: '来源 Incident ID' }).fill(sourceIncident)
  await page.getByRole('textbox', { name: '平台开发 Token（仅保存在当前标签页内存）' }).fill(token)
  await page.getByRole('button', { name: '读取关联历史' }).click()
  await page.locator('[data-reorganization-history]').waitFor()
  await page.getByRole('button', { name: '清除开发会话', exact: true }).click()
  assert.equal(await page.locator('[data-reorganization-history]').count(), 0)
  await page.getByRole('link', { name: '资产', exact: true }).click()
  assert.equal(await page.getByRole('textbox', { name: '平台开发 Token（仅保存在当前标签页内存）' }).inputValue(), '')
  assert.equal(await page.getByRole('button', { name: '刷新列表' }).isDisabled(), true)
  assert.deepEqual(await page.evaluate(() => ({ local: localStorage.length, session: sessionStorage.length, cookie: document.cookie })), { local: 0, session: 0, cookie: '' })
  await Promise.all(boundaryChecks); assert.deepEqual(boundaryFailures, []); assert(boundaryChecks.length > 20)
  await writeFile(path.join(root, '.tmp/metrics-acceptance/request-boundary.json'), JSON.stringify({ checkedResponses: boundaryChecks.length, failures: boundaryFailures.length, credentialStorage: 'document-memory-only' }, null, 2))
  console.log('PASS: Shared platform credential across navigation -> every browser API response correlates its request and forbids caching -> logout clears data and disables another page; no persistent credentials.')
} finally {
  runtime?.kill()
  await browser?.close()
  if (vite) await new Promise(resolve => vite.httpServer.close(resolve))
  platform.kill()
  await Promise.race([exited, delay(10000)])
  if (platform.exitCode === null) platform.kill('SIGKILL')
  await unlink(retentionPolicies).catch(() => {})
}
