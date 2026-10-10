import { expect, test, type Page } from '@playwright/test'
import { createHash } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { TOKEN_OK } from './helpers.ts'

const bundle = JSON.parse(readFileSync(new URL('../../../contracts/catalog/opsweave-core-1.0.0.json', import.meta.url), 'utf8'))
const tenantId = 'model-extension-fixture-tenant'
const secondToken = 'model-extension-second-token-000000000000'
const digest = `sha256:${'a'.repeat(64)}`
const id = (value: number) => `00000000-0000-4000-8000-${value.toString(16).padStart(12, '0')}`

/** Mirrors contracts digest scheme opsweave-model-extension-v1 (fields sorted by id). */
function extensionDigest(input: { base: { id: string; revision: number }; revision: number; fields: Record<string, any>[] }): string {
  const decimal = (value: unknown) => value === undefined || value === null ? '' : String(value)
  const parts = ['opsweave-model-extension-v1', input.base.id, String(input.base.revision), String(input.revision), 'safe-scalars-v1']
  for (const field of [...input.fields].sort((a, b) => a.id < b.id ? -1 : a.id > b.id ? 1 : 0)) {
    parts.push(field.id, field.label, field.type, 'false', field.maxLength === undefined ? '' : String(field.maxLength),
      decimal(field.min), decimal(field.max), String(field.choices?.length ?? 0))
    for (const choice of field.choices ?? []) parts.push(choice)
  }
  const hash = createHash('sha256')
  for (const part of parts) { const bytes = Buffer.from(part, 'utf8'); hash.update(String(bytes.length) + ':'); hash.update(bytes) }
  return 'sha256:' + hash.digest('hex')
}

type Fixture = {
  extensionPosts: { path: string; body: any }[]
  backfillPosts: any[]
  control: { failNextBackfill: boolean }
  extensions: { published: any[]; drafts: any[] }
}

async function setup(page: Page, options: { route?: string; extension?: Record<string, any> | null; entityAttributes?: Record<string, any> } = {}): Promise<Fixture> {
  const fixture: Fixture = { extensionPosts: [], backfillPosts: [], control: { failNextBackfill: false }, extensions: { published: [], drafts: [] } }
  if (options.extension) fixture.extensions.published.push({
    extension: { schemaVersion: '1.0', base: options.extension.base, revision: 1, digest, fields: options.extension.fields },
    state: 'PUBLISHED', editVersion: 0, updatedAt: '2026-10-10T00:00:00Z',
  })
  await page.route('**/api/v1/catalog', route => route.fulfill({ json: {
    schemaVersion: '1.0', storage: 'memory', package: bundle,
    published: { items: [], truncated: false }, drafts: { items: [], truncated: false },
  } }))
  await page.route(/\/api\/v1\/catalog\/extensions(\/.*)?$/, async route => {
    const request = route.request()
    if (request.method() === 'GET') {
      return route.fulfill({ json: {
        schemaVersion: '1.0', storage: 'memory',
        published: { items: fixture.extensions.published, truncated: false },
        drafts: { items: fixture.extensions.drafts, truncated: false },
      } })
    }
    const body = request.postDataJSON()
    fixture.extensionPosts.push({ path: new URL(request.url()).pathname, body })
    if (new URL(request.url()).pathname.endsWith('/drafts')) {
      const revision = fixture.extensions.published.length + 1
      const entry = {
        extension: { schemaVersion: '1.0', base: body.base, revision, digest: extensionDigest({ base: body.base, revision, fields: body.fields }), fields: body.fields },
        state: 'DRAFT', editVersion: body.expectedEditVersion + 1, updatedAt: '2026-10-10T00:00:00Z',
      }
      fixture.extensions.drafts.splice(0, fixture.extensions.drafts.length, entry)
      return route.fulfill({ json: entry })
    }
    const draft = fixture.extensions.drafts[0]!
    const published = { ...draft, state: 'PUBLISHED', editVersion: 0 }
    fixture.extensions.published.splice(0, fixture.extensions.published.length, published)
    fixture.extensions.drafts.splice(0, fixture.extensions.drafts.length)
    return route.fulfill({ json: published })
  })
  await page.route('**/api/v1/entities/*', route => route.fulfill({ json: {
    schemaVersion: '1.0', id: id(1), tenantId, entityType: 'Host', name: 'edge-01', lifecycle: 'ACTIVE', version: 1,
    model: { id: 'builtin.host', revision: 1, digest }, attributes: { hostname: 'edge-01', ...(options.entityAttributes ?? {}) },
  } }))
  await page.route('**/api/v1/entities/page?**', async route => {
    const url = new URL(route.request().url())
    await route.fulfill({ json: {
      storage: 'memory', query: { q: url.searchParams.get('q') ?? '', type: url.searchParams.get('type') ?? '',
        lifecycle: url.searchParams.get('lifecycle') ?? '', after: url.searchParams.get('after'), limit: 25 },
      items: [{
        schemaVersion: '1.0', id: id(1), tenantId, entityType: 'Host', name: 'edge-01', lifecycle: 'ACTIVE', version: 1,
        model: { id: 'builtin.host', revision: 1, digest }, attributes: { hostname: 'edge-01', ...(options.entityAttributes ?? {}) },
      }], nextCursor: null,
    } })
  })
  await page.route('**/api/v1/entities/model-pins/backfill**', async route => {
    const request = route.request()
    if (request.method() === 'GET') return route.fulfill({ status: 404, json: { error: 'NOT_FOUND' } })
    const body = request.postDataJSON()
    fixture.backfillPosts.push(body)
    if (fixture.control.failNextBackfill) { fixture.control.failNextBackfill = false; return route.abort('failed') }
    return route.fulfill({ json: {
      schemaVersion: '1.0', storage: 'memory', replayed: fixture.backfillPosts.length > 1 && body.requestId === fixture.backfillPosts[0]?.requestId, tenantId,
      requestId: body.requestId,
      request: { entityType: body.entityType, model: { id: body.model.id, revision: body.model.revision, digest } },
      counts: { scanned: 3, updated: 2, keptWithoutPin: 1, skippedPinned: 4, skippedSource: 1 },
      completedAt: '2026-10-10T00:00:00Z',
    } })
  })
  await page.goto('/#/' + (options.route ?? 'modeling/entities'))
  await page.getByRole('textbox', { name: '平台开发 Token（仅保存在当前标签页内存）' }).fill(TOKEN_OK)
  if ((options.route ?? 'modeling/entities') === 'inventory') await expect(page.locator('[data-inventory-page]')).toBeVisible()
  else await expect(page.locator('button.model-definition-button').first()).toBeVisible()
  return fixture
}

test('tenant extension fields are declared, saved and published over an immutable base model', async ({ page }) => {
  const fixture = await setup(page)
  await page.getByRole('button', { name: /内置 主机/ }).click()
  const dialog = page.getByRole('dialog', { name: '模型定义' })
  const section = dialog.locator('[data-model-extension]')
  await expect(section).toBeVisible()
  await expect(section).toContainText('模型本体、已有字段语义与系统元信息不可覆盖')
  await expect(section).toContainText('该版本当前没有已发布的租户扩展。')
  await section.getByRole('button', { name: '＋ 添加扩展字段' }).click()
  await section.getByLabel('扩展字段标识 1').fill('business_code')
  await section.getByLabel('扩展字段名称 1').fill('业务编码')
  await section.getByRole('button', { name: '保存扩展草稿' }).click()
  await expect(section).toContainText('扩展私有草稿已保存')
  await expect(section).toContainText('预定发布 v1')
  const draft = fixture.extensionPosts.find(post => post.path.endsWith('/drafts'))!
  expect(draft).toBeDefined()
  expect(Object.keys(draft.body).sort()).toEqual(['base', 'expectedEditVersion', 'fields'])
  expect(draft.body.base).toEqual({ id: 'builtin.host', revision: 1 })
  expect(draft.body.expectedEditVersion).toBe(0)
  expect(draft.body.fields).toEqual([{ id: 'business_code', label: '业务编码', type: 'TEXT', required: false, maxLength: 255 }])
  expect(JSON.stringify(draft.body)).not.toContain('tenantId')
  expect(JSON.stringify(draft.body)).not.toContain('actor')
  await section.getByRole('button', { name: '发布扩展版本' }).click()
  await expect(section).toContainText('扩展版本已发布；内置模型本体与旧扩展版本未被修改。')
  await expect(section).toContainText('已发布扩展版本：v1')
  const publish = fixture.extensionPosts.find(post => post.path.endsWith('/publish'))!
  expect(publish).toBeDefined()
  expect(Object.keys(publish.body).sort()).toEqual(['base', 'digest', 'expectedEditVersion'])
  expect(publish.body.expectedEditVersion).toBe(1)
  expect(publish.body.digest).toMatch(/^sha256:[a-f0-9]{64}$/)
  expect(JSON.stringify(publish.body)).not.toContain('tenantId')
  expect(JSON.stringify(publish.body)).not.toContain('actor')
  expect(fixture.extensionPosts.filter(post => post.path.endsWith('/drafts'))).toHaveLength(1)
})

test('duplicate extension field identities are rejected before any request is sent', async ({ page }) => {
  const fixture = await setup(page)
  await page.getByRole('button', { name: /内置 主机/ }).click()
  const section = page.getByRole('dialog', { name: '模型定义' }).locator('[data-model-extension]')
  await section.getByRole('button', { name: '＋ 添加扩展字段' }).click()
  await section.getByRole('button', { name: '＋ 添加扩展字段' }).click()
  await section.getByLabel('扩展字段标识 1').fill('business_code')
  await section.getByLabel('扩展字段标识 2').fill('business_code')
  await section.getByRole('button', { name: '保存扩展草稿' }).click()
  await expect(section.getByRole('alert')).toContainText('重复')
  expect(fixture.extensionPosts.filter(post => post.path.endsWith('/drafts'))).toHaveLength(0)
})

test('entity detail keeps extension field values with labels and raw field ids when definitions are missing', async ({ page }) => {
  const fixture = await setup(page, {
    route: 'inventory',
    extension: { base: { id: 'builtin.host', revision: 1 }, fields: [{ id: 'business_code', label: '业务编码', type: 'TEXT', required: false, maxLength: 64 }] },
    entityAttributes: { business_code: 'B-1' },
  })
  await page.getByRole('button', { name: '查看详情' }).click()
  const attributes = page.locator('[data-model-attributes]')
  await expect(attributes).toContainText('业务编码')
  await expect(attributes).toContainText('business_code')
  await expect(attributes).toContainText('B-1')
  expect(fixture.extensionPosts).toHaveLength(0)
})

test('missing extension definitions fall back to raw field ids without guessing', async ({ page }) => {
  await setup(page, { route: 'inventory', extension: null, entityAttributes: { business_code: 'B-1' } })
  await page.getByRole('button', { name: '查看详情' }).click()
  const attributes = page.locator('[data-model-attributes]')
  await expect(attributes).toContainText('business_code')
  await expect(attributes).toContainText('B-1')
  await expect(attributes).not.toContainText('业务编码')
})

test('the backfill command is closed and reports bounded counts', async ({ page }) => {
  const fixture = await setup(page, { route: 'inventory' })
  await page.getByRole('button', { name: '回填模型版本' }).click()
  const drawer = page.getByRole('dialog', { name: '回填实体模型版本' })
  await expect(drawer).toBeVisible()
  await drawer.getByRole('combobox', { name: '模型版本' }).selectOption('builtin.host@1')
  await drawer.getByLabel('本次处理上限（1–500）').fill('25')
  await drawer.getByRole('button', { name: '开始回填' }).click()
  await expect(drawer.locator('[data-backfill-receipt]')).toContainText('回填完成')
  await expect(drawer.locator('[data-backfill-receipt]')).toContainText('核对 3 条')
  await expect(drawer.locator('[data-backfill-receipt]')).toContainText('写入版本 2 条')
  await expect(drawer.locator('[data-backfill-receipt]')).toContainText('保持无版本 1 条')
  const command = fixture.backfillPosts[0]!
  expect(Object.keys(command).sort()).toEqual(['entityType', 'maxUpdates', 'model', 'requestId'])
  expect(command.entityType).toBe('Host')
  expect(command.model).toEqual({ id: 'builtin.host', revision: 1 })
  expect(command.maxUpdates).toBe(25)
  expect(command.requestId).toMatch(/^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/)
  expect(JSON.stringify(command)).not.toContain('tenantId')
  expect(JSON.stringify(command)).not.toContain('actor')
})

test('an unknown backfill outcome keeps the original command for an identical retry', async ({ page }) => {
  const fixture = await setup(page, { route: 'inventory' })
  fixture.control.failNextBackfill = true
  await page.getByRole('button', { name: '回填模型版本' }).click()
  const drawer = page.getByRole('dialog', { name: '回填实体模型版本' })
  await drawer.getByRole('combobox', { name: '模型版本' }).selectOption('builtin.host@1')
  await drawer.getByRole('button', { name: '开始回填' }).click()
  await expect(drawer.getByRole('status').filter({ hasText: '回填结果未知' })).toBeVisible()
  await drawer.getByRole('button', { name: '查询原请求结果（原样重试）' }).click()
  await expect(drawer.locator('[data-backfill-receipt]')).toContainText('已按原请求读取回填回执')
  expect(fixture.backfillPosts).toHaveLength(2)
  expect(fixture.backfillPosts[1]).toEqual(fixture.backfillPosts[0])
})

test('identity switch clears backfill drawer state across reopen', async ({ page }) => {
  const fixture = await setup(page, { route: 'inventory' })
  await page.getByRole('button', { name: '回填模型版本' }).click()
  const drawer = page.getByRole('dialog', { name: '回填实体模型版本' })
  await drawer.getByRole('combobox', { name: '模型版本' }).selectOption('builtin.host@1')
  await drawer.getByLabel('本次处理上限（1–500）').fill('25')
  await drawer.getByRole('button', { name: '关闭回填模型版本' }).click()
  await expect(drawer).toHaveCount(0)
  await page.getByRole('textbox', { name: '平台开发 Token（仅保存在当前标签页内存）' }).fill(secondToken)
  await page.getByRole('button', { name: '回填模型版本' }).click()
  await expect(drawer).toBeVisible()
  await expect(drawer.getByLabel('本次处理上限（1–500）')).toHaveValue('100')
  expect(fixture.backfillPosts).toHaveLength(0)
})

test('a session-clearing answer closes the drawer instead of keeping private state', async ({ page }) => {
  await setup(page, { route: 'inventory' })
  await page.route('**/api/v1/entities/model-pins/backfill**', route => route.request().method() === 'GET'
    ? route.fulfill({ status: 404, json: { error: 'NOT_FOUND' } })
    : route.fulfill({ status: 401, json: { error: 'UNAUTHENTICATED' } }))
  await page.getByRole('button', { name: '回填模型版本' }).click()
  const drawer = page.getByRole('dialog', { name: '回填实体模型版本' })
  await drawer.getByRole('combobox', { name: '模型版本' }).selectOption('builtin.host@1')
  await drawer.getByRole('button', { name: '开始回填' }).click()
  await expect(drawer).toHaveCount(0)
  await expect(page.getByText('等待配置凭据')).toBeVisible()
})
