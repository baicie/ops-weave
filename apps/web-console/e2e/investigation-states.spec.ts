import { expect, test, type Page } from '@playwright/test'
import { mkdirSync } from 'node:fs'
import path from 'node:path'
import { TOKEN_OK } from './helpers.ts'

const incident = '10000000-0000-4000-8000-000000000082'
const result = '10000000-0000-4000-8000-000000000083'
async function capture(page: Page, name: string) {
  await page.locator('[data-investigation-workspace]').evaluate(element => {
    for (let current: Element | null = element; current; current = current.parentElement) current.scrollTop = 0
  })
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  if (process.env.OPSWEAVE_VISUAL_OUTPUT) {
    mkdirSync(process.env.OPSWEAVE_VISUAL_OUTPUT, { recursive: true })
    await page.screenshot({ path: path.join(process.env.OPSWEAVE_VISUAL_OUTPUT, `investigation-state-${name}.png`), fullPage: true })
  }
}
for (const theme of ['light', 'dark']) test(`mobile states and keyboard focus ${theme}`, async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await page.addInitScript(theme => localStorage.setItem('opsweave.ui.theme', theme), theme)
  await page.goto(`/#/incidents/current-diagnose?incidentId=${incident}`)
  await expect(page.locator('.iw-status[data-state="forbidden"]')).toBeVisible()
  await capture(page, `${theme}-initial-forbidden`)
  await page.getByRole('textbox', { name: '平台开发 Token（仅保存在当前标签页内存）' }).fill(TOKEN_OK)
  await expect(page.getByRole('button', { name: '开始只读调查' })).toBeEnabled()
  await capture(page, `${theme}-initial`)
  const field = page.getByRole('textbox', { name: '调查问题' })
  const colors = await field.evaluate(element => { const style = getComputedStyle(element); return { text: style.color, background: style.backgroundColor, border: style.borderTopColor } })
  const luminance = (color: string) => {
    const rgb = color.match(/[\d.]+/g)!.slice(0, 3).map(Number).map(value => { const channel = value / 255; return channel <= .04045 ? channel / 12.92 : ((channel + .055) / 1.055) ** 2.4 })
    return rgb[0] * .2126 + rgb[1] * .7152 + rgb[2] * .0722
  }
  const contrast = (a: string, b: string) => { const values = [luminance(a), luminance(b)].sort((x, y) => y - x); return (values[0] + .05) / (values[1] + .05) }
  expect(contrast(colors.text, colors.background)).toBeGreaterThanOrEqual(4.5)
  expect(contrast(colors.border, colors.background)).toBeGreaterThanOrEqual(3)
  await field.focus(); await expect(field).toBeFocused()
  const focus = await field.evaluate(element => { const style = getComputedStyle(element); return { style: style.outlineStyle, width: parseFloat(style.outlineWidth) } })
  expect(focus.style).not.toBe('none'); expect(focus.width).toBeGreaterThanOrEqual(2)
  if (process.env.OPSWEAVE_VISUAL_OUTPUT) await page.screenshot({ path: path.join(process.env.OPSWEAVE_VISUAL_OUTPUT, `investigation-state-${theme}-focus.png`), fullPage: true })
  await page.keyboard.press('Tab'); await expect(page.locator('.iw-advanced > summary')).toBeFocused()
  await page.keyboard.press('Enter'); await expect(page.getByRole('combobox', { name: '提交方式', exact: true })).toBeVisible()
  await page.getByRole('textbox', { name: 'AIInsight 结果请求标识' }).fill(result)
  await page.route('**/api/v1/ai/insights/*', route => route.fulfill({ status: 410, json: { error: 'EXPIRED' } }))
  await page.getByRole('button', { name: '读取已保存结果' }).click()
  await expect(page.getByRole('alert')).toHaveAttribute('data-state', 'stale')
  await capture(page, `${theme}-stale`)
  await page.unroute('**/api/v1/ai/insights/*')
  await page.route('**/api/v1/ai/insights/*', route => route.fulfill({ status: 403, json: {} }))
  await page.getByRole('button', { name: '读取已保存结果' }).click()
  await expect(page.getByRole('alert')).toHaveAttribute('data-state', 'forbidden')
  await expect(page.locator('[data-insight-result]')).toHaveCount(0)
  await capture(page, `${theme}-forbidden`)
})

test('loading and unknown-write have distinct visible feedback and no automatic replay', async ({ page }) => {
  await page.setViewportSize({ width: 1024, height: 768 })
  let release: () => void = () => {}, calls = 0
  const paused = new Promise<void>(resolve => { release = resolve })
  await page.route('**/api/v1/ai/runs', async route => { ++calls; await paused; await route.fulfill({ status: 503, json: {} }) })
  await page.goto(`/#/incidents/current-diagnose?incidentId=${incident}`)
  await page.getByRole('textbox', { name: '平台开发 Token（仅保存在当前标签页内存）' }).fill(TOKEN_OK)
  await page.getByRole('button', { name: '开始只读调查' }).click()
  await expect(page.locator('.iw-loading')).toBeVisible()
  await capture(page, 'loading')
  release()
  await expect(page.getByRole('alert')).toContainText('UNKNOWN')
  await expect(page.locator('[data-state="unknown-write"]')).toBeVisible()
  await capture(page, 'unknown-error')
  expect(calls).toBe(1)
})
