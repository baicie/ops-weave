import { WORKFLOW_OPERATORS } from './helpers.ts'
import { test, expect, type Page } from '@playwright/test'
import { TOKEN_OK, closeWorkflowInspector } from './helpers.ts'

const hash = 'sha256:' + 'a'.repeat(64)
async function fixture(page: Page) {
  const calls: { path: string; body: any }[] = []
  let saved: any
  let published: any
  await page.route(/\/api\/v1\/integrations\/workflows(?:\/|$)/, async route => {
    const path = new URL(route.request().url()).pathname
    const body = route.request().postDataJSON()
    calls.push({ path, body })
    let result: any
    if (path.endsWith('/workflows')) result = { schemaVersion: '2.0', storage: 'memory',operatorCatalog:WORKFLOW_OPERATORS, drafts: { items: saved ? [saved] : [], truncated: false }, published: { items: published ? [published] : [], truncated: false }, models: [], modelsTruncated: false, zabbixSource: { instanceId: 'fixture-host', mode: 'fixture' }, runs: { items: [], truncated: false } }
    else if (path.endsWith('/drafts')) { saved = { definition: body.definition, layout: body.layout, digest: hash, state: 'DRAFT', editVersion: body.expectedEditVersion + 1, updatedAt: new Date().toISOString(), preview: null }; result = saved }
    else if (path.endsWith('/preview')) {
      const sample = body.samples[0]
      const receipt = { id: '10000000-0000-4000-8000-000000000001', digest: hash, inputDigest: hash, origin: 'MANUAL_SAMPLE', accepted: 1, rejected: 0, filtered: 0, createdAt: new Date().toISOString() }
      saved.preview = receipt
      result = { receipt, evaluation: { rows: [{ index: 0, status: 'ACCEPTED', steps: saved.definition.nodes.map((n: any) => ({ nodeId: n.id, type: n.type, status: 'OK', values: sample, issues: [] })) }], accepted: 1, rejected: 0, filtered: 0, dryRun: true, writesPerformed: false }, retainedCount: 1, missingRaw: 0, truncated: false, sourceStatus: 'MANUAL_SAMPLE' }
    } else if (path.endsWith('/publish')) { published = { ...saved, state: 'PUBLISHED', editVersion: 0 }; result = published }
    else result = path.includes('/versions/') ? published : saved
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify(result) })
  })
  await page.goto('/#/integrations/workflows')
  await page.getByRole('textbox', { name: '平台开发 Token（仅保存在当前标签页内存）' }).fill(TOKEN_OK)
  await expect(page.getByRole('button', { name: '新建工作流', exact: true })).toBeVisible()
  await page.getByRole('button', { name: '新建工作流', exact: true }).click()
  return calls
}

for (const width of [1440, 390]) for (const kind of ['LOG', 'METRIC']) test('typed ' + kind + ' workflow without entity catalog at ' + width, async ({ page }) => {
  await page.setViewportSize({ width, height: 1000 })
  const calls = await fixture(page)
  await page.getByRole('button', { name: kind === 'LOG' ? '＋ 日志样本模板' : '＋ 指标样本模板', exact: true }).click()
  await page.getByRole('button', { name: '更多工作流操作', exact: true }).click();await page.getByRole('button', { name: '输出配置', exact: true }).click()
  await expect(page.getByRole('combobox', { name: '实体模型', exact: true })).toHaveCount(0)
  await closeWorkflowInspector(page)
  if (await page.getByRole('button', { name: '节点库', exact: true }).getAttribute('aria-expanded') === 'false') await page.getByRole('button', { name: '节点库', exact: true }).click()
  await expect(page.getByRole('button', { name: '实体输出', exact: true })).toBeDisabled()
  await expect(page.locator('.workflow-node')).toHaveCount(4)
  await expect(page.getByRole('button', { name: kind === 'LOG' ? '日志输出' : '指标输出', exact: true })).toHaveAttribute('aria-pressed', 'true')
  await page.getByRole('button', { name: kind === 'LOG' ? '日志输出' : '指标输出', exact: true }).click()
  await expect(page.getByRole('combobox', { name: '输出类型', exact: true })).toHaveCount(0)
  await expect(page.locator('.workflow-node')).not.toContainText(['undefined'])
  await page.getByRole('combobox', { name: '选择工作流节点' }).selectOption('mapping')
  if (kind === 'LOG') {
    await expect(page.getByRole('textbox', { name: '来源字段 → body' })).toBeVisible()
    await closeWorkflowInspector(page)
    await page.getByText('测试数据与结果', { exact: true }).click()
    await page.getByRole('textbox', { name: '工作流手工样本' }).fill(JSON.stringify([{ eventTime: '2026-10-01T00:00:00Z', body: '  <script>fixtureInjection = true</script>  ' }]))
  }
  await closeWorkflowInspector(page)
  await page.getByRole('button', { name: '保存草稿', exact: true }).click()
  await expect(page.getByRole('button', { name: '预览当前草稿', exact: true })).toBeEnabled()
  await page.getByRole('button', { name: '预览当前草稿', exact: true }).click()
  await expect(page.getByRole('button', { name: '发布版本', exact: true })).toBeEnabled()
  await page.getByRole('button', { name: '更多工作流操作', exact: true }).click();await page.getByRole('button', { name: '输出配置', exact: true }).click()
  await page.getByRole('combobox', { name: '选择工作流节点' }).selectOption('output')
  await closeWorkflowInspector(page)
  await page.getByText('查看节点输入', { exact: true }).click()
  await expect(page.locator('[data-workflow-input]')).toContainText(kind === 'LOG' ? '<script>' : 'metricKey')
  await expect(page.locator('[data-workflow-output]')).toContainText(kind === 'LOG' ? '<script>' : 'metricKey')
  expect(await page.evaluate(() => Object.hasOwn(window, 'fixtureInjection'))).toBe(false)
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  const save = calls.find(c => c.path.endsWith('/drafts'))!.body
  expect(save.definition.target).toEqual({ kind, schemaVersion: '1.0' })
  expect(save.definition.nodes.map((n: any) => n.type)).toEqual(['SOURCE', 'MAP', 'VALIDATE', 'OUTPUT'])
  expect(JSON.stringify(save)).not.toContain('<script>')
  await page.getByRole('button', { name: '发布版本', exact: true }).click()
  await expect(page.getByRole('textbox', { name: '工作流名称', exact: true })).toBeDisabled()
  await page.getByRole('button', { name: '更多工作流操作', exact: true }).click();await page.getByRole('button', { name: '输出配置', exact: true }).click()
  await expect(page.locator('.workflow-output-node')).toHaveCount(0)
  await expect(page.getByRole('combobox', { name: '输出类型', exact: true })).toHaveCount(0)
  await closeWorkflowInspector(page)
  await page.getByRole('button', { name: '更多工作流操作', exact: true }).click();await page.getByRole('button', { name: '返回版本列表', exact: true }).click()
  await page.getByRole('button', { name: '全部处理流程', exact: true }).click()
  await page.getByRole('button', { name: '新建工作流', exact: true }).click()
  await page.getByRole('button', { name: kind === 'LOG' ? '＋ 指标样本模板' : '＋ 日志样本模板', exact: true }).click()
  await page.getByRole('button', { name: '更多工作流操作', exact: true }).click();await page.getByRole('button', { name: '输出配置', exact: true }).click()
  await expect(page.getByRole('button', { name: kind === 'LOG' ? '指标输出节点 output' : '日志输出节点 output', exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '保存草稿', exact: true })).toBeEnabled()
})

test('changing output rebuilds fields and clears the old preview', async ({ page }) => {
  const calls = await fixture(page)
  await page.getByRole('button', { name: '＋ 日志样本模板', exact: true }).click()
  await closeWorkflowInspector(page)
  await page.getByRole('button', { name: '保存草稿', exact: true }).click()
  await page.getByRole('button', { name: '预览当前草稿', exact: true }).click()
  await expect(page.getByRole('button', { name: '发布版本', exact: true })).toBeEnabled()
  await page.getByRole('button', { name: '更多工作流操作', exact: true }).click();await page.getByRole('button', { name: '输出配置', exact: true }).click()
  await closeWorkflowInspector(page)
  if (await page.getByRole('button', { name: '节点库', exact: true }).getAttribute('aria-expanded') === 'false') await page.getByRole('button', { name: '节点库', exact: true }).click()
  await page.getByRole('button', { name: '指标输出', exact: true }).click()
  await page.getByRole('combobox', { name: '选择工作流节点' }).selectOption('mapping')
  await expect(page.getByRole('textbox', { name: '来源字段 → body' })).toHaveCount(0)
  await expect(page.getByRole('textbox', { name: '来源字段 → metricKey' })).toBeVisible()
  await expect(page.getByRole('button', { name: '预览当前草稿', exact: true })).toBeDisabled()
  await expect(page.getByRole('button', { name: '发布版本', exact: true })).toBeDisabled()
  await expect(page.locator('[data-workflow-output]')).toHaveCount(0)
  await page.getByRole('combobox', { name: '选择工作流节点' }).selectOption('source')
  await expect(page.getByRole('combobox', { name: '输入来源' }).locator('option[value=ZABBIX_HOST]')).toHaveAttribute('disabled', '')
  await closeWorkflowInspector(page)
  await expect(page.locator('.workflow-node[data-kind=OUTPUT]')).toHaveCount(1)
  const writeCount = calls.filter(c => c.body).length
  await page.getByRole('button', { name: '更多工作流操作', exact: true }).click()
  await page.getByRole('button', { name: '撤销工作流修改', exact: true }).click()
  await expect(page.getByRole('button', { name: '日志输出节点 output', exact: true })).toBeVisible()
  await expect(page.locator('.workflow-node[data-kind=OUTPUT]')).toHaveCount(1)
  await page.getByRole('button', { name: '日志输出节点 output', exact: true }).click()
  await expect(page.getByRole('combobox', { name: '输出类型', exact: true })).toHaveCount(0)
  await expect(page.getByRole('dialog', { name: '节点配置', exact: true })).toContainText('日志 · v1')
  expect(calls.filter(c => c.body)).toHaveLength(writeCount)
})

test('resizing the canvas keeps all nodes in view without saving', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 })
  const calls = await fixture(page)
  await page.getByRole('button', { name: '＋ 日志样本模板', exact: true }).click()
  await expect(page.locator('.workflow-node')).toHaveCount(4)

  for (const width of [390, 1440]) {
    await page.setViewportSize({ width, height: 1000 })
    await expect.poll(async () => {
      const canvas = await page.locator('.x6-canvas').boundingBox()
      const viewport = await page.locator('.workflow-canvas-viewport').boundingBox()
      const nodes = await page.locator('.workflow-node').all()
      if (!canvas || !viewport || canvas.width > viewport.width + 1 || canvas.height > viewport.height + 1 || nodes.length !== 4) return false
      for (const node of nodes) {
        const box = await node.boundingBox()
        if (!box || box.x < canvas.x - 1 || box.x + box.width > canvas.x + canvas.width + 1 || box.y < canvas.y - 1 || box.y + box.height > canvas.y + canvas.height + 1) return false
      }
      return true
    }).toBe(true)
  }
  expect(calls.filter(c => c.body)).toEqual([])
})
