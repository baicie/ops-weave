import { test, expect, type Page } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { createHash } from 'node:crypto'
import { TOKEN_OK, WORKFLOW_OPERATORS, closeWorkflowInspector } from './helpers.ts'

const example = JSON.parse(readFileSync(new URL('../../../contracts/examples/v2/workflow-log-definition.json',import.meta.url),'utf8'))
const hash = (parts:string[]) => { const h=createHash('sha256');for(const part of parts){const raw=Buffer.from(part);h.update(raw.length+':');h.update(raw)}return 'sha256:'+h.digest('hex') }
function digest(d:any){const parts=['opsweave-transform-v2',d.id,String(d.revision),d.name,d.source.kind,d.source.instanceId,'telemetry-output',d.target.kind,'1.0'];for(const n of d.nodes){parts.push(n.id,n.type,n.version,String(Object.keys(n.config).length));if(n.operatorDigest)parts.push('operator-pin-v2',n.operatorDigest);Object.entries(n.config).sort(([a],[b])=>a.localeCompare(b)).forEach(([key,value])=>parts.push(key,String(value)))}d.edges.forEach((e:any)=>parts.push(e.from,e.to));return hash(parts)}
const receipt = (d:any) => ({id:'10000000-0000-4000-8000-000000000071',digest:digest(d),inputDigest:hash(['fixture']),origin:'MANUAL_SAMPLE',accepted:1,rejected:0,filtered:0,createdAt:new Date().toISOString()})
async function fixture(page:Page,mode:'current'|'missing'|'mismatch'='current'){
 const definition=structuredClone(example);definition.id='fixture-operator-upgrade';definition.name='Fixture 旧算子流程'
 let saved:any={definition,digest:digest(definition),state:'DRAFT',editVersion:1,layout:Object.fromEntries(definition.nodes.map((n:any,i:number)=>[n.id,{x:180,y:40+i*160}])),updatedAt:new Date().toISOString(),preview:receipt(definition)}
 const calls:{path:string;method:string;body:any}[]=[]
 await page.route('**/api/v1/integrations/workflows**',async route=>{
  const path=new URL(route.request().url()).pathname,body=route.request().postDataJSON();calls.push({path,body,method:route.request().method()});let result:any
  if(path.endsWith('/workflows')){result={schemaVersion:'2.0',storage:'memory',drafts:{items:[saved],truncated:false},published:{items:[],truncated:false},models:[],modelsTruncated:false,zabbixSource:{instanceId:'',mode:'closed'},runs:{items:[],truncated:false}};if(mode!=='missing')result.operatorCatalog=structuredClone(WORKFLOW_OPERATORS);if(mode==='mismatch')result.operatorCatalog.operators[0].implementation='remote-code-v1'}
  else if(path.endsWith('/drafts')){saved={...saved,definition:body.definition,digest:digest(body.definition),layout:body.layout,editVersion:body.expectedEditVersion+1,preview:null};result=saved}
  else if(path.endsWith('/preview')){const r=receipt(saved.definition);saved.preview=r;result={receipt:r,evaluation:{rows:[{index:0,status:'ACCEPTED',steps:saved.definition.nodes.map((n:any)=>({nodeId:n.id,type:n.type,status:'OK',values:{body:'Fixture value'},issues:[]}))}],accepted:1,rejected:0,filtered:0,dryRun:true,writesPerformed:false},retainedCount:1,missingRaw:0,truncated:false,sourceStatus:'MANUAL_SAMPLE'}}
  else result=saved
  await route.fulfill({json:result})
 })
 await page.goto('/#/integrations/workflows');await page.getByRole('textbox',{name:'平台开发 Token（仅保存在当前标签页内存）'}).fill(TOKEN_OK)
 return {calls,definition}
}

for(const width of [1440,1024,390])test('explicit operator pin upgrade clears preview and supports undo at '+width,async({page})=>{
 await page.setViewportSize({width,height:1000});const {calls,definition}=await fixture(page)
 await page.getByRole('button',{name:'编辑',exact:true}).click()
 await expect(page.getByRole('button',{name:'发布版本',exact:true})).toBeDisabled()
 await page.getByRole('button',{name:'字段映射节点 mapping',exact:true}).click()
 await expect(page.getByRole('dialog',{name:'节点配置',exact:true})).toContainText('尚未固定')
 await closeWorkflowInspector(page)
 await page.getByRole('button',{name:'更多工作流操作',exact:true}).click();await page.getByRole('button',{name:'固定算子版本',exact:true}).click()
 expect(calls.filter(c=>c.method==='POST')).toHaveLength(0)
 await expect(page.getByRole('button',{name:'保存草稿',exact:true})).toBeEnabled()
 await page.getByRole('button',{name:'更多工作流操作',exact:true}).click();await page.getByRole('button',{name:'撤销工作流修改',exact:true}).click()
 await expect(page.getByRole('button',{name:'保存草稿',exact:true})).toBeDisabled()
 await page.getByRole('button',{name:'更多工作流操作',exact:true}).click();await page.getByRole('button',{name:'重做工作流修改',exact:true}).click()
 await page.getByRole('button',{name:'保存草稿',exact:true}).click()
 const write=calls.find(c=>c.path.endsWith('/drafts'))!.body
 expect(write.expectedEditVersion).toBe(1)
 expect(write.definition.nodes.map((n:any)=>n.operatorDigest)).toEqual(definition.nodes.map((n:any)=>WORKFLOW_OPERATORS.operators.find((o:any)=>o.type===n.type).digest))
 expect(digest(write.definition)).not.toBe(digest(definition))
 await expect(page.getByRole('button',{name:'发布版本',exact:true})).toBeDisabled()
 await page.getByText('测试数据与结果',{exact:true}).click()
 await page.getByRole('textbox',{name:'工作流手工样本',exact:true}).fill('[{"eventTime":"2026-10-03T00:00:00Z","body":"Fixture operator preview"}]')
 await page.getByRole('button',{name:'预览当前草稿',exact:true}).click()
 await expect(page.getByRole('button',{name:'发布版本',exact:true})).toBeEnabled()
 await page.getByRole('button',{name:'字段映射节点 mapping',exact:true}).click()
 await expect(page.getByRole('dialog',{name:'节点配置',exact:true})).toContainText(write.definition.nodes[1].operatorDigest)
 await closeWorkflowInspector(page)
 await page.getByRole('button',{name:'切换到深色模式',exact:true}).click()
 await page.getByRole('button',{name:'字段映射节点 mapping',exact:true}).click()
 expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true)
 await closeWorkflowInspector(page)
 expect(calls.filter(c=>c.path.endsWith('/workflows'))).toHaveLength(1)
 expect(calls.filter(c=>c.path.endsWith('/preview'))).toHaveLength(1)
})

test('missing catalog permits legacy reading and disables new creation',async({page})=>{
 const {calls}=await fixture(page,'missing');await expect(page.getByRole('status').filter({hasText:'算子目录未提供'})).toBeVisible()
 await expect(page.getByRole('button',{name:'新建工作流',exact:true})).toBeDisabled()
 await page.getByRole('button',{name:'编辑',exact:true}).click();await expect(page.getByRole('button',{name:'发布版本',exact:true})).toBeDisabled()
 await page.getByRole('button',{name:'更多工作流操作',exact:true}).click();await expect(page.getByRole('button',{name:'固定算子版本',exact:true})).toBeDisabled()
 expect(calls.filter(c=>c.path.endsWith('/workflows'))).toHaveLength(1);expect(calls.filter(c=>c.method==='POST')).toHaveLength(0)
})

test('unrecognized catalog is rejected without silent replacement or rereading',async({page})=>{
 const {calls}=await fixture(page,'mismatch');await expect(page.getByRole('alert')).toContainText('算子目录与当前控制台版本不兼容')
 await expect(page.getByRole('button',{name:'新建工作流',exact:true})).not.toBeVisible()
 await page.getByRole('button',{name:'切换到深色模式',exact:true}).click()
 await expect(page.getByRole('alert')).toContainText('算子目录与当前控制台版本不兼容')
 expect(calls).toHaveLength(1)
})
