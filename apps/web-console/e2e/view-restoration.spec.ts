import { expect, test, type Page } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { TOKEN_OK } from './helpers.ts'
const sample = (name: string) => JSON.parse(readFileSync(new URL(`../../../contracts/examples/${name}.json`, import.meta.url), 'utf8'))
const asset = sample('entity'), detail = sample('incident-detail'), h = detail.record.incident
const metric = sample('metric-series-page').metricKey as string
const tokenBox = (page: Page) => page.getByRole('textbox', { name: '平台开发 Token（仅保存在当前标签页内存）' })
const hash = (page: Page) => new URL(page.url()).hash
type Call = { path: string; query: URLSearchParams; method: string }
async function fixture(page: Page) {
  const calls: Call[] = []
  await page.route('**/api/v1/**', route => {
    const url = new URL(route.request().url()), q = url.searchParams
    calls.push({ path: url.pathname, query: q, method: route.request().method() })
    if (url.pathname === '/api/v1/entities/page') return route.fulfill({ json: { storage: 'postgres', query: { q: q.get('q'), type: q.get('type'), lifecycle: q.get('lifecycle'), after: q.get('after'), limit: 25 }, items: [asset], nextCursor: null } })
    if (url.pathname === `/api/v1/entities/${asset.id}`) return route.fulfill({ json: asset })
    if (url.pathname === '/api/v1/incidents') return route.fulfill({ json: { storage: 'postgres', query: { status: q.get('status'), after: q.get('after'), limit: 25 }, items: [{ ...h, status: q.get('status') || 'OPEN' }], nextCursor: null } })
    if (url.pathname === `/api/v1/incidents/${h.id}`) return route.fulfill({ json: detail })
    if (url.pathname === '/api/v1/metrics/definitions') return route.fulfill({ json: { items: [{ metricKey: 'host.other', displayName: 'Other', unit: '1' }, { metricKey: metric, displayName: 'CPU', unit: '1' }] } })
    if (url.pathname.endsWith('/series')) return route.fulfill({ json: { entityId: asset.id, metricKey: decodeURIComponent(url.pathname.split('/').at(-2)!), unit: '1', from: Number(q.get('from')), till: Number(q.get('till')), series: [], status: { kind: 'NO_DATA', fresh: false, lastPointAt: null, partial: false } } })
    return route.fulfill({ status: 404, json: {} })
  })
  return calls
}
test('refresh restores applied asset filters and selected detail while requiring new reads and a credential', async ({ page }) => {
  const calls = await fixture(page)
  await page.goto('/#/inventory'); await tokenBox(page).fill(TOKEN_OK)
  await page.getByRole('textbox', { name: '名称或 IP' }).fill('db + 主机')
  await page.getByRole('combobox', { name: '生命周期' }).selectOption('ACTIVE')
  await page.getByRole('button', { name: '刷新列表', exact: true }).click(); await page.getByRole('button', { name: '查看详情', exact: true }).click()
  await expect(page.locator('[data-entity-detail]')).toBeVisible(); expect(new URLSearchParams(hash(page).split('?')[1]).get('entityId')).toBe(asset.id)
  const before = calls.length; await page.reload()
  await expect(page.getByRole('textbox', { name: '名称或 IP' })).toHaveValue('db + 主机'); await expect(page.getByRole('combobox', { name: '生命周期' })).toHaveValue('ACTIVE')
  await expect(tokenBox(page)).toHaveValue(''); await expect(page.locator('[data-entity-detail]')).toHaveCount(0)
  expect(calls).toHaveLength(before); await tokenBox(page).fill(TOKEN_OK)
  await expect(page.getByRole('textbox', { name: '名称或 IP' })).toHaveValue('db + 主机')
  await page.getByRole('button', { name: '读取选中资产', exact: true }).click(); await expect(page.locator('[data-entity-detail]')).toBeVisible()
  expect(calls.at(-1)!.path).toBe(`/api/v1/entities/${asset.id}`); expect(calls.every(c => c.method === 'GET')).toBe(true)
})
test('back and forward restore same-page filters once and discard old results without fetching', async ({ page }) => {
  const calls = await fixture(page)
  await page.goto('/#/inventory'); await tokenBox(page).fill(TOKEN_OK)
  for (const value of ['first', 'second']) {
    await page.getByRole('textbox', { name: '名称或 IP' }).fill(value); await page.getByRole('button', { name: '刷新列表', exact: true }).click(); await expect(page.getByRole('table')).toBeVisible()
  }
  await page.goBack(); await expect(page.getByRole('textbox', { name: '名称或 IP' })).toHaveValue('first'); await expect(page.getByRole('table')).toHaveCount(0)
  expect(calls).toHaveLength(2); await page.goForward(); await expect(page.getByRole('textbox', { name: '名称或 IP' })).toHaveValue('second'); expect(calls).toHaveLength(2)
  await page.getByRole('link', { name: '指标', exact: true }).click(); await page.goBack()
  await expect(page.getByRole('textbox', { name: '名称或 IP' })).toHaveValue('second'); await expect(page.getByRole('table')).toHaveCount(0)
})
test('a restored asset cursor is passed to the bounded API after refresh', async ({ page }) => {
  await fixture(page)
  const rows = Array.from({ length: 26 }, (_, i) => ({ ...asset, id: `${String(i + 1).padStart(8, '0')}-0000-0000-0000-000000000001` }))
  const cursors: (string | null)[] = []
  await page.route('**/api/v1/entities/page?**', route => {
    const q = new URL(route.request().url()).searchParams, after = q.get('after'); cursors.push(after)
    const items = after ? rows.slice(25) : rows.slice(0, 25)
    return route.fulfill({ json: { storage: 'postgres', query: { q: q.get('q'), type: q.get('type'), lifecycle: q.get('lifecycle'), after, limit: 25 }, items, nextCursor: after ? null : items.at(-1)!.id } })
  })
  await page.goto('/#/inventory'); await tokenBox(page).fill(TOKEN_OK); await page.getByRole('button', { name: '刷新列表', exact: true }).click()
  await page.getByRole('button', { name: '下一页资产', exact: true }).click(); await expect(page.locator('[data-inventory-page]')).toContainText('本页 1 条')
  await page.reload(); await tokenBox(page).fill(TOKEN_OK); await page.getByRole('button', { name: '刷新列表', exact: true }).click()
  await expect(page.locator('[data-inventory-page]')).toContainText('恢复的游标页'); expect(cursors.at(-1)).toBe(rows[24]!.id)
  await page.getByRole('button', { name: '重置资产筛选' }).click(); await page.getByRole('button', { name: '刷新列表', exact: true }).click()
  await expect(page.locator('[data-inventory-page]')).toContainText('本页 25 条'); expect(cursors.at(-1)).toBeNull()
})
test('Incident link restores state and selection without restoring a snapshot or triggering an operation', async ({ page }) => {
  const calls = await fixture(page)
  await page.goto(`/#/incidents?status=INVESTIGATING&incidentId=${h.id}`); await tokenBox(page).fill(TOKEN_OK)
  await expect(page.getByRole('combobox', { name: 'Incident 状态' })).toHaveValue('INVESTIGATING'); expect(calls).toHaveLength(0)
  await page.getByRole('button', { name: '读取选中 Incident' }).click(); await expect(page.locator('[data-incident-detail]')).toBeVisible()
  await page.reload(); await expect(page.locator('[data-incident-detail]')).toHaveCount(0); expect(calls).toHaveLength(1)
  await tokenBox(page).fill(TOKEN_OK); await page.getByRole('button', { name: '读取选中 Incident' }).click(); await expect(page.locator('[data-incident-detail]')).toBeVisible()
  expect(calls.every(c => c.method === 'GET')).toBe(true)
})
test('a metric query restores the same resource metric and exact window after reload', async ({ page }) => {
  const calls = await fixture(page)
  await page.goto('/#/metrics'); await tokenBox(page).fill(TOKEN_OK); await page.getByRole('button', { name: '读取资产和指标' }).click()
  await page.getByRole('combobox', { name: 'Metric', exact: true }).selectOption(metric); await page.getByRole('button', { name: 'Last 15m', exact: true }).click()
  await page.getByRole('button', { name: '查询', exact: true }).click(); await expect(page.locator('[data-state="no-data"]')).toBeVisible()
  const first = calls.filter(c => c.path.endsWith('/series')).at(-1)!, query = new URLSearchParams(hash(page).split('?')[1])
  expect(query.get('entityId')).toBe(asset.id); expect(query.get('metricKey')).toBe(metric); expect(query.get('from')).toBe(first.query.get('from'))
  const before = calls.length; await page.reload(); await expect(page.locator('[data-state="no-data"]')).toHaveCount(0); expect(calls).toHaveLength(before)
  await tokenBox(page).fill(TOKEN_OK); await page.getByRole('button', { name: '读取资产和指标' }).click()
  await expect(page.getByRole('combobox', { name: 'Host', exact: true })).toHaveValue(asset.id); await expect(page.getByRole('combobox', { name: 'Metric', exact: true })).toHaveValue(metric)
  expect(calls.slice(before).some(c => c.path === `/api/v1/entities/${asset.id}`)).toBe(true)
  await page.getByRole('button', { name: '查询', exact: true }).click(); await expect(page.locator('[data-state="no-data"]')).toBeVisible()
  const restored = calls.filter(c => c.path.endsWith('/series')).at(-1)!; expect([...restored.query]).toEqual([...first.query])
})
test('a missing selected metric fails explicitly without choosing a different metric', async ({ page }) => {
  const calls = await fixture(page)
  await page.goto(`/#/metrics?entityId=${asset.id}&metricKey=deleted.metric&from=1&till=60`); await tokenBox(page).fill(TOKEN_OK)
  await page.getByRole('button', { name: '读取资产和指标' }).click(); await expect(page.getByRole('alert')).toContainText('选中的指标已不可用')
  await expect(page.getByRole('button', { name: '查询', exact: true })).toHaveCount(0); expect(calls.some(c => c.path.endsWith('/series'))).toBe(false)
})
for (const [route, action, reset] of [['inventory?q=private&tenantId=other', '刷新列表', '重置资产筛选'], ['incidents?status=OPEN&status=CLOSED', '刷新 Incident', '重置 Incident 筛选'],
  ['metrics?entityId=bad&from=1&till=2', '读取资产和指标', '重置指标筛选']] as const) test(`invalid ${route.split('?')[0]} selection blocks reads until explicit reset`, async ({ page }) => {
  const calls = await fixture(page); await page.goto('/#/' + route); await tokenBox(page).fill(TOKEN_OK)
  await expect(page.getByRole('alert')).not.toBeEmpty(); await expect(page.getByRole('button', { name: action, exact: true })).toBeDisabled(); expect(calls).toHaveLength(0)
  await page.getByRole('button', { name: reset, exact: true }).click(); await expect(page.getByRole('alert')).toBeEmpty()
  expect(hash(page)).toBe('#/' + route.split('?')[0]); await expect(page.getByRole('button', { name: action, exact: true })).toBeEnabled()
})
test('changed URL selection rejects a late response and logout removes current selection but keeps no credentials in history state', async ({ page }) => {
  const calls = await fixture(page); let release!: () => void, arrived!: () => void
  const pending = new Promise<void>(resolve => { release = resolve }), started = new Promise<void>(resolve => { arrived = resolve })
  await page.route('**/api/v1/entities/page?**', async route => {
    arrived(); await pending; const q = new URL(route.request().url()).searchParams
    return route.fulfill({ json: { storage: 'postgres', query: { q: q.get('q'), type: 'host', lifecycle: '', after: null, limit: 25 }, items: [asset], nextCursor: null } }).catch(() => {})
  })
  await page.goto('/#/inventory?q=first'); await tokenBox(page).fill(TOKEN_OK); await page.getByRole('button', { name: '刷新列表', exact: true }).click(); await started
  await page.evaluate(() => { window.location.hash = '/inventory?q=second' }); release()
  await expect(page.getByRole('textbox', { name: '名称或 IP' })).toHaveValue('second'); await expect(page.getByRole('table')).toHaveCount(0)
  await page.getByRole('button', { name: '清除开发会话' }).click(); expect(hash(page)).toBe('#/inventory'); await expect(tokenBox(page)).toHaveValue('')
  expect(await page.evaluate(() => ({ local: localStorage.length, session: sessionStorage.length, cookie: document.cookie, state: history.state }))).toEqual({ local: 0, session: 0, cookie: '', state: null })
  expect(page.url()).not.toContain(TOKEN_OK); expect(calls.every(c => c.method === 'GET')).toBe(true)
})
for (const [route, query] of [['inventory', 'q=private&lifecycle=ACTIVE'], ['incidents', 'status=INVESTIGATING'],
  ['metrics', `entityId=${asset.id}&metricKey=${metric}&from=1&till=60`]] as const) test(`credential replacement clears ${route} selection and invalid later navigation never revives previous state`, async ({ page }) => {
  const calls = await fixture(page)
  await page.goto(`/#/${route}?${query}`); await tokenBox(page).pressSequentially(TOKEN_OK)
  expect(hash(page)).toBe(`#/${route}?${query}`)
  const action = route === 'inventory' ? '刷新列表' : route === 'incidents' ? '刷新 Incident' : '读取资产和指标'
  await page.getByRole('button', { name: action, exact: true }).click()
  await expect(page.getByRole('button', { name: action, exact: true })).toBeEnabled()
  expect(calls.length).toBeGreaterThan(0); const before = calls.length
  await tokenBox(page).fill(TOKEN_OK + '-replacement'); await expect(page).toHaveURL(new RegExp(`#/${route}$`))
  await page.evaluate(value => { window.location.hash = value }, `/${route}?${query}`)
  await expect.poll(() => hash(page)).toContain(query)
  await page.evaluate(value => { window.location.hash = value }, `/${route}?unknown=untrusted`)
  await expect(page.getByRole('alert')).not.toBeEmpty()
  if (route === 'inventory') await expect(page.getByRole('textbox', { name: '名称或 IP' })).toHaveValue('')
  if (route === 'incidents') await expect(page.getByRole('combobox', { name: 'Incident 状态' })).toHaveValue('')
  if (route === 'metrics') await expect(page.locator('[data-metric-window]')).toHaveCount(0)
  expect(calls).toHaveLength(before)
})
