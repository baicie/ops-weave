import { expect, test } from '@playwright/test'
import { TOKEN_OK } from './helpers.ts'

const host = {
  schemaVersion: '1.0',
  id: 'd72a8c09-458b-4098-b9d5-89e2d58a7d4f',
  tenantId: 'tenant-demo',
  entityType: 'host',
  name: 'Zabbix server',
  lifecycle: 'ACTIVE',
  version: 1,
  attributes: {
    hostId: '10084',
    ip: '10.0.0.10',
    status: 'enabled',
    source: 'zabbix',
    sourceInstanceId: 'zabbix-1',
    dataMode: 'labeled-fixture',
    pipelineId: 'zabbix-host-default',
    pipelineRevision: 1,
    pipelineDigest: 'sha256:' + 'a'.repeat(64),
    lastSeen: '2026-09-21T12:00:00Z',
    rawReference: 'raw-1',
  },
}

function body(url: string, items = [host], nextCursor: string | null = null) {
  const query = new URL(url).searchParams
  return { storage: 'postgres', query: { q: query.get('q') ?? '', type: query.get('type') ?? '',
    lifecycle: query.get('lifecycle') ?? '', after: query.get('after'), limit: Number(query.get('limit') ?? 25) }, items, nextCursor }
}

test.describe('inventory page', () => {
  test('syncs hosts without sending a tenant and keeps rows when a later sync fails', async ({ page }) => {
    const calls: { method: string; url: string; authorization: string }[] = []
    await page.route('**/api/v1/**', async route => {
      const request = route.request()
      calls.push({
        method: request.method(),
        url: request.url(),
        authorization: request.headers().authorization ?? '',
      })
      if (request.method() === 'POST' && request.url().includes('/hosts/sync')) {
        const syncs = calls.filter(call => call.method === 'POST').length
        if (syncs > 1) {
          await route.fulfill({
            status: 503,
            contentType: 'application/json',
            body: '{"error":"source_unavailable","failureCode":"SOURCE_FETCH_FAILED","pages":1}',
          })
          return
        }
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            accepted: 1,
            retired: 0,
            pages: 1,
            snapshotComplete: true,
            dataMode: 'labeled-fixture',
            inventoryStore: 'postgres',
          }),
        })
        return
      }
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(body(request.url())),
      })
    })

    await page.goto('/#/inventory')
    await expect(page.getByRole('heading', { name: '资产' })).toBeVisible()
    await page.getByRole('textbox', { name: '平台开发 Token（仅保存在当前标签页内存）' }).fill(TOKEN_OK)
    await page.getByRole('button', { name: '同步 Zabbix Host' }).click()
    await expect(page.getByRole('cell', { name: 'Zabbix server' })).toBeVisible()
    await expect(page.getByRole('cell', { name: '10084' })).toBeVisible()
    await expect(page.getByRole('cell', { name: '10.0.0.10' })).toBeVisible()
    await expect(page.getByText('labeled-fixture / postgres / pages 1')).toBeVisible()

    await page.getByRole('button', { name: '同步 Zabbix Host' }).click()
    await expect(page.getByRole('alert')).toContainText('HTTP 503 SOURCE_FETCH_FAILED，已扫描 1 页')
    await expect(page.getByRole('cell', { name: 'Zabbix server' })).toBeVisible()

    expect(calls.every(call => call.authorization === `Bearer ${TOKEN_OK}`)).toBe(true)
    expect(calls.every(call => !call.url.includes('tenant'))).toBe(true)
    expect(calls.some(call => call.method === 'POST')).toBe(true)
    expect(calls.some(call => call.method === 'GET')).toBe(true)
  })

  test('paginates on the server and resets cursors when filtering', async ({ page }) => {
    const calls: URL[] = []
    const rows = Array.from({ length: 26 }, (_, index) => ({ ...host, id: `${(index + 1).toString(16).padStart(8, '0')}-0000-0000-0000-000000000001`, name: `Host ${index + 1}` }))
    await page.route('**/api/v1/entities/page?**', async route => {
      const url = new URL(route.request().url()); calls.push(url)
      const items = url.searchParams.get('q') ? [rows[25]!] : url.searchParams.get('after') ? [rows[25]!] : rows.slice(0, 25)
      await route.fulfill({ json: body(url.href, items, items.length === 25 ? items[24]!.id : null) })
    })
    await page.goto('/#/inventory')
    await page.getByRole('textbox', { name: '平台开发 Token（仅保存在当前标签页内存）' }).fill(TOKEN_OK)
    await page.getByRole('button', { name: '刷新列表' }).click()
    await expect(page.locator('[data-inventory-page]')).toContainText('第 1 页 · 本页 25 条')
    await page.getByRole('button', { name: '下一页资产' }).click()
    await expect(page.getByRole('cell', { name: 'Host 26', exact: true })).toBeVisible()
    await expect(page.getByRole('button', { name: '下一页资产' })).toBeDisabled()
    expect(calls[1]!.searchParams.get('after')).toBe(rows[24]!.id)
    await page.getByRole('button', { name: '上一页资产' }).click()
    await expect(page.locator('[data-inventory-page]')).toContainText('第 1 页')
    await page.getByRole('textbox', { name: '名称或 IP' }).fill('Host 26')
    await expect(page.getByRole('table')).toHaveCount(0)
    await page.getByRole('combobox', { name: '生命周期' }).selectOption('ACTIVE')
    await page.getByRole('button', { name: '刷新列表' }).click()
    await expect(page.getByRole('cell', { name: 'Host 26', exact: true })).toBeVisible()
    expect(calls.at(-1)!.searchParams.get('after')).toBeNull()
    expect(calls.at(-1)!.searchParams.get('q')).toBe('Host 26')
    expect(calls.at(-1)!.searchParams.get('lifecycle')).toBe('ACTIVE')
  })

  test('reloads detail and clears both detail and rows after authorization is revoked', async ({ page }) => {
    let denied = false
    await page.route('**/api/v1/entities/**', async route => {
      if (denied) { await route.fulfill({ status: 403, json: { error: 'forbidden' } }); return }
      await route.fulfill({ json: route.request().url().includes('/page?') ? body(route.request().url()) : { ...host, version: 2 } })
    })
    await page.goto('/#/inventory')
    await page.getByRole('textbox', { name: '平台开发 Token（仅保存在当前标签页内存）' }).fill(TOKEN_OK)
    await page.getByRole('button', { name: '刷新列表' }).click()
    await page.getByRole('button', { name: '查看详情' }).click()
    await expect(page.locator('[data-entity-detail]')).toContainText('版本 2')
    await expect(page.locator('[data-entity-detail]')).toContainText('labeled-fixture')
    await expect(page.locator('[data-entity-detail]')).toContainText('zabbix-host-default@1')
    denied = true
    await page.getByRole('button', { name: '查看详情' }).click()
    await expect(page.getByRole('alert')).toContainText('HTTP 403')
    await expect(page.locator('[data-entity-detail]')).toHaveCount(0)
    await expect(page.getByRole('table')).toHaveCount(0)
  })

  test('changing identity discards an in-flight response and clears prior data', async ({ page }) => {
    let release: (() => void) | undefined
    let delayed = false
    await page.route('**/api/v1/entities/page?**', async route => {
      if (delayed) await new Promise<void>(resolve => { release = resolve })
      await route.fulfill({ json: body(route.request().url()) }).catch(() => {})
    })
    await page.goto('/#/inventory')
    const token = page.getByRole('textbox', { name: '平台开发 Token（仅保存在当前标签页内存）' })
    await token.fill(TOKEN_OK)
    await page.getByRole('button', { name: '刷新列表' }).click()
    await expect(page.getByRole('cell', { name: 'Zabbix server' })).toBeVisible()
    delayed = true
    await page.getByRole('button', { name: '刷新列表' }).click()
    await expect.poll(() => Boolean(release)).toBe(true)
    await token.fill('another-developer-token-32-characters-long')
    release!()
    await expect(page.getByRole('table')).toHaveCount(0)
    await expect(page.locator('[data-inventory-page]')).toHaveCount(0)
    await expect(page.getByRole('button', { name: '刷新列表' })).toBeEnabled()
  })

  test('rejects a response that exceeds the page limit', async ({ page }) => {
    await page.route('**/api/v1/entities/page?**', route => route.fulfill({ json: body(route.request().url(), Array(26).fill(host)) }))
    await page.goto('/#/inventory')
    await page.getByRole('textbox', { name: '平台开发 Token（仅保存在当前标签页内存）' }).fill(TOKEN_OK)
    await page.getByRole('button', { name: '刷新列表' }).click()
    await expect(page.getByRole('alert')).not.toBeEmpty()
    await expect(page.getByRole('table')).toHaveCount(0)
  })
})
