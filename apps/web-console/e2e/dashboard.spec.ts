import { expect, test, type Page, type Route } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { TOKEN_OK } from './helpers.ts'

// Explicit browser HTTP fixtures. No production overview or external source is simulated as live.
const asset = JSON.parse(readFileSync(new URL('../../../contracts/examples/entity-page.json', import.meta.url), 'utf8')).items[0]
const incident = JSON.parse(readFileSync(new URL('../../../contracts/examples/incident-detail.json', import.meta.url), 'utf8')).record.incident
const token = (page: Page) => page.getByRole('textbox', { name: '平台开发 Token（仅保存在当前标签页内存）' })
function payload(route: Route, kind: 'assets' | 'incidents', many = false) {
  const query = new URL(route.request().url()).searchParams
  const items = kind === 'assets' ? Array.from({ length: many ? 25 : 1 }, (_, index) => ({ ...asset,
    id: String(index + 1).padStart(8, '0') + '-1111-1111-1111-111111111111',
    attributes: { ...asset.attributes, dataMode: 'labeled-fixture' },
  })) : [incident]
  return { storage: 'memory', query: kind === 'assets' ? { q: query.get('q'), type: query.get('type'), lifecycle: query.get('lifecycle'), after: query.get('after'), limit: 25 }
    : { status: query.get('status'), after: query.get('after'), limit: 25 }, items, nextCursor: many && kind === 'assets' ? items.at(-1).id : null }
}

test('overview never fetches automatically and guide tabs support keyboard navigation', async ({ page }) => {
  const calls: string[] = []
  page.on('request', request => { if (request.url().includes('/api/')) calls.push(request.url()) })
  await page.goto('/#/start')
  await expect(page.getByRole('heading', { name: '运维工作台', exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '读取概览', exact: true })).toBeDisabled()
  await expect(page.locator('[data-overview-stat]')).toHaveText(['—', '—', '—', '—'])
  await page.getByRole('tab', { name: '运维概览' }).focus()
  await page.keyboard.press('ArrowRight')
  await expect(page.getByRole('tab', { name: '接入指南' })).toHaveAttribute('aria-selected', 'true')
  await expect(page.getByRole('heading', { name: '建立你的数据链路' })).toBeVisible()
  await token(page).fill(TOKEN_OK)
  await page.keyboard.press('Tab')
  expect(calls).toEqual([])
})

test('explicit overview requests are bounded, expose pagination and clear when the session changes', async ({ page }) => {
  const calls: { method: string; url: string; auth: string }[] = []
  await page.route('**/api/v1/**', async route => {
    calls.push({ method: route.request().method(), url: route.request().url(), auth: route.request().headers().authorization ?? '' })
    return route.fulfill({ json: payload(route, route.request().url().includes('/entities/page') ? 'assets' : 'incidents', true) })
  })
  await page.goto('/#/start')
  await token(page).fill(TOKEN_OK)
  await page.getByRole('button', { name: '读取概览', exact: true }).click()
  await expect(page.locator('[data-overview-stat]')).toHaveText(['25', '25', '1', '1'])
  await expect(page.getByText('开发内存 · 还有后续页 · 含 Fixture 资产')).toBeVisible()
  await page.getByRole('link', { name: incident.title, exact: true }).click()
  await expect(page).toHaveURL(new RegExp('incidentId=' + incident.id))
  await expect(page.getByRole('button', { name: '读取选中 Incident' })).toBeVisible()
  await page.getByRole('link', { name: '观织 OpsWeave 首页' }).click()
  await page.getByRole('button', { name: '读取概览', exact: true }).click()
  await expect(page.locator('[data-overview-stat="inventory"]')).toHaveText('25')
  await page.getByRole('button', { name: '清除开发会话' }).click()
  await expect(page.locator('[data-overview-stat]')).toHaveText(['—', '—', '—', '—'])
  await expect(page.getByRole('link', { name: incident.title, exact: true })).toHaveCount(0)
  expect(calls).toHaveLength(4)
  expect(calls.every(call => call.method === 'GET' && call.auth === 'Bearer ' + TOKEN_OK && !call.url.includes('tenant') && new URL(call.url).searchParams.get('limit') === '25')).toBe(true)
})

test('a partial HTTP failure stays unknown and does not silently become an empty success', async ({ page }) => {
  await page.route('**/api/v1/**', route => route.request().url().includes('/entities/page')
    ? route.fulfill({ status: 503, json: { error: 'source_unavailable' } })
    : route.fulfill({ json: payload(route, 'incidents') }))
  await page.goto('/#/start')
  await token(page).fill(TOKEN_OK)
  await page.getByRole('button', { name: '读取概览', exact: true }).click()
  await expect(page.locator('[data-overview-stat]')).toHaveText(['—', '—', '1', '1'])
  await expect(page.getByRole('alert')).toContainText('HTTP 503')
  await expect(page.getByText('当前查询页没有资产')).toHaveCount(0)
})

test('denied overview removes previous data and displays the authorization failure', async ({ page }) => {
  let denied = false
  await page.route('**/api/v1/**', route => denied ? route.fulfill({ status: 403, json: { error: 'forbidden' } })
    : route.fulfill({ json: payload(route, route.request().url().includes('/entities/page') ? 'assets' : 'incidents') }))
  await page.goto('/#/start')
  await token(page).fill(TOKEN_OK)
  await page.getByRole('button', { name: '读取概览', exact: true }).click()
  await expect(page.locator('[data-overview-stat="inventory"]')).toHaveText('1')
  denied = true
  await page.getByRole('button', { name: '读取概览', exact: true }).click()
  await expect(page.locator('[data-overview-stat]')).toHaveText(['—', '—', '—', '—'])
  await expect(page.getByRole('alert')).toContainText('HTTP 403')
  await expect(page.getByRole('link', { name: incident.title, exact: true })).toHaveCount(0)
})

test('clearing the session cancels pending overview and late responses cannot restore data', async ({ page }) => {
  let release!: () => void
  const gate = new Promise<void>(resolve => { release = resolve })
  await page.route('**/api/v1/**', async route => { await gate; await route.fulfill({ json: payload(route, route.request().url().includes('/entities/page') ? 'assets' : 'incidents') }).catch(() => {}) })
  await page.goto('/#/start')
  await token(page).fill(TOKEN_OK)
  await page.getByRole('button', { name: '读取概览', exact: true }).click()
  await expect(page.getByRole('button', { name: '正在读取…', exact: true })).toBeDisabled()
  await page.getByRole('button', { name: '清除开发会话' }).click()
  release()
  await expect(page.getByRole('button', { name: '读取概览', exact: true })).toBeDisabled()
  await expect(page.locator('[data-overview-stat]')).toHaveText(['—', '—', '—', '—'])
  await expect(page.getByRole('link', { name: incident.title, exact: true })).toHaveCount(0)
})

for (const width of [1440, 390]) {
  test('overview cards and loaded tables remain within the viewport at ' + width, async ({ page }) => {
    await page.setViewportSize({ width, height: 844 })
    await page.route('**/api/v1/**', route => route.fulfill({ json: payload(route, route.request().url().includes('/entities/page') ? 'assets' : 'incidents') }))
    await page.goto('/#/start')
    await token(page).fill(TOKEN_OK)
    await page.getByRole('button', { name: '读取概览', exact: true }).click()
    await expect(page.getByRole('link', { name: incident.title, exact: true })).toBeVisible()
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    await page.getByRole('button', { name: '切换到深色模式' }).click()
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark')
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  })
}
