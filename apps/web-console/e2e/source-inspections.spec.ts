import { test, expect, type Page } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { createHash } from 'node:crypto'
import { TOKEN_OK } from './helpers.ts'
const sample = (name: string) => JSON.parse(readFileSync(new URL('../../../contracts/examples/' + name, import.meta.url), 'utf8'))
function digest(parts: string[]) { const h = createHash('sha256'); for (const p of parts) { const bytes = Buffer.from(p); h.update(bytes.length + ':'); h.update(bytes) }; return 'sha256:' + h.digest('hex') }
async function setup(page: Page, mode = 'normal') {
  const source = sample('v2/source-instance-page.json').items[0], calls: { path: string; method: string; body: any }[] = []
  const second = { ...source, id: '20000000-0000-4000-8000-000000000088', workflowId: 'source-20000000-0000-4000-8000-000000000088', name: 'Fixture second instance' }
  if (['unversioned', 'pinned'].includes(mode)) source.dataMode = 'zabbix-jsonrpc'
  let receipt: any, reads = 0, queries = 0; const originals = new Map<string, any>()
  await page.route('**/api/v1/integrations/sources', r => r.fulfill({ json: sample('source-center-page.json') }))
  await page.route('**/api/v2/data-sources**', async r => {
    const path = new URL(r.request().url()).pathname, method = r.request().method(), body = r.request().postDataJSON(); calls.push({ path, method, body })
    if (method === 'POST') {
      const kind = path.endsWith('/metric-discoveries') ? 'DISCOVER_METRIC_PAGE' : path.endsWith('/connection-check') || path.endsWith('/test') ? 'TEST' : path.endsWith('/discover-metrics') ? 'DISCOVER_METRICS' : 'DISCOVER', start = Date.now(), fields = ['hostid', 'host', 'name', 'status', 'interfaces.ip'].map(name => ({ name, type: name === 'interfaces.ip' ? 'TEXT_ARRAY' : 'TEXT', nullable: false }))
      const fingerprint = ['source-host-fields-v1']; for (const f of [...fields].sort((a, b) => a.name.localeCompare(b.name))) fingerprint.push(f.name, f.type, String(f.nullable))
      receipt = { schemaVersion: '2.0', storage: 'memory', view: { validity: mode === 'unversioned' || mode === 'partial' && kind === 'DISCOVER' ? 'UNVERIFIED' : 'CURRENT', inspection: { requestId: body.requestId, sourceId: source.id, kind, configurationRevision: body.configurationRevision, connectionDigest: body.connectionDigest, dataMode: source.dataMode, commandDigest: digest(['source-inspection-v2', source.id, body.requestId, kind, String(body.configurationRevision), body.connectionDigest]), state: 'COMPLETED', asOf: new Date(start).toISOString(), deadline: new Date(start + 65000).toISOString(), availableAt: new Date(start).toISOString(), expiresAt: new Date(start + 900000).toISOString(), check: kind === 'TEST' ? { reachable: true, statusCode: ['unversioned', 'pinned'].includes(mode) ? 'READ_VERIFIED' : 'LABELED_FIXTURE', reportedVersion: ['unversioned', 'pinned'].includes(mode) ? '7.0.0' : null } : null, discovery: kind === 'DISCOVER' ? { fields, observedRecords: mode === 'partial' ? 5 : 2, complete: mode !== 'partial', scanConsistency: 'LABELED_FIXTURE', statusCode: mode === 'partial' ? 'INCOMPLETE' : 'READ_VERIFIED', fingerprint: digest(fingerprint), scope: 'FIRST_HOST_PAGE' } : null } } }
      if (kind === 'DISCOVER_METRIC_PAGE') {
        const d = structuredClone(sample('v2/source-metric-discovery.json'))
        d.items[0].mapping.metricKey = 'host.cpu.usage.user'
        d.items[0].sourceKey = 'fixture.long.source.metric.with.all.namespace.parts.' + 'extended.'.repeat(15) + 'cpu.user'
        const many = ['metric-partial', 'metric-many', 'metric-many-unknown', 'metric-changed'].includes(mode)
        const all = many ? Array.from({ length: 45 }, (_, n) => n < 2 ? structuredClone(d.items[n]) : ({ ...structuredClone(d.items[1]), itemId: String(20001 + n), sourceKey: 'fixture.metric.' + n })) : d.items
        const parent = body.previousRequestId ? originals.get(body.previousRequestId)?.view.inspection : null, i = receipt.view.inspection
        const membership = digest(['source-metric-membership-v1', String(all.length), ...all.map((v: any) => v.itemId)])
        const manifest = parent?.metricPage.manifest ?? { snapshotId: body.requestId, asOf: i.asOf, expiresAt: i.expiresAt, total: all.length, fingerprint: membership }
        const offset = parent?.metricPage.nextOffset ?? 0, statusCode = mode === 'metric-capacity' ? 'CAPACITY' : mode === 'metric-changed' && parent ? 'MEMBERSHIP_CHANGED' : 'READ_VERIFIED'
        const m = statusCode === 'CAPACITY' ? null : manifest, items = statusCode === 'READ_VERIFIED' ? all.slice(offset, offset + 20) : [], consistency = statusCode === 'READ_VERIFIED' ? 'LABELED_FIXTURE' : 'UNVERIFIED'
        const parts = ['source-metric-metadata-v1']; for (const i of items) { parts.push(i.itemId, i.hostId, i.sourceKey, i.name, i.sourceUnit, i.sourceValueType, i.mappingStatus); const m = i.mapping; parts.push(...(m ? ['present', m.id, String(m.revision), m.digest, m.metricKey, m.unit, m.valueType, m.valueTransform] : ['absent'])) }
        const pageParts = ['source-metric-page-v1', ...(m ? ['present', m.snapshotId, m.asOf, m.expiresAt, String(m.total), m.fingerprint] : ['absent']), String(offset), statusCode, consistency, digest(parts)]
        i.previousRequestId = body.previousRequestId; i.metricDiscovery = null; i.commandDigest = digest(['source-metric-page-command-v1', source.id, body.requestId, String(body.configurationRevision), body.connectionDigest, body.previousRequestId ?? 'initial'])
        const complete = statusCode === 'READ_VERIFIED' && offset + items.length === m.total
        i.metricPage = { manifest: m, offset, items, statusCode, scanConsistency: consistency, fingerprint: digest(pageParts), complete, nextOffset: statusCode === 'READ_VERIFIED' && !complete ? offset + 20 : null, limit: 20 }
        if (statusCode !== 'READ_VERIFIED') receipt.view.validity = 'UNVERIFIED'
        if (mode === 'metric-bad') i.metricPage.fingerprint = 'sha256:' + 'f'.repeat(64)
        originals.set(body.requestId, structuredClone(receipt))
        if (mode === 'metric-unknown' || mode === 'metric-many-unknown' && parent) return r.abort('failed')
      }
      originals.set(body.requestId, structuredClone(receipt))
      if (['unknown', 'mismatch', 'bad-fingerprint'].includes(mode)) return r.abort('failed')
      if (mode === 'pending') { const pending = structuredClone(receipt); Object.assign(pending.view.inspection, { state: 'PENDING', availableAt: null, expiresAt: null, check: null, discovery: null }); pending.view.validity = 'UNVERIFIED'; return r.fulfill({ json: pending }) }
      return r.fulfill({ json: receipt })
    }
    if (path.includes('/inspections/')) {
      queries++; const value = structuredClone(originals.get(path.split('/').at(-1)!) ?? receipt)
      if (mode === 'mismatch') { value.view.inspection.requestId = '20000000-0000-4000-8000-000000000099'; const i = value.view.inspection; i.commandDigest = digest(['source-inspection-v2', i.sourceId, i.requestId, i.kind, String(i.configurationRevision), i.connectionDigest]) }
      if (mode === 'bad-fingerprint') value.view.inspection.discovery.fingerprint = 'sha256:' + 'f'.repeat(64)
      if (mode === 'pending' && queries === 1) { Object.assign(value.view.inspection, { state: 'PENDING', availableAt: null, expiresAt: null, check: null, discovery: null }); value.view.validity = 'UNVERIFIED' }
      return r.fulfill({ json: value })
    }
    if (path.endsWith('/inspections')) { reads++; if (['forbidden', 'read-failed'].includes(mode) && reads === 1) return r.fulfill({ status: mode === 'forbidden' ? 403 : 503, json: { error: mode === 'forbidden' ? 'FORBIDDEN' : 'SOURCE_UNAVAILABLE' } }); return r.fulfill({ json: { schemaVersion: '2.0', storage: 'memory', sourceId: path.split('/')[4], items: [] } }) }
    if (path === '/api/v2/data-sources') return r.fulfill({ json: { schemaVersion: '2.0', storage: 'memory', items: mode === 'multiple' ? [source, second] : [source], truncated: false } })
    return r.fulfill({ json: { schemaVersion: '2.0', instance: path.endsWith(second.id) ? second : source } })
  })
  await page.goto('/#/integrations/sources'); await page.getByRole('textbox', { name: '平台开发 Token（仅保存在当前标签页内存）' }).fill(TOKEN_OK); await page.getByRole('tab', { name: /已配置接入/ }).click(); await page.getByRole('button', { name: '维护实例：Fixture Zabbix onboarding', exact: true }).click()
  const drawer = page.getByRole('dialog', { name: '维护接入实例' }); await expect(drawer).toBeVisible(); expect(reads).toBe(0); await drawer.getByRole('tab', { name: '连接与字段', exact: true }).click()
  return { drawer, calls, reads: () => reads, queries: () => queries }
}
for (const width of [1440, 1024, 390]) for (const theme of ['light', 'dark']) test('explicit fixture pinned test and discovery at ' + width + ' ' + theme, async ({ page }) => {
  await page.setViewportSize({ width, height: 940 }); await page.addInitScript(theme => localStorage.setItem('opsweave.ui.theme', theme), theme)
  const f = await setup(page); await expect(f.drawer.getByText('暂无测试或发现记录。', { exact: true })).toBeVisible(); expect(f.reads()).toBe(1)
  await f.drawer.getByRole('button', { name: '测试保存的连接', exact: true }).click(); await expect(f.drawer.getByText('Fixture 读取通过', { exact: true })).toBeVisible(); expect(f.calls.find(c => c.method === 'POST')?.path.endsWith('/connection-check')).toBe(true); await f.drawer.getByRole('button', { name: '发现来源字段', exact: true }).click(); await expect(f.drawer.getByRole('table', { name: '发现的来源字段' })).toContainText('interfaces.ip')
  await expect(f.drawer.getByText('本次结果已确认', { exact: true })).toBeVisible(); expect(f.calls.filter(c => c.method === 'POST')).toHaveLength(2); for (const c of f.calls.filter(c => c.method === 'POST')) expect(Object.keys(c.body).sort()).toEqual(['configurationRevision', 'connectionDigest', 'requestId'])
  await f.drawer.getByRole('tab', { name: '实例配置', exact: true }).click(); await f.drawer.getByRole('tab', { name: '连接与字段', exact: true }).click(); expect(f.reads()).toBe(1); await expect(f.drawer.getByRole('table', { name: '发现的来源字段' })).toBeVisible(); expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  await f.drawer.getByRole('button', { name: '关闭实例维护', exact: true }).click()
})
test('partial discovery preserves observed fields and does not claim complete coverage', async ({ page }) => {
  const f = await setup(page, 'partial'); await f.drawer.getByRole('button', { name: '发现来源字段', exact: true }).click(); await expect(f.drawer.getByText(/发现不完整，仅覆盖首个主机分页/)).toBeVisible(); await expect(f.drawer.getByText('未确认可复用', { exact: true })).toBeVisible(); await expect(f.drawer.getByRole('table', { name: '发现的来源字段' })).toContainText('hostid'); expect(f.calls.filter(c => c.method === 'POST')).toHaveLength(1)
})
test('unknown inspection queries the exact original request and never repeats POST', async ({ page }) => {
  const f = await setup(page, 'unknown'); await f.drawer.getByRole('button', { name: '测试保存的连接', exact: true }).click(); await expect(f.drawer.getByRole('button', { name: '查询原测试或发现回执', exact: true })).toBeEnabled(); await f.drawer.getByRole('tab', { name: '实例配置', exact: true }).click(); await expect(f.drawer.getByRole('textbox', { name: '实例名称', exact: true })).toBeDisabled(); await f.drawer.getByRole('button', { name: '查询原测试或发现回执', exact: true }).click(); await expect(f.drawer.getByText('本次结果已确认', { exact: true })).toBeVisible(); await expect(f.drawer.getByRole('textbox', { name: '实例名称', exact: true })).toBeEnabled(); const post = f.calls.find(c => c.method === 'POST')!; expect(f.calls.some(c => c.path.endsWith('/inspections/' + post.body.requestId))).toBe(true); expect(f.calls.filter(c => c.method === 'POST')).toHaveLength(1)
})
test('a pending server receipt is queried explicitly without polling or repeating the probe', async ({ page }) => {
  const f = await setup(page, 'pending'); await f.drawer.getByRole('button', { name: '测试保存的连接', exact: true }).click(); await expect(f.drawer.getByText('请求仍在处理，请按原请求查询', { exact: true })).toBeVisible(); expect(f.queries()).toBe(0); await f.drawer.getByRole('button', { name: '查询原测试或发现回执', exact: true }).click(); await expect(f.drawer.getByRole('button', { name: '关闭实例维护', exact: true })).toBeDisabled(); expect(f.queries()).toBe(1); await f.drawer.getByRole('button', { name: '查询原测试或发现回执', exact: true }).click(); await expect(f.drawer.getByText('本次结果已确认', { exact: true })).toBeVisible(); expect(f.queries()).toBe(2); expect(f.calls.filter(c => c.method === 'POST')).toHaveLength(1)
})
for (const mode of ['mismatch', 'bad-fingerprint']) test('invalid ' + mode + ' receipt keeps the original command locked', async ({ page }) => {
  const f = await setup(page, mode); await f.drawer.getByRole('button', { name: '发现来源字段', exact: true }).click(); await expect(f.drawer.getByRole('button', { name: '查询原测试或发现回执', exact: true })).toBeEnabled(); await f.drawer.getByRole('button', { name: '查询原测试或发现回执', exact: true }).click(); await expect(f.drawer.getByRole('alert')).toContainText('原配置不一致'); await expect(f.drawer.getByRole('button', { name: '关闭实例维护', exact: true })).toBeDisabled(); expect(f.calls.filter(c => c.method === 'POST')).toHaveLength(1)
})
test('a fixture of an unversioned real connection cannot show a reusable credential pin', async ({ page }) => {
  const f = await setup(page, 'unversioned'); await f.drawer.getByRole('button', { name: '测试保存的连接', exact: true }).click(); await expect(f.drawer.getByText(/本次授权读取通过/)).toBeVisible(); await expect(f.drawer.getByText('未确认可复用', { exact: true })).toBeVisible(); await expect(f.drawer.getByText(/凭据引用尚未版本化/)).not.toBeVisible()
})
test('failed metadata read does not repeat when switching inspector tabs', async ({ page }) => {
  const f = await setup(page, 'read-failed'); await expect(f.drawer.getByRole('alert')).toContainText('请先核对连接配置'); await f.drawer.getByRole('tab', { name: '实例配置', exact: true }).click(); await f.drawer.getByRole('tab', { name: '连接与字段', exact: true }).click(); expect(f.reads()).toBe(1); await f.drawer.getByRole('button', { name: '刷新测试与发现记录', exact: true }).click(); await expect(f.drawer.getByText('暂无测试或发现记录。', { exact: true })).toBeVisible(); expect(f.reads()).toBe(2)
})
test('forbidden inspection clears cached source data and closes the drawer without rereading', async ({ page }) => {
  const f = await setup(page, 'forbidden'); await expect(f.drawer).not.toBeVisible(); await expect(page.locator('.source-center > [role=alert]')).toContainText('来源权限'); await page.getByRole('tab', { name: /已配置接入/ }).click(); await expect(page.locator('.source-instance-panel > [role=alert]')).toContainText('来源权限'); expect(f.reads()).toBe(1); expect(f.calls.filter(c => c.path === '/api/v2/data-sources' && c.method === 'GET')).toHaveLength(1); await expect(page.getByRole('button', { name: '维护实例：Fixture Zabbix onboarding', exact: true })).not.toBeVisible(); expect(f.calls.filter(c => c.method === 'POST')).toHaveLength(0)
})
test('inspection expiry updates the visible badge without an API request', async ({ page }) => {
  await page.clock.install(); const f = await setup(page); await f.drawer.getByRole('button', { name: '测试保存的连接', exact: true }).click(); await expect(f.drawer.getByText('当前配置', { exact: true })).toBeVisible(); const requests = f.calls.length; await page.clock.fastForward(901000); await expect(f.drawer.getByText('已过期', { exact: true })).toBeVisible(); expect(f.calls).toHaveLength(requests)
})
test('a pending inspection survives a workspace page switch without repeating the probe', async ({ page }) => {
  const f = await setup(page, 'unknown'); await f.drawer.getByRole('button', { name: '测试保存的连接', exact: true }).click(); await expect(f.drawer.getByRole('button', { name: '查询原测试或发现回执', exact: true })).toBeEnabled(); await page.evaluate(() => { location.hash = '/modeling/metrics' }); await expect(f.drawer).not.toBeVisible(); await page.evaluate(() => { location.hash = '/integrations/sources' }); await expect(f.drawer).toBeVisible(); await f.drawer.getByRole('button', { name: '查询原测试或发现回执', exact: true }).click(); await expect(f.drawer.getByText('本次结果已确认', { exact: true })).toBeVisible(); expect(f.calls.filter(c => c.method === 'POST')).toHaveLength(1)
})
test('opening another instance starts on configuration and does not read its hidden inspection history', async ({ page }) => {
  const f = await setup(page, 'multiple'); await expect(f.drawer.getByText('暂无测试或发现记录。', { exact: true })).toBeVisible(); expect(f.reads()).toBe(1); await f.drawer.getByRole('button', { name: '关闭实例维护', exact: true }).click(); await page.getByRole('button', { name: '维护实例：Fixture second instance', exact: true }).click(); await expect(f.drawer.getByRole('tab', { name: '实例配置', exact: true })).toHaveAttribute('aria-selected','true'); expect(f.reads()).toBe(1); await f.drawer.getByRole('tab', { name: '连接与字段', exact: true }).click(); await expect(f.drawer.getByText('暂无测试或发现记录。', { exact: true })).toBeVisible(); expect(f.reads()).toBe(2)
})

// Explicit response Fixture of a fully pinned real-connector mode, not an upstream connection claim.
test('fully pinned JSON-RPC CURRENT receipt is accepted and clears the pending command', async ({ page }) => {
  const f = await setup(page, 'pinned'); await f.drawer.getByRole('button', { name: '测试保存的连接', exact: true }).click(); await expect(f.drawer.getByText('当前配置', { exact: true })).toBeVisible(); await expect(f.drawer.getByText('本次结果已确认', { exact: true })).toBeVisible(); await expect(f.drawer.getByRole('button', { name: '关闭实例维护', exact: true })).toBeEnabled(); expect(f.calls.filter(c => c.method === 'POST')).toHaveLength(1)
})

for (const width of [1440, 1024, 390]) for (const theme of ['light', 'dark']) test('source metric discovery keeps full source and mapped keys at ' + width + ' ' + theme, async ({ page }) => {
  await page.setViewportSize({ width, height: 1000 }); await page.addInitScript(theme => localStorage.setItem('opsweave.ui.theme', theme), theme)
  let catalogReads = 0; await page.route('**/api/v1/catalog', r => { catalogReads++; return r.fulfill({ json: { schemaVersion: '1.0', storage: 'memory', package: JSON.parse(readFileSync(new URL('../../../contracts/catalog/opsweave-core-1.0.0.json', import.meta.url), 'utf8')), published: { items: [], truncated: false }, drafts: { items: [], truncated: false } } }) })
  const f = await setup(page); expect(f.reads()).toBe(1); expect(f.calls.filter(c => c.method === 'POST')).toHaveLength(0)
  await f.drawer.getByRole('button', { name: '发现来源指标', exact: true }).click(); const table = f.drawer.getByRole('table', { name: '发现的来源指标' }); await expect(table.locator('tbody tr')).toHaveCount(2); await expect(table).toContainText('未配置映射'); await expect(table).toContainText('未声明单位'); await expect(table).toContainText('extended.'.repeat(15) + 'cpu.user'); await expect(table.getByRole('link', { name: 'host.cpu.usage.user', exact: true })).toHaveAttribute('href', '#/modeling/metrics?metricKey=host.cpu.usage.user'); await expect(f.drawer.getByText('本次结果已确认', { exact: true })).toBeVisible(); expect(f.calls.filter(c => c.method === 'POST')).toHaveLength(1); expect(catalogReads).toBe(0)
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  const key = table.locator('tbody tr').first().locator('code').first(); expect(await key.evaluate(e => e.scrollWidth <= e.clientWidth)).toBe(true)
  expect(await table.evaluate(e => [...e.querySelectorAll('td')].every(cell => cell.scrollWidth <= cell.clientWidth))).toBe(true)
  const link = table.getByRole('link', { name: 'host.cpu.usage.user', exact: true }); expect(await link.evaluate(e => e.scrollWidth <= e.clientWidth)).toBe(true)
  expect(await f.drawer.getByRole('button', { name: '关闭实例维护', exact: true }).evaluate(e => { const b=e.getBoundingClientRect(), s=e.querySelector('svg')!.getBoundingClientRect(); return Math.abs((b.left+b.right)-(s.left+s.right))<1 && Math.abs((b.top+b.bottom)-(s.top+s.bottom))<1 })).toBe(true)
  await table.scrollIntoViewIfNeeded(); await page.screenshot({ path: 'D:/workspace/product-design/ops-weave/data-platform-2026-10-03/checks/page109/metric-discovery-' + width + '-' + theme + '.png', fullPage: false })
  await table.getByRole('link', { name: 'host.cpu.usage.user', exact: true }).click(); await expect(page.getByText('完整指标标识：')).toContainText('host.cpu.usage.user'); expect(catalogReads).toBe(1); expect(f.calls.filter(c => c.method === 'POST')).toHaveLength(1)
})
test('partial metric inventory does not imply full metric coverage', async ({ page }) => {
  const f = await setup(page, 'metric-partial'); await f.drawer.getByRole('button', { name: '发现来源指标', exact: true }).click(); await expect(f.drawer.getByRole('table', { name: '发现的来源指标' }).locator('tbody tr')).toHaveCount(20); await expect(f.drawer.getByText(/尚未覆盖全部指标/)).toBeVisible(); await expect(f.drawer.getByRole('button', { name: '下一页指标', exact: true })).toBeEnabled(); expect(f.calls.filter(c => c.method === 'POST')).toHaveLength(1)
})
test('lost metric response queries the original receipt without rediscovering', async ({ page }) => {
  const f = await setup(page, 'metric-unknown'); await f.drawer.getByRole('button', { name: '发现来源指标', exact: true }).click(); await expect(f.drawer.getByRole('button', { name: '查询原测试或发现回执', exact: true })).toBeEnabled(); await f.drawer.getByRole('button', { name: '查询原测试或发现回执', exact: true }).click(); await expect(f.drawer.getByRole('table', { name: '发现的来源指标' })).toBeVisible(); expect(f.calls.filter(c => c.method === 'POST')).toHaveLength(1); expect(f.queries()).toBe(1)
})
test('a corrupt metric fingerprint retains the pending original command', async ({ page }) => {
  const f = await setup(page, 'metric-bad'); await f.drawer.getByRole('button', { name: '发现来源指标', exact: true }).click(); await expect(f.drawer.getByRole('alert')).toContainText('原配置不一致'); await expect(f.drawer.getByRole('button', { name: '关闭实例维护', exact: true })).toBeDisabled(); await expect(f.drawer.getByRole('table', { name: '发现的来源指标' })).not.toBeVisible(); expect(f.calls.filter(c => c.method === 'POST')).toHaveLength(1)
})

test('45-item inventory uses fixed parents and reading old pages never repeats POST', async ({ page }) => {
  const f = await setup(page, 'metric-many'); await f.drawer.getByRole('button', { name: '发现来源指标', exact: true }).click()
  const section = f.drawer.getByRole('region', { name: '来源指标分页发现' }); await expect(section).toContainText('1–20 / 45 项'); await expect(section.getByRole('button', { name: '上一页指标' })).toBeDisabled()
  await section.getByRole('button', { name: '下一页指标' }).click(); await expect(section).toContainText('21–40 / 45 项')
  await section.getByRole('button', { name: '下一页指标' }).click(); await expect(section).toContainText('41–45 / 45 项'); await expect(section).toContainText('已覆盖本次授权可见的指标清单'); await expect(section.getByRole('button', { name: '下一页指标' })).toBeDisabled()
  const posts = f.calls.filter(c => c.method === 'POST'); expect(posts).toHaveLength(3); expect(posts[0].body.previousRequestId).toBeNull(); expect(posts[1].body.previousRequestId).toBe(posts[0].body.requestId); expect(posts[2].body.previousRequestId).toBe(posts[1].body.requestId)
  for (const post of posts) expect(Object.keys(post.body).sort()).toEqual(['configurationRevision', 'connectionDigest', 'previousRequestId', 'requestId'])
  await section.getByRole('button', { name: '上一页指标' }).click(); await expect(section).toContainText('21–40 / 45 项'); expect(f.queries()).toBe(1)
  await section.getByRole('button', { name: '下一页指标' }).click(); await expect(section).toContainText('41–45 / 45 项'); expect(f.calls.filter(c => c.method === 'POST')).toHaveLength(3); expect(f.queries()).toBe(1)
})
test('unknown continuation queries original request and never advances or repeats it', async ({ page }) => {
  const f = await setup(page, 'metric-many-unknown'); await f.drawer.getByRole('button', { name: '发现来源指标', exact: true }).click(); const section = f.drawer.getByRole('region', { name: '来源指标分页发现' })
  await section.getByRole('button', { name: '下一页指标' }).click(); await expect(section).toContainText('1–20 / 45 项'); await expect(section.getByRole('button', { name: '下一页指标' })).toBeDisabled()
  const posts = f.calls.filter(c => c.method === 'POST'); expect(posts).toHaveLength(2); await f.drawer.getByRole('button', { name: '查询原测试或发现回执' }).click(); await expect(section).toContainText('21–40 / 45 项'); expect(f.calls.filter(c => c.method === 'POST')).toHaveLength(2); expect(f.queries()).toBe(1); expect(f.calls.at(-1)?.path).toContain(posts[1].body.requestId)
})
for (const mode of ['metric-changed', 'metric-capacity']) test(mode + ' cannot imply an empty or complete inventory', async ({ page }) => {
  const f = await setup(page, mode); await f.drawer.getByRole('button', { name: '发现来源指标', exact: true }).click(); const section = f.drawer.getByRole('region', { name: '来源指标分页发现' })
  if (mode === 'metric-changed') await section.getByRole('button', { name: '下一页指标' }).click()
  await expect(section).toContainText(mode === 'metric-changed' ? '清单已变化' : '超过 1000 项'); await expect(section.getByRole('button', { name: '下一页指标' })).toBeDisabled(); await expect(section).not.toContainText('未发现指标'); await expect(section).not.toContainText('已覆盖本次')
})
test('inventory root expiry is not renewed by a later page', async ({ page }) => {
  await page.clock.install(); const f = await setup(page, 'metric-many'); await f.drawer.getByRole('button', { name: '发现来源指标', exact: true }).click(); const section = f.drawer.getByRole('region', { name: '来源指标分页发现' })
  await page.clock.fastForward(60000); await section.getByRole('button', { name: '下一页指标' }).click(); await expect(section).toContainText('21–40 / 45 项'); const requests = f.calls.length
  await page.clock.fastForward(841000); await expect(f.drawer.getByText('已过期', { exact: true })).toBeVisible(); await expect(section.getByRole('button', { name: '下一页指标' })).toBeDisabled(); expect(f.calls).toHaveLength(requests)
})
