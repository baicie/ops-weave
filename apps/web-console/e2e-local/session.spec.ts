import { expect, test, type Page } from '@playwright/test'

// Explicit API fixtures; only local-session handshake uses the real Vite middleware.
async function emptyOverview(page: Page) {
  await page.route('**/api/v1/**', route => {
    const query = new URL(route.request().url()).searchParams
    const asset = route.request().url().includes('/entities/page')
    return route.fulfill({ json: { storage: 'memory', items: [], nextCursor: null,
      query: asset ? { q: query.get('q'), type: query.get('type'), lifecycle: query.get('lifecycle'), after: null, limit: 25 }
        : { status: query.get('status'), after: null, limit: 25 } } })
  })
}
const read = (page: Page) => page.getByRole('button', { name: '读取概览', exact: true })

test('open and refresh establish a memory-only session without a token input', async ({ page }) => {
  await emptyOverview(page)
  const sessions: string[] = [], calls: { nonce?: string; authorization?: string; method: string }[] = []
  page.on('response', async response => { if (response.url().endsWith('/__opsweave/local-session')) sessions.push((await response.json()).sessionNonce) })
  page.on('request', request => { if (request.url().includes('/api/v1/')) calls.push({ nonce: request.headers()['x-opsweave-local-session'], authorization: request.headers().authorization, method: request.method() }) })
  await page.goto('/#/start')
  await expect(read(page)).toBeEnabled()
  await expect(page.locator('[data-platform-session], [data-local-session]')).toHaveCount(0)
  expect(calls).toHaveLength(0)
  await read(page).click()
  await expect(page.locator('[data-overview-stat]')).toHaveText(['0', '0', '0', '0'])
  expect(calls).toHaveLength(2)
  expect(calls.every(call => call.authorization === undefined && call.nonce === sessions[0] && call.method === 'GET')).toBe(true)
  const persisted = await page.evaluate(() => JSON.stringify({ local: { ...localStorage }, session: { ...sessionStorage }, cookie: document.cookie }))
  expect(persisted.includes(sessions[0])).toBe(false)
  await page.reload()
  await expect(read(page)).toBeEnabled()
  await expect.poll(() => sessions.length).toBe(2)
  expect(sessions[1]).not.toBe(sessions[0])
  await expect(page.locator('[data-overview-stat]')).toHaveText(['—', '—', '—', '—'])
})

test('failed bootstrap shows an explicit reconnect and performs no hidden retry', async ({ page }) => {
  let count = 0
  await page.route('**/__opsweave/local-session', route => { count++; return route.fulfill({ status: 503, json: { error: 'UNAVAILABLE' } }) })
  await page.goto('/#/start')
  await expect(page.locator('[data-local-session]')).toContainText('HTTP 503')
  await expect(read(page)).toBeDisabled()
  await expect(page.locator('[data-platform-session]')).toHaveCount(0)
  expect(count).toBe(1)
  await page.unroute('**/__opsweave/local-session')
  await page.getByRole('button', { name: '重新连接', exact: true }).click()
  await expect(read(page)).toBeEnabled()
  await expect(page.locator('[data-local-session]')).toHaveCount(0)
})

test('401 clears the current data and requires explicit reconnect', async ({ page }) => {
  let sessions = 0
  page.on('request', request => { if (request.url().endsWith('/__opsweave/local-session')) sessions++ })
  await page.route('**/api/v1/**', route => route.fulfill({ status: 401, json: { error: 'UNAUTHENTICATED' } }))
  await page.goto('/#/start')
  await expect(read(page)).toBeEnabled()
  await read(page).click()
  await expect(read(page)).toBeDisabled()
  await expect(page.getByRole('button', { name: '重新连接', exact: true })).toBeEnabled()
  await expect(page.locator('[data-overview-stat]')).toHaveText(['—', '—', '—', '—'])
  expect(sessions).toBe(1)
  await page.getByRole('button', { name: '重新连接', exact: true }).click()
  await expect(read(page)).toBeEnabled()
  expect(sessions).toBe(2)
})

test('pagehide and persisted pageshow restore a fresh session with cleared data', async ({ page }) => {
  await emptyOverview(page)
  let sessions = 0
  page.on('request', request => { if (request.url().endsWith('/__opsweave/local-session')) sessions++ })
  await page.goto('/#/start')
  await expect(read(page)).toBeEnabled()
  await read(page).click()
  await expect(page.locator('[data-overview-stat]')).toHaveText(['0', '0', '0', '0'])
  // Deterministic browser lifecycle fixture, not a claim of native bfcache coverage.
  await page.evaluate(() => {
    window.dispatchEvent(new PageTransitionEvent('pagehide', { persisted: true }))
    window.dispatchEvent(new PageTransitionEvent('pageshow', { persisted: true }))
  })
  await expect(read(page)).toBeEnabled()
  await expect(page.locator('[data-overview-stat]')).toHaveText(['—', '—', '—', '—'])
  expect(sessions).toBe(2)
})

test('a workflow deep link opens after the automatic session without an extra read click', async ({ page }) => {
 let reads = 0;
 await page.route('**/api/v1/integrations/workflows', route => {
  reads++;
  return route.fulfill({ json: { schemaVersion: '2.0', storage: 'memory', drafts: { items: [], truncated: false }, published: { items: [], truncated: false }, models: [], modelsTruncated: false, zabbixSource: { instanceId: 'fixture-host', mode: 'fixture' }, runs: { items: [], truncated: false } } });
 });
 await page.goto('/#/integrations/workflows?task=fixture-deep-link');
 await expect(page.getByRole('heading', { name: '数据流版本', exact: true })).toBeVisible();
 expect(reads).toBe(1);
 await expect(page.locator('[data-platform-session], [data-local-session]')).toHaveCount(0);
 await page.reload();
 await expect(page.getByRole('heading', { name: '数据流版本', exact: true })).toBeVisible();
 expect(reads).toBe(2);
});

test('source catalog opens and refreshes automatically with the local session', async ({ page }) => {
 let reads = 0;
 const digest = 'sha256:' + 'a'.repeat(64);
 await page.route('**/api/v1/integrations/sources', route => {
  reads++;
  return route.fulfill({ json: { schemaVersion: '1.0', storage: 'memory', types: [
   { id: 'ZABBIX_HOST', status: 'AVAILABLE', connection: { instanceId: 'fixture-host', digest, dataMode: 'fixture', endpoint: null, credentialRef: null } },
   { id: 'MANUAL_SAMPLE', status: 'AVAILABLE', connection: { instanceId: 'manual', digest, dataMode: 'MANUAL_SAMPLE', endpoint: null, credentialRef: null } },
   { id: 'CMDB_SNAPSHOT', status: 'LEGACY_IMPORT', connection: null }
  ], models: [], modelsTruncated: false, setups: { items: [], truncated: false } } });
 });
 await page.goto('/#/integrations/sources');
 await expect(page.getByRole('tab', { name: '我的接入（0）', exact: true })).toBeVisible();
 expect(reads).toBe(1);
 await expect(page.locator('[data-platform-session], [data-local-session]')).toHaveCount(0);
 await page.reload();
 await expect(page.getByRole('tab', { name: '我的接入（0）', exact: true })).toBeVisible();
 expect(reads).toBe(2);
});
