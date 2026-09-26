// Explicit local OAuth protocol/source fixtures; real Java Worker/API, PostgreSQL and VictoriaMetrics.
// The owned PG container is supplied by the operator. No tokens or secrets are logged or persisted.
import assert from 'node:assert/strict'
import { randomBytes } from 'node:crypto'
import { spawn, execFile } from 'node:child_process'
import { promisify } from 'node:util'
import { once } from 'node:events'
import { readdir, writeFile } from 'node:fs/promises'
import { createServer } from 'node:http'
import path from 'node:path'

const exec = promisify(execFile), delay = ms => new Promise(resolve => setTimeout(resolve, ms))
const START = 1789992001 // Fixture's second second has one unambiguous 0.4 point; first second collides at millisecond precision.
export async function checkHistoryService({ root, java, api, jdbc, vm, tenant, entity, issuer, service, grantsFile, artifacts, browserContext, browserOrigin }) {
  const container = process.env.OPSWEAVE_TEST_PG_CONTAINER, user = process.env.OPSWEAVE_TEST_JDBC_USER
  assert(container && /^[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}$/.test(container), 'Explicit owned test PG container is required')
  assert(/^oidc-[a-f0-9]+$/.test(tenant))
  const database = new URL(jdbc.replace(/^jdbc:/, '')).pathname.slice(1), stream = 'service-' + randomBytes(6).toString('hex')
  async function checkpoint() {
    const query = `SELECT completed_through || ',' || revision FROM ingestion.history_checkpoint WHERE tenant_id='${tenant}' AND stream_name='${stream}'`
    try {
      const { stdout } = await exec('docker', ['exec', '-e', 'PGPASSWORD', container, 'psql', '-U', user, '-d', database, '-At', '-v', 'ON_ERROR_STOP=1', '-c', query],
        { env: { ...process.env, PGPASSWORD: process.env.OPSWEAVE_TEST_JDBC_PASSWORD ?? '' }, windowsHide: true, timeout: 5000 })
      const value = stdout.trim(); return value ? value.split(',').map(Number) : null
    } catch { return null }
  }
  async function grant(enabled) {
    const now = Date.now()
    await writeFile(grantsFile, JSON.stringify({ schemaVersion: '1.0', grants: [{ issuer, clientId: 'history-worker', externalSubject: 'history-worker', subjectId: 'history-worker', tenantId: tenant,
      revision: enabled ? 1 : 2, enabled, sourceInstanceId: 'zabbix-1', itemIds: ['20001'], entityIds: [entity], metricKeys: ['host.cpu.usage.user'],
      validFrom: new Date(now - 60000).toISOString(), validUntil: new Date(now + 3600000).toISOString(), from: START, till: START + 3600, maxWindowSeconds: 3600, maxPoints: 500, requestsPerMinute: 120 }] }, null, 2))
  }
  const statuses = []; let sample, worker, stopAtPoll
  const proxy = createServer(async (req, res) => {
    try {
      assert.equal(req.method, 'GET'); assert(req.url.startsWith('/api/v1/service/ingestion/items/20001/history?')); assert(req.headers.authorization?.startsWith('Bearer '))
      assert(!req.headers.cookie && !req.headers.origin && !req.url.includes('tenant'))
      const upstream = await fetch(api + req.url, { headers: { Authorization: req.headers.authorization }, redirect: 'manual', signal: AbortSignal.timeout(20000) })
      const body = await upstream.text(); assert(body.length < 2097152); statuses.push(upstream.status)
      if (upstream.status === 200) { const parsed = JSON.parse(body); if (parsed.points.length) sample = parsed }
      res.writeHead(upstream.status, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' }); res.end(body)
    } catch { res.writeHead(502); res.end() }
  })
  proxy.listen(0, '127.0.0.1'); await once(proxy, 'listening')
  const workerOrigin = `http://127.0.0.1:${proxy.address().port}`, dir = path.join(root, 'apps/ingestion-worker/build/libs')
  const jars = (await readdir(dir)).filter(n => n.endsWith('.jar') && !n.endsWith('-plain.jar')); assert.equal(jars.length, 1)
  function start() {
    worker = spawn(java, ['-jar', path.join(dir, jars[0]), '--server.address=127.0.0.1', '--server.port=0'], { cwd: root, windowsHide: true, stdio: ['ignore', 'pipe', 'ignore'], env: { ...process.env,
      OPSWEAVE_HISTORY_ENABLED: 'true', OPSWEAVE_HISTORY_AUTH_MODE: 'client-credentials', OPSWEAVE_HISTORY_AUTH_LOOPBACK_TEST: 'true', OPSWEAVE_HISTORY_PLATFORM_URL: workerOrigin,
      OPSWEAVE_HISTORY_PLATFORM_TOKEN: '', OPSWEAVE_HISTORY_TOKEN_URI: issuer + '/token', OPSWEAVE_HISTORY_CLIENT_ID: 'history-worker', OPSWEAVE_HISTORY_CLIENT_SECRET: service.secret,
      OPSWEAVE_HISTORY_TENANT: tenant, OPSWEAVE_HISTORY_DATA_MODE: 'labeled-fixture', OPSWEAVE_HISTORY_SOURCE: 'zabbix-1', OPSWEAVE_HISTORY_ITEM_ID: '20001', OPSWEAVE_HISTORY_STREAM: stream,
      OPSWEAVE_HISTORY_INITIAL_FROM: String(START), OPSWEAVE_HISTORY_POLL_MILLIS: '1000', OPSWEAVE_HISTORY_STEP_SECONDS: '60', OPSWEAVE_HISTORY_OVERLAP_SECONDS: '120',
      OPSWEAVE_HISTORY_JDBC_URL: jdbc, OPSWEAVE_HISTORY_JDBC_USER: user, OPSWEAVE_HISTORY_JDBC_PASSWORD: process.env.OPSWEAVE_TEST_JDBC_PASSWORD ?? '', OPSWEAVE_HISTORY_VICTORIA_URL: vm } })
    // Windows kill is immediate. Wait for a terminal poll log (emitted after PG release/commit)
    // before stopping, so this normal-restart test does not accidentally exercise a 300s crash lease.
    // Discard every other log; no full process output is stored or printed.
    let pending = ''
    worker.stdout.setEncoding('utf8'); worker.stdout.on('data', chunk => {
      pending = (pending + chunk).slice(-16384)
      const lines = pending.split('\n'); pending = lines.pop()
      for (const line of lines) if (line.includes(`History batch confirmed: stream=${stream},`) || line.includes(`History poll failed: stream=${stream},`)) stopAtPoll?.()
    })
  }
  async function stop() {
    if (!worker || worker.exitCode !== null || worker.signalCode !== null) return
    const child = worker
    await new Promise((resolve, reject) => {
      const timer = setTimeout(() => { stopAtPoll = null; child.kill(); reject(new Error('Worker did not reach a bounded poll boundary before stop')) }, 35000)
      stopAtPoll = () => { stopAtPoll = null; child.kill() }
      child.once('exit', () => { clearTimeout(timer); stopAtPoll = null; resolve() })
    })
  }
  async function until(predicate, label) {
    for (let i = 0; i < 60; i++) { assert.equal(worker.exitCode, null, 'Owned Worker exited'); if (await predicate()) return; await delay(1000) }
    throw new Error(label + ' timed out')
  }
  try {
    await grant(true)
    const browserDenied = await browserContext.request.get(browserOrigin + `/api/v1/service/ingestion/items/20001/history?from=${START}&till=${START}&limit=500`)
    assert.equal(browserDenied.status(), 401, 'Browser cookie cannot authenticate machine endpoint')
    start(); await until(async () => (await checkpoint())?.[0] >= START && sample, 'Service checkpoint')
    assert.equal(sample.tenantId, tenant); assert.equal(sample.entityId, entity); assert.equal(sample.dataMode, 'labeled-fixture'); assert(service.exchanges >= 1)
    const query = new URLSearchParams({ 'match[]': `opsweave_metric_value{tenant_id="${tenant}",source_instance_id="zabbix-1",external_item_id="20001",data_mode="labeled-fixture"}`, start: String(START), end: String(START), reduce_mem_usage: '1' })
    const exported = await fetch(vm + '/api/v1/export?' + query, { signal: AbortSignal.timeout(5000) }); assert.equal(exported.status, 200)
    const rows = (await exported.text()).trim().split('\n').filter(Boolean).map(line => JSON.parse(line))
    assert(rows.some(row => row.metric.entity_id === entity && row.timestamps.some((t, i) => t === START * 1000 && row.values[i] === 0.4)))
    await writeFile(path.join(artifacts, 'service-metric-history-page.json'), JSON.stringify(sample, null, 2))
    console.log('PASS: Independent client_credentials JWT -> scoped Java history endpoint -> scheduled Worker -> verified VM fixture sample -> PG checkpoint; browser cookie denied.')
    await grant(false); const revokedStart = statuses.length
    await until(() => statuses.slice(revokedStart).filter(s => s === 403).length >= 3, 'Revocation')
    const revoked = await checkpoint(); assert(revoked)
    const readCount = statuses.length; await until(() => statuses.length >= readCount + 3, 'Denied polling'); assert.deepEqual(await checkpoint(), revoked)
    assert(statuses.slice(readCount).every(s => s === 403)); await stop()
    console.log('PASS: Live operator revocation rejects scheduled reads; completed cursor and revision remain unchanged across failed polls.')
    await grant(true); service.fault = true; const before = service.attempts, beforeReads = statuses.length
    start(); await until(() => service.attempts >= before + 3, 'Unavailable token provider')
    assert.equal(statuses.length, beforeReads); assert.deepEqual(await checkpoint(), revoked); await stop()
    console.log('PASS: Unavailable token provider on Worker restart performs no history read or fallback and preserves the PG checkpoint.')
    service.fault = false; service.secret = randomBytes(32).toString('hex'); const exchanges = service.exchanges
    start(); await until(async () => (await checkpoint())?.[0] > revoked[0], 'Credential rotation and resume')
    assert(service.exchanges > exchanges); const resumed = await checkpoint(); assert(resumed[1] > revoked[1]); await stop()
    await writeFile(path.join(artifacts, 'service-checkpoint-report.json'), JSON.stringify({ dataMode: 'labeled-fixture', authentication: 'oauth-client-credentials-protocol-fixture', tenantId: tenant,
      revoked: { completedThrough: revoked[0], revision: revoked[1] }, resumed: { completedThrough: resumed[0], revision: resumed[1] }, tokenExchanges: service.exchanges, rejectedReads: statuses.filter(s => s === 403).length }, null, 2))
    console.log('PASS: Rotated client secret with the same issuer/client/series resumes the existing PG checkpoint; no credential is part of the fingerprint or artifact.')
  } finally { await stop(); proxy.closeAllConnections(); await new Promise(resolve => proxy.close(resolve)) }
}
