import { test, expect, type Page } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { TOKEN_OK } from './helpers.ts'

const bundle = JSON.parse(readFileSync(new URL('../../../contracts/catalog/opsweave-core-1.0.0.json', import.meta.url), 'utf8'))
const tenantId = 'extlink-fixture-tenant'
const secondToken = 'extlink-fixture-second-token-0000000000000'
const id = (value: number) => `00000000-0000-4000-8000-${value.toString(16).padStart(12, '0')}`
const assets = Array.from({ length: 5 }, (_, index) => ({
  schemaVersion: '1.0', id: id(index + 1), tenantId, entityType: 'Host',
  name: `Host ${String(index + 1).padStart(2, '0')}`, lifecycle: 'ACTIVE', version: 1, attributes: {},
}))

async function setup(page: Page) {
  const calls: { method: string; body: any }[] = []
  const control = { failNext: false }
  await page.route('**/api/v1/catalog', route => route.fulfill({ json: {
    schemaVersion: '1.0', storage: 'memory', package: bundle,
    published: { items: [], truncated: false }, drafts: { items: [], truncated: false },
  } }))
  await page.route('**/api/v1/entities/page**', async route => {
    const owner = route.request().headers().authorization === `Bearer ${secondToken}`
      ? 'extlink-fixture-tenant-two' : tenantId
    const items = owner === tenantId ? assets : [{ ...assets[0]!, id: id(900), name: 'Tenant two host', tenantId: owner }]
    await route.fulfill({ json: { schemaVersion: '1.0', storage: 'memory', query: { q: '', type: '', lifecycle: 'ACTIVE', after: null, limit: 25 }, items, nextCursor: null } })
  })
  await page.route('**/api/v1/external-links**', async route => {
    const body = route.request().postDataJSON() ?? null
    calls.push({ method: route.request().method(), body })
    if (control.failNext) { control.failNext = false; return route.abort('failed') }
    if (route.request().method() === 'POST') {
      return route.fulfill({ json: {
        schemaVersion: '1.0', requestId: body.requestId, replayed: false,
        link: { namespace: body.namespace, objectType: body.objectType, externalKey: body.externalKey, generation: body.generation ?? 'builtin.host', targetId: body.targetId },
      } })
    }
    return route.fulfill({ json: {
      schemaVersion: '1.0', storage: 'memory', tenantId,
      items: [{ link: { namespace: 'workflow.demo', objectType: 'entity', externalKey: 'host-1', generation: 'builtin.host', targetId: id(1) }, reason: '来源身份确认', registeredAt: '2026-10-10T00:00:00Z' }],
      nextCursor: null,
    } })
  })
  await page.goto('/#/modeling/relation-instances')
  await page.getByRole('textbox', { name: '平台开发 Token（仅保存在当前标签页内存）' }).fill(TOKEN_OK)
  await expect(page.getByRole('heading', { name: '关系实例', exact: true })).toBeVisible()
  return { calls, control }
}

test('registering an external link sends the closed command and shows the receipt', async ({ page }) => {
  const { calls } = await setup(page)
  await page.getByRole('button', { name: '登记外部链接' }).click()
  const drawer = page.getByRole('dialog', { name: '登记外部链接' })
  await expect(drawer).toBeVisible()
  await drawer.getByLabel('命名空间').fill('workflow.demo')
  await drawer.getByLabel('外部键').fill('host-1')
  await drawer.getByRole('combobox', { name: '目标实体' }).selectOption(id(1))
  await drawer.getByLabel(/理由/).fill('来源身份确认')
  await drawer.getByRole('button', { name: '登记链接' }).click()
  const receipt = page.getByRole('dialog', { name: '登记回执' })
  await expect(receipt).toBeVisible()
  await expect(receipt).toContainText('已登记')
  await expect(receipt).toContainText('host-1')
  await receipt.getByRole('button', { name: '完成' }).click()
  await expect(receipt).not.toBeVisible()
  const command = calls.find(call => call.method === 'POST')!.body
  expect(command).toMatchObject({ namespace: 'workflow.demo', objectType: 'entity', externalKey: 'host-1', targetId: id(1), reason: '来源身份确认' })
  expect(command.requestId).toMatch(/^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/)
  expect(command.tenantId).toBeUndefined()
  expect(command.actor).toBeUndefined()
})

test('an unknown registration outcome keeps the original request for explicit replay', async ({ page }) => {
  const { calls, control } = await setup(page)
  control.failNext = true
  await page.getByRole('button', { name: '登记外部链接' }).click()
  const drawer = page.getByRole('dialog', { name: '登记外部链接' })
  await drawer.getByLabel('外部键').fill('host-retry')
  await drawer.getByRole('combobox', { name: '目标实体' }).selectOption(id(1))
  await drawer.getByRole('button', { name: '登记链接' }).click()
  await expect(drawer.getByRole('status').filter({ hasText: '原请求结果未知' })).toBeVisible()
  await drawer.getByRole('button', { name: '查询原请求结果（原样重试）' }).click()
  await expect(page.getByRole('dialog', { name: '登记回执' })).toBeVisible()
  const posts = calls.filter(call => call.method === 'POST')
  expect(posts).toHaveLength(2)
  expect(posts[1]!.body.requestId).toBe(posts[0]!.body.requestId)
})

test('changing the platform token clears the registration form state', async ({ page }) => {
  await setup(page)
  await page.getByRole('button', { name: '登记外部链接' }).click()
  await expect(page.getByRole('dialog', { name: '登记外部链接' })).toBeVisible()
  await page.getByRole('textbox', { name: '平台开发 Token（仅保存在当前标签页内存）' }).fill(secondToken)
  await expect(page.getByRole('dialog', { name: '登记外部链接' })).not.toBeVisible()
})
