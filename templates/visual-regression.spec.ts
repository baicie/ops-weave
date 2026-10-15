/**
 * Candidate Playwright visual capture spec. Copy to new web app's e2e after route/auth verification.
 * Requires OPSWEAVE_UI_BASE_URL and OPSWEAVE_UI_ROUTE in environment; avoid capturing real private customer data.
 * This SPEC TAKES SCREENSHOTS; it does not evaluate visual beauty or guarantee a working backend.
 */
import { test, expect } from '@playwright/test'

const baseUrl = process.env.OPSWEAVE_UI_BASE_URL
const route = process.env.OPSWEAVE_UI_ROUTE
const allowCapture = Boolean(baseUrl && route)
const viewports = [
  { name: 'desktop', width: 1440, height: 900 },
  { name: 'narrow', width: 1024, height: 768 },
  { name: 'mobile', width: 390, height: 844 },
] as const

for (const viewport of viewports) {
  for (const theme of ['light', 'dark'] as const) {
    test(`${viewport.name}-${theme} screenshot`, async ({ browser }) => {
      test.skip(!allowCapture, 'Set OPSWEAVE_UI_BASE_URL and OPSWEAVE_UI_ROUTE; no inferred access or customer auth')
      const context = await browser.newContext({
        viewport: { width: viewport.width, height: viewport.height },
        colorScheme: theme,
        reducedMotion: 'reduce',
        // Supply a carefully reviewed authorized storageState in your local test runner if necessary.
      })
      const page = await context.newPage()
      await page.goto(new URL(route!, baseUrl!).toString(), { waitUntil: 'domcontentloaded' })
      // Replace with a product-specific stable READY selector before accepting screenshots.
      await expect(page.locator('body')).toBeVisible()
      // Explicit theme adapter is needed when app doesn't honor prefers-color-scheme.
      await page.screenshot({
        path: `test-results/screenshots/${viewport.name}-${theme}.png`,
        fullPage: true,
        animations: 'disabled',
      })
      await context.close()
    })
  }
}
