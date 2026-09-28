import { test, expect, type Page } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { TOKEN_OK } from './helpers.ts'
const bundle = JSON.parse(readFileSync(new URL('../../../contracts/catalog/opsweave-core-1.0.0.json', import.meta.url), 'utf8'))
async function setup(page: Page, route = 'entities') {
  const calls: { path: string; body: any }[] = []; const drafts: any[] = [], published: any[] = []
  await page.route(/\/api\/v1\/catalog(?:\/|$)/, async request => {
    const url = new URL(request.request().url()), body = request.request().postDataJSON(); calls.push({ path: url.pathname, body })
    let response: unknown
    if (url.pathname === '/api/v1/catalog') response = { schemaVersion: '1.0', storage: 'memory', package: bundle, published: { items: published, truncated: false }, drafts: { items: drafts, truncated: false } }
    else if (url.pathname.endsWith('/drafts')) { response = { definition: body.definition, state: 'DRAFT', editVersion: body.expectedEditVersion + 1, digest: 'sha256:' + 'a'.repeat(64), updatedAt: '2026-09-27T10:00:00Z' }; drafts.splice(0, drafts.length, response) }
    else if (url.pathname.endsWith('/publish')) { response = { ...drafts[0], state: 'PUBLISHED', editVersion: 0 }; published.push(response) }
    else response = { valid: true, values: { name: 'api' }, issues: [], changes: [{ field: 'name', rule: 'trim-text' }] }
    await request.fulfill({ contentType: 'application/json', body: JSON.stringify(response) })
  })
  await page.goto('/#/modeling/' + route)
  await page.getByRole('textbox', { name: '平台开发 Token（仅保存在当前标签页内存）' }).fill(TOKEN_OK)
  await page.getByRole('button', { name: '读取模型目录', exact: true }).click()
  return calls
}
for (const width of [1440, 390]) test('built-in definitions and readonly preview at ' + width, async ({ page }) => {
  await page.setViewportSize({ width, height: 900 }); const calls = await setup(page)
  await expect(page.getByRole('heading', { name: '实体模型' })).toBeVisible()
  await expect(page.locator('button.model-card')).toHaveCount(5)
  await page.getByRole('button', { name: /内置 主机/ }).click()
  const dialog = page.getByRole('dialog', { name: '模型定义' }); await expect(dialog).toBeVisible()
  await expect(dialog.getByRole('textbox', { name: '类型标识', exact: true })).toHaveValue('builtin.host')
  await expect(dialog.getByRole('textbox', { name: '类型标识', exact: true })).toBeDisabled()
  await dialog.getByRole('textbox', { name: '预览样本', exact: true }).fill('{"name":" api "}')
  await dialog.getByRole('button', { name: '预览清洗结果' }).click(); await expect(dialog.getByText('样本通过校验', { exact: true })).toBeVisible()
  expect(calls.filter(c => c.path.endsWith('/preview'))).toHaveLength(1); expect(calls.some(c => c.path.endsWith('/publish') || c.path.endsWith('/drafts'))).toBe(false)
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  await page.keyboard.press('Escape'); await expect(dialog).not.toBeVisible()
})
test('custom fields save and publish with edit version, with no implicit preview or model call', async ({ page }) => {
  const calls = await setup(page); await page.getByRole('button', { name: '新建实体类型' }).click()
  const dialog = page.getByRole('dialog', { name: '模型定义' })
  await dialog.getByRole('textbox', { name: '类型标识', exact: true }).fill('custom.web_service')
  await dialog.getByRole('textbox', { name: '显示名称', exact: true }).fill('Web 服务')
  await dialog.getByRole('button', { name: '添加字段' }).click()
  await dialog.getByRole('textbox', { name: '字段标识 2', exact: true }).fill('port')
  await dialog.getByRole('textbox', { name: '字段名称 2', exact: true }).fill('端口')
  await dialog.getByRole('combobox', { name: '字段类型 2', exact: true }).selectOption('INTEGER')
  await dialog.getByRole('spinbutton', { name: '最小值 2', exact: true }).fill('1')
  await dialog.getByRole('spinbutton', { name: '最大值 2', exact: true }).fill('65535')
  await expect(dialog.getByRole('button', { name: '发布模型版本' })).toBeDisabled()
  await dialog.getByRole('button', { name: '保存私有草稿' }).click()
  await expect(dialog.getByText('私有草稿已保存', { exact: true })).toBeVisible()
  const saved = calls.find(c => c.path.endsWith('/drafts'))!.body
  expect(saved.expectedEditVersion).toBe(0); expect(saved.definition.fields[1]).toEqual({ id: 'port', label: '端口', type: 'INTEGER', required: false, min: 1, max: 65535 })
  await dialog.getByRole('button', { name: '发布模型版本' }).click()
  await expect(dialog.getByText('模型版本已发布，历史资产尚未自动转换', { exact: true })).toBeVisible()
  const command = calls.find(c => c.path.endsWith('/publish'))!.body
  expect(command.expectedEditVersion).toBe(1); expect(command.ref).toEqual({ id: 'custom.web_service', revision: 1 })
  expect(calls.some(c => c.path.endsWith('/preview'))).toBe(false)
  await dialog.getByRole('button', { name: '关闭模型定义' }).click()
  await expect(page.locator('button.model-card').filter({ hasText: 'Web 服务' })).toHaveCount(1)
})
test('relation editor pins entity endpoint versions', async ({ page }) => {
  const calls = await setup(page, 'relations'); await expect(page.locator('button.model-card')).toHaveCount(4)
  await page.getByRole('button', { name: '新建关系类型' }).click(); const dialog = page.getByRole('dialog', { name: '模型定义' })
  await dialog.getByRole('textbox', { name: '类型标识', exact: true }).fill('custom.uses')
  await dialog.getByRole('textbox', { name: '显示名称', exact: true }).fill('使用')
  await dialog.getByRole('combobox', { name: '起点实体类型', exact: true }).selectOption('builtin.application@1')
  await dialog.getByRole('combobox', { name: '终点实体类型', exact: true }).selectOption('builtin.database@1')
  await dialog.getByRole('combobox', { name: '关系基数', exact: true }).selectOption('ONE_TO_MANY')
  await dialog.getByRole('button', { name: '保存私有草稿' }).click(); await expect(dialog.getByText('私有草稿已保存', { exact: true })).toBeVisible()
  const d = calls.find(c => c.path.endsWith('/drafts'))!.body.definition
  expect(d.fields).toEqual([]); expect(d.endpoints).toEqual({ from: { id: 'builtin.application', revision: 1 }, to: { id: 'builtin.database', revision: 1 }, cardinality: 'ONE_TO_MANY' })
})
test('session change clears private models, editor and preview', async ({ page }) => {
  await setup(page); await page.getByRole('button', { name: '新建实体类型' }).click()
  await page.getByRole('dialog', { name: '模型定义' }).getByRole('textbox', { name: '显示名称', exact: true }).fill('private draft')
  await page.keyboard.press('Escape')
  await page.getByRole('textbox', { name: '平台开发 Token（仅保存在当前标签页内存）' }).fill('changed-token-long-enough-123456789')
  await expect(page.getByText('加载你的模型目录', { exact: true })).toBeVisible(); await expect(page.locator('button.model-card')).toHaveCount(0)
  expect(await page.evaluate(() => JSON.stringify({ ...localStorage, ...sessionStorage }))).not.toContain('private draft')
})
test('metric definitions never claim live samples', async ({ page }) => {
  const calls = await setup(page, 'metrics'); await expect(page.locator('article.model-metric')).toHaveCount(3)
  await page.getByText('默认清洗规则与来源版本说明',{exact:true}).click(); await expect(page.getByText('其他版本：尚未验证。不能仅凭版本号认定兼容。')).toBeVisible()
  expect(calls).toHaveLength(1); expect(calls[0].body).toBeNull()
})
