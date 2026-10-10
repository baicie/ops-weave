import { WORKFLOW_OPERATORS, TOKEN_OK, closeWorkflowInspector } from './helpers.ts'
import { test, expect, type Page } from '@playwright/test'
import { readFileSync } from 'node:fs'

// Explicit browser Fixture for the RELATION output surface. It exercises protocol and display
// semantics only: it is not a real source run and proves nothing about production data.
const bundle = JSON.parse(readFileSync(new URL('../../../contracts/catalog/opsweave-core-1.0.0.json', import.meta.url), 'utf8'))
const sourcePin = JSON.parse(readFileSync(new URL('../../../contracts/examples/v2/workflow-source-configuration-pin.json', import.meta.url), 'utf8'))
const digest = 'sha256:' + 'a'.repeat(64)
// Pin digests cross-checked against Java ModelDefinition.digest() (opsweave-model-v1) for the built-in relation models.
const CONTAINS = 'sha256:bc9aadb9d827fd397bd3998c3d4306a0cb806864af00699c95955c549d57d7b4'
const DEPLOYED_ON = 'sha256:6570aa4ae88b42d7f115d58fd679d7e36e99e6bdb7206c6942b2b7e17f887308'
const relationTarget = (pin = CONTAINS, fromField = 'from_identity', toField = 'to_identity') => ({
  kind: 'RELATION', schemaVersion: '1.0', relation: { id: 'builtin.contains', revision: 1, digest: pin }, fromField, toField,
})

function relationWorkflow(source: Record<string, unknown> = { kind: 'MANUAL_SAMPLE', instanceId: 'manual' }, target = relationTarget()) {
  const nodes = [
    { id: 'source', type: 'SOURCE', config: {} },
    { id: 'mapping', type: 'MAP', config: target.fromField === 'from_identity' ? { from_identity: 'from_identity', to_identity: 'to_identity' } : { from_identity: target.fromField, to_identity: target.toField } },
    { id: 'trim', type: 'TRIM', config: {} },
    { id: 'validate', type: 'VALIDATE', config: {} },
    { id: 'output', type: 'OUTPUT', config: {} },
  ].map((n: any) => ({ ...n, version: '1', operatorDigest: WORKFLOW_OPERATORS.operators.find((o: any) => o.type === n.type)!.digest }))
  return {
    schemaVersion: '2.0', id: 'fixture-relation-flow', revision: 1, name: 'Fixture relation output', source, target, nodes,
    edges: nodes.slice(1).map((n: any, i: number) => ({ from: nodes[i].id, to: n.id })),
  }
}

async function relationFixture(page: Page, options: { executions?: any[]; batches?: any[]; host?: boolean; failExecute?: boolean; rejectExecute?: boolean } = {}) {
  const calls: { path: string; body: any }[] = []
  const definition = relationWorkflow(options.host ? { kind: 'ZABBIX_HOST', instanceId: 'fixture-host', configuration: sourcePin } : undefined)
  let executeAttempts = 0
  const entry = { definition, digest, state: 'PUBLISHED', editVersion: 0, preview: null, updatedAt: '2026-10-09T01:00:00Z', layout: Object.fromEntries(definition.nodes.map((n: any, i: number) => [n.id, { x: 180, y: 40 + i * 160 }])) }
  let saved: any = null
  const executions: any[] = options.executions ?? []
  const task = options.host ? { workflowId: definition.id, revision: 1, digest, settings: { identityField: 'entity_id', nameField: 'to_identity' }, generation: 1, state: 'STOPPED', cursor: '2026-10-09T01:00:00Z', cursorId: '10000000-0000-4000-8000-000000000010', updatedAt: '2026-10-09T01:00:00Z', error: null } : null
  const checkpoint = options.host ? { workflowId: definition.id, revision: 1, digest, generation: 1, scanId: '10000000-0000-4000-8000-000000000011', pendingBatchId: null, confirmedBatches: 2, confirmedRecords: 3, complete: false, updatedAt: '2026-10-09T01:00:00Z' } : null
  await page.route(/\/api\/v1\/catalog(?:\/|$)/, route => route.fulfill({ json: { schemaVersion: '1.0', storage: 'memory', package: bundle, published: { items: [], truncated: false }, drafts: { items: [], truncated: false } } }))
  await page.route(/\/api\/v1\/integrations\/workflows(?:\/|$)/, async route => {
    const path = new URL(route.request().url()).pathname, body = route.request().postDataJSON()
    calls.push({ path, body })
    let response: any
    if (path.endsWith('/workflows')) response = { schemaVersion: '2.0', storage: 'memory', operatorCatalog: WORKFLOW_OPERATORS, drafts: { items: saved ? [saved] : [], truncated: false }, published: { items: options.host || options.executions ? [entry] : [], truncated: false }, models: bundle.definitions.filter((m: any) => m.kind === 'ENTITY').map((definition: any) => ({ definition, digest })), modelsTruncated: false, zabbixSource: { instanceId: 'fixture-host', mode: 'fixture' }, runs: { items: [], truncated: false } }
    else if (path.endsWith('/runtime')) response = { schemaVersion: '2.0', mode: 'LOCAL_DEV_ENTITY', pollSeconds: 5, maxBatchRecords: 5, tasks: task ? [task] : [], executions }
    else if (path.includes('/host-scans/')) response = { schemaVersion: '2.0', checkpoint, batches: options.batches ?? [] }
    else if (path.endsWith('/drafts')) { saved = { definition: body.definition, layout: body.layout, digest, state: 'DRAFT', editVersion: body.expectedEditVersion + 1, updatedAt: new Date().toISOString(), preview: null }; response = saved }
    else if (path.endsWith('/preview') || path.endsWith('/run')) {
      const current = path.endsWith('/run') ? entry : saved
      const receipt = { id: '10000000-0000-4000-8000-000000000001', digest, inputDigest: digest, origin: 'MANUAL_SAMPLE', accepted: 1, rejected: 0, filtered: 0, createdAt: new Date().toISOString() }
      if (current === saved) saved.preview = receipt
      response = { receipt, evaluation: { rows: [{ index: 0, status: 'ACCEPTED', steps: current.definition.nodes.map((n: any) => ({ nodeId: n.id, type: n.type, status: 'OK', values: { from_identity: 'fixture-node-a', to_identity: 'fixture-node-b' }, issues: [] })) }], accepted: 1, rejected: 0, filtered: 0, dryRun: true, writesPerformed: false }, retainedCount: 1, missingRaw: 0, truncated: false, sourceStatus: 'MANUAL_SAMPLE' }
    } else if (path.endsWith('/publish')) { entry.definition = saved.definition; response = { ...saved, state: 'PUBLISHED', editVersion: 0 } }
    else if (path.endsWith('/runtime/execute')) {
      if (options.failExecute && executeAttempts++ === 0) return route.abort('failed')
      if (options.rejectExecute) return route.fulfill({ status: 400, json: { error: 'INVALID_SAMPLE' } })
      response = { id: body.previewId, workflowId: body.id, revision: body.revision, digest: body.digest, settings: body.settings, origin: 'MANUAL_SAMPLE', syncRunId: null, createdAt: new Date().toISOString(), state: 'SUCCEEDED', accepted: 3, rejected: 0, filtered: 0, entityIds: [], relationIds: ['10000000-0000-4000-8000-000000000021', '10000000-0000-4000-8000-000000000022'], pending: [{ index: 2, code: 'FROM_UNRESOLVED' }], error: null }
      if (!executions.some(x => x.id === response.id)) executions.unshift(response)
    } else response = path.includes('/versions/') ? entry : saved
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify(response) })
  })
  await page.goto('/#/integrations/workflows')
  await page.getByRole('textbox', { name: '平台开发 Token（仅保存在当前标签页内存）' }).fill(TOKEN_OK)
  await expect(page.getByRole('button', { name: '新建工作流', exact: true })).toBeVisible()
  return calls
}

test('relation output pins a published relation model and maps explicit identity fields', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 })
  const calls = await relationFixture(page)
  await page.getByRole('button', { name: '新建工作流', exact: true }).click()
  await page.getByRole('button', { name: '＋ 关系输出模板', exact: true }).click()
  await expect(page.locator('.workflow-node')).toHaveCount(5)
  await expect(page.locator('.workflow-node[data-kind=OUTPUT]')).toHaveCount(1)
  await page.getByRole('button', { name: '关系输出节点 output', exact: true }).click()
  await expect(page.getByRole('combobox', { name: '关系模型' })).toHaveValue('builtin.contains@1')
  await expect(page.getByRole('textbox', { name: '起点身份字段' })).toHaveValue('from_identity')
  await expect(page.getByRole('textbox', { name: '终点身份字段' })).toHaveValue('to_identity')
  await page.getByText('测试数据与结果', { exact: true }).click()
  await expect(page.getByRole('textbox', { name: '工作流手工样本' })).toHaveValue(/fixture-node-a/)
  await expect(page.getByText('（默认内容为 Fixture）')).toBeVisible()
  await page.getByText('测试数据与结果', { exact: true }).click()
  await page.getByRole('button', { name: '关系输出节点 output', exact: true }).click()
  await page.getByRole('combobox', { name: '关系模型' }).selectOption('builtin.deployed_on@1')
  // Keyboard reachability: the identity field controls accept keyboard editing in the same drawer.
  const fromField = page.getByRole('textbox', { name: '起点身份字段' })
  await fromField.focus()
  await page.keyboard.press('Control+a')
  await page.keyboard.type('left_id')
  await expect(fromField).toHaveValue('left_id')
  await page.getByRole('textbox', { name: '终点身份字段' }).fill('right_id')
  await page.getByRole('combobox', { name: '选择工作流节点' }).selectOption('mapping')
  await expect(page.getByRole('textbox', { name: '来源字段 → left_id' })).toHaveValue('from_identity')
  await expect(page.getByRole('textbox', { name: '来源字段 → right_id' })).toHaveValue('to_identity')
  await page.getByRole('combobox', { name: '选择工作流节点' }).selectOption('output')
  await page.getByRole('textbox', { name: '终点身份字段' }).fill('left_id')
  await expect(page.getByRole('alert').filter({ hasText: '起点与终点身份字段必须不同' })).toBeVisible()
  await expect(page.getByRole('button', { name: '保存草稿', exact: true })).toBeDisabled()
  await page.getByRole('textbox', { name: '终点身份字段' }).fill('right_id')
  await expect(page.getByRole('alert').filter({ hasText: '起点与终点身份字段必须不同' })).toHaveCount(0)
  // Esc closes the non-modal drawer and returns focus to the node entry that opened it.
  await page.keyboard.press('Escape')
  await expect(page.locator('.studio-inspector-body')).not.toBeVisible()
  await expect(page.getByRole('button', { name: '关系输出节点 output', exact: true })).toBeFocused()
  await page.getByRole('button', { name: '关系输出节点 output', exact: true }).click()
  await closeWorkflowInspector(page)
  await page.getByRole('button', { name: '保存草稿', exact: true }).click()
  await expect(page.getByRole('button', { name: '预览当前草稿', exact: true })).toBeEnabled()
  const save = calls.find(c => c.path.endsWith('/drafts'))!.body
  expect(save.definition.target).toEqual({ kind: 'RELATION', schemaVersion: '1.0', relation: { id: 'builtin.deployed_on', revision: 1, digest: DEPLOYED_ON }, fromField: 'left_id', toField: 'right_id' })
  expect(save.definition.nodes.find((n: any) => n.type === 'MAP').config).toEqual({ from_identity: 'left_id', to_identity: 'right_id' })
  expect(save).not.toHaveProperty('tenantId')
  await page.getByRole('button', { name: '预览当前草稿', exact: true }).click()
  await expect(page.getByRole('button', { name: '发布版本', exact: true })).toBeEnabled()
  await page.getByRole('button', { name: '发布版本', exact: true }).click()
  await expect(page.getByRole('button', { name: '创建下一版', exact: true })).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
})

for (const [width, theme] of [[1440, 'light'], [390, 'dark']] as const) test(`relation receipts keep written relations and pending entries separate at ${width} ${theme}`, async ({ page }) => {
  await page.setViewportSize({ width, height: 1000 })
  await page.addInitScript(t => localStorage.setItem('opsweave.ui.theme', t), theme)
  await relationFixture(page, {
    executions: [
      { id: '10000000-0000-4000-8000-000000000031', workflowId: 'fixture-relation-flow', revision: 1, digest, settings: { identityField: 'from_identity', nameField: 'to_identity' }, origin: 'fixture', syncRunId: null, createdAt: '2026-10-09T02:00:00Z', state: 'SUCCEEDED', accepted: 3, rejected: 0, filtered: 0, entityIds: [], relationIds: ['10000000-0000-4000-8000-000000000041', '10000000-0000-4000-8000-000000000042'], pending: [{ index: 2, code: 'FROM_UNRESOLVED' }], error: null },
      // Legacy receipt without the closed relation fields: it must show "—", never a fabricated zero.
      { id: '10000000-0000-4000-8000-000000000032', workflowId: 'fixture-relation-flow', revision: 1, digest, settings: { identityField: 'from_identity', nameField: 'to_identity' }, origin: 'MANUAL_SAMPLE', syncRunId: null, createdAt: '2026-10-09T01:30:00Z', state: 'SUCCEEDED', accepted: 1, rejected: 0, filtered: 0, entityIds: ['10000000-0000-4000-8000-000000000043'], error: null },
    ],
  })
  await page.getByRole('button', { name: '查看', exact: true }).click()
  await page.getByText('执行与任务管理', { exact: true }).click()
  const receipts = page.locator('.workflow-runtime-receipts')
  const first = receipts.locator('article').first()
  await expect(first).toContainText('执行完成')
  await expect(first).not.toContainText('执行失败')
  await expect(first).toContainText('已写资产 0 条')
  await expect(first).toContainText('已写关系 2 条')
  await expect(first).toContainText('待处理 1 条')
  await expect(first).toContainText('待处理记录 3 · 起点身份未解析 FROM_UNRESOLVED')
  await expect(first).toContainText('待处理不是失败也不是过滤')
  const legacy = receipts.locator('article').nth(1)
  await expect(legacy).toContainText('已写关系 —')
  await expect(legacy).toContainText('待处理 —')
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
})

test('an unknown execution result keeps the original request and retries as-is', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 })
  const calls = await relationFixture(page, { executions: [], failExecute: true })
  await page.getByRole('button', { name: '查看', exact: true }).click()
  await page.getByText('执行与任务管理', { exact: true }).click()
  await page.getByText('测试数据与结果', { exact: true }).click()
  await page.getByRole('textbox', { name: '工作流手工样本' }).fill('[{"from_identity":"fixture-node-a","to_identity":"fixture-node-b"}]')
  await page.getByRole('textbox', { name: '运行来源标识字段' }).fill('from_identity')
  await page.getByRole('combobox', { name: '运行实体名称字段' }).selectOption('to_identity')
  await page.getByRole('button', { name: '执行并写入关系', exact: true }).click()
  await expect(page.getByRole('button', { name: '按原标识确认执行', exact: true })).toBeEnabled()
  await expect(page.getByRole('button', { name: '创建下一版', exact: true })).toBeDisabled()
  await page.getByRole('button', { name: '按原标识确认执行', exact: true }).click()
  await expect(page.locator('.workflow-runtime-receipts')).toContainText('已写关系 2 条')
  const posts = calls.filter(c => c.path.endsWith('/runtime/execute'))
  expect(posts).toHaveLength(2)
  expect(posts[1].body).toEqual(posts[0].body)
  expect(calls.filter(c => c.path.endsWith('/run'))).toHaveLength(1)
  await expect(page.getByRole('button', { name: '创建下一版', exact: true })).toBeEnabled()
})

test('an explicit 4xx execution result is known and unlocks the page', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 })
  const calls = await relationFixture(page, { executions: [], rejectExecute: true })
  await page.getByRole('button', { name: '查看', exact: true }).click()
  await page.getByText('执行与任务管理', { exact: true }).click()
  await page.getByText('测试数据与结果', { exact: true }).click()
  await page.getByRole('textbox', { name: '工作流手工样本' }).fill('[{"from_identity":"fixture-node-a","to_identity":"fixture-node-b"}]')
  await page.getByRole('textbox', { name: '运行来源标识字段' }).fill('from_identity')
  await page.getByRole('combobox', { name: '运行实体名称字段' }).selectOption('to_identity')
  await page.getByRole('button', { name: '执行并写入关系', exact: true }).click()
  await expect(page.locator('.workflow-runtime-panel').getByRole('alert')).toBeVisible()
  await expect(page.getByRole('button', { name: '按原标识确认执行', exact: true })).toHaveCount(0)
  await expect(page.getByRole('button', { name: '创建下一版', exact: true })).toBeEnabled()
  expect(calls.filter(c => c.path.endsWith('/runtime/execute'))).toHaveLength(1)
})

test('host batch receipts keep relation ids and pending entries separate', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 })
  const time = '2026-10-09T01:00:00Z'
  await relationFixture(page, {
    host: true,
    batches: [
      { id: '10000000-0000-4000-8000-000000000051', scanId: '10000000-0000-4000-8000-000000000011', workflowId: 'fixture-relation-flow', revision: 1, digest, settings: { identityField: 'entity_id', nameField: 'to_identity' }, sequence: 1, complete: true, observedAt: time, state: 'CONFIRMED', entityIds: [], relationIds: ['10000000-0000-4000-8000-000000000061'], pending: [{ index: 1, code: 'TO_UNRESOLVED' }], error: null, updatedAt: time, recordCount: 2 },
      // Legacy batch receipt without the closed relation fields: counts stay "—".
      { id: '10000000-0000-4000-8000-000000000052', scanId: '10000000-0000-4000-8000-000000000011', workflowId: 'fixture-relation-flow', revision: 1, digest, settings: { identityField: 'entity_id', nameField: 'to_identity' }, sequence: 2, complete: true, observedAt: time, state: 'CONFIRMED', entityIds: ['10000000-0000-4000-8000-000000000062'], error: null, updatedAt: time, recordCount: 1 },
    ],
  })
  await page.getByRole('button', { name: '查看', exact: true }).click()
  await page.getByText('执行与任务管理', { exact: true }).click()
  const batches = page.getByRole('region', { name: '固定接入资产批次' })
  await expect(batches).toBeVisible()
  await batches.getByText(/第 1 批/).click()
  await expect(batches).toContainText('记录 2 · 终点身份未解析 TO_UNRESOLVED')
  await expect(batches).toContainText('待处理条目不是失败也不是过滤')
  await expect(batches.getByRole('cell', { name: '1', exact: true }).first()).toBeVisible()
  await expect(batches.getByRole('cell', { name: '—', exact: true }).first()).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
})
