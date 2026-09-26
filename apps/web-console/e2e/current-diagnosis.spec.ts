import { expect, test, type Page } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { TOKEN_OK } from './helpers.ts'

// Explicit HTTP fixtures; real PG/Rust/VM acceptance is scripts/check_metrics_stack.mjs --runtime.
const example = JSON.parse(readFileSync(new URL('../../../contracts/examples/ai-insight-result.json', import.meta.url), 'utf8'))
const evidenceExample = JSON.parse(readFileSync(new URL('../../../contracts/examples/platform-evidence.json', import.meta.url), 'utf8'))
function saved() { const v = structuredClone(example); v.record.expiresAt = new Date(Date.now() + 3600000).toISOString(); return v }
async function enter(page: Page) {
  await page.goto(`/#/incidents/current-diagnose?incidentId=${example.record.incidentId}`)
  await page.getByRole('textbox', { name: '平台开发 Token（仅保存在当前标签页内存）' }).fill(TOKEN_OK)
}
test('saves current request, reopens by ID and reauthorizes evidence with explicit fixture and mock labels', async ({ page }) => {
  const v = saved(); let calls = 0
  await page.route('**/api/v1/**', async route => {
    const request = route.request(); expect(request.headers().authorization).toBe(`Bearer ${TOKEN_OK}`)
    expect(request.headers()['x-opsweave-runtime-key']).toBeUndefined()
    if (request.url().endsWith('/ai/diagnoses')) {
      ++calls; const body = request.postDataJSON(); expect(Object.keys(body).sort()).toEqual(['incidentId', 'knowledgeMode', 'question', 'runId', 'timeRange'])
      expect(body.knowledgeMode).toBe('current'); v.record.id = body.runId; v.record.question = body.question; v.record.queryWindow = body.timeRange
      return route.fulfill({ json: v })
    }
    if (request.url().includes('/ai/insights/')) return route.fulfill({ json: v })
    const doc = structuredClone(evidenceExample); doc.evidence.id = v.record.evidenceIds[0]; doc.evidence.sourceRef = `/api/v1/ai/evidence/${doc.evidence.id}`
    doc.evidence.tenantId = v.record.tenantId; doc.evidence.incidentId = v.record.incidentId; doc.evidence.expiresAt = v.record.expiresAt
    doc.sessionId = v.record.sessionId; doc.incidentVersion = v.record.incidentVersion; doc.entityIds = v.record.entityIds; doc.queryWindow = v.record.queryWindow
    doc.data.title = '<img src=x onerror=alert(1)> untrusted fixture'
    return route.fulfill({ json: doc })
  })
  await enter(page)
  await expect(page.getByRole('textbox', { name: '诊断问题' })).not.toBeEmpty()
  await page.getByRole('textbox', { name: '诊断问题' }).fill('请解释这组本地证据并保留缺口。')
  await page.getByRole('button', { name: '开始只读诊断', exact: true }).click()
  await expect(page.locator('[data-insight-result]')).toContainText('mock-deterministic')
  await expect(page.locator('[data-insight-result]')).toContainText('labeled-fixture')
  await page.reload(); await expect(page.locator('[data-insight-result]')).toHaveCount(0)
  await page.getByRole('textbox', { name: '平台开发 Token（仅保存在当前标签页内存）' }).fill(TOKEN_OK)
  await page.getByRole('button', { name: '读取已保存结果', exact: true }).click()
  await expect(page.getByRole('textbox', { name: '诊断问题' })).toHaveValue('请解释这组本地证据并保留缺口。')
  await expect(page.getByRole('textbox', { name: 'Incident ID' })).toHaveValue(v.record.incidentId)
  await page.getByRole('button', { name: `读取快照 ${v.record.evidenceIds[0]}`, exact: true }).click()
  await expect(page.locator('[data-insight-evidence]')).toContainText('<img src=x onerror=alert(1)>')
  await expect(page.locator('[data-insight-evidence] img')).toHaveCount(0)
  expect(calls).toBe(1)
  await page.getByRole('textbox', { name: '平台开发 Token（仅保存在当前标签页内存）' }).fill('')
  await expect(page.locator('[data-insight-result]')).toHaveCount(0); await expect(page.locator('[data-insight-evidence]')).toHaveCount(0)
})
for (const mode of ['fabricated-reference', 'actions', 'wrong-run', 'expired']) test(`rejects ${mode} result`, async ({ page }) => {
  await page.route('**/api/v1/ai/diagnoses', route => {
    const v = saved(), body = route.request().postDataJSON(); v.record.id = body.runId; v.record.question = body.question; v.record.queryWindow = body.timeRange
    if (mode === 'fabricated-reference') v.record.insight.findings[0].evidenceRefs = ['11111111-1111-4111-8111-111111111111']
    if (mode === 'actions') v.record.insight.actions = ['shell']
    if (mode === 'wrong-run') v.record.id = example.record.id
    if (mode === 'expired') v.record.expiresAt = v.record.savedAt
    return route.fulfill({ json: v })
  })
  await enter(page); await page.getByRole('button', { name: '开始只读诊断', exact: true }).click()
  await expect(page.getByRole('alert')).not.toBeEmpty(); await expect(page.locator('[data-insight-result]')).toHaveCount(0)
})
test('a failed dispatch preserves run ID for lookup without automatically calling the model again', async ({ page }) => {
  let calls = 0
  await page.route('**/api/v1/ai/diagnoses', route => { ++calls; return route.fulfill({ status: 504, json: { error: 'DEADLINE' } }) })
  await page.route('**/api/v1/ai/insights/*', route => route.fulfill({ status: 404, json: { error: 'NOT_FOUND' } }))
  await enter(page); await page.getByRole('button', { name: '开始只读诊断', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('保存状态待确认')
  await expect(page.getByRole('button', { name: '开始只读诊断', exact: true })).toBeDisabled()
  await expect(page.getByRole('textbox', { name: '结果请求标识' })).not.toBeEmpty()
  await page.getByRole('button', { name: '读取已保存结果', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('尚未找到已保存结果'); expect(calls).toBe(1)
})
test('late response cannot restore data after identity changes', async ({ page }) => {
  let release: () => void = () => {}; const paused = new Promise<void>(r => { release = r })
  await page.route('**/api/v1/ai/diagnoses', async route => {
    const v = saved(), body = route.request().postDataJSON(); v.record.id = body.runId; v.record.question = body.question; v.record.queryWindow = body.timeRange
    await paused; await route.fulfill({ json: v })
  })
  await enter(page); const requested = page.waitForRequest('**/api/v1/ai/diagnoses')
  await page.getByRole('button', { name: '开始只读诊断', exact: true }).click(); await requested
  await page.getByRole('textbox', { name: '平台开发 Token（仅保存在当前标签页内存）' }).fill(''); release()
  await expect(page.locator('[data-insight-result]')).toHaveCount(0); await expect(page.getByRole('button', { name: '读取已保存结果' })).toBeDisabled()
})
test('evidence from another tenant is rejected, and a revoked result is cleared', async ({ page }) => {
  const v = saved(); let reads = 0
  await page.route('**/api/v1/ai/insights/*', route => { ++reads; return route.fulfill(reads === 1 ? { json: v } : { status: 403, json: {} }) })
  await page.route('**/api/v1/ai/evidence/*', route => route.fulfill({ json: evidenceExample }))
  await page.goto(`/#/incidents/current-diagnose?runId=${v.record.id}`)
  await page.getByRole('textbox', { name: '平台开发 Token（仅保存在当前标签页内存）' }).fill(TOKEN_OK)
  await page.getByRole('button', { name: '读取已保存结果' }).click()
  await page.getByRole('button', { name: `读取快照 ${v.record.evidenceIds[0]}`, exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('范围不正确'); await expect(page.locator('[data-insight-evidence]')).toHaveCount(0)
  await page.getByRole('button', { name: '读取已保存结果' }).click()
  await expect(page.getByRole('alert')).toContainText('无权'); await expect(page.locator('[data-insight-result]')).toHaveCount(0)
})
