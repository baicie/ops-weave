// Read-only source self-check through the actual browser -> Java -> PostgreSQL path.
// Fixture mode stays labeled, reports no vendor version, and never falls back to a fake success.
import assert from 'node:assert/strict'
import { writeFile } from 'node:fs/promises'
import path from 'node:path'

export async function checkSourceConnection({ page, expect, request, browserJson, tenant, token, root }) {
  const listed = await request('/api/v1/integrations/zabbix/connection-checks?limit=5')
  assert.equal(listed.dataMode, 'connection-check')
  assert.equal(listed.tenantId, tenant)
  assert.equal(listed.sourceInstanceId, 'zabbix-1')
  assert.equal(listed.limit, 5)
  assert.ok(Array.isArray(listed.items))

  await page.getByRole('link', { name: '来源扫描', exact: true }).click()
  await page.getByRole('textbox', { name: '平台开发 Token（仅保存在当前标签页内存）' }).fill(token)
  const receiptResponse = page.waitForResponse(response => new URL(response.url()).pathname === '/api/v1/integrations/zabbix/connection-checks' && response.request().method() === 'POST')
  await page.getByRole('button', { name: '来源连接自检', exact: true }).click()
  const receipt = await browserJson(await receiptResponse)
  assert.equal(receipt.dataMode, 'connection-check')
  assert.equal(receipt.tenantId, tenant)
  assert.equal(receipt.sourceInstanceId, 'zabbix-1')
  assert.equal(receipt.check.reachable, true)
  assert.equal(receipt.check.statusCode, 'labeled-fixture')
  assert.equal(receipt.check.dataMode, 'labeled-fixture')
  assert.equal(receipt.check.reportedVersion, null)
  await expect(page.locator('[data-connection-check]')).toContainText('labeled-fixture')
  await expect(page.locator('[data-connection-check]')).toContainText('无版本声明')
  await expect(page.locator('[data-connection-check]')).toContainText(receipt.check.checkId)

  const pageResponse = page.waitForResponse(response => new URL(response.url()).pathname === '/api/v1/integrations/zabbix/connection-checks' && response.request().method() === 'GET')
  await page.getByRole('button', { name: '读取自检回执', exact: true }).click()
  const pageBody = await browserJson(await pageResponse)
  assert.equal(pageBody.dataMode, 'connection-check')
  assert.equal(pageBody.items[0].checkId, receipt.check.checkId)
  assert.equal(pageBody.items[0].reportedVersion, null)
  await expect(page.locator('[data-connection-check-list]')).toContainText(receipt.check.checkId)

  await page.screenshot({ path: path.join(root, '.tmp/metrics-acceptance/source-connection-check.png'), fullPage: true })
  await writeFile(path.join(root, '.tmp/metrics-acceptance/source-connection-check-receipt.json'), JSON.stringify(receipt, null, 2))
  await writeFile(path.join(root, '.tmp/metrics-acceptance/source-connection-check-page.json'), JSON.stringify(pageBody, null, 2))

  await page.getByRole('button', { name: '清除开发会话', exact: true }).click()
  assert.equal(await page.locator('[data-connection-check]').count(), 0)
  console.log('PASS: Browser authorized source self-check -> Java probe -> PostgreSQL receipt; fixture stays labeled with no vendor version, the bounded list reads it back, and credential clearing drops the receipt (no fallback to a fake success).')
}
