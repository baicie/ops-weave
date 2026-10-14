import { test, expect, type Page } from '@playwright/test'
import { TOKEN_OK } from './helpers.ts'

const tokenLabel = '平台开发 Token（仅保存在当前标签页内存）'
const RUN_ID = '10000000-0000-4000-8000-000000000083'
const digest = (letter: string) => 'sha256:' + letter.repeat(64)

type Call = { method: string; path: string; query: URLSearchParams; body: Record<string, unknown> | null }

function run(overrides: Record<string, unknown> = {}) {
  return {
    schemaVersion: '2.0',
    runId: RUN_ID,
    requestId: '10000000-0000-4000-8000-000000000081',
    commandDigest: digest('b'),
    status: 'FAILED',
    statusVersion: 6,
    incidentId: '10000000-0000-4000-8000-000000000082',
    question: 'Which change explains the latency spike in this window?',
    timeRange: { from: '2026-10-13T01:00:00Z', to: '2026-10-13T01:30:00Z' },
    knowledgeMode: 'current',
    skill: { id: 'incident.diagnose', version: '2.0.0', digest: digest('a') },
    attempt: 2,
    reason: 'RESULT_LOST',
    disclosure: { adoptedPriorResult: false, rerun: false, priorModelCalls: 1, uncertainPriorFees: 1, duplicateFeeRisk: true },
    createdAt: '2026-10-13T01:00:01.000Z',
    updatedAt: '2026-10-13T01:00:07.000Z',
    ...overrides,
  }
}

function event(seq: number, type: string, payload: Record<string, unknown>) {
  return {
    runId: RUN_ID,
    seq,
    type,
    occurredAt: `2026-10-13T01:00:0${seq}.000Z`,
    payloadVersion: 1,
    payload,
  }
}

const FULL_LOG = [
  event(1, 'SUBMITTED', { status: 'PENDING', attempt: 0 }),
  event(2, 'CLAIMED', { status: 'RUNNING', attempt: 1 }),
  event(3, 'RECONCILED', { disclosure: { adoptedPriorResult: false, rerun: true, priorModelCalls: 0, uncertainPriorFees: 0, duplicateFeeRisk: false } }),
  event(4, 'TOOL_WAIT', { status: 'WAITING_TOOL' }),
  event(5, 'STATUS_CHANGED', { status: 'RUNNING' }),
  event(6, 'FINISHED', { status: 'FAILED', reason: 'RESULT_LOST', disclosure: { adoptedPriorResult: false, rerun: false, priorModelCalls: 1, uncertainPriorFees: 1, duplicateFeeRisk: true } }),
]

const WINDOW_LOG = [
  event(4, 'TOOL_WAIT', { status: 'WAITING_TOOL' }),
  event(5, 'STATUS_CHANGED', { status: 'RUNNING' }),
  event(6, 'FINISHED', { status: 'SUCCEEDED' }),
]

function eventPage(log: unknown[], afterSeq: number, limit: number, snapshot: Record<string, unknown>) {
  const rows = log.filter(item => (item as { seq: number }).seq > afterSeq)
  const items = rows.slice(0, limit)
  const hasMore = rows.length > limit
  return {
    schemaVersion: '2.0',
    runId: RUN_ID,
    items,
    nextCursor: hasMore ? String((items[items.length - 1] as { seq: number }).seq) : null,
    hasMore,
    gap: afterSeq + 1 < (log[0] as { seq: number }).seq,
    snapshot,
  }
}

function frames(log: unknown[], limit: number, snapshot: Record<string, unknown>) {
  let body = 'retry: 2000\n\n'
  let after = 0
  while (true) {
    const page = eventPage(log, after, limit, snapshot)
    if (page.items.length === 0) break
    body += `id: ${page.nextCursor ?? (page.items[page.items.length - 1] as { seq: number }).seq}\n`
    body += `data: ${JSON.stringify(page)}\n\n`
    after = (page.items[page.items.length - 1] as { seq: number }).seq
    if (!page.hasMore) break
  }
  return body
}

type Options = {
  log?: unknown[]
  pageSize?: number
  snapshot?: Record<string, unknown>
  run?: Record<string, unknown>
  stream?: 'sse' | 'fail' | 'frame-first-page'
  cancel?: 'ok' | 'fail-first'
}

/** Every platform route of the run console is mocked explicitly; the page never reaches a real platform. */
async function fixture(page: Page, options: Options = {}) {
  const log = options.log ?? FULL_LOG
  const pageSize = options.pageSize ?? 20
  const snapshot = options.snapshot ?? { status: 'FAILED', statusVersion: 6, attempt: 2, updatedAt: '2026-10-13T01:00:07.000Z' }
  const record = options.run ?? run()
  const calls: Call[] = []
  let cancelPosts = 0
  await page.route('**/api/v1/ai/runs**', async route => {
    const url = new URL(route.request().url())
    const body = route.request().postDataJSON() as Record<string, unknown> | null
    calls.push({ method: route.request().method(), path: url.pathname, query: url.searchParams, body })
    if (url.pathname.endsWith('/events/stream')) {
      if (options.stream === 'fail') return route.fulfill({ status: 503, json: { error: 'UNAVAILABLE' } })
      if (options.stream === 'frame-first-page') {
        return route.fulfill({ contentType: 'text/event-stream', body: `retry: 2000\n\nid: 5\ndata: ${JSON.stringify(eventPage(log, 0, 2, snapshot))}\n\n` })
      }
      return route.fulfill({ contentType: 'text/event-stream', body: frames(log, pageSize, snapshot) })
    }
    if (url.pathname.endsWith('/events')) {
      return route.fulfill({ json: eventPage(log, Number(url.searchParams.get('afterSeq') ?? '0'),
        Math.min(Number(url.searchParams.get('limit') ?? '20'), pageSize), snapshot) })
    }
    if (url.pathname.endsWith('/cancel')) {
      cancelPosts++
      if (options.cancel === 'fail-first' && cancelPosts === 1) return route.abort('failed')
      return route.fulfill({ json: { schemaVersion: '2.0', requestId: body!.requestId, runId: RUN_ID,
        requestedAt: '2026-10-13T01:00:08.000Z', recorded: true, status: record.status } })
    }
    if (url.pathname.endsWith('/retries')) {
      return route.fulfill({ json: { schemaVersion: '2.0', requestId: body!.requestId, runId: RUN_ID,
        requestedAt: '2026-10-13T01:00:08.000Z', attempt: 2, status: record.status,
        reconciliation: { adoptedPriorResult: false, rerun: true, priorModelCalls: 0, uncertainPriorFees: 0, duplicateFeeRisk: false } } })
    }
    if (/\/ai\/runs\/[^/]+$/.test(url.pathname)) return route.fulfill({ json: record })
    return route.fulfill({ json: { schemaVersion: '2.0', items: [record], nextCursor: null, hasMore: false } })
  })
  await page.goto('/#/ai/runs')
  await page.getByRole('textbox', { name: tokenLabel }).fill(TOKEN_OK)
  await expect(page.getByRole('heading', { name: '诊断运行' })).toBeVisible()
  await expect(page.getByRole('cell', { name: RUN_ID.slice(0, 8) })).toBeVisible()
  return calls
}

test('运行列表、详情与事件时间线只呈现持久记录，不执行任何操作', async ({ page }) => {
  await fixture(page, { stream: 'sse' })
  await page.getByRole('button', { name: '查看运行与事件' }).click()
  await expect(page.locator(`[data-run-detail="${RUN_ID}"]`)).toBeVisible()
  await expect(page.locator(`[data-run-status="run-${RUN_ID}-status"]`)).toContainText('第 2 次尝试')
  await expect(page.locator('[data-run-reason]')).toContainText('RESULT_LOST')
  await expect(page.locator('[data-run-disclosure]')).toContainText('费用已花而结果丢失')
  await expect(page.locator('[data-run-disclosure]')).toContainText('已有模型调用 1 次')
  const events = page.locator('[data-run-event]')
  await expect(events).toHaveCount(6)
  await expect(events.nth(0)).toHaveAttribute('data-run-event', 'SUBMITTED')
  await expect(events.nth(2)).toHaveAttribute('data-run-event', 'RECONCILED')
  await expect(events.nth(5)).toHaveAttribute('data-run-event', 'FINISHED')
  await expect(page.locator('[data-run-events-transport="sse"]')).toBeVisible()
  await expect(page.locator('[data-run-events-gap]')).toHaveCount(0)
  // A terminal run never migrates again: the control entries are closed, not hidden behind a menu.
  await expect(page.getByRole('button', { name: '请求取消' })).toBeDisabled()
  await expect(page.getByRole('button', { name: '恢复/重试（同运行新尝试）' })).toBeDisabled()
  await expect(page.getByText('已终止运行不再迁移')).toBeVisible()
  await expect(page.getByText('完整结论由 AIInsight 保存并授权读取', { exact: false })).toHaveCount(0)
})

test('事件时间线明示缺口并用游标加载更多记录', async ({ page }) => {
  await fixture(page, {
    log: WINDOW_LOG,
    pageSize: 2,
    stream: 'frame-first-page',
    snapshot: { status: 'SUCCEEDED', statusVersion: 4, attempt: 1, updatedAt: '2026-10-13T01:00:06.000Z' },
    run: run({ status: 'SUCCEEDED', reason: undefined, disclosure: undefined, statusVersion: 4, attempt: 1 }),
  })
  await page.getByRole('button', { name: '查看运行与事件' }).click()
  await expect(page.locator('[data-run-events-gap]')).toBeVisible()
  await expect(page.locator('[data-run-events-gap]')).toContainText('事件缺口')
  await expect(page.locator('[data-run-event]')).toHaveCount(2)
  await expect(page.locator('[data-run-events-more]')).toBeVisible()
  await page.getByRole('button', { name: '加载更多记录' }).click()
  await expect(page.locator('[data-run-event]')).toHaveCount(3)
  await expect(page.locator('[data-run-events-more]')).toHaveCount(0)
  await expect(page.getByText('已到当前保留窗口的末尾')).toBeVisible()
  await expect(page.locator('[data-run-events-snapshot]')).toContainText('SUCCEEDED')
})

test('推送通道不可用时降级为轮询回读，语义不变', async ({ page }) => {
  await fixture(page, { stream: 'fail', snapshot: { status: 'RUNNING', statusVersion: 2, attempt: 1, updatedAt: '2026-10-13T01:00:02.000Z' } })
  await page.getByRole('button', { name: '查看运行与事件' }).click()
  await expect(page.locator('[data-run-events-transport="polling"]')).toBeVisible()
  await expect(page.locator('[data-run-events-transport="polling"]')).toContainText('轮询回读')
  await expect(page.locator('[data-run-event]')).toHaveCount(6)
})

test('取消与重试保留原请求标识，未知结果只允许原样重发', async ({ page }) => {
  const calls = await fixture(page, {
    stream: 'fail',
    cancel: 'fail-first',
    snapshot: { status: 'RUNNING', statusVersion: 2, attempt: 1, updatedAt: '2026-10-13T01:00:02.000Z' },
    run: run({ status: 'RUNNING', statusVersion: 2, attempt: 1, reason: undefined, disclosure: undefined }),
  })
  await page.getByRole('button', { name: '查看运行与事件' }).click()
  await page.getByRole('button', { name: '请求取消' }).click()
  await expect(page.locator('[data-run-pending]')).toContainText('提交结果待确认')
  await expect(page.locator('[data-run-pending]')).toContainText('不自动重试、不改用新标识')
  // The outcome is unknown: nothing is retried automatically and the control entries stay closed.
  await page.waitForTimeout(1200)
  const pending = calls.filter(call => call.path.endsWith('/cancel'))
  expect(pending).toHaveLength(1)
  await expect(page.getByRole('button', { name: '请求取消' })).toBeDisabled()
  await expect(page.getByRole('button', { name: '恢复/重试（同运行新尝试）' })).toBeDisabled()
  await expect(page.getByRole('button', { name: '原样重发同一请求' })).toBeVisible()
  await page.getByRole('button', { name: '原样重发同一请求' }).click()
  await expect(page.locator('[data-run-pending]')).toHaveCount(0)
  const cancels = calls.filter(call => call.path.endsWith('/cancel'))
  expect(cancels).toHaveLength(2)
  expect(cancels[1].body!.requestId).toBe(cancels[0].body!.requestId)
  await expect(page.getByRole('button', { name: '请求取消' })).toBeEnabled()
})
