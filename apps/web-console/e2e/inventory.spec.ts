import { expect, test } from '@playwright/test'
import { TOKEN_OK } from './helpers.ts'

const host = {
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
    lastSeen: '2026-09-21T12:00:00Z',
    rawReference: 'raw-1',
  },
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
          await route.fulfill({ status: 503, contentType: 'application/json', body: '{"error":"source_unavailable"}' })
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
        body: JSON.stringify({ items: [host] }),
      })
    })

    await page.goto('/#/inventory')
    await expect(page.getByRole('heading', { name: '资产' })).toBeVisible()
    await page.getByRole('textbox', { name: '平台开发 Token（仅保存在当前页面内存）' }).fill(TOKEN_OK)
    await page.getByRole('button', { name: '同步 Zabbix Host' }).click()
    await expect(page.getByRole('cell', { name: 'Zabbix server' })).toBeVisible()
    await expect(page.getByRole('cell', { name: '10084' })).toBeVisible()
    await expect(page.getByRole('cell', { name: '10.0.0.10' })).toBeVisible()
    await expect(page.getByText('labeled-fixture / postgres / pages 1')).toBeVisible()

    await page.getByRole('button', { name: '同步 Zabbix Host' }).click()
    await expect(page.getByRole('alert')).toContainText('HTTP 503')
    await expect(page.getByRole('cell', { name: 'Zabbix server' })).toBeVisible()

    expect(calls.every(call => call.authorization === `Bearer ${TOKEN_OK}`)).toBe(true)
    expect(calls.every(call => !call.url.includes('tenant'))).toBe(true)
    expect(calls.some(call => call.method === 'POST')).toBe(true)
    expect(calls.some(call => call.method === 'GET')).toBe(true)
  })
})
