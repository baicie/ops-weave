import { platformClient } from './http.ts'
import { parseWorkflowEntry, parseRun, type Entry, type Run } from './workflows.ts'

// Authoritative wire contract: contracts/schemas/v2/workflow-entry-page.schema.json,
// contracts/schemas/v2/workflow-run-page.schema.json and contracts/workflow-pages.md.

export type EntryPage = { items: Entry[]; nextCursor: string | null; hasMore: boolean }
export type RunPage = { items: Run[]; nextCursor: string | null; hasMore: boolean }

const entryCursor = /^[a-z][a-z0-9_-]{0,47}:(?:[1-9][0-9]{0,3}|10000)$/
const runCursor = /^[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}$/

function invalid(): never { throw new Error('分页结果不符合契约，请重新读取。') }
function object(value: unknown, keys: string[]): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value) || Object.keys(value).some(k => !keys.includes(k)) || keys.some(k => !Object.hasOwn(value, k))) invalid()
  return value as Record<string, unknown>
}

function unique<T>(items: T[], key: (item: T) => string): boolean { return new Set(items.map(key)).size === items.length }

/** Closed page parse: bounded unique items, one deterministic order and exact hasMore disclosure. */
function page<T>(value: unknown, parse: (item: unknown) => T, cursor: RegExp, consistent: (items: T[]) => boolean): { items: T[]; nextCursor: string | null; hasMore: boolean } {
  const p = object(value, ['schemaVersion', 'items', 'nextCursor', 'hasMore'])
  if (p.schemaVersion !== '2.0' || typeof p.hasMore !== 'boolean' || !Array.isArray(p.items) || p.items.length > 50) invalid()
  const items = (p.items as unknown[]).map(parse)
  const disclosed = p.nextCursor === null ? !p.hasMore : typeof p.nextCursor === 'string' && cursor.test(p.nextCursor) && p.hasMore && items.length > 0
  if (!disclosed || !consistent(items)) invalid()
  return { items, nextCursor: p.nextCursor as string | null, hasMore: p.hasMore as boolean }
}

const entryKey = (e: Entry) => e.definition.id + ':' + e.definition.revision
const entriesConsistent = (items: Entry[]) => unique(items, entryKey) && items.every((e, i) => i === 0 || items[i - 1]!.updatedAt >= e.updatedAt)
const runsConsistent = (items: Run[]) => unique(items, r => r.receipt.id)

function queryOf(options: { state?: string; workflowId?: string; after?: string; limit?: number }): string {
  const query = new URLSearchParams()
  if (options.state) query.set('state', options.state)
  if (options.workflowId) query.set('workflowId', options.workflowId)
  if (options.after) query.set('after', options.after)
  if (options.limit) query.set('limit', String(options.limit))
  return query.size ? '?' + query.toString() : ''
}

function failed(status: number, code: string): Error {
  if (code === 'NOT_FOUND') return new Error('所选游标对应的条目已不可用，请重新读取列表。')
  if (code === 'FORBIDDEN') return new Error('当前身份没有读取这些工作流版本或运行记录的权限。')
  return new Error(status === 401 ? '请先建立平台会话，再读取分页列表。' : status === 400 ? '分页请求参数无效。' : '分页读取失败（HTTP ' + status + '）')
}

/** Bounded cursor page of saved workflow entries (owner drafts plus readable published versions). */
export async function readWorkflowEntries(signal: AbortSignal, options: { state?: 'DRAFT' | 'PUBLISHED'; workflowId?: string; after?: string; limit?: number }): Promise<EntryPage> {
  return page(await platformClient.request('/api/v1/integrations/workflows/entries' + queryOf(options), { signal, error: failed }), parseWorkflowEntry, entryCursor, entriesConsistent)
}

/** Bounded cursor page of the owner's recorded preview and version-test runs. */
export async function readWorkflowRuns(signal: AbortSignal, options: { after?: string; limit?: number }): Promise<RunPage> {
  return page(await platformClient.request('/api/v1/integrations/workflows/runs' + queryOf(options), { signal, error: failed }), parseRun, runCursor, runsConsistent)
}
