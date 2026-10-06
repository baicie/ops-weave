import {test,expect,type Page} from '@playwright/test'
import {readFileSync,mkdirSync} from 'node:fs'
import {WORKFLOW_OPERATORS,TOKEN_OK,closeWorkflowInspector} from './helpers.ts'
// Protocol Fixtures; actual PostgreSQL and main-environment verification are separate.
const example=(name:string)=>JSON.parse(readFileSync(new URL('../../../contracts/examples/v2/'+name+'.json',import.meta.url),'utf8'))
const digest='sha256:'+'a'.repeat(64),id='10000000-0000-4000-8000-000000000126',other='20000000-0000-4000-8000-000000000126'
const region=(p:Page)=>p.getByRole('region',{name:'检查统计',exact:true}),iso=(ms:number)=>new Date(ms).toISOString().replace('.000Z','Z')
async function fixture(page:Page,mode='partial',gate?:Promise<void>){
 const now=Math.floor(Date.now()/1000)*1000,host=mode.startsWith('host'),definition=example(host?'workflow-definition':'workflow-real-log-definition')
 if(host){const configuration=example('workflow-real-log-definition').source.configuration;definition.source={kind:'ZABBIX_HOST',instanceId:configuration.sourceId,configuration}}
 for(const n of definition.nodes)n.operatorDigest=WORKFLOW_OPERATORS.operators.find((o:any)=>o.type===n.type)!.digest
 const saved={definition,digest,state:'PUBLISHED',editVersion:0,updatedAt:iso(now),preview:null,layout:Object.fromEntries(definition.nodes.map((n:any,i:number)=>[n.id,{x:160,y:i*160}]))}
 const result:any={coverage:'PARTIAL',sampleRate:null,received:10,accepted:4,rejected:1,filtered:0,unknown:5,schemaMismatch:1,missingIdentity:0,invalidTimestamp:0,unitMismatch:null,nodes:definition.nodes.map((n:any)=>({nodeId:n.id,type:n.type,received:n.type==='OUTPUT'?4:5,accepted:n.type==='VALIDATE'||n.type==='OUTPUT'?4:5,rejected:n.type==='VALIDATE'?1:0,filtered:0,skipped:n.type==='OUTPUT'?1:0,issues:n.type==='VALIDATE'?{EMPTY_REQUIRED:1}:{}}))}
 const observation:any={id,reference:{id:definition.id,revision:definition.revision,digest},kind:'LOG_STREAM',generation:1,state:'REJECTED',from:iso(now-81000),till:iso(now-21000),startedAt:iso(now-1000),completedAt:iso(now),queueWaitMillis:null,relatedBatchId:null,error:'INVALID_SAMPLE',result}
 if(host){observation.kind='HOST_SCAN';observation.from=observation.till=null;observation.relatedBatchId=other;observation.state='CHECKED';observation.error=null;result.coverage='COMPLETE';Object.assign(result,{received:5,accepted:5,rejected:0,unknown:0,schemaMismatch:0});for(const node of result.nodes){node.received=node.accepted=5;node.rejected=node.skipped=0;node.issues={}}}
 if(mode==='host-private')observation.entityIds=[other]
 if(mode==='host-too-many'){result.received=result.accepted=6;for(const node of result.nodes)node.received=node.accepted=6}
 if(mode==='host-window')observation.from=iso(now-81000)
 if(mode==='source-down'){observation.state='SOURCE_FAILED';observation.error='SOURCE_UNAVAILABLE';result.coverage='UNAVAILABLE';result.nodes=[];for(const key of ['received','accepted','rejected','filtered','unknown','schemaMismatch','missingIdentity','invalidTimestamp','unitMismatch'])result[key]=null}
 if(mode==='empty'){observation.state='CHECKED';observation.error=null;result.coverage='COMPLETE';for(const key of ['received','accepted','rejected','filtered','unknown','schemaMismatch','missingIdentity','invalidTimestamp'])result[key]=0;for(const node of result.nodes){node.received=node.accepted=node.rejected=node.skipped=0;node.issues={}}}
 if(mode.startsWith('measured')){
  observation.sourceRead={attempts:1,completed:1,failed:0,received:10,failureCode:null,startedAt:observation.startedAt,completedAt:observation.completedAt}
  if(!mode.includes('success')){observation.state='SOURCE_FAILED';observation.error='SOURCE_CHANGED';result.coverage='UNAVAILABLE';result.nodes=[];for(const key of ['received','accepted','rejected','filtered','unknown','schemaMismatch','missingIdentity','invalidTimestamp','unitMismatch'])result[key]=null;Object.assign(observation.sourceRead,{completed:0,failed:1,received:null,failureCode:'UNIT_CHANGED'})}
  if(mode==='measured-private')observation.sourceRead.body='private source body'
  if(mode==='measured-invented-zero')observation.sourceRead.received=0
  if(mode==='measured-double-attempt')observation.sourceRead.attempts=2
  if(mode==='measured-wrong-error')observation.error='OUTPUT_REJECTED'
  if(mode==='measured-future')observation.sourceRead.completedAt=iso(now+1000)
  if(mode==='measured-success-wrong-count')observation.sourceRead.received=9
 }
 if(mode.startsWith('queued')){
  observation.queueWaitMillis=275;observation.dispatch={id:other,enqueuedAt:iso(now-1275),startedAt:observation.startedAt}
  if(mode==='queued-source')observation.sourceRead={attempts:1,completed:1,failed:0,received:10,failureCode:null,startedAt:observation.startedAt,completedAt:observation.completedAt}
  if(mode==='queued-private')observation.dispatch.tenantId='forged'
  if(mode==='queued-missing')delete observation.dispatch
  if(mode==='queued-null-dispatch')observation.dispatch=null
  if(mode==='queued-null-wait')observation.queueWaitMillis=null
  if(mode==='queued-negative')observation.queueWaitMillis=-1
  if(mode==='queued-float')observation.queueWaitMillis=275.5
  if(mode==='queued-unbounded')observation.queueWaitMillis=3600001
  if(mode==='queued-mismatch')observation.queueWaitMillis=0
  if(mode==='queued-backward')observation.dispatch.enqueuedAt=iso(now)
  if(mode==='queued-future')observation.dispatch.startedAt=iso(now)
  if(mode==='queued-id')observation.dispatch.id='1-1-1-1-1'
  if(mode==='queued-noncanonical')observation.dispatch.enqueuedAt='2026-10-05T01:00:00+01:00'
  if(mode==='queued-submillisecond'){observation.queueWaitMillis=0;observation.dispatch.enqueuedAt=iso(now-1000);observation.dispatch.startedAt=observation.dispatch.enqueuedAt.replace('Z','.000999Z');observation.startedAt=observation.dispatch.startedAt}
 }
 if(mode==='private')observation.body='private customer input'
 if(mode==='false-success'){observation.state='CHECKED';observation.error=null}
 if(mode==='null-input')result.received=null
 if(mode==='bad-node')result.nodes[0].type='CODE'
 if(mode==='wrong-window')observation.till=iso(now-20000)
 if(mode==='inconsistent')result.accepted=9
 if(mode==='invented-unit')result.unitMismatch=0
 const report={schemaVersion:'2.0',asOf:iso(now),reference:observation.reference,observations:[observation],truncated:false}
 let reads=0,posts=0;const lookups:string[]=[]
 await page.route(/\/api\/v1\/integrations\/workflows(?:\/|$)/,async route=>{
  const path=new URL(route.request().url()).pathname;if(route.request().method()!=='GET')posts++
  if(path.includes('/diagnostics')){if(path.endsWith('/diagnostics')){reads++;if(gate)await gate;if(mode==='fail'||mode==='forbidden')return route.fulfill({status:mode==='fail'?503:403,json:{error:'FORBIDDEN'}});return route.fulfill({json:report})}lookups.push(path);return route.fulfill({json:{...observation,id:mode==='foreign-id'?id:path.split('/').at(-1)}})}
  if(path.includes('/quality/'))return route.fulfill({json:{schemaVersion:'2.0',asOf:iso(now),reference:observation.reference,kind:observation.kind,task:null,batches:[],truncated:false}})
  if(path.endsWith('/workflows'))return route.fulfill({json:{schemaVersion:'2.0',storage:'postgres',operatorCatalog:WORKFLOW_OPERATORS,metricMappings:[],drafts:{items:[],truncated:false},published:{items:[saved],truncated:false},models:[],modelsTruncated:false,zabbixSource:{instanceId:'fixture-source',mode:'fixture'},runs:{items:[],truncated:false}}})
  if(path.includes('/versions/'))return route.fulfill({json:saved})
  return route.fulfill({status:404,json:{error:'NOT_FOUND'}})
 })
 await page.goto('/#/integrations/workflows');await page.getByRole('textbox',{name:'平台开发 Token（仅保存在当前标签页内存）'}).fill(TOKEN_OK);await page.getByRole('button',{name:'查看',exact:true}).click();await closeWorkflowInspector(page);await page.getByText('运行质量与异常',{exact:true}).click();await page.locator('details > summary').filter({hasText:/^检查统计$/}).click();if(mode!=='forbidden')await expect(region(page)).toBeVisible()
 return {reads:()=>reads,posts:()=>posts,lookups}
}
for(const width of [1440,1024,390])for(const theme of ['light','dark'])test(`diagnostics Fixture ${width} ${theme}`,async({page})=>{
 await page.setViewportSize({width,height:940});await page.addInitScript(t=>localStorage.setItem('opsweave.ui.theme',t),theme);const f=await fixture(page);await expect(region(page).locator('table').first().locator('tbody tr')).toHaveCount(1);const row=region(page).locator('table').first().locator('tbody tr');await expect(row.locator('td').nth(6)).toHaveText('5');await expect(row.locator('td').last()).toHaveText('—');await region(page).getByRole('button',{name:'查看检查 '+id}).click();await expect(region(page).getByRole('article',{name:'检查节点详情'})).toContainText('EMPTY_REQUIRED');expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);expect(f.reads()).toBe(1);expect(f.posts()).toBe(0)
 if(process.env.OPSWEAVE_SOURCE_SCREENSHOTS){mkdirSync(process.env.OPSWEAVE_SOURCE_SCREENSHOTS,{recursive:true});await region(page).screenshot({path:process.env.OPSWEAVE_SOURCE_SCREENSHOTS+`/diagnostics-${width}-${theme}.png`})}
})
for(const mode of ['fail','private','false-success','null-input','bad-node','wrong-window','inconsistent','invented-unit'])test(`diagnostics ${mode} has no automatic retry`,async({page})=>{const f=await fixture(page,mode);await expect(region(page).getByRole('alert')).toContainText(mode==='fail'?'HTTP':'不符合契约');await expect(region(page).locator('tbody tr')).toHaveCount(0);await page.waitForTimeout(1500);expect(f.reads()).toBe(1);expect(f.posts()).toBe(0);await region(page).getByRole('button',{name:'刷新检查'}).click();await expect.poll(f.reads).toBe(2)})
test('diagnostics 403 clears private page',async({page})=>{const f=await fixture(page,'forbidden');await expect(region(page)).toHaveCount(0);await expect(page.getByRole('alert')).toContainText('HTTP 403');expect(f.reads()).toBe(1);expect(f.posts()).toBe(0)})
for(const mode of ['source-down','empty'])test(`diagnostics ${mode} preserves null versus zero`,async({page})=>{await fixture(page,mode);const row=region(page).locator('table').first().locator('tbody tr');await expect(row.locator('td').nth(2)).toHaveText(mode==='empty'?'0':'—');await region(page).getByRole('button',{name:'查看检查 '+id}).click();await expect(region(page).getByRole('article')).toContainText(mode==='empty'?'完整':'节点数量不可用')})
test('diagnostics lookup validates requested identity',async({page})=>{const f=await fixture(page,'foreign-id');await region(page).getByRole('textbox',{name:'检查 UUID'}).fill('1-1-1-1-1');await region(page).getByRole('button',{name:'查询检查'}).click();await expect(region(page).getByRole('alert')).toContainText('完整检查 UUID');expect(f.lookups).toHaveLength(0);await region(page).getByRole('textbox',{name:'检查 UUID'}).fill(other);await region(page).getByRole('button',{name:'查询检查'}).click();await expect(region(page).getByRole('alert')).toContainText('不符合契约');await expect(region(page).getByRole('article')).toHaveCount(0)})
test('diagnostics close and reopen preserves one default request',async({page})=>{const f=await fixture(page);await expect(region(page).locator('tbody tr')).toHaveCount(1);const summary=page.locator('details > summary').filter({hasText:/^检查统计$/});await summary.click();await summary.click();await expect(region(page).locator('tbody tr')).toHaveCount(1);expect(f.reads()).toBe(1)})
test('diagnostics discards late response on identity clear',async({page})=>{let release!:()=>void;const gate=new Promise<void>(r=>release=r);const f=await fixture(page,'partial',gate);await expect(region(page).getByRole('status')).toContainText('正在读取');await page.getByRole('button',{name:'清除开发会话'}).click();release();await expect(region(page)).toHaveCount(0);expect(f.reads()).toBe(1);expect(f.posts()).toBe(0)})


for(const width of [1440,1024,390])for(const theme of ['light','dark'])test(`host diagnostics Fixture ${width} ${theme}`,async({page})=>{
 await page.setViewportSize({width,height:940});await page.addInitScript(t=>localStorage.setItem('opsweave.ui.theme',t),theme);const f=await fixture(page,'host');const row=region(page).locator('table').first().locator('tbody tr');await expect(row.locator('td').nth(2)).toHaveText('5');await expect(row.locator('td').nth(3)).toHaveText('5');await row.getByRole('button',{name:'查看检查 '+id}).click();const detail=region(page).getByRole('article');await expect(detail).toContainText('本次来源分页');await expect(detail).toContainText(other);await expect(detail).not.toContainText('读取窗口');expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);expect(f.reads()).toBe(1);expect(f.posts()).toBe(0)
})
for(const mode of ['host-private','host-too-many','host-window'])test(`${mode} fails closed`,async({page})=>{const f=await fixture(page,mode);await expect(region(page).getByRole('alert')).toContainText('不符合契约');await expect(region(page).locator('tbody tr')).toHaveCount(0);await page.waitForTimeout(1500);expect(f.reads()).toBe(1);expect(f.posts()).toBe(0)})
test('host original diagnostic UUID is a read only lookup',async({page})=>{const f=await fixture(page,'host');await region(page).getByRole('textbox',{name:'检查 UUID'}).fill(id);await region(page).getByRole('button',{name:'查询检查'}).click();await expect(region(page).getByRole('article')).toContainText('本次来源分页');expect(f.lookups).toHaveLength(1);expect(f.posts()).toBe(0)})

for(const width of [1440,1024,390])for(const theme of ['light','dark'])test(`measured source diagnostics ${width} ${theme}`,async({page})=>{
 await page.setViewportSize({width,height:940});await page.addInitScript(t=>localStorage.setItem('opsweave.ui.theme',t),theme);const f=await fixture(page,'measured-unit');const row=region(page).locator('table').first().locator('tbody tr');await expect(row.locator('td').nth(2)).toHaveText('1 / 1');await expect(row.locator('td').nth(3)).toHaveText('—');await row.getByRole('button',{name:'查看检查 '+id}).click();const detail=region(page).getByRole('article');await expect(detail.getByRole('definition').filter({hasText:'来源单位发生变化'})).toBeVisible();await expect(detail.getByLabel('来源读取统计')).toContainText('1 / 1');await expect(detail.getByLabel('来源读取统计')).toContainText('UNIT_CHANGED');expect(f.reads()).toBe(1);expect(f.posts()).toBe(0);expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true)
 if(process.env.OPSWEAVE_SOURCE_SCREENSHOTS)await detail.screenshot({path:process.env.OPSWEAVE_SOURCE_SCREENSHOTS+`/source-diagnostics-${width}-${theme}.png`})
})
for(const mode of ['measured-private','measured-invented-zero','measured-double-attempt','measured-wrong-error','measured-future','measured-success-wrong-count'])test(`${mode} is rejected without retry`,async({page})=>{const f=await fixture(page,mode);await expect(region(page).getByRole('alert')).toContainText('不符合契约');await expect(region(page).locator('tbody tr')).toHaveCount(0);expect(f.reads()).toBe(1);expect(f.posts()).toBe(0)})
test('measured successful source can still have rejected record validation',async({page})=>{const f=await fixture(page,'measured-success');const row=region(page).locator('table').first().locator('tbody tr');await expect(row.locator('td').nth(2)).toHaveText('0 / 1');await expect(row).toContainText('校验拒绝');await row.getByRole('button',{name:'查看检查 '+id}).click();await expect(region(page).getByLabel('来源读取统计')).toContainText('完整返回');expect(f.posts()).toBe(0)})

for(const width of [1440,1024,390])for(const theme of ['light','dark'])test(`measured dispatch diagnostics ${width} ${theme}`,async({page})=>{
 await page.setViewportSize({width,height:940});await page.addInitScript(t=>localStorage.setItem('opsweave.ui.theme',t),theme);const f=await fixture(page,'queued-source');await region(page).getByRole('button',{name:'查看检查 '+id}).click();const detail=region(page).getByRole('article');await expect(detail).toContainText('— / 275 ms');await expect(detail.getByLabel('调度时间')).toContainText('入队时间');await expect(detail.getByLabel('调度时间')).toContainText('开始执行');await expect(detail.getByLabel('来源读取统计')).toContainText('完整返回');expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);await page.locator('details > summary').filter({hasText:/^检查统计$/}).click();await page.locator('details > summary').filter({hasText:/^检查统计$/}).click();expect(f.reads()).toBe(1);expect(f.posts()).toBe(0)
 if(process.env.OPSWEAVE_SOURCE_SCREENSHOTS)await detail.screenshot({path:process.env.OPSWEAVE_SOURCE_SCREENSHOTS+`/dispatch-diagnostics-${width}-${theme}.png`})
})
for(const mode of ['queued-private','queued-missing','queued-null-dispatch','queued-null-wait','queued-negative','queued-float','queued-unbounded','queued-mismatch','queued-backward','queued-future','queued-id','queued-noncanonical'])test(`${mode} fails closed without retry`,async({page})=>{const f=await fixture(page,mode);await expect(region(page).getByRole('alert')).toContainText('不符合契约');await expect(region(page).locator('tbody tr')).toHaveCount(0);expect(f.reads()).toBe(1);expect(f.posts()).toBe(0)})
for(const mode of ['queued-submillisecond','partial'])test(`${mode} preserves measured zero versus unknown wait`,async({page})=>{const f=await fixture(page,mode);await region(page).getByRole('button',{name:'查看检查 '+id}).click();const detail=region(page).getByRole('article');await expect(detail).toContainText(mode==='partial'?'— / —':'— / 0 ms');await expect(detail.getByLabel('调度时间')).toHaveCount(mode==='partial'?0:1);expect(f.posts()).toBe(0)})
