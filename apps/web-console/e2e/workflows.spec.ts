import { WORKFLOW_OPERATORS } from './helpers.ts'
import { test, expect, type Page } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { TOKEN_OK, closeWorkflowInspector } from './helpers.ts'
const bundle=JSON.parse(readFileSync(new URL('../../../contracts/catalog/opsweave-core-1.0.0.json',import.meta.url),'utf8'))
const hash='sha256:'+'a'.repeat(64)

for (const width of [1440, 1024, 390]) for (const view of ['canvas', 'steps']) test('node click and context menu open the same drawer in ' + view + ' at ' + width, async ({ page }) => {
 await page.setViewportSize({ width, height: 1000 }); const calls = await setup(page)
 const errors: string[] = []; page.on('pageerror', error => errors.push(error.message))
 await page.getByRole('button', { name: '＋ 自定义实体模板', exact: true }).click()
 if (view === 'steps') await page.getByRole('button', { name: '步骤视图', exact: true }).click()
 const inspector = page.getByRole('dialog', { name: '节点配置', exact: true })
 const mapping = page.getByRole('button', { name: '字段映射节点 mapping', exact: true })
 await expect(inspector).not.toBeVisible()
 for (const dark of [false, true]) {
  if (dark) await page.getByRole('button', { name: '切换到深色模式', exact: true }).click()
  await mapping.click({ button: 'right' })
  await expect(page.getByRole('menuitem', { name: '编辑节点', exact: true })).toBeFocused()
  await expect(inspector).not.toBeVisible()
  await page.keyboard.press('Escape')
  await expect(page.getByRole('menu', { name: '节点操作', exact: true })).not.toBeVisible()
  await expect(mapping).toBeFocused()
  await mapping.press('Shift+F10')
  await page.getByRole('menuitem', { name: '编辑节点', exact: true }).press('Enter')
  await expect(inspector).toBeVisible()
  await expect(page.getByRole('button', { name: '关闭节点配置', exact: true })).toBeFocused()
  const field = page.getByRole('textbox', { name: '来源字段 → name', exact: true })
  await field.fill('fixture_context_name')
  await page.keyboard.press('Escape')
  await expect(inspector).not.toBeVisible()
  await expect(mapping).toBeFocused()
  await mapping.click()
  await expect(field).toHaveValue('fixture_context_name')
  const bounds = (await page.locator('.studio-inspector').boundingBox())!
  expect(bounds.x).toBeGreaterThanOrEqual(0)
  expect(bounds.x + bounds.width).toBeCloseTo(width, 0)
  expect(bounds.y).toBe(0)
  expect(bounds.height).toBe(1000)
  await expect(inspector).toContainText('builtin.service.name')
  await closeWorkflowInspector(page)
 }
 expect(calls).toHaveLength(1)
 expect(errors).toEqual([])
 expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
})

for (const width of [1440, 1024, 390]) test('configuration stays out of the canvas footer and retains edits at ' + width, async ({ page }) => {
 await page.setViewportSize({ width, height: 1000 }); const calls = await setup(page)
 await page.getByRole('button', { name: '＋ 自定义实体模板', exact: true }).click()
 const inspector = page.locator('.studio-inspector')
 await expect(inspector).not.toBeVisible()
 await expect(page.locator('.studio-heading-actions')).not.toBeVisible()
 await expect(page.locator('.studio-process-tools > button')).toHaveCount(2)
 await expect(page.getByRole('button', { name: '节点配置', exact: true })).not.toBeVisible()
 await page.getByRole('button', { name: '更多工作流操作', exact: true }).click()
 await expect(page.getByRole('button', { name: '放弃本地修改', exact: true })).toBeVisible()
 await page.keyboard.press('Escape')
 await expect(page.getByRole('button', { name: '更多工作流操作', exact: true })).toBeFocused()
 await page.getByRole('button', { name: '字段映射节点 mapping', exact: true }).click()
 await expect(inspector).toBeVisible()
 await page.getByRole('textbox', { name: '来源字段 → name', exact: true }).fill('fixture_service_name')
 {
  await page.keyboard.press('Escape')
  await expect(inspector).not.toBeVisible()
  await expect(page.getByRole('button', { name: '字段映射节点 mapping', exact: true })).toBeFocused()
  await page.getByRole('button', { name: '更多工作流操作', exact: true }).click();await page.getByRole('button', { name: '节点配置', exact: true }).click()
  await expect(page.getByRole('textbox', { name: '来源字段 → name', exact: true })).toHaveValue('fixture_service_name')
  await page.getByRole('button', { name: '关闭节点配置', exact: true }).click()
 }
 await page.getByRole('button', { name: '连接与分支 4', exact: true }).click()
 await expect(page.getByRole('dialog', { name: '连接管理', exact: true })).toBeVisible()
 await page.keyboard.press('Escape')
 await expect(page.getByRole('dialog', { name: '连接管理', exact: true })).not.toBeVisible()
 await expect(page.getByRole('button', { name: '连接与分支 4', exact: true })).toBeFocused()
 await page.getByRole('button', { name: '数据输入节点 source', exact: true }).click()
 await page.getByRole('button', { name: '填写测试数据', exact: true }).click()
 await page.getByRole('textbox', { name: '工作流手工样本' }).fill('[{"fixture_service_name":"Fixture retained"}]')
 await page.getByText('测试数据与结果', { exact: true }).click()
 await page.getByText('测试数据与结果', { exact: true }).click()
 await expect(page.getByRole('textbox', { name: '工作流手工样本' })).toHaveValue('[{"fixture_service_name":"Fixture retained"}]')
 await page.setViewportSize({ width: width <= 760 ? 1440 : 390, height: 1000 })
 await page.getByRole('button', { name: '字段映射节点 mapping', exact: true }).click()
 await expect(page.getByRole('textbox', { name: '来源字段 → name', exact: true })).toHaveValue('fixture_service_name')
 await expect(page.getByRole('button', { name: '保存草稿', exact: true })).toBeEnabled()
 expect(calls).toHaveLength(1)
 expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
})
async function setup(page:Page,delegated?:{available:boolean;invalid?:Record<string,unknown>}){
 const calls:{path:string;body:any}[]=[];let saved:any;let published:any;const runs:any[]=[];const executions:any[]=[];let task:any=null;const controls=new Map<string,any>()
 await page.route(/\/api\/v1\/integrations\/workflows(?:\/|$)/,async route=>{
  const path=new URL(route.request().url()).pathname,body=route.request().postDataJSON();calls.push({path,body});let response:any
  if(path.endsWith('/workflows'))response={schemaVersion:'2.0',storage:'memory',operatorCatalog:WORKFLOW_OPERATORS,drafts:{items:saved?[saved]:[],truncated:false},published:{items:published?[published]:[],truncated:false},models:bundle.definitions.filter((m:any)=>m.kind==='ENTITY').map((definition:any)=>({definition,digest:hash})),modelsTruncated:false,zabbixSource:{instanceId:'zabbix-fixture',mode:'fixture'},runs:{items:runs,truncated:false}}
  else if(path.endsWith('/runtime'))response={schemaVersion:'2.0',mode:delegated?'DELEGATED_ENTITY':'LOCAL_DEV_ENTITY',...(delegated?{backgroundAvailable:delegated.available}:{}),pollSeconds:5,maxBatchRecords:5,tasks:task?[task]:[],executions}
  else if(path.includes('/runtime/commands/')){response=controls.get(path.split('/').at(-1)!);if(!response)return route.fulfill({status:404,json:{code:'NOT_FOUND'}})}
  else if(path.endsWith('/runtime/execute')){response=executions.find(x=>x.id===body.previewId)??{id:body.previewId,workflowId:body.id,revision:body.revision,digest:body.digest,settings:body.settings,origin:published.definition.source.kind==='MANUAL_SAMPLE'?'MANUAL_SAMPLE':'fixture',syncRunId:body.syncRunId??null,createdAt:new Date().toISOString(),state:'SUCCEEDED',accepted:1,rejected:0,filtered:0,entityIds:['10000000-0000-4000-8000-000000000099'],error:null};if(!executions.some(x=>x.id===response.id))executions.unshift(response)}
  else if(path.endsWith('/runtime/start')||path.endsWith('/runtime/stop')){task={workflowId:body.id,revision:body.revision,digest:body.digest,settings:body.settings,generation:body.expectedGeneration+1,state:path.endsWith('/start')?'RUNNING':'STOPPED',cursor:new Date().toISOString(),cursorId:'00000000-0000-0000-0000-000000000000',updatedAt:new Date().toISOString(),error:null};if(delegated)task.authorization={id:'10000000-0000-4000-8000-000000000113',issuedAt:task.updatedAt,expiresAt:new Date(Date.parse(task.updatedAt)+600000).toISOString(),maxBatches:20,consumedBatches:3,...delegated.invalid};response={requestId:body.requestId,operation:path.endsWith('/start')?'START':'STOP',commandDigest:hash,createdAt:task.updatedAt,task:structuredClone(task)};controls.set(body.requestId,response)}
  else if(path.endsWith('/drafts')){saved={definition:body.definition,layout:body.layout,digest:hash,state:'DRAFT',editVersion:body.expectedEditVersion+1,updatedAt:new Date().toISOString(),preview:null};response=saved}
  else if(path.endsWith('/preview')||path.endsWith('/run')){const entry=path.endsWith('/run')?published:saved;const receipt={id:'10000000-0000-4000-8000-000000000001',digest:hash,inputDigest:hash,origin:entry.definition.source.kind==='MANUAL_SAMPLE'?'MANUAL_SAMPLE':entry.definition.source.configuration?'zabbix-jsonrpc':'fixture',accepted:1,rejected:0,filtered:0,createdAt:new Date().toISOString()};if(entry===saved)saved.preview=receipt;runs.unshift({workflowId:entry.definition.id,revision:entry.definition.revision,mode:entry===saved?'PREVIEW':'RUN',receipt});response={receipt,evaluation:{rows:[{index:0,status:'ACCEPTED',steps:entry.definition.nodes.map((n:any)=>({nodeId:n.id,type:n.type,status:'OK',values:{name:'Fixture service'},issues:[]}))}],accepted:1,rejected:0,filtered:0,dryRun:true,writesPerformed:false},retainedCount:1,missingRaw:0,truncated:false,sourceStatus:receipt.origin==='MANUAL_SAMPLE'?'MANUAL_SAMPLE':'SUCCEEDED'}}
  else if(path.endsWith('/publish')){published={...saved,state:'PUBLISHED',editVersion:0};response=published}
  else response=path.includes('/versions/')?published:saved
  await route.fulfill({contentType:'application/json',body:JSON.stringify(response)})
 })
 await page.goto('/#/integrations/workflows');await page.getByRole('textbox',{name:'平台开发 Token（仅保存在当前标签页内存）'}).fill(TOKEN_OK);await expect(page.getByRole('button',{name:'新建工作流',exact:true})).toBeVisible();await page.getByRole('button',{name:'新建工作流',exact:true}).click();await expect(page.getByRole('button',{name:'＋ 自定义实体模板',exact:true})).toBeVisible();return calls
}
for (const width of [1440,1024,390]) test('binds an explicit connection version and previews without a legacy batch at '+width,async({page})=>{
 await page.setViewportSize({width,height:1000});const calls=await setup(page)
 const c=JSON.parse(readFileSync(new URL('../../../contracts/examples/v2/source-connection-configuration-scoped.json',import.meta.url),'utf8'))
 const sample=JSON.parse(readFileSync(new URL('../../../contracts/examples/v2/source-instance-page.json',import.meta.url),'utf8'))
 const instance={...sample.items[0],id:c.sourceId,name:'Fixture configured workflow source',source:{kind:'ZABBIX_HOST',instanceId:'connection-'+c.sourceId},configurationRevision:c.revision,connectionDigest:c.connectionDigest,dataMode:'zabbix-jsonrpc',editVersion:1,state:'ACTIVE',createdAt:c.createdAt,updatedAt:c.createdAt,workflowId:'source-'+c.sourceId}
 const reads:string[]=[];await page.route(/\/api\/v2\/data-sources(?:\/|$)/,async route=>{const path=new URL(route.request().url()).pathname;reads.push(path);if(path.endsWith('/connection/history'))return route.fulfill({json:{schemaVersion:'2.0',storage:'memory',sourceId:c.sourceId,items:[c]}});return route.fulfill({json:{schemaVersion:'2.0',storage:'memory',items:[instance],truncated:false}})})
 await page.getByRole('button',{name:'＋ Zabbix 主机模板',exact:true}).click();await page.getByRole('button',{name:'数据输入节点 source',exact:true}).click()
 expect(reads).toHaveLength(0);await page.getByRole('button',{name:'选择接入实例',exact:true}).click();await page.getByLabel('工作流接入实例',{exact:true}).selectOption(c.sourceId)
 await expect(page.getByLabel('工作流连接版本',{exact:true})).toHaveValue('');await expect(page.getByRole('button',{name:'使用此版本',exact:true})).toBeDisabled()
 await page.getByLabel('工作流连接版本',{exact:true}).selectOption('1');await expect(page.locator('.studio-inspector .workflow-source-config')).toContainText(c.credentialPin.versionId);await page.getByRole('button',{name:'使用此版本',exact:true}).click()
 for(const dark of [false,true]){if(dark){await closeWorkflowInspector(page);await page.getByRole('button',{name:'切换到深色模式',exact:true}).click();await page.getByRole('button',{name:'数据输入节点 source',exact:true}).click()}await expect(page.locator('.studio-inspector .workflow-source-config')).toContainText(c.connectionDigest);expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true)}
 await closeWorkflowInspector(page);await page.getByRole('button',{name:'保存草稿',exact:true}).click();await page.getByRole('button',{name:'预览当前草稿',exact:true}).click();await expect(page.locator('[data-workflow-output]')).toBeVisible()
 const saved=calls.find(x=>x.path.endsWith('/drafts'))!.body.definition.source;expect(saved.configuration).toEqual({sourceId:c.sourceId,revision:1,digest:c.connectionDigest})
 const preview=calls.find(x=>x.path.endsWith('/preview'))!.body;expect(preview).not.toHaveProperty('samples');expect(preview).not.toHaveProperty('syncRunId');expect(preview.dryRun).toBe(true)
 await expect(page.getByLabel('工作流来源批次',{exact:true})).toHaveCount(0);await page.getByRole('button',{name:'发布版本',exact:true}).click();await expect(page.getByRole('button',{name:'选择接入实例',exact:true})).toHaveCount(0)
 expect(calls.filter(x=>x.path.endsWith('/runtime'))).toHaveLength(0);expect(reads).toHaveLength(2)
})
test('failed connection list stays failed across drawer reopening until explicit retry',async({page})=>{
 await setup(page);let reads=0;await page.route('**/api/v2/data-sources',async route=>{reads++;return route.fulfill({status:500,json:{code:'UNAVAILABLE'}})})
 await page.getByRole('button',{name:'＋ Zabbix 主机模板',exact:true}).click();await page.getByRole('button',{name:'数据输入节点 source',exact:true}).click();await page.getByRole('button',{name:'选择接入实例',exact:true}).click();await expect(page.getByRole('button',{name:'重新读取连接',exact:true})).toBeVisible();expect(reads).toBe(1)
 await closeWorkflowInspector(page);await page.getByRole('button',{name:'数据输入节点 source',exact:true}).click();await expect(page.getByRole('button',{name:'重新读取连接',exact:true})).toBeVisible();expect(reads).toBe(1);await page.getByRole('button',{name:'重新读取连接',exact:true}).click();await expect.poll(()=>reads).toBe(2)
})
test('connection list permission loss clears the source drawer and cached workflow',async({page})=>{
 await setup(page);let reads=0;await page.route('**/api/v2/data-sources',async route=>{reads++;return route.fulfill({status:403,json:{code:'FORBIDDEN'}})})
 await page.getByRole('button',{name:'＋ Zabbix 主机模板',exact:true}).click();await page.getByRole('button',{name:'数据输入节点 source',exact:true}).click();await page.getByRole('button',{name:'选择接入实例',exact:true}).click();await expect(page.getByRole('dialog',{name:'节点配置',exact:true})).not.toBeVisible();await expect(page.getByRole('button',{name:'选择接入实例',exact:true})).toHaveCount(0);expect(reads).toBe(1)
})
for (const width of [1440, 1024, 390]) test('node library is searchable, sits beside the canvas and restores keyboard focus at ' + width, async ({ page }) => {
 await page.setViewportSize({ width, height: 1000 }); const calls = await setup(page)
 await page.getByRole('button', { name: '＋ 自定义实体模板', exact: true }).click()
 const toggle = page.getByRole('button', { name: '节点库', exact: true })
 await expect(toggle).toHaveAttribute('aria-expanded', String(width > 1000))
 for (const dark of [false, true]) {
  if (dark) await page.getByRole('button', { name: '切换到深色模式', exact: true }).click()
  if (await toggle.getAttribute('aria-expanded') === 'false') await toggle.click()
  const library = page.getByRole('complementary', { name: '处理算子', exact: true })
  await expect(library).toBeVisible()
  await expect(library).toContainText('清洗与转换')
  await expect(library).toContainText('流程控制')
  await expect(library.locator('.workflow-library-item')).toHaveCount(7)
  await expect(library.locator('.workflow-output-node')).toHaveCount(3)
  const selectedOutput = library.locator('.workflow-output-node[aria-pressed=true]'), otherOutput = library.locator('.workflow-output-node[aria-pressed=false]').first()
  expect(await selectedOutput.evaluate(button => getComputedStyle(button).backgroundColor)).not.toBe(await otherOutput.evaluate(button => getComputedStyle(button).backgroundColor))
  await expect(page.getByRole('link', { name: '← 数据源中心', exact: true })).toHaveCount(0)
  await expect(page.locator('.studio-add-node, .graph-caption')).toHaveCount(0)
  await expect(library).not.toContainText('仅列出已实现算子')
  await expect.poll(() => page.locator('.studio-canvas-tools button[aria-label="放大画布"]').evaluate(button => {
   const box = button.getBoundingClientRect(), icon = button.querySelector('svg')!.getBoundingClientRect()
   return Math.max(Math.abs(box.x + box.width / 2 - icon.x - icon.width / 2), Math.abs(box.y + box.height / 2 - icon.y - icon.height / 2))
  })).toBeLessThanOrEqual(1)
  if (width > 1000) {
   const bounds = (await library.boundingBox())!, canvas = (await page.locator('.studio-flow-surface').boundingBox())!
   expect(bounds.x + bounds.width).toBeCloseTo(canvas.x, 0)
   expect(bounds.y).toBeCloseTo(canvas.y, 0)
  }
  const search = library.getByRole('textbox', { name: '搜索可添加节点', exact: true })
  await search.fill('SCALE')
  await expect(library.locator('.workflow-library-item')).toHaveCount(1)
  await expect(library.locator('.workflow-library-item')).toContainText('数值换算')
  await search.fill('指标')
  await expect(library.locator('.workflow-library-item')).toHaveCount(0)
  await expect(library.locator('.workflow-output-node')).toHaveCount(1)
  await expect(library.getByRole('button', { name: '指标输出', exact: true })).toBeEnabled()
  await search.fill('不存在的节点')
  await expect(library.getByText('没有匹配的节点。', { exact: true })).toBeVisible()
  await library.getByRole('button', { name: '清除节点搜索', exact: true }).click()
  await expect(library.locator('.workflow-library-item')).toHaveCount(7)
  await search.press('Escape')
  await expect(library).not.toBeVisible()
  await expect(toggle).toBeFocused()
  await toggle.press('Enter')
  await page.getByRole('button', { name: '收起节点库', exact: true }).click()
  await expect(toggle).toBeFocused()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
 }
 await toggle.click()
 await page.locator('.workflow-library-item').filter({ hasText: '数值换算' }).click()
 await expect(page.locator('.workflow-node')).toHaveCount(6)
 await expect(page.getByRole('dialog', { name: '节点配置', exact: true })).toBeVisible()
 await expect(page.getByRole('textbox', { name: '换算系数', exact: true })).toBeVisible()
 expect(calls).toHaveLength(1)
})
for (const width of [1440, 1024, 390]) test('default connectors stay straight and readable in both themes at ' + width, async ({ page }) => {
 await page.setViewportSize({ width, height: 1000 }); const calls = await setup(page)
 await page.getByRole('button', { name: '＋ 自定义实体模板', exact: true }).click()
 await expect(page.locator('[data-engine=x6]')).toHaveAttribute('data-graph-status', 'ready')
 for (const dark of [false, true]) {
  if (dark) await page.getByRole('button', { name: '切换到深色模式', exact: true }).click()
  await page.getByRole('button', { name: '适应画布', exact: true }).click()
  const nodes = await page.locator('.workflow-node').evaluateAll(elements => elements.map(element => {
   const box = element.getBoundingClientRect(); return { x: box.x + box.width / 2, top: box.top, bottom: box.bottom }
  }))
  expect(nodes).toHaveLength(5)
  for (let i = 1; i < nodes.length; i++) {
   expect(Math.abs(nodes[i]!.x - nodes[i - 1]!.x)).toBeLessThan(1)
   expect(nodes[i]!.top - nodes[i - 1]!.bottom).toBeGreaterThan(24)
  }
  const paths = await page.locator('.x6-edge path[stroke="var(--ow-graph-edge)"]').evaluateAll(elements => elements.map(element => {
   const path = element as SVGPathElement; return { width: path.getBBox().width, length: path.getTotalLength() }
  }))
  expect(paths).toHaveLength(4)
  for (const path of paths) { expect(path.width).toBeLessThan(1); expect(path.length).toBeGreaterThan(24) }
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
 }
 expect(calls).toHaveLength(1)
 expect(calls[0]!.path).toBe('/api/v1/integrations/workflows')
})

test('a bypass branch clears the intervening node after arranging', async ({ page }) => {
 await page.setViewportSize({ width: 1440, height: 1000 }); const calls = await setup(page)
 await page.getByRole('button', { name: '＋ 自定义实体模板', exact: true }).click()
 if (await page.getByRole('button', { name: '节点库', exact: true }).getAttribute('aria-expanded') === 'false') await page.getByRole('button', { name: '节点库', exact: true }).click()
 await page.locator('.workflow-library-item').filter({ hasText: '分支合流' }).click()
 const merge = await page.locator('.workflow-node').filter({ hasText: '分支合流' }).getAttribute('data-node-id')
 await closeWorkflowInspector(page)
 await page.getByText('连接与分支', { exact: false }).click()
 await page.getByRole('combobox', { name: '连接起点' }).selectOption('mapping')
 await page.getByRole('combobox', { name: '连接终点' }).selectOption(merge!)
 await page.getByRole('button', { name: '添加连接', exact: true }).click()
 await page.keyboard.press('Escape')
 await expect(page.getByRole('dialog', { name: '连接管理', exact: true })).not.toBeVisible()
 await page.getByRole('button', { name: '更多工作流操作', exact: true }).click();await page.getByRole('button', { name: '自动排列节点', exact: true }).click()
 await page.getByRole('button', { name: '适应画布', exact: true }).click()
 const trim = (await page.locator('[data-node-id=trim]').boundingBox())!
 const paths = page.locator('.x6-edge path[stroke="var(--ow-graph-edge)"]')
 await expect(paths).toHaveCount(6)
 // Edits sort connections topologically; find this branch by its displayed endpoints.
 const edgeIndex = await page.locator('.workflow-connection-editor li').evaluateAll((rows, label) =>
  rows.findIndex(row => row.querySelector('button')?.getAttribute('aria-label') === label), '移除连接 mapping → ' + merge)
 expect(edgeIndex).toBeGreaterThanOrEqual(0)
 const points = await page.locator('.x6-edge[data-cell-id="edge-' + edgeIndex + '"] path[stroke="var(--ow-graph-edge)"]').evaluate(element => {
  const path = element as SVGPathElement, matrix = path.getScreenCTM()!, length = path.getTotalLength()
  return Array.from({ length: 101 }, (_, index) => {
   const point = path.getPointAtLength(length * index / 100)
   return { x: matrix.a * point.x + matrix.c * point.y + matrix.e, y: matrix.b * point.x + matrix.d * point.y + matrix.f }
  })
 })
 expect(points.every(point => point.x <= trim.x || point.x >= trim.x + trim.width || point.y <= trim.y || point.y >= trim.y + trim.height)).toBe(true)
 expect(points.some(point => point.x < trim.x - 2 || point.x > trim.x + trim.width + 2)).toBe(true)
 expect(calls).toHaveLength(1)
})

test('drag a new operator and connect its X6 ports before saving',async({page})=>{
 await page.setViewportSize({width:1500,height:1000});const calls=await setup(page);await page.getByRole('button',{name:'＋ 自定义实体模板',exact:true}).click();await closeWorkflowInspector(page);if (await page.getByRole('button', { name: '节点库', exact: true }).getAttribute('aria-expanded') === 'false') await page.getByRole('button', { name: '节点库', exact: true }).click();const palette=page.locator('.workflow-library-item').filter({hasText:'补充默认值'}),canvas=page.locator('.workflow-canvas-viewport');await palette.dragTo(canvas,{targetPosition:{x:350,y:190}});await expect(page.locator('.workflow-node')).toHaveCount(6);await expect(page.getByRole('button',{name:'保存草稿',exact:true})).toBeDisabled();const added=await page.locator('.workflow-node').filter({hasText:'补充默认值'}).getAttribute('data-node-id');expect(added).toBeTruthy();await page.getByRole('textbox',{name:'缺失时填入'}).fill('Fixture drag');await closeWorkflowInspector(page);await page.getByText('连接与分支',{exact:false}).click();await page.getByRole('button',{name:'移除连接 trim → validate',exact:true}).click();
 const edge=async(from:string,to:string)=>{await page.getByRole('button',{name:'关闭连接管理',exact:true}).click();await canvas.scrollIntoViewIfNeeded();const start=page.locator('.x6-node[data-cell-id="'+from+'"] [port="out"][magnet="true"]'),end=page.locator('.x6-node[data-cell-id="'+to+'"] [port="in"][magnet="passive"]');await expect(start).toBeInViewport();await expect(end).toBeInViewport();const a=(await start.boundingBox())!,b=(await end.boundingBox())!;await page.mouse.move(a.x+a.width/2,a.y+a.height/2);await page.mouse.down();await page.mouse.move(b.x+b.width/2,b.y+b.height/2,{steps:12});await page.mouse.up();await page.getByRole('button',{name:/^连接与分支/}).click();await expect(page.getByRole('button',{name:'移除连接 '+from+' → '+to,exact:true})).toBeVisible()};
 await edge('trim',added!);await edge(added!,'validate');await expect(page.getByRole('button',{name:'保存草稿',exact:true})).toBeEnabled();await closeWorkflowInspector(page);await page.getByRole('button',{name:'保存草稿',exact:true}).click();await expect(page.getByText('草稿已保存；尚未发布或启用采集。',{exact:true})).toBeVisible();const d=calls.at(-1)!.body.definition;expect(d.nodes[3].type).toBe('DEFAULT');expect(d.edges).toContainEqual({from:'trim',to:added});expect(d.edges).toContainEqual({from:added,to:'validate'});expect(calls.some(c=>c.path.endsWith('/preview')||c.path.endsWith('/run'))).toBe(false)
})
test('branch and merge use accessible connections, preserve graph edits and reject cycles',async({page})=>{
 const calls=await setup(page);await page.getByRole('button',{name:'＋ 自定义实体模板',exact:true}).click();await closeWorkflowInspector(page);if (await page.getByRole('button', { name: '节点库', exact: true }).getAttribute('aria-expanded') === 'false') await page.getByRole('button', { name: '节点库', exact: true }).click();await page.locator('.workflow-library-item').filter({hasText:'分支合流'}).click();const merge=await page.locator('.workflow-node').filter({hasText:'分支合流'}).getAttribute('data-node-id');await closeWorkflowInspector(page);await page.getByText('连接与分支',{exact:false}).click();await page.getByRole('combobox',{name:'连接起点'}).selectOption('mapping');await page.getByRole('combobox',{name:'连接终点'}).selectOption(merge!);await page.getByRole('button',{name:'添加连接',exact:true}).click();await expect(page.getByRole('button',{name:'移除连接 mapping → '+merge,exact:true})).toBeVisible();await page.getByRole('combobox',{name:'连接起点'}).selectOption(merge!);await page.getByRole('combobox',{name:'连接终点'}).selectOption('trim');await page.getByRole('button',{name:'添加连接',exact:true}).click();await expect(page.getByText('此节点已有输入；先移除原连接，或使用合流节点',{exact:true})).toBeVisible();await page.getByRole('button', { name: '更多工作流操作', exact: true }).click();await page.getByRole('button',{name:'节点配置',exact:true}).click();await page.getByRole('combobox',{name:'选择工作流节点'}).selectOption(merge!);await closeWorkflowInspector(page);await page.getByRole('textbox',{name:'工作流名称',exact:true}).fill('Fixture DAG renamed');await closeWorkflowInspector(page);await page.getByRole('button',{name:'保存草稿',exact:true}).click();await expect(page.getByText('草稿已保存；尚未发布或启用采集。',{exact:true})).toBeVisible();const d=calls.at(-1)!.body.definition;expect(d.edges).toContainEqual({from:'mapping',to:merge});expect(d.nodes.find((n:any)=>n.id===merge).type).toBe('MERGE');await page.getByRole('button',{name:'步骤视图',exact:true}).click();await expect(page.locator('.workflow-operator-palette')).toBeVisible()
})
for(const width of [1440,390])test('published entity execution requires explicit identity and shows a separate write receipt at '+width,async({page})=>{
 await page.setViewportSize({width,height:1000});const calls=await setup(page);await page.getByRole('button',{name:'＋ 自定义实体模板',exact:true}).click();await closeWorkflowInspector(page);await page.getByRole('button',{name:'保存草稿',exact:true}).click();await page.getByRole('button',{name:'预览当前草稿',exact:true}).click();await page.getByRole('button',{name:'发布版本',exact:true}).click();expect(calls.some(c=>c.path.includes('/runtime/'))).toBe(false);await page.getByText('执行与任务管理',{exact:true}).click();await expect(page.getByRole('button',{name:'执行并写入资产',exact:true})).toBeDisabled();await page.getByRole('textbox',{name:'运行来源标识字段'}).fill('name');await page.getByRole('combobox',{name:'运行实体名称字段'}).selectOption('name');await page.getByRole('button',{name:'执行并写入资产',exact:true}).click();await expect(page.getByText('已确认写入 1 条资产，可到资产页查看。',{exact:true})).toBeVisible();const write=calls.find(c=>c.path.endsWith('/runtime/execute'))!.body;expect(write.settings).toEqual({identityField:'name',nameField:'name'});expect(write.previewId).toBe(calls.find(c=>c.path.endsWith('/run'))!.body? '10000000-0000-4000-8000-000000000001':'missing');expect(write).not.toHaveProperty('tenantId');expect(write).not.toHaveProperty('dryRun');await expect(page.locator('.workflow-runtime-receipts')).toContainText('手工样本');await expect(page.getByRole('button',{name:'启动持续处理',exact:true})).toHaveCount(0);expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true)
})
test('write transport failure pins the preview key, blocks version changes and explicitly reconciles',async({page})=>{
 const calls=await setup(page);await page.getByRole('button',{name:'＋ 自定义实体模板',exact:true}).click();await closeWorkflowInspector(page);await page.getByRole('button',{name:'保存草稿',exact:true}).click();await page.getByRole('button',{name:'预览当前草稿',exact:true}).click();await page.getByRole('button',{name:'发布版本',exact:true}).click();await page.getByText('执行与任务管理',{exact:true}).click();await page.getByRole('textbox',{name:'运行来源标识字段'}).fill('name');await page.getByRole('combobox',{name:'运行实体名称字段'}).selectOption('name');let firstBody:any;await page.route('**/workflows/runtime/execute',async route=>{firstBody=route.request().postDataJSON();await route.abort('failed')},{times:1});await page.getByRole('button',{name:'执行并写入资产',exact:true}).click();await expect(page.getByRole('button',{name:'按原标识确认执行',exact:true})).toBeEnabled();await expect(page.getByRole('button',{name:'创建下一版',exact:true})).toBeDisabled();await page.getByRole('button',{name:'按原标识确认执行',exact:true}).click();await expect(page.getByText('已确认写入 1 条资产，可到资产页查看。',{exact:true})).toBeVisible();expect(calls.find(c=>c.path.endsWith('/runtime/execute'))!.body).toEqual(firstBody);expect(calls.filter(c=>c.path.endsWith('/run'))).toHaveLength(1);await expect(page.getByRole('button',{name:'创建下一版',exact:true})).toBeEnabled()
})
test('source runtime start and stop retain the published pin and generation',async({page})=>{
 const calls=await setup(page);await page.getByRole('button',{name:'＋ Zabbix 主机模板',exact:true}).click();await closeWorkflowInspector(page);await page.getByRole('button',{name:'保存草稿',exact:true}).click();await page.getByText('测试数据与结果',{exact:true}).click();await page.getByRole('textbox',{name:'工作流来源批次'}).fill('10000000-0000-4000-8000-000000000002');await page.getByRole('button',{name:'预览当前草稿',exact:true}).click();await page.getByRole('button',{name:'发布版本',exact:true}).click();await page.getByText('执行与任务管理',{exact:true}).click();await page.getByRole('textbox',{name:'运行来源标识字段'}).fill('entity_id');await page.getByRole('combobox',{name:'运行实体名称字段'}).selectOption('hostname');await page.getByRole('button',{name:'启动持续处理',exact:true}).click();await expect(page.locator('.workflow-runtime-actions')).toContainText('运行中');await expect(page.getByRole('textbox',{name:'运行来源标识字段'})).toBeDisabled();await page.getByRole('button',{name:'停止任务',exact:true}).click();await expect(page.locator('.workflow-runtime-actions')).toContainText('已停止');const start=calls.find(c=>c.path.endsWith('/runtime/start'))!.body,stop=calls.find(c=>c.path.endsWith('/runtime/stop'))!.body;expect(start.expectedGeneration).toBe(0);expect(stop.expectedGeneration).toBe(1);expect(start.digest).toBe(stop.digest);expect(start.revision).toBe(stop.revision);expect(calls.some(c=>c.path.endsWith('/runtime/execute'))).toBe(false)
})
test('a stale operator task remains failed without triggering a hidden restart',async({page})=>{
 const calls=await setup(page);await page.getByRole('button',{name:'＋ Zabbix 主机模板',exact:true}).click();await closeWorkflowInspector(page)
 await page.getByRole('button',{name:'保存草稿',exact:true}).click();await page.getByText('测试数据与结果',{exact:true}).click();await page.getByRole('textbox',{name:'工作流来源批次'}).fill('10000000-0000-4000-8000-000000000002')
 await page.getByRole('button',{name:'预览当前草稿',exact:true}).click();await page.getByRole('button',{name:'发布版本',exact:true}).click()
 const d=calls.find(c=>c.path.endsWith('/drafts'))!.body.definition
 await page.route('**/workflows/runtime',route=>route.fulfill({json:{schemaVersion:'2.0',mode:'LOCAL_DEV_ENTITY',pollSeconds:5,maxBatchRecords:5,executions:[],tasks:[{workflowId:d.id,revision:d.revision,digest:hash,settings:{identityField:'entity_id',nameField:'hostname'},generation:1,state:'FAILED',cursor:new Date().toISOString(),cursorId:'00000000-0000-0000-0000-000000000000',updatedAt:new Date().toISOString(),error:'OPERATOR_CHANGED'}]}}))
 await page.getByText('执行与任务管理',{exact:true}).click();await expect(page.locator('.workflow-runtime-actions')).toContainText('已失败');await expect(page.getByRole('alert')).toContainText('固定算子版本与当前实现不匹配')
 expect(calls.filter(c=>c.path.includes('/runtime/'))).toHaveLength(0)
})
for(const width of [1500,390])test('workflow fixture edit, preview, publish, run and version at '+width,async({page})=>{
 await page.setViewportSize({width,height:1000});const calls=await setup(page);await page.getByRole('button',{name:'＋ 自定义实体模板',exact:true}).click();await expect(page.locator('.workflow-node')).toHaveCount(5); await expect(page.locator('[data-engine=x6]')).toHaveAttribute('data-graph-status','ready'); await page.getByRole('button', { name: '更多工作流操作', exact: true }).click();await page.getByRole('button',{name:'输出配置',exact:true}).click(); await expect(page.getByRole('combobox',{name:'实体模型',exact:true})).toHaveValue('builtin.service@1');await expect(page.getByRole('button',{name:'发布版本',exact:true})).toBeDisabled();
 await closeWorkflowInspector(page);if (await page.getByRole('button', { name: '节点库', exact: true }).getAttribute('aria-expanded') === 'false') await page.getByRole('button', { name: '节点库', exact: true }).click();await page.locator('.workflow-library-item').filter({hasText:'补充默认值'}).click();await page.getByRole('textbox',{name:'缺失时填入'}).fill('Fixture service');await closeWorkflowInspector(page);await expect(page.locator('.workflow-node')).toHaveCount(6);await page.getByRole('button', { name: '更多工作流操作', exact: true }).click();await page.getByRole('button',{name:'撤销工作流修改'}).click();await page.getByRole('button', { name: '更多工作流操作', exact: true }).click();await page.getByRole('button',{name:'重做工作流修改'}).click();await closeWorkflowInspector(page);await page.getByRole('button',{name:'保存草稿',exact:true}).click();await expect(page.getByText('草稿已保存；尚未发布或启用采集。',{exact:true})).toBeVisible();
 const request=calls.find(c=>c.path.endsWith('/drafts'))!.body;expect(request.definition.nodes[3].type).toBe('DEFAULT');expect(request.definition.nodes[3].config.value).toBe('Fixture service');expect(request.definition.target.id).toBe('builtin.service');expect(request.expectedEditVersion).toBe(0);
 await page.getByRole('button',{name:'预览当前草稿',exact:true}).click();await expect(page.getByRole('button',{name:'发布版本',exact:true})).toBeEnabled();await page.getByRole('button', { name: '更多工作流操作', exact: true }).click();await page.getByRole('button',{name:'输出配置',exact:true}).click();await page.getByRole('combobox',{name:'选择工作流节点'}).selectOption('output');await expect(page.locator('[data-workflow-output]')).toContainText('Fixture service');await expect(page.locator('.workflow-result-counts')).toContainText('手工样本');await closeWorkflowInspector(page);
 await page.getByRole('button',{name:'发布版本',exact:true}).click();await expect(page.getByText('版本已发布。测试运行只读；实体输出可在运行管理中显式启用。',{exact:true})).toBeVisible();await expect(page.getByRole('textbox',{name:'工作流名称',exact:true})).toBeDisabled();await page.getByRole('button',{name:'测试运行已发布版本',exact:true}).click();await page.getByRole('button', { name: '更多工作流操作', exact: true }).click();await page.getByRole('button',{name:'返回版本列表',exact:true}).click();await expect(page.locator('.workflow-history')).toContainText('版本测试');await page.getByRole('button',{name:'查看流程',exact:true}).click();await page.getByRole('button',{name:'创建下一版',exact:true}).click();await expect(page.locator('.workflow-state')).toContainText('v2');await expect(page.getByRole('button',{name:'发布版本',exact:true})).toBeDisabled();expect(calls.filter(c=>c.path.endsWith('/run'))[0].body.dryRun).toBe(true);expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true)
})
test('X6 drag and keyboard movement persist layout without execution',async({page})=>{
 await page.setViewportSize({width:1500,height:1000});const calls=await setup(page);await page.getByRole('button',{name:'＋ 自定义实体模板',exact:true}).click();await expect(page.locator('[data-engine=x6]')).toHaveAttribute('data-graph-status','ready');const node=page.locator('[data-node-id=source]');await node.scrollIntoViewIfNeeded();const canvas=page.locator('.x6-canvas');const beforeNode=(await node.boundingBox())!,beforeCanvas=(await canvas.boundingBox())!;const scrollBefore=await page.getByRole('main').evaluate(e=>e.scrollTop);await page.mouse.move(beforeCanvas.x+beforeCanvas.width-20,beforeNode.y+20);await page.mouse.wheel(0,150);await expect.poll(()=>page.getByRole('main').evaluate(e=>e.scrollTop)).toBeGreaterThan(scrollBefore);const afterNode=(await node.boundingBox())!,afterCanvas=(await canvas.boundingBox())!;expect(Math.abs((afterNode.y-afterCanvas.y)-(beforeNode.y-beforeCanvas.y))).toBeLessThan(2);await node.scrollIntoViewIfNeeded();const box=(await node.boundingBox())!;await page.mouse.move(box.x+60,box.y+30);await page.mouse.down();await page.mouse.move(box.x+105,box.y+62,{steps:8});await page.mouse.up();await node.focus();await page.keyboard.press('ArrowRight');expect(calls).toHaveLength(1);await closeWorkflowInspector(page);await page.getByRole('button',{name:'保存草稿',exact:true}).click();await expect(page.getByText('草稿已保存；尚未发布或启用采集。',{exact:true})).toBeVisible();const position=calls.at(-1)!.body.layout.source;expect(position.x).toBeGreaterThan(190);expect(position.y).toBeGreaterThan(40);expect(Number.isInteger(position.x)&&Number.isInteger(position.y)).toBe(true);expect(calls.at(-1)!.body.definition).not.toHaveProperty('cells');await page.getByRole('button',{name:'适应画布'}).click();expect(calls).toHaveLength(2)
})

test('Zabbix workflow sends only an explicit batch and preserves fixture marker',async({page})=>{
 const calls=await setup(page);await page.getByRole('button',{name:'＋ Zabbix 主机模板',exact:true}).click();await expect(page.getByRole('textbox',{name:'工作流手工样本'})).toHaveCount(0);await closeWorkflowInspector(page);await page.getByRole('button',{name:'保存草稿',exact:true}).click();await expect(page.getByRole('button',{name:'预览当前草稿',exact:true})).toBeEnabled();await page.getByText('测试数据与结果',{exact:true}).click();await page.getByRole('textbox',{name:'工作流来源批次'}).fill('10000000-0000-4000-8000-000000000002');await page.getByRole('button',{name:'预览当前草稿',exact:true}).click();await expect(page.locator('.workflow-result-counts')).toContainText('Fixture 合成数据');const command=calls.find(c=>c.path.endsWith('/preview'))!.body;expect(command).not.toHaveProperty('samples');expect(command.syncRunId).toBe('10000000-0000-4000-8000-000000000002');expect(calls.some(c=>c.path.includes('/sync'))).toBe(false)
})
test('session change clears private drafts and sample without local storage',async({page})=>{
 await setup(page);await page.getByRole('button',{name:'＋ 自定义实体模板',exact:true}).click();await page.getByText('测试数据与结果',{exact:true}).click();await page.getByRole('textbox',{name:'工作流手工样本'}).fill('[{"name":"private fixture"}]');await page.getByRole('textbox',{name:'平台开发 Token（仅保存在当前标签页内存）'}).fill('changed-token-long-enough-123456789');await expect(page.locator('.workflow-node')).toHaveCount(0);await expect(page.locator('.workflow-welcome')).toBeVisible();expect(await page.evaluate(()=>JSON.stringify({...localStorage,...sessionStorage}))).not.toContain('private fixture')
})
test('a write-capable response cannot unlock workflow publication',async({page})=>{
 await setup(page);await page.getByRole('button',{name:'＋ 自定义实体模板',exact:true}).click();await closeWorkflowInspector(page);await page.getByRole('button',{name:'保存草稿',exact:true}).click();await expect(page.getByRole('button',{name:'预览当前草稿',exact:true})).toBeEnabled();await page.route('**/api/v1/integrations/workflows/preview',route=>route.fulfill({contentType:'application/json',body:JSON.stringify({writesPerformed:true})}));await page.getByRole('button',{name:'预览当前草稿',exact:true}).click();await expect(page.getByRole('alert').filter({hasText:'不符合契约'})).toBeVisible();await expect(page.getByRole('button',{name:'发布版本',exact:true})).toBeDisabled()
})

for (const width of [1440, 390]) test('steps are clickable, ordered and secondary controls stay closed at ' + width, async ({ page }) => {
 await page.setViewportSize({ width, height: 1000 }); const calls = await setup(page);
 await page.getByRole('button', { name: '＋ 自定义实体模板', exact: true }).click();
 await page.getByRole('button', { name: '步骤视图', exact: true }).click();
 const cards = page.locator('.workflow-sequence-card');
 await expect(cards).toHaveCount(5);
 await expect(page.locator('.workflow-library-item')).toHaveCount(width > 1000 ? 7 : 0);
 await expect(page.locator('.workflow-history')).not.toBeVisible();
 await expect(page.getByRole('textbox', { name: '工作流手工样本', includeHidden: true })).not.toBeVisible();
 let previousBottom = 0;
 for (const card of await cards.all()) {
  const box = (await card.boundingBox())!;
  expect(box.y).toBeGreaterThanOrEqual(previousBottom); previousBottom = box.y + box.height;
  await expect(card).not.toContainText('mapping');
 }
 await page.locator('[data-node-id=mapping]').click();
 await expect(page.getByRole('textbox', { name: '来源字段 → name' })).toBeVisible();
 await page.getByRole('textbox', { name: '来源字段 → name' }).fill('service_name');
 await closeWorkflowInspector(page);
 await page.getByRole('button', { name: '保存草稿', exact: true }).click();
 await page.locator('[data-node-id=mapping]').click();
 await expect(page.getByRole('combobox', { name: '选择工作流节点' })).toHaveValue('mapping');
 await expect(page.getByRole('textbox', { name: '来源字段 → name' })).toHaveValue('service_name');
 expect(calls.filter(c => c.path.endsWith('/drafts'))[0].body.definition.nodes[1].config.service_name).toBe('name');
 await page.locator('[data-node-id=source]').focus(); await page.keyboard.press('Enter');
 await expect(page.getByRole('combobox', { name: '输入来源' })).toBeVisible();
 expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
});

test('editing test data requires a fresh preview before publishing', async ({ page }) => {
 const calls = await setup(page); await page.getByRole('button', { name: '＋ 自定义实体模板', exact: true }).click();
 await page.getByRole('button', { name: '保存草稿', exact: true }).click();
 await page.getByRole('button', { name: '预览当前草稿', exact: true }).click();
 await expect(page.getByRole('button', { name: '发布版本', exact: true })).toBeEnabled();
 await expect(page.getByRole('textbox', { name: '工作流手工样本' })).toBeVisible();
 await page.getByRole('textbox', { name: '工作流手工样本' }).fill('[{"name":"new Fixture sample"}]');
 await expect(page.getByRole('button', { name: '发布版本', exact: true })).toBeDisabled();
 await expect(page.locator('[data-workflow-output]')).toHaveCount(0);
 expect(calls.filter(c => c.path.endsWith('/preview'))).toHaveLength(1);
 await page.getByRole('button', { name: '预览当前草稿', exact: true }).click();
 await expect(page.getByRole('button', { name: '发布版本', exact: true })).toBeEnabled();
 expect(calls.filter(c => c.path.endsWith('/preview'))).toHaveLength(2);
});

async function delegatedHost(page:Page,options:{available:boolean;invalid?:Record<string,unknown>}) {
 const calls=await setup(page,options)
 await page.getByRole('button',{name:'＋ Zabbix 主机模板',exact:true}).click()
 await closeWorkflowInspector(page)
 await page.getByRole('button',{name:'保存草稿',exact:true}).click()
 await page.getByText('测试数据与结果',{exact:true}).click()
 await page.getByRole('textbox',{name:'工作流来源批次'}).fill('10000000-0000-4000-8000-000000000002')
 await page.getByRole('button',{name:'预览当前草稿',exact:true}).click()
 await page.getByRole('button',{name:'发布版本',exact:true}).click()
 await page.getByText('执行与任务管理',{exact:true}).click()
 await page.getByRole('textbox',{name:'运行来源标识字段'}).fill('entity_id')
 await page.getByRole('combobox',{name:'运行实体名称字段'}).selectOption('hostname')
 return calls
}
for(const width of [1440,390]) test('bounded authorization summary and stable failure are visible at '+width,async({page})=>{
 await page.setViewportSize({width,height:1000});const calls=await delegatedHost(page,{available:true})
 await page.getByRole('button',{name:'启动持续处理',exact:true}).click()
 await expect(page.locator('.workflow-task-authorization summary')).toHaveText('后台授权 · 已用 3/20 批')
 await page.locator('.workflow-task-authorization summary').click()
 await expect(page.locator('.workflow-task-authorization')).toContainText('10000000-0000-4000-8000-000000000113')
 await expect(page.locator('.workflow-task-authorization')).toContainText('授权截止')
 if(process.env.OPSWEAVE_SOURCE_SCREENSHOTS) {
  for(const dark of [false,true]) {
   if(dark)await page.getByRole('button',{name:'切换到深色模式',exact:true}).click()
   await expect(page.locator('.workflow-task-authorization summary')).toHaveText('后台授权 · 已用 3/20 批')
   expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true)
   await page.locator('.workflow-runtime-panel').screenshot({path:process.env.OPSWEAVE_SOURCE_SCREENSHOTS+'/authorization-'+width+'-'+(dark?'dark':'light')+'.png'})
  }
 }
 const start=calls.find(c=>c.path.endsWith('/runtime/start'))!.body
 expect(Object.keys(start).sort()).toEqual(['digest','expectedGeneration','id','requestId','revision','settings'])
 await page.route('**/api/v1/integrations/workflows/runtime',route=>route.fulfill({json:{schemaVersion:'2.0',mode:'DELEGATED_ENTITY',backgroundAvailable:true,pollSeconds:5,maxBatchRecords:5,executions:[],tasks:[{workflowId:start.id,revision:1,digest:hash,settings:start.settings,generation:1,state:'FAILED',cursor:new Date().toISOString(),cursorId:'00000000-0000-0000-0000-000000000000',updatedAt:new Date().toISOString(),error:'AUTHORIZATION_REVOKED',authorization:{id:'10000000-0000-4000-8000-000000000113',issuedAt:new Date(Date.now()-1000).toISOString(),expiresAt:new Date(Date.now()+300000).toISOString(),maxBatches:20,consumedBatches:3}}]}}))
 await page.getByRole('button',{name:'刷新状态',exact:true}).click()
 await expect(page.getByRole('alert')).toContainText('后台授权已撤销或权限配置不可用')
 await expect(page.getByRole('button',{name:'启动持续处理',exact:true})).toBeEnabled()
 expect(calls.filter(c=>c.path.endsWith('/runtime/start'))).toHaveLength(1)
 expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true)
})
test('operator opt in is required without disabling explicit one shot execution',async({page})=>{
 const calls=await delegatedHost(page,{available:false})
 await expect(page.getByText('当前身份没有可用后台授权。',{exact:true})).toBeVisible()
 await expect(page.getByRole('button',{name:'启动持续处理',exact:true})).toBeDisabled()
 await expect(page.getByRole('button',{name:'执行并写入资产',exact:true})).toBeEnabled()
 expect(calls.some(c=>c.path.endsWith('/runtime/start'))).toBe(false)
})
for(const [label,invalid] of Object.entries({overBudget:{consumedBatches:21},privateField:{grantDigest:hash},tooLong:{expiresAt:'2099-01-01T00:00:00Z'}}))test('rejects invalid server authorization '+label+' without retry',async({page})=>{
 const calls=await delegatedHost(page,{available:true,invalid})
 await page.getByRole('button',{name:'启动持续处理',exact:true}).click()
 await expect(page.getByRole('alert')).toContainText('流程运行响应不符合契约')
 await expect(page.locator('.workflow-task-authorization')).toHaveCount(0)
 expect(calls.filter(c=>c.path.endsWith('/runtime/start'))).toHaveLength(1)
})

function controlAck(body:any,operation:'START'|'STOP') {
 const time=new Date().toISOString()
 return {requestId:body.requestId,operation,commandDigest:hash,createdAt:time,task:{workflowId:body.id,revision:body.revision,digest:body.digest,settings:body.settings,generation:body.expectedGeneration+1,state:operation==='START'?'RUNNING':'STOPPED',cursor:time,cursorId:'ffffffff-ffff-ffff-ffff-ffffffffffff',updatedAt:time,error:null}}
}
function runtimeFixture(task:any) {return {schemaVersion:'2.0',mode:'LOCAL_DEV_ENTITY',pollSeconds:5,maxBatchRecords:5,tasks:task?[task]:[],executions:[]}}
test('a legacy task at the generation ceiling can stop and cannot start again',async({page})=>{
 const calls=await delegatedHost(page,{available:true})
 await page.getByRole('button',{name:'启动持续处理',exact:true}).click()
 await expect(page.locator('.workflow-runtime-actions')).toContainText('运行中')
 const original=calls.find(c=>c.path.endsWith('/runtime/start'))!.body
 let task={...controlAck(original,'START').task,generation:1000000}
 const stops:any[]=[]
 await page.route('**/api/v1/integrations/workflows/runtime',route=>route.fulfill({json:runtimeFixture(task)}))
 await page.route('**/api/v1/integrations/workflows/runtime/stop',route=>{const body=route.request().postDataJSON();stops.push(body);const ack=controlAck(body,'STOP');task=ack.task;return route.fulfill({json:ack})})
 await page.getByRole('button',{name:'刷新状态',exact:true}).click()
 await expect(page.getByRole('button',{name:'停止任务',exact:true})).toBeEnabled()
 await page.getByRole('button',{name:'停止任务',exact:true}).click()
 await expect(page.locator('.workflow-runtime-actions')).toContainText('已停止')
 expect(stops[0].expectedGeneration).toBe(1000000);expect(task.generation).toBe(1000001)
 await expect(page.getByRole('button',{name:'启动持续处理',exact:true})).toBeDisabled()
 await expect(page.getByText('任务已达到启动次数上限。',{exact:true})).toBeVisible()
 expect(calls.filter(c=>c.path.endsWith('/runtime/start'))).toHaveLength(1)
 task={...task,state:'RUNNING'}
 await page.getByRole('button',{name:'刷新状态',exact:true}).click()
 await expect(page.getByRole('alert')).toContainText('流程运行响应不符合契约')
 await expect(page.locator('.workflow-runtime-actions')).toContainText('已停止')
 expect(stops).toHaveLength(1)
})
for(const width of [1440,390])test('lost start acknowledgement only queries its original key and keeps current state at '+width,async({page})=>{
 await page.setViewportSize({width,height:1000});await delegatedHost(page,{available:true})
 let ack:any;const submissions:any[]=[];const queries:string[]=[]
 await page.route('**/api/v1/integrations/workflows/runtime/start',async route=>{const body=route.request().postDataJSON();submissions.push(body);ack=controlAck(body,'START');await route.abort('failed')})
 await page.route('**/api/v1/integrations/workflows/runtime/commands/*',route=>{queries.push(new URL(route.request().url()).pathname);return route.fulfill({json:ack})})
 await page.getByRole('button',{name:'启动持续处理',exact:true}).click()
 await expect(page.getByRole('region',{name:'任务控制结果待确认'})).toContainText(submissions[0].requestId)
 await expect(page.getByRole('button',{name:'执行并写入资产',exact:true})).toBeDisabled()
 await expect(page.getByRole('textbox',{name:'运行来源标识字段'})).toBeDisabled()
 await expect(page.getByRole('button',{name:'按原标识重新提交',exact:true})).toHaveCount(0)
 await page.waitForTimeout(5200);expect(submissions).toHaveLength(1);expect(queries).toHaveLength(0)
 if(process.env.OPSWEAVE_SOURCE_SCREENSHOTS)for(const dark of [false,true]){if(dark)await page.getByRole('button',{name:'切换到深色模式',exact:true}).click();expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);await page.getByRole('region',{name:'任务控制结果待确认'}).screenshot({path:process.env.OPSWEAVE_SOURCE_SCREENSHOTS+'/control-'+width+'-'+(dark?'dark':'light')+'.png'})}
 await page.route('**/api/v1/integrations/workflows/runtime',route=>route.fulfill({json:runtimeFixture({...ack.task,generation:2,state:'STOPPED'})}))
 await page.getByRole('button',{name:'查询原控制结果',exact:true}).click()
 await expect(page.getByText('原启动回执已确认 · 固定 v1',{exact:true})).toBeVisible()
 await expect(page.locator('.workflow-runtime-actions')).toContainText('已停止')
 expect(queries).toEqual(['/api/v1/integrations/workflows/runtime/commands/'+submissions[0].requestId]);expect(submissions).toHaveLength(1)
 await expect(page.getByRole('button',{name:'启动持续处理',exact:true})).toBeEnabled()
})
test('missing control receipt permits an explicit resend of the unchanged original command',async({page})=>{
 await delegatedHost(page,{available:true});const submissions:any[]=[];let task:any=null
 await page.route('**/api/v1/integrations/workflows/runtime/start',async route=>{const body=route.request().postDataJSON();submissions.push(body);if(submissions.length===1)return route.abort('failed');const ack=controlAck(body,'START');task=ack.task;return route.fulfill({json:ack})})
 await page.route('**/api/v1/integrations/workflows/runtime/commands/*',route=>route.fulfill({status:404,json:{code:'NOT_FOUND'}}))
 await page.route('**/api/v1/integrations/workflows/runtime',route=>route.fulfill({json:runtimeFixture(task)}))
 await page.getByRole('button',{name:'启动持续处理',exact:true}).click()
 await page.getByRole('button',{name:'查询原控制结果',exact:true}).click()
 await expect(page.getByRole('button',{name:'按原标识重新提交',exact:true})).toBeVisible();expect(submissions).toHaveLength(1)
 await page.getByRole('button',{name:'按原标识重新提交',exact:true}).click()
 await expect(page.getByRole('region',{name:'任务控制结果待确认'})).toHaveCount(0)
 expect(submissions).toHaveLength(2);expect(submissions[1]).toEqual(submissions[0]);await expect(page.locator('.workflow-runtime-actions')).toContainText('运行中')
})
test('lost stop acknowledgement cannot stop a later generation when queried',async({page})=>{
 await delegatedHost(page,{available:true});await page.getByRole('button',{name:'启动持续处理',exact:true}).click();await expect(page.locator('.workflow-runtime-actions')).toContainText('运行中')
 let ack:any;const submissions:any[]=[]
 await page.route('**/api/v1/integrations/workflows/runtime/stop',route=>{const body=route.request().postDataJSON();submissions.push(body);ack=controlAck(body,'STOP');return route.abort('failed')})
 await page.route('**/api/v1/integrations/workflows/runtime/commands/*',route=>route.fulfill({json:ack}))
 await page.getByRole('button',{name:'停止任务',exact:true}).click()
 await expect(page.getByRole('button',{name:'停止任务',exact:true})).toBeDisabled()
 await page.route('**/api/v1/integrations/workflows/runtime',route=>route.fulfill({json:runtimeFixture({...ack.task,generation:3,state:'RUNNING'})}))
 await page.getByRole('button',{name:'查询原控制结果',exact:true}).click()
 await expect(page.getByText('原停止回执已确认 · 固定 v1',{exact:true})).toBeVisible()
 await expect(page.locator('.workflow-runtime-actions')).toContainText('运行中');expect(submissions).toHaveLength(1)
})
test('an acknowledgement of a different command cannot clear the pending control',async({page})=>{
 await delegatedHost(page,{available:true});let ack:any;let wrong=true
 await page.route('**/api/v1/integrations/workflows/runtime/start',route=>{ack=controlAck(route.request().postDataJSON(),'START');return route.abort('failed')})
 await page.route('**/api/v1/integrations/workflows/runtime/commands/*',route=>route.fulfill({json:wrong?{...ack,requestId:'10000000-0000-4000-8000-000000000999'}:ack}))
 await page.getByRole('button',{name:'启动持续处理',exact:true}).click();await page.getByRole('button',{name:'查询原控制结果',exact:true}).click()
 await expect(page.getByRole('alert')).toContainText('不符合契约');await expect(page.getByRole('region',{name:'任务控制结果待确认'})).toBeVisible();await expect(page.getByRole('button',{name:'启动持续处理',exact:true})).toBeDisabled()
 wrong=false;await page.getByRole('button',{name:'查询原控制结果',exact:true}).click();await expect(page.getByRole('region',{name:'任务控制结果待确认'})).toHaveCount(0)
})
test('a known absent control can be dismissed without claiming server cancellation',async({page})=>{
 await delegatedHost(page,{available:true});let posts=0
 await page.route('**/api/v1/integrations/workflows/runtime/start',route=>{posts++;return route.abort('failed')})
 await page.getByRole('button',{name:'启动持续处理',exact:true}).click();await page.getByRole('button',{name:'查询原控制结果',exact:true}).click();await page.getByRole('button',{name:'放弃本次确认',exact:true}).click()
 await expect(page.getByRole('region',{name:'任务控制结果待确认'})).toHaveCount(0);await expect(page.getByText('已放弃本次确认；这不会取消服务器命令，请重新读取当前状态。',{exact:true})).toBeVisible()
 await expect(page.getByRole('button',{name:'启动持续处理',exact:true})).toBeDisabled();expect(posts).toBe(1)
 await page.getByRole('button',{name:'刷新状态',exact:true}).click();await expect(page.getByRole('button',{name:'启动持续处理',exact:true})).toBeEnabled()
})
test('identity replacement clears a pending control and rejects a late receipt',async({page})=>{
 await delegatedHost(page,{available:true});let ack:any;let release:()=>void=()=>{};let queried=false
 const held=new Promise<void>(resolve=>{release=resolve})
 await page.route('**/api/v1/integrations/workflows/runtime/start',route=>{ack=controlAck(route.request().postDataJSON(),'START');return route.abort('failed')})
 await page.route('**/api/v1/integrations/workflows/runtime/commands/*',async route=>{queried=true;await held;await route.fulfill({json:ack}).catch(()=>{})})
 await page.getByRole('button',{name:'启动持续处理',exact:true}).click();await page.getByRole('button',{name:'查询原控制结果',exact:true}).click();await expect.poll(()=>queried).toBe(true)
 await page.getByRole('textbox',{name:'平台开发 Token（仅保存在当前标签页内存）'}).fill('replaced-control-token-long-enough');release()
 await expect(page.getByRole('region',{name:'任务控制结果待确认'})).toHaveCount(0);await expect(page.getByText('原启动回执已确认 · 固定 v1',{exact:true})).toHaveCount(0)
 expect(await page.evaluate(()=>JSON.stringify({...localStorage,...sessionStorage}))).not.toContain(ack.requestId)
})
