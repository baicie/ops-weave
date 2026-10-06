import {test,expect,type Page} from '@playwright/test'
import {readFileSync,mkdirSync} from 'node:fs'
import {createHash} from 'node:crypto'
import {WORKFLOW_OPERATORS,TOKEN_OK,closeWorkflowInspector} from './helpers.ts'

// Every response and value in this file is an explicit browser Fixture.
const cpu=JSON.parse(readFileSync(new URL('../../../contracts/examples/v2/metric-mapping-definition.json',import.meta.url),'utf8'))
const definition=JSON.parse(readFileSync(new URL('../../../contracts/examples/v2/workflow-standard-metric-definition.json',import.meta.url),'utf8'))
const hash='sha256:'+'a'.repeat(64)
function digest(parts:string[]){return 'sha256:'+createHash('sha256').update(Buffer.concat(parts.map(p=>{const b=Buffer.from(p);return Buffer.concat([Buffer.from(b.length+':'),b])}))).digest('hex')}
const memory={...structuredClone(cpu),metricKey:'host.memory.available.ratio',sourceKey:'vm.memory.size[pavailable]',displayName:'Fixture memory',dimensionSchema:[],fixedDimensions:{},mappingPin:{id:'zabbix-memory-available',revision:1,digest:''}}
memory.mappingPin.digest=digest(['metric-mapping-v1',memory.mappingPin.id,memory.connector,memory.sourceKey,memory.metricKey,memory.displayName,memory.metricType,memory.unit,memory.valueType,memory.valueTransform,'1',memory.minimum,memory.maximum,'0','0'])
const unsupported={...structuredClone(cpu),metricType:'SUM'}
unsupported.mappingPin.digest=digest(['metric-mapping-v1',cpu.mappingPin.id,cpu.connector,cpu.sourceKey,cpu.metricKey,cpu.displayName,'SUM',cpu.unit,cpu.valueType,cpu.valueTransform,String(cpu.mappingPin.revision),cpu.minimum,cpu.maximum,'1','mode','1','mode','user'])
async function fixture(page:Page,mode='normal'){
 const calls:{path:string;body:any}[]=[];let saved:any=['missing','unsupported'].includes(mode)?{definition:structuredClone(definition),layout:Object.fromEntries(definition.nodes.map((n:any,i:number)=>[n.id,{x:180,y:40+i*160}])),digest:hash,state:'DRAFT',editVersion:1,updatedAt:'2026-10-04T00:00:00Z',preview:null}:null;let published:any=null
 if(mode==='unsupported')saved.definition.target.mappingPin=unsupported.mappingPin
 await page.route(/\/api\/v1\/integrations\/workflows(?:\/|$)/,async r=>{
  const path=new URL(r.request().url()).pathname,body=r.request().postDataJSON();calls.push({path,body});let result:any
  if(path.endsWith('/workflows')){const maps=mode==='missing'?[]:[structuredClone(mode==='unsupported'?unsupported:cpu),structuredClone(memory)];if(mode==='bad-definition')maps[0].minimum='0.5';result={schemaVersion:'2.0',storage:'memory',operatorCatalog:WORKFLOW_OPERATORS,metricMappings:maps,drafts:{items:saved?[saved]:[],truncated:false},published:{items:published?[published]:[],truncated:false},models:[],modelsTruncated:false,zabbixSource:{instanceId:'fixture-host',mode:'fixture'},runs:{items:[],truncated:false}}}
  else if(path.endsWith('/drafts')){saved={definition:body.definition,layout:body.layout,digest:hash,state:'DRAFT',editVersion:body.expectedEditVersion+1,updatedAt:new Date().toISOString(),preview:null};result=saved}
  else if(path.endsWith('/preview')){
   if(mode==='forbidden')return r.fulfill({status:403,json:{error:'FORBIDDEN'}})
   const mapping=saved.definition.target.metricKey===memory.metricKey?memory:cpu,raw=body.samples[0]
   const point={timestamp:'2026-10-04T00:00:00Z',metricKey:mapping.metricKey,value:'0.125',unit:mapping.unit,metricType:'GAUGE',dimensions:mapping.fixedDimensions,mappingPin:mapping.mappingPin}
   if(mode==='bad-unit')point.unit='forged';if(mode==='bad-dimensions')point.dimensions={mode:'idle'};if(mode==='bad-pin')point.mappingPin={...mapping.mappingPin,digest:'sha256:'+'0'.repeat(64)};if(mode==='bad-precision')point.value='1.0000000000000000000000000000000001'
   const receipt={id:'10000000-0000-4000-8000-000000000111',digest:hash,inputDigest:hash,origin:'MANUAL_SAMPLE',accepted:1,rejected:0,filtered:0,createdAt:new Date().toISOString()};saved.preview=receipt
   result={receipt,evaluation:{rows:[{index:0,status:'ACCEPTED',steps:saved.definition.nodes.map((n:any)=>({nodeId:n.id,type:n.type,status:'OK',values:['VALIDATE','OUTPUT'].includes(n.type)?point:raw,issues:[]}))}],accepted:1,rejected:0,filtered:0,dryRun:true,writesPerformed:false},retainedCount:1,missingRaw:0,truncated:false,sourceStatus:'MANUAL_SAMPLE'}
  }else if(path.endsWith('/publish')){published={...saved,state:'PUBLISHED',editVersion:0};result=published}
  else result=path.includes('/versions/')?published:saved
  await r.fulfill({json:result})
 })
 await page.goto('/#/integrations/workflows');await page.getByRole('textbox',{name:'平台开发 Token（仅保存在当前标签页内存）'}).fill(TOKEN_OK)
 return calls
}
async function output(page:Page){await closeWorkflowInspector(page);await page.getByRole('button',{name:'更多工作流操作',exact:true}).click();await page.getByRole('button',{name:'输出配置',exact:true}).click()}
async function standard(page:Page){await page.getByRole('button',{name:'新建工作流',exact:true}).click();await page.getByRole('button',{name:'＋ 指标样本模板',exact:true}).click();await output(page);await page.getByRole('combobox',{name:'指标定义',exact:true}).selectOption('metric-mapping:'+cpu.mappingPin.id)}
async function sampleAndSave(page:Page){await closeWorkflowInspector(page);await page.getByText('测试数据与结果',{exact:true}).click();await page.getByRole('button',{name:'填入 Fixture 指标示例',exact:true}).click();await page.getByRole('button',{name:'保存草稿',exact:true}).click();await expect(page.getByRole('button',{name:'预览当前草稿',exact:true})).toBeEnabled()}

for(const width of [1440,390])for(const theme of ['light','dark'])test(`standard metric Fixture ${width} ${theme}`,async({page})=>{
 await page.setViewportSize({width,height:940});await page.addInitScript(theme=>localStorage.setItem('opsweave.ui.theme',theme),theme)
 const calls=await fixture(page);await standard(page);const panel=page.getByLabel('标准指标配置')
 await expect(panel).toContainText(cpu.sourceKey);await expect(panel).toContainText('multiply:0.01');await expect(panel.getByRole('link',{name:cpu.metricKey,exact:true})).toHaveAttribute('href','#/modeling/metrics?metricKey='+cpu.metricKey)
 await panel.getByText('完整摘要',{exact:true}).click();await expect(panel).toContainText(cpu.mappingPin.digest)
 expect(calls.filter(c=>c.body)).toHaveLength(0);expect(calls.filter(c=>c.path.endsWith('/workflows'))).toHaveLength(1);expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true)
 if(process.env.OPSWEAVE_STANDARD_SCREENSHOTS){mkdirSync(process.env.OPSWEAVE_STANDARD_SCREENSHOTS,{recursive:true});await page.screenshot({path:process.env.OPSWEAVE_STANDARD_SCREENSHOTS+`/standard-${width}-${theme}.png`,fullPage:true})}
 await page.getByRole('combobox',{name:'选择工作流节点'}).selectOption('mapping');await expect(page.getByRole('textbox',{name:'来源字段 → sourceKey'})).toBeVisible();await expect(page.getByRole('textbox',{name:'来源字段 → metricKey'})).toHaveCount(0)
 await sampleAndSave(page);const save=calls.find(c=>c.path.endsWith('/drafts'))!.body;expect(save.definition.target).toEqual({kind:'METRIC',schemaVersion:'1.1',metricKey:cpu.metricKey,mappingPin:cpu.mappingPin});expect(Object.values(save.definition.nodes[1].config).sort()).toEqual(['sourceKey','timestamp','value']);expect(save.definition.edges).toHaveLength(3)
 await page.getByRole('button',{name:'预览当前草稿',exact:true}).click();await expect(page.getByRole('button',{name:'发布版本',exact:true})).toBeEnabled();await output(page);await closeWorkflowInspector(page)
 await expect(page.locator('[data-workflow-output]')).toContainText('"value": "0.125"');await expect(page.locator('[data-workflow-output]')).toContainText(cpu.mappingPin.digest);await expect(page.locator('[data-workflow-output]')).toContainText('"mode": "user"')
 expect(calls.find(c=>c.path.endsWith('/preview'))!.body.samples[0]).toEqual({timestamp:'2026-10-04T00:00:00Z',sourceKey:cpu.sourceKey,value:'12.5'})
 await page.getByRole('button',{name:'发布版本',exact:true}).click();await output(page);await expect(page.getByRole('combobox',{name:'指标定义',exact:true})).toBeDisabled();await closeWorkflowInspector(page)
 expect(calls.filter(c=>c.body)).toHaveLength(3)
})
for(const mode of ['bad-unit','bad-dimensions','bad-pin','bad-precision'])test('reject inconsistent normalized Fixture '+mode,async({page})=>{
 const calls=await fixture(page,mode);await standard(page);await sampleAndSave(page);await page.getByRole('button',{name:'预览当前草稿',exact:true}).click();await expect(page.getByRole('alert')).toContainText('标准指标结果与固定定义不一致');await expect(page.getByRole('button',{name:'发布版本',exact:true})).toBeDisabled();expect(calls.filter(c=>c.path.endsWith('/preview'))).toHaveLength(1);expect(calls.some(c=>c.path.endsWith('/publish'))).toBe(false)
})
test('reject changed Fixture definition before any edit or write',async({page})=>{
 const calls=await fixture(page,'bad-definition');await expect(page.getByRole('alert')).toContainText('不符合契约');await expect(page.getByRole('button',{name:'新建工作流',exact:true})).toHaveCount(0);expect(calls).toHaveLength(1)
})
for(const mode of ['missing','unsupported'])test('historical fixed definition stays readable without replacement or preview: '+mode,async({page})=>{
 const calls=await fixture(page,mode);await page.getByRole('button',{name:'编辑',exact:true}).click();await output(page);await expect(page.getByRole('combobox',{name:'指标定义',exact:true})).toHaveValue('metric-unavailable');await expect(page.getByLabel('标准指标配置')).toContainText(mode==='unsupported'?unsupported.mappingPin.digest:cpu.mappingPin.digest);await expect(page.getByRole('button',{name:'预览当前草稿',exact:true})).toBeDisabled();expect(calls.filter(c=>c.body)).toHaveLength(0)
})
test('changing a standard definition rebuilds edges and undo restores the previous chain',async({page})=>{
 const calls=await fixture(page);await standard(page);await closeWorkflowInspector(page)
 if(await page.getByRole('button',{name:'节点库',exact:true}).getAttribute('aria-expanded')==='false')await page.getByRole('button',{name:'节点库',exact:true}).click()
 await page.locator('.workflow-operator-palette').getByRole('button',{name:/去除空白/}).click();await expect(page.locator('.workflow-node')).toHaveCount(5);await output(page)
 await page.getByRole('combobox',{name:'指标定义',exact:true}).selectOption('metric-mapping:'+memory.mappingPin.id);await closeWorkflowInspector(page);await expect(page.locator('.workflow-node')).toHaveCount(4)
 await page.getByRole('button',{name:'更多工作流操作',exact:true}).click();await page.getByRole('button',{name:'撤销工作流修改',exact:true}).click();await expect(page.locator('.workflow-node')).toHaveCount(5);await page.getByRole('button',{name:'保存草稿',exact:true}).click()
 const saved=calls.find(c=>c.path.endsWith('/drafts'))!.body.definition;expect(saved.target.metricKey).toEqual(cpu.metricKey);expect(saved.nodes).toHaveLength(5);expect(saved.edges).toHaveLength(4);expect(saved.edges.every((e:any)=>saved.nodes.some((n:any)=>n.id===e.from)&&saved.nodes.some((n:any)=>n.id===e.to))).toBe(true)
})
test('authorization loss clears the standard editor and never retries preview',async({page})=>{
 const calls=await fixture(page,'forbidden');await standard(page);await sampleAndSave(page);await page.getByRole('button',{name:'预览当前草稿',exact:true}).click();await expect(page.getByRole('combobox',{name:'指标定义',exact:true})).toHaveCount(0);await expect(page.locator('.workflow-node')).toHaveCount(0);expect(calls.filter(c=>c.path.endsWith('/preview'))).toHaveLength(1);expect(calls.some(c=>c.path.endsWith('/publish'))).toBe(false)
})
