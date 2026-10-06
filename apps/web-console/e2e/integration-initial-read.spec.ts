import { WORKFLOW_OPERATORS } from './helpers.ts'
import { expect, test, type Page } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { TOKEN_OK, mockEmptySourceInstances } from './helpers.ts'

const sample = (name: string) => JSON.parse(readFileSync(new URL('../../../contracts/examples/' + name, import.meta.url), 'utf8'))
const token = (page: Page) => page.getByRole('textbox', { name: '平台开发 Token（仅保存在当前标签页内存）' })
const source = sample('source-center-page.json')
const emptyWorkspace = { schemaVersion: '2.0', storage: 'memory',operatorCatalog:WORKFLOW_OPERATORS, drafts: { items: [], truncated: false }, published: { items: [], truncated: false }, models: [], modelsTruncated: false, zabbixSource: { instanceId: '', mode: 'fixture' }, runs: { items: [], truncated: false } }
async function leaveAndReturn(page: Page, label: string) {
  const nav = page.getByRole('navigation', { name: '产品模块' })
  await nav.getByRole('button', { name: '资源观测', exact: true }).click()
  await nav.getByRole('link', { name: '资产', exact: true }).click()
  await page.getByRole('tablist', { name: '已打开页面' }).getByRole('tab', { name: label, exact: true }).click()
}

test('leaving an initial catalog read cancels it and a late reply cannot fill the hidden page', async ({ page }) => {
  await mockEmptySourceInstances(page)
  let reads=0,release=()=>{},finished=()=>{}
  const held=new Promise<void>(resolve=>{release=resolve}),settled=new Promise<void>(resolve=>{finished=resolve})
  await page.route('**/api/v1/integrations/sources',async route=>{
    reads++;if(reads===1) { await held;try { await route.fulfill({status:503,json:{error:'UNAVAILABLE'}}) } catch {} finally { finished() };return }
    await route.fulfill({json:source})
  })
  await page.goto('/#/integrations/sources');await token(page).fill(TOKEN_OK)
  await page.getByRole('tab',{name:'接入类型',exact:true}).click();await expect.poll(()=>reads).toBe(1)
  await page.getByRole('tab',{name:/已配置接入/}).click();release();await settled
  await expect(page.locator('.source-center > [role=alert]')).toBeEmpty()
  await page.getByRole('tab',{name:'接入类型',exact:true}).click();await expect(page.getByRole('button',{name:'配置 Zabbix →',exact:true})).toBeEnabled();await expect.poll(()=>reads).toBe(2)
  await page.getByRole('tab',{name:/已配置接入/}).click();await page.getByRole('tab',{name:'接入类型',exact:true}).click();expect(reads).toBe(2)
})

test('source list reads once after authorization, caches returns and never probes on view changes', async ({ page }) => {
  const calls: string[] = []
  await mockEmptySourceInstances(page)
  await page.route('**/api/v1/integrations/**', route => {
    calls.push(route.request().method() + ' ' + new URL(route.request().url()).pathname)
    return route.fulfill({ json: source })
  })
  await page.goto('/#/integrations/sources')
  await expect(page.getByRole('tab', { name: /已配置接入/ })).toHaveAttribute('aria-selected', 'true')
  await page.getByRole('tab', { name: '接入类型', exact: true }).click()
  expect(calls).toEqual([])
  await token(page).fill(TOKEN_OK)
  await page.getByRole('tab', { name: '接入类型', exact: true }).click()
  await expect(page.locator('.source-center .integration-source-card')).toHaveCount(3)
  await page.getByRole('textbox', { name: '搜索接入类型' }).fill('zabbix')
  await page.getByRole('button', { name: '切换到深色模式' }).click()
  await leaveAndReturn(page, '数据源中心')
  await expect(page.getByRole('textbox', { name: '搜索接入类型' })).toHaveValue('zabbix')
  expect(calls).toEqual(['GET /api/v1/integrations/sources'])
})

for (const status of [403, 503]) test('source read ' + status + ' persists across tabs until explicit retry', async ({ page }) => {
  let reads = 0
  await mockEmptySourceInstances(page)
  await page.route('**/api/v1/integrations/sources', route => route.fulfill(++reads === 1 ? { status, json: { error: status === 403 ? 'FORBIDDEN' : 'UNAVAILABLE' } } : { json: source }))
  await page.goto('/#/integrations/sources'); await token(page).fill(TOKEN_OK); await page.getByRole('tab', { name: '接入回执', exact: true }).click()
  await expect(page.locator('.source-center > [role=alert]')).not.toBeEmpty()
  await page.getByRole('tab', { name: '接入类型', exact: true }).click()
  await page.getByRole('tab', { name: '接入回执', exact: true }).click()
  await leaveAndReturn(page, '数据源中心')
  await expect(page.getByText('接入配置读取失败', { exact: true })).toBeVisible()
  expect(reads).toBe(1)
  await page.getByRole('button', { name: '读取数据源', exact: true }).click()
  await expect(page.locator('.source-saved-row')).toHaveCount(source.setups.items.length)
  expect(reads).toBe(2)
})

test('workflow authorization failure is retained on page return without a retry loop', async ({ page }) => {
  let reads = 0
  await page.route('**/api/v1/integrations/workflows', route => route.fulfill(++reads === 1 ? { status: 403, json: { error: 'FORBIDDEN' } } : { json: emptyWorkspace }))
  await page.goto('/#/integrations/workflows'); await token(page).fill(TOKEN_OK)
  await expect(page.locator('.workflow-page > [role=alert]')).not.toBeEmpty()
  await leaveAndReturn(page, '数据工作流')
  await expect(page.locator('.workflow-page > [role=alert]')).not.toBeEmpty()
  expect(reads).toBe(1)
  await page.getByRole('button', { name: '读取工作流', exact: true }).click()
  await expect(page.getByRole('button', { name: '新建工作流', exact: true })).toBeVisible()
  expect(reads).toBe(2)
})

test('continue opens the exact latest revision while the creation response stays on revision one', async ({ page }) => {
  const setup = source.setups.items[0]
  const definition = sample('v2/workflow-definition.json')
  definition.id = setup.workflowId
  const version = (revision: number) => ({ definition: { ...definition, revision }, digest: 'sha256:' + 'a'.repeat(64), state: 'DRAFT', editVersion: 1, updatedAt: '2026-10-03T00:00:00Z', preview: null, layout: Object.fromEntries(definition.nodes.map((node: { id: string }, i: number) => [node.id, { x: 180, y: 40 + i * 160 }])) })
  const paths: string[] = []
  await mockEmptySourceInstances(page)
  await page.route('**/api/v1/integrations/**', route => {
    const path = new URL(route.request().url()).pathname; paths.push(route.request().method() + ' ' + path)
    const result = path.endsWith('/sources') ? source : path.endsWith('/continuation') ? { schemaVersion: '1.0', setupId: setup.id, workflow: version(2) } : path.includes('/sources/') ? { setup, workflow: version(1) } : path.endsWith('/workflows') ? { ...emptyWorkspace, drafts: { items: [version(2)], truncated: false } } : version(2)
    return route.fulfill({ json: result })
  })
  await page.goto('/#/integrations/sources'); await token(page).fill(TOKEN_OK); await page.getByRole('tab', { name: '接入回执', exact: true }).click()
  await page.getByRole('button', { name: '继续编排：' + setup.name, exact: true }).click()
  await expect(page).toHaveURL(new RegExp('revision=2&state=DRAFT$'))
  await expect(page.locator('[data-engine=x6]')).toHaveAttribute('data-graph-status', 'ready')
  expect(paths).toEqual(['GET /api/v1/integrations/sources', 'GET /api/v1/integrations/sources/' + setup.id, 'GET /api/v1/integrations/sources/' + setup.id + '/continuation', 'GET /api/v1/integrations/workflows', 'GET /api/v1/integrations/workflows/drafts/' + setup.workflowId + '/2'])
})
