import { expect, test, type Page } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { TOKEN_OK } from './helpers.ts'
const sample = (name: string) => JSON.parse(readFileSync(new URL(`../../../contracts/examples/${name}.json`, import.meta.url), 'utf8'))
const entity = sample('entity')
function response(url: string, count = 1) {
  const q = new URL(url).searchParams
  const query = { from: Number(q.get('from')), till: Number(q.get('till')), asOf: q.get('asOf') ?? new Date(Number(q.get('till')) * 1000).toISOString(),
    source: q.get('source') ?? '', after: q.get('after'), limit: 25 }
  const items = Array.from({ length: count }, (_, i) => ({ ...sample('observation'), id: `observation-${String(i + (query.after ? 26 : 1)).padStart(3, '0')}`,
    entityId: entity.id, tenantId: entity.tenantId, observedAt: new Date((query.till - 10) * 1000).toISOString(), ingestedAt: query.asOf,
    sourceInstanceId: query.source || 'zabbix-1', fields: { ...sample('observation').fields, entityName: '<img src=x onerror=alert(1)>fixture' } }))
  return { ...sample('observation-page'), entityId: entity.id, tenantId: entity.tenantId, query, items, nextCursor: count === 25 ? items.at(-1)!.id : null }
}
async function open(page: Page, handler: (url: string) => unknown | Promise<unknown>) {
  await page.route('**/api/v1/**', async route => {
    const url = route.request().url()
    if (new URL(url).pathname.endsWith('/observations')) return route.fulfill(await handler(url) as Parameters<typeof route.fulfill>[0])
    return route.fulfill({ json: url.includes('/entities/page?') ? { ...sample('entity-page'), items: [entity], query: { q: '', type: 'host', lifecycle: '', after: null, limit: 25 }, nextCursor: null } : entity })
  })
  await page.goto('/#/inventory'); await page.getByRole('textbox', { name: '平台开发 Token（仅保存在当前标签页内存）' }).fill(TOKEN_OK)
  await page.getByRole('button', { name: '刷新列表' }).click(); await page.getByRole('button', { name: '查看详情' }).click()
}
test('retained history preserves source times and cutoff while paging and shows fields as text', async ({ page }) => {
  const requests: URLSearchParams[] = []
  await open(page, url => { requests.push(new URL(url).searchParams); return { json: response(url, requests.length === 1 ? 25 : 1) } })
  await page.getByRole('button', { name: '读取观测历史', exact: true }).click()
  await expect(page.locator('[data-observation-record]')).toHaveCount(25); await expect(page.locator('[data-observation-record] img')).toHaveCount(0)
  const cutoff = (await page.locator('[data-observation-page]').textContent())!.split('截止 ')[1]
  await page.getByRole('button', { name: '下一页观测', exact: true }).click(); await expect(page.locator('[data-observation-record]')).toHaveCount(1)
  expect(requests[1]!.get('asOf')).toBe(cutoff); expect(requests[1]!.get('after')).toBe('observation-025'); expect(requests[1]!.get('till')).toBe(requests[0]!.get('till'))
  await page.getByRole('textbox', { name: '来源实例筛选' }).fill('zabbix-2'); await expect(page.locator('[data-observation-record]')).toHaveCount(0)
  await page.getByRole('button', { name: '读取观测历史', exact: true }).click(); await expect(page.locator('[data-observation-record]')).toHaveCount(1)
  expect(requests[2]!.get('source')).toBe('zabbix-2'); expect(requests[2]!.get('after')).toBeNull()
})
for (const mode of ['tenant', 'entity', 'future-nanosecond', 'cursor']) test(`rejects ${mode} history without rendering records`, async ({ page }) => {
  await open(page, url => {
    const value = response(url)
    if (mode === 'tenant') value.items[0]!.tenantId = 'foreign'
    if (mode === 'entity') value.items[0]!.entityId = '00000000-0000-0000-0000-000000000000'
    if (mode === 'future-nanosecond') value.items[0]!.ingestedAt = value.query.asOf.replace('.000Z', '.000000001Z')
    if (mode === 'cursor') value.nextCursor = '../raw'
    return { json: value }
  })
  await page.getByRole('button', { name: '读取观测历史', exact: true }).click()
  await expect(page.locator('[data-observation-error]')).toContainText('范围不正确'); await expect(page.locator('[data-observation-record]')).toHaveCount(0)
})
test('revocation clears the entity and all retained history', async ({ page }) => {
  let calls = 0
  await open(page, url => ++calls === 1 ? { json: response(url) } : { status: 403, json: { error: 'FORBIDDEN' } })
  await page.getByRole('button', { name: '读取观测历史', exact: true }).click(); await expect(page.locator('[data-observation-record]')).toHaveCount(1)
  await page.getByRole('button', { name: '读取观测历史', exact: true }).click(); await expect(page.locator('[data-entity-detail]')).toHaveCount(0)
  await expect(page.getByRole('table')).toHaveCount(0)
})
test('a delayed history response cannot restore data after logout', async ({ page }) => {
  let release!: () => void; let started!: () => void
  const pending = new Promise<void>(resolve => { release = resolve }), dispatched = new Promise<void>(resolve => { started = resolve })
  await open(page, async url => { started(); await pending; return { json: response(url) } })
  await page.getByRole('button', { name: '读取观测历史', exact: true }).click(); await dispatched
  await page.getByRole('button', { name: '清除开发会话', exact: true }).click(); release()
  await expect(page.locator('[data-entity-detail]')).toHaveCount(0); await expect(page.locator('[data-observation-record]')).toHaveCount(0)
  await expect(page.getByRole('button', { name: '刷新列表' })).toBeDisabled()
})
test('legacy precision and missing provenance remain explicit', async ({ page }) => {
  await open(page, url => { const value = response(url); value.items[0]!.timePrecision = 'legacy-microseconds'; value.items[0]!.gaps = ['PROJECTION_FIELDS_UNAVAILABLE', 'SOURCE_MODE_UNAVAILABLE']; value.items[0]!.fields = {}; return { json: value } })
  await page.getByRole('button', { name: '读取观测历史', exact: true }).click()
  await expect(page.locator('[data-observation-record]')).toContainText('微秒精度'); await expect(page.locator('[data-observation-record]')).toContainText('历史名称未记录')
  await expect(page.locator('[data-observation-record]')).toContainText('SOURCE_MODE_UNAVAILABLE')
})
