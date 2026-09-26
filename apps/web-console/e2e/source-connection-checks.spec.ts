import { expect,test,type Page } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { TOKEN_OK } from './helpers.ts'
const sample=(name:string)=>JSON.parse(readFileSync(new URL(`../../../contracts/examples/${name}.json`,import.meta.url),'utf8'))
const root='/api/v1/integrations/zabbix/connection-checks'
const receiptFixture=()=>structuredClone(sample('source-connection-check-receipt'))
const pageFixture=()=>structuredClone(sample('source-connection-check-page'))
async function enter(page:Page){await page.goto('/#/integrations/zabbix/runs');await expect(page.locator('[data-page="source-scan-runs"]')).toBeVisible();await page.getByRole('textbox',{name:'平台开发 Token（仅保存在当前标签页内存）'}).fill(TOKEN_OK)}
async function probe(page:Page){await page.getByRole('button',{name:'来源连接自检',exact:true}).click()}
async function read(page:Page){await page.getByRole('button',{name:'读取自检回执',exact:true}).click()}

test('explicit self-check posts once, renders the labeled receipt and reads bounded receipts back',async({page})=>{
  const receipt=receiptFixture(),list=pageFixture(),seen:string[]=[]
  await page.route('**/api/v1/**',route=>{const url=new URL(route.request().url());seen.push(`${route.request().method()} ${url.pathname}${url.search}`)
    if(url.pathname===root&&route.request().method()==='POST')return route.fulfill({json:receipt})
    if(url.pathname===root&&route.request().method()==='GET')return route.fulfill({json:list})
    return route.fulfill({status:404,json:{error:'NOT_FOUND'}})})
  await enter(page)
  expect(seen).toEqual([])
  await expect(page.getByRole('button',{name:'来源连接自检',exact:true})).toBeEnabled()
  await probe(page)
  const panel=page.locator('[data-connection-check]')
  await expect(panel).toContainText(receipt.check.checkId)
  await expect(panel).toContainText('来源模式 labeled-fixture')
  await expect(panel).toContainText('状态码 labeled-fixture')
  await expect(panel).toContainText('无版本声明')
  expect(seen).toEqual([`POST ${root}`])
  await read(page)
  const listRegion=page.locator('[data-connection-check-list]')
  await expect(listRegion).toContainText('最近 2 条（上限 20）')
  await expect(listRegion).toContainText(receipt.check.checkId)
  await expect(listRegion).toContainText(list.items[1].checkId)
  await expect(listRegion).toContainText('状态码 unreachable')
  await expect(listRegion).toContainText('无版本声明')
  await expect(page.locator('[data-connection-check]')).toHaveCount(0)
  expect(seen).toEqual([`POST ${root}`,`GET ${root}?limit=20`])
})

test('receipt count stays inside 10/20/50, clears the previous list and marks an empty history explicitly',async({page})=>{
  const seen:string[]=[]
  await page.route('**/api/v1/**',route=>{const url=new URL(route.request().url());seen.push(`${route.request().method()} ${url.pathname}${url.search}`)
    if(url.pathname!==root||route.request().method()!=='GET')return route.fulfill({status:404,json:{error:'NOT_FOUND'}})
    return route.fulfill({json:url.searchParams.get('limit')==='50'?{...pageFixture(),limit:50,items:[]}:pageFixture()})})
  await enter(page)
  await read(page)
  await expect(page.locator('[data-connection-check-list]')).toContainText('最近 2 条（上限 20）')
  await page.getByRole('button',{name:'最近 50 条',exact:true}).click()
  await expect(page.locator('[data-connection-check-list]')).toHaveCount(0)
  await read(page)
  await expect(page.locator('[data-connection-check-empty]')).toBeVisible()
  await expect(page.locator('[data-connection-check-list]')).toContainText('最近 0 条（上限 50）')
  expect(seen).toEqual([`GET ${root}?limit=20`,`GET ${root}?limit=50`])
})

for(const [name,damage] of [
  ['unreachable receipt that claims a version',(check:any)=>{check.reachable=false;check.statusCode='unreachable';check.reportedVersion='7.0.0'}],
  ['unknown status code',(check:any)=>{check.statusCode='vendor says ok'}],
  ['unreachable status on a reachable probe',(check:any)=>{check.statusCode='not-configured'}],
  ['fixture receipt that claims a vendor version',(check:any)=>{check.reportedVersion='7.0.4'}],
  ['unknown source mode',(check:any)=>{check.dataMode='live-zabbix'}],
  ['receipt of another source',(check:any)=>{check.sourceInstanceId='zabbix-2'}],
  ['offset timestamp instead of UTC',(check:any)=>{check.checkedAt='2026-09-26T14:05:00.123456+08:00'}],
  ['extra vendor text',(check:any)=>{check.vendorMessage='Host is down since 14:05 (raw vendor detail).'}],
] as const)test(`${name} is refused and never rendered`,async({page})=>{
  const receipt=receiptFixture();damage(receipt.check)
  await page.route('**/api/v1/**',route=>new URL(route.request().url()).pathname===root&&route.request().method()==='POST'?route.fulfill({json:receipt}):route.fulfill({status:404,json:{error:'NOT_FOUND'}}))
  await enter(page);await probe(page)
  await expect(page.locator('[data-connection-check-error]')).toContainText('来源连接自检响应结构、范围或时间不正确')
  await expect(page.locator('[data-connection-check]')).toHaveCount(0)
  await expect(page.locator('[data-connection-check-error]')).not.toContainText('raw vendor detail')
})

for(const [name,damage] of [
  ['limit that does not echo the request',(list:any)=>{list.limit=50}],
  ['receipts above the requested limit',(list:any)=>{list.items=Array.from({length:21},()=>list.items[0])}],
  ['duplicate receipt identifier',(list:any)=>{list.items=[list.items[0],{...list.items[0]}]}],
  ['receipts that are not newest first',(list:any)=>{list.items=[list.items[1],list.items[0]]}],
  ['receipt without its actor',(list:any)=>{delete list.items[0].actor}],
  ['receipt of another source inside the history',(list:any)=>{list.items[0].sourceInstanceId='zabbix-2'}],
] as const)test(`receipt history with ${name} is refused and never rendered`,async({page})=>{
  const list=pageFixture();damage(list)
  await page.route('**/api/v1/**',route=>new URL(route.request().url()).pathname===root&&route.request().method()==='GET'?route.fulfill({json:list}):route.fulfill({status:404,json:{error:'NOT_FOUND'}}))
  await enter(page);await read(page)
  await expect(page.locator('[data-connection-check-error]')).toContainText('来源连接自检响应结构、范围或时间不正确')
  await expect(page.locator('[data-connection-check-list]')).toHaveCount(0)
})

test('missing source permission, an unavailable source and an invalid query keep stable explanations',async({page})=>{
  let mode='forbidden'
  await page.route('**/api/v1/**',route=>{const url=new URL(route.request().url())
    if(url.pathname!==root)return route.fulfill({status:404,json:{error:'NOT_FOUND'}})
    if(route.request().method()==='POST')return mode==='forbidden'?route.fulfill({status:403,json:{error:'FORBIDDEN'}}):route.fulfill({status:503,json:{error:'UNCONFIGURED'}})
    return route.fulfill({status:400,json:{error:'INVALID_REQUEST'}})})
  await enter(page)
  await probe(page)
  await expect(page.locator('[data-connection-check-error]')).toContainText('source.sync')
  await expect(page.locator('[data-connection-check]')).toHaveCount(0)
  mode='unconfigured';await probe(page)
  await expect(page.locator('[data-connection-check-error]')).toContainText('不会回退成 fixture 成功')
  await read(page)
  await expect(page.locator('[data-connection-check-error]')).toContainText('只接受 limit（1–50）')
  await expect(page.locator('[data-connection-check-list]')).toHaveCount(0)
  await expect(page.getByRole('button',{name:'来源连接自检',exact:true})).toBeEnabled()
})

test('identity change clears the receipt and the history and disables the self-check actions',async({page})=>{
  const receipt=receiptFixture()
  await page.route('**/api/v1/**',route=>{const url=new URL(route.request().url())
    if(url.pathname===root&&route.request().method()==='POST')return route.fulfill({json:receipt})
    if(url.pathname===root&&route.request().method()==='GET')return route.fulfill({json:pageFixture()})
    return route.fulfill({status:404,json:{error:'NOT_FOUND'}})})
  await enter(page)
  await probe(page)
  await expect(page.locator('[data-connection-check]')).toBeVisible()
  await read(page)
  await expect(page.locator('[data-connection-check-list]')).toBeVisible()
  await page.getByRole('textbox',{name:'平台开发 Token（仅保存在当前标签页内存）'}).fill('')
  await expect(page.locator('[data-connection-check]')).toHaveCount(0)
  await expect(page.locator('[data-connection-check-list]')).toHaveCount(0)
  await expect(page.getByRole('button',{name:'来源连接自检',exact:true})).toBeDisabled()
  await expect(page.getByRole('button',{name:'读取自检回执',exact:true})).toBeDisabled()
})
