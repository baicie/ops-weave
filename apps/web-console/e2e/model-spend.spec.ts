import { expect, test, type Page } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { TOKEN_OK } from './helpers.ts'
const example = JSON.parse(readFileSync(new URL('../../../contracts/examples/model-spend-result.json', import.meta.url), 'utf8'))
const control = '读取用量与费用'
async function enter(page: Page) { await page.goto(`/#/incidents/current-diagnose?runId=${example.record.runId}`); await page.getByRole('textbox', { name: '平台开发 Token（仅保存在当前标签页内存）' }).fill(TOKEN_OK) }
test('reads configured price estimate explicitly without a model dispatch and clears on identity change', async ({ page }) => {
  const paths: string[] = []
  await page.route('**/api/v1/**', route => { paths.push(route.request().url()); expect(route.request().method()).toBe('GET'); expect(route.request().headers()['x-opsweave-runtime-key']).toBeUndefined(); return route.fulfill({ json: example }) })
  await enter(page); expect(paths).toHaveLength(0); await page.getByRole('button', { name: control }).click()
  await expect(page.locator('[data-model-spend]')).toContainText('USD 0.001200'); await expect(page.locator('[data-model-spend]')).toContainText('输入 1000 Token')
  await expect(page.locator('[data-model-spend]')).toContainText('并非提供方账单'); expect(paths).toHaveLength(1)
  await page.getByRole('textbox', { name: '平台开发 Token（仅保存在当前标签页内存）' }).fill(''); await expect(page.locator('[data-model-spend]')).toHaveCount(0)
})
test('uncertain reservation preserves charge and never fabricates zero usage', async ({ page }) => {
  const v = structuredClone(example); Object.assign(v.record, { state: 'UNCERTAIN', usage: null, reportedAt: null, estimatedMicros: null, accountedMicros: v.record.reservedMicros })
  await page.route('**/api/v1/ai/model-calls/*', route => route.fulfill({ json: v })); await enter(page); await page.getByRole('button', { name: control }).click()
  await expect(page.locator('[data-model-spend]')).toContainText('费用待确认'); await expect(page.locator('[data-model-spend]')).toContainText('USD 0.086016')
  await expect(page.locator('[data-model-spend]')).not.toContainText('已上报')
})
for (const mode of ['wrong-run', 'wrong-cost', 'cached-overflow', 'negative', 'unknown-zero', 'wrong-mock', 'digest-newline', 'price-newline']) test(`rejects ${mode} usage`, async ({ page }) => {
  const v = structuredClone(example)
  if (mode === 'wrong-run') v.record.runId = crypto.randomUUID()
  if (mode === 'wrong-cost') v.record.estimatedMicros = 0
  if (mode === 'cached-overflow') v.record.usage.cachedInputTokens = 1001
  if (mode === 'negative') v.record.usage.inputTokens = -1
  if (mode === 'unknown-zero') v.record.state = 'UNCERTAIN'
  if (mode === 'wrong-mock') v.record.policy.provider = 'mock-deterministic'
  if (mode === 'digest-newline') v.record.inputDigest += '\n'
  if (mode === 'price-newline') v.record.policy.priceVersion += '\n'
  await page.route('**/api/v1/ai/model-calls/*', route => route.fulfill({ json: v })); await enter(page); await page.getByRole('button', { name: control }).click()
  await expect(page.getByRole('alert')).toContainText('用量响应结构'); await expect(page.locator('[data-model-spend]')).toHaveCount(0)
})
test('absent usage is unknown and does not dispatch a new model request', async ({ page }) => {
  let calls = 0; await page.route('**/api/v1/**', route => { ++calls; expect(route.request().url()).toContain('/ai/model-calls/'); return route.fulfill({ status: 404, json: {} }) })
  await enter(page); await page.getByRole('button', { name: control }).click(); await expect(page.getByRole('alert')).toContainText('无法据此确认费用为零'); expect(calls).toBe(1)
})
test('late usage cannot restore another identity data', async ({ page }) => {
  let release = () => {}; const held = new Promise<void>(r => { release = r })
  await page.route('**/api/v1/ai/model-calls/*', async route => { await held; await route.fulfill({ json: example }) })
  await enter(page); const requested = page.waitForRequest('**/api/v1/ai/model-calls/*'); await page.getByRole('button', { name: control }).click(); await requested
  await page.getByRole('textbox', { name: '平台开发 Token（仅保存在当前标签页内存）' }).fill(''); release(); await expect(page.locator('[data-model-spend]')).toHaveCount(0)
})
