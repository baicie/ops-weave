import { test, expect, type Page } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { TOKEN_OK, WORKFLOW_OPERATORS } from './helpers.ts'

const sample=JSON.parse(readFileSync(new URL('../../../contracts/examples/v2/workflow-log-definition.json',import.meta.url),'utf8'))
const reference=(e:any)=>({id:e.definition.id,revision:e.definition.revision,state:e.state,editVersion:e.editVersion,digest:e.digest})
const fullDigest='sha256:'+'d'.repeat(64)
const differences=[
 {section:'NAME',nodeId:null,key:'name',before:'Fixture 比较流程',after:'Fixture 下一版'},
 {section:'MAPPING',nodeId:'mapping',key:'source.metrics.fixture.complete.key',before:'metric.name',after:null},
 {section:'PARAMETER',nodeId:'trim',key:'value',before:null,after:''},
 {section:'OPERATOR',nodeId:'trim',key:'operatorDigest',before:null,after:fullDigest},
 {section:'SOURCE',nodeId:null,key:'connectionDigest',before:null,after:fullDigest},
 {section:'TARGET',nodeId:null,key:'digest',before:null,after:fullDigest},
 {section:'EDGE',nodeId:null,key:'mapping → trim',before:null,after:'present'},
 {section:'ORDER',nodeId:null,key:'nodes',before:'source → mapping → output',after:'source → mapping → trim → output'},
]
async function fixture(page:Page,mode='normal') {
 const d=structuredClone(sample);d.id='fixture-comparison';d.name='Fixture 比较流程'
 const entry=(definition:any,state:string,editVersion:number,digest:string)=>({definition,state,editVersion,digest,layout:Object.fromEntries(definition.nodes.map((n:any,i:number)=>[n.id,{x:200,y:i*160}])),updatedAt:'2026-10-03T00:00:00Z',preview:null})
 const base=entry(d,'PUBLISHED',0,'sha256:'+'a'.repeat(64)),candidate=entry({...structuredClone(d),revision:2,name:'Fixture 下一版'},'DRAFT',1,'sha256:'+'b'.repeat(64))
 let reads=0,comparisons=0,missing=false,release:(()=>void)|undefined
 const calls:{path:string;method:string;body:any}[]=[]
 await page.route('**/api/v1/integrations/workflows**',async route=>{
  const path=new URL(route.request().url()).pathname,body=route.request().postDataJSON();calls.push({path,body,method:route.request().method()})
  if(path.endsWith('/comparisons')){
   comparisons++
   if(mode==='network')return route.abort('failed')
   if(mode==='forbidden')return route.fulfill({status:403,json:{error:'FORBIDDEN'}})
   if(mode==='conflict'&&comparisons===1){candidate.editVersion=2;return route.fulfill({status:409,json:{error:'CONFLICT'}})}
   if(mode==='late')await new Promise<void>(resolve=>{release=resolve})
   const result:any={schemaVersion:'2.0',base:body.base,candidate:body.candidate,comparedAt:'2026-10-03T04:00:00Z',changes:structuredClone(differences)}
   if(mode==='wrong-reference')result.candidate.editVersion++
   if(mode==='duplicate')result.changes.push(result.changes[0])
   if(mode==='unknown')result.secret='forged'
   if(mode==='bad-value')result.changes[0].before={nested:'forged'}
   if(mode==='empty')result.changes=[]
   await route.fulfill({json:result}).catch(()=>{})
  }else if(path.endsWith('/workflows')){
   reads++;await route.fulfill({json:{schemaVersion:'2.0',storage:'memory',drafts:{items:missing?[]:[candidate],truncated:false},published:{items:missing?[]:[base],truncated:false},models:[],modelsTruncated:false,zabbixSource:{instanceId:'',mode:'closed'},operatorCatalog:WORKFLOW_OPERATORS,runs:{items:[],truncated:false}}})
  }else await route.fulfill({json:path.endsWith('/2')?candidate:base})
 })
 await page.goto('/#/integrations/workflows');await page.getByRole('textbox',{name:'平台开发 Token（仅保存在当前标签页内存）'}).fill(TOKEN_OK)
 await page.getByRole('button',{name:'查看版本',exact:true}).click()
 await page.getByRole('button',{name:'比较版本',exact:true}).first().click()
 await expect(page.getByRole('dialog',{name:'版本比较',exact:true})).toBeVisible()
 return {calls,base,candidate,reads:()=>reads,release:()=>release?.(),missing:(value:boolean)=>{missing=value}}
}
for(const width of [1440,1024,390])test('explicit version comparison preserves complete values and drawer layout at '+width,async({page})=>{
 await page.setViewportSize({width,height:1000});const f=await fixture(page),panel=page.getByRole('dialog',{name:'版本比较',exact:true})
 expect(f.calls.filter(c=>c.method==='POST')).toHaveLength(0)
 await expect(panel.getByLabel('基准版本',{exact:true})).toHaveValue('PUBLISHED:1');await expect(panel.getByLabel('对比版本',{exact:true})).toHaveValue('DRAFT:2')
 await panel.getByRole('button',{name:'比较所选版本',exact:true}).click()
 await expect(panel.getByRole('status')).toContainText('共 8 项变化')
 expect(f.calls.filter(c=>c.method==='POST').map(c=>({path:c.path,body:c.body}))).toEqual([{path:'/api/v1/integrations/workflows/comparisons',body:{base:reference(f.base),candidate:reference(f.candidate)}}])
 await expect(panel.getByRole('table',{name:'字段映射差异'})).toContainText('source.metrics.fixture.complete.key')
 await expect(panel.getByRole('table',{name:'算子版本差异'})).toContainText(fullDigest)
 await expect(panel.getByRole('table',{name:'节点参数差异'})).toContainText('未设置');await expect(panel.getByRole('table',{name:'节点参数差异'})).toContainText('空字符串')
 const bounds=await panel.boundingBox();expect(bounds!.x+bounds!.width).toBeCloseTo(width,0);expect(bounds!.y).toBe(0);expect(bounds!.height).toBe(1000)
 const close=panel.getByRole('button',{name:'关闭版本比较',exact:true}),buttonBounds=(await close.boundingBox())!,iconBounds=(await close.locator('svg').boundingBox())!
 expect(Math.abs(buttonBounds.x+buttonBounds.width/2-iconBounds.x-iconBounds.width/2)).toBeLessThan(1)
 expect(Math.abs(buttonBounds.y+buttonBounds.height/2-iconBounds.y-iconBounds.height/2)).toBeLessThan(1)
 expect(await panel.getAttribute('aria-modal')).toBe('false');expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true)
 await page.keyboard.press('Escape');await expect(panel).not.toBeVisible();await expect(page.getByRole('button',{name:'比较版本',exact:true}).first()).toBeFocused()
 await page.getByRole('button',{name:'切换到深色模式',exact:true}).click();await page.getByRole('button',{name:'比较版本',exact:true}).first().click();await panel.getByRole('button',{name:'比较所选版本',exact:true}).click()
 await expect(panel.getByRole('table',{name:'输出与模型差异'})).toContainText(fullDigest);expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true)
 expect(f.reads()).toBe(1);expect(f.calls.filter(c=>c.method==='POST')).toHaveLength(2)
})
for(const mode of ['wrong-reference','duplicate','unknown','bad-value'])test('rejects malformed comparison response: '+mode,async({page})=>{
 const f=await fixture(page,mode),panel=page.getByRole('dialog',{name:'版本比较',exact:true});await panel.getByRole('button',{name:'比较所选版本',exact:true}).click()
 await expect(panel.getByRole('alert')).toContainText('版本差异响应不符合所选版本');await expect(panel.getByRole('table')).toHaveCount(0)
 expect(f.calls.filter(c=>c.method==='POST')).toHaveLength(1);expect(f.reads()).toBe(1)
})
test('stale editing version requires explicit refresh and another comparison',async({page})=>{
 const f=await fixture(page,'conflict'),panel=page.getByRole('dialog',{name:'版本比较',exact:true});await panel.getByRole('button',{name:'比较所选版本',exact:true}).click()
 await expect(panel.getByRole('alert')).toContainText('所选草稿已变化');expect(f.reads()).toBe(1)
 await panel.getByRole('button',{name:'刷新版本选项',exact:true}).click();await expect(panel.getByLabel('对比版本',{exact:true})).toContainText('编辑 2')
 expect(f.calls.filter(c=>c.method==='POST')).toHaveLength(1);expect(f.reads()).toBe(2)
 await panel.getByRole('button',{name:'比较所选版本',exact:true}).click();await expect(panel.getByRole('status')).toContainText('共 8 项变化')
 expect(f.calls.filter(c=>c.method==='POST')[1].body.candidate.editVersion).toBe(2)
})
test('missing options can be refreshed without either selection or automatic comparison',async({page})=>{
 const f=await fixture(page),panel=page.getByRole('dialog',{name:'版本比较',exact:true});f.missing(true);await panel.getByRole('button',{name:'刷新版本选项',exact:true}).click()
 await expect(panel.getByLabel('基准版本',{exact:true})).toHaveValue('');await expect(panel.getByLabel('对比版本',{exact:true})).toHaveValue('')
 await expect(panel.getByRole('button',{name:'比较所选版本',exact:true})).toBeDisabled()
 f.missing(false);await panel.getByRole('button',{name:'刷新版本选项',exact:true}).click();await expect(panel.getByLabel('基准版本',{exact:true}).locator('option')).toHaveCount(3)
 await expect(panel.getByLabel('基准版本',{exact:true})).toHaveValue('');expect(f.reads()).toBe(3);expect(f.calls.filter(c=>c.method==='POST')).toHaveLength(0)
})
test('changing selection cancels and ignores a late comparison response',async({page})=>{
 const f=await fixture(page,'late'),panel=page.getByRole('dialog',{name:'版本比较',exact:true});await panel.getByRole('button',{name:'比较所选版本',exact:true}).click()
 await expect(panel.getByRole('button',{name:'正在比较…',exact:true})).toBeDisabled()
 await panel.getByLabel('对比版本',{exact:true}).selectOption('PUBLISHED:1');f.release()
 await expect(panel.getByRole('button',{name:'比较所选版本',exact:true})).toBeEnabled();await expect(panel.getByRole('table')).toHaveCount(0)
 expect(f.calls.filter(c=>c.method==='POST')).toHaveLength(1)
})
test('empty differences are shown as processing configuration equality',async({page})=>{
 await fixture(page,'empty');const panel=page.getByRole('dialog',{name:'版本比较',exact:true});await panel.getByRole('button',{name:'比较所选版本',exact:true}).click();await expect(panel.getByRole('status')).toContainText('处理配置相同');await expect(panel.getByRole('table')).toHaveCount(0)
})
test('network failure retains choices and only retries on explicit comparison',async({page})=>{
 const f=await fixture(page,'network'),panel=page.getByRole('dialog',{name:'版本比较',exact:true});await panel.getByRole('button',{name:'比较所选版本',exact:true}).click()
 await expect(panel.getByRole('alert')).toContainText('版本数据读取失败');await expect(panel.getByRole('alert')).not.toContainText('提交结果待确认')
 await expect(panel.getByLabel('对比版本',{exact:true})).toHaveValue('DRAFT:2');expect(f.calls.filter(c=>c.method==='POST')).toHaveLength(1)
 await panel.getByRole('button',{name:'比较所选版本',exact:true}).click();await expect(panel.getByRole('alert')).toContainText('版本数据读取失败')
 expect(f.calls.filter(c=>c.method==='POST')).toHaveLength(2);expect(f.reads()).toBe(1)
})
test('forbidden comparison clears private versions and the drawer',async({page})=>{
 const f=await fixture(page,'forbidden'),panel=page.getByRole('dialog',{name:'版本比较',exact:true});await panel.getByRole('button',{name:'比较所选版本',exact:true}).click()
 await expect(panel).not.toBeVisible();await expect(page.getByRole('button',{name:'比较版本',exact:true})).toHaveCount(0)
 expect(f.calls.filter(c=>c.method==='POST')).toHaveLength(1);expect(f.reads()).toBe(1)
})
test('editor comparison uses saved version and is disabled for local changes',async({page})=>{
 const f=await fixture(page),panel=page.getByRole('dialog',{name:'版本比较',exact:true});await panel.getByRole('button',{name:'关闭版本比较',exact:true}).click()
 await page.getByRole('button',{name:'编辑流程',exact:true}).click();await expect(page.getByLabel('工作流名称',{exact:true})).toHaveValue('Fixture 下一版')
 await page.getByRole('button',{name:'更多工作流操作',exact:true}).click();await page.getByRole('button',{name:'比较版本',exact:true}).click()
 await expect(panel).toBeVisible();await panel.getByRole('button',{name:'比较所选版本',exact:true}).click();await expect(panel.getByRole('status')).toContainText('共 8 项变化')
 await page.keyboard.press('Escape');await expect(panel).not.toBeVisible();await expect(page.getByRole('button',{name:'更多工作流操作',exact:true})).toBeFocused()
 await page.getByLabel('工作流名称',{exact:true}).fill('Fixture 尚未保存的修改');await page.getByRole('button',{name:'更多工作流操作',exact:true}).click()
 await expect(page.getByRole('button',{name:'比较版本',exact:true})).toBeDisabled()
 expect(f.calls.filter(c=>c.method==='POST')).toHaveLength(1)
})
test('cached page closes the comparison on navigation without rereading versions',async({page})=>{
 await page.addInitScript(()=>localStorage.setItem('opsweave.ui.layout','tabs'))
 await page.route('**/api/v1/**',route=>route.fulfill({status:503,json:{error:'UNAVAILABLE'}}))
 const f=await fixture(page),panel=page.getByRole('dialog',{name:'版本比较',exact:true});await panel.getByRole('button',{name:'比较所选版本',exact:true}).click();await expect(panel.getByRole('status')).toContainText('共 8 项变化')
 await page.evaluate(()=>{location.hash='#/start'});await expect(panel).not.toBeVisible()
 await page.getByRole('tablist',{name:'已打开页面'}).getByRole('tab',{name:'数据工作流',exact:true}).click();await expect(panel).not.toBeVisible()
 await page.getByRole('button',{name:'比较版本',exact:true}).first().click();await expect(panel).toBeVisible();await expect(panel.getByRole('table')).toHaveCount(0)
 expect(f.reads()).toBe(1);expect(f.calls.filter(c=>c.method==='POST')).toHaveLength(1)
})
