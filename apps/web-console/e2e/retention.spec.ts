import { expect, test, type Page } from '@playwright/test'
import { createHash } from 'node:crypto'
import { TOKEN_OK } from './helpers.ts'
const hash = (s: string) => 'sha256:' + createHash('sha256').update(s).digest('hex')
function fixture(allowPurge = true) {
  const policy = { tenantId: 'retention-browser-fixture', version: 'fixture-v1', insightDays: 30, evidenceDays: 7, auditDays: 90, batchSize: 100, heldIncidents: [], allowPurge }
  const asOf = new Date(Math.floor(Date.now() / 1000) * 1000).toISOString().replace('.000Z', 'Z')
  const preview = { tenantId: policy.tenantId, actor: 'fixture-operator', policyDigest: hash(['ai-retention-policy-v1', policy.tenantId, policy.version, 30, 7, 90, 100, allowPurge, ''].join('\n')), asOf, expiresAt: new Date(Date.parse(asOf) + 120000).toISOString().replace('.000Z', 'Z'), previewDigest: '', batches: ['INSIGHT', 'EVIDENCE', 'AUDIT'].map(kind => ({ kind, ids: [], logicalBytes: 0, hasMore: false })) }
  preview.previewDigest = hash(['ai-retention-preview-v1', preview.tenantId, preview.actor, preview.policyDigest, asOf, ...preview.batches.map(b => `${b.kind}:0:false`), ''].join('\n'))
  return { schemaVersion: '1.0', storage: 'postgres', policy, preview }
}
async function enter(page: Page) { await page.goto('/#/ai/retention'); await page.getByRole('textbox', { name: '平台开发 Token（仅保存在当前标签页内存）' }).fill(TOKEN_OK) }
test('explicit preview and confirmation bind a single write and clear on session change', async ({ page }) => {
  const v = fixture(); let gets = 0; let posts = 0
  await page.route(/\/api\/v1\/ai\/retention(?:\/.*)?$/, route => {
    if (route.request().method() === 'GET') { gets++; return route.fulfill({ json: v }) }
    posts++; const command = route.request().postDataJSON(); expect(Object.keys(command).sort()).toEqual(['asOf', 'policyDigest', 'previewDigest', 'requestId']); expect(command.previewDigest).toBe(v.preview.previewDigest)
    return route.fulfill({ json: { schemaVersion: '1.0', storage: 'postgres', state: 'COMPLETED', tenantId: v.policy.tenantId, actor: v.preview.actor, command, preview: v.preview, completedAt: new Date().toISOString() } })
  })
  await enter(page); expect(gets).toBe(0); expect(posts).toBe(0); await page.getByRole('button', { name: '预览留存清理' }).click(); await expect(page.locator('[data-retention-preview]')).toContainText('结果 30 天')
  await expect(page.locator('[data-retention-preview] li')).toHaveCount(3); await expect(page.locator('[data-retention-preview]')).toContainText('证据正文：0 条'); await expect(page.getByRole('button', { name: '确认清理本批内容' })).toBeDisabled(); expect(posts).toBe(0)
  await page.getByRole('checkbox').check(); await page.getByRole('button', { name: '确认清理本批内容' }).click(); await expect(page.locator('[data-retention-receipt]')).toContainText('本批清理已完成'); expect(posts).toBe(1); expect(gets).toBe(1)
  await expect(page.locator('[data-retention-receipt] li')).toHaveCount(3); await expect(page.locator('[data-retention-receipt]')).toContainText('Tool 读取审计：0 条'); await page.getByRole('textbox', { name: '平台开发 Token（仅保存在当前标签页内存）' }).fill(''); await expect(page.locator('[data-retention-receipt]')).toHaveCount(0)
})
test('preview-only policy cannot be confirmed', async ({ page }) => { await page.route('**/api/v1/ai/retention', r => r.fulfill({ json: fixture(false) })); await enter(page); await page.getByRole('button', { name: '预览留存清理' }).click(); await expect(page.locator('[data-retention-preview]')).toContainText('仅允许预览'); await expect(page.getByRole('checkbox')).toBeDisabled(); await expect(page.getByRole('button', { name: '确认清理本批内容' })).toBeDisabled() })
for (const mode of ['digest', 'tenant', 'batch', 'unknown', 'expired']) test(`rejects ${mode} preview`, async ({ page }) => {
  const v = fixture(); if (mode === 'digest') v.preview.previewDigest = 'sha256:' + 'a'.repeat(64); if (mode === 'tenant') v.policy.tenantId = 'other'; if (mode === 'batch') v.preview.batches[0].logicalBytes = -1; if (mode === 'unknown') Object.assign(v, { sql: 'untrusted' }); if (mode === 'expired') v.preview.expiresAt = '2020-01-01T00:00:00Z'
  await page.route('**/api/v1/ai/retention', r => r.fulfill({ json: v })); await enter(page); await page.getByRole('button', { name: '预览留存清理' }).click(); await expect(page.getByRole('alert')).toContainText('留存响应结构'); await expect(page.locator('[data-retention-preview]')).toHaveCount(0)
})
test('uncertain commit is not retried and the original receipt can be queried', async ({ page }) => {
  const v = fixture(); let posts = 0; let command: unknown
  await page.route(/\/api\/v1\/ai\/retention(?:\/.*)?$/, route => { if (route.request().method() === 'POST') { posts++; command = route.request().postDataJSON(); return route.abort('failed') } if (route.request().url().includes('/runs/')) return route.fulfill({ json: { schemaVersion: '1.0', storage: 'postgres', state: 'COMPLETED', tenantId: v.policy.tenantId, actor: v.preview.actor, command, preview: v.preview, completedAt: new Date().toISOString() } }); return route.fulfill({ json: v }) })
  await enter(page); await page.getByRole('button', { name: '预览留存清理' }).click(); await page.getByRole('checkbox').check(); await page.getByRole('button', { name: '确认清理本批内容' }).click(); await expect(page.locator('[data-retention-pending]')).toBeVisible(); await expect(page.getByRole('button', { name: '预览留存清理' })).toBeDisabled(); await page.getByRole('button', { name: '查询清理回执' }).click(); await expect(page.locator('[data-retention-receipt]')).toBeVisible(); expect(posts).toBe(1)
})
test('late preview cannot restore data after logout', async ({ page }) => { let release = () => {}; const held = new Promise<void>(r => { release = r }); const v = fixture(); await page.route('**/api/v1/ai/retention', async route => { await held; await route.fulfill({ json: v }) }); await enter(page); const requested = page.waitForRequest('**/api/v1/ai/retention'); await page.getByRole('button', { name: '预览留存清理' }).click(); await requested; await page.getByRole('textbox', { name: '平台开发 Token（仅保存在当前标签页内存）' }).fill(''); release(); await expect(page.locator('[data-retention-preview]')).toHaveCount(0) })
