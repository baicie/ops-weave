import { test, expect, type Page } from '@playwright/test'
import { TOKEN_OK, WORKFLOW_OPERATORS } from './helpers.ts'

const tokenLabel = '平台开发 Token（仅保存在当前标签页内存）'
const digest = (letter: string) => 'sha256:' + letter.repeat(64)

type Call = { method: string; path: string; body: Record<string, unknown> | null }

function binding(state: 'BOUND' | 'UNBOUND') {
  return {
    id: 'flow-one', revision: 1, digest: digest('c'), sourceInstanceId: 'connection-fixture',
    state, generation: state === 'BOUND' ? 1 : 2, updatedAt: '2026-10-13T00:00:00.000Z',
    reason: state === 'BOUND' ? 'enable collection' : 'disable collection',
  }
}

function workspace() {
  return {
    schemaVersion: '2.0', operatorCatalog: WORKFLOW_OPERATORS, storage: 'postgres',
    drafts: [], published: [], models: [], modelsTruncated: false,
    zabbixSource: { instanceId: 'connection-fixture', mode: 'fixture' },
    runs: { items: [], truncated: false }, metricMappings: [],
  }
}

const report = {
  kind: 'onboarding', scopeKey: 'fixture-operator', kept: 3, keptProtected: 2,
  maxPerScope: 1000, maxPerTenant: 5000, status: 'OK', summary: 'There is room for more receipts.',
}

/** Every governance route is mocked explicitly; the page never reaches a real platform. */
async function fixture(page: Page, options: { failFirstBinding?: boolean } = {}) {
  const calls: Call[] = []
  let bound = false
  let bindingPosts = 0
  await page.route('**/api/v1/integrations/workflows**', async route => {
    const url = new URL(route.request().url())
    const body = route.request().postDataJSON() as Record<string, unknown> | null
    calls.push({ method: route.request().method(), path: url.pathname, body })
    if (url.pathname.endsWith('/collection-bindings')) {
      return route.fulfill({ json: { schemaVersion: '2.0', items: [binding(bound ? 'BOUND' : 'UNBOUND')], count: 1 } })
    }
    if (url.pathname.includes('/collection-bindings/')) {
      bindingPosts++
      if (options.failFirstBinding && bindingPosts === 1) return route.abort('failed')
      bound = (body?.operation ?? 'ENABLE') === 'ENABLE'
      return route.fulfill({ json: { schemaVersion: '2.0', requestId: body!.requestId, operation: body!.operation, commandDigest: digest('a'), createdAt: '2026-10-13T00:00:01.000Z', binding: binding(bound ? 'BOUND' : 'UNBOUND') } })
    }
    if (url.pathname.endsWith('/entries') || url.pathname.endsWith('/runs')) {
      return route.fulfill({ json: { schemaVersion: '2.0', items: [], nextCursor: null, hasMore: false } })
    }
    return route.fulfill({ json: workspace() })
  })
  await page.route('**/api/v1/integrations/receipts/retention**', async route => {
    const url = new URL(route.request().url())
    const body = route.request().postDataJSON() as Record<string, unknown> | null
    calls.push({ method: route.request().method(), path: url.pathname, body })
    if (url.pathname.endsWith('/preview')) {
      return route.fulfill({ json: { schemaVersion: '1.0', kind: body!.kind, scopeKey: body!.scopeKey, previewDigest: digest('a'), candidates: ['receipt-1', 'receipt-2'], removable: 2, keptProtected: 2, keptUnfinished: 0, maxRemove: body!.maxRemove } })
    }
    if (url.pathname.endsWith('/confirm')) {
      return route.fulfill({ json: { schemaVersion: '1.0', requestId: body!.requestId, commandDigest: digest('b'), createdAt: '2026-10-13T00:00:02.000Z', kind: body!.kind, scopeKey: body!.scopeKey, removed: 2, keptProtected: 2, keptUnfinished: 0, reason: body!.reason } })
    }
    return route.fulfill({ json: { schemaVersion: '1.0', dataMode: 'receipt-log', items: [report], count: 1 } })
  })
  await page.route('**/api/v1/entities/**/deletions**', async route => {
    const body = route.request().postDataJSON() as Record<string, unknown> | null
    calls.push({ method: route.request().method(), path: new URL(route.request().url()).pathname, body })
    return route.fulfill({ json: { schemaVersion: '1.0', requestId: body!.requestId, commandDigest: digest('c'), createdAt: '2026-10-13T00:00:03.000Z', entityId: body!.entityId, outcome: 'SOFT_DELETED', summary: 'The entity is marked deleted; the row and all references are retained.', auditCount: 1, keptProtected: 0, keptUnfinished: 0, reason: body!.reason } })
  })
  await page.goto('/#/integrations/workflows/runs')
  await page.getByRole('textbox', { name: tokenLabel }).fill(TOKEN_OK)
  await expect(page.getByRole('heading', { name: '采集绑定与留存治理' })).toBeVisible()
  await page.getByRole('button', { name: '展开治理操作' }).click()
  await expect(page.getByRole('cell', { name: 'flow-one', exact: true })).toBeVisible()
  return calls
}

test('publishing never enables collection; enabling is its own reasoned, closed command', async ({ page }) => {
  const calls = await fixture(page)
  expect(calls.some(c => c.path.includes('/collection-bindings/') && c.method === 'POST')).toBe(false)
  await expect(page.getByRole('cell', { name: 'UNBOUND', exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '启用采集绑定' })).toBeDisabled()
  await page.getByRole('textbox', { name: '理由（必填）' }).fill('发布后显式启用采集')
  await page.getByRole('button', { name: '启用采集绑定' }).click()
  await expect(page.getByRole('cell', { name: 'BOUND', exact: true })).toBeVisible()
  const post = calls.find(c => c.path.endsWith('/collection-bindings/enable'))
  expect(post?.body).toMatchObject({ id: 'flow-one', revision: 1, sourceInstanceId: 'connection-fixture', operation: 'ENABLE', reason: '发布后显式启用采集' })
  expect(post?.body).not.toHaveProperty('tenantId')
  expect(post?.body).not.toHaveProperty('actor')
  expect(post?.body).not.toHaveProperty('permissions')
  expect(post?.body).not.toHaveProperty('start')
})

test('an unknown binding result keeps the original request and retries it byte for byte', async ({ page }) => {
  const calls = await fixture(page, { failFirstBinding: true })
  await page.getByRole('textbox', { name: '理由（必填）' }).fill('未知结果原样重试')
  await page.getByRole('button', { name: '启用采集绑定' }).click()
  await expect(page.getByRole('button', { name: '按原请求标识确认采集绑定' })).toBeEnabled()
  await page.getByRole('button', { name: '按原请求标识确认采集绑定' }).click()
  await expect(page.getByRole('cell', { name: 'BOUND', exact: true })).toBeVisible()
  const posts = calls.filter(c => c.path.endsWith('/collection-bindings/enable'))
  expect(posts).toHaveLength(2)
  expect(posts[1]!.body).toEqual(posts[0]!.body)
})

test('cleanup previews before it confirms and reading never removes a receipt', async ({ page }) => {
  const calls = await fixture(page)
  await page.getByRole('textbox', { name: '理由（必填）' }).fill('清理过期回执')
  await page.getByRole('button', { name: '预览并确认清理' }).click()
  await expect.poll(() => calls.filter(c => c.path.includes('/receipts/retention') && c.method === 'POST').map(c => c.path)).toEqual(['/api/v1/integrations/receipts/retention/preview', '/api/v1/integrations/receipts/retention/confirm'])
  const confirm = calls.find(c => c.path.endsWith('/confirm'))!.body!
  expect(confirm.previewDigest).toBe('sha256:' + 'a'.repeat(64))
  expect(confirm).not.toHaveProperty('tenantId')
  expect(confirm).not.toHaveProperty('schedule')
})

test('the operational delete entry is reasoned and never a cascade', async ({ page }) => {
  const calls = await fixture(page)
  await expect(page.getByRole('button', { name: '受权删除该实体（软删除）' })).toBeDisabled()
  await page.getByRole('textbox', { name: '理由（必填）' }).fill('运维下线')
  await page.getByRole('textbox', { name: '实体标识' }).fill('10000000-0000-4000-8000-000000000009')
  await page.getByRole('button', { name: '受权删除该实体（软删除）' }).click()
  await expect.poll(() => calls.find(c => c.path.endsWith('/deletions'))).toBeTruthy()
  const post = calls.find(c => c.path.endsWith('/deletions'))
  expect(post?.body).toMatchObject({ entityId: '10000000-0000-4000-8000-000000000009', reason: '运维下线' })
  expect(post?.body).not.toHaveProperty('cascade')
  expect(post?.body).not.toHaveProperty('dryRun')
})

test('governance state is cleared when the identity changes', async ({ page }) => {
  await fixture(page)
  await expect(page.getByRole('cell', { name: 'fixture-operator', exact: true })).toBeVisible()
  await page.getByRole('textbox', { name: tokenLabel }).fill(TOKEN_OK.slice(0, -1) + '9')
  await expect(page.getByRole('cell', { name: 'fixture-operator', exact: true })).toHaveCount(0)
})
