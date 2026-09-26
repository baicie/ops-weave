import { platformClient } from './http.ts'
import template from '../../../../contracts/examples/pipeline-definition.json'

// Wire schemas live in contracts/schemas/v1/pipeline-*.schema.json.
export type PipelineRef = { id: string; revision: number; digest: string }
export type HostSummary = { entityId: string; name: string; ip: string; lifecycle: string }
export type EvaluationRow = { rawRef: string; status: string; previous: HostSummary | null; candidate: HostSummary | null; changed: boolean }
export type Evaluation = {
  mode: string; sourceInstanceId: string; syncRunId: string; sourceRunStatus: string; dataMode: string
  originalVersion: PipelineRef; targetVersion: PipelineRef
  fetched: number; retained: number; missingRaw: number; truncated: boolean; oversized: number
  accepted: number; rejected: number; changed: number; wouldFailFast: boolean; rows: EvaluationRow[]
}
export type Definition = ReturnType<typeof hostDefinition>
export type Published = { definition: Definition; digest: string; state: 'PUBLISHED'; engine: string }
export type ReplaySpec = { syncRunId: string; targetVersion: PipelineRef; limit: number; purpose: 'COMPARE_VERSION' | 'VALIDATE_MAPPING' }
export type ReplayRequest = ReplaySpec & { requestKey: string }
export type ReplayHeader = { id: string; requestKey: string; sourceInstanceId: string; spec: ReplaySpec; state: 'RUNNING' | 'SUCCEEDED' | 'FAILED'
  attempt: number; createdAt: string; updatedAt: string; leaseUntil: string | null; failureCode: string | null; canResume: boolean }
export type ReplayDetail = { storage: 'memory' | 'postgres'; run: ReplayHeader; report: Evaluation | null }
export type ReplayHistory = { storage: 'memory' | 'postgres'; items: ReplayHeader[]; nextCursor: string | null }
export type Draft = Omit<Published, 'state'> & { state: 'DRAFT'; storage: 'memory' | 'postgres'; editVersion: number; updatedAt: string }
export type DraftHeader = { target: PipelineRef; editVersion: number; updatedAt: string }
export type DraftList = { storage: 'memory' | 'postgres'; items: DraftHeader[]; truncated: boolean }
const root = '/api/v1/integrations/zabbix/hosts'

export function hostDefinition(id: string, revision: number, displayNameField: string, errorPolicy: string) {
  if (!/^[A-Za-z][A-Za-z0-9_-]{0,63}$/.test(id) || !Number.isInteger(revision) || revision < 1 || revision > 2147483647
    || !['name', 'host'].includes(displayNameField) || !['skipRecord', 'failFast'].includes(errorPolicy)) throw new Error('请填写有效的流水线名称、版本和映射选项')
  return { ...template, id, revision, errorPolicy, nodes: template.nodes.map(node => ({ ...node,
    config: node.type === 'Map' ? { displayNameField } : {},
  })) }
}
function record(value: unknown): value is Record<string, unknown> { return typeof value === 'object' && value !== null && !Array.isArray(value) }
function invalid(): never { throw new Error('流水线响应结构不正确') }
function parseRef(value: unknown): PipelineRef {
  if (!record(value) || typeof value.id !== 'string' || !/^[A-Za-z][A-Za-z0-9_-]{0,63}$/.test(value.id)
    || typeof value.revision !== 'number' || !Number.isSafeInteger(value.revision) || value.revision < 1
    || typeof value.digest !== 'string' || !/^sha256:[0-9a-f]{64}$/.test(value.digest)) invalid()
  return value as PipelineRef
}
function parseHost(value: unknown): HostSummary | null {
  if (value === null) return null
  if (!record(value) || typeof value.entityId !== 'string' || typeof value.name !== 'string'
    || typeof value.ip !== 'string' || !['ACTIVE', 'INACTIVE'].includes(String(value.lifecycle))) invalid()
  return value as HostSummary
}
function parseEvaluation(value: unknown): Evaluation {
  if (!record(value) || value.dryRun !== true || value.writesPerformed !== false || !Array.isArray(value.rows) || value.rows.length > 100
    || !['PREVIEW', 'REPLAY'].includes(String(value.mode)) || !['SUCCEEDED', 'FAILED'].includes(String(value.sourceRunStatus))
    || !['labeled-fixture', 'zabbix-jsonrpc'].includes(String(value.dataMode)) || typeof value.syncRunId !== 'string'
    || typeof value.sourceInstanceId !== 'string' || typeof value.truncated !== 'boolean' || typeof value.wouldFailFast !== 'boolean') invalid()
  for (const key of ['fetched', 'retained', 'missingRaw', 'oversized', 'accepted', 'rejected', 'changed']) {
    if (typeof value[key] !== 'number' || !Number.isSafeInteger(value[key]) || value[key] < 0) invalid()
  }
  parseRef(value.originalVersion); parseRef(value.targetVersion)
  value.rows = value.rows.map(row => {
    if (!record(row) || typeof row.rawRef !== 'string' || typeof row.changed !== 'boolean'
      || !['ACCEPTED', 'MISSING_HOST_ID', 'INVALID_HOST', 'RAW_TOO_LARGE'].includes(String(row.status))) invalid()
    const previous = parseHost(row.previous), candidate = parseHost(row.candidate)
    if ((row.status === 'ACCEPTED') !== (candidate !== null)) invalid()
    return { ...row, previous, candidate }
  })
  if ((value.accepted as number) + (value.rejected as number) + (value.oversized as number) !== (value.rows as unknown[]).length) invalid()
  return value as unknown as Evaluation
}
function parsePublished(value: unknown): Published {
  if (!record(value) || value.state !== 'PUBLISHED' || value.engine !== 'zabbix-host-mapper-v1' || !record(value.definition)
    || !Array.isArray(value.definition.nodes)) invalid()
  parseRef({ ...value.definition, digest: value.digest })
  const map = value.definition.nodes.find(node => record(node) && node.type === 'Map')
  if (!record(map) || (map.config !== undefined && !record(map.config))) invalid()
  const config = (map.config ?? {}) as Record<string, unknown>
  hostDefinition(String(value.definition.id), Number(value.definition.revision), String(config.displayNameField ?? 'name'), String(value.definition.errorPolicy))
  return value as unknown as Published
}
async function request(path: string, signal: AbortSignal, body?: unknown): Promise<unknown> {
  return platformClient.request(root + path, { signal, body, error: (status, code) => new Error(`流水线请求失败（HTTP ${status}${code ? ' ' + code : ''}）`) })
}
export async function syncSample(signal: AbortSignal): Promise<string> {
  const value = await platformClient.request(root + '/sync', { method: 'POST', signal, error: status => new Error(`Host 同步失败（HTTP ${status}）`) })
  if (!record(value) || typeof value.syncRunId !== 'string' || value.snapshotComplete !== true) invalid()
  parseRef(value.pipelineVersion)
  return value.syncRunId
}

export async function previewPipeline(run: string, definition: Definition, signal: AbortSignal): Promise<Evaluation> {
  const result = parseEvaluation(await request('/pipeline/preview', signal, { syncRunId: run, definition, limit: 100 }))
  if (result.mode !== 'PREVIEW' || result.syncRunId !== run || result.targetVersion.id !== definition.id || result.targetVersion.revision !== definition.revision) invalid()
  return result
}
export async function publishPipeline(definition: Definition, expected: PipelineRef, signal: AbortSignal): Promise<Published> {
  const result = parsePublished(await request('/pipeline/versions', signal, definition))
  if (result.definition.id !== expected.id || result.definition.revision !== expected.revision || result.digest !== expected.digest) invalid()
  return result
}
export async function getPipeline(id: string, revision: number, signal: AbortSignal): Promise<Published> {
  const result = parsePublished(await request(`/pipeline/versions/${encodeURIComponent(id)}/${revision}`, signal))
  if (result.definition.id !== id || result.definition.revision !== revision) invalid()
  return result
}
export async function replayPipeline(run: string, target: PipelineRef, signal: AbortSignal): Promise<Evaluation> {
  const result = parseEvaluation(await request('/pipeline/replay', signal, { syncRunId: run, targetVersion: target, purpose: 'COMPARE_VERSION', dryRun: true, limit: 100 }))
  if (result.mode !== 'REPLAY' || result.syncRunId !== run || result.targetVersion.id !== target.id
    || result.targetVersion.revision !== target.revision || result.targetVersion.digest !== target.digest) invalid()
  return result
}
const uuid = (value: unknown): value is string => typeof value === 'string' && /^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(value)
const date = (value: unknown) => typeof value === 'string' && /^\d{4}-\d{2}-\d{2}T/.test(value) && Number.isFinite(Date.parse(value))
const sameRef = (a: PipelineRef, b: PipelineRef) => a.id === b.id && a.revision === b.revision && a.digest === b.digest
const sameSpec = (a: ReplaySpec, b: ReplaySpec) => a.syncRunId === b.syncRunId && a.purpose === b.purpose && a.limit === b.limit && sameRef(a.targetVersion, b.targetVersion)
function parseReplayHeader(value: unknown): ReplayHeader {
  if (!record(value) || !uuid(value.id) || !uuid(value.requestKey) || typeof value.sourceInstanceId !== 'string'
    || value.dryRun !== true || value.writesPerformed !== false || !record(value.spec) || !uuid(value.spec.syncRunId)
    || !['COMPARE_VERSION', 'VALIDATE_MAPPING'].includes(String(value.spec.purpose)) || !Number.isInteger(value.spec.limit)
    || Number(value.spec.limit) < 1 || Number(value.spec.limit) > 100 || !['RUNNING', 'SUCCEEDED', 'FAILED'].includes(String(value.state))
    || !Number.isInteger(value.attempt) || Number(value.attempt) < 1 || Number(value.attempt) > 3
    || !date(value.createdAt) || !date(value.updatedAt) || typeof value.canResume !== 'boolean') invalid()
  parseRef(value.spec.targetVersion)
  if (value.state === 'RUNNING' ? !date(value.leaseUntil) || value.failureCode !== null
    : value.leaseUntil !== null || (value.state === 'FAILED' ? typeof value.failureCode !== 'string' || !/^[A-Z_]{1,64}$/.test(value.failureCode) : value.failureCode !== null)) invalid()
  if ((value.state === 'SUCCEEDED' || value.attempt === 3) && value.canResume) invalid()
  return value as unknown as ReplayHeader
}
function parseReplayDetail(value: unknown): ReplayDetail {
  if (!record(value) || !['memory', 'postgres'].includes(String(value.storage))) invalid()
  const run = parseReplayHeader(value.run)
  const report = value.report === null ? null : parseEvaluation(value.report)
  if ((run.state === 'SUCCEEDED') !== (report !== null)) invalid()
  if (report && (report.mode !== 'REPLAY' || report.syncRunId !== run.spec.syncRunId || report.sourceInstanceId !== run.sourceInstanceId
    || !sameRef(report.targetVersion, run.spec.targetVersion) || report.rows.length > run.spec.limit
    || (value.report as Record<string, unknown>).purpose !== run.spec.purpose)) invalid()
  return { storage: value.storage as ReplayDetail['storage'], run, report }
}
export async function executeReplay(body: ReplayRequest, signal: AbortSignal): Promise<ReplayDetail> {
  const result = parseReplayDetail(await request('/pipeline/replay-runs', signal, { ...body, dryRun: true }))
  if (result.run.requestKey !== body.requestKey || !sameSpec(result.run.spec, body)) invalid()
  return result
}
export async function getReplay(id: string, signal: AbortSignal): Promise<ReplayDetail> {
  const result = parseReplayDetail(await request(`/pipeline/replay-runs/${encodeURIComponent(id)}`, signal))
  if (result.run.id !== id) invalid()
  return result
}
export async function listReplays(before: string | null, signal: AbortSignal): Promise<ReplayHistory> {
  const value = await request(`/pipeline/replay-runs?limit=20${before ? '&before=' + encodeURIComponent(before) : ''}`, signal)
  if (!record(value) || !['memory', 'postgres'].includes(String(value.storage)) || !Array.isArray(value.items) || value.items.length > 20
    || (value.nextCursor !== null && !uuid(value.nextCursor))) invalid()
  const items = value.items.map(parseReplayHeader)
  if (new Set(items.map(item => item.id)).size !== items.length || (value.nextCursor !== null && value.nextCursor !== items.at(-1)?.id)) invalid()
  return { storage: value.storage as ReplayHistory['storage'], items, nextCursor: value.nextCursor as string | null }
}
function editVersion(value: unknown): value is number { return typeof value === 'number' && Number.isInteger(value) && value > 0 && value <= 2147483647 }
function parseDraft(value: unknown): Draft {
  if (!record(value) || value.state !== 'DRAFT' || !['memory', 'postgres'].includes(String(value.storage))
    || !editVersion(value.editVersion) || !date(value.updatedAt)) invalid()
  parsePublished({ ...value, state: 'PUBLISHED' })
  return value as unknown as Draft
}
export function sameDraftDefinition(a: Definition, b: Definition) {
  const field = (d: Definition) => d.nodes.find(node => node.type === 'Map')?.config?.displayNameField ?? 'name'
  return a.id === b.id && a.revision === b.revision && a.errorPolicy === b.errorPolicy && field(a) === field(b)
}
export async function saveDraft(definition: Definition, expectedEditVersion: number, signal: AbortSignal): Promise<Draft> {
  const result = parseDraft(await request('/pipeline/drafts', signal, { definition, expectedEditVersion }))
  if (result.editVersion !== expectedEditVersion + 1 || !sameDraftDefinition(result.definition, definition)) invalid()
  return result
}
export async function getDraft(id: string, revision: number, signal: AbortSignal): Promise<Draft> {
  const result = parseDraft(await request(`/pipeline/drafts/${encodeURIComponent(id)}/${revision}`, signal))
  if (result.definition.id !== id || result.definition.revision !== revision) invalid()
  return result
}
export async function listDrafts(signal: AbortSignal): Promise<DraftList> {
  const value = await request('/pipeline/drafts?limit=20', signal)
  if (!record(value) || !['memory', 'postgres'].includes(String(value.storage)) || !Array.isArray(value.items)
    || value.items.length > 20 || typeof value.truncated !== 'boolean') invalid()
  const items = value.items.map(item => {
    if (!record(item) || !editVersion(item.editVersion) || !date(item.updatedAt)) invalid()
    return { target: parseRef(item.target), editVersion: item.editVersion, updatedAt: item.updatedAt as string }
  })
  if (new Set(items.map(item => `${item.target.id}@${item.target.revision}`)).size !== items.length) invalid()
  return { storage: value.storage as DraftList['storage'], items, truncated: value.truncated }
}
