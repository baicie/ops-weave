import {test,expect,type Page} from '@playwright/test'
import {readFileSync,mkdirSync} from 'node:fs'
import {WORKFLOW_OPERATORS,TOKEN_OK,closeWorkflowInspector} from './helpers.ts'

// Protocol Fixtures only. Actual journal and main-environment checks run separately.
const example=(name:string)=>JSON.parse(readFileSync(new URL('../../../contracts/examples/v2/'+name+'.json',import.meta.url),'utf8'))
const digest='sha256:'+'a'.repeat(64),id='10000000-0000-4000-8000-000000000125',original='20000000-0000-4000-8000-000000000125'
const region=(p:Page)=>p.getByRole('region',{name:'运行质量与异常',exact:true}),iso=(ms:number)=>new Date(ms).toISOString().replace('.000Z','Z')
async function fixture(page:Page,mode='normal',gate?:Promise<void>){
 const now=Math.floor(Date.now()/1000)*1000,definition=example(mode==='metric'?'workflow-real-metric-definition':mode==='host'?'workflow-definition':'workflow-real-log-definition')
 if(mode==='host')definition.source={kind:'ZABBIX_HOST',instanceId:'fixture-source'}
 for(const n of definition.nodes)n.operatorDigest=WORKFLOW_OPERATORS.operators.find((o:any)=>o.type===n.type)!.digest
 const saved={definition,digest,state:'PUBLISHED',editVersion:0,updatedAt:iso(now),preview:null,layout:Object.fromEntries(definition.nodes.map((n:any,i:number)=>[n.id,{x:160,y:i*160}]))}
 const counts={input:3,accepted:1,rejected:0,filtered:0,deduplicated:2,outputExpected:1,confirmed:1,outputRejected:0,unknown:0,pending:0,repeatedOutput:0,late:1}
 let batches:any[]=[{id,observedAt:iso(now-1000),updatedAt:iso(now-1000),from:iso(now-81000),till:iso(now-21000),unit:'LOG_RECORD',state:'CONFIRMED',coverage:'WINDOW',sampleRate:null,counts,error:null,reconcilesBatchId:original}]
 if(mode==='metric')batches[0]={...batches[0],unit:'POINT',counts:{...counts,accepted:3,deduplicated:0,outputExpected:3,confirmed:3,repeatedOutput:2}}
 if(mode==='host')batches[0]={...batches[0],unit:'ENTITY',coverage:'PAGE',from:null,till:null,reconcilesBatchId:null,counts:Object.fromEntries(Object.keys(counts).map(k=>[k,k==='input'?3:k==='confirmed'?1:null]))}
 if(mode==='unknown')batches[0]={...batches[0],state:'UNKNOWN',error:'OUTPUT_UNCONFIRMED',counts:{...counts,confirmed:0,unknown:1}}
 if(mode==='empty')batches[0]={...batches[0],reconcilesBatchId:null,counts:Object.fromEntries(Object.keys(counts).map(k=>[k,0]))}
 if(mode==='source-down')batches=[]
 if(mode==='false-success')batches[0]={...batches[0],state:'UNKNOWN',error:'OUTPUT_UNCONFIRMED'}
 if(mode==='null-input')batches[0]={...batches[0],counts:{...counts,input:null}}
 const result={schemaVersion:'2.0',asOf:iso(now),reference:{id:definition.id,revision:definition.revision,digest},kind:mode==='host'?'HOST_SCAN':mode==='metric'?'METRIC_STREAM':'LOG_STREAM',task:{state:mode==='source-down'?'FAILED':'STOPPED',generation:1,updatedAt:iso(now),error:mode==='source-down'?'SOURCE_UNAVAILABLE':null,pendingBatchId:null},batches,truncated:false}
 let reads=0,posts=0;const lookups:string[]=[]
 await page.route(/\/api\/v1\/integrations\/workflows(?:\/|$)/,async route=>{
  const path=new URL(route.request().url()).pathname;if(route.request().method()!=='GET')posts++
  if(path.includes('/quality/')){if(path.includes('/batches/')){lookups.push(path);return route.fulfill({json:{...batches[0],id:mode==='foreign-id'?id:path.split('/').at(-1)}})}reads++;if(gate)await gate;if(mode==='fail'||mode==='forbidden')return route.fulfill({status:mode==='fail'?503:403,json:{error:'FORBIDDEN'}});return route.fulfill({json:mode==='private'?{...result,authority:{issuer:'private'}}:result})}
  if(path.endsWith('/workflows'))return route.fulfill({json:{schemaVersion:'2.0',storage:'postgres',operatorCatalog:WORKFLOW_OPERATORS,metricMappings:mode==='metric'?[example('metric-mapping-definition')]:[],drafts:{items:[],truncated:false},published:{items:[saved],truncated:false},models:[],modelsTruncated:false,zabbixSource:{instanceId:'fixture-source',mode:'fixture'},runs:{items:[],truncated:false}}})
  if(path.includes('/versions/'))return route.fulfill({json:saved})
  return route.fulfill({status:404,json:{error:'NOT_FOUND'}})
 })
 await page.goto('/#/integrations/workflows');await page.getByRole('textbox',{name:'平台开发 Token（仅保存在当前标签页内存）'}).fill(TOKEN_OK);await page.getByRole('button',{name:'查看',exact:true}).click();await closeWorkflowInspector(page);await page.getByText('运行质量与异常',{exact:true}).click();if(mode!=='forbidden')await expect(region(page)).toBeVisible()
 return {reads:()=>reads,posts:()=>posts,lookups}
}
for(const width of [1440,1024,390])for(const theme of ['light','dark'])test(`quality Fixture ${width} ${theme}`,async({page})=>{
 await page.setViewportSize({width,height:940});await page.addInitScript(t=>localStorage.setItem('opsweave.ui.theme',t),theme);const f=await fixture(page);await expect(region(page).locator('tbody tr')).toHaveCount(1);await expect(region(page).locator('tbody tr td').last()).toHaveText('100%');await region(page).getByRole('button',{name:'查看质量批次 '+id}).click();const detail=region(page).getByRole('article',{name:'质量批次详情'});await expect(detail).toContainText(original);await expect(detail).toContainText('—（未记录总体分母）');expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);expect(f.reads()).toBe(1);expect(f.posts()).toBe(0)
 if(process.env.OPSWEAVE_SOURCE_SCREENSHOTS){mkdirSync(process.env.OPSWEAVE_SOURCE_SCREENSHOTS,{recursive:true});await region(page).screenshot({path:process.env.OPSWEAVE_SOURCE_SCREENSHOTS+`/quality-${width}-${theme}.png`})}
})
for(const mode of ['fail','private','false-success','null-input'])test(`quality ${mode} preserves failure without automatic reads`,async({page})=>{const f=await fixture(page,mode);await expect(region(page).getByRole('alert')).toContainText(mode==='fail'?'HTTP':'不符合契约');await expect(region(page).locator('tbody tr')).toHaveCount(0);await page.waitForTimeout(5500);expect(f.reads()).toBe(1);expect(f.posts()).toBe(0);await region(page).getByRole('button',{name:'刷新质量'}).click();await expect.poll(f.reads).toBe(2)})
test('quality 403 clears private page and requires a fresh trusted session',async({page})=>{const f=await fixture(page,'forbidden');await expect(region(page)).toHaveCount(0);await expect(page.getByRole('alert')).toContainText('HTTP 403');await page.waitForTimeout(5500);expect(f.reads()).toBe(1);expect(f.posts()).toBe(0)})
test('unknown confirmation is zero, not a successful batch',async({page})=>{await fixture(page,'unknown');const row=region(page).locator('tbody tr');await expect(row).toContainText('结果未知');await expect(row.locator('td').last()).toHaveText('0%');await expect(row.locator('td').nth(8)).toHaveText('1')})
for(const mode of ['host','empty'])test(`quality ${mode} with missing or zero denominator shows dash`,async({page})=>{await fixture(page,mode);await expect(region(page).locator('tbody tr td').last()).toHaveText('—');await region(page).getByRole('button',{name:'查看质量批次 '+id}).click();await expect(region(page).getByRole('article')).toContainText(mode==='host'?'主机批次未保留转换分项':'0 / 0')})
test('metric late supplement separates repeated points from new points',async({page})=>{await fixture(page,'metric');await region(page).getByRole('button',{name:'查看质量批次 '+id}).click();const detail=region(page).getByRole('article');await expect(detail.locator('dt').filter({hasText:'重复提交的指标点'}).locator('+ dd')).toHaveText('2');await expect(detail.locator('dt').filter({hasText:'迟到条目 / 点'}).locator('+ dd')).toHaveText('1')})
test('source failure has an error and no invented zero input batch',async({page})=>{await fixture(page,'source-down');await expect(region(page).getByRole('alert')).toContainText('SOURCE_UNAVAILABLE');await expect(region(page).locator('tbody tr')).toHaveCount(0);await expect(region(page)).toContainText('暂无已记录批次')})
test('batch UUID lookup rejects malformed values and validates original identity',async({page})=>{const f=await fixture(page,'foreign-id');await region(page).getByRole('textbox',{name:'质量批次 UUID'}).fill('1-1-1-1-1');await region(page).getByRole('button',{name:'查询批次'}).click();await expect(region(page).getByRole('alert')).toContainText('完整批次 UUID');expect(f.lookups).toHaveLength(0);await region(page).getByRole('textbox',{name:'质量批次 UUID'}).fill(original);await region(page).getByRole('button',{name:'查询批次'}).click();await expect(region(page).getByRole('alert')).toContainText('不符合契约');expect(f.lookups).toHaveLength(1);await expect(region(page).getByRole('article')).toHaveCount(0)})
test('close and reopen preserves single default read',async({page})=>{const f=await fixture(page);await expect(region(page).locator('tbody tr')).toHaveCount(1);const summary=page.locator('details.workflow-runtime-section > summary').filter({hasText:'运行质量与异常'});await summary.click();await summary.click();await expect(region(page).locator('tbody tr')).toHaveCount(1);expect(f.reads()).toBe(1)})
test('identity clear discards late quality response',async({page})=>{let release!:()=>void;const gate=new Promise<void>(r=>release=r);const f=await fixture(page,'normal',gate);await expect(region(page).getByRole('status')).toContainText('正在读取');await page.getByRole('button',{name:'清除开发会话'}).click();release();await expect(region(page)).toHaveCount(0);expect(f.reads()).toBe(1);expect(f.posts()).toBe(0)})

