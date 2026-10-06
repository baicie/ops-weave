import { platformClient } from './http.ts'
import type { Setup } from './source-setups.ts'
export type SourceInstance = { id: string; name: string; description: string; source: Setup['source']; configurationRevision: number; connectionDigest: string; dataMode: Setup['dataMode']; editVersion: number; state: 'ACTIVE' | 'ARCHIVED'; createdAt: string; updatedAt: string; workflowId: string }
export type InstancePage = { schemaVersion: '2.0'; storage: 'memory' | 'postgres'; items: SourceInstance[]; truncated: boolean }
export type InstanceEdit = { requestId: string; expectedEditVersion: number; name: string; description: string; connectionDigest: string; state: SourceInstance['state'] }
export type Configuration = { sourceId: string; revision: number; connectionDigest: string; dataMode: Setup['dataMode']; createdAt: string }
type Receipt = { requestId: string; sourceId: string; commandDigest: string; instance: SourceInstance }
const uuid = /^[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}$/, hash = /^sha256:[a-f0-9]{64}$/
function invalid(): never { throw new Error('接入实例响应不符合契约，原请求仍需核对') }
function object(value: unknown, keys: string[]): Record<string, unknown> { if (!value || typeof value !== 'object' || Array.isArray(value) || Object.keys(value).length !== keys.length || keys.some(key => !Object.hasOwn(value, key))) invalid(); return value as Record<string, unknown> }
function integer(value: unknown, max: number) { if (!Number.isInteger(value) || Number(value) < 1 || Number(value) > max) invalid() }
function instant(value: unknown) { if (typeof value !== 'string' || value.length > 40 || !Number.isFinite(Date.parse(value))) invalid() }
export function parseInstance(value: unknown): SourceInstance {
  const i = object(value, ['id', 'name', 'description', 'source', 'configurationRevision', 'connectionDigest', 'dataMode', 'editVersion', 'state', 'createdAt', 'updatedAt', 'workflowId'])
  if (typeof i.id !== 'string' || !uuid.test(i.id) || i.workflowId !== 'source-' + i.id || typeof i.name !== 'string' || !i.name.trim() || i.name !== i.name.trim() || i.name.length > 80 || typeof i.description !== 'string' || i.description.length > 500 || typeof i.connectionDigest !== 'string' || !hash.test(i.connectionDigest) || !['ACTIVE', 'ARCHIVED'].includes(String(i.state))) invalid()
  integer(i.configurationRevision, 100); integer(i.editVersion, 1000); instant(i.createdAt); instant(i.updatedAt)
  if (Date.parse(String(i.updatedAt)) < Date.parse(String(i.createdAt)) || Number(i.configurationRevision) > Number(i.editVersion)) invalid()
  const source = object(i.source, ['kind', 'instanceId'])
  if (!['ZABBIX_HOST', 'MANUAL_SAMPLE'].includes(String(source.kind)) || typeof source.instanceId !== 'string' || !/^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$/.test(source.instanceId) || source.kind === 'MANUAL_SAMPLE' && source.instanceId !== 'manual' || !['fixture', 'zabbix-jsonrpc', 'MANUAL_SAMPLE'].includes(String(i.dataMode)) || (source.kind === 'MANUAL_SAMPLE') !== (i.dataMode === 'MANUAL_SAMPLE')) invalid()
  return value as SourceInstance
}
const errors: Record<string, string> = { CONFLICT: '实例已被修改、正在运行或原请求内容不同，请读取最新实例后核对', FORBIDDEN: '当前身份没有这份接入实例的维护权限', NOT_FOUND: '当前身份下找不到这份实例或命令回执', SOURCE_UNAVAILABLE: '当前连接配置不可用或已变化，请重新读取连接信息', CAPACITY: '实例、配置版本或维护回执已达到容量上限' }
export class InstanceRejectedError extends Error {}
function request(path: string, signal: AbortSignal, body?: InstanceEdit) { return platformClient.request('/api/v2/data-sources' + path, { signal, body, ...(body ? { method: 'PATCH' as const } : {}), error: (status, code) => { const message = errors[code] ?? '接入实例请求失败（HTTP ' + status + '）'; return body && ([400, 403, 404, 409].includes(status) || status === 503 && code === 'SOURCE_UNAVAILABLE') ? new InstanceRejectedError(message) : new Error(message) } }) }
export async function readInstances(signal: AbortSignal): Promise<InstancePage> { const value = object(await request('', signal), ['schemaVersion', 'storage', 'items', 'truncated']); if (value.schemaVersion !== '2.0' || !['postgres', 'memory'].includes(String(value.storage)) || !Array.isArray(value.items) || value.items.length > 20 || typeof value.truncated !== 'boolean') invalid(); value.items.forEach(parseInstance); if (new Set(value.items.map(i => i.id)).size !== value.items.length) invalid(); return value as unknown as InstancePage }
export async function readInstance(id: string, signal: AbortSignal): Promise<SourceInstance> { if (!uuid.test(id)) invalid(); const value = object(await request('/' + id, signal), ['schemaVersion', 'instance']); if (value.schemaVersion !== '2.0') invalid(); const instance = parseInstance(value.instance); if (instance.id !== id) invalid(); return instance }
export async function readConfigurations(id: string, signal: AbortSignal): Promise<Configuration[]> { if (!uuid.test(id)) invalid(); const value = object(await request('/' + id + '/configurations', signal), ['schemaVersion', 'sourceId', 'items']); if (value.schemaVersion !== '2.0' || value.sourceId !== id || !Array.isArray(value.items) || !value.items.length || value.items.length > 100) invalid(); for (const row of value.items) { const c = object(row, ['sourceId', 'revision', 'connectionDigest', 'dataMode', 'createdAt']); if (c.sourceId !== id || typeof c.connectionDigest !== 'string' || !hash.test(c.connectionDigest) || !['fixture', 'zabbix-jsonrpc', 'MANUAL_SAMPLE'].includes(String(c.dataMode))) invalid(); integer(c.revision, 100); instant(c.createdAt) }; if (new Set(value.items.map(c => c.revision)).size !== value.items.length) invalid(); return value.items as Configuration[] }
export async function instanceCommandDigest(id: string, c: InstanceEdit): Promise<string> {
  const encoder = new TextEncoder(), chunks: Uint8Array[] = []
  for (const part of ['source-instance-edit-v2', id, c.requestId, String(c.expectedEditVersion), c.name, c.description, c.connectionDigest, c.state]) { const bytes = encoder.encode(part); chunks.push(encoder.encode(bytes.length + ':'), bytes) }
  const input = new Uint8Array(chunks.reduce((sum, bytes) => sum + bytes.length, 0)); let offset = 0; for (const bytes of chunks) { input.set(bytes, offset); offset += bytes.length }
  return 'sha256:' + Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', input)), byte => byte.toString(16).padStart(2, '0')).join('')
}
async function receipt(value: unknown, id: string, command: InstanceEdit): Promise<SourceInstance> {
  const v = object(value, ['schemaVersion', 'receipt']), r = object(v.receipt, ['requestId', 'sourceId', 'commandDigest', 'instance']) as unknown as Receipt
  if (v.schemaVersion !== '2.0' || r.requestId !== command.requestId || r.sourceId !== id || r.commandDigest !== await instanceCommandDigest(id, command)) invalid()
  const i = parseInstance(r.instance)
  if (i.id !== id || i.editVersion !== command.expectedEditVersion + 1 || i.name !== command.name || i.description !== command.description || i.connectionDigest !== command.connectionDigest || i.state !== command.state) invalid()
  return i
}
export async function editInstance(id: string, command: InstanceEdit, signal: AbortSignal) { if (!uuid.test(id) || !uuid.test(command.requestId)) invalid(); return receipt(await request('/' + id, signal, command), id, command) }
export async function readInstanceCommand(id: string, command: InstanceEdit, signal: AbortSignal) { if (!uuid.test(id) || !uuid.test(command.requestId)) invalid(); return receipt(await request('/' + id + '/commands/' + command.requestId, signal), id, command) }
