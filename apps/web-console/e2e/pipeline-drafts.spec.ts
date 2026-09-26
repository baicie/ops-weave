import { expect, test, type Page } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { TOKEN_OK } from './helpers.ts'

const sample = (name: string) => JSON.parse(readFileSync(resolve(import.meta.dirname, '../../../contracts/examples', name + '.json'), 'utf8'))
const draft = sample('pipeline-draft'), recent = sample('pipeline-draft-list'), report = sample('pipeline-evaluation')
async function open(page: Page) {
  await page.goto('/#/integrations/pipelines')
  await page.getByRole('textbox', { name: '平台开发 Token（仅保存在当前标签页内存）' }).fill(TOKEN_OK)
  await page.getByRole('textbox', { name: '流水线 ID', exact: true }).fill(draft.definition.id)
  await page.getByRole('textbox', { name: '版本号', exact: true }).fill(String(draft.definition.revision))
  await page.getByRole('combobox', { name: '主机显示名' }).selectOption('host')
  await page.getByRole('combobox', { name: '记录拒绝策略' }).selectOption(draft.definition.errorPolicy)
}
test('save, reload and load a private draft before preview and explicit publication', async ({ page }, testInfo) => {
  const writes: any[] = []
  await page.route(/\/pipeline\/drafts(?:[/?].*)?$/, async route => {
    if (route.request().method() === 'POST') writes.push(route.request().postDataJSON())
    await route.fulfill({ json: route.request().url().includes('?') ? recent : draft })
  })
  let publications = 0
  await page.route('**/pipeline/preview', route => route.fulfill({ json: { ...report, mode: 'PREVIEW' } }))
  await page.route('**/pipeline/versions', async route => { publications++; await route.fulfill({ json: sample('pipeline-version') }) })
  await open(page)
  await page.getByRole('button', { name: '保存草稿', exact: true }).click()
  await expect(page.locator('[data-draft]')).toContainText('草稿编辑号 1')
  expect(writes).toEqual([{ definition: draft.definition, expectedEditVersion: 0 }])
  expect(publications).toBe(0)
  await expect(page.getByRole('button', { name: '发布预览版本' })).toBeDisabled()
  await page.reload()
  await page.getByRole('textbox', { name: '平台开发 Token（仅保存在当前标签页内存）' }).fill(TOKEN_OK)
  await page.getByRole('button', { name: '读取最近草稿', exact: true }).click()
  await expect(page.locator('[data-draft-list]')).toContainText('开发内存')
  await page.getByRole('button', { name: '载入草稿', exact: true }).click()
  await expect(page.getByRole('textbox', { name: '流水线 ID', exact: true })).toHaveValue(draft.definition.id)
  await expect(page.getByRole('combobox', { name: '主机显示名' })).toHaveValue('host')
  await expect(page.getByRole('button', { name: '发布预览版本' })).toBeDisabled()
  await page.getByRole('textbox', { name: '同步批次 ID' }).fill(report.syncRunId)
  await page.getByRole('button', { name: '预览当前定义' }).click()
  await expect(page.locator('[data-evaluation]')).toBeVisible()
  await page.getByRole('button', { name: '发布预览版本' }).click()
  await expect(page.locator('[data-published]')).toContainText(draft.digest)
  expect(publications).toBe(1)
  await page.screenshot({ path: testInfo.outputPath('draft.png'), fullPage: true })
})

test('stale save preserves local edits and reload uses the newer edit version', async ({ page }) => {
  let loads = 0; const saves: any[] = []
  await page.route(/\/pipeline\/drafts(?:[/?].*)?$/, async route => {
    if (route.request().method() === 'POST') {
      saves.push(route.request().postDataJSON())
      if (saves.length === 1) await route.fulfill({ status: 409, json: { error: 'DRAFT_CONFLICT' } })
      else await route.fulfill({ json: { ...draft, editVersion: 3 } })
    } else { loads++; await route.fulfill({ json: { ...draft, editVersion: loads } }) }
  })
  await open(page)
  await page.getByRole('button', { name: '读取当前版本草稿' }).click()
  await expect(page.locator('[data-draft]')).toContainText('编辑号 1')
  await page.getByRole('combobox', { name: '主机显示名' }).selectOption('name')
  await expect(page.locator('[data-draft]')).toContainText('有未保存修改')
  await page.getByRole('button', { name: '保存草稿', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('DRAFT_CONFLICT')
  await expect(page.getByRole('combobox', { name: '主机显示名' })).toHaveValue('name')
  expect(saves[0].expectedEditVersion).toBe(1)
  await page.getByRole('button', { name: '读取当前版本草稿' }).click()
  await expect(page.locator('[data-draft]')).toContainText('编辑号 2')
  await page.getByRole('button', { name: '保存草稿', exact: true }).click()
  await expect(page.locator('[data-draft]')).toContainText('编辑号 3')
  expect(saves[1].expectedEditVersion).toBe(2)
})

test('changing identity clears drafts and ignores an old in-flight load', async ({ page }) => {
  let requested = false
  await page.route('**/pipeline/drafts?*', route => route.fulfill({ json: { ...recent, truncated: true } }))
  await page.route('**/pipeline/drafts/*/*', async route => {
    requested = true
    await new Promise(resolve => setTimeout(resolve, 250))
    await route.fulfill({ json: draft }).catch(() => {})
  })
  await open(page)
  await page.getByRole('button', { name: '读取最近草稿' }).click()
  await expect(page.locator('[data-draft-list]')).toContainText('仅显示最近 20')
  await page.getByRole('button', { name: '载入草稿', exact: true }).click()
  await expect.poll(() => requested).toBe(true)
  await page.getByRole('textbox', { name: '平台开发 Token（仅保存在当前标签页内存）' }).fill(TOKEN_OK + '-new')
  await page.waitForTimeout(350)
  await expect(page.locator('[data-draft]')).toHaveCount(0)
  await expect(page.locator('[data-draft-list]')).toHaveCount(0)
  await expect(page.getByRole('textbox', { name: '流水线 ID', exact: true })).toHaveValue('zabbix-host-default')
})

for (const failure of ['published', 'wrong-id', 'unavailable']) {
  test(`draft rejects ${failure} without showing a saved working copy`, async ({ page }) => {
    await page.route('**/pipeline/drafts', route => route.fulfill({ status: failure === 'unavailable' ? 503 : 200,
      json: failure === 'unavailable' ? { error: 'PIPELINE_STORE_UNAVAILABLE' } : { ...draft,
        ...(failure === 'published' ? { state: 'PUBLISHED' } : { definition: { ...draft.definition, id: 'different' } }) } }))
    await open(page)
    await page.getByRole('button', { name: '保存草稿', exact: true }).click()
    await expect(page.getByRole('alert')).not.toBeEmpty()
    await expect(page.locator('[data-draft]')).toHaveCount(0)
    await expect(page.getByRole('button', { name: '发布预览版本' })).toBeDisabled()
  })
}
