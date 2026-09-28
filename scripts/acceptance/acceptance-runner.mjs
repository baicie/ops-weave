// Passing platform probes are candidates for human review, never milestone sign-off.
import { randomUUID } from 'node:crypto'
import { isDeepStrictEqual } from 'node:util'

const uuid = v => typeof v === 'string' && /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?![\s\S])/.test(v)
const list = (v, min, max) => Array.isArray(v) && v.length >= min && v.length <= max
const ids = (v, min, max) => list(v, min, max) && v.every(uuid) && new Set(v).size === v.length
const instant = v => typeof v === 'string' && /^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,9})?Z(?![\s\S])/.test(v) && Number.isFinite(Date.parse(v))
const sameWindow = (a, b) => a && b && instant(a.from) && instant(a.to)
  && Date.parse(a.from) === Date.parse(b.from) && Date.parse(a.to) === Date.parse(b.to)
const sameIds = (a, b) => ids(a, 0, 5) && ids(b, 0, 5) && a.length === b.length && a.every(id => b.includes(id))
class ProbeFailure extends Error {
  constructor(code, exitCode = 1) { super(code); this.code = code; this.exitCode = exitCode }
}
function check(ok, code) { if (!ok) throw new ProbeFailure(code) }

// Never log an upstream body, credential, source text, model output or arbitrary exception.
async function call(origin, token, pathname, requestIds, { method = 'GET', body, timeoutMs = 20000 } = {}) {
  let response
  try {
    response = await fetch(new URL(pathname, origin), {
      method, redirect: 'manual', signal: AbortSignal.timeout(timeoutMs),
      headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
    })
    if (!response.ok) throw new ProbeFailure(`HTTP_${response.status}`)
    const requestId = response.headers.get('x-opsweave-request-id')
    check(uuid(requestId), 'RESPONSE_REQUEST_ID_INVALID')
    check(response.headers.get('cache-control')?.split(',').some(v => v.trim() === 'no-store')
      && response.headers.get('x-content-type-options') === 'nosniff', 'RESPONSE_CACHE_BOUNDARY_INVALID')
    const reader = response.body?.getReader()
    check(reader, 'RESPONSE_BODY_MISSING')
    const chunks = []; let bytes = 0
    try {
      while (true) {
        const { done, value } = await reader.read()
        if (done) break
        bytes += value.byteLength
        check(bytes <= 262144, 'RESPONSE_TOO_LARGE')
        chunks.push(value)
      }
    } finally { await reader.cancel().catch(() => {}) }
    let result
    try { result = JSON.parse(Buffer.concat(chunks).toString('utf8')) }
    catch { throw new ProbeFailure('RESPONSE_JSON_INVALID') }
    requestIds.push(requestId)
    return result
  } catch (error) {
    await response?.body?.cancel().catch(() => {})
    if (error instanceof ProbeFailure) throw error
    throw new ProbeFailure('TRANSPORT_FAILED')
  }
}

export async function runAcceptance(env, args = [], log = console.log) {
  const rehearsal = args.includes('--rehearsal')
  const report = {
    schemaVersion: '2.0', mode: rehearsal ? 'rehearsal' : 'unverified', status: 'failed',
    startedAt: new Date().toISOString(), completedAt: null, sourceDataMode: null, modelProvider: null,
    steps: [], failedStep: 'CONFIG', errorCode: null, milestonesSatisfied: false,
    unverified: [
      '目标环境真实性、Zabbix 版本兼容与分页语义需要独立核对；自报版本不是兼容性证明',
      '重复同步、失败不误删除及授权负例需要独立回归；单次成功扫描不证明这些条件',
      '人工抽样审阅（包括结论是否被证据支持，脚本不能代替）',
      '真实模型提供方账单与配额对账',
      '真实 IdP/HTTPS/反向代理与浏览器会话验收（见 OIDC runbook）',
    ],
  }
  let exitCode = 1
  let requestIds = []
  const stage = id => { report.failedStep = id; requestIds = [] }
  const record = (id, evidence) => { report.steps.push({ id, requestIds: [...requestIds], evidence }); log(`PASS ${id}`) }
  const modes = values => {
    check(list(values, 1, 3) && new Set(values).size === values.length
      && values.every(v => ['labeled-fixture', 'zabbix-jsonrpc', 'unknown'].includes(v)), 'DATA_MODE_INVALID')
    if (!rehearsal && values.includes('labeled-fixture')) throw new ProbeFailure('FIXTURE_REQUIRES_REHEARSAL', 2)
    check(rehearsal || values.every(v => v === 'zabbix-jsonrpc'), 'REAL_SOURCE_REQUIRED')
  }
  try {
    check(args.every(arg => arg === '--rehearsal' || arg.startsWith('--report=')), 'ARGUMENT_INVALID')
    let origin
    try { origin = new URL(env.OPSWEAVE_ACCEPTANCE_URL) } catch { throw new ProbeFailure('PLATFORM_URL_REQUIRED') }
    check(['http:', 'https:'].includes(origin.protocol) && !origin.username && !origin.password
      && origin.pathname === '/' && !origin.search && !origin.hash, 'PLATFORM_ORIGIN_INVALID')
    const loopback = ['127.0.0.1', 'localhost', '[::1]'].includes(origin.hostname)
    check(loopback || (env.OPSWEAVE_ACCEPTANCE_ALLOW_REMOTE === 'true' && origin.protocol === 'https:'), 'REMOTE_HTTPS_REQUIRED')
    const token = env.OPSWEAVE_ACCEPTANCE_TOKEN
    check(typeof token === 'string' && token.length > 0 && token.length <= 8192 && !/[\r\n]/.test(token), 'PLATFORM_TOKEN_REQUIRED')
    const metric = env.OPSWEAVE_ACCEPTANCE_METRIC
    check(typeof metric === 'string' && /^[A-Za-z0-9_.:/%\-]{1,128}(?![\s\S])/.test(metric), 'METRIC_REQUIRED')
    const entityInput = env.OPSWEAVE_ACCEPTANCE_ENTITY, incidentInput = env.OPSWEAVE_ACCEPTANCE_INCIDENT
    check(!entityInput || uuid(entityInput), 'ENTITY_ID_INVALID')
    check(!incidentInput || uuid(incidentInput), 'INCIDENT_ID_INVALID')
    check(rehearsal || (uuid(entityInput) && uuid(incidentInput)), 'EXPLICIT_TARGETS_REQUIRED')
    const api = (pathname, options) => call(origin, token, pathname, requestIds, options)

    stage('S1')
    const connection = await api('/api/v1/integrations/zabbix/connection-checks', { method: 'POST' })
    check(connection?.dataMode === 'connection-check' && connection.check?.reachable === true && uuid(connection.check.checkId), 'SOURCE_CHECK_FAILED')
    check(['labeled-fixture', 'zabbix-jsonrpc'].includes(connection.check.dataMode), 'DATA_MODE_INVALID')
    report.sourceDataMode = connection.check.dataMode; modes([report.sourceDataMode])
    const version = connection.check.reportedVersion
    check(report.sourceDataMode === 'labeled-fixture' ? version === null
      : typeof version === 'string' && /^\d{1,3}\.\d{1,3}\.\d{1,4}(?![\s\S])/.test(version), 'SOURCE_VERSION_INVALID')
    check(rehearsal || version === env.OPSWEAVE_ACCEPTANCE_ZABBIX_VERSION, 'EXPECTED_SOURCE_VERSION_REQUIRED_OR_MISMATCH')
    record('S1', { checkId: connection.check.checkId, reportedVersion: version, dataMode: report.sourceDataMode })

    stage('S2')
    const host = await api('/api/v1/integrations/zabbix/hosts/sync', { method: 'POST' }); modes([host?.dataMode])
    check(host.snapshotComplete === true && host.scanConsistency === 'hostid-watermark-snapshot'
      && host.inventoryStore === 'postgres' && uuid(host.syncRunId), 'HOST_SNAPSHOT_INVALID')
    const pin = host.pipelineVersion
    check(pin && typeof pin.id === 'string' && /^[A-Za-z][A-Za-z0-9_-]{0,63}(?![\s\S])/.test(pin.id)
      && Number.isSafeInteger(pin.revision) && pin.revision >= 1 && pin.revision <= 2147483647
      && /^sha256:[0-9a-f]{64}(?![\s\S])/.test(pin.digest), 'PUBLISHED_PIPELINE_REQUIRED')
    const hostTrace = await api(`/api/v1/integrations/zabbix/hosts/runs/${host.syncRunId}`)
    check(hostTrace?.run?.syncRunId === host.syncRunId && hostTrace.run.status === 'SUCCEEDED'
      && hostTrace.run.snapshotComplete === true && hostTrace.run.scanConsistency === host.scanConsistency
      && hostTrace.run.dataMode === host.dataMode && isDeepStrictEqual(hostTrace.run.pipelineVersion, pin), 'HOST_TRACE_MISMATCH')
    record('S2', { syncRunId: host.syncRunId, pipelineVersion: { id: pin.id, revision: pin.revision, digest: pin.digest }, dataMode: host.dataMode })

    stage('S3')
    const item = await api('/api/v1/integrations/zabbix/items/sync', { method: 'POST' }); modes([item?.dataMode])
    check(item.snapshotComplete === true && item.scanConsistency === 'itemid-watermark-snapshot' && uuid(item.syncRunId), 'ITEM_SNAPSHOT_INVALID')
    const itemTrace = await api(`/api/v1/integrations/zabbix/items/runs/${item.syncRunId}`)
    check(itemTrace?.run?.syncRunId === item.syncRunId && itemTrace.run.status === 'SUCCEEDED'
      && itemTrace.run.snapshotComplete === true && itemTrace.run.scanConsistency === item.scanConsistency
      && itemTrace.run.dataMode === item.dataMode, 'ITEM_TRACE_MISMATCH')
    record('S3', { syncRunId: item.syncRunId, dataMode: item.dataMode })

    stage('S4')
    // An explicit target need not appear on the first page.
    let entityId = entityInput
    if (!entityId) {
      const page = await api('/api/v1/entities/page?limit=5')
      check(list(page?.items, 1, 5) && uuid(page.items[0].id), 'ASSET_REQUIRED'); entityId = page.items[0].id
    }
    const entity = await api(`/api/v1/entities/${entityId}`)
    check(entity?.id === entityId && typeof entity.tenantId === 'string' && entity.tenantId.length > 0, 'ASSET_SCOPE_INVALID')
    record('S4', { entityId })

    stage('S5')
    const till = Math.floor(Date.now() / 1000)
    const window = { from: new Date((till - 3600) * 1000).toISOString(), to: new Date(till * 1000).toISOString() }
    const series = await api(`/api/v1/entities/${entityId}/metrics/${encodeURIComponent(metric)}/series?from=${till - 3600}&till=${till}&maxPoints=500`)
    check(series?.entityId === entityId && series.metricKey === metric && series.from === till - 3600 && series.till === till, 'METRIC_SCOPE_INVALID')
    check(series.status?.kind === 'AVAILABLE' && list(series.series, 1, 500), 'METRIC_SAMPLES_REQUIRED')
    let samples = 0
    for (const row of series.series) {
      modes([row.dataMode]); check(list(row.points, 1, 500), 'METRIC_SAMPLES_REQUIRED')
      for (const point of row.points) check(list(point, 2, 2) && Number.isSafeInteger(point[0])
        && point[0] >= (till - 3600) * 1000 && point[0] <= till * 1000
        && typeof point[1] === 'string' && point[1].trim() !== '' && Number.isFinite(Number(point[1])), 'METRIC_SAMPLE_INVALID')
      samples += row.points.length
    }
    check(samples <= 500, 'METRIC_SAMPLE_BUDGET_EXCEEDED')
    record('S5', { entityId, metricKey: metric, samples, queryWindow: window })

    stage('S6')
    let incidentId = incidentInput
    if (!incidentId) {
      await api('/api/v1/integrations/zabbix/problems/ingest', { method: 'POST', body: { from: till - 86400, till, afterEventId: null, limit: 100 } })
      const page = await api('/api/v1/incidents?limit=1')
      check(list(page?.items, 1, 1) && uuid(page.items[0].id), 'INCIDENT_REQUIRED'); incidentId = page.items[0].id
    }
    const incident = await api(`/api/v1/incidents/${incidentId}`)
    const current = incident?.record?.incident, problems = incident?.record?.problems
    check(current?.id === incidentId && current.tenantId === entity.tenantId
      && Number.isSafeInteger(current.version) && current.version >= 1 && list(problems, 1, 50), 'INCIDENT_SCOPE_INVALID')
    for (const problem of problems) modes([problem.dataMode])
    check(problems.some(problem => problem.entities?.some(link => link.entityId === entityId)), 'INCIDENT_ASSET_NOT_LINKED')
    record('S6', { incidentId, incidentVersion: current.version, entityId, problems: problems.length })

    stage('S7')
    const request = { runId: randomUUID(), incidentId, question: 'Summarize the current authorized evidence for this incident.', timeRange: window, knowledgeMode: 'current' }
    const saved = await api('/api/v1/ai/diagnoses', { method: 'POST', timeoutMs: 90000, body: request })
    const insight = saved?.record
    check(saved?.storage === 'postgres' && insight?.id === request.runId && insight.incidentId === incidentId
      && insight.tenantId === entity.tenantId && insight.incidentVersion === current.version
      && uuid(insight.sessionId) && ids(insight.entityIds, 1, 5) && insight.entityIds.includes(entityId)
      && sameWindow(insight.queryWindow, window) && insight.question === request.question && insight.knowledgeMode === 'current'
      && insight.verification === 'reference_integrity_only', 'INSIGHT_SCOPE_INVALID')
    modes(insight.dataModes)
    check(['mock-deterministic', 'rig-openai'].includes(insight.model?.provider), 'MODEL_PROVIDER_INVALID')
    report.modelProvider = insight.model.provider
    check(rehearsal || report.modelProvider === 'rig-openai', 'REAL_MODEL_REQUIRED')
    check(instant(insight.asOf) && instant(insight.builtAt) && instant(insight.completedAt) && instant(insight.savedAt) && instant(insight.expiresAt)
      && Date.parse(insight.asOf) <= Date.parse(insight.builtAt) && Date.parse(insight.builtAt) <= Date.parse(insight.completedAt)
      && Date.parse(insight.completedAt) <= Date.parse(insight.savedAt) && Date.parse(insight.savedAt) < Date.parse(insight.expiresAt)
      && Date.parse(insight.expiresAt) > Date.now(), 'INSIGHT_TIME_INVALID')
    // A valid result may honestly contain no findings; never require the model to invent one.
    check(ids(insight.evidenceIds, 2, 2) && list(insight.insight?.findings, 0, 12), 'INSIGHT_REFERENCES_INVALID')
    for (const finding of insight.insight.findings) check(ids(finding.evidenceRefs, 1, 2)
      && finding.evidenceRefs.every(id => insight.evidenceIds.includes(id)), 'INSIGHT_REFERENCES_INVALID')
    const found = await api(`/api/v1/ai/insights/${insight.id}`)
    check(isDeepStrictEqual(found, saved), 'INSIGHT_READBACK_MISMATCH')
    const kinds = new Set()
    for (const id of insight.evidenceIds) {
      // Never follow the sourceRef supplied by an untrusted response.
      const document = await api(`/api/v1/ai/evidence/${id}`), evidence = document?.evidence
      check(evidence?.id === id && evidence.tenantId === insight.tenantId && evidence.incidentId === incidentId
        && document.sessionId === insight.sessionId && document.incidentVersion === insight.incidentVersion
        && sameIds(document.entityIds, insight.entityIds) && sameWindow(document.queryWindow, window)
        && document.knowledgeMode === 'current' && document.policyVersion === 'readonly-diagnosis-v1'
        && evidence.trust === 'untrusted_data' && evidence.sourceRef === `/api/v1/ai/evidence/${id}`, 'EVIDENCE_SCOPE_INVALID')
      modes(document.dataModes)
      check(instant(evidence.observedAt) && instant(evidence.availableAt) && instant(evidence.expiresAt)
        && Date.parse(evidence.observedAt) <= Date.parse(insight.asOf) && Date.parse(evidence.availableAt) <= Date.parse(insight.asOf)
        && Date.parse(evidence.expiresAt) > Date.now(), 'EVIDENCE_TIME_INVALID')
      check((evidence.kind === 'incident' && document.producerTool === 'incident.get@2.0.0')
        || (evidence.kind === 'metric' && document.producerTool === 'metric.summary@2.0.0'), 'EVIDENCE_TOOL_INVALID')
      kinds.add(evidence.kind)
      if (evidence.kind === 'metric') check(document.data?.metricKey === metric && document.data.sampleCount > 0
        && document.data.entities?.some(row => row.entityId === entityId && row.series?.some(series => series.count > 0)), 'DIAGNOSIS_METRIC_SAMPLES_REQUIRED')
    }
    check(kinds.size === 2, 'INCIDENT_AND_METRIC_EVIDENCE_REQUIRED')
    record('S7', { insightId: insight.id, sessionId: insight.sessionId, evidenceIds: insight.evidenceIds, modelProvider: report.modelProvider })
    report.mode = rehearsal ? 'rehearsal' : 'real-candidate'; report.status = 'passed'; report.failedStep = null; exitCode = 0
  } catch (error) {
    report.errorCode = error instanceof ProbeFailure ? error.code : 'RESPONSE_CONTRACT_INVALID'
    exitCode = error instanceof ProbeFailure ? error.exitCode : 1
    log(`FAILED ${report.failedStep}: ${report.errorCode}`)
  }
  report.completedAt = new Date().toISOString()
  return { report, exitCode }
}
