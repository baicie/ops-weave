import { expect, type Page } from '@playwright/test'

export const TOKEN_OK = 'e2e-dev-token-not-secret-32chars-xx'
export const TOKEN_UNAUTHORIZED = 'e2e-unauthorized-token-32chars-xx'

export function diagnosePayload(overrides: Record<string, unknown> = {}) {
  return {
    runId: 'run-e2e-1',
    dataMode: 'synthetic-fixture',
    modelProvider: 'mock-deterministic',
    verification: 'checked-not-proven',
    context: {
      evidence: [
        {
          id: 'ev-html',
          kind: 'incident',
          summary: '<img src=x onerror=alert(1)>plain-evidence',
          sourceRef: 'fixture://ev-html',
        },
      ],
    },
    insight: {
      summary: '<script>alert(1)</script>订单延迟摘要',
      findings: [
        {
          kind: 'observation',
          statement: 'p95 升高',
          evidenceRefs: ['ev-html'],
        },
      ],
      missingData: ['log'],
      limitations: ['fixture'],
    },
    ...overrides,
  }
}

export async function openDiagnose(page: Page) {
  await page.goto('/#/incidents/diagnose')
  await expect(page.getByRole('heading', { name: '只读诊断 Demo' })).toBeVisible()
  await expect(page.locator('zw-input input')).toBeVisible()
}

export async function fillToken(page: Page, token: string) {
  await page.getByRole('textbox', { name: '开发 Token（仅保存在当前页面内存）' }).fill(token)
}

export async function runDiagnose(page: Page) {
  await page.getByRole('button', { name: '运行只读诊断' }).click()
}

export function mockDiagnose(
  page: Page,
  handler: (request: { authorization: string; body: unknown }) => Promise<{
    status: number
    json?: unknown
    delayMs?: number
  }>,
) {
  return page.route('**/agent/api/v1/diagnoses', async route => {
    if (route.request().method() !== 'POST') {
      await route.fallback()
      return
    }
    const authorization = route.request().headers().authorization ?? ''
    const body = route.request().postDataJSON()
    const result = await handler({ authorization, body })
    if (result.delayMs) await new Promise(resolve => setTimeout(resolve, result.delayMs))
    try {
      await route.fulfill({
        status: result.status,
        contentType: 'application/json',
        body: JSON.stringify(result.json ?? {}),
      })
    } catch {
      // The page may have aborted after leaving or cancelling.
    }
  })
}
