// All servers and credentials below are explicit protocol fixtures, never real acceptance.
import test, { after } from 'node:test'
import assert from 'node:assert/strict'
import { createServer } from 'node:http'
import { randomUUID } from 'node:crypto'
import { readFile, writeFile, mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { execFile } from 'node:child_process'
import { promisify } from 'node:util'
import { fileURLToPath } from 'node:url'
import { runAcceptance } from '../../scripts/acceptance/acceptance-runner.mjs'

const entityId = '11111111-1111-1111-1111-111111111111'
const incidentId = '55555555-5555-3555-8555-555555555555'
const tenant = 'tenant-fixture'
const metricKey = 'host.cpu.usage.user'
const hostId = randomUUID(), itemId = randomUUID()
const secret = 'fixture-secret-never-log'
const privateText = 'fixture-customer-text-never-log'
const pin = { id: 'zabbix-host-v1', revision: 1, digest: 'sha256:' + 'a'.repeat(64) }
const insightExample = JSON.parse(await readFile(new URL('../../contracts/examples/ai-insight-result.json', import.meta.url), 'utf8'))
const evidenceExample = JSON.parse(await readFile(new URL('../../contracts/examples/platform-evidence.json', import.meta.url), 'utf8'))
const generatedReports = []
after(async () => {
  // Optional artifact for independent JSON Schema validation. Contains only protocol fixture reports.
  if (process.env.OPSWEAVE_TEST_ACCEPTANCE_REPORTS) {
    await writeFile(process.env.OPSWEAVE_TEST_ACCEPTANCE_REPORTS, JSON.stringify(generatedReports, null, 2))
  }
})

async function fixture(t, change = () => {}, options = {}) {
  const requests = [], logs = []
  let saved, serverError
  const server = createServer(async (req, res) => {
    try {
      const url = new URL(req.url, 'http://fixture.invalid')
      const pathname = url.pathname
      let raw = ''; for await (const chunk of req) raw += chunk
      const input = raw ? JSON.parse(raw) : undefined
      requests.push({ pathname, method: req.method, input })
      assert.equal(req.headers.authorization, `Bearer ${secret}`)
      let body
      if (pathname.endsWith('/connection-checks')) body = { dataMode: 'connection-check', check: {
        checkId: randomUUID(), reachable: true, dataMode: 'zabbix-jsonrpc', reportedVersion: '7.0.0' } }
      else if (pathname.endsWith('/hosts/sync') || pathname.endsWith('/items/sync')) {
        const host = pathname.includes('/hosts/')
        body = { syncRunId: host ? hostId : itemId, snapshotComplete: true, inventoryStore: 'postgres',
          dataMode: 'zabbix-jsonrpc', scanConsistency: host ? 'hostid-watermark-snapshot' : 'itemid-watermark-snapshot',
          ...(host ? { pipelineVersion: pin } : {}) }
      } else if (pathname.includes('/runs/')) {
        const host = pathname.includes('/hosts/')
        body = { run: { syncRunId: host ? hostId : itemId, snapshotComplete: true, status: 'SUCCEEDED',
          dataMode: 'zabbix-jsonrpc', scanConsistency: host ? 'hostid-watermark-snapshot' : 'itemid-watermark-snapshot',
          ...(host ? { pipelineVersion: pin } : {}) } }
      } else if (pathname === '/api/v1/entities/page') body = { items: [{ id: entityId }] }
      else if (pathname.endsWith('/series')) {
        const from = Number(url.searchParams.get('from')), till = Number(url.searchParams.get('till'))
        body = { entityId, metricKey, from, till, status: { kind: 'AVAILABLE' },
          series: [{ dataMode: 'zabbix-jsonrpc', points: [[(till - 10) * 1000, '0.8']] }] }
      } else if (pathname === `/api/v1/entities/${entityId}`) body = { id: entityId, tenantId: tenant, name: privateText }
      else if (pathname.endsWith('/problems/ingest')) body = { accepted: 1 }
      else if (pathname === '/api/v1/incidents') body = { items: [{ id: incidentId }] }
      else if (pathname === `/api/v1/incidents/${incidentId}`) body = { record: {
        incident: { id: incidentId, tenantId: tenant, version: 1 },
        problems: [{ dataMode: 'zabbix-jsonrpc', entities: [{ entityId }], observation: { title: privateText } }] } }
      else if (pathname === '/api/v1/ai/diagnoses') {
        body = structuredClone(insightExample)
        const now = new Date().toISOString()
        Object.assign(body.record, { id: input.runId, tenantId: tenant, incidentId, incidentVersion: 1,
          question: input.question, queryWindow: input.timeRange, entityIds: [entityId], dataModes: ['zabbix-jsonrpc'],
          asOf: now, builtAt: now, completedAt: now, savedAt: now, expiresAt: new Date(Date.now() + 60000).toISOString(),
          model: { provider: 'rig-openai', name: 'protocol-fixture-no-real-model' } })
        body.record.insight.summary = privateText
      } else if (pathname.startsWith('/api/v1/ai/insights/')) body = structuredClone(saved)
      else if (pathname.startsWith('/api/v1/ai/evidence/')) {
        body = structuredClone(evidenceExample)
        const id = pathname.split('/').at(-1)
        const kind = id === saved.record.evidenceIds[0] ? 'incident' : 'metric'
        Object.assign(body, { sessionId: saved.record.sessionId, incidentVersion: 1, entityIds: [entityId],
          queryWindow: saved.record.queryWindow, dataModes: ['zabbix-jsonrpc'],
          producerTool: kind === 'incident' ? 'incident.get@2.0.0' : 'metric.summary@2.0.0' })
        Object.assign(body.evidence, { id, tenantId: tenant, incidentId, kind, sourceRef: pathname,
          observedAt: saved.record.asOf, availableAt: saved.record.asOf, expiresAt: saved.record.expiresAt, summary: privateText })
        body.data = kind === 'metric' ? { metricKey, sampleCount: 1, entities: [{ entityId, series: [{ count: 1 }] }] } : { title: privateText }
      } else throw new Error('Unexpected fixture request')
      const response = { body, status: 200, headers: { 'Content-Type': 'application/json', 'Cache-Control': 'no-store',
        'X-Content-Type-Options': 'nosniff', 'X-Opsweave-Request-Id': randomUUID() } }
      change(pathname, body, response)
      if (pathname === '/api/v1/ai/diagnoses') saved = structuredClone(response.body)
      res.writeHead(response.status, response.headers)
      res.end(response.raw ?? JSON.stringify(response.body))
    } catch (error) { serverError = error; res.writeHead(500); res.end('fixture failure') }
  })
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
  t.after(() => { server.closeAllConnections(); return new Promise(resolve => server.close(resolve)) })
  const env = { OPSWEAVE_ACCEPTANCE_URL: `http://127.0.0.1:${server.address().port}`,
    OPSWEAVE_ACCEPTANCE_TOKEN: secret, OPSWEAVE_ACCEPTANCE_METRIC: metricKey,
    OPSWEAVE_ACCEPTANCE_ENTITY: entityId, OPSWEAVE_ACCEPTANCE_INCIDENT: incidentId,
    OPSWEAVE_ACCEPTANCE_ZABBIX_VERSION: '7.0.0', ...options.env }
  const result = await runAcceptance(env, options.args ?? [], line => logs.push(line))
  generatedReports.push(result.report)
  if (serverError) throw serverError
  assert.ok(!JSON.stringify({ result, logs }).includes(secret))
  assert.ok(!JSON.stringify({ result, logs }).includes(privateText))
  return { ...result, requests, logs }
}

test('protocol fixture can pass probes but cannot claim real acceptance or milestone completion', async t => {
  const result = await fixture(t)
  assert.equal(result.exitCode, 0)
  assert.equal(result.report.mode, 'real-candidate')
  assert.equal(result.report.status, 'passed')
  assert.equal(result.report.milestonesSatisfied, false)
  assert.equal(result.report.unverified.length, 5)
  assert.deepEqual(result.report.steps.map(s => s.id), ['S1', 'S2', 'S3', 'S4', 'S5', 'S6', 'S7'])
  assert.equal(result.requests.filter(r => r.pathname.startsWith('/api/v1/ai/evidence/')).length, 2)
  assert.equal(result.report.steps.at(-1).requestIds.length, 4)
  assert.equal(result.requests.filter(r => r.pathname.endsWith('/diagnoses')).length, 1)
  assert.ok(!result.requests.some(r => r.pathname === '/api/v1/entities/page'))
  assert.ok(Date.parse(result.report.startedAt) <= Date.parse(result.report.completedAt))
})

test('explicit rehearsal preserves fixture and mock even when no targets were specified', async t => {
  const result = await fixture(t, (pathname, body) => {
    if (pathname.endsWith('/connection-checks')) Object.assign(body.check, { dataMode: 'labeled-fixture', reportedVersion: null })
    if (pathname.endsWith('/diagnoses')) body.record.model.provider = 'mock-deterministic'
  }, { args: ['--rehearsal'], env: { OPSWEAVE_ACCEPTANCE_ENTITY: '', OPSWEAVE_ACCEPTANCE_INCIDENT: '' } })
  assert.equal(result.exitCode, 0)
  assert.equal(result.report.mode, 'rehearsal')
  assert.equal(result.report.sourceDataMode, 'labeled-fixture')
  assert.equal(result.report.modelProvider, 'mock-deterministic')
})

const negatives = [
  ['fixture source', '/connection-checks', b => { b.check.dataMode = 'labeled-fixture'; b.check.reportedVersion = null }, 'FIXTURE_REQUIRES_REHEARSAL', 2],
  ['unknown source', '/connection-checks', b => { b.check.dataMode = 'unknown' }, 'DATA_MODE_INVALID'],
  ['wrong source version', '/connection-checks', b => { b.check.reportedVersion = '7.2.0' }, 'EXPECTED_SOURCE_VERSION_REQUIRED_OR_MISMATCH'],
  ['source version with a trailing newline', '/connection-checks', b => { b.check.reportedVersion = '7.0.0\n' }, 'SOURCE_VERSION_INVALID'],
  ['unreachable source', '/connection-checks', b => { b.check.reachable = false }, 'SOURCE_CHECK_FAILED'],
  ['fixture host data', '/hosts/sync', b => { b.dataMode = 'labeled-fixture' }, 'FIXTURE_REQUIRES_REHEARSAL', 2],
  ['missing pipeline pin', '/hosts/sync', b => { delete b.pipelineVersion }, 'PUBLISHED_PIPELINE_REQUIRED'],
  ['pipeline pin with a trailing newline', '/hosts/sync', b => { b.pipelineVersion = { ...pin, id: 'zabbix-host-v1\n' } }, 'PUBLISHED_PIPELINE_REQUIRED'],
  ['failed host snapshot', '/hosts/sync', b => { b.snapshotComplete = false }, 'HOST_SNAPSHOT_INVALID'],
  ['changed pipeline pin', `/hosts/runs/${hostId}`, b => { b.run.pipelineVersion = { ...pin, revision: 2 } }, 'HOST_TRACE_MISMATCH'],
  ['failed item trace', `/items/runs/${itemId}`, b => { b.run.status = 'FAILED' }, 'ITEM_TRACE_MISMATCH'],
  ['fixture metric data', '/series', b => { b.series[0].dataMode = 'labeled-fixture' }, 'FIXTURE_REQUIRES_REHEARSAL', 2],
  ['wrong metric entity', '/series', b => { b.entityId = randomUUID() }, 'METRIC_SCOPE_INVALID'],
  ['missing samples', '/series', b => { b.series[0].points = [] }, 'METRIC_SAMPLES_REQUIRED'],
  ['invalid sample', '/series', b => { b.series[0].points[0][1] = 'NaN' }, 'METRIC_SAMPLE_INVALID'],
  ['unlinked incident', `/incidents/${incidentId}`, b => { b.record.problems[0].entities = [] }, 'INCIDENT_ASSET_NOT_LINKED'],
  ['empty incident', `/incidents/${incidentId}`, b => { b.record.problems = [] }, 'INCIDENT_SCOPE_INVALID'],
  ['foreign tenant', `/incidents/${incidentId}`, b => { b.record.incident.tenantId = 'other' }, 'INCIDENT_SCOPE_INVALID'],
  ['mock model with declared JSON-RPC source', '/diagnoses', b => { b.record.model.provider = 'mock-deterministic' }, 'REAL_MODEL_REQUIRED'],
  ['unknown model', '/diagnoses', b => { b.record.model.provider = 'unknown' }, 'MODEL_PROVIDER_INVALID'],
  ['unknown insight provenance', '/diagnoses', b => { b.record.dataModes = ['unknown'] }, 'REAL_SOURCE_REQUIRED'],
  ['fixture insight provenance', '/diagnoses', b => { b.record.dataModes = ['zabbix-jsonrpc', 'labeled-fixture'] }, 'FIXTURE_REQUIRES_REHEARSAL', 2],
  ['wrong run receipt', '/diagnoses', b => { b.record.id = randomUUID() }, 'INSIGHT_SCOPE_INVALID'],
  ['expired insight', '/diagnoses', b => { b.record.expiresAt = '2000-01-01T00:00:00Z' }, 'INSIGHT_TIME_INVALID'],
  ['fabricated finding reference', '/diagnoses', b => { b.record.insight.findings[0].evidenceRefs = [randomUUID()] }, 'INSIGHT_REFERENCES_INVALID'],
]
for (const [name, suffix, mutate, error, code = 1] of negatives) test(`refuses ${name} without retry or a real report`, async t => {
  const result = await fixture(t, (pathname, body) => { if (pathname.endsWith(suffix)) mutate(body) })
  assert.equal(result.exitCode, code)
  assert.equal(result.report.errorCode, error)
  assert.equal(result.report.mode, 'unverified')
  assert.equal(result.report.status, 'failed')
  assert.equal(result.requests.filter(r => r.pathname.endsWith(suffix)).length, 1)
  assert.ok(result.report.steps.length < 7)
})

const evidenceNegatives = [
  ['revoked evidence', (_b, r) => { r.status = 403; r.raw = privateText }, 'HTTP_403'],
  ['foreign evidence session', b => { b.sessionId = randomUUID() }, 'EVIDENCE_SCOPE_INVALID'],
  ['wrong evidence window', b => { b.queryWindow = { ...b.queryWindow, from: '2000-01-01T00:00:00Z' } }, 'EVIDENCE_SCOPE_INVALID'],
  ['future available evidence', b => { b.evidence.availableAt = '2100-01-01T00:00:00Z' }, 'EVIDENCE_TIME_INVALID'],
  ['expired evidence', b => { b.evidence.expiresAt = '2000-01-01T00:00:00Z' }, 'EVIDENCE_TIME_INVALID'],
  ['fixture evidence', b => { b.dataModes = ['labeled-fixture'] }, 'FIXTURE_REQUIRES_REHEARSAL', 2],
  ['untrusted sourceRef redirect', b => { b.evidence.sourceRef = 'https://example.invalid/secret' }, 'EVIDENCE_SCOPE_INVALID'],
  ['metric evidence without samples', b => { if (b.evidence.kind === 'metric') b.data.sampleCount = 0 }, 'DIAGNOSIS_METRIC_SAMPLES_REQUIRED'],
]
for (const [name, mutate, error, code = 1] of evidenceNegatives) test(`reads back and refuses ${name}`, async t => {
  const result = await fixture(t, (pathname, body, response) => { if (pathname.includes('/ai/evidence/')) mutate(body, response) })
  assert.equal(result.exitCode, code)
  assert.equal(result.report.errorCode, error)
  assert.equal(result.report.steps.length, 6)
  assert.equal(result.report.mode, 'unverified')
})

test('changed saved result fails even when the identifier is unchanged', async t => {
  const result = await fixture(t, (pathname, body) => { if (pathname.includes('/ai/insights/')) body.record.insight.summary = 'changed' })
  assert.equal(result.report.errorCode, 'INSIGHT_READBACK_MISMATCH')
})
test('an honest result with no findings still verifies evidence without forcing a model claim', async t => {
  const result = await fixture(t, (pathname, body) => { if (pathname.endsWith('/diagnoses')) body.record.insight.findings = [] })
  assert.equal(result.exitCode, 0)
  assert.equal(result.report.milestonesSatisfied, false)
  assert.equal(result.requests.filter(r => r.pathname.includes('/ai/evidence/')).length, 2)
})
test('published pipeline identifiers retain the canonical uppercase and underscore support', async t => {
  const result = await fixture(t, (pathname, body) => {
    if (pathname.endsWith('/hosts/sync')) body.pipelineVersion = { ...pin, id: 'Zabbix_host_v1' }
    if (pathname.includes('/hosts/runs/')) body.run.pipelineVersion = { ...pin, id: 'Zabbix_host_v1' }
  })
  assert.equal(result.exitCode, 0)
})
test('redirects are not followed, even within the authorized origin', async t => {
  const result = await fixture(t, (pathname, _body, response) => {
    if (pathname.endsWith('/connection-checks')) { response.status = 307; response.headers.Location = '/redirect-target'; response.raw = secret }
  })
  assert.equal(result.report.errorCode, 'HTTP_307')
  assert.equal(result.requests.length, 1)
})
test('failed HTTP response bodies do not enter logs or reports', async t => {
  const result = await fixture(t, (_pathname, _body, response) => { response.status = 401; response.raw = secret + privateText })
  assert.equal(result.report.errorCode, 'HTTP_401')
  assert.equal(result.report.steps.length, 0)
})
test('oversized and malformed bodies fail with safe codes', async t => {
  for (const [raw, code] of [['x'.repeat(262145), 'RESPONSE_TOO_LARGE'], [secret, 'RESPONSE_JSON_INVALID']]) {
    const result = await fixture(t, (_p, _b, r) => { r.raw = raw })
    assert.equal(result.report.errorCode, code)
    assert.equal(result.requests.length, 1)
  }
})
test('missing cache boundary fails before any source sync', async t => {
  const result = await fixture(t, (_p, _b, r) => { delete r.headers['Cache-Control'] })
  assert.equal(result.report.errorCode, 'RESPONSE_CACHE_BOUNDARY_INVALID')
  assert.equal(result.requests.length, 1)
})
test('remote HTTP, embedded credentials and URL path are rejected before any requests', async () => {
  for (const url of ['http://example.invalid', 'https://user:password@example.invalid', 'http://127.0.0.1/path']) {
    const { report, exitCode } = await runAcceptance({ OPSWEAVE_ACCEPTANCE_URL: url, OPSWEAVE_ACCEPTANCE_ALLOW_REMOTE: 'true' }, [], () => {})
    assert.equal(exitCode, 1); assert.equal(report.failedStep, 'CONFIG'); assert.equal(report.mode, 'unverified')
  }
})
test('CLI writes a failed report for missing configuration without exposing configuration values', async () => {
  const directory = await mkdtemp(path.join(tmpdir(), 'opsweave-acceptance-'))
  try {
    const target = path.join(directory, 'report.json')
    const env = { ...process.env }; for (const name of Object.keys(env)) if (name.startsWith('OPSWEAVE_ACCEPTANCE_')) delete env[name]
    const result = await promisify(execFile)(process.execPath, [fileURLToPath(new URL('../../scripts/acceptance/real-acceptance.mjs', import.meta.url)), `--report=${target}`], { env, windowsHide: true }).catch(e => e)
    assert.equal(result.code, 1)
    const report = JSON.parse(await readFile(target, 'utf8'))
    assert.equal(report.errorCode, 'PLATFORM_URL_REQUIRED'); assert.equal(report.mode, 'unverified')
  } finally {
    // mkdtemp returned this exact owned directory under the OS temporary directory.
    assert.equal(path.dirname(path.resolve(directory)), path.resolve(tmpdir()))
    await rm(directory, { recursive: true, force: true })
  }
})
