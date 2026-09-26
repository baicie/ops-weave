// Ordered read-only acceptance probe for the M2–M4 exit conditions. It calls the platform the same way
// the console does, records every step's evidence, and never retries a failed step.
//
// It refuses to produce a "real" report from a fixture source: when the connection self-check reports
// `labeled-fixture`, the run is only allowed with --rehearsal and the report is marked `rehearsal`.
// Nothing here contacts Zabbix, a model or a database directly, and nothing here writes to a source.
import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import { mkdir, writeFile } from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = fileURLToPath(new URL('../../', import.meta.url))
const args = new Set(process.argv.slice(2))
const rehearsalRequested = args.has('--rehearsal')
const reportArg = process.argv.find(value => value.startsWith('--report='))
const platformUrl = (process.env.OPSWEAVE_ACCEPTANCE_URL ?? '').replace(/\/$/, '')
const token = process.env.OPSWEAVE_ACCEPTANCE_TOKEN ?? ''
const metricKey = process.env.OPSWEAVE_ACCEPTANCE_METRIC ?? ''
const entityInput = process.env.OPSWEAVE_ACCEPTANCE_ENTITY ?? ''
const incidentInput = process.env.OPSWEAVE_ACCEPTANCE_INCIDENT ?? ''
const allowRemote = process.env.OPSWEAVE_ACCEPTANCE_ALLOW_REMOTE === 'true'
const reportPath = reportArg
  ? path.resolve(reportArg.slice('--report='.length))
  : path.join(root, '.tmp/acceptance', `${new Date().toISOString().replaceAll(':', '-')}-report.json`)

assert(platformUrl, 'OPSWEAVE_ACCEPTANCE_URL is required')
assert(token, 'OPSWEAVE_ACCEPTANCE_TOKEN is required (development bearer token)')
const origin = new URL(platformUrl)
assert(['http:', 'https:'].includes(origin.protocol), 'The acceptance URL must be http(s)')
assert(!origin.username && !origin.password && !origin.search, 'The acceptance URL must not embed credentials or query')
if (!allowRemote) {
  assert(['127.0.0.1', 'localhost', '[::1]'].includes(origin.hostname),
    'Only a loopback platform is accepted unless OPSWEAVE_ACCEPTANCE_ALLOW_REMOTE=true')
}

const steps = []
function record(id, title, detail, evidence) {
  steps.push({ id, title, detail, evidence })
  console.log(`PASS ${id} ${title} — ${detail}`)
}

async function call(pathname, { method = 'GET', body, timeoutMs = 20000 } = {}) {
  const response = await fetch(platformUrl + pathname, {
    method,
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(timeoutMs),
  })
  const requestId = response.headers.get('x-opsweave-request-id')
  const text = await response.text()
  assert(response.ok, `${method} ${pathname} failed with ${response.status}: ${text.slice(0, 200)}`)
  assert(requestId, `${method} ${pathname} did not return a request id`)
  return { body: text ? JSON.parse(text) : null, requestId }
}

let sourceDataMode = null
const unverified = [
  '人工抽样审阅（必须由人完成，脚本不能代替）',
  '真实模型提供方账单与配额对账',
  '真实 IdP/HTTPS/反向代理验收（见 OIDC runbook）',
]

try {
  // S1: the source self-check decides whether this run may call itself real.
  const check = (await call('/api/v1/integrations/zabbix/connection-checks', { method: 'POST' })).body
  sourceDataMode = check.check.dataMode
  assert(check.dataMode === 'connection-check', 'The self-check envelope is not a connection-check')
  assert(check.check.reachable === true, `The source is not reachable (${check.check.statusCode})`)
  const rehearsal = rehearsalRequested || sourceDataMode === 'labeled-fixture'
  if (sourceDataMode === 'labeled-fixture' && !rehearsalRequested) {
    console.error('The source self-check reports labeled-fixture. Re-run with --rehearsal to rehearse, or configure a real source; a fixture run is never a real acceptance.')
    process.exitCode = 2
    throw new Error('Fixture source requires --rehearsal')
  }
  record('S1', '来源连接自检', `reachable, dataMode=${sourceDataMode}, reportedVersion=${check.check.reportedVersion ?? '无版本声明'}`,
    { statusCode: check.check.statusCode, reportedVersion: check.check.reportedVersion, checkId: check.check.checkId })

  // S2: the declared source completes a bounded, watermarked host snapshot.
  const hostSync = (await call('/api/v1/integrations/zabbix/hosts/sync', { method: 'POST' })).body
  assert(hostSync.snapshotComplete === true, 'The host scan did not complete a snapshot')
  assert(hostSync.scanConsistency === 'hostid-watermark-snapshot', `Unexpected host scan consistency ${hostSync.scanConsistency}`)
  const hostTrace = (await call(`/api/v1/integrations/zabbix/hosts/runs/${hostSync.syncRunId}`)).body
  assert(hostTrace.run.status === 'SUCCEEDED' && hostTrace.run.scanConsistency === 'hostid-watermark-snapshot',
    'The stored host run does not keep the verified snapshot label')
  record('S2', 'Host 分页与水位快照', `accepted=${hostSync.accepted}, pages=${hostSync.pages}, retired=${hostSync.retired}`,
    { syncRunId: hostSync.syncRunId, dataMode: hostSync.dataMode, pipelineVersion: hostSync.pipelineVersion ?? null })

  // S3: item scans carry their own verified bound.
  const itemSync = (await call('/api/v1/integrations/zabbix/items/sync', { method: 'POST' })).body
  assert(itemSync.snapshotComplete === true, 'The item scan did not complete a snapshot')
  assert(itemSync.scanConsistency === 'itemid-watermark-snapshot', `Unexpected item scan consistency ${itemSync.scanConsistency}`)
  record('S3', 'Item 采集与水位快照', `accepted=${itemSync.accepted}, rejected=${itemSync.rejected}, retired=${itemSync.retired}`,
    { syncRunId: itemSync.syncRunId, dataMode: itemSync.dataMode })

  // S4: an authorized asset is readable through the bounded page endpoint.
  const entities = (await call('/api/v1/entities/page?limit=5')).body
  assert(Array.isArray(entities.items) && entities.items.length >= 1, 'No authorized asset was returned')
  const entity = entityInput ? entities.items.find(item => item.id === entityInput) : entities.items[0]
  assert(entity, entityInput ? `OPSWEAVE_ACCEPTANCE_ENTITY ${entityInput} is not among the authorized assets` : 'No authorized asset was returned')
  record('S4', '资产读取', `entity=${entity.id}, name=${entity.name}`, { dataMode: entity.dataMode ?? null })

  // S5: one declared metric returns stored samples for that asset.
  assert(metricKey, 'OPSWEAVE_ACCEPTANCE_METRIC is required: name the metric that must have real samples')
  const till = Math.floor(Date.now() / 1000)
  const series = (await call(`/api/v1/entities/${entity.id}/metrics/${encodeURIComponent(metricKey)}/series?from=${till - 3600}&till=${till}&maxPoints=500`)).body
  assert(series.status.kind === 'AVAILABLE', `The metric is ${series.status.kind}, not AVAILABLE`)
  assert(series.series.length >= 1 && series.series[0].points.length >= 1, 'The metric has no stored points')
  const resets = series.series.flatMap(row => (row.counterRates ?? []).filter(rate => rate.counterReset)).length
  record('S5', '指标读取', `metric=${metricKey}, series=${series.series.length}, derivation=${series.derivation?.kind ?? 'raw'}, resets=${resets}`,
    { unit: series.unit, lastPointAt: series.status.lastPointAt })

  // S6: the incident that will be diagnosed is readable with its problems.
  let incidentId = incidentInput
  if (!incidentId) {
    const window = { from: till - 86400, till, afterEventId: null, limit: 100 }
    await call('/api/v1/integrations/zabbix/problems/ingest', { method: 'POST', body: window })
    const incidents = (await call('/api/v1/incidents?limit=1')).body
    assert(incidents.items.length >= 1, 'No incident is available for diagnosis')
    incidentId = incidents.items[0].id
  }
  const incident = (await call(`/api/v1/incidents/${incidentId}`)).body
  assert(incident.record && incident.record.incident, 'The incident is not readable')
  record('S6', 'Incident 与关联告警', `incident=${incidentId}, problems=${incident.record.problems.length}`,
    { status: incident.record.incident.status, version: incident.record.incident.version })

  // S7: one bounded diagnosis is saved and can be read back with authorized evidence.
  const diagnose = (await call('/api/v1/ai/diagnoses', {
    method: 'POST', timeoutMs: 90000,
    body: { runId: randomUUID(), incidentId, question: 'Summarize the current authorized evidence for this incident.',
      // The runtime takes an RFC3339 window, not epoch seconds.
      timeRange: { from: new Date((till - 3600) * 1000).toISOString(), to: new Date(till * 1000).toISOString() },
      knowledgeMode: 'current' },
  })).body
  assert(diagnose.storage === 'postgres', 'The diagnosis was not stored in PostgreSQL')
  assert(diagnose.record.evidenceIds.length >= 1, 'The saved diagnosis has no evidence references')
  const insight = (await call(`/api/v1/ai/insights/${diagnose.record.id}`)).body
  assert(insight.record.id === diagnose.record.id, 'The saved diagnosis cannot be read back')
  record('S7', '只读诊断与 AIInsight', `insight=${diagnose.record.id}, evidence=${diagnose.record.evidenceIds.length}, provider=${diagnose.record.model.provider}`,
    { model: diagnose.record.model, sessionId: diagnose.record.sessionId })

  const mode = rehearsal ? 'rehearsal' : 'real'
  const report = {
    schemaVersion: '1.0',
    mode,
    startedAt: new Date().toISOString(),
    platformUrl,
    sourceDataMode,
    steps,
    exitConditions: {
      'M2 来源版本与完整快照': 'S1+S2',
      'M2 重复同步与失败不误删': 'S2+S3（失败路径见扫描追溯 runbook）',
      'M3 指标与 Incident 关联': 'S5+S6',
      'M4 只读诊断、结果与证据': 'S7',
    },
    unverified,
  }
  await mkdir(path.dirname(reportPath), { recursive: true })
  await writeFile(reportPath, JSON.stringify(report, null, 2))
  console.log(`\nmode=${mode}; report=${reportPath}`)
  console.log(`unverified: ${unverified.join(' / ')}`)
  if (mode === 'rehearsal') {
    console.log('This run used a labeled fixture source: it rehearses the acceptance path and is NOT real acceptance.')
  }
} catch (failed) {
  const report = {
    schemaVersion: '1.0',
    mode: sourceDataMode === 'labeled-fixture' ? 'rehearsal' : 'real',
    failed: true,
    startedAt: new Date().toISOString(),
    platformUrl,
    sourceDataMode,
    steps,
    error: failed instanceof Error ? failed.message : String(failed),
    unverified,
  }
  await mkdir(path.dirname(reportPath), { recursive: true })
  await writeFile(reportPath, JSON.stringify(report, null, 2))
  console.error(`\nFAILED after ${steps.length} step(s): ${report.error}`)
  console.error(`report=${reportPath}`)
  if (process.exitCode !== 2) process.exitCode = 1
}
