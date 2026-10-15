import { expect, test, type Page } from '@playwright/test'
import { readFileSync, mkdirSync } from 'node:fs'
import { createHash } from 'node:crypto'
import path from 'node:path'
import { TOKEN_OK } from './helpers.ts'

// Explicit HTTP fixtures exercise the browser boundary; they are not a Runtime/model acceptance.
const example = JSON.parse(readFileSync(new URL('../../../contracts/examples/ai-insight-result.json', import.meta.url), 'utf8'))
const evidenceExample = JSON.parse(readFileSync(new URL('../../../contracts/examples/platform-evidence.json', import.meta.url), 'utf8'))
const RUN = '10000000-0000-4000-8000-000000000083'
const tokenLabel = '平台开发 Token（仅保存在当前标签页内存）'
const hash = createHash('sha256')
for (const file of ['skill.json', 'prompt.md', 'output.schema.json']) {
  const bytes = readFileSync(new URL(`../../../extensions/skills/incident-diagnosis-current/${file}`, import.meta.url))
  const length = Buffer.alloc(8); length.writeBigUInt64BE(BigInt(bytes.length)); hash.update(length).update(bytes)
}
const skillDigest = `sha256:${hash.digest('hex')}`
type Command = { requestId: string; incidentId: string; question: string; timeRange: { from: string; to: string }; knowledgeMode: string; skill: { id: string; version: string; digest: string } }
function run(command: Command, overrides: Record<string, unknown> = {}) {
  return { ...command, schemaVersion: '2.0', runId: RUN, commandDigest: `sha256:${'b'.repeat(64)}`, status: 'PENDING', statusVersion: 0,
    attempt: 0, createdAt: '2026-10-13T01:00:01Z', updatedAt: '2026-10-13T01:00:07Z', ...overrides }
}
function saved(command: Command) {
  const result = structuredClone(example)
  Object.assign(result.record, { id: RUN, incidentId: command.incidentId, question: command.question,
    queryWindow: command.timeRange, skill: command.skill, expiresAt: new Date(Date.now() + 3600000).toISOString() })
  return result
}
async function enter(page: Page) {
  await page.goto(`/#/incidents/current-diagnose?incidentId=${example.record.incidentId}`)
  await page.getByRole('textbox', { name: tokenLabel }).fill(TOKEN_OK)
}
async function advanced(page: Page) { await page.locator('.iw-advanced > summary').click() }

test('one pinned durable submission, explicit run and bound result reads, no polling or evidence prefetch', async ({ page }) => {
  const calls: string[] = []; let command: Command
  await page.route('**/api/v1/**', async route => {
    const request = route.request(), url = new URL(request.url()); calls.push(`${request.method()} ${url.pathname}`)
    expect(request.headers().authorization).toBe(`Bearer ${TOKEN_OK}`)
    expect(request.headers()['x-opsweave-runtime-key']).toBeUndefined()
    if (request.method() === 'POST') {
      expect(url.pathname).toBe('/api/v1/ai/runs'); command = request.postDataJSON()
      expect(Object.keys(command).sort()).toEqual(['incidentId', 'knowledgeMode', 'question', 'requestId', 'skill', 'timeRange'])
      expect(command.skill).toEqual({ id: 'incident.diagnose', version: '2.0.0', digest: skillDigest })
      expect(command.requestId).not.toBe(RUN); expect(request.headers()['x-opsweave-request-id']).not.toBe(command.requestId)
      return route.fulfill({ json: run(command) })
    }
    if (url.pathname === `/api/v1/ai/runs/${RUN}`) return route.fulfill({ json: run(command, { status: 'SUCCEEDED', statusVersion: 3, attempt: 1 }) })
    if (url.pathname === `/api/v1/ai/insights/${RUN}`) return route.fulfill({ json: saved(command) })
    throw new Error(`Unexpected request ${url.pathname}`)
  })
  await enter(page); expect(calls).toEqual([])
  await page.getByRole('button', { name: '开始只读调查', exact: true }).click()
  await expect(page.locator('[data-run-detail]')).toContainText('待认领')
  expect(calls).toEqual(['POST /api/v1/ai/runs'])
  await page.getByRole('button', { name: '读取调查进度', exact: true }).click()
  await expect(page.locator('[data-run-detail]')).toContainText('运行已完成')
  await page.getByRole('button', { name: '读取调查报告', exact: true }).click()
  await expect(page.locator('[data-insight-result]')).toContainText('尚未确认根因')
  await expect(page.locator('[data-insight-result] [data-mode="labeled-fixture"]')).toBeVisible()
  await expect(page.locator('[data-insight-result] [data-mode="mock"]')).toBeVisible()
  expect(calls).toEqual(['POST /api/v1/ai/runs', `GET /api/v1/ai/runs/${RUN}`, `GET /api/v1/ai/insights/${RUN}`])
  await page.getByRole('textbox', { name: tokenLabel }).fill('')
  await expect(page.locator('[data-insight-result]')).toHaveCount(0); await expect(page.locator('[data-run-detail]')).toHaveCount(0)
  await expect(page.locator('#iw-incident')).toHaveValue(''); await expect(page.locator('#iw-run-lookup')).toHaveValue('')
})

test('UNKNOWN keeps original immutable command; recovery never implies absence, replay uses same request', async ({ page }) => {
  const commands: Command[] = []; let reads = 0
  await page.route('**/api/v1/ai/runs', route => {
    if (route.request().method() === 'GET') { ++reads; return route.fulfill({ json: { schemaVersion: '2.0', items: [], hasMore: false, nextCursor: null } }) }
    const body = route.request().postDataJSON(); commands.push(body)
    return route.fulfill(commands.length === 1 ? { status: 503, json: { error: 'UNAVAILABLE' } } : { json: run(body) })
  })
  await enter(page); await page.getByRole('button', { name: '开始只读调查', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('UNKNOWN')
  await expect(page.getByRole('textbox', { name: '调查问题' })).toBeDisabled()
  await expect(page.getByRole('button', { name: '原样重发原请求' })).toBeDisabled()
  await advanced(page); await expect(page.getByRole('button', { name: '准备新调查' })).toBeDisabled()
  await expect(page.getByRole('combobox', { name: '提交方式' })).toBeDisabled()
  expect(commands).toHaveLength(1)
  await page.getByRole('button', { name: '回查原请求', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('不能证明未受理'); expect(reads).toBe(1)
  await page.getByRole('button', { name: '原样重发原请求' }).click()
  await expect(page.locator('[data-run-detail]')).toBeVisible()
  expect(commands).toHaveLength(2); expect(commands[1]).toEqual(commands[0])
})

for (const mismatch of ['requestId', 'question', 'skill']) test(`rejects mismatched submission ${mismatch} and preserves UNKNOWN`, async ({ page }) => {
  await page.route('**/api/v1/ai/runs', route => {
    const command = route.request().postDataJSON(), response = run(command)
    if (mismatch === 'requestId') response.requestId = RUN
    if (mismatch === 'question') response.question = 'unrelated question'
    if (mismatch === 'skill') response.skill = { ...command.skill, digest: `sha256:${'a'.repeat(64)}` }
    return route.fulfill({ json: response })
  })
  await enter(page); await page.getByRole('button', { name: '开始只读调查', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('UNKNOWN')
  await expect(page.locator('[data-run-detail]')).toHaveCount(0)
  await expect(page.locator('[data-insight-result]')).toHaveCount(0)
})

test('recovery finds the matching request, wrong run read is rejected, report must match the admitted Skill', async ({ page }) => {
  let command: Command; let reads = 0
  await page.route('**/api/v1/**', route => {
    const request = route.request(), url = new URL(request.url())
    if (request.method() === 'POST') { command = request.postDataJSON(); return route.fulfill({ status: 503, json: {} }) }
    if (url.pathname === '/api/v1/ai/runs') return route.fulfill({ json: { schemaVersion: '2.0', items: [run(command)], hasMore: false, nextCursor: null } })
    if (url.pathname === `/api/v1/ai/runs/${RUN}`) {
      ++reads; return route.fulfill({ json: run(command, { runId: reads === 1 ? command.requestId : RUN, status: 'SUCCEEDED', statusVersion: 3, attempt: 1 }) })
    }
    const result = saved(command); result.record.skill.digest = `sha256:${'a'.repeat(64)}`
    return route.fulfill({ json: result })
  })
  await enter(page); await page.getByRole('button', { name: '开始只读调查' }).click()
  await expect(page.getByRole('alert')).toContainText('UNKNOWN')
  await page.getByRole('button', { name: '回查原请求', exact: true }).click()
  await expect(page.locator('[data-run-detail]')).toContainText('待认领')
  await page.getByRole('button', { name: '读取调查进度' }).click()
  await expect(page.getByRole('alert')).not.toBeEmpty(); await expect(page.locator('[data-run-detail]')).toContainText('待认领')
  await page.getByRole('button', { name: '读取调查进度' }).click()
  await page.getByRole('button', { name: '读取调查报告' }).click()
  await expect(page.getByRole('alert')).toContainText('固定 Skill 不一致'); await expect(page.locator('[data-insight-result]')).toHaveCount(0)
})

test('an unrelated AIRun cannot replace an UNKNOWN synchronous command', async ({ page }) => {
  let posts = 0, runReads = 0, original = ''
  await page.route('**/api/v1/**', route => {
    if (route.request().method() === 'POST') { ++posts; original = route.request().postDataJSON().runId; return route.fulfill({ status: 504, json: {} }) }
    if (route.request().url().includes('/ai/runs/')) { ++runReads; return route.fulfill({ status: 404, json: {} }) }
    expect(route.request().url()).toContain(`/ai/insights/${original}`)
    return route.fulfill({ status: 404, json: {} })
  })
  await enter(page); await advanced(page)
  await page.getByRole('combobox', { name: '提交方式', exact: true }).selectOption('synchronous')
  await page.getByRole('textbox', { name: 'AIRun 运行标识' }).fill(RUN)
  await page.getByRole('button', { name: '开始同步诊断', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('UNKNOWN')
  await page.getByRole('button', { name: '读取运行记录', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('不能用其他运行替换原请求')
  expect(runReads).toBe(0); expect(posts).toBe(1)
  await page.getByRole('button', { name: '读取已保存结果', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('尚未找到已保存结果')
  await expect(page.locator('[data-diagnosis-pending]')).toBeVisible(); expect(posts).toBe(1)
})

test('evidence expires independently while the still-valid report remains available', async ({ page }) => {
  await page.clock.install()
  const command: Command = { requestId: RUN, incidentId: example.record.incidentId, question: '核查证据有效期', timeRange: example.record.queryWindow,
    knowledgeMode: 'current', skill: { id: 'incident.diagnose', version: '2.0.0', digest: skillDigest } }
  const result = saved(command), doc = structuredClone(evidenceExample)
  Object.assign(doc, { sessionId: result.record.sessionId, incidentVersion: result.record.incidentVersion, entityIds: result.record.entityIds, queryWindow: result.record.queryWindow })
  Object.assign(doc.evidence, { id: result.record.evidenceIds[0], sourceRef: `/api/v1/ai/evidence/${result.record.evidenceIds[0]}`,
    tenantId: result.record.tenantId, incidentId: result.record.incidentId, expiresAt: new Date(Date.now() + 60000).toISOString() })
  await page.route('**/api/v1/ai/insights/*', route => route.fulfill({ json: result }))
  await page.route('**/api/v1/ai/evidence/*', route => route.fulfill({ json: doc }))
  await page.goto(`/#/incidents/current-diagnose?runId=${RUN}`)
  await page.getByRole('textbox', { name: tokenLabel }).fill(TOKEN_OK)
  await advanced(page); await page.getByRole('button', { name: '读取已保存结果' }).click()
  await page.getByRole('button', { name: '证据 1', exact: true }).first().click()
  await expect(page.locator('[data-insight-evidence]')).toBeVisible()
  await page.clock.fastForward(61000)
  await expect(page.locator('[data-insight-evidence]')).toHaveCount(0)
  await expect(page.getByRole('alert')).toContainText('证据快照已过期')
  await expect(page.locator('[data-insight-result]')).toHaveCount(1)
})

test('a hidden investigation clears private state without changing the active route on identity change', async ({ page }) => {
  await page.route('**/api/v1/ai/runs', route => route.fulfill(route.request().method() === 'POST'
    ? { json: run(route.request().postDataJSON()) } : { json: { schemaVersion: '2.0', items: [], hasMore: false, nextCursor: null } }))
  await enter(page); await page.getByRole('button', { name: '开始只读调查' }).click()
  await page.getByRole('link', { name: '查看运行记录与事件' }).click()
  await expect(page).toHaveURL(/#\/ai\/runs$/)
  await page.getByRole('textbox', { name: tokenLabel }).fill('')
  await expect(page).toHaveURL(/#\/ai\/runs$/)
})

for (const tamper of ['commandDigest', 'createdAt', 'terminal']) test(`later run read rejects immutable/terminal tamper ${tamper}`, async ({ page }) => {
  let command: Command
  await page.route('**/api/v1/**', route => {
    if (route.request().method() === 'POST') { command = route.request().postDataJSON(); return route.fulfill({ json: run(command, { status: 'FAILED', reason: 'RESULT_LOST', statusVersion: 3 }) }) }
    const response = run(command, { status: 'FAILED', reason: 'RESULT_LOST', statusVersion: 4 })
    if (tamper === 'commandDigest') response.commandDigest = `sha256:${'a'.repeat(64)}`
    if (tamper === 'createdAt') response.createdAt = '2026-10-13T01:00:02Z'
    if (tamper === 'terminal') response.status = 'CANCELLED'
    return route.fulfill({ json: response })
  })
  await enter(page); await page.getByRole('button', { name: '开始只读调查' }).click()
  await page.getByRole('button', { name: '读取调查进度' }).click()
  await expect(page.getByRole('alert')).toContainText(tamper === 'terminal' ? '终态不能再次迁移' : '不可变审计依据')
  await expect(page.locator('[data-run-detail]')).toContainText('运行失败')
})

for (const width of [1440, 1024, 390]) for (const theme of ['light', 'dark']) test(`visual fixture ${width} ${theme}`, async ({ page }) => {
  await page.setViewportSize({ width, height: width === 390 ? 844 : width === 1024 ? 768 : 900 })
  await page.addInitScript(theme => localStorage.setItem('opsweave.ui.theme', theme), theme)
  await page.route('**/api/v1/ai/insights/*', route => route.fulfill({ json: saved({ requestId: RUN, incidentId: example.record.incidentId,
    question: '分析 CPU 峰值与当前可见证据，保留未验证的假设。', timeRange: example.record.queryWindow, knowledgeMode: 'current',
    skill: { id: 'incident.diagnose', version: '2.0.0', digest: skillDigest } }) }))
  await page.goto(`/#/incidents/current-diagnose?runId=${RUN}`)
  await expect(page.locator('html')).toHaveAttribute('data-theme', theme)
  await page.getByRole('textbox', { name: tokenLabel }).fill(TOKEN_OK)
  await advanced(page); await page.getByRole('button', { name: '读取已保存结果' }).click()
  await expect(page.locator('[data-insight-result]')).toBeVisible()
  await page.locator('.iw-advanced > summary').click()
  // The console scrolls its workspace rather than window; audit the actual page top.
  await page.locator('[data-investigation-workspace]').evaluate(element => {
    for (let current: Element | null = element; current; current = current.parentElement) current.scrollTop = 0
  })
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  if (process.env.OPSWEAVE_VISUAL_OUTPUT) {
    mkdirSync(process.env.OPSWEAVE_VISUAL_OUTPUT, { recursive: true })
    await page.screenshot({ path: path.join(process.env.OPSWEAVE_VISUAL_OUTPUT, `investigation-${width}-${theme}.png`), fullPage: true })
  }
})
