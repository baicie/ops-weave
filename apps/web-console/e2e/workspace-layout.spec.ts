import { WORKFLOW_OPERATORS } from './helpers.ts'
import { expect, test, type Page } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { TOKEN_OK, mockEmptySourceInstances } from './helpers.ts'
import { ROUTES, groupFor, type RouteName } from '../src/state/routes.ts'

test.beforeEach(async ({ page }) => {
  await mockEmptySourceInstances(page)
  const sourcePage = JSON.parse(readFileSync(new URL('../../../contracts/examples/source-center-page.json', import.meta.url), 'utf8'))
  await page.route('**/api/v1/integrations/sources', route => route.fulfill({ json: { ...sourcePage, setups: { items: [], truncated: false } } }))
})

const token = (page: Page) => page.getByRole('textbox', { name: '平台开发 Token（仅保存在当前标签页内存）' })
const tabs = (page: Page) => page.getByRole('tablist', { name: '已打开页面' })
const activePane = (page: Page) => page.locator('.page-pane:not([hidden])')
async function centered(page: Page, selector: string) {
  const box = (await page.locator(selector).boundingBox())!
  const viewport = page.viewportSize()!
  expect(Math.abs(box.x + box.width / 2 - viewport.width / 2)).toBeLessThan(2)
  expect(Math.abs(box.y + box.height / 2 - viewport.height / 2)).toBeLessThan(2)
}
async function layout(page: Page, mode = 'tabs') {
  await page.getByRole('button', { name: '布局设置', exact: true }).click()
  await centered(page, '.layout-settings')
  await page.getByRole('radio', { name: mode === 'tabs' ? '多页签布局 多个页面同时打开，切换时保留编辑' : '标准布局 聚焦当前页面' }).check()
  await page.getByRole('button', { name: '完成', exact: true }).click()
  await expect(page.getByRole('main')).toHaveAttribute('data-layout', mode)
}
async function open(page: Page, name: RouteName) {
  const nav = page.getByRole('navigation', { name: '产品模块' })
  const group = nav.getByRole('button', { name: groupFor(name).label, exact: true })
  if (await group.getAttribute('aria-expanded') === 'false') await group.click()
  await nav.getByRole('link', { name: ROUTES.find(route => route.name === name)!.label, exact: true }).click()
  await expect(page.getByRole('main')).toHaveAttribute('data-route', name)
}
async function draft(page: Page) {
  const bundle = JSON.parse(readFileSync(new URL('../../../contracts/catalog/opsweave-core-1.0.0.json', import.meta.url), 'utf8'))
  const calls: string[] = []
  await page.route('**/api/v1/integrations/workflows', route => {
    calls.push(route.request().method())
    return route.fulfill({ json: { schemaVersion: '2.0', storage: 'memory',operatorCatalog:WORKFLOW_OPERATORS, drafts: { items: [], truncated: false }, published: { items: [], truncated: false }, models: bundle.definitions.filter((model: any) => model.kind === 'ENTITY').map((definition: unknown) => ({ definition, digest: 'sha256:' + 'a'.repeat(64) })), modelsTruncated: false, zabbixSource: { instanceId: 'zabbix-fixture', mode: 'fixture' }, runs: { items: [], truncated: false } } })
  })
  await page.goto('/#/integrations/workflows')
  await token(page).fill(TOKEN_OK)
  await layout(page)
  await expect(page.getByRole('button', { name: '新建工作流', exact: true })).toBeVisible()
  await page.getByRole('button', { name: '新建工作流', exact: true }).click()
  await page.getByRole('button', { name: '＋ 自定义实体模板', exact: true }).click()
  await expect(page.locator('[data-engine=x6]')).toHaveAttribute('data-graph-status', 'ready')
  await page.getByRole('textbox', { name: '工作流名称', exact: true }).fill('Fixture 页签草稿')
  return calls
}

test('layout preference survives refresh while open pages and private values stay in memory', async ({ page }) => {
  await page.goto('/#/inventory')
  await expect(page.getByRole('main')).toHaveAttribute('data-layout', 'tabs')
  await layout(page, 'standard')
  await layout(page)
  await page.getByRole('textbox', { name: '名称或 IP' }).fill('private unsent fixture')
  await open(page, 'source-center')
  await expect(tabs(page).getByRole('tab')).toHaveCount(2)
  expect(await page.evaluate(() => ({ ...localStorage, ...sessionStorage }))).toEqual({ 'opsweave.ui.layout': 'tabs' })
  await page.reload()
  await expect(page.getByRole('main')).toHaveAttribute('data-layout', 'tabs')
  await expect(tabs(page).getByRole('tab')).toHaveCount(1)
  await open(page, 'inventory')
  await expect(page.getByRole('textbox', { name: '名称或 IP' })).toHaveValue('')
})

test('invalid stored layout uses the default tabs layout', async ({ page }) => {
  await page.addInitScript(() => localStorage.setItem('opsweave.ui.layout', 'untrusted-value'))
  await page.goto('/#/inventory')
  await expect(page.getByRole('main')).toHaveAttribute('data-layout', 'tabs')
  await expect(tabs(page).getByRole('tab')).toHaveCount(1)
})

test('a fresh standard layout releases previous pages', async ({ page }) => {
  await page.addInitScript(() => localStorage.setItem('opsweave.ui.layout', 'standard'))
  await page.goto('/#/inventory')
  await page.getByRole('textbox', { name: '名称或 IP' }).fill('unsent fixture')
  await open(page, 'source-center')
  await expect(page.locator('.page-pane')).toHaveCount(1)
  await open(page, 'inventory')
  await expect(page.getByRole('textbox', { name: '名称或 IP' })).toHaveValue('')
})

test('tabs preserve a workflow draft across pages and layout changes without API writes', async ({ page }) => {
  const calls = await draft(page)
  await open(page, 'inventory')
  await page.getByRole('textbox', { name: '名称或 IP' }).fill('unapplied filter fixture')
  await tabs(page).getByRole('tab', { name: '数据工作流', exact: true }).click()
  await expect(page.getByRole('textbox', { name: '工作流名称', exact: true })).toHaveValue('Fixture 页签草稿')
  await expect(activePane(page).locator('.workflow-node')).toHaveCount(5)
  await layout(page, 'standard')
  await open(page, 'inventory')
  await expect(page.getByRole('textbox', { name: '名称或 IP' })).toHaveValue('unapplied filter fixture')
  await layout(page)
  await tabs(page).getByRole('tab', { name: '数据工作流', exact: true }).click()
  await expect(page.getByRole('textbox', { name: '工作流名称', exact: true })).toHaveValue('Fixture 页签草稿')
  expect(calls).toEqual(['GET'])
  expect(await page.evaluate(() => JSON.stringify({ ...localStorage, ...sessionStorage }))).not.toContain('Fixture 页签草稿')
})

test('small-screen workflow panels close on page changes while keeping configuration in memory', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 1000 })
  const errors: string[] = []; page.on('pageerror', error => errors.push(error.message))
  const calls = await draft(page)
  await page.getByRole('button', { name: '字段映射节点 mapping', exact: true }).click()
  await page.getByRole('textbox', { name: '来源字段 → name', exact: true }).fill('fixture_cached_name')
  // A browser history/address change can happen while a non-modal panel is open.
  await page.evaluate(() => { location.hash = '#/inventory' })
  await expect(page.getByRole('main')).toHaveAttribute('data-route', 'inventory')
  await expect(page.locator('.studio-inspector:popover-open')).toHaveCount(0)
  await page.getByRole('textbox', { name: '名称或 IP' }).fill('Fixture active page')
  await tabs(page).getByRole('tab', { name: '数据工作流', exact: true }).click()
  await page.getByRole('button', { name: '更多工作流操作', exact: true }).click();await page.getByRole('button', { name: '节点配置', exact: true }).click()
  await expect(page.getByRole('textbox', { name: '来源字段 → name', exact: true })).toHaveValue('fixture_cached_name')
  await page.getByRole('button', { name: '关闭节点配置', exact: true }).click()
  await page.getByRole('button', { name: '连接与分支 4', exact: true }).click()
  await expect(page.getByRole('dialog', { name: '连接管理', exact: true })).toBeVisible()
  await tabs(page).getByRole('tab', { name: '资产', exact: true }).click()
  await expect(page.locator('.workflow-connection-editor:popover-open')).toHaveCount(0)
  await expect(page.getByRole('textbox', { name: '名称或 IP' })).toHaveValue('Fixture active page')
  await tabs(page).getByRole('tab', { name: '数据工作流', exact: true }).click()
  await page.getByRole('button', { name: '字段映射节点 mapping', exact: true }).click({ button: 'right' })
  await expect(page.getByRole('menu', { name: '节点操作', exact: true })).toBeVisible()
  await page.evaluate(() => { location.hash = '#/inventory' })
  await expect(page.getByRole('main')).toHaveAttribute('data-route', 'inventory')
  await expect(page.locator('.workflow-node-menu:popover-open')).toHaveCount(0)
  expect(calls).toEqual(['GET'])
  expect(errors).toEqual([])
})

test('dirty page closure defaults to keeping edits and confirmed closure removes the draft', async ({ page }) => {
  const calls = await draft(page)
  await open(page, 'inventory')
  await page.getByRole('button', { name: '关闭数据工作流页面', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '关闭页面前确认' })
  await expect(dialog).toContainText('工作流有未保存的修改')
  await centered(page, '.page-close-dialog')
  await expect(dialog.getByRole('button', { name: '保留页面' })).toBeFocused()
  await page.keyboard.press('Escape')
  await tabs(page).getByRole('tab', { name: '数据工作流', exact: true }).click()
  await expect(page.getByRole('textbox', { name: '工作流名称', exact: true })).toHaveValue('Fixture 页签草稿')
  await page.getByRole('button', { name: '关闭数据工作流页面', exact: true }).click()
  await dialog.getByRole('button', { name: '关闭页面', exact: true }).click()
  await expect(tabs(page).getByRole('tab')).toHaveCount(1)
  await expect(page.getByRole('main')).toHaveAttribute('data-route', 'inventory')
  await expect(tabs(page).getByRole('tab', { name: '资产', exact: true })).toBeFocused()
  await open(page, 'workflows')
  await expect(page.locator('.workflow-node')).toHaveCount(0)
  await expect(page.getByRole('button', { name: '新建工作流', exact: true })).toBeVisible()
  expect(calls).toEqual(['GET', 'GET'])
})

test('identity changes release hidden private pages and clear the current page', async ({ page }) => {
  await draft(page)
  await open(page, 'inventory')
  await page.getByRole('textbox', { name: '名称或 IP' }).fill('private fixture search')
  await token(page).fill('changed-fixture-token-32-characters')
  await expect(tabs(page).getByRole('tab')).toHaveCount(1)
  await expect(page.locator('[data-page-route=workflows]')).toHaveCount(0)
  await expect(page.getByRole('textbox', { name: '名称或 IP' })).toHaveValue('')
  await open(page, 'workflows')
  await expect(page.locator('.workflow-node')).toHaveCount(0)
})

test('sidebar activation restores the remembered query and unsubmitted filter without duplicate tabs', async ({ page }) => {
  const calls: string[] = []
  await page.route('**/api/v1/entities/page?*', route => {
    const q = new URL(route.request().url()).searchParams
    calls.push(route.request().url())
    return route.fulfill({ json: { storage: 'postgres', query: { q: q.get('q'), type: q.get('type'), lifecycle: q.get('lifecycle'), after: q.get('after'), limit: 25 }, items: [], nextCursor: null } })
  })
  await page.goto('/#/inventory')
  await token(page).fill(TOKEN_OK)
  await layout(page)
  await page.getByRole('textbox', { name: '名称或 IP' }).fill('applied fixture')
  await page.getByRole('button', { name: '刷新列表', exact: true }).click()
  await expect(page).toHaveURL(/q=applied/)
  const remembered = new URL(page.url()).hash
  await page.getByRole('textbox', { name: '名称或 IP' }).fill('unsubmitted fixture')
  await open(page, 'source-center')
  await open(page, 'inventory')
  await expect(page).toHaveURL(url => url.hash === remembered)
  await expect(page.getByRole('textbox', { name: '名称或 IP' })).toHaveValue('unsubmitted fixture')
  await expect(tabs(page).getByRole('tab')).toHaveCount(2)
  expect(calls).toHaveLength(2)
  expect(new URL(calls[0]!).searchParams.get('q')).toBe('')
  expect(new URL(calls[1]!).searchParams.get('q')).toBe('applied fixture')
  await page.goBack()
  await expect(page.getByRole('main')).toHaveAttribute('data-route', 'source-center')
  await page.goForward()
  await expect(page.getByRole('textbox', { name: '名称或 IP' })).toHaveValue('unsubmitted fixture')
  expect(calls).toHaveLength(2)
})

test('late reads from an inactive page cannot restore private data after identity changes', async ({ page }) => {
  let release = () => {}, settled = () => {}
  const held = new Promise<void>(resolve => { release = resolve })
  const finished = new Promise<void>(resolve => { settled = resolve })
  let calls = 0
  await page.route('**/api/v1/entities/page?*', async route => {
    calls++
    const q = new URL(route.request().url()).searchParams
    await held
    try { await route.fulfill({ json: { storage: 'postgres', query: { q: q.get('q'), type: q.get('type'), lifecycle: q.get('lifecycle'), after: q.get('after'), limit: 25 }, items: [], nextCursor: null } }) }
    finally { settled() }
  })
  await page.goto('/#/inventory')
  await token(page).fill(TOKEN_OK)
  await layout(page)
  await expect.poll(() => calls).toBe(1)
  await open(page, 'source-center')
  await token(page).fill('changed-fixture-token-32-characters')
  await expect(page.locator('[data-page-route=inventory]')).toHaveCount(0)
  release(); await finished
  await open(page, 'inventory')
  await expect(page.locator('[data-inventory-page]')).toContainText('本页 0 条')
  expect(calls).toBe(2)
})

test('tabs support keyboard navigation, active closure and close all with a fresh home page', async ({ page }) => {
  await page.goto('/#/start')
  await layout(page)
  await open(page, 'inventory')
  await open(page, 'source-center')
  const source = tabs(page).getByRole('tab', { name: '数据源中心', exact: true })
  await source.focus()
  await page.keyboard.press('ArrowLeft')
  await expect(tabs(page).getByRole('tab', { name: '资产', exact: true })).toBeFocused()
  await expect(activePane(page)).toHaveAttribute('data-page-route', 'inventory')
  await page.keyboard.press('Home')
  await expect(activePane(page)).toHaveAttribute('data-page-route', 'start')
  await page.keyboard.press('End')
  await expect(activePane(page)).toHaveAttribute('data-page-route', 'source-center')
  await page.keyboard.press('Delete')
  await expect(tabs(page).getByRole('tab')).toHaveCount(2)
  await expect(activePane(page)).toHaveAttribute('data-page-route', 'inventory')
  await page.getByRole('button', { name: '页签操作', exact: true }).click()
  await page.getByRole('button', { name: '关闭其他页面', exact: true }).click()
  await expect(tabs(page).getByRole('tab')).toHaveCount(1)
  await page.getByRole('button', { name: '页签操作', exact: true }).click()
  await page.getByRole('button', { name: '关闭所有页面', exact: true }).click()
  await expect(tabs(page).getByRole('tab', { name: '运维工作台', exact: true })).toBeVisible()
  await expect(activePane(page)).toHaveAttribute('data-page-route', 'start')
})

test('scroll position belongs to each page and the header stays visible', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 700 })
  await draft(page)
  const main = page.getByRole('main')
  await main.evaluate(element => { element.scrollTop = 250 })
  await expect.poll(() => main.evaluate(element => element.scrollTop)).toBeGreaterThan(200)
  const position = await main.evaluate(element => element.scrollTop)
  await open(page, 'source-center')
  await expect.poll(() => main.evaluate(element => element.scrollTop)).toBe(0)
  await tabs(page).getByRole('tab', { name: '数据工作流', exact: true }).click()
  await expect.poll(() => main.evaluate(element => element.scrollTop)).toBe(position)
  await expect(page.getByRole('button', { name: '布局设置', exact: true })).toBeInViewport()
  await expect(page.getByRole('navigation', { name: '当前位置' })).toBeInViewport()
  await expect(page.getByRole('navigation', { name: '当前位置' }).locator('[aria-current=page]')).toHaveText('数据工作流')
  expect(await page.evaluate(() => scrollY)).toBe(0)
})

for (const width of [1440, 1024, 390]) test('all tabbed product pages and themes stay bounded at ' + width, async ({ page }) => {
  await page.setViewportSize({ width, height: 900 })
  const errors: string[] = [], calls: string[] = []
  page.on('pageerror', error => errors.push(error.message))
  page.on('request', request => { if (request.url().includes('/api/')) calls.push(request.url()) })
  await page.goto('/#/start')
  await layout(page)
  for (const route of ROUTES.filter(route => route.navigation !== false)) {
    if (width === 390) await page.getByRole('button', { name: '切换导航', exact: true }).click()
    await open(page, route.name)
    await expect(activePane(page).getByRole('heading').first()).toBeVisible()
    await expect(page.locator('.page-pane:not([hidden])')).toHaveCount(1)
    const breadcrumb = page.getByRole('navigation', { name: '当前位置' })
    await expect(breadcrumb.locator('[aria-current=page]')).toHaveText(route.label)
    for (const theme of ['dark', 'light']) {
      await page.getByRole('button', { name: theme === 'dark' ? '切换到深色模式' : '切换到浅色模式' }).click()
      await expect(page.locator('html')).toHaveAttribute('data-theme', theme)
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), route.path + ' ' + theme).toBe(true)
      const panel = await activePane(page).boundingBox()
      expect(panel!.width, route.path).toBeGreaterThan(width === 390 ? 300 : 600)
      const header = (await page.getByRole('banner').boundingBox())!
      const strip = (await tabs(page).boundingBox())!
      const trail = (await breadcrumb.boundingBox())!
      expect(header.y + header.height).toBeLessThanOrEqual(strip.y)
      expect(strip.y + strip.height).toBeLessThanOrEqual(trail.y)
      expect(trail.y + trail.height).toBeLessThanOrEqual(panel!.y)
      expect(trail.height).toBeLessThanOrEqual(width === 390 ? 32 : 36)
      expect(await breadcrumb.locator('ol').evaluate(element => element.getBoundingClientRect().left)).toBeCloseTo(panel!.x, 0)
    }
  }
  await expect(tabs(page).getByRole('tab')).toHaveCount(16)
  expect(errors).toEqual([])
  expect(calls).toEqual([])
})
