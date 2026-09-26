import { expect, test, type Page } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { TOKEN_OK } from './helpers.ts'
const sample = (name: string) => JSON.parse(readFileSync(new URL(`../../../contracts/examples/${name}.json`, import.meta.url), 'utf8'))
const detail = sample('incident-detail'), h = detail.record.incident
function response(url: string, count = 1) {
  const q = new URL(url).searchParams
  const query = { version: Number(q.get('version')), from: Number(q.get('from')), till: Number(q.get('till')),
    asOf: q.get('asOf') ?? new Date(Number(q.get('till')) * 1000).toISOString(), source: q.get('source') ?? '', eventId: q.get('eventId') ?? '', after: q.get('after'), limit: 25 }
  const p = detail.record.problems[0]
  const items = Array.from({ length: count }, (_, i) => ({ ...sample('problem-observation'), id: `${String(i + (query.after ? 26 : 1)).padStart(8, '0')}-0000-0000-0000-000000000001`,
    observation: { ...p.observation, title: '<img src=x onerror=alert(1)>历史', occurredAt: new Date((query.till - 50) * 1000).toISOString(),
      observedAt: new Date((query.till - 10) * 1000).toISOString(), recoveryEventId: null, recoveredAt: null, state: 'ACTIVE', gaps: [] },
    entities: p.entities, firstReceivedAt: query.asOf, gaps: [] }))
  return { ...sample('problem-observation-page'), incidentId: h.id, query, items, nextCursor: count === 25 ? items.at(-1)!.id : null }
}
async function open(page: Page, handler: (url: string) => unknown | Promise<unknown>) {
  await page.route('**/api/v1/**', async route => {
    const url = route.request().url(), q = new URL(url).searchParams
    if (new URL(url).pathname.endsWith('/problem-observations')) return route.fulfill(await handler(url) as Parameters<typeof route.fulfill>[0])
    return route.fulfill({ json: new URL(url).pathname === '/api/v1/incidents' ? { storage: 'postgres', query: { status: q.get('status'), after: q.get('after'), limit: 25 }, items: [h], nextCursor: null } : detail })
  })
  await page.goto('/#/incidents'); await page.getByRole('textbox', { name: '平台开发 Token（仅保存在当前标签页内存）' }).fill(TOKEN_OK)
  await page.getByRole('button', { name: '刷新 Incident', exact: true }).click(); await page.getByRole('button', { name: '查看 Incident', exact: true }).click()
}
test('normalized history displays original recovery state and retains cutoff version and filters when paging', async ({ page }) => {
  const requests: URLSearchParams[] = []
  await open(page, url => { requests.push(new URL(url).searchParams); return { json: response(url, requests.length === 1 ? 25 : 1) } })
  await page.getByRole('button', { name: '读取告警观测', exact: true }).click()
  await expect(page.locator('[data-problem-observation]')).toHaveCount(25); await expect(page.locator('[data-problem-observation] img')).toHaveCount(0)
  await expect(page.locator('[data-problem-observation]').first()).toContainText('ACTIVE')
  await expect(page.locator('[data-problem-history]')).toContainText('厂商原始报文未保存')
  const cutoff = (await page.locator('[data-problem-history-page]').textContent())!.split('知识截止 ')[1]
  await page.getByRole('button', { name: '下一页告警观测', exact: true }).click(); await expect(page.locator('[data-problem-observation]')).toHaveCount(1)
  expect(requests[1]!.get('asOf')).toBe(cutoff); expect(requests[1]!.get('till')).toBe(requests[0]!.get('till')); expect(requests[1]!.get('version')).toBe(String(h.version))
  expect(requests[1]!.get('after')).toBe('00000025-0000-0000-0000-000000000001')
  await page.getByRole('textbox', { name: 'Problem Event ID 筛选' }).fill(detail.record.problems[0].observation.problemEventId)
  await expect(page.locator('[data-problem-observation]')).toHaveCount(0); await expect(page.getByRole('button', { name: '读取告警观测', exact: true })).toBeDisabled()
  await page.getByRole('textbox', { name: '告警来源筛选' }).fill(detail.record.problems[0].observation.sourceInstanceId)
  await page.getByRole('button', { name: '读取告警观测', exact: true }).click(); await expect(page.locator('[data-problem-observation]')).toHaveCount(1)
  expect(requests[2]!.get('after')).toBeNull(); expect(requests[2]!.get('source')).toBe(detail.record.problems[0].observation.sourceInstanceId)
})
for (const mode of ['tenant', 'occurrence', 'future-nanosecond', 'historical-mapping', 'cursor']) test(`rejects ${mode} without rendering history`, async ({ page }) => {
  await open(page, url => {
    const value = response(url)
    if (mode === 'tenant') value.items[0]!.observation.tenantId = 'foreign'
    if (mode === 'occurrence') value.items[0]!.observation.problemEventId = '99999'
    if (mode === 'future-nanosecond') value.items[0]!.firstReceivedAt = value.query.asOf.replace('.000Z', '.000000001Z')
    if (mode === 'historical-mapping') value.items[0]!.entities = [{ hostId: '99999', entityId: '11111111-1111-1111-1111-111111111111' }]
    if (mode === 'cursor') value.nextCursor = '../raw'
    return { json: value }
  })
  await page.getByRole('button', { name: '读取告警观测', exact: true }).click()
  await expect(page.locator('[data-problem-history-error]')).not.toBeEmpty(); await expect(page.locator('[data-problem-observation]')).toHaveCount(0)
})
test('version conflict requires a fresh detail and does not silently retry history', async ({ page }) => {
  let calls = 0
  await open(page, url => ++calls === 1 ? { status: 409, json: { error: 'CONFLICT' } } : { json: response(url) })
  await page.getByRole('button', { name: '读取告警观测', exact: true }).click()
  await expect(page.locator('[data-problem-history-error]')).toContainText('版本或归属已变化'); await expect(page.getByRole('button', { name: '读取告警观测', exact: true })).toBeDisabled()
  expect(calls).toBe(1); await page.getByRole('button', { name: '刷新详情后重查', exact: true }).click()
  await expect(page.getByRole('button', { name: '读取告警观测', exact: true })).toBeEnabled(); expect(calls).toBe(1)
  await page.getByRole('button', { name: '读取告警观测', exact: true }).click(); await expect(page.locator('[data-problem-observation]')).toHaveCount(1)
})
test('permission revocation clears current detail and retained history', async ({ page }) => {
  let calls = 0
  await open(page, url => ++calls === 1 ? { json: response(url) } : { status: 403, json: { error: 'FORBIDDEN' } })
  await page.getByRole('button', { name: '读取告警观测', exact: true }).click(); await expect(page.locator('[data-problem-observation]')).toHaveCount(1)
  await page.getByRole('button', { name: '读取告警观测', exact: true }).click(); await expect(page.locator('[data-incident-detail]')).toHaveCount(0)
  await expect(page.getByRole('table')).toHaveCount(0)
})
test('late history cannot restore data after logout', async ({ page }) => {
  let release!: () => void, started!: () => void
  const pending = new Promise<void>(resolve => { release = resolve }), dispatched = new Promise<void>(resolve => { started = resolve })
  await open(page, async url => { started(); await pending; return { json: response(url) } })
  await page.getByRole('button', { name: '读取告警观测', exact: true }).click(); await dispatched
  await page.getByRole('button', { name: '清除开发会话', exact: true }).click(); release()
  await expect(page.locator('[data-incident-detail]')).toHaveCount(0); await expect(page.locator('[data-problem-observation]')).toHaveCount(0)
  await expect(page.getByRole('button', { name: '刷新 Incident', exact: true })).toBeDisabled()
})
