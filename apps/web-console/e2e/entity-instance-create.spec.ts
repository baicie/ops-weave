import { expect, test, type Page } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { TOKEN_OK } from './helpers.ts'

const bundle = JSON.parse(readFileSync(new URL('../../../contracts/catalog/opsweave-core-1.0.0.json', import.meta.url), 'utf8'))
const tenantId = 'entity-create-fixture-tenant'
const model = {
  schemaVersion: '1.0', id: 'custom.checkout_service', revision: 1, kind: 'ENTITY', label: '结算服务',
  description: '创建表单 e2e 模型', cleaningProfile: 'safe-scalars-v1',
  fields: [
    { id: 'service_code', label: '服务编码', type: 'TEXT', required: true, maxLength: 40 },
    { id: 'enabled', label: '是否启用', type: 'BOOLEAN', required: false },
    { id: 'severity', label: '等级', type: 'ENUM', required: true, maxLength: 12, choices: ['low', 'high'] },
    { id: 'load_factor', label: '负载系数', type: 'DECIMAL', required: false, min: 0, max: 10 },
    { id: 'registered_at', label: '登记时间', type: 'DATETIME', required: false },
  ],
}
const digest = `sha256:${'a'.repeat(64)}`

function catalog() {
  return {
    schemaVersion: '1.0', storage: 'memory', package: bundle,
    published: { items: [{ definition: model, digest, state: 'PUBLISHED', editVersion: 0, updatedAt: '2026-10-01T00:00:00Z' }], truncated: false },
    drafts: { items: [], truncated: false },
  }
}

function entity(command: Record<string, any>) {
  return {
    schemaVersion: '1.0', id: command.entityId, tenantId, entityType: command.model.id,
    name: command.name, lifecycle: command.lifecycle ?? 'ACTIVE', version: 1,
    model: { id: command.model.id, revision: command.model.revision, digest }, attributes: command.attributes,
  }
}

async function setup(page: Page, create: (command: Record<string, any>, attempt: number) => Promise<{ status?: number; json?: unknown }> = async command => ({
  json: { schemaVersion: '1.0', storage: 'memory', replayed: false, tenantId,
    model: { id: command.model.id, revision: command.model.revision, digest }, entity: entity(command) },
})) {
  let catalogReads = 0
  let attempt = 0
  const commands: Record<string, any>[] = []
  await page.route('**/api/v1/catalog', async route => {
    catalogReads++
    await route.fulfill({ json: catalog() })
  })
  await page.route('**/api/v1/entities', async route => {
    if (route.request().method() !== 'POST') return route.fallback()
    const command = route.request().postDataJSON() as Record<string, any>
    commands.push(command)
    attempt++
    const response = await create(command, attempt)
    await route.fulfill({ status: response.status ?? 200, json: response.json ?? {} })
  })
  await page.route('**/api/v1/entities/page?**', async route => {
    const url = new URL(route.request().url())
    await route.fulfill({ json: {
      storage: 'memory', query: { q: url.searchParams.get('q') ?? '', type: url.searchParams.get('type') ?? '',
        lifecycle: url.searchParams.get('lifecycle') ?? '', after: url.searchParams.get('after'), limit: 25 },
      items: commands.length ? [entity(commands.at(-1)!)] : [], nextCursor: null,
    } })
  })
  await page.goto('/#/inventory')
  await page.getByRole('textbox', { name: '平台开发 Token（仅保存在当前标签页内存）' }).fill(TOKEN_OK)
  await page.getByRole('button', { name: '新建实体实例' }).click()
  await expect(page.getByRole('dialog', { name: '创建实体实例' })).toBeVisible()
  return { commands, catalogReads: () => catalogReads }
}

async function fillEntityForm(page: Page) {
  const drawer = page.getByRole('dialog', { name: '创建实体实例' })
  await drawer.getByRole('combobox', { name: '实体类型' }).selectOption('custom.checkout_service@1')
  await drawer.getByRole('textbox', { name: '名称' }).fill(' 结算 API ')
  await drawer.getByRole('textbox', { name: '服务编码' }).fill(' checkout-v2 ')
  await drawer.getByRole('combobox', { name: '是否启用' }).selectOption('false')
  await drawer.getByRole('combobox', { name: '等级' }).selectOption('high')
  await drawer.getByRole('spinbutton', { name: '负载系数' }).fill('2.5')
  await drawer.getByLabel('登记时间').fill('2026-10-06T10:30')
}

test('creates an entity from a published model and displays its complete model fields', async ({ page }) => {
  const { commands, catalogReads } = await setup(page)
  const expectedDate = await page.evaluate(() => new Date('2026-10-06T10:30').toISOString())
  await fillEntityForm(page)
  await page.getByRole('button', { name: '创建实体', exact: true }).click()
  await expect(page.getByRole('dialog', { name: '创建实体实例' })).toHaveCount(0)
  await expect(page.locator('[data-model-attributes]')).toContainText('service_code')
  await expect(page.locator('[data-model-attributes]')).toContainText('服务编码')
  await expect(page.locator('[data-model-attributes]')).toContainText('checkout-v2')
  await expect(page.locator('[data-model-attributes]')).toContainText('负载系数')
  await expect(page.locator('[data-model-attributes]')).toContainText('load_factor')
  await expect(page.locator('[data-model-attributes]')).toContainText('2.5')
  expect(catalogReads()).toBe(1)
  expect(commands).toHaveLength(1)
  expect(commands[0]).toMatchObject({ model: { id: 'custom.checkout_service', revision: 1 }, name: '结算 API', lifecycle: 'ACTIVE',
    attributes: { service_code: 'checkout-v2', enabled: false, severity: 'high', load_factor: 2.5, registered_at: expectedDate } })
  expect(Object.keys(commands[0]!).sort()).toEqual(['attributes', 'entityId', 'lifecycle', 'model', 'name', 'requestId'].sort())
  expect(commands[0]!.requestId).toMatch(/^[0-9a-f-]{36}$/i)
  expect(commands[0]!.entityId).toMatch(/^[0-9a-f-]{36}$/i)
  expect(JSON.stringify(commands[0])).not.toContain('tenantId')
  expect(JSON.stringify(commands[0])).not.toContain('sourceRef')
})

test('resolves the pinned model definition when reopening an entity detail', async ({ page }) => {
  const { commands } = await setup(page)
  await fillEntityForm(page)
  await page.getByRole('button', { name: '创建实体', exact: true }).click()
  await expect(page.getByRole('dialog', { name: '创建实体实例' })).toHaveCount(0)
  await expect(page.locator('[data-entity-detail]')).toBeVisible()

  const entityId = commands[0]!.entityId
  await page.reload()
  await page.getByRole('textbox', { name: '平台开发 Token（仅保存在当前标签页内存）' }).fill(TOKEN_OK)
  await page.route(`**/api/v1/entities/${entityId}`, async route => {
    await route.fulfill({ json: entity(commands[0]!) })
  })
  let versionReads = 0
  await page.route('**/api/v1/catalog/versions/custom.checkout_service/1', async route => {
    versionReads++
    await route.fulfill({ json: { definition: model, digest, state: 'PUBLISHED', editVersion: 0, updatedAt: '2026-10-01T00:00:00Z' } })
  })
  await page.getByRole('button', { name: '读取选中资产' }).click()
  await expect(page.locator('[data-model-attributes]')).toContainText('负载系数')
  await expect(page.locator('[data-model-attributes]')).toContainText('load_factor')
  expect(versionReads).toBe(1)
})

test('retries an uncertain create using the original request id and exact body', async ({ page }) => {
  const { commands } = await setup(page, async (command, attempt) => attempt === 1
    ? { status: 503, json: { error: 'UNAVAILABLE' } }
    : { json: { schemaVersion: '1.0', storage: 'memory', replayed: true, tenantId,
      model: { id: command.model.id, revision: command.model.revision, digest }, entity: entity(command) } })
  await fillEntityForm(page)
  await page.getByRole('button', { name: '创建实体', exact: true }).click()
  const drawer = page.getByRole('dialog', { name: '创建实体实例' })
  await expect(drawer.getByText(/提交结果未知/)).toBeVisible()
  await expect(drawer.getByRole('button', { name: /原样重试/ })).toBeEnabled()
  await drawer.getByRole('button', { name: /原样重试/ }).click()
  await expect(drawer).toHaveCount(0)
  expect(commands).toHaveLength(2)
  expect(commands[1]).toEqual(commands[0])
})

test('switching identity reloads the cached model catalog before the next create', async ({ page }) => {
  const { commands, catalogReads } = await setup(page)
  await page.getByRole('combobox', { name: '实体类型' }).selectOption('custom.checkout_service@1')
  await page.getByRole('textbox', { name: '服务编码' }).fill('private-state')
  await page.getByRole('button', { name: '关闭创建实体' }).click()
  await expect(page.getByRole('dialog', { name: '创建实体实例' })).toHaveCount(0)
  await page.getByRole('textbox', { name: '平台开发 Token（仅保存在当前标签页内存）' }).fill('another-developer-token-32-characters-long')
  await page.getByRole('button', { name: '新建实体实例' }).click()
  const drawer = page.getByRole('dialog', { name: '创建实体实例' })
  await expect(drawer).toBeVisible()
  await drawer.getByRole('combobox', { name: '实体类型' }).selectOption('custom.checkout_service@1')
  await expect(drawer.getByRole('textbox', { name: '服务编码' })).toHaveValue('')
  expect(catalogReads()).toBe(2)
  expect(commands).toHaveLength(0)
})
