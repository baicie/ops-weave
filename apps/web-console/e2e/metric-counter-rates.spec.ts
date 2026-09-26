import { expect, test, type Page, type Route } from '@playwright/test'
import { TOKEN_OK } from './helpers.ts'

// Synthetic counter series; the page must state the reset policy instead of smoothing a reset away.
const ENTITY = '11111111-1111-1111-1111-111111111111'
const METRIC = 'net.if.in.bytes'
const host = {
  schemaVersion: '1.0',
  id: ENTITY, tenantId: 'tenant-demo', entityType: 'host', name: 'Counter fixture host',
  lifecycle: 'ACTIVE', version: 1, attributes: {},
}

async function openMetrics(page: Page) {
  await page.route('**/api/v1/entities/page?**', route => {
    const params = new URL(route.request().url()).searchParams
    return route.fulfill({ json: {
      storage: 'postgres',
      query: { q: params.get('q'), type: 'host', lifecycle: '', after: params.get('after'), limit: 25 },
      items: [host], nextCursor: null,
    } })
  })
  await page.route('**/api/v1/metrics/definitions', route => route.fulfill({ json: {
    items: [{ metricKey: METRIC, displayName: 'Interface inbound bytes', unit: '1' }],
  } }))
  await page.goto('/#/metrics')
  await page.getByRole('textbox', { name: '平台开发 Token（仅保存在当前标签页内存）' }).fill(TOKEN_OK)
  await page.getByRole('button', { name: '读取资产和指标' }).click()
  await expect(page.getByRole('combobox', { name: 'Host' })).toHaveValue(ENTITY)
}

function counterPage(route: Route) {
  const url = new URL(route.request().url())
  const from = Number(url.searchParams.get('from'))
  const till = Number(url.searchParams.get('till'))
  const base = (till - 30) * 1000
  return {
    entityId: ENTITY, metricKey: METRIC, unit: '1', from, till,
    series: [{
      sourceInstanceId: 'zabbix-1', dataMode: 'labeled-fixture', externalItemId: '20001', mappingRevision: 3, unit: '1',
      dimensions: { iface: 'eth0' },
      points: [[base, '100'], [base + 10_000, '150'], [base + 20_000, '20'], [base + 30_000, '40']],
      counterRates: [
        { t: base + 10_000, rate: '5.000000', counterReset: false },
        { t: base + 20_000, rate: '2.000000', counterReset: true },
        { t: base + 30_000, rate: '2.000000', counterReset: false },
      ],
    }],
    derivation: { kind: 'counter-rate', resetPolicy: 'reset-counts-from-zero' },
    status: { kind: 'AVAILABLE', fresh: true, partial: false, lastPointAt: base + 30_000 },
  }
}

test('draws per-second rates and marks the reset interval while keeping the raw points', async ({ page }) => {
  await page.route('**/series?*', route => route.fulfill({ json: counterPage(route) }))
  await openMetrics(page)
  await page.getByRole('button', { name: '查询', exact: true }).click()

  await expect(page.locator('[data-derivation="counter-rate"]')).toBeVisible()
  await expect(page.getByRole('img', { name: '指标变化率曲线' })).toBeVisible()
  await expect(page.locator('[data-derivation-note="rate"]')).toContainText('reset-counts-from-zero')
  const ratePoints = await page.locator('.metric-chart polyline').first().getAttribute('points')
  expect(ratePoints!.split(' ')).toHaveLength(3)
  await expect(page.locator('.metric-chart line[data-counter-reset]')).toHaveCount(1)
  await expect(page.locator('[data-counter-resets]')).toContainText('按零重计')
  await expect(page.locator('[data-series-view="rate"]')).toContainText('Last rate: 2.000000')
  await expect(page.locator('[data-series-view="rate"]')).toContainText('resets: 1')

  await page.getByRole('button', { name: '原始累计值', exact: true }).click()
  await expect(page.getByRole('img', { name: '指标曲线' })).toBeVisible()
  await expect(page.locator('[data-derivation-note="raw"]')).toContainText('未做变化率推导')
  const rawPoints = await page.locator('.metric-chart polyline').first().getAttribute('points')
  expect(rawPoints!.split(' ')).toHaveLength(4)
  await expect(page.locator('.metric-chart line[data-counter-reset]')).toHaveCount(0)
  await expect(page.locator('[data-series-view="raw"]')).toContainText('Last: 40')
})

for (const invalid of ['wrong reset policy', 'rates without a policy', 'missing rates', 'negative rate', 'rate on the first point', 'non-boolean reset']) {
  test(`rejects a counter page with ${invalid}`, async ({ page }) => {
    await page.route('**/series?*', route => {
      const body = counterPage(route)
      if (invalid === 'wrong reset policy') body.derivation.resetPolicy = 'guess'
      if (invalid === 'rates without a policy') delete (body as { derivation?: unknown }).derivation
      if (invalid === 'missing rates') delete (body.series[0] as { counterRates?: unknown }).counterRates
      if (invalid === 'negative rate') body.series[0].counterRates[0]!.rate = '-5.000000'
      if (invalid === 'rate on the first point') body.series[0].counterRates[0]!.t = body.series[0].points[0]![0]
      if (invalid === 'non-boolean reset') (body.series[0].counterRates[0] as { counterReset: unknown }).counterReset = 'yes'
      return route.fulfill({ json: body })
    })
    await openMetrics(page)
    await page.getByRole('button', { name: '查询', exact: true }).click()
    await expect(page.getByRole('alert')).not.toBeEmpty()
    await expect(page.locator('.metric-chart')).toHaveCount(0)
    await expect(page.locator('[data-derivation]')).toHaveCount(0)
  })
}

test('a gauge page stays raw and offers no rate view', async ({ page }) => {
  await page.route('**/series?*', route => {
    const body = counterPage(route)
    delete (body as { derivation?: unknown }).derivation
    delete (body.series[0] as { counterRates?: unknown }).counterRates
    return route.fulfill({ json: body })
  })
  await openMetrics(page)
  await page.getByRole('button', { name: '查询', exact: true }).click()
  await expect(page.getByRole('img', { name: '指标曲线' })).toBeVisible()
  await expect(page.locator('[data-derivation]')).toHaveCount(0)
  await expect(page.getByRole('button', { name: '变化率', exact: true })).toHaveCount(0)
  await expect(page.locator('[data-series-view="raw"]')).toContainText('Last: 40')
})
