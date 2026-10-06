import { test, expect } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { TOKEN_OK } from './helpers.ts'

const bundle = JSON.parse(readFileSync(new URL('../../../contracts/catalog/opsweave-core-1.0.0.json', import.meta.url), 'utf8'))
const result = { schemaVersion: '1.0', storage: 'memory', package: bundle, published: { items: [], truncated: false }, drafts: { items: [], truncated: false } }

for (const width of [1440, 1024, 390]) test('metric list loads once after authorization and keeps filtering local at ' + width, async ({ page }) => {
  await page.setViewportSize({ width, height: 1000 })
  const calls: string[] = []
  await page.route('**/api/v1/catalog', async route => { calls.push(route.request().method()); await route.fulfill({ json: result }) })
  await page.goto('/#/modeling/metrics')
  await expect(page.getByRole('button', { name: '刷新指标目录' })).toBeDisabled()
  expect(calls).toEqual([])
  await page.getByRole('textbox', { name: '平台开发 Token（仅保存在当前标签页内存）' }).fill(TOKEN_OK)
  const table = page.getByRole('table', { name: '内置指标列表' })
  await expect(table.locator('tbody tr')).toHaveCount(3)
  await expect(page.locator('article.model-metric')).toHaveCount(0)
  await expect(page.getByRole('button', { name: '接入维护', exact: true })).toHaveCount(0)
  for (const dark of [false, true]) {
    if (dark) await page.getByRole('button', { name: '切换到深色模式' }).click()
    await page.getByRole('textbox', { name: '搜索指标定义' }).fill('system.uptime')
    await expect(table.locator('tbody tr')).toHaveCount(1)
    await expect(table).toContainText('host.system.uptime')
    await page.getByRole('button', { name: '清除指标定义搜索' }).click()
    await expect(table.locator('tbody tr')).toHaveCount(3)
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  }
  expect(calls).toEqual(['GET'])
  await page.getByRole('button', { name: '刷新指标目录' }).click()
  await expect(table.locator('tbody tr')).toHaveCount(3)
  expect(calls).toEqual(['GET', 'GET'])
})

test('failed initial list read requires an explicit retry, even after view changes', async ({ page }) => {
  const calls: string[] = []
  await page.route('**/api/v1/catalog', async route => {
    calls.push(route.request().method())
    await route.fulfill(calls.length === 1 ? { status: 503, json: { error: 'unavailable' } } : { json: result })
  })
  await page.goto('/#/modeling/metrics')
  await page.getByRole('textbox', { name: '平台开发 Token（仅保存在当前标签页内存）' }).fill(TOKEN_OK)
  await expect(page.getByRole('alert')).toContainText('503')
  await page.getByRole('button', { name: '切换到深色模式' }).click()
  await page.setViewportSize({ width: 390, height: 1000 })
  expect(calls).toEqual(['GET'])
  await expect(page.getByRole('table', { name: '内置指标列表' })).toHaveCount(0)
  await page.getByRole('button', { name: '刷新指标目录' }).click()
  await expect(page.getByRole('table', { name: '内置指标列表' }).locator('tbody tr')).toHaveCount(3)
  expect(calls).toEqual(['GET', 'GET'])
})

test('an empty authorized catalog stays empty and names are rendered as text', async ({ page }) => {
  let reads = 0
  const fixture = structuredClone(result); fixture.package.metrics = []
  await page.route('**/api/v1/catalog', async route => { reads++; await route.fulfill({ json: fixture }) })
  await page.goto('/#/modeling/metrics')
  await page.getByRole('textbox', { name: '平台开发 Token（仅保存在当前标签页内存）' }).fill(TOKEN_OK)
  await expect(page.getByText('没有匹配的指标定义。', { exact: true })).toBeVisible()
  expect(reads).toBe(1)
  fixture.package.metrics = structuredClone(bundle.metrics)
  fixture.package.metrics[0].label = '<img src=x onerror="fixtureMetricInjection=true">'
  await page.getByRole('button', { name: '刷新指标目录' }).click()
  await expect(page.getByRole('table', { name: '内置指标列表' })).toContainText(fixture.package.metrics[0].label)
  expect(await page.evaluate(() => Object.hasOwn(window, 'fixtureMetricInjection'))).toBe(false)
})

test('a forbidden initial read cannot start an automatic retry loop', async ({ page }) => {
  const calls: string[] = []
  await page.route('**/api/v1/catalog', async route => { calls.push(route.request().method()); await route.fulfill({ status: 403, json: { error: 'FORBIDDEN' } }) })
  await page.goto('/#/modeling/metrics')
  await page.getByRole('textbox', { name: '平台开发 Token（仅保存在当前标签页内存）' }).fill(TOKEN_OK)
  await expect(page.getByRole('alert')).toContainText('当前身份没有模型目录权限')
  await page.getByRole('button', { name: '切换到深色模式' }).click()
  await page.setViewportSize({ width: 390, height: 1000 })
  expect(calls).toEqual(['GET'])
  await expect(page.getByRole('table', { name: '内置指标列表' })).toHaveCount(0)
  await page.getByRole('button', { name: '刷新指标目录' }).click()
  await expect.poll(() => calls.length).toBe(2)
  await expect(page.getByRole('button', { name: '刷新指标目录' })).toBeEnabled()
  expect(calls).toEqual(['GET', 'GET'])
})
