import { expect, test, type Page, type Route } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { TOKEN_OK } from './helpers.ts'

// Shared contract example, explicitly synthetic. These tests intercept HTTP.
const example = JSON.parse(readFileSync(new URL('../../../contracts/examples/metric-series-page.json', import.meta.url), 'utf8'))
const ENTITY = example.entityId as string
const METRIC = example.metricKey as string
const host = {
  schemaVersion: '1.0',
  id: ENTITY, tenantId: 'tenant-demo', entityType: 'host', name: 'Fixture host <img src=x onerror=alert(1)>',
  lifecycle: 'ACTIVE', version: 1, attributes: {},
}

async function openMetrics(page: Page) {
  await page.route('**/api/v1/entities/page?**', route => {
    const params = new URL(route.request().url()).searchParams
    return route.fulfill({ json: { storage: 'postgres', query: { q: params.get('q'), type: 'host', lifecycle: '', after: params.get('after'), limit: 25 }, items: [host], nextCursor: null } })
  })
  await page.route('**/api/v1/metrics/definitions', route => route.fulfill({ json: {
    items: [{ metricKey: METRIC, displayName: 'CPU User', unit: '1' }],
  } }))
  await page.goto('/#/metrics')
  await page.getByRole('textbox', { name: '平台开发 Token（仅保存在当前标签页内存）' }).fill(TOKEN_OK)
  await page.getByRole('button', { name: '读取资产和指标' }).click()
  await expect(page.getByRole('combobox', { name: 'Host' })).toHaveValue(ENTITY)
}

function payload(route: Route, kind = 'AVAILABLE') {
  const url = new URL(route.request().url())
  const from = Number(url.searchParams.get('from'))
  const till = Number(url.searchParams.get('till'))
  const last = (till - (kind === 'STALE' ? 400 : 1)) * 1000
  const result = structuredClone(example)
  Object.assign(result, { from, till })
  result.series[0].points = [[last - 1000, '0.31'], [last, '0.40']]
  result.status = { kind, fresh: kind !== 'STALE' && kind !== 'NO_DATA', partial: kind === 'PARTIAL', lastPointAt: last }
  if (kind === 'NO_DATA') {
    result.series = []
    result.status.lastPointAt = null
  }
  return result
}

test('queries bounded authorized curves and keeps each source value and fixture label', async ({ page }) => {
  const calls: URL[] = []
  await page.route('**/series?*', async route => {
    const url = new URL(route.request().url())
    calls.push(url)
    expect(route.request().headers().authorization).toBe(`Bearer ${TOKEN_OK}`)
    expect([...url.searchParams.keys()].sort()).toEqual(['from', 'maxPoints', 'till'])
    expect(url.searchParams.get('maxPoints')).toBe('500')
    const body = payload(route)
    const second = structuredClone(body.series[0])
    second.sourceInstanceId = 'zabbix-2'
    second.points = [[(body.till - 100) * 1000, '0.22']]
    body.series.push(second)
    await route.fulfill({ json: body })
  })
  await openMetrics(page)
  for (const [label, seconds] of [['Last 15m', 900], ['Last 30m', 1800], ['Last 1h', 3600]] as const) {
    await page.getByRole('button', { name: label, exact: true }).click()
    await expect(page.getByRole('img', { name: '指标曲线' })).toHaveCount(0)
    await page.getByRole('button', { name: '查询', exact: true }).click()
    await expect(page.getByRole('img', { name: '指标曲线' })).toBeVisible()
    const last = calls.at(-1)!
    expect(Number(last.searchParams.get('till')) - Number(last.searchParams.get('from'))).toBe(seconds)
  }
  await expect(page.locator('[data-series-source="zabbix-1"]')).toContainText('labeled-fixture')
  await expect(page.locator('[data-series-source="zabbix-1"]')).toContainText('Last: 0.40')
  await expect(page.locator('[data-series-source="zabbix-2"]')).toContainText('Last: 0.22')
  await expect(page.locator('.metric-chart polyline')).toHaveCount(2)
  expect(await page.locator('.metric-chart polyline').first().evaluate(element => element.namespaceURI)).toBe('http://www.w3.org/2000/svg')
  await expect(page.locator('[data-page="metrics"] img')).toHaveCount(0)
})

for (const kind of ['NO_DATA', 'STALE', 'PARTIAL']) {
  test(`shows ${kind} and does not present a later storage failure as no data`, async ({ page }) => {
    let fail = false
    await page.route('**/series?*', route => fail
      ? route.fulfill({ status: 503, json: { error: 'SOURCE_UNAVAILABLE' } })
      : route.fulfill({ json: payload(route, kind) }))
    await openMetrics(page)
    await page.getByRole('button', { name: '查询', exact: true }).click()
    await expect(page.locator(`[data-state="${kind === 'NO_DATA' ? 'no-data' : kind.toLowerCase()}"]`)).toBeVisible()
    fail = true
    await page.getByRole('button', { name: '查询', exact: true }).click()
    await expect(page.getByRole('alert')).toContainText('SOURCE_UNAVAILABLE')
    await expect(page.getByRole('img', { name: '指标曲线' })).toHaveCount(0)
    await expect(page.locator('[data-state="no-data"]')).toHaveCount(0)
  })
}

test('an incomplete empty scan stays partial', async ({ page }) => {
  await page.route('**/series?*', route => {
    const body = payload(route, 'NO_DATA')
    body.status.kind = 'PARTIAL'
    body.status.partial = true
    return route.fulfill({ json: body })
  })
  await openMetrics(page)
  await page.getByRole('button', { name: '查询', exact: true }).click()
  await expect(page.locator('[data-state="partial"]')).toBeVisible()
  await expect(page.locator('[data-state="no-data"]')).toHaveCount(0)
})

for (const invalid of ['missing provenance', 'NaN', 'wrong entity', 'wrong window']) {
  test(`rejects ${invalid} from a successful HTTP response`, async ({ page }) => {
    await page.route('**/series?*', route => {
      const body = payload(route)
      if (invalid === 'missing provenance') delete body.series[0].dataMode
      if (invalid === 'NaN') body.series[0].points[0][1] = 'NaN'
      if (invalid === 'wrong entity') body.entityId = '22222222-2222-2222-2222-222222222222'
      if (invalid === 'wrong window') body.from += 1
      return route.fulfill({ json: body })
    })
    await openMetrics(page)
    await page.getByRole('button', { name: '查询', exact: true }).click()
    await expect(page.getByRole('alert')).not.toBeEmpty()
    await expect(page.getByRole('img', { name: '指标曲线' })).toHaveCount(0)
  })
}

test('changing credentials clears cached assets and ignores an in-flight result', async ({ page }) => {
  let release!: () => void
  let arrived!: () => void
  const pending = new Promise<void>(resolve => { release = resolve })
  const requested = new Promise<void>(resolve => { arrived = resolve })
  await page.route('**/series?*', async route => {
    const body = payload(route)
    arrived()
    await pending
    await route.fulfill({ json: body }).catch(() => {})
  })
  await openMetrics(page)
  await page.getByRole('button', { name: '查询', exact: true }).click()
  await requested
  await page.getByRole('textbox', { name: '平台开发 Token（仅保存在当前标签页内存）' }).fill(`${TOKEN_OK}-new`)
  release()
  await expect(page.getByRole('combobox')).toHaveCount(0)
  await expect(page.getByRole('img', { name: '指标曲线' })).toHaveCount(0)
  await expect(page.locator('[data-state="loading"]')).toHaveCount(0)
  await expect(page.getByRole('alert')).toBeEmpty()
  await page.goto('/#/inventory')
  await page.goto('/#/metrics')
  await expect(page.getByRole('textbox', { name: '平台开发 Token（仅保存在当前标签页内存）' })).toHaveValue(`${TOKEN_OK}-new`)
  await expect(page.getByRole('combobox')).toHaveCount(0)
})

test('pages the Host selector and sends name filtering to the bounded API', async ({ page }) => {
  const rows = Array.from({ length: 26 }, (_, i) => ({ ...host, id: `${(i + 1).toString(16).padStart(8, '0')}-0000-0000-0000-000000000001`, name: `Host ${i + 1}` }))
  await openMetrics(page)
  const calls: URL[] = []
  await page.route('**/api/v1/entities/page?**', route => {
    const url = new URL(route.request().url()); calls.push(url)
    const query = url.searchParams
    const items = query.get('q') || query.get('after') ? [rows[25]!] : rows.slice(0, 25)
    return route.fulfill({ json: { storage: 'postgres', query: { q: query.get('q'), type: 'host', lifecycle: '', after: query.get('after'), limit: 25 },
      items, nextCursor: items.length === 25 ? items[24]!.id : null } })
  })
  await page.getByRole('button', { name: '读取资产和指标' }).click()
  await expect(page.getByRole('combobox', { name: 'Host' }).locator('option')).toHaveCount(25)
  await page.getByRole('button', { name: '下一页 Host' }).click()
  await expect(page.getByRole('combobox', { name: 'Host' })).toHaveValue(rows[25]!.id)
  expect(calls[1]!.searchParams.get('after')).toBe(rows[24]!.id)
  await page.getByRole('button', { name: '上一页 Host' }).click()
  await expect(page.getByRole('combobox', { name: 'Host' })).toHaveValue(rows[0]!.id)
  await page.getByRole('textbox', { name: '筛选 Host 名称或 IP' }).fill('Host 26')
  await page.getByRole('button', { name: '读取资产和指标' }).click()
  await expect(page.getByRole('combobox', { name: 'Host' })).toHaveValue(rows[25]!.id)
  expect(calls.at(-1)!.searchParams.get('q')).toBe('Host 26')
  expect(calls.at(-1)!.searchParams.get('after')).toBeNull()
})

test('a revoked metric permission clears the previously loaded catalog and series', async ({ page }) => {
  let denied = false
  await page.route('**/series?*', route => denied ? route.fulfill({ status: 403, json: { error: 'FORBIDDEN' } }) : route.fulfill({ json: payload(route) }))
  await openMetrics(page)
  await page.getByRole('button', { name: '查询', exact: true }).click()
  await expect(page.getByRole('img', { name: '指标曲线' })).toBeVisible()
  denied = true
  await page.getByRole('button', { name: '查询', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('FORBIDDEN')
  await expect(page.getByRole('combobox')).toHaveCount(0)
  await expect(page.getByRole('img', { name: '指标曲线' })).toHaveCount(0)
})

test('an invalid linked window cannot silently fall back to a current-time query', async ({ page }) => {
  await page.goto(`/#/metrics?entityId=${ENTITY}&from=1&till=7200`)
  await page.getByRole('textbox', { name: '平台开发 Token（仅保存在当前标签页内存）' }).fill(TOKEN_OK)
  await expect(page.getByRole('alert')).toContainText('指标链接的资产或时间窗口无效')
  await expect(page.getByRole('button', { name: '读取资产和指标' })).toBeDisabled()
  await expect(page.getByRole('button', { name: '查询', exact: true })).toHaveCount(0)
})
