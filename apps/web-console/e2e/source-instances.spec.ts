import { test, expect, type Page } from '@playwright/test'
import { createHash } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { TOKEN_OK } from './helpers.ts'
import type { InstanceEdit, SourceInstance } from '../src/api/source-instances.ts'
const sample = (name: string) => JSON.parse(readFileSync(new URL('../../../contracts/examples/' + name, import.meta.url), 'utf8'))
function digest(id: string, c: InstanceEdit) { const hash = createHash('sha256'); for (const part of ['source-instance-edit-v2', id, c.requestId, String(c.expectedEditVersion), c.name, c.description, c.connectionDigest, c.state]) { const bytes = Buffer.from(part); hash.update(bytes.length + ':'); hash.update(bytes) }; return 'sha256:' + hash.digest('hex') }
async function fixture(page: Page, failure?: 'unknown' | 'mismatch' | 'conflict' | 'forbidden') {
  const sources = sample('source-center-page.json'), sourcePage = sample('v2/source-instance-page.json')
  let current = structuredClone(sourcePage.items[0]) as SourceInstance, receipt: unknown, reads = 0, patches = 0
  const calls: string[] = []
  await page.route('**/api/v1/integrations/sources', route => route.fulfill({ json: sources }))
  await page.route('**/api/v2/data-sources**', route => {
    const path = new URL(route.request().url()).pathname, method = route.request().method(); calls.push(method + ' ' + path)
    if (method === 'PATCH') {
      patches++; const c = route.request().postDataJSON() as InstanceEdit
      if (failure === 'conflict') { current = { ...current, name: 'Fixture external change', editVersion: 2 }; return route.fulfill({ status: 409, json: { error: 'CONFLICT' } }) }
      current = { ...current, name: c.name, description: c.description, connectionDigest: c.connectionDigest, state: c.state, editVersion: c.expectedEditVersion + 1, updatedAt: '2026-10-03T01:00:00Z' }
      receipt = { schemaVersion: '2.0', receipt: { requestId: c.requestId, sourceId: current.id, commandDigest: digest(current.id, c), instance: current } }
      if (failure === 'unknown' || failure === 'mismatch') return route.abort('failed')
      return route.fulfill({ json: receipt })
    }
    if (path.includes('/commands/')) return route.fulfill({ json: failure === 'mismatch' ? { schemaVersion: '2.0', receipt: { requestId: '20000000-0000-4000-8000-000000000099', sourceId: current.id, commandDigest: 'sha256:' + 'b'.repeat(64), instance: current } } : receipt })
    if (path.endsWith('/configurations')) return route.fulfill({ json: sample('v2/source-instance-configurations.json') })
    if (path === '/api/v2/data-sources') { reads++; if (failure === 'forbidden' && reads === 1) return route.fulfill({ status: 403, json: { error: 'FORBIDDEN' } }); return route.fulfill({ json: { ...sourcePage, items: [current] } }) }
    return route.fulfill({ json: { schemaVersion: '2.0', instance: current } })
  })
  await page.goto('/#/integrations/sources')
  await page.getByRole('textbox', { name: '平台开发 Token（仅保存在当前标签页内存）' }).fill(TOKEN_OK)
  await expect(page.getByRole('tab', { name: /已配置接入/ })).toBeVisible()
  await expect.poll(() => reads).toBe(1)
  return { calls, reads: () => reads, patches: () => patches, instance: () => current }
}
async function open(page: Page) { await page.getByRole('button', { name: '维护实例：Fixture Zabbix onboarding', exact: true }).click(); const dialog = page.getByRole('dialog', { name: '维护接入实例' }); await expect(dialog).toBeVisible(); return dialog }

test('primary list uses only authorized current metadata and never fills missing rows from creation receipts', async ({ page }) => {
  const sources = sample('source-center-page.json'), legacy = sample('v2/source-instance-page.json').items[0], connection = sample('v2/source-connection-read.json').instance
  const revised = { ...legacy,name:'Fixture renamed archived instance',configurationRevision:2,connectionDigest:'sha256:'+'c'.repeat(64),editVersion:3,state:'ARCHIVED',updatedAt:'2026-10-03T02:00:00Z' }
  const absent = { ...sources.setups.items[0],id:'44444444-4444-4444-8444-444444444444',workflowId:'source-44444444-4444-4444-8444-444444444444',name:'Fixture creation outside current scope' }
  sources.setups.items.push(absent)
  const calls: string[] = []
  await page.route('**/api/v2/data-sources',route=>{calls.push('instances');return route.fulfill({json:{schemaVersion:'2.0',storage:'memory',items:[revised,connection],truncated:false}})})
  await page.route('**/api/v1/integrations/sources',route=>{calls.push('receipts');return route.fulfill({json:sources})})
  await page.goto('/#/integrations/sources'); await page.getByRole('textbox',{name:'平台开发 Token（仅保存在当前标签页内存）'}).fill(TOKEN_OK)
  const table = page.getByRole('table',{name:'接入实例列表'}); await expect(table.locator('tbody tr')).toHaveCount(1)
  await expect(page.getByRole('tab',{name:'已配置接入（2）',exact:true})).toHaveAttribute('aria-selected','true'); expect(calls).toEqual(['instances'])
  await expect(page.getByRole('link',{name:'编排配置 v1：'+connection.name,exact:true})).toHaveAttribute('href',new RegExp('configurationRevision=1&connectionDigest=sha256%3A'+connection.connectionDigest.slice(7)))
  await page.getByRole('button',{name:'已归档',exact:true}).click(); await expect(table.locator('tbody tr')).toHaveCount(1); await expect(table).toContainText(revised.name); await expect(table).toContainText('v2')
  await page.getByRole('tab',{name:'接入回执',exact:true}).click(); await expect(page.getByRole('table',{name:'接入回执'})).toContainText(absent.name)
  await page.getByRole('tab',{name:/已配置接入/}).click(); await page.getByRole('button',{name:'全部',exact:true}).click()
  await expect(table.locator('tbody tr')).toHaveCount(2); await expect(table).not.toContainText(absent.name); await expect(table).not.toContainText(legacy.name); expect(calls).toEqual(['instances','receipts'])
  await page.getByRole('combobox',{name:'按接入类型筛选实例'}).selectOption('MANUAL_SAMPLE'); await expect(table.locator('tbody tr')).toHaveCount(0)
  await page.getByRole('combobox',{name:'按接入类型筛选实例'}).selectOption('ALL'); await page.getByRole('textbox',{name:'搜索接入实例'}).fill('renamed'); await expect(table.locator('tbody tr')).toHaveCount(1); expect(calls).toEqual(['instances','receipts'])
})

test('failed primary list does not load creation receipts and retries only explicitly', async ({ page }) => {
  let reads=0,receipts=0
  await page.route('**/api/v2/data-sources',route=>route.fulfill(++reads===1?{status:503,json:{error:'UNAVAILABLE'}}:{json:sample('v2/source-instance-page.json')}))
  await page.route('**/api/v1/integrations/sources',route=>{receipts++;return route.fulfill({json:sample('source-center-page.json')})})
  await page.goto('/#/integrations/sources');await page.getByRole('textbox',{name:'平台开发 Token（仅保存在当前标签页内存）'}).fill(TOKEN_OK)
  await expect(page.getByText('接入实例读取失败',{exact:true})).toBeVisible();expect(reads).toBe(1);expect(receipts).toBe(0)
  await page.getByRole('button',{name:'切换到深色模式'}).click();await page.getByRole('button',{name:'刷新实例列表',exact:true}).click()
  await expect(page.getByRole('table',{name:'接入实例列表'})).toContainText('Fixture Zabbix onboarding');expect(reads).toBe(2);expect(receipts).toBe(0)
})

for (const width of [1440, 1024, 390]) for (const theme of ['light', 'dark']) test('explicit fixture instance maintenance at ' + width + ' ' + theme, async ({ page }) => {
  await page.setViewportSize({ width, height: 940 }); await page.addInitScript(theme => { localStorage.setItem('opsweave.ui.theme', theme) }, theme)
  const f = await fixture(page)
  if (width === 390) {
    const panel = page.getByRole('region',{name:'实例管理',exact:true})
    const toolbar = (await panel.locator('.source-instance-toolbar').boundingBox())!, search = (await panel.locator('.source-instance-toolbar .integration-search').boundingBox())!
    expect(search.width).toBeGreaterThan(toolbar.width * 0.95)
    expect((await page.getByRole('tab',{name:/已配置接入/}).boundingBox())!.height).toBeLessThan(55)
  }
  const dialog = await open(page)
  await dialog.getByRole('textbox', { name: '实例名称', exact: true }).fill('Fixture revised source')
  await dialog.getByRole('button', { name: '保存实例', exact: true }).click()
  await expect(dialog.getByRole('status')).toHaveText('实例维护已确认')
  await dialog.getByRole('tab', { name: '配置历史', exact: true }).click()
  await expect(dialog.getByRole('table', { name: '配置历史', exact: true })).toContainText('v1')
  await dialog.getByRole('tab', { name: '实例配置', exact: true }).click()
  await dialog.getByRole('button', { name: '归档实例', exact: true }).click()
  await dialog.getByRole('button', { name: '确认归档', exact: true }).click()
  await expect(dialog.getByRole('button', { name: '恢复实例', exact: true })).toBeVisible()
  await expect(dialog.getByRole('textbox', { name: '实例名称', exact: true })).toBeDisabled()
  await dialog.getByRole('button', { name: '恢复实例', exact: true }).click()
  await expect(dialog.getByRole('textbox', { name: '实例名称', exact: true })).toBeEnabled()
  expect(f.patches()).toBe(3); expect(f.instance().configurationRevision).toBe(1); expect(f.instance().editVersion).toBe(4)
  await dialog.getByRole('button', { name: '关闭实例维护' }).click()
  await page.getByRole('tab', { name: '接入类型', exact: true }).click(); await page.getByRole('tab', { name: /已配置接入/ }).click()
  await expect(page.getByRole('table', { name: '接入实例列表' })).toContainText('Fixture revised source'); expect(f.reads()).toBe(1)
  expect(f.calls.filter(path => path.endsWith('/configurations'))).toHaveLength(1)
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width)
})
test('unknown maintenance result locks fields until exact original receipt is verified', async ({ page }) => {
  const f = await fixture(page, 'unknown'), dialog = await open(page)
  await dialog.getByRole('textbox', { name: '实例名称', exact: true }).fill('Fixture verified recovery'); await dialog.getByRole('button', { name: '保存实例', exact: true }).click()
  await expect(dialog.getByRole('button', { name: '查询原维护回执' })).toBeVisible(); await expect(dialog.getByRole('textbox', { name: '实例名称', exact: true })).toBeDisabled()
  await dialog.getByRole('button', { name: '查询原维护回执' }).click(); await expect(dialog.getByRole('status')).toHaveText('实例维护已确认')
  expect(f.patches()).toBe(1); await dialog.getByRole('button', { name: '关闭实例维护' }).click(); await page.getByRole('button', { name: '关闭数据源中心页面' }).click()
  await expect(page.getByRole('dialog', { name: '关闭页面前确认' })).toHaveCount(0)
})
test('mismatched maintenance receipt keeps original pending command and never repeats PATCH', async ({ page }) => {
  const f = await fixture(page, 'mismatch'), dialog = await open(page)
  await dialog.getByRole('textbox', { name: '实例名称', exact: true }).fill('Fixture mismatch'); await dialog.getByRole('button', { name: '保存实例', exact: true }).click()
  await dialog.getByRole('button', { name: '查询原维护回执' }).click(); await expect(dialog.getByRole('alert')).toContainText('原请求仍需核对')
  await expect(dialog.getByRole('textbox', { name: '实例名称', exact: true })).toBeDisabled(); expect(f.patches()).toBe(1)
})
test('CAS rejection keeps local edit and permits explicit reread without polling', async ({ page }) => {
  const f = await fixture(page, 'conflict'), dialog = await open(page)
  await dialog.getByRole('textbox', { name: '实例名称', exact: true }).fill('Fixture stale edit'); await dialog.getByRole('button', { name: '保存实例', exact: true }).click()
  await expect(dialog.getByRole('alert')).toContainText('实例已被修改'); await expect(dialog.getByRole('textbox', { name: '实例名称', exact: true })).toHaveValue('Fixture stale edit'); await expect(dialog.getByRole('textbox', { name: '实例名称', exact: true })).toBeEnabled()
  expect(f.calls.filter(call => call === 'GET /api/v2/data-sources/' + f.instance().id)).toHaveLength(1)
  await dialog.getByRole('button', { name: '放弃修改并重读', exact: true }).click(); await expect(dialog.getByRole('textbox', { name: '实例名称', exact: true })).toHaveValue('Fixture external change'); expect(f.patches()).toBe(1)
})
test('instance authorization failure is retained until explicit refresh', async ({ page }) => {
  const f = await fixture(page, 'forbidden'); await expect(page.locator('.source-instance-panel > [role=alert]')).toContainText('维护权限')
  await page.getByRole('tab', { name: '接入类型', exact: true }).click(); await page.getByRole('tab', { name: /已配置接入/ }).click(); expect(f.reads()).toBe(1)
  await page.getByRole('button', { name: '刷新实例列表', exact: true }).click(); await expect(page.getByRole('table', { name: '接入实例列表' })).toContainText('Fixture Zabbix onboarding'); expect(f.reads()).toBe(2)
})

test('managed instance drawer reads its instance-scoped connection snapshot', async ({ page }) => {
  const view = sample('v2/source-connection-read.json'), instance = view.instance, calls: string[] = []
  await page.route('**/api/v2/data-sources**', route => {
    const path = new URL(route.request().url()).pathname
    calls.push(path)
    if (path === '/api/v2/data-sources') return route.fulfill({ json: { schemaVersion: '2.0', storage: 'memory', items: [instance], truncated: false } })
    if (path === `/api/v2/data-sources/${instance.id}/connection`) return route.fulfill({ json: view })
    if (path === `/api/v2/data-sources/${instance.id}`) return route.fulfill({ json: { schemaVersion: '2.0', instance } })
    return route.fulfill({ json: { schemaVersion: '2.0', sourceId: instance.id, items: [] } })
  })
  await page.goto('/#/integrations/sources')
  await page.getByRole('textbox', { name: '平台开发 Token（仅保存在当前标签页内存）' }).fill(TOKEN_OK)
  const table = page.getByRole('table', { name: '接入实例列表' })
  await expect(table).toContainText(instance.name)
  await page.getByRole('button', { name: '维护实例：' + instance.name, exact: true }).click()
  const drawer = page.getByRole('dialog', { name: '维护接入实例' })
  await expect(drawer).toBeVisible()
  await expect(drawer).toContainText('https://192.0.2.10/api_jsonrpc.php')
  await expect(drawer).toContainText('v1 · 10000000-0000-4000-8000-000000000081')
  expect(calls).toContain(`/api/v2/data-sources/${instance.id}/connection`)
})
