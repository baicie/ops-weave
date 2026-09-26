import { expect, test, type Page, type Route } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { TOKEN_OK } from './helpers.ts'

// Explicit HTTP fixtures, sourced from the canonical examples. Real PG acceptance is separate.
const example = JSON.parse(readFileSync(new URL('../../../contracts/examples/incident-detail.json', import.meta.url), 'utf8'))
const h = example.record.incident
const id = h.id as string
function list(route: Route, items = [h], nextCursor: string | null = null) {
  const query = new URL(route.request().url()).searchParams
  return { storage: 'postgres', query: { status: query.get('status'), after: query.get('after'), limit: 25 }, items, nextCursor }
}
async function open(page: Page) {
  await page.goto('/#/incidents')
  await page.getByRole('textbox', { name: '平台开发 Token（仅保存在当前标签页内存）' }).fill(TOKEN_OK)
  await page.getByRole('button', { name: '刷新 Incident', exact: true }).click()
  await page.getByRole('button', { name: '查看 Incident', exact: true }).first().click()
}
test('imports a bounded explicit window, renders missing data and reauthorizes an entity and metric window', async ({ page }) => {
  const calls: { url: string; body: unknown; auth: string }[] = []
  const entityId = example.record.problems[0].entities[0].entityId
  await page.route('**/api/v1/**', async route => {
    const request = route.request(); const url = request.url()
    calls.push({ url, body: request.postDataJSON(), auth: request.headers().authorization ?? '' })
    if (url.includes('/problems/ingest')) return route.fulfill({ json: { storage: 'postgres', dataMode: 'labeled-fixture', sourceInstanceId: 'zabbix-1',
      accepted: 1, createdIncidents: 1, changedIncidents: 0, unmappedHosts: 0, nextAfterEventId: null } })
    if (url.includes('/incidents?')) return route.fulfill({ json: list(route) })
    if (url.endsWith(`/incidents/${id}`)) return route.fulfill({ json: example })
    if (url.endsWith(`/entities/${entityId}`)) return route.fulfill({ json: { schemaVersion: '1.0', id: entityId, tenantId: 'tenant-demo', entityType: 'host',
      name: '<img src=x onerror=alert(1)> Host', lifecycle: 'ACTIVE', version: 2, attributes: { hostId: '10084', sourceInstanceId: 'zabbix-1', dataMode: 'labeled-fixture' } } })
    if (url.includes('/metrics/definitions')) return route.fulfill({ json: { items: [{ metricKey: 'host.cpu.usage.user', displayName: 'CPU User', unit: '1' }] } })
    if (url.includes('/series?')) {
      const query = new URL(url).searchParams
      return route.fulfill({ json: { entityId, metricKey: 'host.cpu.usage.user', unit: '1', from: Number(query.get('from')), till: Number(query.get('till')),
        series: [], status: { kind: 'NO_DATA', fresh: false, lastPointAt: null, partial: false } } })
    }
    return route.fulfill({ status: 404, json: {} })
  })
  await page.goto('/#/incidents')
  await page.getByRole('textbox', { name: '平台开发 Token（仅保存在当前标签页内存）' }).fill(TOKEN_OK)
  await page.locator('summary').click()
  await page.getByRole('button', { name: '使用 fixture 时间窗口' }).click()
  await page.getByRole('button', { name: '导入告警首批' }).click()
  await expect(page.locator('[data-problem-import]')).toContainText('labeled-fixture / postgres')
  expect(calls[0]!.body).toEqual({ from: Date.parse('2026-09-21T12:00:00Z') / 1000, till: Date.parse('2026-09-21T13:00:00Z') / 1000, afterEventId: null, limit: 100 })
  await page.getByRole('button', { name: '刷新 Incident', exact: true }).click()
  await page.getByRole('button', { name: '查看 Incident' }).click()
  await expect(page.locator('[data-incident-detail]')).toContainText('RECOVERED')
  await expect(page.locator('[data-incident-state]')).toContainText('OPEN · 版本 1')
  await expect(page.locator('[data-incident-gaps]')).toContainText('日志尚未接入')
  await expect(page.locator('.incident-timeline')).toContainText('可见 2026-09-25T00:00:00Z')
  await page.getByRole('button', { name: '查看关联资产' }).click()
  await expect(page.locator('[data-incident-asset]')).toContainText('版本 2')
  await expect(page.locator('[data-page="incidents"] img')).toHaveCount(0)
  await page.getByRole('link', { name: '查看发生前后 30 分钟指标' }).click()
  await expect(page.locator('[data-metric-window]')).toContainText('2026-09-21T11:30:00.000Z 至 2026-09-21T12:30:00.000Z')
  await expect(page.getByRole('textbox', { name: '平台开发 Token（仅保存在当前标签页内存）' })).toHaveValue(TOKEN_OK)
  await page.getByRole('textbox', { name: '平台开发 Token（仅保存在当前标签页内存）' }).fill(TOKEN_OK)
  await page.getByRole('button', { name: '读取资产和指标' }).click()
  await expect(page.getByRole('combobox', { name: 'Host' })).toHaveValue(entityId)
  await page.getByRole('button', { name: '查询', exact: true }).click()
  await expect(page.locator('[data-state="no-data"]')).toBeVisible()
  expect(calls.filter(c => c.url.endsWith(`/entities/${entityId}`))).toHaveLength(2)
  expect(calls.every(c => c.auth === `Bearer ${TOKEN_OK}` && !c.url.includes('tenant'))).toBe(true)
})
test('retains the same transition key across an uncertain response and displays the fresh version', async ({ page }) => {
  const requests: Record<string, unknown>[] = []
  let updated = false
  await page.route('**/api/v1/**', async route => {
    if (route.request().method() === 'POST') {
      requests.push(route.request().postDataJSON())
      if (requests.length === 1) return route.fulfill({ status: 503, json: {} })
      updated = true
      return route.fulfill({ json: { storage: 'postgres', incidentId: id, status: 'INVESTIGATING', version: 2 } })
    }
    if (route.request().url().includes('?')) return route.fulfill({ json: list(route) })
    const body = structuredClone(example)
    if (updated) Object.assign(body.record.incident, { status: 'INVESTIGATING', version: 2 })
    return route.fulfill({ json: body })
  })
  await open(page)
  await page.getByRole('button', { name: '转为 INVESTIGATING', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('HTTP 503')
  await expect(page.locator('[data-transition-pending]')).toContainText('提交结果待确认')
  await expect(page.getByRole('button', { name: '刷新 Incident', exact: true })).toBeDisabled()
  await page.getByRole('button', { name: '重试同一状态请求' }).click()
  await expect(page.locator('[data-incident-state]')).toContainText('INVESTIGATING · 版本 2')
  await expect(page.locator('[data-transition-pending]')).toHaveCount(0)
  expect(requests).toHaveLength(2); expect(requests[1]).toEqual(requests[0])
  expect(Object.keys(requests[0]!).sort()).toEqual(['expectedVersion', 'requestKey', 'target'])
})
test('a transition conflict clears stale detail and requires a fresh read', async ({ page }) => {
  await page.route('**/api/v1/**', route => route.request().method() === 'POST'
    ? route.fulfill({ status: 409, json: {} }) : route.fulfill({ json: route.request().url().includes('?') ? list(route) : example }))
  await open(page)
  await page.getByRole('button', { name: '转为 INVESTIGATING' }).click()
  await expect(page.getByRole('alert')).toContainText('状态或版本冲突')
  await expect(page.locator('[data-incident-detail]')).toHaveCount(0)
  await expect(page.locator('[data-transition-pending]')).toHaveCount(0)
  await expect(page.getByRole('button', { name: '刷新 Incident', exact: true })).toBeEnabled()
})
test('paginates and starts over after changing the status filter', async ({ page }) => {
  const calls: URL[] = []
  const rows = Array.from({ length: 26 }, (_, i) => ({ ...h, id: `${(i + 1).toString(16).padStart(8, '0')}-0000-0000-0000-000000000001`, title: `Incident ${i + 1}` }))
  await page.route('**/api/v1/incidents?**', route => {
    const url = new URL(route.request().url()); calls.push(url)
    const items = url.searchParams.get('status') || url.searchParams.get('after') ? [rows[25]!] : rows.slice(0, 25)
    return route.fulfill({ json: list(route, items, items.length === 25 ? items[24]!.id : null) })
  })
  await page.goto('/#/incidents')
  await page.getByRole('textbox', { name: '平台开发 Token（仅保存在当前标签页内存）' }).fill(TOKEN_OK)
  await page.getByRole('button', { name: '刷新 Incident', exact: true }).click()
  await expect(page.locator('[data-incident-page]')).toContainText('本页 25 条')
  await page.getByRole('button', { name: '下一页 Incident' }).click()
  await expect(page.getByRole('cell', { name: 'Incident 26', exact: true })).toBeVisible()
  expect(calls[1]!.searchParams.get('after')).toBe(rows[24]!.id)
  await page.getByRole('combobox', { name: 'Incident 状态' }).selectOption('OPEN')
  await expect(page.getByRole('table')).toHaveCount(0)
  await page.getByRole('button', { name: '刷新 Incident', exact: true }).click()
  await expect(page.locator('[data-incident-page]')).toContainText('第 1 页')
  expect(calls.at(-1)!.searchParams.get('after')).toBeNull()
  expect(calls.at(-1)!.searchParams.get('status')).toBe('OPEN')
})
test('revoked permissions and a changed identity clear list, detail and late responses', async ({ page }) => {
  let denied = false; let delayed = false; let release: (() => void) | undefined
  await page.route('**/api/v1/**', async route => {
    if (denied) return route.fulfill({ status: 403, json: {} })
    if (delayed) await new Promise<void>(resolve => { release = resolve })
    await route.fulfill({ json: route.request().url().includes('?') ? list(route) : example }).catch(() => {})
  })
  await open(page)
  denied = true
  await page.getByRole('button', { name: '刷新当前详情' }).click()
  await expect(page.getByRole('alert')).toContainText('HTTP 403')
  await expect(page.getByRole('table')).toHaveCount(0)
  await expect(page.locator('[data-incident-detail]')).toHaveCount(0)
  denied = false; delayed = true
  await page.getByRole('button', { name: '刷新 Incident', exact: true }).click()
  await expect.poll(() => Boolean(release)).toBe(true)
  await page.getByRole('textbox', { name: '平台开发 Token（仅保存在当前标签页内存）' }).fill(TOKEN_OK + '-new')
  release!()
  await expect(page.getByRole('table')).toHaveCount(0)
  await expect(page.locator('[data-incident-detail]')).toHaveCount(0)
  await expect(page.getByRole('alert')).toBeEmpty()
})
for (const invalid of ['wrong tenant', 'missing mode', 'missing gap', 'foreign timeline']) {
  test(`rejects ${invalid} in an incident response`, async ({ page }) => {
    const body = structuredClone(example)
    if (invalid === 'wrong tenant') body.record.problems[0].observation.tenantId = 'other'
    if (invalid === 'missing mode') delete body.record.problems[0].dataMode
    if (invalid === 'missing gap') body.record.gaps = []
    if (invalid === 'foreign timeline') body.record.timeline[0].problemEventId = '999'
    await page.route('**/api/v1/**', route => route.fulfill({ json: route.request().url().includes('?') ? list(route) : body }))
    await open(page)
    await expect(page.getByRole('alert')).not.toBeEmpty()
    await expect(page.locator('[data-incident-detail]')).toHaveCount(0)
  })
}
