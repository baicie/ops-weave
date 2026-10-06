import { test, expect, type Page } from '@playwright/test'
import { readFileSync, mkdirSync } from 'node:fs'
import { createHash } from 'node:crypto'
import { TOKEN_OK } from './helpers.ts'

const bundle = JSON.parse(readFileSync(new URL('../../../contracts/catalog/opsweave-core-1.0.0.json', import.meta.url), 'utf8'))
const definition = JSON.parse(readFileSync(new URL('../../../contracts/examples/v2/metric-mapping-definition.json', import.meta.url), 'utf8'))
const original = JSON.parse(readFileSync(new URL('../../../contracts/examples/v2/metric-mapping-binding.json', import.meta.url), 'utf8'))
function digest(parts: string[]) { return 'sha256:' + createHash('sha256').update(Buffer.concat(parts.map(part => { const b = Buffer.from(part); return Buffer.concat([Buffer.from(b.length + ':'), b]) }))).digest('hex') }
async function setup(page: Page, mode = 'normal') {
  const binding = { ...structuredClone(original), version: 1, mappingPin: null }
  let receipt: any = null
  const calls: { path: string; method: string; body: any }[] = []
  await page.route(/\/api\/v1\/catalog(?:\/|$)/, r => r.fulfill({ json: { schemaVersion: '1.0', storage: 'memory', package: bundle, published: { items: [], truncated: false }, drafts: { items: [], truncated: false } } }))
  await page.route(/\/api\/v2\/metric-bindings(?:\/|$)/, async r => {
    const path = new URL(r.request().url()).pathname, method = r.request().method(), body = r.request().postDataJSON(); calls.push({ path, method, body })
    const descriptor = structuredClone(definition); if (mode === 'bad-definition') descriptor.minimum = '0.5'
    const view = { binding, canConfigure: mode !== 'readonly', candidates: [descriptor] }
    if (path === '/api/v2/metric-bindings') {
      if (mode === 'forbidden') return r.fulfill({ status: 403, json: { error: 'METRIC_MAPPING_FORBIDDEN' } })
      return r.fulfill({ json: { schemaVersion: '2.0', items: [view], truncated: false } })
    }
    if (path.endsWith('/mapping')) {
      if (mode === 'conflict') return r.fulfill({ status: 409, json: { error: 'METRIC_MAPPING_CONFLICT' } })
      const previousPin = binding.mappingPin
      binding.version++; binding.mappingPin = body.mappingPin
      receipt = { requestId: body.requestId, expectedBindingVersion: body.expectedBindingVersion, commandDigest: digest(['metric-mapping-maintenance-v1', binding.sourceInstanceId, binding.externalItemId, body.requestId, String(body.expectedBindingVersion), body.mappingPin.id, String(body.mappingPin.revision), body.mappingPin.digest]), previousPin, binding: structuredClone(binding), createdAt: '2026-10-03T00:00:00Z' }
      if (mode === 'unknown' || mode === 'bad-original') return r.abort('failed')
      return r.fulfill({ json: { schemaVersion: '2.0', receipt } })
    }
    if (path.includes('/commands/')) {
      const saved = structuredClone(receipt); if (mode === 'bad-original') saved.binding.valueTransform = 'identity'
      return r.fulfill({ json: { schemaVersion: '2.0', receipt: saved } })
    }
    return r.fulfill({ json: { schemaVersion: '2.0', view } })
  })
  await page.goto('/#/modeling/metrics'); await page.getByRole('textbox', { name: '平台开发 Token（仅保存在当前标签页内存）' }).fill(TOKEN_OK)
  await expect(page.getByRole('button', { name: '来源绑定', exact: true })).toBeEnabled(); expect(calls).toHaveLength(0)
  await page.getByRole('button', { name: '来源绑定', exact: true }).click()
  const drawer = page.getByRole('dialog', { name: '指标来源绑定', exact: true }); if (mode !== 'forbidden') await expect(drawer).toBeVisible()
  return { drawer, calls }
}
for (const width of [1440, 390]) for (const theme of ['light', 'dark']) test(`explicit fixture mapping drawer ${width} ${theme}`, async ({ page }) => {
  await page.setViewportSize({ width, height: 940 }); await page.addInitScript(theme => localStorage.setItem('opsweave.ui.theme', theme), theme)
  const f = await setup(page); const table = f.drawer.getByRole('table', { name: '指标来源绑定列表' }); await expect(table).toContainText('host.cpu.usage.user'); await expect(table).toContainText('未固定'); expect(f.calls.filter(c => c.path === '/api/v2/metric-bindings')).toHaveLength(1)
  await f.drawer.getByRole('button', { name: '维护映射', exact: true }).click(); await expect(f.drawer.getByLabel('映射规则详情')).toContainText('system.cpu.util[,user]'); await expect(f.drawer.getByLabel('映射规则详情')).toContainText('multiply:0.01'); await expect(f.drawer.getByLabel('映射规则详情')).toContainText(definition.mappingPin.digest)
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  if (process.env.OPSWEAVE_MAPPING_SCREENSHOTS) { mkdirSync(process.env.OPSWEAVE_MAPPING_SCREENSHOTS, { recursive: true }); await page.screenshot({ path: process.env.OPSWEAVE_MAPPING_SCREENSHOTS + `/mapping-${width}-${theme}.png`, fullPage: true }) }
  await f.drawer.getByRole('button', { name: '确认固定映射', exact: true }).click(); await expect(f.drawer.getByRole('status').filter({ hasText: '映射维护结果已确认' })).toBeVisible(); await expect(f.drawer.getByRole('button', { name: '确认更新映射', exact: true })).toBeDisabled()
  const submitted = f.calls.filter(c => c.method === 'POST'); expect(submitted).toHaveLength(1); expect(Object.keys(submitted[0].body).sort()).toEqual(['expectedBindingVersion', 'mappingPin', 'requestId'])
  await f.drawer.getByRole('button', { name: '关闭指标来源绑定', exact: true }).click(); await page.getByRole('button', { name: '来源绑定', exact: true }).click(); expect(f.calls.filter(c => c.path === '/api/v2/metric-bindings')).toHaveLength(1)
})
test('unconfirmed command locks edits and confirms only original GET', async ({ page }) => {
  const f = await setup(page, 'unknown'); await f.drawer.getByRole('button', { name: '维护映射', exact: true }).click(); await f.drawer.getByRole('button', { name: '确认固定映射', exact: true }).click(); await expect(f.drawer.getByRole('button', { name: '查询原映射请求', exact: true })).toBeEnabled()
  await expect(f.drawer.getByRole('combobox', { name: '选定指标映射' })).toBeDisabled(); await expect(f.drawer.getByRole('button', { name: '关闭指标来源绑定', exact: true })).toBeDisabled(); await page.keyboard.press('Escape'); await expect(f.drawer).toBeVisible()
  await f.drawer.getByRole('button', { name: '查询原映射请求', exact: true }).click(); await expect(f.drawer.getByText('映射维护结果已确认', { exact: true })).toBeVisible()
  expect(f.calls.filter(c => c.method === 'POST')).toHaveLength(1); const post = f.calls.find(c => c.method === 'POST')!; expect(f.calls.some(c => c.method === 'GET' && c.path.endsWith('/commands/' + post.body.requestId))).toBe(true)
})
test('wrong original semantics retain the original command lock', async ({ page }) => {
  const f = await setup(page, 'bad-original'); await f.drawer.getByRole('button', { name: '维护映射', exact: true }).click(); await f.drawer.getByRole('button', { name: '确认固定映射', exact: true }).click(); await expect(f.drawer.getByRole('button', { name: '查询原映射请求', exact: true })).toBeEnabled(); await f.drawer.getByRole('button', { name: '查询原映射请求', exact: true }).click(); await expect(f.drawer.getByRole('alert')).toContainText('不符合契约'); await expect(f.drawer.getByRole('button', { name: '关闭指标来源绑定', exact: true })).toBeDisabled(); expect(f.calls.filter(c => c.method === 'POST')).toHaveLength(1)
})
test('CAS rejection requires a fresh explicit read and never retries POST', async ({ page }) => {
  const f = await setup(page, 'conflict'); await f.drawer.getByRole('button', { name: '维护映射', exact: true }).click(); await f.drawer.getByRole('button', { name: '确认固定映射', exact: true }).click(); await expect(f.drawer.getByRole('alert')).toContainText('绑定版本'); await expect(f.drawer.getByRole('button', { name: '读取最新绑定', exact: true })).toBeEnabled(); await expect(f.drawer.getByRole('button', { name: '确认固定映射', exact: true })).toBeDisabled(); await f.drawer.getByRole('button', { name: '读取最新绑定', exact: true }).click(); expect(f.calls.filter(c => c.method === 'POST')).toHaveLength(1); expect(f.calls.filter(c => c.method === 'GET' && c.path.endsWith('/20001'))).toHaveLength(2)
})
test('authorization loss clears the binding drawer and does not retry', async ({ page }) => {
  const f = await setup(page, 'forbidden'); await expect(f.drawer).not.toBeVisible(); expect(f.calls).toHaveLength(1); await expect(page.getByRole('table', { name: '指标来源绑定列表' })).toHaveCount(0)
})
test('changed numeric range with old digest is rejected before maintenance', async ({ page }) => {
  const f = await setup(page, 'bad-definition'); await expect(f.drawer.getByRole('alert')).toContainText('不符合契约'); await expect(f.drawer.getByRole('table')).toHaveCount(0); expect(f.calls).toHaveLength(1)
})
test('read-only identities can inspect rules without a submit control', async ({ page }) => {
  const f = await setup(page, 'readonly'); await f.drawer.getByRole('button', { name: '查看映射', exact: true }).click(); await expect(f.drawer.getByLabel('映射规则详情')).toContainText('system.cpu.util[,user]'); await expect(f.drawer.getByRole('button', { name: '确认固定映射', exact: true })).toHaveCount(0); await expect(f.drawer.getByRole('combobox', { name: '选定指标映射' })).toBeDisabled(); expect(f.calls.every(c => c.method === 'GET')).toBe(true)
})
