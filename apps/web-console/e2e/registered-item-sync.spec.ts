import { test, expect, type Page } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { TOKEN_OK } from './helpers.ts'

const sample = (name: string) => JSON.parse(readFileSync(new URL('../../../contracts/' + name, import.meta.url), 'utf8'))

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
  return { connectionRead, connection, instance, page, detail, receipt }
}

async function openSync(page: Page, mode: 'history' | 'error' = 'history') {
  const sourcePage = sample('source-center-page.json')
  const f = fixtures()
  let historyReads = 0
  let syncPosts = 0
  let detailReads = 0
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
  return { drawer, f, historyReads: () => historyReads, syncPosts: () => syncPosts, detailReads: () => detailReads, calls }
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
