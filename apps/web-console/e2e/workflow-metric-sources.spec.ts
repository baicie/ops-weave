import {test,expect,type Page} from '@playwright/test'
import {readFileSync,mkdirSync} from 'node:fs'
import {WORKFLOW_OPERATORS,TOKEN_OK,closeWorkflowInspector} from './helpers.ts'

// Explicit browser Fixtures; upstream and persistence are separately verified by Java HTTP tests.
const example=(name:string)=>JSON.parse(readFileSync(new URL('../../../contracts/examples/v2/'+name+'.json',import.meta.url),'utf8'))
const cpu=example('metric-mapping-definition'),real=example('workflow-real-metric-definition'),connection=example('source-connection-read'),hash='sha256:'+'a'.repeat(64)
async function fixture(page:Page,mode='normal',firstRead?:Promise<void>){
 const calls:{path:string;body:any}[]=[];let saved={definition:{...structuredClone(real),source:{kind:'MANUAL_SAMPLE',instanceId:'manual'}},layout:Object.fromEntries(real.nodes.map((n:any,i:number)=>[n.id,{x:180,y:40+i*160}])),digest:hash,state:'DRAFT',editVersion:1,updatedAt:new Date().toISOString(),preview:null as any}
 const choices=example('workflow-metric-source-page');choices.items[0].asOf=new Date(Date.now()-1000).toISOString();choices.items[0].expiresAt=new Date(Date.now()+(mode==='expired'?-1:600000)).toISOString();if(mode==='bad-pin')choices.items[0].source.metric.digest='sha256:'+'0'.repeat(64);if(mode==='empty')choices.items=[]
 await page.route(/\/api\/(?:v1\/integrations\/workflows|v2\/data-sources)(?:\/|$)/,async route=>{
  const path=new URL(route.request().url()).pathname,body=route.request().postDataJSON();calls.push({path,body})
  if(path.endsWith('/workflows'))return route.fulfill({json:{schemaVersion:'2.0',storage:'memory',operatorCatalog:WORKFLOW_OPERATORS,metricMappings:[cpu],drafts:{items:[saved],truncated:false},published:{items:[],truncated:false},models:[],modelsTruncated:false,zabbixSource:{instanceId:'fixture-host',mode:'fixture'},runs:{items:[],truncated:false}}})
  if(path==='/api/v2/data-sources')return route.fulfill({json:{schemaVersion:'2.0',storage:'memory',items:[connection.instance],truncated:false}})
  if(path.endsWith('/connection/history'))return route.fulfill({json:example('source-connection-history')})
  if(path.endsWith('/workflow-metrics')){if(firstRead&&calls.filter(c=>c.path.endsWith('/workflow-metrics')).length===1)await firstRead;if(mode==='unavailable')return route.fulfill({status:503,json:{error:'SOURCE_UNAVAILABLE'}});if(mode==='forbidden')return route.fulfill({status:403,json:{error:'FORBIDDEN'}});return route.fulfill({json:choices})}
  if(path.includes('/drafts/'))return route.fulfill({json:saved})
  if(path.endsWith('/drafts')){saved={...saved,definition:body.definition,layout:body.layout,editVersion:body.expectedEditVersion+1,preview:null};return route.fulfill({json:saved})}
  if(path.endsWith('/preview')){
   if(mode==='changed')return route.fulfill({status:409,json:{error:'SOURCE_CHANGED'}})
   const receipt={id:'10000000-0000-4000-8000-000000000112',digest:hash,inputDigest:hash,origin:'zabbix-jsonrpc',accepted:1,rejected:0,filtered:0,createdAt:new Date().toISOString()},raw={timestamp:'2026-10-04T00:00:00Z',sourceKey:cpu.sourceKey,value:'12.5'},output={timestamp:raw.timestamp,metricKey:cpu.metricKey,value:'0.125',unit:cpu.unit,metricType:'GAUGE',dimensions:cpu.fixedDimensions,mappingPin:cpu.mappingPin};saved.preview=receipt
   return route.fulfill({json:{receipt,evaluation:{rows:[{index:0,status:'ACCEPTED',steps:saved.definition.nodes.map((n:any)=>({nodeId:n.id,type:n.type,status:'OK',values:['VALIDATE','OUTPUT'].includes(n.type)?output:raw,issues:[]}))}],accepted:1,rejected:0,filtered:0,dryRun:true,writesPerformed:false},retainedCount:1,missingRaw:0,truncated:false,sourceStatus:'SUCCEEDED'}})
  }
  if(path.endsWith('/publish'))return route.fulfill({json:{...saved,state:'PUBLISHED',editVersion:0}})
  return route.fulfill({status:404,json:{error:'NOT_FOUND'}})
 })
 await page.goto('/#/integrations/workflows');await page.getByRole('textbox',{name:'平台开发 Token（仅保存在当前标签页内存）'}).fill(TOKEN_OK);await page.getByRole('button',{name:'编辑',exact:true}).click();await page.getByRole('button',{name:'数据输入节点 source',exact:true}).click();await page.getByRole('button',{name:'选择接入实例',exact:true}).click();await page.getByRole('combobox',{name:'工作流接入实例',exact:true}).selectOption(connection.instance.id);await page.getByRole('combobox',{name:'工作流连接版本',exact:true}).selectOption('1');return calls
}
async function bind(page:Page){await page.getByRole('combobox',{name:'工作流来源指标',exact:true}).selectOption(real.source.metric.itemId);await page.getByRole('button',{name:'使用此指标来源',exact:true}).click()}
for(const width of [1440,390])for(const theme of ['light','dark'])test(`fixed metric selector Fixture ${width} ${theme}`,async({page})=>{
 await page.setViewportSize({width,height:940});await page.addInitScript(theme=>localStorage.setItem('opsweave.ui.theme',theme),theme);const calls=await fixture(page)
 await expect(page.getByRole('combobox',{name:'工作流来源指标',exact:true})).toBeVisible();await bind(page);await expect(page.locator('.studio-inspector')).toContainText(cpu.sourceKey);await expect(page.locator('.studio-inspector')).toContainText(real.source.metric.digest)
 expect(calls.filter(c=>c.path.endsWith('/workflow-metrics'))).toHaveLength(1);expect(calls.filter(c=>c.body)).toHaveLength(0);expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true)
 if(process.env.OPSWEAVE_SOURCE_SCREENSHOTS){mkdirSync(process.env.OPSWEAVE_SOURCE_SCREENSHOTS,{recursive:true});await page.screenshot({path:process.env.OPSWEAVE_SOURCE_SCREENSHOTS+`/source-${width}-${theme}.png`,fullPage:true})}
 await closeWorkflowInspector(page);await page.getByRole('button',{name:'保存草稿',exact:true}).click();await expect(page.getByRole('button',{name:'预览当前草稿',exact:true})).toBeEnabled();await page.getByRole('button',{name:'预览当前草稿',exact:true}).click();await expect(page.getByRole('button',{name:'发布版本',exact:true})).toBeEnabled()
 const save=calls.find(c=>c.path.endsWith('/drafts'))!.body;expect(save.definition.source).toEqual(real.source);expect(save.definition.target).toEqual(real.target)
 const preview=calls.find(c=>c.path.endsWith('/preview'))!.body;expect(Object.keys(preview).sort()).toEqual(['id','revision','editVersion','digest','dryRun'].sort());expect(preview.dryRun).toBe(true)
 await page.getByRole('button',{name:'更多工作流操作',exact:true}).click();await page.getByRole('button',{name:'输出配置',exact:true}).click();await expect(page.getByRole('combobox',{name:'指标定义',exact:true})).toBeDisabled();await closeWorkflowInspector(page);await expect(page.locator('[data-workflow-output]')).toContainText('0.125');await expect(page.getByRole('textbox',{name:'工作流手工样本',exact:true})).toHaveCount(0)
 await page.getByRole('button',{name:'发布版本',exact:true}).click();expect(calls.filter(c=>c.body)).toHaveLength(3)
})
for(const mode of ['unavailable','bad-pin'])test('fixed metric selector refuses '+mode+' without automatic reread',async({page})=>{
 const calls=await fixture(page,mode);await expect(page.getByLabel('指标来源选择')).toContainText(mode==='bad-pin'?'摘要不一致':'固定连接当前不可用');expect(calls.filter(c=>c.path.endsWith('/workflow-metrics'))).toHaveLength(1);await page.getByRole('button',{name:'重新读取指标清单',exact:true}).click();await expect.poll(()=>calls.filter(c=>c.path.endsWith('/workflow-metrics')).length).toBe(2);expect(calls.filter(c=>c.body)).toHaveLength(0)
})
for(const mode of ['empty','expired'])test('fixed metric selector keeps '+mode+' without forged binding',async({page})=>{
 const calls=await fixture(page,mode);await expect(page.getByRole('button',{name:'使用此指标来源',exact:true})).toBeDisabled();if(mode==='empty')await expect(page.getByLabel('指标来源选择')).toContainText('没有与当前映射匹配');else {await expect(page.getByRole('combobox',{name:'工作流来源指标',exact:true}).locator('option').nth(1)).toHaveAttribute('disabled','');await expect(page.getByLabel('指标来源选择')).toContainText('发现记录已过期')}expect(calls.filter(c=>c.body)).toHaveLength(0)
})

test('closing an in-flight selector rejects its late response until explicit reread',async({page})=>{
 let release!:()=>void;const wait=new Promise<void>(resolve=>{release=resolve}),calls=await fixture(page,'normal',wait)
 await expect.poll(()=>calls.filter(c=>c.path.endsWith('/workflow-metrics')).length).toBe(1)
 await closeWorkflowInspector(page);release();await page.getByRole('button',{name:'数据输入节点 source',exact:true}).click()
 await expect(page.getByLabel('指标来源选择')).toContainText('读取已取消');await expect(page.getByRole('combobox',{name:'工作流来源指标',exact:true})).toHaveCount(0)
 expect(calls.filter(c=>c.path.endsWith('/workflow-metrics'))).toHaveLength(1)
 await page.getByRole('button',{name:'重新读取指标清单',exact:true}).click();await bind(page)
 expect(calls.filter(c=>c.path.endsWith('/workflow-metrics'))).toHaveLength(2);expect(calls.filter(c=>c.body)).toHaveLength(0)
})

test('identity clearing rejects a late source selection and clears private editor state',async({page})=>{
 let release!:()=>void;const wait=new Promise<void>(resolve=>{release=resolve}),calls=await fixture(page,'normal',wait)
 await expect.poll(()=>calls.filter(c=>c.path.endsWith('/workflow-metrics')).length).toBe(1)
 await closeWorkflowInspector(page);await page.getByRole('button',{name:'清除开发会话',exact:true}).click();release()
 await expect(page.locator('.workflow-node')).toHaveCount(0);await expect(page.getByRole('textbox',{name:'工作流名称',exact:true})).toHaveCount(0)
 expect(calls.filter(c=>c.path.endsWith('/workflow-metrics'))).toHaveLength(1);expect(calls.filter(c=>c.body)).toHaveLength(0)
})
test('fixed metric selector authorization loss clears editor and never retries',async({page})=>{const calls=await fixture(page,'forbidden');await expect(page.locator('.workflow-node')).toHaveCount(0);await expect(page.getByRole('combobox',{name:'工作流来源指标',exact:true})).toHaveCount(0);expect(calls.filter(c=>c.path.endsWith('/workflow-metrics'))).toHaveLength(1);expect(calls.filter(c=>c.body)).toHaveLength(0)})
test('real source drift preserves saved definition and denies publication',async({page})=>{const calls=await fixture(page,'changed');await bind(page);await closeWorkflowInspector(page);await page.getByRole('button',{name:'保存草稿',exact:true}).click();await page.getByRole('button',{name:'预览当前草稿',exact:true}).click();await expect(page.getByRole('alert')).toContainText('来源项的主机、完整键、类型或单位已变化');await expect(page.getByRole('button',{name:'发布版本',exact:true})).toBeDisabled();expect(calls.filter(c=>c.path.endsWith('/preview'))).toHaveLength(1)})
