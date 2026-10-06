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
    else if(url.pathname.endsWith('/revisions/review')){const d=drafts.find(e=>e.definition.id===body.ref.id&&e.definition.revision===body.ref.revision),base=published.filter(e=>e.definition.id===body.ref.id).sort((a,b)=>b.definition.revision-a.definition.revision)[0];response={schemaVersion:'1.0',review:{candidate:{...body.ref,digest:body.digest},editVersion:body.expectedEditVersion,base:base?{id:base.definition.id,revision:base.definition.revision,digest:base.digest}:null,reviewedAt:'2026-10-04T07:00:00Z',compatible:true,reasons:[],changes:d.definition.fields.filter((f:any)=>!base?.definition.fields.some((old:any)=>old.id===f.id)).map((f:any)=>({fieldId:f.id,property:'FIELD',before:null,after:f.type+' · optional',compatible:true}))}}}
    else if(url.pathname.endsWith('/references')){const parts=url.pathname.split('/');response={schemaVersion:'1.0',report:{target:{id:parts.at(-3),revision:Number(parts.at(-2)),digest:published.find(e=>e.definition.id===parts.at(-3)&&e.definition.revision===Number(parts.at(-2)))?.digest??'sha256:'+'a'.repeat(64)},inspectedAt:'2026-10-04T07:00:00Z',workflowsAvailable:true,references:{items:[],truncated:false}}}}
    else response = { valid: true, values: { name: 'api' }, issues: [], changes: [{ field: 'name', rule: 'trim-text' }] }
    await request.fulfill({ contentType: 'application/json', body: JSON.stringify(response) })
  })
  await page.goto('/#/modeling/' + route)
  await page.getByRole('textbox', { name: '平台开发 Token（仅保存在当前标签页内存）' }).fill(TOKEN_OK)
  await expect(route === 'metrics' ? page.getByRole('table', {name:'内置指标列表'}) : page.locator('button.model-definition-button').first()).toBeVisible()
  return calls
}
for (const width of [1440, 390]) test('built-in definitions and readonly preview at ' + width, async ({ page }) => {
  await page.setViewportSize({ width, height: 900 }); const calls = await setup(page)
  await expect(page.getByRole('heading', { name: '实体模型' })).toBeVisible()
  await expect(page.locator('button.model-definition-button')).toHaveCount(5)
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
  await expect(page.locator('button.model-definition-button').filter({ hasText: 'Web 服务' })).toHaveCount(1)
})
test('relation editor pins entity endpoint versions', async ({ page }) => {
  const calls = await setup(page, 'relations'); await expect(page.locator('button.model-definition-button')).toHaveCount(4)
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
  await expect(page.getByRole('dialog', {name:'模型定义'})).not.toBeVisible(); await expect(page.locator('button.model-definition-button')).toHaveCount(5)
  expect(await page.evaluate(() => JSON.stringify({ ...localStorage, ...sessionStorage }))).not.toContain('private draft')
})
test('metric definitions never claim live samples', async ({ page }) => {
  const calls = await setup(page, 'metrics'); await expect(page.getByRole('table', {name:'内置指标列表'}).locator('tbody tr')).toHaveCount(3)
  await page.getByText('默认清洗规则与来源版本说明',{exact:true}).click(); await expect(page.getByText('其他版本：尚未验证。不能仅凭版本号认定兼容。')).toBeVisible()
  expect(calls).toHaveLength(1); expect(calls[0].body).toBeNull()
})

for (const width of [1440, 1024, 390]) test('full metric key opens exact authorized definition at ' + width, async ({ page }) => {
  await page.setViewportSize({ width, height: 1000 }); const calls = await setup(page, 'metrics')
  for (const dark of [false, true]) {
    if (dark) await page.getByRole('button', { name: '切换到深色模式', exact: true }).click()
    await page.getByRole('link', { name: 'host.cpu.usage.user', exact: true }).click()
    await expect(page).toHaveURL(/metricKey=host.cpu.usage.user$/)
    const detail = page.getByRole('region', { name: '指标定义 host.cpu.usage.user', exact: true })
    await expect(detail).toBeVisible()
    await expect(detail).toContainText('system.cpu.util[,user]')
    await expect(detail).toContainText('zabbix-cpu-user@1.0.0')
    await expect(detail).toContainText('builtin.host')
    await expect(detail).toContainText('percent-to-ratio')
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    await page.getByRole('link', { name: '返回指标目录' }).click()
    await expect(page.getByRole('table', {name:'内置指标列表'}).locator('tbody tr')).toHaveCount(3)
  }
  expect(calls).toHaveLength(1)
  await page.getByRole('textbox', { name: '搜索指标定义' }).fill('host.memory.available.ratio')
  await expect(page.getByRole('table', {name:'内置指标列表'}).locator('tbody tr')).toHaveCount(1)
  await page.getByRole('link', { name: 'host.memory.available.ratio', exact: true }).click()
  await expect(page.getByRole('region', { name: '指标定义 host.memory.available.ratio', exact: true })).toContainText('vm.memory.size[pavailable]')
  await page.getByRole('textbox', { name: '平台开发 Token（仅保存在当前标签页内存）' }).fill('changed-token-long-enough-123456789')
  await expect(page.getByRole('region', { name: '指标定义 host.memory.available.ratio', exact: true })).not.toBeVisible()
  await expect.poll(() => calls.length).toBe(2)
})

test('metric definition deep link reads only metadata and preserves missing keys', async ({ page }) => {
  const calls: string[] = []
  await page.route('**/api/v1/catalog', async route => { calls.push(route.request().method()); await route.fulfill({ json: { schemaVersion: '1.0', storage: 'memory', package: bundle, published: { items: [], truncated: false }, drafts: { items: [], truncated: false } } }) })
  await page.goto('/#/modeling/metrics?metricKey=host.system.uptime')
  await page.getByRole('textbox', { name: '平台开发 Token（仅保存在当前标签页内存）' }).fill(TOKEN_OK)
  await expect(page.getByRole('region', { name: '指标定义 host.system.uptime', exact: true })).toContainText('system.uptime')
  expect(calls).toEqual(['GET'])
  await page.goto('/#/modeling/metrics?metricKey=host.missing.metric')
  await page.getByRole('textbox', { name: '平台开发 Token（仅保存在当前标签页内存）' }).fill(TOKEN_OK)
  await expect(page.getByText('当前授权目录中没有此指标定义，未替换为其他指标。')).toBeVisible()
  await expect(page.getByRole('region', { name: /指标定义 host/ })).toHaveCount(0)
})

test('invalid metric definition link sends no catalog request', async ({ page }) => {
  const calls: string[] = []
  await page.route('**/api/v1/catalog', route => { calls.push('catalog'); return route.abort() })
  await page.goto('/#/modeling/metrics?metricKey=host.cpu.usage.user&metricKey=host.system.uptime')
  await page.getByRole('textbox', { name: '平台开发 Token（仅保存在当前标签页内存）' }).fill(TOKEN_OK)
  await expect(page.getByText('指标定义链接无效，请返回目录后重新选择。')).toBeVisible()
  expect(calls).toHaveLength(0)
})

test('a failed metric definition read requires explicit retry', async ({ page }) => {
 const calls: string[] = []
 await page.route('**/api/v1/catalog', async route => {
  calls.push(route.request().method())
  if (calls.length === 1) await route.fulfill({ status: 503, json: { error: 'unavailable' } })
  else await route.fulfill({ json: { schemaVersion: '1.0', storage: 'memory', package: bundle, published: { items: [], truncated: false }, drafts: { items: [], truncated: false } } })
 })
 await page.goto('/#/modeling/metrics?metricKey=host.system.uptime')
 await page.getByRole('textbox', { name: '平台开发 Token（仅保存在当前标签页内存）' }).fill(TOKEN_OK)
 await expect(page.getByRole('alert')).toContainText('503')
 await page.getByRole('button', { name: '切换到深色模式', exact: true }).click()
 await expect(page.getByRole('region', { name: '指标定义 host.system.uptime', exact: true })).toHaveCount(0)
 expect(calls).toEqual(['GET'])
 await page.getByRole('button', { name: '刷新指标目录', exact: true }).click()
 await expect(page.getByRole('region', { name: '指标定义 host.system.uptime', exact: true })).toContainText('system.uptime')
 expect(calls).toEqual(['GET', 'GET'])
})

test('tabbed metric details return to their catalog and browser history without another read', async ({ page }) => {
 await page.addInitScript(() => localStorage.setItem('opsweave.ui.layout', 'tabs'))
 const calls = await setup(page, 'metrics')
 await page.getByRole('link', { name: 'host.cpu.usage.user', exact: true }).click()
 await expect(page.getByRole('region', { name: '指标定义 host.cpu.usage.user', exact: true })).toBeVisible()
 await page.getByRole('link', { name: '返回指标目录' }).click()
 await expect(page.getByRole('table', {name:'内置指标列表'}).locator('tbody tr')).toHaveCount(3)
 await page.goBack()
 await expect(page.getByRole('region', { name: '指标定义 host.cpu.usage.user', exact: true })).toBeVisible()
 await page.getByRole('link', { name: '内置指标', exact: true }).click()
 await expect(page.getByRole('table', {name:'内置指标列表'}).locator('tbody tr')).toHaveCount(3)
 expect(calls).toHaveLength(1)
 await expect(page.getByRole('tablist', { name: '已打开页面' }).getByRole('tab')).toHaveCount(1)
})


test('review failure does not publish or retry until explicitly requested',async({page})=>{
 const calls=await setup(page);let checks=0
 await page.route('**/api/v1/catalog/revisions/review',async route=>{checks++;if(checks===1)return route.fulfill({status:503,json:{error:'CATALOG_UNAVAILABLE'}});await route.fallback()})
 await page.getByRole('button',{name:'新建实体类型',exact:true}).click();const dialog=page.getByRole('dialog',{name:'模型定义'})
 await dialog.getByRole('textbox',{name:'类型标识',exact:true}).fill('custom.review_fixture');await dialog.getByRole('textbox',{name:'显示名称',exact:true}).fill('Fixture review')
 await dialog.getByRole('button',{name:'保存私有草稿',exact:true}).click();await expect(dialog.getByRole('region',{name:'模型发布检查'}).getByRole('alert')).toContainText('503');await expect(dialog.getByRole('button',{name:'发布模型版本',exact:true})).toBeDisabled();expect(checks).toBe(1)
 await dialog.getByRole('button',{name:'重新检查版本',exact:true}).click();await expect(dialog.getByRole('button',{name:'发布模型版本',exact:true})).toBeEnabled();expect(checks).toBe(2);expect(calls.some(c=>c.path.endsWith('/publish'))).toBe(false)
 await dialog.getByRole('textbox',{name:'显示名称',exact:true}).fill('Changed Fixture');await expect(dialog.getByRole('region',{name:'模型发布检查'})).toHaveCount(0);await expect(dialog.getByRole('button',{name:'发布模型版本',exact:true})).toBeDisabled()
})

test('incompatible review exposes full field name and blocks publication',async({page})=>{
 await setup(page);await page.route('**/api/v1/catalog/revisions/review',route=>{const b=route.request().postDataJSON();return route.fulfill({json:{schemaVersion:'1.0',review:{candidate:{...b.ref,digest:b.digest},editVersion:b.expectedEditVersion,base:{id:b.ref.id,revision:1,digest:'sha256:'+'b'.repeat(64)},reviewedAt:'2026-10-04T07:00:00Z',compatible:false,reasons:['INCOMPATIBLE_CHANGE'],changes:[{fieldId:'name',property:'MAX_LENGTH',before:'255',after:'254',compatible:false}]}}})})
 await page.getByRole('button',{name:'新建实体类型',exact:true}).click();const d=page.getByRole('dialog',{name:'模型定义'});await d.getByRole('textbox',{name:'类型标识',exact:true}).fill('custom.fixture');await d.getByRole('textbox',{name:'显示名称',exact:true}).fill('Fixture');await d.getByRole('spinbutton',{name:'发布版本',exact:true}).fill('2');await d.getByRole('button',{name:'保存私有草稿',exact:true}).click()
 await expect(d.getByRole('table',{name:'模型字段差异'})).toContainText('custom.fixture.name');await expect(d.getByRole('table',{name:'模型字段差异'})).toContainText('254');await expect(d.getByRole('button',{name:'发布模型版本',exact:true})).toBeDisabled()
})

for(const width of [1440,390])test('exact definition field link and references stay bounded at '+width,async({page})=>{
 await page.setViewportSize({width,height:1000});const calls=await setup(page,'entities?definition=builtin.host&revision=1&field=hostname');const d=page.getByRole('dialog',{name:'模型定义'});await expect(d).toBeVisible();await expect(d.locator('[data-model-field=hostname]')).toHaveAttribute('data-selected','true');await expect(d.locator('[data-model-field=hostname] code')).toHaveText('builtin.host.hostname');expect(calls).toHaveLength(1)
 await d.getByText('固定版本引用',{exact:true}).click();await expect(d.getByRole('table',{name:'模型引用列表'})).toBeVisible();expect(calls.filter(c=>c.path.endsWith('/references'))).toHaveLength(1)
 await d.getByText('固定版本引用',{exact:true}).click();await d.getByText('固定版本引用',{exact:true}).click();expect(calls.filter(c=>c.path.endsWith('/references'))).toHaveLength(1);expect(calls.some(c=>c.body!==null)).toBe(false);expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true)
})

test('reference read failure requires explicit retry and closing does not leave loading locked',async({page})=>{
 await setup(page);let count=0;await page.route('**/api/v1/catalog/versions/builtin.host/1/references',route=>{count++;return count===1?route.fulfill({status:503,json:{error:'REFERENCES_UNAVAILABLE'}}):route.fallback()});await page.getByRole('button',{name:/内置 主机/}).click();const d=page.getByRole('dialog',{name:'模型定义'});await d.getByText('固定版本引用',{exact:true}).click();await expect(d.locator('.model-reference-section').getByRole('alert')).toContainText('503');await d.getByText('固定版本引用',{exact:true}).click();await d.getByText('固定版本引用',{exact:true}).click();expect(count).toBe(1);await d.getByRole('button',{name:'刷新模型引用',exact:true}).click();await expect(d.getByRole('table',{name:'模型引用列表'})).toBeVisible();expect(count).toBe(2)
})


test('reference field link opens exact full definition and metadata task summary',async({page})=>{
 const calls=await setup(page);await page.route('**/api/v1/catalog/versions/builtin.host/1/references',route=>route.fulfill({json:{schemaVersion:'1.0',report:{target:{id:'builtin.host',revision:1,digest:'sha256:'+'a'.repeat(64)},inspectedAt:'2026-10-04T07:00:00Z',workflowsAvailable:true,references:{items:[{kind:'WORKFLOW',id:'fixture-host',revision:2,digest:'sha256:'+'b'.repeat(64),label:'Fixture Host workflow',state:'PUBLISHED',editVersion:0,roles:['OUTPUT'],fieldIds:['hostname'],tasks:[{kind:'HOST_SCAN',state:'STOPPED',generation:2}]}],truncated:false}}}}))
 await page.getByRole('button',{name:/内置 主机/}).click();const d=page.getByRole('dialog',{name:'模型定义'});await d.getByText('固定版本引用',{exact:true}).click();const table=d.getByRole('table',{name:'模型引用列表'});await expect(table).toContainText('已停止');await expect(table.getByRole('link',{name:'Fixture Host workflow'})).toHaveAttribute('href','#/integrations/workflows?id=fixture-host&revision=2&state=PUBLISHED');await table.getByRole('link',{name:'builtin.host.hostname',exact:true}).click();await expect(d.locator('[data-model-field=hostname]')).toHaveAttribute('data-selected','true');expect(calls.filter(c=>c.path==='/api/v1/catalog')).toHaveLength(1)
})

test('custom definition absent from first directory page loads only its explicit published version',async({page})=>{
 await setup(page);let exact=0;const definition={...bundle.definitions.find((d:any)=>d.id==='builtin.host'),id:'custom.fixture_exact',revision:7,label:'Fixture exact version'}
 await page.route('**/api/v1/catalog/versions/custom.fixture_exact/7',route=>{exact++;return route.fulfill({json:{definition,state:'PUBLISHED',editVersion:0,digest:'sha256:'+'a'.repeat(64),updatedAt:'2026-10-04T07:00:00Z'}})})
 await page.goto('/#/modeling/entities?definition=custom.fixture_exact&revision=7&field=hostname');const d=page.getByRole('dialog',{name:'模型定义'});await expect(d).toBeVisible();await expect(d.getByRole('spinbutton',{name:'发布版本',exact:true})).toHaveValue('7');await expect(d.locator('[data-model-field=hostname] code')).toHaveText('custom.fixture_exact.hostname');expect(exact).toBe(1)
})


test('contradictory compatible decision fails closed and cannot enable publication',async({page})=>{
 await setup(page);await page.route('**/api/v1/catalog/revisions/review',route=>{const b=route.request().postDataJSON();return route.fulfill({json:{schemaVersion:'1.0',review:{candidate:{...b.ref,digest:b.digest},editVersion:b.expectedEditVersion,base:null,reviewedAt:'2026-10-04T07:00:00Z',compatible:true,reasons:[],changes:[{fieldId:'name',property:'MAX_LENGTH',before:'255',after:'254',compatible:false}]}}})});await page.getByRole('button',{name:'新建实体类型',exact:true}).click();const d=page.getByRole('dialog',{name:'模型定义'});await d.getByRole('textbox',{name:'类型标识',exact:true}).fill('custom.contradictory_fixture');await d.getByRole('textbox',{name:'显示名称',exact:true}).fill('Fixture');await d.getByRole('button',{name:'保存私有草稿',exact:true}).click();await expect(d.getByRole('region',{name:'模型发布检查'}).getByRole('alert')).toContainText('不符合契约');await expect(d.getByRole('button',{name:'发布模型版本',exact:true})).toBeDisabled()
})


test('tabbed fixed workflow jump closes the model drawer and leaves the destination usable',async({page})=>{
 await page.addInitScript(()=>localStorage.setItem('opsweave.ui.layout','tabs'));await setup(page);await page.route('**/api/v1/catalog/versions/builtin.host/1/references',route=>route.fulfill({json:{schemaVersion:'1.0',report:{target:{id:'builtin.host',revision:1,digest:'sha256:'+'a'.repeat(64)},inspectedAt:'2026-10-04T07:00:00Z',workflowsAvailable:true,references:{items:[{kind:'WORKFLOW',id:'fixture-host',revision:2,digest:'sha256:'+'b'.repeat(64),label:'Fixture workflow jump',state:'PUBLISHED',editVersion:0,roles:['OUTPUT'],fieldIds:['hostname'],tasks:[]}],truncated:false}}}}));await page.route('**/api/v1/integrations/workflows',route=>route.fulfill({status:503,json:{error:'Fixture unavailable'}}));await page.getByRole('button',{name:/内置 主机/}).click();const d=page.getByRole('dialog',{name:'模型定义'});await d.getByText('固定版本引用',{exact:true}).click();await d.getByRole('link',{name:'Fixture workflow jump',exact:true}).click();await expect(page).toHaveURL(/id=fixture-host&revision=2&state=PUBLISHED$/);await expect(d).not.toBeVisible();await expect(page.getByRole('heading',{name:'数据工作流',exact:true})).toBeVisible();await expect(page.locator('dialog[open]')).toHaveCount(0)
})

test('off-directory relation endpoint remains its complete fixed version',async({page})=>{
 await setup(page,'relations');const relation={schemaVersion:'1.0',id:'custom.fixture_offpage',revision:2,kind:'RELATION',label:'Fixture relation exact',description:'Fixture only',cleaningProfile:'safe-scalars-v1',fields:[],endpoints:{from:{id:'custom.fixture_endpoint',revision:7},to:{id:'builtin.host',revision:1},cardinality:'MANY_TO_MANY'}};await page.route('**/api/v1/catalog/versions/custom.fixture_offpage/2',route=>route.fulfill({json:{definition:relation,state:'PUBLISHED',editVersion:0,digest:'sha256:'+'a'.repeat(64),updatedAt:'2026-10-04T07:00:00Z'}}));await page.goto('/#/modeling/relations?definition=custom.fixture_offpage&revision=2');const d=page.getByRole('dialog',{name:'模型定义'});await expect(d).toBeVisible();await expect(d.getByRole('combobox',{name:'起点实体类型',exact:true})).toHaveValue('custom.fixture_endpoint@7');await expect(d.getByRole('combobox',{name:'起点实体类型',exact:true}).locator('option:checked')).toContainText('custom.fixture_endpoint@7');await expect(d.getByRole('combobox',{name:'终点实体类型',exact:true})).toHaveValue('builtin.host@1')
})


test('explicit additive next version reviews old fixed references before publication',async({page})=>{
 const calls=await setup(page);await page.getByRole('button',{name:'新建实体类型',exact:true}).click();const d=page.getByRole('dialog',{name:'模型定义'});await d.getByRole('textbox',{name:'类型标识',exact:true}).fill('custom.additive_fixture');await d.getByRole('textbox',{name:'显示名称',exact:true}).fill('Fixture additive');await d.getByRole('button',{name:'保存私有草稿',exact:true}).click();await expect(d.getByRole('button',{name:'发布模型版本',exact:true})).toBeEnabled();await d.getByRole('button',{name:'发布模型版本',exact:true}).click();await expect(d.getByRole('button',{name:'创建下一版本',exact:true})).toBeVisible();await d.getByRole('button',{name:'创建下一版本',exact:true}).click();await d.getByRole('button',{name:'添加字段'}).click();await d.getByRole('textbox',{name:'字段标识 2',exact:true}).fill('owner');await d.getByRole('textbox',{name:'字段名称 2',exact:true}).fill('Owner');await d.getByRole('button',{name:'保存私有草稿',exact:true}).click();await expect(d.getByRole('button',{name:'发布模型版本',exact:true})).toBeEnabled();await expect(d.getByRole('table',{name:'模型字段差异'})).toContainText('custom.additive_fixture.owner');await expect(d.getByRole('region',{name:'模型发布检查'})).toContainText('v1 → v2');expect(calls.filter(c=>c.path.endsWith('/references'))).toHaveLength(1);expect(calls.filter(c=>c.path.endsWith('/publish'))).toHaveLength(1)
})
