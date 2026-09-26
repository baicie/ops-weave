import { expect, test, type Page } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { TOKEN_OK } from './helpers.ts'

const example = (name: string) => JSON.parse(readFileSync(resolve(import.meta.dirname, '../../../contracts/examples', name + '.json'), 'utf8'))
const version = example('pipeline-version')
const report = example('pipeline-evaluation')
const durable = example('pipeline-replay-run')
async function open(page: Page) {
  await page.goto('/#/integrations/pipelines')
  await page.getByRole('textbox', { name: '平台开发 Token（仅保存在当前标签页内存）' }).fill(TOKEN_OK)
  await page.getByRole('textbox', { name: '流水线 ID', exact: true }).fill(version.definition.id)
  await page.getByRole('textbox', { name: '版本号', exact: true }).fill(String(version.definition.revision))
  await page.getByRole('textbox', { name: '同步批次 ID' }).fill(report.syncRunId)
  await page.getByRole('combobox', { name: '主机显示名' }).selectOption('host')
  await page.getByRole('combobox', { name: '记录拒绝策略' }).selectOption(version.definition.errorPolicy)
}
test('preview, immutable publication and read-only replay use the reviewed reference', async ({ page }, testInfo) => {
  const requests: { url: string; body: any; authorization: string }[] = []
  await page.route('**/api/v1/integrations/zabbix/hosts/**', async route => {
    const req = route.request()
    const body = req.postData() ? req.postDataJSON() : null
    requests.push({ url: req.url(), body, authorization: req.headers().authorization })
    let output = report
    if (req.url().endsWith('/sync')) output = { snapshotComplete: true, syncRunId: report.syncRunId, pipelineVersion: report.originalVersion }
    if (req.url().endsWith('/preview')) output = { ...report, mode: 'PREVIEW', purpose: 'VALIDATE_MAPPING' }
    if (req.url().endsWith('/versions')) output = version
    if (req.url().endsWith('/replay-runs')) output = { ...durable, run: { ...durable.run, requestKey: body.requestKey } }
    await route.fulfill({ status: 200, json: output })
  })
  await open(page)
  await expect(page.getByRole('button', { name: '发布预览版本' })).toBeDisabled()
  await page.getByRole('button', { name: '同步 Host 并取得批次' }).click()
  await expect(page.getByRole('status')).toContainText('Host 同步完成')
  await page.getByRole('button', { name: '预览当前定义' }).click()
  await expect(page.getByRole('heading', { name: '预览结果' })).toBeVisible()
  await expect(page.locator('[data-evaluation]')).toContainText('labeled-fixture')
  await expect(page.locator('[data-evaluation]')).toContainText('未执行写入')
  await page.getByRole('button', { name: '发布预览版本' }).click()
  await expect(page.locator('[data-published]')).toContainText(version.digest)
  await page.getByRole('button', { name: '只读重放', exact: true }).click()
  await expect(page.getByRole('heading', { name: '只读重放结果' })).toBeVisible()
  const published = requests.find(req => req.url.endsWith('/versions'))!
  expect(published.body).toEqual(version.definition)
  const replay = requests.find(req => req.url.endsWith('/replay-runs'))!
  expect(replay.body).toEqual({ requestKey: expect.stringMatching(/^[0-9a-f-]{36}$/), syncRunId: report.syncRunId, targetVersion: report.targetVersion, purpose: 'COMPARE_VERSION', dryRun: true, limit: 100 })
  expect(requests.every(req => req.authorization === `Bearer ${TOKEN_OK}`)).toBe(true)
  expect(requests.every(req => !JSON.stringify(req.body).includes('tenantId'))).toBe(true)
  await page.screenshot({ path: testInfo.outputPath('pipeline.png'), fullPage: true })
})

test('reads an existing version and reports a replay conflict without stale results', async ({ page }) => {
  await page.route('**/pipeline/versions/**', route => route.fulfill({ status: 200, json: version }))
  await page.route('**/pipeline/replay-runs', route => route.fulfill({ status: 409, json: { error: 'LINEAGE_UNAVAILABLE' } }))
  await open(page)
  await page.getByRole('button', { name: '读取已发布版本' }).click()
  await expect(page.locator('[data-published]')).toContainText(version.digest)
  await page.getByRole('button', { name: '只读重放', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('LINEAGE_UNAVAILABLE')
  await expect(page.locator('[data-evaluation]')).toHaveCount(0)
})

test('changing credentials invalidates a pending preview and clears the previous run', async ({ page }) => {
  let requested = false
  await page.route('**/pipeline/preview', async route => {
    requested = true
    await new Promise(resolve => setTimeout(resolve, 250))
    await route.fulfill({ status: 200, json: { ...report, mode: 'PREVIEW' } }).catch(() => {})
  })
  await open(page)
  await page.getByRole('button', { name: '预览当前定义' }).click()
  await expect.poll(() => requested).toBe(true)
  await page.getByRole('textbox', { name: '平台开发 Token（仅保存在当前标签页内存）' }).fill(TOKEN_OK + '-changed')
  await expect(page.getByRole('textbox', { name: '同步批次 ID' })).toHaveValue('')
  await expect(page.locator('[data-evaluation]')).toHaveCount(0)
  await expect(page.getByRole('button', { name: '发布预览版本' })).toBeDisabled()
  await page.waitForTimeout(350)
  await expect(page.locator('[data-evaluation]')).toHaveCount(0)
})

test('gaps and failed source runs remain explicit and host names are plain text', async ({ page }) => {
  const hostile = '<img src=x onerror=alert(1)>'
  const rows = report.rows.map((row: any) => ({ ...row, candidate: { ...row.candidate, name: hostile } }))
  await page.route('**/pipeline/preview', route => route.fulfill({ status: 200,
    json: { ...report, mode: 'PREVIEW', fetched: report.fetched + 1, missingRaw: 1, truncated: true, sourceRunStatus: 'FAILED', rows } }))
  await open(page)
  await page.getByRole('button', { name: '预览当前定义' }).click()
  await expect(page.locator('[data-gap]')).toContainText('数据不完整')
  await expect(page.locator('[data-evaluation]')).toContainText(hostile)
  await expect(page.locator('[data-page=pipelines] img')).toHaveCount(0)
  await page.getByRole('combobox', { name: '主机显示名' }).selectOption('name')
  await expect(page.locator('[data-evaluation]')).toHaveCount(0)
  await expect(page.getByRole('button', { name: '发布预览版本' })).toBeDisabled()
})

for (const failure of ['wrong-run', 'writes', 'unavailable']) {
  test(`rejects ${failure} without presenting a successful preview`, async ({ page }) => {
    await page.route('**/pipeline/preview', route => route.fulfill({ status: failure === 'unavailable' ? 503 : 200,
      json: failure === 'unavailable' ? { error: 'REPLAY_BUSY' } : { ...report, mode: 'PREVIEW',
        ...(failure === 'writes' ? { writesPerformed: true } : { syncRunId: '11111111-1111-4111-8111-111111111111' }) } }))
    await open(page)
    await page.getByRole('button', { name: '预览当前定义' }).click()
    await expect(page.getByRole('alert')).not.toBeEmpty()
    await expect(page.locator('[data-evaluation]')).toHaveCount(0)
    await expect(page.getByRole('button', { name: '发布预览版本' })).toBeDisabled()
  })
}

test('history restores a saved report after page reload without executing a replay', async ({ page }) => {
  let posts = 0
  await page.route(/\/pipeline\/replay-runs(?:[/?].*)?$/, async route => {
    if (route.request().method() === 'POST') posts++
    await route.fulfill({ json: route.request().url().includes('?') ? example('pipeline-replay-history') : durable })
  })
  await open(page)
  await page.reload()
  await expect(page.getByRole('textbox', { name: '平台开发 Token（仅保存在当前标签页内存）' })).toHaveValue('')
  await page.getByRole('textbox', { name: '平台开发 Token（仅保存在当前标签页内存）' }).fill(TOKEN_OK)
  await page.getByRole('button', { name: '读取重放记录', exact: true }).click()
  await expect(page.locator('[data-replay-history]')).toContainText(durable.run.id)
  await page.getByRole('button', { name: '查看记录', exact: true }).click()
  await expect(page.locator('[data-replay-detail]')).toContainText('SUCCEEDED')
  await expect(page.locator('[data-evaluation]')).toContainText(report.rows[0].candidate.name)
  await expect(page.getByRole('button', { name: '恢复或确认过期记录' })).toBeDisabled()
  expect(posts).toBe(0)
  await page.getByRole('textbox', { name: '平台开发 Token（仅保存在当前标签页内存）' }).fill(TOKEN_OK + '-changed')
  await expect(page.locator('[data-replay-detail]')).toHaveCount(0)
  await expect(page.locator('[data-replay-history]')).toHaveCount(0)
  await expect(page.locator('[data-evaluation]')).toHaveCount(0)
})

test('uncertain replay retry reuses the original request key and parameters', async ({ page }) => {
  const bodies: any[] = []
  await page.route('**/pipeline/versions/**', route => route.fulfill({ json: version }))
  await page.route('**/pipeline/replay-runs', async route => {
    const body = route.request().postDataJSON(); bodies.push(body)
    if (bodies.length === 1) await route.abort('failed')
    else await route.fulfill({ json: { ...durable, run: { ...durable.run, requestKey: body.requestKey } } })
  })
  await open(page)
  await page.getByRole('button', { name: '读取已发布版本' }).click()
  await page.getByRole('button', { name: '只读重放', exact: true }).click()
  await expect(page.getByRole('button', { name: '按原请求重试' })).toBeVisible()
  await expect(page.getByRole('button', { name: '只读重放', exact: true })).toBeDisabled()
  await page.getByRole('button', { name: '按原请求重试' }).click()
  await expect(page.locator('[data-replay-detail]')).toContainText('SUCCEEDED')
  expect(bodies).toHaveLength(2); expect(bodies[0]).toEqual(bodies[1])
})

test('active run is read-only until lease expires and explicit recovery preserves its request', async ({ page }) => {
  let expired = false, posts = 0
  const current = () => ({ ...durable, report: null, run: { ...durable.run, state: 'RUNNING', canResume: expired,
    leaseUntil: expired ? '2020-01-01T00:02:00Z' : '2099-01-01T00:02:00Z' } })
  await page.route(/\/pipeline\/replay-runs(?:[/?].*)?$/, async route => {
    const req = route.request()
    if (req.method() === 'POST') {
      posts++; expect(req.postDataJSON()).toEqual({ ...durable.run.spec, requestKey: durable.run.requestKey, dryRun: true })
      await route.fulfill({ json: { ...durable, run: { ...durable.run, attempt: 2 } } })
    } else await route.fulfill({ json: req.url().includes('?') ? { storage: 'postgres', items: [current().run], nextCursor: null } : current() })
  })
  await open(page)
  await page.getByRole('button', { name: '读取重放记录', exact: true }).click()
  await page.getByRole('button', { name: '查看记录', exact: true }).click()
  await expect(page.locator('[data-replay-detail]')).toContainText('RUNNING')
  await expect(page.locator('[data-evaluation]')).toHaveCount(0)
  await expect(page.getByRole('button', { name: '恢复或确认过期记录' })).toBeDisabled()
  expired = true
  await page.getByRole('button', { name: '刷新记录状态' }).click()
  await expect(page.getByRole('button', { name: '恢复或确认过期记录' })).toBeEnabled()
  expect(posts).toBe(0)
  await page.getByRole('button', { name: '恢复或确认过期记录' }).click()
  await expect(page.locator('[data-replay-detail]')).toContainText('第 2/3 次')
  await expect(page.locator('[data-replay-detail]')).toContainText('SUCCEEDED')
  expect(posts).toBe(1)
})

test('history pagination uses the returned cursor and a denied detail clears prior data', async ({ page }) => {
  let denied = false
  await page.route(/\/pipeline\/replay-runs(?:[/?].*)?$/, async route => {
    const url = new URL(route.request().url())
    if (url.searchParams.has('limit')) {
      const next = url.searchParams.has('before')
      if (next) expect(url.searchParams.get('before')).toBe(durable.run.id)
      await route.fulfill({ json: { storage: 'postgres', items: next ? [] : [durable.run], nextCursor: next ? null : durable.run.id } })
    } else await route.fulfill({ status: denied ? 403 : 200, json: denied ? { error: 'FORBIDDEN' } : durable })
  })
  await open(page)
  await page.getByRole('button', { name: '读取重放记录', exact: true }).click()
  await page.getByRole('button', { name: '下一页记录' }).click()
  await expect(page.getByRole('button', { name: '下一页记录' })).toBeDisabled()
  await page.getByRole('button', { name: '读取重放记录', exact: true }).click()
  await page.getByRole('button', { name: '查看记录', exact: true }).click()
  await expect(page.locator('[data-evaluation]')).toBeVisible()
  denied = true
  await page.getByRole('button', { name: '刷新记录状态' }).click()
  await expect(page.getByRole('alert')).toContainText('FORBIDDEN')
  await expect(page.locator('[data-evaluation]')).toHaveCount(0)
  await expect(page.locator('[data-replay-history]')).toHaveCount(0)
})

for (const malformed of ['wrong-report', 'running-report', 'writes']) {
  test(`stored replay rejects ${malformed}`, async ({ page }) => {
    await page.route(/\/pipeline\/replay-runs(?:[/?].*)?$/, route => route.fulfill({ json: route.request().url().includes('?')
      ? example('pipeline-replay-history') : { ...durable,
        ...(malformed === 'wrong-report' ? { report: { ...report, syncRunId: '33333333-3333-4333-8333-333333333333' } }
          : { run: { ...durable.run, ...(malformed === 'writes' ? { writesPerformed: true } : { state: 'RUNNING', leaseUntil: '2099-01-01T00:02:00Z' }) } }) } }))
    await open(page)
    await page.getByRole('button', { name: '读取重放记录', exact: true }).click()
    await page.getByRole('button', { name: '查看记录', exact: true }).click()
    await expect(page.getByRole('alert')).toContainText('响应结构不正确')
    await expect(page.locator('[data-evaluation]')).toHaveCount(0)
  })
}
