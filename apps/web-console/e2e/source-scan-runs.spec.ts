import { expect,test,type Page } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { TOKEN_OK } from './helpers.ts'
const sample=(name:string)=>JSON.parse(readFileSync(new URL(`../../../contracts/examples/${name}.json`,import.meta.url),'utf8'))
const root='/api/v1/integrations/zabbix'
const fixture=()=>structuredClone(sample('source-scan-run-page'))
async function enter(page:Page){await page.goto('/#/integrations/zabbix/runs');await expect(page.locator('[data-page="source-scan-runs"]')).toBeVisible();await page.getByRole('textbox',{name:'平台开发 Token（仅保存在当前标签页内存）'}).fill(TOKEN_OK)}
async function load(page:Page){await page.getByRole('button',{name:'读取扫描运行',exact:true}).click();await expect(page.locator('[data-scan-run-list]')).toBeVisible()}

test('explicit trace lists stored runs with counts, failure code and pinned version, then reads one by id',async({page})=>{
  const list=fixture(),read=sample('source-scan-run-read'),seen:string[]=[]
  await page.route('**/api/v1/**',route=>{const url=new URL(route.request().url());seen.push(url.pathname+url.search)
    if(url.pathname===`${root}/hosts/runs`)return route.fulfill({json:list})
    if(url.pathname===`${root}/hosts/runs/${read.run.syncRunId}`)return route.fulfill({json:read})
    return route.fulfill({status:404,json:{error:'NOT_FOUND'}})})
  await enter(page)
  expect(seen).toEqual([])
  await expect(page.getByRole('button',{name:'读取扫描运行',exact:true})).toBeEnabled()
  await load(page)
  const trace=page.locator('[data-scan-run-list]')
  await expect(trace).toContainText('host · SUCCEEDED · 2026-09-26T05:09:00.123456Z')
  await expect(trace).toContainText('页数 2 · 抓取 2 · 采纳 2 · 拒绝 0 · 完整快照 是 · labeled-fixture')
  await expect(trace).toContainText('SOURCE_FETCH_FAILED：Source request failed. Existing entities were kept.')
  await expect(trace).toContainText('映射版本 zabbix-host-v1 修订 1 · sha256:8b0f0b7c9d5e4a3b2c1d0e9f8a7b6c5d4e3f2a1b0c9d8e7f6a5b4c3d2e1f0a9b')
  await expect(trace).toContainText('边界 已验证 hostid 水位快照')
  await expect(trace.locator(`[data-scan-run-id="${list.items[0].syncRunId}"]`)).toContainText('SUCCEEDED')
  await expect(trace.locator(`[data-scan-run-id="${list.items[1].syncRunId}"]`)).toContainText('FAILED')
  await expect(trace).not.toContainText('failureReason')
  expect(seen[0]).toBe(`${root}/hosts/runs?limit=20`)
  await page.getByRole('textbox',{name:'扫描运行标识'}).fill(read.run.syncRunId)
  await page.getByRole('button',{name:'查询扫描运行',exact:true}).click()
  await expect(page.locator('[data-scan-run-read]')).toContainText(read.run.syncRunId)
  await expect(page.locator('[data-scan-run-read]')).toContainText('完整快照 是')
  await expect(page.locator('[data-scan-run-read]')).toContainText('边界 已验证 hostid 水位快照')
  expect(seen[1]).toBe(`${root}/hosts/runs/${read.run.syncRunId}`)
})

test('item scans keep their own path and paging echoes only the server cursor',async({page})=>{
  const first=fixture();first.objectType='item'
  first.items=first.items.map((run:any)=>({...run,objectType:'item',scanConsistency:'itemid-watermark-snapshot'}))
  first.hasMore=true;first.nextCursor='c2VydmVyLWN1cnNvcg'
  const second={...first,after:first.nextCursor,hasMore:false,nextCursor:null,items:[first.items[0]]}
  const seen:string[]=[]
  await page.route('**/api/v1/**',route=>{const url=new URL(route.request().url());seen.push(url.pathname+url.search)
    if(url.pathname===`${root}/items/runs`)return route.fulfill({json:url.searchParams.get('after')?second:first})
    return route.fulfill({status:404,json:{error:'NOT_FOUND'}})})
  await enter(page)
  await page.getByRole('button',{name:'Item 扫描',exact:true}).click()
  await load(page)
  await expect(page.locator('[data-scan-run-list]')).toContainText('item · SUCCEEDED')
  await expect(page.locator('[data-scan-run-list]')).toContainText('边界 已验证 itemid 水位快照')
  await page.getByRole('button',{name:'下一页扫描运行',exact:true}).click()
  await expect(page.locator('[data-scan-run-list] article')).toHaveCount(1)
  await expect(page.getByRole('button',{name:'下一页扫描运行',exact:true})).toBeDisabled()
  expect(seen).toEqual([`${root}/items/runs?limit=20`,`${root}/items/runs?limit=20&after=c2VydmVyLWN1cnNvcg`])
})

test('page size stays inside 10/20/50, clears the previous page and marks an empty trace explicitly',async({page})=>{
  const seen:string[]=[]
  await page.route('**/api/v1/**',route=>{const url=new URL(route.request().url());seen.push(url.pathname+url.search)
    if(url.pathname!==`${root}/hosts/runs`)return route.fulfill({status:404,json:{error:'NOT_FOUND'}})
    return route.fulfill({json:url.searchParams.get('limit')==='50'?{...fixture(),limit:50,hasMore:false,nextCursor:null,items:[]}:fixture()})})
  await enter(page)
  await load(page)
  await expect(page.locator('[data-scan-run-list]')).toContainText('本页 2 条')
  await page.getByRole('button',{name:'每页 50 条',exact:true}).click()
  await expect(page.locator('[data-scan-run-list]')).toHaveCount(0)
  await page.getByRole('button',{name:'读取扫描运行',exact:true}).click()
  await expect(page.locator('[data-scan-run-empty]')).toBeVisible()
  await expect(page.locator('[data-scan-run-list]')).toContainText('本页 0 条')
  await expect(page.getByRole('button',{name:'下一页扫描运行',exact:true})).toBeDisabled()
  expect(seen).toEqual([`${root}/hosts/runs?limit=20`,`${root}/hosts/runs?limit=50`])
})

for(const [name,damage] of [
  ['unknown failure code',(run:any)=>{run.items[1].failureCode='VENDOR_RAW_TEXT'}],
  ['edited failure summary',(run:any)=>{run.items[1].failureSummary='Host is down since 05:10 (raw vendor detail).'}],
  ['code without its fixed summary',(run:any)=>{delete run.items[1].failureSummary}],
  ['success without a complete snapshot',(run:any)=>{run.items[0].snapshotComplete=false}],
  ['run of another object type',(run:any)=>{run.items[0].objectType='item'}],
  ['unknown data mode',(run:any)=>{run.items[0].dataMode='live-zabbix'}],
  ['unknown boundary',(run:any)=>{run.items[0].scanConsistency='consistent-snapshot'}],
  ['boundary without its proof',(run:any)=>{delete run.items[0].scanConsistency}],
] as const)test(`${name} is refused and never rendered`,async({page})=>{
  const list=fixture();damage(list)
  await page.route('**/api/v1/**',route=>new URL(route.request().url()).pathname===`${root}/hosts/runs`?route.fulfill({json:list}):route.fulfill({status:404,json:{error:'NOT_FOUND'}}))
  await enter(page);await page.getByRole('button',{name:'读取扫描运行',exact:true}).click()
  await expect(page.getByRole('alert')).toContainText('扫描记录响应结构、范围或时间不正确')
  await expect(page.locator('[data-scan-run-list]')).toHaveCount(0)
  await expect(page.getByRole('alert')).not.toContainText('raw vendor detail')
})

test('the trace states the storage budget it holds rows against and never claims it pruned anything',async({page})=>{
  const list=fixture()
  await page.route('**/api/v1/**',route=>new URL(route.request().url()).pathname===`${root}/hosts/runs`?route.fulfill({json:list}):route.fulfill({status:404,json:{error:'NOT_FOUND'}}))
  await enter(page);await load(page)
  const budget=page.locator('[data-scan-run-retention]')
  await expect(budget).toContainText('本范围 1000 条')
  await expect(budget).toContainText('本租户 5000 条')
  await expect(budget).toContainText('当前本范围存储 2 条')
  await expect(budget).toContainText('读取不会清理记录')
  await expect(budget).toContainText('不会被删除')
})

for(const [name,damage] of [
  ['a budget above the published per-scope default',(page:any)=>{page.retention.maxRunsPerScope=1001}],
  ['a budget above the published per-tenant default',(page:any)=>{page.retention.maxRunsPerTenant=5001}],
  ['a scope budget larger than the tenant budget',(page:any)=>{page.retention.maxRunsPerScope=2000;page.retention.maxRunsPerTenant=1500}],
  ['a zero scope budget',(page:any)=>{page.retention.maxRunsPerScope=0}],
  ['a negative retained count',(page:any)=>{page.retention.retained=-1}],
  ['a retained count above the scope budget',(page:any)=>{page.retention.retained=1001}],
  ['a retained count smaller than the page it describes',(page:any)=>{page.retention.retained=1}],
  ['an incomplete budget',(page:any)=>{delete page.retention.retained}],
  ['an extra retention claim',(page:any)=>{page.retention.prunedAt='2026-09-26T05:00:00Z'}],
  ['a missing budget',(page:any)=>{delete page.retention}],
] as const)test(`a page with ${name} is refused and never rendered`,async({page})=>{
  const list=fixture();damage(list)
  await page.route('**/api/v1/**',route=>new URL(route.request().url()).pathname===`${root}/hosts/runs`?route.fulfill({json:list}):route.fulfill({status:404,json:{error:'NOT_FOUND'}}))
  await enter(page);await page.getByRole('button',{name:'读取扫描运行',exact:true}).click()
  await expect(page.getByRole('alert')).toContainText('扫描记录响应结构、范围或时间不正确')
  await expect(page.locator('[data-scan-run-list]')).toHaveCount(0)
})

test('missing source permission and an unknown run keep stable explanations without raw text',async({page})=>{
  let mode='forbidden'
  await page.route('**/api/v1/**',route=>{const url=new URL(route.request().url())
    if(url.pathname===`${root}/hosts/runs`)return mode==='forbidden'?route.fulfill({status:403,json:{error:'FORBIDDEN'}}):route.fulfill({json:fixture()})
    return route.fulfill({status:404,json:{error:'NOT_FOUND'}})})
  await enter(page)
  await page.getByRole('button',{name:'读取扫描运行',exact:true}).click()
  await expect(page.getByRole('alert')).toContainText('source.sync')
  await expect(page.getByRole('button',{name:'读取扫描运行',exact:true})).toBeEnabled()
  mode='ok';await load(page)
  await page.getByRole('textbox',{name:'扫描运行标识'}).fill('00000000-0000-4000-8000-000000000009')
  await page.getByRole('button',{name:'查询扫描运行',exact:true}).click()
  await expect(page.getByRole('alert')).toContainText('不会被返回')
  await expect(page.locator('[data-scan-run-list]')).toHaveCount(0)
  await expect(page.locator('[data-scan-run-read]')).toHaveCount(0)
})

test('identity change clears the trace, the read receipt and the entered identifier',async({page})=>{
  const read=sample('source-scan-run-read')
  await page.route('**/api/v1/**',route=>{const url=new URL(route.request().url())
    if(url.pathname===`${root}/hosts/runs`)return route.fulfill({json:fixture()})
    if(url.pathname===`${root}/hosts/runs/${read.run.syncRunId}`)return route.fulfill({json:read})
    return route.fulfill({status:404,json:{error:'NOT_FOUND'}})})
  await enter(page);await load(page)
  await page.getByRole('textbox',{name:'扫描运行标识'}).fill(read.run.syncRunId)
  await page.getByRole('button',{name:'查询扫描运行',exact:true}).click()
  await expect(page.locator('[data-scan-run-read]')).toBeVisible()
  await page.getByRole('textbox',{name:'平台开发 Token（仅保存在当前标签页内存）'}).fill('')
  await expect(page.locator('[data-scan-run-list]')).toHaveCount(0)
  await expect(page.locator('[data-scan-run-read]')).toHaveCount(0)
  await expect(page.getByRole('textbox',{name:'扫描运行标识'})).toHaveValue('')
  await expect(page.getByRole('button',{name:'读取扫描运行',exact:true})).toBeDisabled()
})
