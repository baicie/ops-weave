import { platformClient } from './http.ts'

export type ExternalLink = {
  namespace: string; objectType: string; externalKey: string; generation: string; targetId: string
}
export type ExternalLinkRegistrationCommand = {
  requestId: string; namespace: string; objectType: string; externalKey: string
  targetId: string; generation?: string; reason?: string
}
export type ExternalLinkRegistrationReceipt = { schemaVersion: '1.0'; requestId: string; replayed: boolean; link: ExternalLink }
export type ExternalLinkEntry = { link: ExternalLink; reason: string | null; registeredAt: string }
export type ExternalLinkPage = { schemaVersion: '1.0'; storage: string; tenantId: string; items: ExternalLinkEntry[]; nextCursor: string | null }

const uuid = (v: unknown): v is string => typeof v === 'string' && /^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(v)
const record = (v: unknown): v is Record<string, unknown> => !!v && typeof v === 'object' && !Array.isArray(v)
const namespace = (v: unknown): v is string => typeof v === 'string' && /^[a-z][a-z0-9._-]{0,95}$/.test(v)
const objectType = (v: unknown): v is string => typeof v === 'string' && /^[a-z][a-z0-9_]{0,63}$/.test(v)
const bounded = (v: unknown, max: number): v is string => typeof v === 'string' && v.trim().length >= 1 && v.length <= max
const instant = (v: unknown): v is string => typeof v === 'string' && Number.isFinite(Date.parse(v))

function parseLink(value: unknown): ExternalLink {
  if (!record(value) || !namespace(value.namespace) || !objectType(value.objectType) || !bounded(value.externalKey, 512) || !bounded(value.generation, 128) || !uuid(value.targetId)) throw new Error('外部链接项不符合契约')
  return { namespace: value.namespace, objectType: value.objectType, externalKey: value.externalKey, generation: value.generation, targetId: value.targetId }
}

export async function registerExternalLink(command: ExternalLinkRegistrationCommand, signal: AbortSignal): Promise<ExternalLinkRegistrationReceipt> {
  if (!uuid(command.requestId) || !namespace(command.namespace) || !objectType(command.objectType) || !bounded(command.externalKey, 512) || !uuid(command.targetId)
    || (command.generation !== undefined && !bounded(command.generation, 128))
    || (command.reason !== undefined && (command.reason.trim().length < 1 || command.reason.length > 256 || command.reason.split('').some(char => char.charCodeAt(0) < 32 || char.charCodeAt(0) === 127)))) throw new Error('外部链接登记请求无效')
  const value = await platformClient.request('/api/v1/external-links', { method: 'POST', body: command, signal })
  if (!record(value) || value.schemaVersion !== '1.0' || value.requestId !== command.requestId || typeof value.replayed !== 'boolean') throw new Error('外部链接登记回执不符合契约')
  return { schemaVersion: '1.0', requestId: value.requestId, replayed: value.replayed, link: parseLink(value.link) }
}

export async function pageExternalLinks(namespace: string | null, after: string | null, signal: AbortSignal): Promise<ExternalLinkPage> {
  if (namespace !== null && !namespacePattern(namespace)) throw new Error('命名空间无效')
  if (after !== null && !uuid(after)) throw new Error('外部链接游标无效')
  const query = new URLSearchParams({ limit: '25' })
  if (namespace) query.set('namespace', namespace)
  if (after) query.set('after', after)
  const value = await platformClient.request(`/api/v1/external-links?${query}`, { signal })
  if (!record(value) || value.schemaVersion !== '1.0' || typeof value.storage !== 'string' || typeof value.tenantId !== 'string' || !Array.isArray(value.items) || value.items.length > 50 || (value.nextCursor !== null && !uuid(value.nextCursor))) throw new Error('外部链接响应不符合契约')
  const items = value.items.map(item => {
    if (!record(item) || !('link' in item) || !('reason' in item) || !('registeredAt' in item)) throw new Error('外部链接项不符合契约')
    const reason = item.reason
    if (reason !== null && (typeof reason !== 'string' || reason.length < 1 || reason.length > 256)) throw new Error('外部链接项不符合契约')
    if (!instant(item.registeredAt)) throw new Error('外部链接项不符合契约')
    return { link: parseLink(item.link), reason: reason as string | null, registeredAt: item.registeredAt as string }
  })
  return { schemaVersion: '1.0', storage: value.storage as string, tenantId: value.tenantId as string, items, nextCursor: value.nextCursor as string | null }
}

function namespacePattern(value: string) { return /^[a-z][a-z0-9._-]{0,95}$/.test(value) }
