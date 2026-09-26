import { expect, test, type Page } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { TOKEN_OK, diagnosePayload } from './helpers.ts'

const sample = (name: string) => JSON.parse(readFileSync(new URL(`../../../contracts/examples/${name}.json`, import.meta.url), 'utf8'))
const tokenBox = (page: Page) => page.getByRole('textbox', { name: '平台开发 Token（仅保存在当前标签页内存）' })
test('shares only the in-memory platform credential across routes and clears drafts on logout', async ({ page }) => {
  const auth: string[] = []
  await page.route('**/api/v1/**', route => {
    auth.push(route.request().headers().authorization ?? '')
    if (route.request().url().includes('/pipeline/drafts')) return route.fulfill({ json: sample('pipeline-draft-list') })
    return route.fulfill({ json: { ...sample('entity-page'), query: { q: '', type: 'host', lifecycle: '', after: null, limit: 25 } } })
  })
  await page.goto('/#/inventory'); await tokenBox(page).fill(TOKEN_OK)
  await page.getByRole('button', { name: '刷新列表' }).click(); await expect(page.getByRole('table')).toBeVisible()
  await page.getByRole('link', { name: '接入流水线', exact: true }).click(); await expect(tokenBox(page)).toHaveValue(TOKEN_OK)
  await page.getByRole('button', { name: '读取最近草稿', exact: true }).click(); await expect(page.locator('[data-draft-list]')).toBeVisible()
  await page.getByRole('button', { name: '清除开发会话', exact: true }).click()
  await expect(page.locator('[data-draft-list]')).toHaveCount(0); await expect(tokenBox(page)).toHaveValue('')
  await page.getByRole('link', { name: '资产', exact: true }).click(); await expect(page.getByRole('table')).toHaveCount(0)
  expect(auth).toHaveLength(2); expect(auth.every(value => value === `Bearer ${TOKEN_OK}`)).toBe(true)
  expect(await page.evaluate(() => ({ local: localStorage.length, session: sessionStorage.length, cookie: document.cookie }))).toEqual({ local: 0, session: 0, cookie: '' })
})
test('a forbidden pipeline read clears loaded drafts and user edits throughout the page', async ({ page }) => {
  let requests = 0
  await page.route('**/api/v1/**', route => route.fulfill(++requests === 1 ? { json: sample('pipeline-draft-list') } : { status: 403, json: { error: 'FORBIDDEN' } }))
  await page.goto('/#/integrations/pipelines'); await tokenBox(page).fill(TOKEN_OK)
  await page.getByRole('button', { name: '读取最近草稿', exact: true }).click(); await expect(page.locator('[data-draft-list]')).toBeVisible()
  await page.getByRole('textbox', { name: '流水线 ID', exact: true }).fill('private-customer-edit')
  await page.getByRole('button', { name: '读取当前版本草稿', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('403'); await expect(page.locator('[data-draft-list]')).toHaveCount(0)
  await expect(page.getByRole('textbox', { name: '流水线 ID', exact: true })).toHaveValue('zabbix-host-default')
  await expect(tokenBox(page)).toHaveValue(TOKEN_OK)
})
test('an expired credential is removed on 401 and cannot be reused from another page', async ({ page }) => {
  let calls = 0; await page.route('**/api/v1/**', route => { ++calls; return route.fulfill({ status: 401, json: {} }) })
  await page.goto('/#/inventory'); await tokenBox(page).fill(TOKEN_OK); await page.getByRole('button', { name: '刷新列表' }).click()
  await expect(page.getByRole('alert')).toContainText('401'); await expect(tokenBox(page)).toHaveValue('')
  await page.getByRole('link', { name: '指标', exact: true }).click(); await expect(page.getByRole('button', { name: '读取资产和指标' })).toBeDisabled()
  expect(calls).toBe(1)
})
test('pagehide clears rendered results and credentials before a restored document can display them', async ({ page }) => {
  await page.route('**/api/v1/**', route => route.fulfill({ json: sample('pipeline-draft-list') }))
  await page.goto('/#/integrations/pipelines'); await tokenBox(page).fill(TOKEN_OK)
  await page.getByRole('button', { name: '读取最近草稿' }).click(); await expect(page.locator('[data-draft-list]')).toBeVisible()
  await page.evaluate(() => window.dispatchEvent(new PageTransitionEvent('pagehide', { persisted: true })))
  await expect(tokenBox(page)).toHaveValue(''); await expect(page.locator('[data-draft-list]')).toHaveCount(0)
  await page.evaluate(() => window.dispatchEvent(new PageTransitionEvent('pageshow', { persisted: true })))
  await expect(page.locator('[data-draft-list]')).toHaveCount(0)
})
test('platform credential is never forwarded to the isolated fixture demo', async ({ page }) => {
  let received = ''
  await page.route('**/agent/api/v1/diagnoses', route => { received = route.request().headers().authorization ?? ''; return route.fulfill({ json: diagnosePayload() }) })
  await page.goto('/#/inventory'); await tokenBox(page).fill(TOKEN_OK)
  await page.getByRole('link', { name: 'Fixture 诊断演示' }).click()
  const demoToken = page.getByRole('textbox', { name: '开发 Token（仅保存在当前页面内存）' })
  await expect(demoToken).toHaveValue(''); await expect(page.getByRole('button', { name: '运行只读诊断' })).toBeDisabled()
  await demoToken.fill(TOKEN_OK + '-fixture'); await page.getByRole('button', { name: '运行只读诊断' }).click()
  await expect(page.getByText('mock-deterministic', { exact: false })).toBeVisible(); expect(received).toBe(`Bearer ${TOKEN_OK}-fixture`)
  await page.getByRole('link', { name: '资产', exact: true }).click(); await expect(tokenBox(page)).toHaveValue(TOKEN_OK)
})
