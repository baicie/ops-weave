import { WORKFLOW_OPERATORS } from './helpers.ts'
import { test, expect, type Page } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { createHash } from 'node:crypto'
import { TOKEN_OK, closeWorkflowInspector, mockEmptySourceInstances } from './helpers.ts'
test.beforeEach(async ({ page }) => { await mockEmptySourceInstances(page) })
const hash='sha256:'+'a'.repeat(64)
const bundle=JSON.parse(readFileSync(new URL('../../../contracts/catalog/opsweave-core-1.0.0.json',import.meta.url),'utf8'))
const models=bundle.definitions.filter((m:any)=>m.kind==='ENTITY').map((definition:any)=>({definition,digest:hash}))
function wireDigest(parts:string[]) { const h=createHash('sha256'); for(const part of parts){const b=Buffer.from(part);h.update(`${b.byteLength}:`);h.update(b)} return 'sha256:'+h.digest('hex') }
test('browser history suspends a cached source drawer and restores its unsaved fields', async ({ page }) => {
 const calls = await setup(page)
 await page.getByRole('button', { name: '布局设置', exact: true }).click()
 await page.getByRole('radio', { name: '多页签布局 多个页面同时打开，切换时保留编辑' }).check()
 await page.getByRole('button', { name: '完成', exact: true }).click()
 const nav = page.getByRole('navigation', { name: '产品模块' })
 await nav.getByRole('button', { name: '资源观测', exact: true }).click()
 await nav.getByRole('link', { name: '资产', exact: true }).click()
 await nav.getByRole('link', { name: '数据源中心', exact: true }).click()
 await page.getByRole('button', { name: '配置手工样本 →', exact: true }).click()
 await page.getByRole('textbox', { name: '接入名称', exact: true }).fill('Fixture cached drawer')
 await page.goBack()
 await expect(page.getByRole('main')).toHaveAttribute('data-route', 'inventory')
 await expect(page.locator('dialog[aria-label="数据源配置"]')).not.toHaveAttribute('open', '')
 await nav.getByRole('link', { name: '数据源中心', exact: true }).click()
 await expect(page.getByRole('dialog', { name: '数据源配置' })).toBeVisible()
 await expect(page.getByRole('textbox', { name: '接入名称', exact: true })).toHaveValue('Fixture cached drawer')
 expect(calls).toHaveLength(1)
 expect(calls[0].path.endsWith('/sources')).toBe(true)
 await page.getByRole('button', { name: '关闭数据源配置' }).click()
})
async function setup(page:Page,read=true){
 const calls:{path:string;body:any}[]=[];let receipt:any=null;const sourcePage={schemaVersion:'1.0',storage:'memory',types:[{id:'ZABBIX_HOST',status:'AVAILABLE',connection:{instanceId:'zabbix-fixture',digest:hash,dataMode:'fixture',endpoint:null,credentialRef:null}},{id:'MANUAL_SAMPLE',status:'AVAILABLE',connection:{instanceId:'manual',digest:hash,dataMode:'MANUAL_SAMPLE',endpoint:null,credentialRef:null}},{id:'CMDB_SNAPSHOT',status:'LEGACY_IMPORT',connection:null}],models,modelsTruncated:false,setups:{items:[],truncated:false}}
 await page.route(/\/api\/v1\/integrations\/(sources|workflows|zabbix\/connection-checks)(?:\/|$)/,async route=>{const path=new URL(route.request().url()).pathname,body=route.request().postDataJSON();calls.push({path,body});let result:any
 if(path.endsWith('/sources'))result={...sourcePage,setups:{items:receipt?[receipt.setup]:[],truncated:false}}
 else if(path.endsWith('/confirm')){receipt={setup:{id:body.requestId,name:body.name,description:body.description,source:body.source,connectionDigest:body.connectionDigest,dataMode:body.source.kind==='MANUAL_SAMPLE'?'MANUAL_SAMPLE':'fixture',initialTarget:null,digest:hash,createdAt:new Date().toISOString(),workflowId:'source-'+body.requestId},workflow:null};result=receipt}
 else if(path.endsWith('/drafts')&&body){receipt.workflow={definition:body.definition,layout:body.layout,digest:hash,state:'DRAFT',editVersion:body.expectedEditVersion+1,updatedAt:new Date().toISOString(),preview:null};result=receipt.workflow}
 else if(path.endsWith('/workflows'))result={schemaVersion:'2.0',storage:'memory',operatorCatalog:WORKFLOW_OPERATORS,drafts:{items:receipt?.workflow?[receipt.workflow]:[],truncated:false},published:{items:[],truncated:false},models,modelsTruncated:false,zabbixSource:{instanceId:'zabbix-fixture',mode:'fixture'},runs:{items:[],truncated:false}}
 else if(path.includes('/workflows/'))result=receipt?.workflow
 else if(path.endsWith('/connection-checks'))result={schemaVersion:'1.0',storage:'memory',dataMode:'connection-check',tenantId:'fixture',sourceInstanceId:'zabbix-fixture',check:{checkId:'10000000-0000-4000-8000-000000000001',sourceInstanceId:'zabbix-fixture',actor:'fixture',checkedAt:new Date().toISOString(),dataMode:'labeled-fixture',reachable:true,statusCode:'labeled-fixture',reportedVersion:null}}
 else if(path.endsWith('/continuation'))result={schemaVersion:'1.0',setupId:receipt.setup.id,workflow:receipt.workflow}
 else result=receipt
 await route.fulfill({contentType:'application/json',body:JSON.stringify(result)})})
 await page.goto('/#/integrations/sources');if(read){await page.getByRole('textbox',{name:'平台开发 Token（仅保存在当前标签页内存）'}).fill(TOKEN_OK);await expect(page.locator('.source-center .integration-list-foot')).toContainText('当前列表 0');}await page.getByRole('tab',{name:'接入类型',exact:true}).click();await expect(page.getByRole('button',{name:'配置手工样本 →',exact:true})).toBeEnabled();return calls
}
for(const width of [1500,390])test('source selection fixture drawer persists and opens the exact canvas '+width,async({page})=>{
 await page.setViewportSize({width,height:1000});const errors:string[]=[];page.on('pageerror',e=>errors.push(e.message));const calls=await setup(page)
 await page.getByRole('button',{name:'配置手工样本 →',exact:true}).click();const drawer=page.getByRole('dialog',{name:'数据源配置'});await expect(drawer).toBeVisible();await expect(drawer.getByRole('combobox')).toHaveCount(0);await page.getByRole('textbox',{name:'接入名称',exact:true}).fill('Fixture source choice');await page.getByRole('button',{name:'保存并配置流程',exact:true}).click();await expect(page.getByRole('group',{name:'选择数据输出'})).toHaveCount(0);await expect(page.locator('[data-engine=x6]')).toHaveAttribute('data-graph-status','ready');await expect(page.getByRole('textbox',{name:'工作流名称',exact:true})).toHaveValue('Fixture source choice');await expect(page.locator('.workflow-node')).toHaveCount(5);expect(calls.filter(c=>c.body)).toHaveLength(1);expect(calls[1].body.source).toEqual({kind:'MANUAL_SAMPLE',instanceId:'manual'});expect(calls[1].body).not.toHaveProperty('tenantId');expect(calls[1].body).not.toHaveProperty('target');await page.getByRole('button',{name:'保存草稿',exact:true}).click();await expect(page.getByText('草稿已保存；尚未发布或启用采集。',{exact:true})).toBeVisible();expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true)
 if(width===390)await page.getByRole('button',{name:'切换导航',exact:true}).click();await page.getByRole('navigation',{name:'产品模块'}).getByRole('link',{name:'数据源中心',exact:true}).click();await page.getByRole('tab',{name:'接入回执',exact:true}).click();await expect(page.locator('.source-saved-row')).toContainText('Fixture source choice');await page.getByRole('button',{name:'查看配置',exact:true}).click();await expect(page.getByRole('textbox',{name:'接入名称',exact:true})).toBeDisabled();await page.getByRole('button',{name:'继续编排',exact:true}).click();await expect(page.getByRole('textbox',{name:'工作流名称',exact:true})).toHaveValue('Fixture source choice');expect(errors).toEqual([])
})
test('Zabbix source opens a local canvas directly without saving or executing a workflow', async ({ page }) => {
 const calls = await setup(page)
 await page.getByRole('button', { name: '配置 Zabbix →', exact: true }).click()
 await page.getByRole('button', { name: '保存并配置流程', exact: true }).click()
 await expect(page.getByRole('group', { name: '选择数据输出' })).toHaveCount(0)
 await expect(page.locator('[data-engine=x6]')).toHaveAttribute('data-graph-status', 'ready')
 await expect(page.locator('.workflow-node')).toHaveCount(5)
 await expect(page.locator('.studio-source-mode')).toHaveText('Fixture 合成数据')
 await expect(page.getByRole('textbox', { name: '工作流名称', exact: true })).toHaveValue('Zabbix 主机接入')
 await page.getByRole('button', { name: '更多工作流操作', exact: true }).click();await page.getByRole('button', { name: '输出配置', exact: true }).click()
 await expect(page.getByRole('combobox', { name: '输出类型', exact: true })).toHaveCount(0)
 await expect(page.getByRole('combobox', { name: '实体模型', exact: true })).toHaveValue('builtin.host@1')
 await closeWorkflowInspector(page)
 if (await page.getByRole('button', { name: '节点库', exact: true }).getAttribute('aria-expanded') === 'false') await page.getByRole('button', { name: '节点库', exact: true }).click()
 for (const name of ['日志输出', '指标输出']) await expect(page.getByRole('button', { name, exact: true })).toBeDisabled()
 expect(calls.filter(c => c.body).map(c => c.path)).toEqual(['/api/v1/integrations/sources/confirm'])
})
test('Zabbix probe is explicit, fixture marked, and CMDB remains legacy',async({page})=>{const calls=await setup(page);expect(calls).toHaveLength(1);await expect(page.getByRole('link',{name:'前往快照导入 ↗'})).toHaveAttribute('href','#/integrations/cmdb');await page.getByRole('button',{name:'配置 Zabbix →'}).click();await expect(page.getByRole('dialog')).toContainText('暂不支持多实例新增');await expect(page.getByRole('dialog').getByRole('combobox')).toHaveCount(0);await page.getByRole('button',{name:'测试连接',exact:true}).click();await expect(page.getByText('Fixture 自检 · 非真实连接',{exact:true})).toBeVisible();expect(calls.filter(c=>c.path.endsWith('/connection-checks'))).toHaveLength(1);await page.keyboard.press('Escape');await expect(page.getByRole('dialog')).not.toBeVisible();await expect(page.getByRole('button',{name:'配置 Zabbix →'})).toBeFocused()})
test('failed confirmation keeps the same request for explicit retry',async({page})=>{await setup(page);let attempts:any[]=[];await page.route('**/sources/confirm',async r=>{attempts.push(r.request().postDataJSON());await r.fulfill({status:503,contentType:'application/json',body:'{"error":"UNAVAILABLE"}'})});await page.getByRole('button',{name:'配置手工样本 →'}).click();await page.getByRole('button',{name:'保存并配置流程'}).click();await expect(page.getByRole('button',{name:'按原配置重试'})).toBeEnabled();await expect(page.getByRole('textbox',{name:'接入名称',exact:true})).toBeDisabled();await page.getByRole('button',{name:'按原配置重试'}).click();await expect(page.getByRole('button',{name:'查询确认结果'})).toBeEnabled();expect(attempts).toHaveLength(2);expect(attempts[0]).toEqual(attempts[1]);expect(await page.evaluate(()=>JSON.stringify({...localStorage,...sessionStorage}))).not.toContain(attempts[0].requestId)})

for (const mismatch of [false, true]) test('unknown source confirmation ' + (mismatch ? 'rejects a mismatched receipt' : 'resolves without another write or a stale close guard'), async ({ page }) => {
  await setup(page)
  let command: any, writes = 0, lookups = 0, continuations = 0
  await page.route('**/sources/confirm', async route => {
    writes++; command = route.request().postDataJSON()
    await route.fulfill({ status: 503, json: { error: 'UNAVAILABLE' } })
  })
  await page.route('**/sources/**', async route => {
    if (route.request().method() !== 'GET') { await route.fallback(); return }
    const path = new URL(route.request().url()).pathname
    if (path.endsWith('/continuation')) {
      continuations++; await route.fulfill({ json: { schemaVersion: '1.0', setupId: command.requestId, workflow: null } }); return
    }
    lookups++
    await route.fulfill({ json: { setup: { id: command.requestId, name: mismatch ? 'Fixture different request' : command.name, description: command.description, source: command.source, connectionDigest: command.connectionDigest, dataMode: 'MANUAL_SAMPLE', initialTarget: null, digest: hash, createdAt: '2026-10-03T00:00:00Z', workflowId: 'source-' + command.requestId }, workflow: null } })
  })
  await page.getByRole('button', { name: '配置手工样本 →', exact: true }).click()
  await page.getByRole('button', { name: '保存并配置流程', exact: true }).click()
  await page.getByRole('button', { name: '查询确认结果', exact: true }).click()
  if (mismatch) {
    const drawer = page.getByRole('dialog', { name: '数据源配置' })
    await expect(drawer.getByRole('alert')).toContainText('不符合契约')
    await expect(drawer.getByRole('button', { name: '按原配置重试', exact: true })).toBeEnabled()
    await expect(drawer.getByRole('textbox', { name: '接入名称', exact: true })).toBeDisabled()
    expect(lookups).toBe(1); expect(continuations).toBe(0)
  } else {
    await expect(page.locator('[data-engine=x6]')).toHaveAttribute('data-graph-status', 'ready')
    await page.getByRole('button', { name: '关闭数据源中心页面', exact: true }).click()
    await expect(page.getByRole('tablist', { name: '已打开页面' }).getByRole('tab', { name: '数据源中心', exact: true })).toHaveCount(0)
    await expect(page.getByRole('dialog', { name: '关闭页面前确认' })).not.toBeVisible()
    expect(lookups).toBe(2); expect(continuations).toBe(1)
  }
  expect(writes).toBe(1)
})
test('session change removes saved configuration and closes the private drawer',async({page})=>{await setup(page);await page.getByRole('button',{name:'配置手工样本 →'}).click();await page.getByRole('textbox',{name:'接入名称',exact:true}).fill('Private fixture source');await page.keyboard.press('Escape');await page.getByRole('textbox',{name:'平台开发 Token（仅保存在当前标签页内存）'}).fill('another-token-long-enough-123456789');await expect(page.getByRole('tab',{name:/已配置接入/})).toHaveAttribute('aria-selected','true');await page.getByRole('tab',{name:'接入类型',exact:true}).click();await expect(page.getByRole('button',{name:'配置手工样本 →'})).toBeEnabled();await expect(page.getByRole('dialog')).not.toBeVisible();expect(await page.getByRole('textbox',{name:'接入名称',exact:true,includeHidden:true}).inputValue()).toBe('')})
test('invalid canvas deep link cannot trigger a workflow request',async({page})=>{const calls=await setup(page);await page.evaluate(()=>location.hash='#/integrations/workflows?id=../../escape&revision=1&state=DRAFT');await expect(page.getByRole('alert').filter({hasText:'工作流地址参数无效'})).toBeVisible();expect(calls.filter(c=>c.path.includes('/workflows'))).toHaveLength(0)})

test('authorization reads sources once before choosing a type without a separate read click',async({page})=>{const calls=await setup(page,false);expect(calls).toHaveLength(0);await page.getByRole('textbox',{name:'平台开发 Token（仅保存在当前标签页内存）'}).fill(TOKEN_OK);await expect(page.locator('.source-center .integration-list-foot')).toContainText('当前列表 0');await page.getByRole('tab',{name:'接入类型',exact:true}).click();await page.getByRole('button',{name:'配置手工样本 →',exact:true}).click();await expect(page.getByRole('dialog',{name:'数据源配置'})).toBeVisible();await expect(page.getByRole('dialog').getByRole('combobox')).toHaveCount(0);expect(calls.map(c=>c.path)).toEqual(['/api/v1/integrations/sources']);expect(calls.every(c=>c.body===null)).toBe(true)})
test('missing session explains the blocked action without sending any request',async({page})=>{const calls=await setup(page,false);const token=page.getByRole('textbox',{name:'平台开发 Token（仅保存在当前标签页内存）'});await token.fill('');await expect(page.locator('.source-access-hint')).toContainText('尚未建立开发会话');await page.getByRole('button',{name:'配置 Zabbix →',exact:true}).click();await expect(page.getByRole('alert').filter({hasText:'请先在页面顶部填写平台开发 Token'})).toBeVisible();await expect(page.getByRole('dialog',{name:'数据源配置'})).not.toBeVisible();expect(calls).toHaveLength(0);await expect(page.getByRole('button',{name:'读取数据源',exact:true})).toBeDisabled();expect(calls).toHaveLength(0)})

for (const width of [1440, 1024, 390]) for (const host of [true, false]) test('source configuration, fields, metrics and guide preserve inputs at ' + width + (host ? ' Zabbix' : ' JSON'), async ({ page }) => {
  await page.setViewportSize({ width, height: 1000 })
  const calls = await setup(page)
  const errors: string[] = []; page.on('pageerror', e => errors.push(e.message))
  const catalogCalls: string[] = []
  const catalog = structuredClone(bundle)
  catalog.metrics[0].label = '<img src=x onerror="fixtureMetricInjection=true">'
  await page.route('**/api/v1/catalog', async route => {
    catalogCalls.push(route.request().method())
    await route.fulfill({ json: { schemaVersion: '1.0', storage: 'memory', package: catalog, published: { items: [], truncated: false }, drafts: { items: [], truncated: false } } })
  })
  const trigger = page.getByRole('button', { name: host ? '配置 Zabbix →' : '配置手工样本 →', exact: true })
  const drawer = page.getByRole('dialog', { name: '数据源配置', exact: true })
  for (const dark of [false, true]) {
    if (dark) await page.getByRole('button', { name: '切换到深色模式', exact: true }).click()
    await trigger.click()
    const name = drawer.getByRole('textbox', { name: '接入名称', exact: true })
    await name.fill('Fixture preserved source')
    await drawer.getByRole('textbox', { name: '接入说明', exact: true }).fill('Fixture configuration description')
    await drawer.getByRole('tab', { name: '接入配置', exact: true }).press('ArrowRight')
    await expect(drawer.getByRole('tab', { name: '数据字段', exact: true })).toBeFocused()
    const fieldTable = drawer.getByRole('region', { name: '数据字段表', exact: true })
    await expect(fieldTable).toContainText(host ? 'entity_id' : 'metricType')
    if (!host) {
      await expect(fieldTable.locator('tbody tr')).toHaveCount(Object.keys(JSON.parse(readFileSync(new URL('../../../contracts/schemas/v2/workflow-metric-record.schema.json', import.meta.url), 'utf8')).properties).length)
      const selected = await drawer.getByRole('button', { name: '指标字段', exact: true }).evaluate(button => getComputedStyle(button).backgroundColor)
      const other = await drawer.getByRole('button', { name: '日志字段', exact: true }).evaluate(button => getComputedStyle(button).backgroundColor)
      expect(selected).not.toBe(other)
      await drawer.getByText('查看指标样本（Fixture）', { exact: true }).click()
      await expect(drawer.locator('.source-sample-reference pre')).toContainText('fixture.cpu.usage')
      await drawer.getByRole('button', { name: '日志字段', exact: true }).click()
      await expect(fieldTable).toContainText('traceId')
      await drawer.getByRole('button', { name: '实体字段', exact: true }).click()
      await expect(fieldTable).toContainText('hostname')
    }
    await drawer.getByRole('textbox', { name: '搜索数据字段', exact: true }).fill('not-a-field')
    await expect(fieldTable).toContainText('没有匹配的字段')
    await drawer.getByRole('button', { name: '清除字段搜索', exact: true }).click()
    await drawer.getByRole('tab', { name: '监控指标', exact: true }).click()
    const metricTable = drawer.getByRole('region', { name: '监控指标表', exact: true })
    await expect(metricTable.locator('tbody tr')).toHaveCount(bundle.metrics.length)
    await expect(metricTable.getByRole('link', { name: bundle.metrics[0].key, exact: true })).toHaveAttribute('href', '#/modeling/metrics?metricKey=' + encodeURIComponent(bundle.metrics[0].key))
    await expect(metricTable).toContainText(catalog.metrics[0].label)
    expect(await page.evaluate(() => Object.hasOwn(window, 'fixtureMetricInjection'))).toBe(false)
    await drawer.getByRole('textbox', { name: '搜索监控指标', exact: true }).fill(bundle.metrics[0].sourceKey)
    await expect(metricTable).toContainText(bundle.metrics[0].sourceKey)
    await drawer.getByRole('textbox', { name: '搜索监控指标', exact: true }).fill('not-a-metric')
    await expect(metricTable).toContainText('没有匹配的指标')
    await drawer.getByRole('tab', { name: '接入教程', exact: true }).click()
    await expect(drawer.locator('.source-guide-steps li')).toHaveCount(4)
    await expect(drawer.locator('.source-guide')).toContainText(host ? '保存接入不会启动采集' : '持续采集和存储写入尚未实现')
    await drawer.getByRole('tab', { name: '监控指标', exact: true }).click()
    await expect(drawer.getByRole('textbox', { name: '搜索监控指标', exact: true })).toHaveValue('not-a-metric')
    await drawer.getByRole('button', { name: '清除指标搜索', exact: true }).click()
    await expect(metricTable.locator('tbody tr')).toHaveCount(bundle.metrics.length)
    expect(catalogCalls).toHaveLength(dark ? 2 : 1)
    await drawer.getByRole('tab', { name: '接入教程', exact: true }).click()
    await drawer.getByRole('tab', { name: '接入教程', exact: true }).press('Home')
    await expect(drawer.getByRole('tab', { name: '接入配置', exact: true })).toBeFocused()
    await expect(name).toHaveValue('Fixture preserved source')
    await expect(drawer.getByRole('textbox', { name: '接入说明', exact: true })).toHaveValue('Fixture configuration description')
    const box = (await drawer.boundingBox())!
    expect(box.x).toBeGreaterThanOrEqual(0); expect(box.x + box.width).toBeLessThanOrEqual(width + 1)
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    await page.keyboard.press('Escape')
    await expect(drawer).not.toBeVisible(); await expect(trigger).toBeFocused()
  }
  expect(catalogCalls).toEqual(['GET', 'GET'])
  expect(calls.filter(call => call.body)).toEqual([])
  expect(errors).toEqual([])
})

test('source metric catalog keeps failures explicit and retries only on a click', async ({ page }) => {
  const calls = await setup(page)
  let reads = 0
  await page.route('**/api/v1/catalog', async route => {
    reads++
    if (reads === 1) await route.fulfill({ status: 503, json: { error: 'UNAVAILABLE' } })
    else await route.fulfill({ json: { schemaVersion: '1.0', storage: 'memory', package: bundle, published: { items: [], truncated: false }, drafts: { items: [], truncated: false } } })
  })
  await page.getByRole('button', { name: '配置 Zabbix →', exact: true }).click()
  const drawer = page.getByRole('dialog', { name: '数据源配置' })
  await drawer.getByRole('tab', { name: '监控指标', exact: true }).click()
  await expect(drawer.getByRole('alert')).toContainText('HTTP 503')
  await expect(drawer.getByRole('region', { name: '监控指标表' })).toHaveCount(0)
  expect(reads).toBe(1)
  await drawer.getByRole('button', { name: '读取指标目录', exact: true }).click()
  await expect(drawer.getByRole('region', { name: '监控指标表' }).locator('tbody tr')).toHaveCount(bundle.metrics.length)
  expect(reads).toBe(2); expect(calls.filter(call => call.body)).toEqual([])
})

test('closing the source drawer cancels a late catalog read across an identity change', async ({ page }) => {
  await setup(page)
  let release = () => {}, settled = () => {}
  const held = new Promise<void>(resolve => { release = resolve })
  const finished = new Promise<void>(resolve => { settled = resolve })
  let reads = 0
  await page.route('**/api/v1/catalog', async route => {
    reads++; await held
    try { await route.fulfill({ json: { schemaVersion: '1.0', storage: 'memory', package: bundle, published: { items: [], truncated: false }, drafts: { items: [], truncated: false } } }) }
    finally { settled() }
  })
  await page.getByRole('button', { name: '配置手工样本 →', exact: true }).click()
  const drawer = page.getByRole('dialog', { name: '数据源配置' })
  const requested = page.waitForRequest('**/api/v1/catalog')
  await drawer.getByRole('tab', { name: '监控指标', exact: true }).click(); await requested
  await page.keyboard.press('Escape'); await expect(drawer).not.toBeVisible()
  await page.getByRole('textbox', { name: '平台开发 Token（仅保存在当前标签页内存）' }).fill('changed-fixture-token-32-characters')
  release(); await finished
  await page.getByRole('tab', { name: '接入类型', exact: true }).click()
  await page.getByRole('button', { name: '配置手工样本 →', exact: true }).click()
  await drawer.getByRole('tab', { name: '监控指标', exact: true }).click()
  await expect(drawer.getByRole('region', { name: '监控指标表' }).locator('tbody tr')).toHaveCount(bundle.metrics.length)
  expect(reads).toBe(2)
})

test('registered source item sync is scoped to the saved revision and shows its receipt history', async ({ page }) => {
  await setup(page)
  const sourceId = '11111111-1111-4111-8111-111111111111'
  const credentialId = '22222222-2222-4222-8222-222222222222'
  const versionId = '33333333-3333-4333-8333-333333333333'
  const endpointAddress = 'http://127.0.0.1:10051/api_jsonrpc.php'
  const endpointDigest = wireDigest(['source-endpoint-v2', 'zabbix-main', 'ZABBIX_HOST', endpointAddress])
  const connectionDigest = wireDigest(['source-connection-v3', sourceId, 'host-jsonrpc-v2', 'zabbix-main', endpointDigest, credentialId, '1', versionId, '1', '1001'])
  const scopeDigest = wireDigest(['registered-item-scan-scope-v1', sourceId, '1', connectionDigest, '1001'])
  const instance = { id: sourceId, name: 'Registered fixture', description: '', source: { kind: 'ZABBIX_HOST', instanceId: 'connection-' + sourceId }, configurationRevision: 1, connectionDigest, dataMode: 'zabbix-jsonrpc', editVersion: 1, state: 'ACTIVE', createdAt: '2026-10-03T00:00:00Z', updatedAt: '2026-10-03T00:00:00Z', workflowId: 'source-' + sourceId }
  const connection = { sourceId, revision: 1, connectorVersion: 'host-jsonrpc-v2', endpoint: { id: 'zabbix-main', name: 'Registered fixture', connectorKind: 'ZABBIX_HOST', address: endpointAddress, digest: endpointDigest }, credentialPin: { credentialId, revision: 1, versionId }, hostGroupIds: ['1001'], connectionDigest, createdAt: '2026-10-03T00:00:00Z' }
  const run = { syncRunId: '44444444-4444-4444-8444-444444444444', sourceId, configurationRevision: 1, connectionDigest, scopeDigest, objectType: 'item', status: 'SUCCEEDED', startedAt: '2026-10-03T00:00:00Z', completedAt: '2026-10-03T00:00:01Z', cursor: null, pages: 2, fetched: 8, accepted: 7, rejected: 1, retired: 0, snapshotComplete: true, dataMode: 'zabbix-jsonrpc', scanConsistency: 'itemid-watermark-snapshot' }
  const calls: { method: string; path: string; body: any }[] = []
  await page.route('**/api/v2/data-sources', async route => { calls.push({ method: route.request().method(), path: new URL(route.request().url()).pathname, body: route.request().postDataJSON() }); await route.fulfill({ json: { schemaVersion: '2.0', storage: 'memory', items: [instance], truncated: false } }) })
  await page.route('**/api/v2/data-sources/**', async route => {
    const request = route.request(), url = new URL(request.url()), path = url.pathname
    calls.push({ method: request.method(), path, body: request.postDataJSON() })
    if (path === `/api/v2/data-sources/${sourceId}`) return route.fulfill({ json: { schemaVersion: '2.0', instance } })
    if (path === `/api/v2/data-sources/${sourceId}/connection`) return route.fulfill({ json: { schemaVersion: '2.0', storage: 'memory', instance, connection, availability: 'AVAILABLE', canConfigure: false } })
    if (path.endsWith('/items/sync')) return route.fulfill({ json: { schemaVersion: '2.0', storage: 'memory', dataMode: 'zabbix-jsonrpc', sourceId, sourceInstanceId: 'connection-' + sourceId, configurationRevision: 1, connectionDigest, hostGroupIds: ['1001'], scopeDigest, pages: 2, fetched: 8, accepted: 7, rejected: 1, retired: 0, snapshotComplete: true, scanConsistency: 'itemid-watermark-snapshot', syncRunId: run.syncRunId } })
    if (path.endsWith(`/items/runs/${run.syncRunId}`)) return route.fulfill({ json: { schemaVersion: '2.0', storage: 'memory', dataMode: 'scan-log', tenantId: 'fixture', sourceId, sourceInstanceId: 'connection-' + sourceId, configurationRevision: 1, connectionDigest, hostGroupIds: ['1001'], run } })
    if (path.endsWith('/items/runs')) return route.fulfill({ json: { schemaVersion: '2.0', storage: 'memory', dataMode: 'scan-log', tenantId: 'fixture', sourceId, sourceInstanceId: 'connection-' + sourceId, configurationRevision: 1, connectionDigest, hostGroupIds: ['1001'], limit: 20, after: null, hasMore: false, nextCursor: null, items: [run] } })
    return route.fulfill({ status: 404, json: { error: 'NOT_FOUND' } })
  })
  await page.getByRole('button', { name: '刷新实例列表', exact: true }).click()
  await page.getByRole('button', { name: '维护实例：Registered fixture', exact: true }).click()
  const drawer = page.getByRole('dialog', { name: '维护接入实例' })
  await drawer.getByRole('tab', { name: '指标同步', exact: true }).click()
  await expect(drawer).toContainText('配置版本')
  await expect(drawer).toContainText(sourceId)
  await expect(drawer.getByRole('button', { name: '同步指标目录', exact: true })).toBeEnabled()
  await drawer.getByRole('button', { name: '同步指标目录', exact: true }).click()
  await expect(drawer).toContainText('本次扫描已完成并记录回执。')
  await expect(drawer).toContainText('接受')
  await expect(drawer).toContainText('7')
  await drawer.getByRole('button', { name: '查看', exact: true }).click()
  await expect(drawer.getByRole('region', { name: '指标扫描详情' })).toContainText(run.syncRunId)
  const sync = calls.find(call => call.path.endsWith('/items/sync'))
  expect(sync?.method).toBe('POST')
  expect(sync?.body).toBeNull()
  expect(sync?.path).toBe(`/api/v2/data-sources/${sourceId}/connection/1/items/sync`)
})
