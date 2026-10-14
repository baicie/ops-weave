import { test, expect, type Page } from '@playwright/test'
import { TOKEN_OK, WORKFLOW_OPERATORS } from './helpers.ts'

const tokenLabel = '平台开发 Token（仅保存在当前标签页内存）'
const digest = (letter: string) => 'sha256:' + letter.repeat(64)

function definition(id: string, revision: number, name: string) {
  return {
    schemaVersion: '2.0', id, revision, name,
    source: { kind: 'ZABBIX_HOST', instanceId: 'connection-fixture', configuration: { sourceId: '10000000-0000-4000-8000-000000000001', revision: 1, digest: digest('a') } },
    target: { id: 'builtin.host', revision: 1, digest: digest('b') },
    nodes: [
      { id: 'source', type: 'SOURCE', version: '1', config: {} },
      { id: 'mapping', type: 'MAP', version: '1', config: { name: 'hostname' } },
      { id: 'validate', type: 'VALIDATE', version: '1', config: {} },
      { id: 'output', type: 'OUTPUT', version: '1', config: {} },
    ],
    edges: [{ from: 'source', to: 'mapping' }, { from: 'mapping', to: 'validate' }, { from: 'validate', to: 'output' }],
  }
}

function entry(id: string, revision: number, name: string, state: 'DRAFT' | 'PUBLISHED', updatedAt: string) {
  const d = definition(id, revision, name)
  return {
    definition: d, digest: digest(revision === 1 ? 'c' : 'd'), state, editVersion: state === 'DRAFT' ? 1 : 0,
    layout: Object.fromEntries(d.nodes.map((n, i) => [n.id, { x: 200, y: i * 160 }])), updatedAt, preview: null,
  }
}

function runSummary(workflowId: string, id: string, createdAt: string) {
  return {
    workflowId, revision: 1, mode: 'PREVIEW',
    receipt: { id, digest: digest('c'), inputDigest: digest('e'), origin: 'MANUAL_SAMPLE', accepted: 1, rejected: 0, filtered: 0, createdAt },
  }
}

function historyPage(reference: { id: string; revision: number; digest: string }) {
  return {
    schemaVersion: '2.0', asOf: '2026-10-13T08:00:00.000Z', snapshotAt: '2026-10-13T08:00:00.000Z',
    reference, coverage: 'RECORDED_CHECKS', offset: 0, recordedCount: 0, items: [], nextCursor: null, hasMore: false,
  }
}

type Call = { method: string; path: string; query: URLSearchParams }

/** Workspace directory plus bounded cursor pages for runs and entry options. */
async function fixture(page: Page, mode: 'runs' | 'entries' | 'options' | 'duplicate' = 'runs') {
  const calls: Call[] = []
  const published = [entry('flow-two', 2, 'Fixture 候选流程', 'PUBLISHED', '2026-10-13T02:00:00Z'), entry('flow-one', 1, 'Fixture 较早流程', 'PUBLISHED', '2026-10-13T01:00:00Z')]
  const oldest = entry('flow-oldest', 1, 'Fixture 最早流程', 'PUBLISHED', '2026-10-13T00:00:00Z')
  const olderOption = entry('flow-two', 1, 'Fixture 候选流程', 'PUBLISHED', '2026-10-13T00:30:00Z')
  const runs = [
    runSummary('flow-two', '10000000-0000-4000-8000-000000000022', '2026-10-13T02:00:00Z'),
    runSummary('flow-one', '10000000-0000-4000-8000-000000000021', '2026-10-13T01:00:00Z'),
    runSummary('flow-one', '10000000-0000-4000-8000-000000000020', '2026-10-13T00:30:00Z'),
    runSummary('flow-oldest', '10000000-0000-4000-8000-000000000019', '2026-10-13T00:00:00Z'),
  ]
  let runReads = 0
  await page.route('**/api/v1/integrations/workflows**', async route => {
    const url = new URL(route.request().url())
    const query = new URLSearchParams(url.search)
    calls.push({ method: route.request().method(), path: url.pathname, query })
    const path = url.pathname
    if (path.endsWith('/workflows/entries')) {
      if (mode === 'options') await route.fulfill({ json: { schemaVersion: '2.0', items: [published[0], olderOption], nextCursor: null, hasMore: false } })
      else await route.fulfill({ json: { schemaVersion: '2.0', items: [oldest], nextCursor: null, hasMore: false } })
      return
    }
    if (path.endsWith('/workflows/runs')) {
      runReads++
      if (mode === 'duplicate') await route.fulfill({ json: { schemaVersion: '2.0', items: [runs[1]], nextCursor: runs[1]!.receipt.id, hasMore: true } })
      else if (runReads === 1) await route.fulfill({ json: { schemaVersion: '2.0', items: [runs[2]], nextCursor: runs[2]!.receipt.id, hasMore: true } })
      else await route.fulfill({ json: { schemaVersion: '2.0', items: [runs[3]], nextCursor: null, hasMore: false } })
      return
    }
    if (path.endsWith('/history')) {
      const parts = path.split('/')
      const reference = [...published, oldest].find(e => e.definition.id === parts[7] && e.definition.revision === Number(parts[9])) ?? published[0]!
      await route.fulfill({ json: historyPage({ id: reference.definition.id, revision: reference.definition.revision, digest: reference.digest }) })
      return
    }
    await route.fulfill({
      json: {
        schemaVersion: '2.0', storage: 'memory', drafts: { items: mode === 'options' ? [entry('flow-two', 3, 'Fixture 候选流程', 'DRAFT', '2026-10-13T03:00:00Z')] : [], truncated: mode === 'options' },
        published: { items: published, truncated: mode !== 'options' }, models: [], modelsTruncated: false,
        zabbixSource: { instanceId: 'fixture-source', mode: 'fixture' }, operatorCatalog: WORKFLOW_OPERATORS,
        runs: { items: runs.slice(0, 2), truncated: true },
      },
    })
  })
  return { calls, published, oldest, runs }
}

test('run records continue through explicit bounded cursor pages', async ({ page }) => {
  const f = await fixture(page, 'runs')
  await page.goto('/#/integrations/workflows/runs')
  await page.getByRole('textbox', { name: tokenLabel }).fill(TOKEN_OK)
  await page.getByRole('button', { name: '预览与版本测试', exact: true }).click()
  await expect(page.locator('.run-table tbody tr')).toHaveCount(2)
  await expect(page.getByText('还有更早的运行记录，可继续读取；筛选只作用于已读取列表。')).toBeVisible()

  await page.getByRole('button', { name: '加载更早记录', exact: true }).click()
  await expect(page.locator('.run-table tbody tr')).toHaveCount(3)
  const first = f.calls.filter(c => c.path.endsWith('/workflows/runs'))
  expect(first).toHaveLength(1)
  expect(first[0]!.method).toBe('GET')
  expect(first[0]!.query.get('after')).toBe(f.runs[1]!.receipt.id)
  expect(first[0]!.query.get('limit')).toBe('20')
  expect(first[0]!.query.getAll('after')).toHaveLength(1)

  await page.getByRole('button', { name: '加载更早记录', exact: true }).click()
  await expect(page.locator('.run-table tbody tr')).toHaveCount(4)
  await expect(page.getByText('已到最早运行记录。')).toBeVisible()
  await expect(page.getByRole('button', { name: '加载更早记录', exact: true })).toHaveCount(0)
  expect(f.calls.every(c => c.method === 'GET')).toBe(true)
  expect(f.calls.some(c => c.path.endsWith('/workflows/entries'))).toBe(false)
})

test('history version selection continues through explicit entry pages', async ({ page }) => {
  const f = await fixture(page, 'entries')
  await page.goto('/#/integrations/workflows/runs')
  await page.getByRole('textbox', { name: tokenLabel }).fill(TOKEN_OK)
  const select = page.getByRole('combobox', { name: '历史发布版本' })
  await expect(select.locator('option')).toHaveCount(3)
  await expect(page.getByText('版本目录还有更早的发布版本，可继续读取；读取与选择都不执行任何操作。')).toBeVisible()

  await page.getByRole('button', { name: '读取更早版本', exact: true }).click()
  await expect(select.locator('option')).toHaveCount(4)
  await expect(select.locator('option', { hasText: 'Fixture 最早流程' })).toHaveCount(1)
  const reads = f.calls.filter(c => c.path.endsWith('/workflows/entries'))
  expect(reads).toHaveLength(1)
  expect(reads[0]!.method).toBe('GET')
  expect(reads[0]!.query.get('state')).toBe('PUBLISHED')
  expect(reads[0]!.query.get('after')).toBe('flow-one:1')
  expect(reads[0]!.query.get('limit')).toBe('20')
  await expect(page.getByText('已到最早发布版本。')).toBeVisible()
  await expect(page.getByRole('button', { name: '读取更早版本', exact: true })).toHaveCount(0)

  // Selecting the newly read version is explicit and reads that version's own history.
  await select.selectOption('flow-oldest@1')
  await expect.poll(() => f.calls.filter(c => c.path.endsWith('/history')).map(c => c.path).join('|')).toContain('/workflows/flow-oldest/versions/1/history')
  expect(f.calls.every(c => c.method === 'GET')).toBe(true)
})

test('comparison drawer reads earlier saved versions only explicitly', async ({ page }) => {
  const f = await fixture(page, 'options')
  await page.goto('/#/integrations/workflows')
  await page.getByRole('textbox', { name: tokenLabel }).fill(TOKEN_OK)
  await page.getByRole('button', { name: '查看版本', exact: true }).first().click()
  await page.getByRole('button', { name: '比较版本', exact: true }).first().click()
  const panel = page.getByRole('dialog', { name: '版本比较', exact: true })
  await expect(panel.getByLabel('基准版本', { exact: true }).locator('option')).toHaveCount(3)
  await expect(panel.getByText('选项来自最近版本列表，可点击“读取更早版本”继续读取更早条目；读取不比较、不保存、不发布。')).toBeVisible()

  await panel.getByRole('button', { name: '读取更早版本', exact: true }).click()
  await expect(panel.getByLabel('基准版本', { exact: true }).locator('option')).toHaveCount(4)
  await expect(panel.getByLabel('对比版本', { exact: true }).locator('option', { hasText: 'v1 · 已发布' })).toHaveCount(1)
  const reads = f.calls.filter(c => c.path.endsWith('/workflows/entries'))
  expect(reads).toHaveLength(1)
  expect(reads[0]!.method).toBe('GET')
  expect(reads[0]!.query.get('workflowId')).toBe('flow-two')
  expect(reads[0]!.query.get('limit')).toBe('50')
  expect(reads[0]!.query.get('after')).toBeNull()
  await expect(panel.getByRole('button', { name: '读取更早版本', exact: true })).toBeDisabled()
  expect(f.calls.some(c => c.path.endsWith('/comparisons'))).toBe(false)
  expect(f.calls.every(c => c.method === 'GET')).toBe(true)
})

test('pagination stays keyboard operable and bounded at 390px', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 1000 })
  const f = await fixture(page, 'runs')
  await page.goto('/#/integrations/workflows/runs')
  await page.getByRole('textbox', { name: tokenLabel }).fill(TOKEN_OK)
  await page.getByRole('button', { name: '预览与版本测试', exact: true }).click()
  const button = page.getByRole('button', { name: '加载更早记录', exact: true })
  await button.focus()
  await expect(button).toBeFocused()
  await page.keyboard.press('Enter')
  await expect(page.locator('.run-table tbody tr')).toHaveCount(3)
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  expect(f.calls.every(c => c.method === 'GET')).toBe(true)
})

test('a repeated run page is rejected instead of silently merging', async ({ page }) => {
  await fixture(page, 'duplicate')
  await page.goto('/#/integrations/workflows/runs')
  await page.getByRole('textbox', { name: tokenLabel }).fill(TOKEN_OK)
  await page.getByRole('button', { name: '预览与版本测试', exact: true }).click()
  await page.getByRole('button', { name: '加载更早记录', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('运行记录重复，请刷新运行记录。')
  await expect(page.locator('.run-table tbody tr')).toHaveCount(2)
})
