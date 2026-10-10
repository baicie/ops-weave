import { test, expect, type Page } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { TOKEN_OK } from './helpers.ts'

const bundle = JSON.parse(readFileSync(new URL('../../../contracts/catalog/opsweave-core-1.0.0.json', import.meta.url), 'utf8'))
const tenantId = 'relation-fixture-tenant'
const secondTenantId = 'relation-fixture-tenant-two'
const secondToken = 'relation-fixture-second-token-0000000000000000'
const id = (value: number) => `00000000-0000-4000-8000-${value.toString(16).padStart(12, '0')}`
const assets = [
  ...Array.from({ length: 25 }, (_, index) => entity(index + 1, 'Host', `Host ${String(index + 1).padStart(2, '0')}`)),
  entity(26, 'NetworkInterface', 'Network interface eth0'),
]
function entity(value: number, entityType: string, name: string, owner = tenantId) {
  return { schemaVersion: '1.0', id: id(value), tenantId: owner, entityType, name, lifecycle: 'ACTIVE', version: 1, attributes: {} }
}
function relation(value: number, entityId: string, owner = tenantId) {
  return {
    schemaVersion: '1.0', id: id(1000 + value), tenantId: owner, fromEntityId: entityId, toEntityId: id(2),
    relationType: 'builtin.deployed_on', relationRevision: 1, validFrom: '2026-10-01T00:00:00Z', validTo: null,
    dataMode: (['fixture', 'zabbix-jsonrpc', 'import', 'unknown'] as const)[(value - 1) % 4], version: 1,
  }
}
const customRelation = {
  definition: {
    schemaVersion: '1.0', id: 'custom.network_binding', revision: 1, kind: 'RELATION', label: '自定义网络绑定',
    description: '发布模型 fixture', cleaningProfile: 'safe-scalars-v1', fields: [],
    endpoints: { from: { id: 'builtin.host', revision: 1 }, to: { id: 'builtin.network_interface', revision: 1 }, cardinality: 'ONE_TO_MANY' },
  },
  digest: `sha256:${'c'.repeat(64)}`, state: 'PUBLISHED', editVersion: 0, updatedAt: '2026-10-01T00:00:00Z',
}

function closedRelation(value: number, entityId: string, owner = tenantId) {
  return {
    relation: {
      schemaVersion: '1.0', id: id(3000 + value), tenantId: owner, fromEntityId: entityId, toEntityId: id(2),
      relationType: 'builtin.deployed_on', relationRevision: 1, validFrom: '2026-10-01T00:00:00Z', validTo: '2026-10-02T00:00:00Z',
      dataMode: 'unknown', version: 2,
    },
    closeReason: '接口下线确认',
  }
}

async function setup(page: Page, publishedTruncated = false) {
  const calls: { method: string; path: string; body: any }[] = []
  const closeControl = { failNext: false }
  const correctControl = { failNext: false }
  const withdrawControl = { failNext: false }
  await page.route('**/api/v1/catalog', route => route.fulfill({ json: {
    schemaVersion: '1.0', storage: 'memory', package: bundle,
    published: { items: [customRelation], truncated: publishedTruncated }, drafts: { items: [], truncated: false },
  } }))
  await page.route('**/api/v1/entities/page**', async route => {
    const url = new URL(route.request().url()), query = url.searchParams.get('q') ?? '', after = url.searchParams.get('after')
    const owner = route.request().headers().authorization === `Bearer ${secondToken}` ? secondTenantId : tenantId
    const candidates = owner === secondTenantId ? [entity(200, 'Host', 'Tenant two host', secondTenantId)] : assets
    const matches = candidates.filter(item => item.name.toLowerCase().includes(query.toLowerCase()))
    const offset = after ? Math.max(0, matches.findIndex(item => item.id === after) + 1) : 0
    const items = matches.slice(offset, offset + 25)
    const nextCursor = offset + items.length < matches.length ? items.at(-1)!.id : null
    await route.fulfill({ json: {
      schemaVersion: '1.0', storage: 'memory',
      query: { q: query, type: '', lifecycle: 'ACTIVE', after, limit: 25 }, items, nextCursor,
    } })
  })
  await page.route(/\/api\/v1\/entities\/[^/]+\/relations(?:\?.*)?$/, async route => {
    const url = new URL(route.request().url()), pathParts = url.pathname.split('/'), entityId = pathParts[pathParts.length - 2]!
    const body = route.request().postDataJSON() ?? null
    calls.push({ method: route.request().method(), path: url.pathname + url.search, body })
    if (route.request().method() === 'POST') {
      const owner = route.request().headers().authorization === `Bearer ${secondToken}` ? secondTenantId : tenantId
      const saved = {
        schemaVersion: '1.0', id: id(2000), tenantId: owner, fromEntityId: body.fromEntityId, toEntityId: body.toEntityId,
        relationType: body.relationType, relationRevision: body.relationRevision, validFrom: body.validFrom,
        validTo: body.validTo, dataMode: body.dataMode, version: 1,
      }
      return route.fulfill({ json: { schemaVersion: '1.0', requestId: body.requestId, replayed: false, relation: saved } })
    }
    const owner = route.request().headers().authorization === `Bearer ${secondToken}` ? secondTenantId : tenantId
    const after = url.searchParams.get('after')
    const items = owner === secondTenantId ? [relation(1, entityId, owner)] : after ? [relation(26, entityId)] : Array.from({ length: 25 }, (_, index) => relation(index + 1, entityId))
    return route.fulfill({ json: {
      schemaVersion: '1.0', storage: 'memory', tenantId: owner, entityId, asOf: '2026-10-01T00:00:00Z',
      items, nextCursor: after || owner === secondTenantId ? null : items.at(-1)!.id,
    } })
  })
  await page.route(/\/api\/v1\/entities\/[^/]+\/relations\/history(?:\?.*)?$/, async route => {
    const url = new URL(route.request().url()), pathParts = url.pathname.split('/'), entityId = pathParts[4]!
    const owner = route.request().headers().authorization === `Bearer ${secondToken}` ? secondTenantId : tenantId
    calls.push({ method: route.request().method(), path: url.pathname + url.search, body: null })
    const items = owner === secondTenantId ? [] : [{ relation: relation(1, entityId), closeReason: null }, closedRelation(1, entityId)]
    return route.fulfill({ json: { schemaVersion: '1.0', storage: 'memory', tenantId: owner, entityId, items, nextCursor: null } })
  })
  await page.route(/\/api\/v1\/entities\/[^/]+\/relations\/[^/]+\/close$/, async route => {
    const body = route.request().postDataJSON() ?? null
    calls.push({ method: route.request().method(), path: route.request().url(), body })
    if (closeControl.failNext) { closeControl.failNext = false; return route.abort('failed') }
    const saved = {
      schemaVersion: '1.0', id: body.relationId, tenantId, fromEntityId: id(1), toEntityId: id(2),
      relationType: 'builtin.deployed_on', relationRevision: 1, validFrom: '2026-10-01T00:00:00Z',
      validTo: body.validTo ?? '2026-10-09T00:00:00Z', dataMode: 'unknown', version: 2,
    }
    return route.fulfill({ json: { schemaVersion: '1.0', requestId: body.requestId, replayed: false, relation: saved } })
  })
  await page.route(/\/api\/v1\/entities\/[^/]+\/relations\/[^/]+\/correct$/, async route => {
    const body = route.request().postDataJSON() ?? null
    calls.push({ method: route.request().method(), path: route.request().url(), body })
    if (correctControl.failNext) { correctControl.failNext = false; return route.abort('failed') }
    const saved = {
      schemaVersion: '1.0', id: id(2100), tenantId, fromEntityId: body.fromEntityId, toEntityId: body.toEntityId,
      relationType: body.relationType, relationRevision: body.relationRevision, validFrom: '2026-10-09T00:00:00Z',
      validTo: null, dataMode: 'unknown', version: 1,
    }
    return route.fulfill({ json: { schemaVersion: '1.0', requestId: body.requestId, replayed: false, previousRelationId: body.relationId, relation: saved } })
  })
  const confirmationView = (relationId: string) => ({
    schemaVersion: '1.0', storage: 'memory', tenantId, relationId, relationState: 'ACTIVE', entries: [
      { sourceKind: 'source', sourceKey: 'workflow.source-a', batchRef: 'batch-1', dataMode: 'fixture', state: 'CONFIRMED', confirmedAt: '2026-10-01T00:00:00Z', withdrawnAt: null, withdrawReason: null },
      { sourceKind: 'source', sourceKey: 'workflow.source-b', batchRef: 'batch-7', dataMode: 'import', state: 'CONFIRMED', confirmedAt: '2026-10-01T01:00:00Z', withdrawnAt: null, withdrawReason: null },
      { sourceKind: 'manual', sourceKey: 'operator', batchRef: 'assert-1', dataMode: 'unknown', state: 'WITHDRAWN', confirmedAt: '2026-10-01T02:00:00Z', withdrawnAt: '2026-10-02T00:00:00Z', withdrawReason: '人工撤回断言' },
    ],
  })
  await page.route(/\/api\/v1\/entities\/[^/]+\/relations\/[^/]+\/confirmations$/, async route => {
    const parts = new URL(route.request().url()).pathname.split('/')
    const relationId = parts[parts.length - 1] === 'confirmations' ? parts[parts.length - 2]! : id(1001)
    const body = route.request().postDataJSON() ?? null
    calls.push({ method: route.request().method(), path: route.request().url(), body })
    if (route.request().method() === 'GET') return route.fulfill({ json: confirmationView(relationId) })
    return route.fulfill({ json: {
      schemaVersion: '1.0', requestId: body.requestId, replayed: false, relationId,
      entry: {
        sourceKind: body.sourceKind, sourceKey: body.sourceKey, batchRef: body.batchRef, dataMode: body.dataMode,
        state: 'CONFIRMED', confirmedAt: '2026-10-09T00:00:00Z', withdrawnAt: null, withdrawReason: null,
      },
    } })
  })
  await page.route(/\/api\/v1\/entities\/[^/]+\/relations\/[^/]+\/confirmations\/withdraw$/, async route => {
    const parts = new URL(route.request().url()).pathname.split('/')
    const relationId = parts[parts.length - 3]!
    const body = route.request().postDataJSON() ?? null
    calls.push({ method: route.request().method(), path: route.request().url(), body })
    if (withdrawControl.failNext) { withdrawControl.failNext = false; return route.abort('failed') }
    return route.fulfill({ json: {
      schemaVersion: '1.0', requestId: body.requestId, replayed: false, relationId,
      entry: {
        sourceKind: body.sourceKind, sourceKey: body.sourceKey, batchRef: body.batchRef, dataMode: 'fixture',
        state: 'WITHDRAWN', confirmedAt: '2026-10-01T00:00:00Z', withdrawnAt: '2026-10-09T00:00:00Z', withdrawReason: body.reason,
      },
    } })
  })
  await page.goto('/#/modeling/relation-instances')
  await page.getByRole('textbox', { name: '平台开发 Token（仅保存在当前标签页内存）' }).fill(TOKEN_OK)
  await expect(page.getByRole('heading', { name: '关系实例', exact: true })).toBeVisible()
  await expect(page.getByRole('status').filter({ hasText: '本页 25 项，结果未完整' })).toBeVisible()
  return { calls, secondToken, closeControl, correctControl, withdrawControl }
}

test('search and cursor pages expose incomplete results for assets and relations', async ({ page }) => {
  const { calls } = await setup(page)
  const table = page.getByRole('table', { name: '已保存关系实例' })
  await expect(table.locator('tbody tr')).toHaveCount(25)
  for (const mode of ['Fixture', 'Zabbix', '导入', '未知']) {
    await expect(table.getByRole('cell', { name: mode }).first()).toBeVisible()
  }
  await page.getByRole('button', { name: '加载更多关系' }).click()
  await expect(table.locator('tbody tr')).toHaveCount(26)
  await expect(page.getByRole('button', { name: '加载更多关系' })).toHaveCount(0)
  const relationReads = calls.filter(call => call.method === 'GET')
  expect(new URLSearchParams(relationReads[0]!.path.split('?')[1]).get('asOf')).toBeNull()
  expect(new URLSearchParams(relationReads[1]!.path.split('?')[1]).get('asOf')).toBe('2026-10-01T00:00:00Z')

  await page.getByRole('textbox', { name: '搜索授权资产' }).fill('eth0')
  await page.getByRole('button', { name: '搜索授权资产' }).click()
  await expect(page.getByRole('combobox', { name: '查看端点资产' })).toContainText('Network interface eth0')
  await expect(page.getByRole('status').filter({ hasText: '本页 1 项，已到末页' })).toBeVisible()
})

test('published custom relations load and network interface endpoints are selectable across pages', async ({ page }) => {
  const { calls } = await setup(page)
  const open = page.getByRole('button', { name: '新建关系' })
  await open.click()
  const drawer = page.getByRole('dialog', { name: '新建关系' })
  await expect(drawer).toBeVisible()
  const model = drawer.getByRole('combobox', { name: '关系类型' })
  await expect(model).toBeFocused()
  await expect(model.locator('option', { hasText: '自定义网络绑定 · custom.network_binding@1' })).toHaveCount(1)
  await model.selectOption('builtin.has_interface@1')

  const from = drawer.getByRole('combobox', { name: '起点资产' })
  const to = drawer.getByRole('combobox', { name: '终点资产' })
  await expect(from.locator('option', { hasText: 'Host 01' })).toHaveCount(1)
  await expect(to.locator('option', { hasText: 'Network interface eth0' })).toHaveCount(0)
  await expect(drawer.getByRole('status').filter({ hasText: '本页 0 条符合条件，结果未完整' })).toBeVisible()
  await from.selectOption(id(1))
  await drawer.getByRole('button', { name: '下一页终点资产' }).click()
  await expect(to.locator('option', { hasText: 'Network interface eth0' })).toHaveCount(1)
  await to.selectOption(id(26))
  await expect(drawer.getByRole('button', { name: '创建关系' })).toBeEnabled()
  await drawer.getByRole('button', { name: '创建关系' }).click()
  await expect(drawer).not.toBeVisible()
  await expect(open).toBeFocused()
  const command = calls.find(call => call.method === 'POST')!.body
  expect(command).toMatchObject({ relationType: 'builtin.has_interface', relationRevision: 1, fromEntityId: id(1), toEntityId: id(26) })
})

test('drawer traps focus, closes on Escape and returns focus to its opener', async ({ page }) => {
  await setup(page)
  const open = page.getByRole('button', { name: '新建关系' })
  await open.click()
  const drawer = page.getByRole('dialog', { name: '新建关系' })
  const model = drawer.getByRole('combobox', { name: '关系类型' })
  const close = drawer.getByRole('button', { name: '关闭关系抽屉' })
  const cancel = drawer.getByRole('button', { name: '取消' })
  await expect(model).toBeFocused()
  await open.focus()
  await page.keyboard.press('Tab')
  await expect(close).toBeFocused()
  await page.keyboard.press('Tab')
  await expect(model).toBeFocused()
  await page.keyboard.press('Shift+Tab')
  await expect(close).toBeFocused()
  await page.keyboard.press('Shift+Tab')
  await expect(cancel).toBeFocused()
  await page.keyboard.press('Tab')
  await expect(close).toBeFocused()
  await page.keyboard.press('Tab')
  await expect(model).toBeFocused()
  await page.keyboard.press('Escape')
  await expect(drawer).not.toBeVisible()
  await expect(open).toBeFocused()
})

test('a truncated published model catalog is disclosed before and inside the drawer', async ({ page }) => {
  await setup(page, true)
  await expect(page.getByRole('status').filter({ hasText: '已发布模型目录达到返回上限' })).toBeVisible()
  await page.getByRole('button', { name: '新建关系' }).click()
  await expect(page.getByRole('dialog', { name: '新建关系' }).getByRole('status').filter({ hasText: '部分较早的自定义关系类型可能未显示' })).toBeVisible()
})

test('relation asset search and drawer controls stay within a mobile viewport', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await setup(page)
  await page.getByRole('button', { name: '新建关系' }).click()
  const drawer = page.getByRole('dialog', { name: '新建关系' })
  await expect(drawer).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  const bounds = await drawer.boundingBox()
  expect(bounds?.width).toBeLessThanOrEqual(390)
})

test('changing the platform token clears the previous identity asset and relation state', async ({ page }) => {
  await setup(page)
  await expect(page.getByRole('table', { name: '已保存关系实例' }).locator('tbody tr')).toHaveCount(25)
  await page.getByRole('textbox', { name: '平台开发 Token（仅保存在当前标签页内存）' }).fill(secondToken)
  const selector = page.getByRole('combobox', { name: '查看端点资产' })
  await expect(selector).toContainText('Tenant two host')
  await expect(selector).not.toContainText('Host 01')
  await expect(page.getByRole('table', { name: '已保存关系实例' }).locator('tbody tr')).toHaveCount(1)
})

test('history view keeps terminated relations with their recorded close reason', async ({ page }) => {
  await setup(page)
  await page.getByRole('button', { name: '维护历史' }).click()
  const table = page.getByRole('table', { name: '关系维护历史' })
  await expect(table).toBeVisible()
  await expect(table.locator('tbody tr')).toHaveCount(2)
  await expect(table.locator('tbody tr').nth(1)).toContainText('已终止')
  await expect(table.locator('tbody tr').first()).toContainText('生效中')
  await table.locator('tbody tr').nth(1).getByRole('button', { name: '详情' }).click()
  const detail = page.getByRole('dialog', { name: '关系详情' })
  await expect(detail).toContainText('接口下线确认')
  await expect(detail).toContainText('已终止')
  await detail.getByRole('button', { name: '关闭', exact: true }).click()
  await expect(detail).not.toBeVisible()
  await page.getByRole('button', { name: '当前关系' }).click()
  await expect(page.getByRole('table', { name: '已保存关系实例' })).toBeVisible()
})

test('terminating a relation records the operator reason and expected version', async ({ page }) => {
  const { calls } = await setup(page)
  const table = page.getByRole('table', { name: '已保存关系实例' })
  await table.locator('tbody tr').first().getByRole('button', { name: '终止关系' }).click()
  const drawer = page.getByRole('dialog', { name: '终止关系' })
  await expect(drawer).toBeVisible()
  await drawer.getByRole('button', { name: '终止关系' }).click()
  await expect(drawer.getByRole('alert')).toContainText('请填写终止理由')
  await drawer.getByLabel('终止理由').fill('业务下线确认')
  await drawer.getByRole('button', { name: '终止关系' }).click()
  await expect(drawer).not.toBeVisible()
  const command = calls.find(call => call.method === 'POST')!.body
  expect(command).toMatchObject({ relationId: id(1001), reason: '业务下线确认', expectedVersion: 1 })
  expect(command.validTo).toBeNull()
  expect(command.requestId).toMatch(/^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/)
})

test('an unknown close outcome keeps the original command for explicit replay', async ({ page }) => {
  const { calls, closeControl } = await setup(page)
  closeControl.failNext = true
  const table = page.getByRole('table', { name: '已保存关系实例' })
  await table.locator('tbody tr').first().getByRole('button', { name: '终止关系' }).click()
  const drawer = page.getByRole('dialog', { name: '终止关系' })
  await drawer.getByLabel('终止理由').fill('接口下线确认')
  await drawer.getByRole('button', { name: '终止关系' }).click()
  await expect(drawer.getByRole('status').filter({ hasText: '原请求结果未知' })).toBeVisible()
  await expect(drawer.getByRole('button', { name: '查询原请求结果（原样重试）' })).toBeVisible()
  await drawer.getByRole('button', { name: '查询原请求结果（原样重试）' }).click()
  await expect(drawer).not.toBeVisible()
  const closes = calls.filter(call => call.method === 'POST')
  expect(closes).toHaveLength(2)
  expect(closes[1]!.body.requestId).toBe(closes[0]!.body.requestId)
  expect(closes[1]!.body.reason).toBe('接口下线确认')
})

async function openCorrection(page: Page) {
  const table = page.getByRole('table', { name: '已保存关系实例' })
  await table.locator('tbody tr').first().getByRole('button', { name: '更正关系' }).click()
  const drawer = page.getByRole('dialog', { name: '更正关系' })
  await expect(drawer).toBeVisible()
  await drawer.getByRole('combobox', { name: '关系类型' }).selectOption('builtin.has_interface@1')
  await drawer.getByRole('button', { name: '下一页终点资产' }).click()
  await drawer.getByRole('combobox', { name: '终点资产' }).selectOption(id(26))
  await drawer.getByLabel('更正理由').fill('端点录入错误，改为实际接口资产')
  return drawer
}

test('correcting a relation sends the closed correction command and keeps the original record', async ({ page }) => {
  const { calls } = await setup(page)
  const drawer = await openCorrection(page)
  await drawer.getByRole('button', { name: '更正关系' }).click()
  await expect(drawer).not.toBeVisible()
  const command = calls.filter(call => call.method === 'POST' && call.path.endsWith('/correct'))[0]!.body
  expect(command).toMatchObject({
    relationId: id(1001), reason: '端点录入错误，改为实际接口资产', expectedVersion: 1,
    fromEntityId: id(1), toEntityId: id(26), relationType: 'builtin.has_interface', relationRevision: 1,
  })
  expect(command.requestId).toMatch(/^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/)
  expect(Object.keys(command)).not.toContain('tenantId')
  expect(Object.keys(command)).not.toContain('actor')
  await expect(page.getByRole('dialog', { name: '关系详情' })).toHaveCount(0)
})

test('an unknown correction outcome keeps the original command for explicit replay', async ({ page }) => {
  const { calls, correctControl } = await setup(page)
  correctControl.failNext = true
  const drawer = await openCorrection(page)
  await drawer.getByRole('button', { name: '更正关系' }).click()
  await expect(drawer.getByRole('status').filter({ hasText: '原请求结果未知' })).toBeVisible()
  await expect(drawer.getByRole('button', { name: '查询原请求结果（原样重试）' })).toBeVisible()
  await drawer.getByRole('button', { name: '查询原请求结果（原样重试）' }).click()
  await expect(drawer).not.toBeVisible()
  const corrections = calls.filter(call => call.method === 'POST' && call.path.endsWith('/correct'))
  expect(corrections).toHaveLength(2)
  expect(corrections[1]!.body.requestId).toBe(corrections[0]!.body.requestId)
  expect(corrections[1]!.body.reason).toBe('端点录入错误，改为实际接口资产')
})

test('the detail drawer separates still-confirmed and withdrawn source confirmations', async ({ page }) => {
  const { calls } = await setup(page)
  const table = page.getByRole('table', { name: '已保存关系实例' })
  await table.locator('tbody tr').first().getByRole('button', { name: '详情' }).click()
  const detail = page.getByRole('dialog', { name: '关系详情' })
  await expect(detail.getByRole('heading', { name: '多来源确认' })).toBeVisible()
  await expect(detail.getByRole('cell', { name: '仍确认' })).toHaveCount(2)
  await expect(detail.getByRole('cell', { name: '已撤回' })).toHaveCount(1)
  await expect(detail.getByText('人工撤回断言')).toBeVisible()
  await expect(detail.getByText('workflow.source-a')).toBeVisible()
  await detail.getByRole('row', { name: /workflow\.source-a/ }).getByRole('button', { name: '撤回' }).click()
  const drawer = page.getByRole('dialog', { name: '来源确认维护' })
  await expect(drawer.getByLabel('撤回来源标识')).toHaveValue('workflow.source-a')
  await expect(drawer.getByLabel('撤回批次标识')).toHaveValue('batch-1')
  await drawer.getByLabel('撤回理由').fill('来源批次撤销')
  await drawer.getByRole('button', { name: '撤回来源确认' }).click()
  await expect(drawer).not.toBeVisible()
  const withdraw = calls.filter(call => call.method === 'POST' && call.path.endsWith('/confirmations/withdraw'))[0]!.body
  expect(withdraw).toMatchObject({ sourceKind: 'source', sourceKey: 'workflow.source-a', batchRef: 'batch-1', reason: '来源批次撤销' })
  expect(withdraw.requestId).toMatch(/^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/)
  expect(Object.keys(withdraw)).not.toContain('tenantId')
  expect(Object.keys(withdraw)).not.toContain('actor')
})

test('registering a source confirmation sends the closed confirmation command', async ({ page }) => {
  const { calls } = await setup(page)
  const table = page.getByRole('table', { name: '已保存关系实例' })
  await table.locator('tbody tr').first().getByRole('button', { name: '详情' }).click()
  await page.getByRole('dialog', { name: '关系详情' }).getByRole('button', { name: '登记来源确认' }).click()
  const drawer = page.getByRole('dialog', { name: '来源确认维护' })
  await expect(drawer).toBeVisible()
  await drawer.getByLabel('来源标识', { exact: true }).fill('workflow.source-c')
  await drawer.getByLabel('批次标识', { exact: true }).fill('batch-3')
  await drawer.getByLabel('数据模式').selectOption('fixture')
  await drawer.getByRole('button', { name: '登记来源确认' }).click()
  await expect(drawer).not.toBeVisible()
  const command = calls.filter(call => call.method === 'POST' && call.path.endsWith('/confirmations'))[0]!.body
  expect(command).toMatchObject({ sourceKind: 'source', sourceKey: 'workflow.source-c', batchRef: 'batch-3', dataMode: 'fixture' })
  expect(command.requestId).toMatch(/^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/)
  expect(Object.keys(command)).not.toContain('tenantId')
})

test('changing the platform token clears detail and confirmation drawer state', async ({ page }) => {
  await setup(page)
  const table = page.getByRole('table', { name: '已保存关系实例' })
  await table.locator('tbody tr').first().getByRole('button', { name: '详情' }).click()
  await page.getByRole('dialog', { name: '关系详情' }).getByRole('button', { name: '登记来源确认' }).click()
  await expect(page.getByRole('dialog', { name: '来源确认维护' })).toBeVisible()
  await page.getByRole('textbox', { name: '平台开发 Token（仅保存在当前标签页内存）' }).fill(secondToken)
  await expect(page.getByRole('dialog', { name: '来源确认维护' })).toHaveCount(0)
  await expect(page.getByRole('dialog', { name: '关系详情' })).toHaveCount(0)
})
