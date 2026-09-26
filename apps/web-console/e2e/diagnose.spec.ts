import { expect, test } from '@playwright/test'
import {
  TOKEN_OK,
  TOKEN_UNAUTHORIZED,
  diagnosePayload,
  fillToken,
  mockDiagnose,
  openDiagnose,
  runDiagnose,
} from './helpers.ts'

test.describe('diagnose page', () => {
  test('upgrades Zeus UI native controls in the production bundle', async ({ page }) => {
    await openDiagnose(page)
    await expect.poll(async () => page.evaluate(() => ({
      buttonDefined: Boolean(customElements.get('zw-button')),
      inputDefined: Boolean(customElements.get('zw-input')),
      innerButton: Boolean(document.querySelector('zw-button button')),
      innerInput: Boolean(document.querySelector('zw-input input')),
    }))).toEqual({
      buttonDefined: true,
      inputDefined: true,
      innerButton: true,
      innerInput: true,
    })
  })

  test('keeps run disabled until a long enough in-memory token is entered', async ({ page }) => {
    await openDiagnose(page)
    await expect(page.getByRole('button', { name: '运行只读诊断' })).toBeDisabled()
    await fillToken(page, TOKEN_OK)
    await expect(page.getByRole('button', { name: '运行只读诊断' })).toBeEnabled()
  })

  test('submits via zw-input value-change and zw-button press, then shows evidence as text', async ({ page }) => {
    const posts: unknown[] = []
    await mockDiagnose(page, async ({ authorization, body }) => {
      posts.push({ authorization, body })
      return { status: 200, json: diagnosePayload() }
    })
    await openDiagnose(page)
    await fillToken(page, TOKEN_OK)
    await runDiagnose(page)

    await expect(page.getByRole('heading', { name: /<script>alert\(1\)<\/script>订单延迟摘要/ })).toBeVisible()
    await expect(page.getByText('synthetic-fixture / mock-deterministic')).toBeVisible()
    await page.locator('#evidence-ev-html').locator('summary').click()
    await expect(page.getByText('<img src=x onerror=alert(1)>plain-evidence')).toBeVisible()
    await expect(page.locator('[data-page="diagnose"] script')).toHaveCount(0)
    await expect(page.locator('[data-page="diagnose"] img')).toHaveCount(0)

    expect(posts).toHaveLength(1)
    const sent = posts[0] as { authorization: string; body: Record<string, unknown> }
    expect(sent.authorization).toBe(`Bearer ${TOKEN_OK}`)
    expect(sent.body.incidentId).toBe('inc-demo')
    expect(sent.body).not.toHaveProperty('tenantId')
    expect(sent.body).not.toHaveProperty('userId')
    expect(sent.body).not.toHaveProperty('permissions')
  })

  test('rejects unauthorized tokens and does not render a fixture success', async ({ page }) => {
    await mockDiagnose(page, async ({ authorization }) => {
      if (authorization !== `Bearer ${TOKEN_OK}`) return { status: 401, json: { error: 'unauthorized' } }
      return { status: 200, json: diagnosePayload() }
    })
    await openDiagnose(page)
    await fillToken(page, TOKEN_UNAUTHORIZED)
    await runDiagnose(page)
    await expect(page.getByRole('alert')).toContainText('HTTP 401')
    await expect(page.getByRole('heading', { name: /订单延迟摘要/ })).toHaveCount(0)
    await expect(page.getByText('synthetic-fixture / mock-deterministic')).toHaveCount(0)
  })

  test('does not treat a real-mode HTTP failure as a successful fixture snapshot', async ({ page }) => {
    await mockDiagnose(page, async () => ({ status: 503, json: { error: 'upstream' } }))
    await openDiagnose(page)
    await fillToken(page, TOKEN_OK)
    await runDiagnose(page)
    await expect(page.getByRole('alert')).toContainText('HTTP 503')
    await expect(page.getByText('synthetic-fixture')).toHaveCount(0)
  })

  test('disables rerun while in flight and allows cancel', async ({ page }) => {
    let fulfill!: (value: { status: number; json: unknown }) => void
    const gate = new Promise<{ status: number; json: unknown }>(resolve => {
      fulfill = resolve
    })
    await mockDiagnose(page, async () => gate)
    await openDiagnose(page)
    await fillToken(page, TOKEN_OK)
    await runDiagnose(page)
    await expect(page.getByRole('button', { name: '正在诊断…' })).toBeDisabled()
    await expect(page.getByRole('button', { name: '取消' })).toBeEnabled()
    await page.getByRole('button', { name: '取消' }).click()
    await expect(page.getByRole('alert')).toContainText('诊断已取消或超时')
    fulfill({ status: 200, json: diagnosePayload() })
    await expect(page.getByRole('heading', { name: /订单延迟摘要/ })).toHaveCount(0)
  })

  test('does not apply a completed request after the page unmounts', async ({ page }) => {
    let fulfill!: (value: { status: number; json: unknown }) => void
    const gate = new Promise<{ status: number; json: unknown }>(resolve => {
      fulfill = resolve
    })
    await mockDiagnose(page, async () => gate)
    await openDiagnose(page)
    await fillToken(page, TOKEN_OK)
    await runDiagnose(page)
    await expect(page.getByRole('button', { name: '正在诊断…' })).toBeVisible()
    await page.getByRole('link', { name: '资产' }).click()
    await expect(page.getByRole('heading', { name: '资产', exact: true })).toBeVisible()
    fulfill({ status: 200, json: diagnosePayload({ insight: { ...(diagnosePayload().insight), summary: '迟到结果不得出现' } }) })
    await page.waitForTimeout(300)
    await expect(page.getByText('迟到结果不得出现')).toHaveCount(0)
    await page.getByRole('link', { name: 'Fixture 诊断演示' }).click()
    await expect(page.getByRole('heading', { name: '只读诊断 Demo' })).toBeVisible()
    await expect(page.getByText('迟到结果不得出现')).toHaveCount(0)
  })
})
