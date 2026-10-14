import { test, expect, type Page } from '@playwright/test'
import { createHash, randomUUID } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { TOKEN_OK } from './helpers.ts'

const sample = (name: string) => JSON.parse(readFileSync(new URL('../../../contracts/examples/' + name, import.meta.url), 'utf8'))
function digest(parts: string[]) {
  const h = createHash('sha256')
  for (const part of parts) { const bytes = Buffer.from(part, 'utf8'); h.update(Buffer.from(String(bytes.length) + ':')); h.update(bytes) }
  return 'sha256:' + h.digest('hex')
}

type Fixture = {
  id: string
  instance: Record<string, unknown>
  connection: Record<string, unknown>
  receipts: { inspection: Record<string, unknown>; validity: string }[]
}

function fixtureInstance(label: string, endpointId: string, address: string, credentialId: string, revision: number, groups: string[]): Fixture {
  const id = randomUUID().toLowerCase()
  const endpointDigest = digest(['source-endpoint-v2', endpointId, 'ZABBIX_HOST', address])
  const connectionDigest = digest(['source-connection-v3', id, 'host-jsonrpc-v2', endpointId, endpointDigest, credentialId, '1', credentialId, String(groups.length), ...groups])
  const at = new Date(Date.now() - 60000).toISOString()
  const instance = {
    id, name: label, description: 'OW-ST02 e2e synthetic instance',
    source: { kind: 'ZABBIX_HOST', instanceId: 'connection-' + id },
    configurationRevision: revision, connectionDigest, dataMode: 'zabbix-jsonrpc',
    editVersion: revision, state: 'ACTIVE', createdAt: at, updatedAt: at, workflowId: 'source-' + id
  }
  const connection = {
    sourceId: id, revision, connectorVersion: 'host-jsonrpc-v2',
    endpoint: { id: endpointId, name: 'Fixture registered endpoint ' + endpointId, connectorKind: 'ZABBIX_HOST', address, digest: endpointDigest },
    credentialPin: { credentialId, revision: 1, versionId: credentialId },
    hostGroupIds: groups, connectionDigest, createdAt: at
  }
  return { id, instance, connection, receipts: [] }
}

function testReceipt(f: Fixture, revision: number, connectionDigest: string, reportedVersion: string, validity: string) {
  const requestId = randomUUID().toLowerCase(), start = Date.now() - 30000
  f.receipts.push({
    validity,
    inspection: {
      requestId, sourceId: f.id, kind: 'TEST', configurationRevision: revision, connectionDigest,
      dataMode: 'zabbix-jsonrpc',
      commandDigest: digest(['source-inspection-v2', f.id, requestId, 'TEST', String(revision), connectionDigest]),
      state: 'COMPLETED',
      asOf: new Date(start).toISOString(), deadline: new Date(start + 65000).toISOString(),
      availableAt: new Date(start).toISOString(), expiresAt: new Date(start + 900000).toISOString(),
      check: { reachable: true, statusCode: 'READ_VERIFIED', reportedVersion }, discovery: null
    }
  })
}

async function fixture(page: Page) {
  const addressA = 'http://127.0.0.1:18301/api_jsonrpc.php', addressB = 'http://127.0.0.1:18302/api_jsonrpc.php'
  const a = fixtureInstance('Fixture multi instance A', 'fixture-multi-a', addressA, randomUUID().toLowerCase(), 2, ['1001', '1002'])
  const b = fixtureInstance('Fixture multi instance B', 'fixture-multi-b', addressB, randomUUID().toLowerCase(), 1, ['2001'])
  // Instance A keeps one receipt recorded under its previous configuration: the record is
  // preserved with its own connection digest, while its validity is no longer current.
  const oldCredential = '20000000-0000-4000-8000-0000000000a1'
  const oldDigestA = digest(['source-connection-v3', a.id, 'host-jsonrpc-v2', 'fixture-multi-a', digest(['source-endpoint-v2', 'fixture-multi-a', 'ZABBIX_HOST', addressA]), oldCredential, '1', oldCredential, '1', '1001'])
  testReceipt(a, 1, oldDigestA, '7.0.0-a-old', 'STALE')
  testReceipt(a, 2, a.connection.connectionDigest as string, '7.0.0-a', 'CURRENT')
  testReceipt(b, 1, b.connection.connectionDigest as string, '7.0.0-b', 'CURRENT')
  const calls: string[] = []
  await page.route('**/api/v1/integrations/sources', route => route.fulfill({ json: sample('source-center-page.json') }))
  await page.route('**/api/v2/data-sources**', route => {
    const path = new URL(route.request().url()).pathname, method = route.request().method()
    calls.push(method + ' ' + path)
    const known = [a, b].find(f => path.includes('/' + f.id))
    if (method === 'GET' && path === '/api/v2/data-sources') return route.fulfill({ json: { schemaVersion: '2.0', storage: 'memory', items: [a.instance, b.instance], truncated: false } })
    if (known && path.endsWith('/connection')) return route.fulfill({ json: { schemaVersion: '2.0', storage: 'memory', instance: known.instance, connection: known.connection, availability: 'AVAILABLE', canConfigure: true } })
    if (known && path.endsWith('/inspections')) return route.fulfill({ json: { schemaVersion: '2.0', storage: 'memory', sourceId: known.id, items: known.receipts } })
    if (known && path.endsWith('/' + known.id)) return route.fulfill({ json: { schemaVersion: '2.0', instance: known.instance } })
    return route.fulfill({ json: { schemaVersion: '2.0', storage: 'memory', sourceId: known?.id ?? null, items: [] } })
  })
  await page.goto('/#/integrations/sources')
  await page.getByRole('textbox', { name: /平台开/ }).fill(TOKEN_OK)
  await page.getByRole('tab', { name: /已配置接入/ }).click()
  return { a, b, calls }
}

test('same vendor instances keep separate identities and their own receipts', async ({ page }) => {
  const f = await fixture(page)
  const table = page.getByRole('table', { name: '接入实例列表' })
  await expect(table).toContainText('Fixture multi instance A')
  await expect(table).toContainText('Fixture multi instance B')
  await expect(table).toContainText('connection-' + f.a.id)
  await expect(table).toContainText('connection-' + f.b.id)

  await page.getByRole('button', { name: '维护实例：Fixture multi instance A', exact: true }).click()
  const drawer = page.getByRole('dialog', { name: '维护接入实例' })
  await expect(drawer).toBeVisible()
  await drawer.getByRole('tab', { name: '连接与字段', exact: true }).click()
  await expect(drawer).toContainText('来源报告版本 7.0.0-a')
  await expect(drawer).not.toContainText('来源报告版本 7.0.0-b')
  expect(f.calls.filter(call => call.endsWith('/inspections'))).toEqual(['GET /api/v2/data-sources/' + f.a.id + '/inspections'])
  await drawer.getByRole('button', { name: '关闭实例维护' }).click()

  await page.getByRole('button', { name: '维护实例：Fixture multi instance B', exact: true }).click()
  await expect(drawer).toBeVisible()
  await drawer.getByRole('tab', { name: '连接与字段', exact: true }).click()
  await expect(drawer).toContainText('来源报告版本 7.0.0-b')
  await expect(drawer).not.toContainText('来源报告版本 7.0.0-a')
  await expect(drawer.getByText('配置已变化', { exact: true })).toHaveCount(0)
  expect(f.calls.filter(call => call.endsWith('/inspections'))).toEqual([
    'GET /api/v2/data-sources/' + f.a.id + '/inspections',
    'GET /api/v2/data-sources/' + f.b.id + '/inspections'
  ])
})

test('a connection receipt of an older configuration is labeled as changed, never current', async ({ page }) => {
  const f = await fixture(page)
  await page.getByRole('button', { name: '维护实例：Fixture multi instance A', exact: true }).click()
  const drawer = page.getByRole('dialog', { name: '维护接入实例' })
  await drawer.getByRole('tab', { name: '连接与字段', exact: true }).click()
  await expect(drawer.getByText('当前配置', { exact: true })).toBeVisible()
  await expect(drawer.getByText('配置已变化', { exact: true })).toBeVisible()
  const stale = drawer.locator('article').filter({ hasText: '配置已变化' })
  await expect(stale).toContainText('连接测试 · v1')
  await expect(stale).toContainText('连接摘要')
  const oldDigest = String(f.a.receipts.find(row => row.validity === 'STALE')!.inspection.connectionDigest)
  await expect(stale).toContainText(oldDigest)
  await expect(stale).not.toContainText('当前配置')
  await expect(drawer.locator('article').filter({ hasText: '当前配置' })).toContainText('连接测试 · v2')
})
