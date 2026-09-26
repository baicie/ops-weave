import { expect, test, type Page } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { TOKEN_OK } from './helpers.ts'
import type { ReorganizationRequest } from '../src/api/reorganizations.ts'

// Explicit HTTP fixtures. The separate stack acceptance runs actual PostgreSQL requests.
const example = JSON.parse(readFileSync(new URL('../../../contracts/examples/incident-detail.json', import.meta.url), 'utf8'))
const sourceId = example.record.incident.id as string
const targetId = '88888888-8888-4888-8888-888888888888'
const tokenBox = (page: Page) => page.getByRole('textbox', { name: '平台开发 Token（仅保存在当前标签页内存）' })
function detail(id: string, two = false) {
  const value = structuredClone(example); value.record.incident.id = id
  if (two) {
    const other = structuredClone(value.record.problems[0]); other.observation.problemEventId = '30002'
    other.observation.recoveryEventId = '30004'; value.record.problems.push(other)
    value.record.timeline.push(...value.record.timeline.map((event: { id: string; problemEventId: string; recoveryEventId: string | null }, i: number) => ({ ...event,
      id: `99999999-9999-4999-8999-99999999999${i}`, problemEventId: '30002', recoveryEventId: event.recoveryEventId ? '30004' : null })))
  }
  return value
}
function receipt(request: ReorganizationRequest) {
  return { storage: 'postgres', change: { schemaVersion: '1.0', request, actor: 'fixture-operator', sourceVersion: request.expectedSourceVersion + 1,
    targetVersion: request.expectedTargetVersion + 1, movedProblems: request.kind === 'MERGE' ? [{ sourceInstanceId: 'zabbix-1', problemEventId: '30001' }] : request.problemKeys,
    occurredAt: '2026-09-25T09:00:00Z' } }
}
async function open(page: Page, merge = true) {
  await page.goto(`/#/incidents/reorganize?incidentId=${sourceId}`); await tokenBox(page).fill(TOKEN_OK)
  await page.getByRole('button', { name: '读取来源 Incident', exact: true }).click()
  await expect(page.locator('[data-reorganization-source]')).toBeVisible()
  if (merge) {
    await page.getByRole('textbox', { name: '目标 Incident ID' }).fill(targetId)
    await page.getByRole('button', { name: '读取目标 Incident', exact: true }).click()
    await expect(page.locator('[data-reorganization-target]')).toBeVisible()
  } else await page.getByRole('combobox', { name: '调整方式' }).selectOption('SPLIT')
  await page.getByRole('textbox', { name: '人工调整原因' }).fill('Fixture operator reviewed the grouping')
}
test('reviews pinned versions before merge, retries the same request and recovers its receipt after reload', async ({ page }) => {
  const writes: ReorganizationRequest[] = []; const auth: string[] = []
  await page.route('**/api/v1/**', route => {
    auth.push(route.request().headers().authorization ?? '')
    if (route.request().method() === 'POST') {
      writes.push(route.request().postDataJSON()); return writes.length === 1 ? route.fulfill({ status: 503, json: {} }) : route.fulfill({ json: receipt(writes[0]!) })
    }
    return route.fulfill({ json: route.request().url().includes('/reorganizations/') ? receipt(writes[0]!) : detail(route.request().url().endsWith(targetId) ? targetId : sourceId) })
  })
  await open(page); await page.getByRole('button', { name: '预览关联调整' }).click()
  await expect(page.locator('[data-reorganization-review]')).toContainText('版本 1'); expect(writes).toHaveLength(0)
  await page.getByRole('button', { name: '确认关联调整' }).click()
  await expect(page.locator('[data-reorganization-pending]')).toBeVisible()
  await expect(page.getByRole('textbox', { name: '人工调整原因' })).toBeDisabled()
  await page.getByRole('button', { name: '重试同一关联请求' }).click()
  await expect(page.locator('[data-reorganization-result]')).toContainText('fixture-operator')
  expect(writes).toHaveLength(2); expect(writes[1]).toEqual(writes[0])
  expect(writes[0]).toMatchObject({ kind: 'MERGE', sourceIncidentId: sourceId, targetIncidentId: targetId, expectedSourceVersion: 1, expectedTargetVersion: 1, problemKeys: [], title: null })
  expect(Object.keys(writes[0]!).sort()).toEqual(['expectedSourceVersion', 'expectedTargetVersion', 'kind', 'problemKeys', 'reason', 'requestKey', 'sourceIncidentId', 'targetIncidentId', 'title'])
  await page.reload(); await expect(page.locator('[data-reorganization-result]')).toHaveCount(0); await expect(tokenBox(page)).toHaveValue('')
  await tokenBox(page).fill(TOKEN_OK); await page.getByRole('button', { name: '读取关联记录', exact: true }).click()
  await expect(page.locator('[data-reorganization-result]')).toContainText('fixture-operator'); expect(writes).toHaveLength(2)
  await tokenBox(page).fill(''); await expect(page.locator('[data-reorganization-result]')).toHaveCount(0)
  expect(auth.every(a => a === `Bearer ${TOKEN_OK}`)).toBe(true)
})
test('split preserves at least one problem and pins a newly generated target at version zero', async ({ page }) => {
  const writes: ReorganizationRequest[] = []
  await page.route('**/api/v1/**', route => {
    if (route.request().method() === 'POST') { writes.push(route.request().postDataJSON()); return route.fulfill({ json: receipt(writes[0]!) }) }
    return route.fulfill({ json: detail(sourceId, true) })
  })
  await open(page, false); await page.getByRole('textbox', { name: '新 Incident 标题' }).fill('<img src=x onerror=alert(1)> separate scope')
  await page.getByRole('checkbox').nth(0).check(); await page.getByRole('checkbox').nth(1).check()
  await page.getByRole('button', { name: '预览关联调整' }).click(); await expect(page.getByRole('alert')).toContainText('来源至少保留一个问题')
  await expect(page.locator('[data-reorganization-review]')).toHaveCount(0)
  await page.getByRole('checkbox').nth(1).uncheck(); await page.getByRole('button', { name: '预览关联调整' }).click()
  expect(writes).toHaveLength(0); await page.getByRole('button', { name: '确认关联调整' }).click()
  await expect(page.locator('[data-reorganization-result]')).toContainText('SPLIT')
  expect(writes[0]).toMatchObject({ kind: 'SPLIT', expectedTargetVersion: 0, problemKeys: [{ sourceInstanceId: 'zabbix-1', problemEventId: '30001' }] })
  expect(writes[0]!.targetIncidentId).not.toBe(sourceId); await expect(page.locator('[data-page="reorganization"] img')).toHaveCount(0)
})
test('a conflict discards stale previews and requires both Incident versions to be reread', async ({ page }) => {
  await page.route('**/api/v1/**', route => route.request().method() === 'POST' ? route.fulfill({ status: 409, json: {} })
    : route.fulfill({ json: detail(route.request().url().endsWith(targetId) ? targetId : sourceId) }))
  await open(page); await page.getByRole('button', { name: '预览关联调整' }).click(); await page.getByRole('button', { name: '确认关联调整' }).click()
  await expect(page.getByRole('alert')).toContainText('版本、归属或请求内容冲突')
  for (const section of ['source', 'target', 'review', 'pending']) await expect(page.locator(`[data-reorganization-${section}]`)).toHaveCount(0)
  await expect(page.getByRole('button', { name: '预览关联调整' })).toBeDisabled()
})
test('merged archives cannot be previewed as an active source', async ({ page }) => {
  await page.route('**/api/v1/**', route => { const value = detail(route.request().url().endsWith(targetId) ? targetId : sourceId)
    if (value.record.incident.id === sourceId) value.record.organization = { version: 1, changeId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', mergedInto: targetId }
    return route.fulfill({ json: value })
  })
  await open(page); await page.getByRole('button', { name: '预览关联调整' }).click()
  await expect(page.getByRole('alert')).toContainText('请读取可调整的来源 Incident'); await expect(page.locator('[data-reorganization-review]')).toHaveCount(0)
})
test('ignores a late response after the identity changes', async ({ page }) => {
  let release!: () => void; const gate = new Promise<void>(resolve => { release = resolve })
  let arrived!: () => void; const arrival = new Promise<void>(resolve => { arrived = resolve })
  await page.route('**/api/v1/**', async route => { arrived(); await gate; await route.fulfill({ json: detail(sourceId) }) })
  await page.goto(`/#/incidents/reorganize?incidentId=${sourceId}`); await tokenBox(page).fill(TOKEN_OK)
  await page.getByRole('button', { name: '读取来源 Incident' }).click(); await arrival; await tokenBox(page).fill(''); release()
  await expect(page.locator('[data-reorganization-source]')).toHaveCount(0); await expect(page.getByRole('button', { name: '预览关联调整' })).toBeDisabled()
})
test('pages authorized history, treats reasons as text and rejects a response for another Incident', async ({ page }) => {
  const history = Array.from({ length: 21 }, (_, i) => receipt({ requestKey: `aaaaaaaa-aaaa-4aaa-8aaa-${String(i + 1).padStart(12, '0')}`, kind: 'MERGE', sourceIncidentId: sourceId,
    targetIncidentId: targetId, expectedSourceVersion: 1, expectedTargetVersion: 1, problemKeys: [], title: null, reason: '<img src=x onerror=alert(1)> fixture history' }).change)
  const cursors: (string | null)[] = []; let invalid = false
  await page.route('**/api/v1/**', route => { const url = new URL(route.request().url()); const after = url.searchParams.get('after'); cursors.push(after)
    return route.fulfill({ json: { storage: 'postgres', incidentId: invalid ? targetId : sourceId, after, limit: 20, items: after ? history.slice(20) : history.slice(0, 20), nextCursor: after ? null : history[19]!.request.requestKey } })
  })
  await page.goto(`/#/incidents/reorganize?incidentId=${sourceId}`); await tokenBox(page).fill(TOKEN_OK)
  await page.getByRole('button', { name: '读取关联历史' }).click(); await expect(page.locator('[data-reorganization-history] article')).toHaveCount(20)
  await expect(page.locator('[data-reorganization-history] img')).toHaveCount(0)
  await page.getByRole('button', { name: '下一页关联历史' }).click(); await expect(page.locator('[data-reorganization-history] article')).toHaveCount(1)
  expect(cursors).toEqual([null, history[19]!.request.requestKey]); await expect(page.getByRole('button', { name: '下一页关联历史' })).toBeDisabled()
  await tokenBox(page).fill(''); await expect(page.locator('[data-reorganization-history]')).toHaveCount(0)
  invalid = true; await tokenBox(page).fill(TOKEN_OK); await page.getByRole('button', { name: '读取关联历史' }).click()
  await expect(page.getByRole('alert')).toContainText('响应结构或范围不正确'); await expect(page.locator('[data-reorganization-history]')).toHaveCount(0)
})
