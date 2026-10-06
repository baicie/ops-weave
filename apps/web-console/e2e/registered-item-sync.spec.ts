import { test, expect, type Page } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { createHash } from 'node:crypto'
import { TOKEN_OK } from './helpers.ts'

const sample = (name: string) => JSON.parse(readFileSync(new URL('../../../contracts/' + name, import.meta.url), 'utf8'))
const wireDigest = (parts: string[]) => { const hash = createHash('sha256'); for (const part of parts) { const bytes = Buffer.from(part); hash.update(`${bytes.byteLength}:`); hash.update(bytes) } return 'sha256:' + hash.digest('hex') }
const localInput = (epochSeconds: number) => new Date(epochSeconds * 1000 - new Date(epochSeconds * 1000).getTimezoneOffset() * 60000).toISOString().slice(0, 16)

function fixtures() {
  const connectionRead = sample('examples/v2/source-connection-read.json')
  const connection = sample('examples/v2/source-connection-configuration-scoped.json')
  const instance = structuredClone(connectionRead.instance)
  Object.assign(instance, {
    configurationRevision: connection.revision,
    connectionDigest: connection.connectionDigest,
    dataMode: 'zabbix-jsonrpc',
  })
  connectionRead.instance = instance
  connectionRead.connection = connection
  const page = sample('examples/v2/registered-item-scan-run-page.json')
  page.sourceId = instance.id
  page.sourceInstanceId = instance.source.instanceId
  page.configurationRevision = connection.revision
  page.connectionDigest = connection.connectionDigest
  page.hostGroupIds = connection.hostGroupIds
  for (const run of page.items) {
    run.sourceId = instance.id
    run.configurationRevision = connection.revision
    run.connectionDigest = connection.connectionDigest
  }
  const detail = sample('examples/v2/registered-item-scan-run-read.json')
  detail.sourceId = instance.id
  detail.sourceInstanceId = instance.source.instanceId
  detail.configurationRevision = connection.revision
  detail.connectionDigest = connection.connectionDigest
  detail.hostGroupIds = connection.hostGroupIds
  detail.run = structuredClone(page.items[0])
  const receipt = sample('examples/v2/registered-item-sync.json')
  receipt.sourceId = instance.id
  receipt.sourceInstanceId = instance.source.instanceId
  receipt.configurationRevision = connection.revision
  receipt.connectionDigest = connection.connectionDigest
  receipt.hostGroupIds = connection.hostGroupIds
  receipt.syncRunId = detail.run.syncRunId
  const problemPage = sample('examples/v2/registered-problem-page.json')
  problemPage.sourceId = instance.id
  problemPage.sourceInstanceId = instance.source.instanceId
  problemPage.configurationRevision = connection.revision
  problemPage.connectionDigest = connection.connectionDigest
  problemPage.hostGroupIds = connection.hostGroupIds
  problemPage.scopeDigest = wireDigest(['registered-problem-scope-v1', instance.id, String(connection.revision), connection.connectionDigest, ...connection.hostGroupIds])
  const problemRows = Array.from({ length: 25 }, (_, index) => ({ ...structuredClone(problemPage.items[0]), sourceInstanceId: instance.source.instanceId, problemEventId: String(7001 + index), triggerId: String(8001 + index) }))
  problemPage.items = problemRows
  problemPage.query = { from: 0, till: 0, afterEventId: null, limit: 25 }
  problemPage.nextAfterEventId = '7025'
  const problemLast = structuredClone(problemPage)
  problemLast.items = [{ ...structuredClone(problemRows[0]), problemEventId: '7026', triggerId: '8026' }]
  problemLast.nextAfterEventId = null
  problemLast.query = { from: 0, till: 0, afterEventId: '7025', limit: 25 }
  return { connectionRead, connection, instance, page, detail, receipt, problemPage, problemLast }
}

async function openSync(page: Page, mode: 'history' | 'error' | 'problem-error' = 'history') {
  const sourcePage = sample('examples/source-center-page.json')
  const f = fixtures()
  let historyReads = 0
  let syncPosts = 0
  let detailReads = 0
  let problemReads = 0
  const calls: string[] = []
  await page.route('**/api/v1/integrations/sources', route => route.fulfill({ json: sourcePage }))
  await page.route('**/api/v2/data-sources**', async route => {
    const request = route.request()
    const url = new URL(request.url())
    const path = url.pathname
    calls.push(request.method() + ' ' + path + (url.search ? url.search : ''))
    if (path === '/api/v2/data-sources' && request.method() === 'GET') return route.fulfill({ json: { schemaVersion: '2.0', storage: 'memory', items: [f.instance], truncated: false } })
    if (path === `/api/v2/data-sources/${f.instance.id}` && request.method() === 'GET') return route.fulfill({ json: { schemaVersion: '2.0', instance: f.instance } })
    if (path === `/api/v2/data-sources/${f.instance.id}/connection` && request.method() === 'GET') return route.fulfill({ json: f.connectionRead })
    if (path.endsWith('/items/runs') && request.method() === 'GET') {
      historyReads++
      return route.fulfill({ json: f.page })
    }
    if (path.endsWith('/items/sync') && request.method() === 'POST') {
      syncPosts++
      if (mode === 'error') return route.fulfill({ status: 503, json: { error: 'SOURCE_UNAVAILABLE' } })
      return route.fulfill({ json: f.receipt })
    }
    if (path.endsWith('/problems') && request.method() === 'GET') {
      problemReads++
      if (mode === 'problem-error') return route.fulfill({ status: 503, json: { error: 'SOURCE_UNAVAILABLE' } })
      const after = url.searchParams.get('afterEventId')
      const result = structuredClone(after ? f.problemLast : f.problemPage)
      const from = Number(url.searchParams.get('from'))
      const till = Number(url.searchParams.get('till'))
      const happenedAt = new Date(Math.min(till, from + 1) * 1000).toISOString()
      result.items.forEach(item => { item.occurredAt = happenedAt; item.observedAt = happenedAt })
      result.query.from = from
      result.query.till = till
      result.query.afterEventId = after
      return route.fulfill({ json: result })
    }
    if (path.includes('/items/runs/') && request.method() === 'GET') {
      detailReads++
      return route.fulfill({ json: f.detail })
    }
    return route.fulfill({ status: 404, json: { error: 'NOT_FOUND' } })
  })
  await page.goto('/#/integrations/sources')
  await page.getByRole('textbox', { name: '平台开发 Token（仅保存在当前标签页内存）' }).fill(TOKEN_OK)
  await expect(page.getByRole('table', { name: '接入实例列表' })).toContainText(f.instance.name)
  await page.getByRole('button', { name: '维护实例：' + f.instance.name, exact: true }).click()
  const drawer = page.getByRole('dialog', { name: '维护接入实例' })
  await expect(drawer).toBeVisible()
  await drawer.getByRole('tab', { name: '指标同步', exact: true }).click()
  await expect(drawer.getByRole('table', { name: '指标扫描历史' })).toBeVisible()
  return { drawer, f, historyReads: () => historyReads, syncPosts: () => syncPosts, detailReads: () => detailReads, problemReads: () => problemReads, calls }
}

test('registered item sync reads history once, opens detail, and confirms a manual sync', async ({ page }) => {
  const f = await openSync(page)
  await expect(f.drawer.getByRole('table', { name: '指标扫描历史' })).toContainText('817')
  expect(f.historyReads()).toBe(1)
  await f.drawer.getByRole('button', { name: '查看', exact: true }).click()
  await expect(f.drawer.getByRole('region', { name: '指标扫描详情' })).toContainText('820')
  expect(f.detailReads()).toBe(1)
  await f.drawer.getByRole('button', { name: '关闭详情', exact: true }).click()
  await f.drawer.getByRole('button', { name: '同步指标目录', exact: true }).click()
  await expect(f.drawer.locator('.registered-item-sync [role="status"]')).toContainText('本次扫描已完成并记录回执。')
  expect(f.syncPosts()).toBe(1)
  expect(f.historyReads()).toBe(2)
})

test('registered item sync keeps the existing history after an unavailable request', async ({ page }) => {
  const f = await openSync(page, 'error')
  await f.drawer.getByRole('button', { name: '同步指标目录', exact: true }).click()
  await expect(f.drawer.getByRole('alert')).toContainText('刷新扫描历史可核对服务端结果；不会自动重试。')
  await expect(f.drawer.getByRole('table', { name: '指标扫描历史' })).toContainText('817')
  expect(f.syncPosts()).toBe(1)
  expect(f.historyReads()).toBe(1)
})

test('registered connection problem pages keep the fixed scope while paging', async ({ page }) => {
  const f = await openSync(page)
  await f.drawer.getByRole('tab', { name: '问题读取', exact: true }).click()
  const table = f.drawer.getByRole('table', { name: '登记连接问题列表' })
  await expect(table).toBeVisible()
  await expect(table).toContainText('7001')
  await expect(f.drawer).toContainText('主机组范围')
  expect(f.problemReads()).toBe(1)
  await f.drawer.getByRole('button', { name: '下一页问题', exact: true }).click()
  await expect(table).toContainText('7026')
  expect(f.problemReads()).toBe(2)
  const problemRequests = f.calls.filter(call => call.includes('/problems?'))
  expect(problemRequests[0]).toContain('limit=25')
  expect(problemRequests[0]).not.toContain('afterEventId=')
  expect(problemRequests[1]).toContain('afterEventId=7025')
  const initial = new URL('http://localhost' + problemRequests[0].slice(problemRequests[0].indexOf('/api')))
  const next = new URL('http://localhost' + problemRequests[1].slice(problemRequests[1].indexOf('/api')))
  expect(next.searchParams.get('from')).toBe(initial.searchParams.get('from'))
  expect(next.searchParams.get('till')).toBe(initial.searchParams.get('till'))

  const tillInput = f.drawer.getByLabel('结束时间')
  const selectedTill = Math.floor(Date.parse(await tillInput.inputValue()) / 1000) - 300
  const selectedFrom = selectedTill - 600
  await f.drawer.getByLabel('开始时间').fill(localInput(selectedFrom))
  await tillInput.fill(localInput(selectedTill))
  await f.drawer.getByRole('button', { name: '按时间窗刷新', exact: true }).click()
  await expect(table).toContainText('7001')
  expect(f.problemReads()).toBe(3)
  const requests = f.calls.filter(call => call.includes('/problems?'))
  const refreshed = new URL('http://localhost' + requests[2].slice(requests[2].indexOf('/api')))
  expect(refreshed.searchParams.get('from')).toBe(String(selectedFrom))
  expect(refreshed.searchParams.get('till')).toBe(String(selectedTill))
  expect(refreshed.searchParams.has('afterEventId')).toBe(false)
})

test('registered connection problem 503 does not retry until explicit refresh', async ({ page }) => {
  const f = await openSync(page, 'problem-error')
  await f.drawer.getByRole('tab', { name: '问题读取', exact: true }).click()
  await expect(f.drawer.getByRole('alert')).toContainText('不会自动重试')
  await page.waitForTimeout(100)
  expect(f.problemReads()).toBe(1)
  await f.drawer.getByRole('button', { name: '按时间窗刷新', exact: true }).click()
  expect(f.problemReads()).toBe(2)
})
