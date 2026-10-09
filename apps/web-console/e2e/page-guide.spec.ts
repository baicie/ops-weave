import { expect, test, type Page } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { mockEmptySourceInstances } from './helpers.ts'

test.beforeEach(async ({ page }) => {
  await mockEmptySourceInstances(page)
  const sourcePage = JSON.parse(readFileSync(new URL('../../../contracts/examples/source-center-page.json', import.meta.url), 'utf8'))
  await page.route('**/api/v1/integrations/sources', route => route.fulfill({ json: { ...sourcePage, setups: { items: [], truncated: false } } }))
})

const guide = (page: Page) => page.locator('.page-pane:not([hidden]) .page-guide')

test('guide stays collapsed until asked and then shows steps with related pages', async ({ page }) => {
  await page.goto('/#/integrations/sources')
  const panel = guide(page)
  const summary = panel.locator('summary')
  await expect(summary).toBeVisible()
  await expect(summary).toContainText('页面指引')
  await expect(panel.locator('.page-guide-body')).toBeHidden()
  const collapsed = (await summary.boundingBox())!
  expect(collapsed.height).toBeLessThan(44)
  await summary.click()
  await expect(panel.locator('.page-guide-body')).toBeVisible()
  await expect(panel).toContainText('新接入从这里开始')
  await expect(panel).toContainText('确认配置不会自动采集')
  const related = panel.getByRole('navigation', { name: '相关页面' })
  await expect(related.getByRole('link', { name: '数据工作流 →' })).toBeVisible()
  await expect(related.getByRole('link', { name: '实体模型 →' })).toBeVisible()
  await summary.click()
  await expect(panel.locator('.page-guide-body')).toBeHidden()
})

test('expanded guide stays inside a narrow viewport', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/#/integrations/sources')
  const panel = guide(page)
  await panel.locator('summary').click()
  await expect(panel.locator('.page-guide-body')).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  const body = (await panel.locator('.page-guide-body').boundingBox())!
  expect(body.x).toBeGreaterThanOrEqual(0)
  expect(body.x + body.width).toBeLessThanOrEqual(390)
})
