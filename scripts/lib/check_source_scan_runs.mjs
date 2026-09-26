// Read-only trace of already stored scans: actual browser -> Java -> PostgreSQL.
// No source is contacted, no scan is retried and nothing is repaired or reconciled.
import assert from 'node:assert/strict'
import { writeFile } from 'node:fs/promises'
import path from 'node:path'

export async function checkSourceScanRuns({ page, expect, request, browserJson, tenant, token, root, hostRunId }) {
  assert(hostRunId, 'A stored host scan is required for the trace check')
  const stored = await request(`/api/v1/integrations/zabbix/hosts/runs/${hostRunId}`)
  assert.equal(stored.dataMode, 'scan-log')
  assert.equal(stored.tenantId, tenant)
  assert.equal(stored.sourceInstanceId, 'zabbix-1')
  assert.equal(stored.run.syncRunId, hostRunId)
  assert.equal(stored.run.objectType, 'host')
  assert.equal(stored.run.status, 'SUCCEEDED')
  assert.equal(stored.run.snapshotComplete, true)
  assert.equal(stored.run.dataMode, 'labeled-fixture')
  assert.equal(stored.run.scanConsistency, 'hostid-watermark-snapshot')
  assert.ok(stored.run.pipelineVersion && stored.run.pipelineVersion.id, 'the stored scan keeps its pinned mapping version')
  const itemRuns = await request('/api/v1/integrations/zabbix/items/runs')
  assert.equal(itemRuns.dataMode, 'scan-log')
  assert.equal(itemRuns.objectType, 'item')
  assert.ok(itemRuns.items.length >= 1, 'the item sync of this acceptance run is stored')
  assert.ok(itemRuns.items.every(item => item.objectType === 'item'), 'item scans never mix host runs')
  assert.ok(itemRuns.items.every(item => item.scanConsistency === 'itemid-watermark-snapshot'),
    'item scans report their own itemid watermark bound')
  assert.ok(!itemRuns.items.some(item => item.syncRunId === hostRunId), 'the host run never appears in the item trace')

  await page.getByRole('link', { name: '来源扫描', exact: true }).click()
  await page.getByRole('textbox', { name: '平台开发 Token（仅保存在当前标签页内存）' }).fill(token)
  const listResponse = page.waitForResponse(response => new URL(response.url()).pathname === '/api/v1/integrations/zabbix/hosts/runs' && response.request().method() === 'GET')
  await page.getByRole('button', { name: '读取扫描运行', exact: true }).click()
  const list = await browserJson(await listResponse)
  assert.equal(list.dataMode, 'scan-log')
  assert.equal(list.objectType, 'host')
  assert.ok(list.items.some(item => item.syncRunId === hostRunId), 'the stored scan is listed')
  await expect(page.locator(`[data-scan-run-id="${hostRunId}"]`)).toContainText('SUCCEEDED')
  await expect(page.locator('[data-scan-run-list]')).toContainText('labeled-fixture')
  await expect(page.locator('[data-scan-run-list]')).toContainText('边界 已验证 hostid 水位快照')

  const itemResponse = page.waitForResponse(response => new URL(response.url()).pathname === '/api/v1/integrations/zabbix/items/runs' && response.request().method() === 'GET')
  await page.getByRole('button', { name: 'Item 扫描', exact: true }).click()
  await page.getByRole('button', { name: '读取扫描运行', exact: true }).click()
  const items = await browserJson(await itemResponse)
  assert.equal(items.objectType, 'item')
  assert.ok(items.items.length >= 1, 'the item sync is listed on its own trace')
  await expect(page.locator(`[data-scan-run-id="${items.items[0].syncRunId}"]`)).toContainText('item · ')
  await expect(page.locator(`[data-scan-run-id="${hostRunId}"]`)).toHaveCount(0)

  await page.getByRole('button', { name: 'Host 扫描', exact: true }).click()
  await page.getByRole('button', { name: '读取扫描运行', exact: true }).click()
  await expect(page.locator(`[data-scan-run-id="${hostRunId}"]`)).toBeVisible()

  await page.getByRole('textbox', { name: '扫描运行标识' }).fill(hostRunId)
  const readResponse = page.waitForResponse(response => new URL(response.url()).pathname === `/api/v1/integrations/zabbix/hosts/runs/${hostRunId}` && response.request().method() === 'GET')
  await page.getByRole('button', { name: '查询扫描运行', exact: true }).click()
  const read = await browserJson(await readResponse)
  assert.equal(read.run.syncRunId, hostRunId)
  assert.equal(read.run.status, 'SUCCEEDED')
  await expect(page.locator('[data-scan-run-read]')).toContainText(hostRunId)
  await expect(page.locator('[data-scan-run-read]')).toContainText('labeled-fixture')
  await expect(page.locator('[data-scan-run-read]')).toContainText('边界 已验证 hostid 水位快照')
  await page.screenshot({ path: path.join(root, '.tmp/metrics-acceptance/source-scan-runs.png'), fullPage: true })
  await writeFile(path.join(root, '.tmp/metrics-acceptance/source-scan-run-page.json'), JSON.stringify(list, null, 2))
  await writeFile(path.join(root, '.tmp/metrics-acceptance/source-scan-run-read.json'), JSON.stringify(read, null, 2))

  await page.getByRole('button', { name: '清除开发会话', exact: true }).click()
  assert.equal(await page.locator('[data-scan-run-read]').count(), 0)
  console.log('PASS: Browser authorized scan trace -> PostgreSQL stored runs with newest-first page and pinned mapping version -> explicit lookup/refresh; item scans stay separate and credential clearing drops the trace (labeled fixture, no source contact).')
}
