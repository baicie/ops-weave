import { WORKFLOW_OPERATORS } from './helpers.ts'
import { test, expect, type Page } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { TOKEN_OK } from './helpers.ts'

const hash = 'sha256:' + 'a'.repeat(64)
const example = (name: string) => JSON.parse(readFileSync(new URL('../../../contracts/examples/v2/' + name, import.meta.url), 'utf8'))
const log = example('workflow-log-definition.json')
const metric = example('workflow-metric-definition.json')
function entry(definition: any, state: 'DRAFT' | 'PUBLISHED', revision = 1) {
  return { definition: { ...definition, revision }, state, digest: hash, editVersion: state === 'DRAFT' ? 1 : 0, updatedAt: '2026-10-01T00:00:00Z', preview: null, layout: Object.fromEntries(definition.nodes.map((n: any, i: number) => [n.id, { x: 180, y: 40 + i * 116 }])) }
}
const draft = entry(log, 'DRAFT', 2)
const published = entry(log, 'PUBLISHED')
const metricDraft = entry(metric, 'DRAFT')
const workspace = { schemaVersion: '2.0', storage: 'memory',operatorCatalog:WORKFLOW_OPERATORS, drafts: { items: [draft, metricDraft], truncated: false }, published: { items: [published], truncated: false }, models: [], modelsTruncated: false, zabbixSource: { instanceId: '', mode: 'fixture' }, runs: { items: [], truncated: false } }

async function workflowFixture(page: Page, suffix = '') {
  const calls: { path: string; method: string }[] = []
  await page.route('**/api/v1/integrations/workflows**', async route => {
    const path = new URL(route.request().url()).pathname
    calls.push({ path, method: route.request().method() })
    const result = path.endsWith('/workflows') ? workspace : path.includes('/versions/') ? published : draft
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify(result) })
  })
  await page.goto('/#/integrations/workflows' + suffix)
  await page.getByRole('textbox', { name: '平台开发 Token（仅保存在当前标签页内存）' }).fill(TOKEN_OK)
  if (!suffix) await expect(page.getByRole('button', { name: '新建工作流', exact: true })).toBeVisible()
  return calls
}

for (const width of [1440, 1024, 390]) test('search, versions and editor remain separate read-only views at ' + width, async ({ page }) => {
  await page.setViewportSize({ width, height: 1000 })
  const calls = await workflowFixture(page)
  const list = page.getByRole('region', { name: '处理流程列表' })
  await expect(list.locator('tbody tr')).toHaveCount(2)
  await page.getByRole('textbox', { name: '搜索处理流程' }).fill('log')
  await expect(list.locator('tbody tr')).toHaveCount(1)
  await expect(list).toContainText('v2 · 草稿')
  await page.getByRole('combobox', { name: '按输出类型筛选流程' }).selectOption('METRIC')
  await expect(list).toContainText('没有匹配的流程')
  await page.getByRole('textbox', { name: '搜索处理流程' }).fill('')
  await expect(list.locator('tbody tr')).toHaveCount(1)
  await page.getByRole('combobox', { name: '按输出类型筛选流程' }).selectOption('ALL')
  await list.getByRole('button', { name: 'Fixture LOG preview', exact: true }).click()
  const versions = page.getByRole('region', { name: '工作流版本' })
  await expect(versions.locator('tbody tr')).toHaveCount(2)
  await expect(page.getByRole('textbox', { name: '工作流名称', exact: true })).toHaveCount(0)
  await versions.getByRole('button', { name: '查看流程', exact: true }).click()
  await expect(page.getByRole('textbox', { name: '工作流名称', exact: true })).toBeDisabled()
  await expect(page.locator('[data-engine=x6]')).toHaveAttribute('data-graph-status', 'ready')
  await expect(page.locator('.workflow-node')).toHaveCount(4)
  const editorBounds = await page.locator('.studio-editor').boundingBox()
  expect(editorBounds).not.toBeNull()
  const processBounds = (await page.locator('.studio-process').boundingBox())!
  await expect(page.locator('.studio-inspector')).not.toBeVisible()
  expect(processBounds.width).toBeGreaterThan(editorBounds!.width - 3)
  await page.getByRole('button', { name: '字段映射节点 mapping', exact: true }).click({ button: 'right' })
  await expect(page.getByRole('menuitem', { name: '编辑节点', exact: true })).toHaveCount(0)
  await page.getByRole('menuitem', { name: '查看节点', exact: true }).click()
  await expect(page.getByRole('textbox', { name: '来源字段 → body', exact: true })).toBeDisabled()
  await page.getByRole('button', { name: '关闭节点配置', exact: true }).click()
  await page.getByRole('button', { name: '更多工作流操作', exact: true }).click();await page.getByRole('button', { name: '返回版本列表', exact: true }).click()
  await expect(versions.getByRole('heading', { name: '数据流版本' })).toBeVisible()
  await page.getByRole('button', { name: '全部处理流程', exact: true }).click()
  await expect(list).toBeVisible()
  expect(calls.map(c => c.path)).toEqual(['/api/v1/integrations/workflows', '/api/v1/integrations/workflows/versions/fixture-log/1'])
  expect(calls.every(c => c.method === 'GET')).toBe(true)
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
})

test('a task deep link selects its version table and template cancellation restores focus', async ({ page }) => {
  const calls = await workflowFixture(page, '?task=fixture-log')
  await expect(page.getByRole('heading', { name: '数据流版本' })).toBeVisible()
  await page.getByRole('button', { name: '全部处理流程', exact: true }).click()
  await page.getByRole('button', { name: '新建工作流', exact: true }).click()
  await expect(page.getByRole('dialog', { name: '新建工作流' })).toBeVisible()
  await expect(page.getByRole('button', { name: '＋ 自定义实体模板', exact: true })).toBeDisabled()
  await page.keyboard.press('Escape')
  await expect(page.getByRole('dialog', { name: '新建工作流' })).not.toBeVisible()
  await expect(page.getByRole('button', { name: '新建工作流', exact: true })).toBeFocused()
  expect(calls).toEqual([{ path: '/api/v1/integrations/workflows', method: 'GET' }])
})

test('invalid or mixed task selection is rejected before any workflow request', async ({ page }) => {
  const calls = await workflowFixture(page, '?task=../../escape')
  for (const query of ['task=../../escape', 'task=fixture-log&task=fixture-metric', 'task=fixture-log&state=DRAFT', 'task=fixture-log%0A']) {
    await page.evaluate(q => { location.hash = '#/integrations/workflows?' + q }, query)
    await expect(page.getByRole('alert').filter({ hasText: '工作流地址参数无效' })).toBeVisible()
  }
  expect(calls).toEqual([])
})

for (const width of [1440, 1024, 390]) test('catalog search and task filters send no writes or hidden probes at ' + width, async ({ page }) => {
  await page.setViewportSize({ width, height: 1000 })
  const calls: string[] = []
  const types = [{ id: 'ZABBIX_HOST', status: 'AVAILABLE', connection: { instanceId: 'zabbix-fixture', digest: hash, dataMode: 'fixture', endpoint: null, credentialRef: null } }, { id: 'MANUAL_SAMPLE', status: 'AVAILABLE', connection: { instanceId: 'manual', digest: hash, dataMode: 'MANUAL_SAMPLE', endpoint: null, credentialRef: null } }, { id: 'CMDB_SNAPSHOT', status: 'LEGACY_IMPORT', connection: null }]
  const setups = ['ZABBIX_HOST', 'MANUAL_SAMPLE'].map((kind, i) => ({ id: '10000000-0000-4000-8000-00000000000' + (i + 1), name: i ? 'Fixture JSON 接入' : 'Fixture Zabbix 接入', description: '', source: { kind, instanceId: i ? 'manual' : 'zabbix-fixture' }, connectionDigest: hash, dataMode: i ? 'MANUAL_SAMPLE' : 'fixture', initialTarget: null, digest: hash, createdAt: '2026-10-01T00:00:00Z', workflowId: 'source-10000000-0000-4000-8000-00000000000' + (i + 1) }))
  await page.route('**/api/v2/data-sources', route => { calls.push('GET /api/v2/data-sources'); return route.fulfill({ json: { schemaVersion:'2.0',storage:'memory',items:setups.map(s=>({id:s.id,name:s.name,description:s.description,source:s.source,configurationRevision:1,connectionDigest:s.connectionDigest,dataMode:s.dataMode,editVersion:1,state:'ACTIVE',createdAt:s.createdAt,updatedAt:s.createdAt,workflowId:s.workflowId})),truncated:false } }) })
  await page.route('**/api/v1/integrations/**', async route => {
    calls.push(route.request().method() + ' ' + new URL(route.request().url()).pathname)
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify({ schemaVersion: '1.0', storage: 'memory', types, models: [], modelsTruncated: false, setups: { items: setups, truncated: false } }) })
  })
  await page.goto('/#/integrations/sources')
  await page.getByRole('textbox', { name: '平台开发 Token（仅保存在当前标签页内存）' }).fill(TOKEN_OK)
  await expect(page.getByRole('tab', { name: /已配置接入/ })).toHaveAttribute('aria-selected', 'true')
  const table = page.getByRole('table', { name: '接入实例列表' })
  await expect(table.locator('tbody tr')).toHaveCount(2)
  await page.getByRole('tab', { name: /已配置接入/ }).press('ArrowRight')
  await expect(page.getByRole('tab', { name: '接入类型', exact: true })).toBeFocused()
  await expect(page.locator('.integration-source-card')).toHaveCount(3)
  await page.getByRole('textbox', { name: '搜索接入类型' }).fill('zabbix')
  await expect(page.locator('.integration-source-card')).toHaveCount(1)
  await expect(page.locator('.integration-source-card')).toContainText('已保存 1')
  await page.getByRole('button', { name: '查看任务', exact: true }).click()
  await expect(table.locator('tbody tr')).toHaveCount(1)
  await expect(table).toContainText('Fixture Zabbix 接入')
  await expect(page.getByRole('link', { name: '查看工作流版本', exact: true })).toHaveAttribute('href', '#/integrations/workflows?task=' + setups[0].workflowId)
  await page.getByRole('button', { name: '全部', exact: true }).click()
  await page.getByRole('combobox', { name: '按接入类型筛选实例' }).selectOption('ALL')
  await expect(table.locator('tbody tr')).toHaveCount(2)
  await page.getByRole('textbox', { name: '搜索接入实例' }).fill('不存在')
  await expect(page.getByText('没有匹配的接入实例', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: '清除实例搜索' }).click()
  await expect(table.locator('tbody tr')).toHaveCount(2)
  expect(calls).toEqual(['GET /api/v2/data-sources','GET /api/v1/integrations/sources'])
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
})
