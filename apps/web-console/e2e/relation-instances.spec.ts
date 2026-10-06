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

async function setup(page: Page, publishedTruncated = false) {
  const calls: { method: string; path: string; body: any }[] = []
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
  await page.goto('/#/modeling/relation-instances')
  await page.getByRole('textbox', { name: '平台开发 Token（仅保存在当前标签页内存）' }).fill(TOKEN_OK)
  await expect(page.getByRole('heading', { name: '关系实例', exact: true })).toBeVisible()
  await expect(page.getByRole('status').filter({ hasText: '本页 25 项，结果未完整' })).toBeVisible()
  return { calls, secondToken }
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
