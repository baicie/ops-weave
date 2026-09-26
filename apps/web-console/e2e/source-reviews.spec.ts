import { expect, test, type Page } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { TOKEN_OK } from './helpers.ts'
const sample = (name: string) => JSON.parse(readFileSync(new URL(`../../../contracts/examples/${name}.json`,import.meta.url),'utf8'))
const entity = () => sample('entity')
function record() {
  const r = sample('source-review'); const now = Date.now()
  r.observedAt = new Date(now - 1000).toISOString(); r.ingestedAt = new Date(now).toISOString(); r.expiresAt = new Date(now - 1000 + 7*86400000).toISOString(); r.baseVersion = entity().version
  return r
}
async function open(page: Page, handler: (path: string, method: string, body: Record<string,unknown> | null) => unknown | Promise<unknown>) {
  await page.route('**/api/v1/**',async route => {
    const req = route.request(), path = new URL(req.url()).pathname
    if (path.includes('/source-reviews')) return route.fulfill(await handler(path,req.method(),req.postDataJSON()) as Parameters<typeof route.fulfill>[0])
    return route.fulfill({ json: path.endsWith('/entities/page') ? { ...sample('entity-page'),items:[entity()],query:{ q:'',type:'host',lifecycle:'',after:null,limit:25 },nextCursor:null } : entity() })
  })
  await page.goto('/#/inventory'); await page.getByRole('textbox',{name:'平台开发 Token（仅保存在当前标签页内存）'}).fill(TOKEN_OK)
  await page.getByRole('button',{name:'刷新列表'}).click(); await page.getByRole('button',{name:'查看详情'}).click()
}
test('human field preview renders untrusted text and requires a choice for every field',async ({page}) => {
  const r=record();r.values.name='<img src=x onerror=alert(1)> imported'; let posted: Record<string,unknown> | null=null
  await open(page,(_path,method,body)=>{ if(method==='GET')return {json:{...sample('source-review-page'),items:[r]}}
    posted=body; return {json:{...r,status:'ACCEPTED',version:2,decisions:[{requestId:body!.requestId,action:'ACCEPT',choices:body!.choices,reason:body!.reason,actor:'trusted-user',at:new Date().toISOString(),entityVersion:r.baseVersion+1}]}} })
  await page.getByRole('button',{name:'读取补充来源',exact:true}).click();await page.getByRole('button',{name:'核对这条记录'}).click()
  await expect(page.locator('[data-source-review-preview]')).toContainText(r.values.name);await expect(page.locator('[data-source-review-preview] img')).toHaveCount(0)
  await page.getByRole('textbox',{name:'核对或撤销原因'}).fill('manually checked')
  await expect(page.getByRole('button',{name:'确认字段选择'})).toBeDisabled()
  await page.getByRole('combobox',{name:'选择名称来源'}).selectOption('SUPPLEMENTAL');await page.getByRole('combobox',{name:'选择负责人来源'}).selectOption('PRIMARY')
  await page.getByRole('button',{name:'确认字段选择'}).click();await expect(page.locator('[data-source-review-receipt]')).toContainText('ACCEPTED')
  expect(posted).toMatchObject({expectedEntityVersion:r.baseVersion,expectedReviewVersion:1,choices:{name:'SUPPLEMENTAL',owner:'PRIMARY'}})
  expect(posted).not.toHaveProperty('tenantId');expect(posted).not.toHaveProperty('actor');expect(posted).not.toHaveProperty('sourceInstanceId')
})
for(const mode of ['tenant','source','cursor'])test(`rejects ${mode} mismatch before displaying imported values`,async ({page})=>{
  const p=sample('source-review-page');p.items=[record()]
  if(mode==='tenant')p.items[0].tenantId='foreign'
  if(mode==='source')p.items[0].sourceInstanceId='foreign'
  if(mode==='cursor')p.nextCursor='../untrusted'
  await open(page,()=>({json:p}));await page.getByRole('button',{name:'读取补充来源',exact:true}).click()
  await expect(page.locator('[data-source-review-error]')).toContainText('范围不正确');await expect(page.locator('[data-source-review-record]')).toHaveCount(0)
})
test('expired accepted values remain explicitly stale and provide revoke instead of another acceptance',async ({page})=>{
  const r=record();const now=Date.now();r.observedAt=new Date(now-8*86400000).toISOString();r.ingestedAt=r.observedAt;r.expiresAt=new Date(now-86400000).toISOString()
  r.status='ACCEPTED';r.version=2;r.decisions=[{requestId:crypto.randomUUID(),action:'ACCEPT',choices:{name:'SUPPLEMENTAL',owner:'SUPPLEMENTAL'},reason:'old confirmation',actor:'trusted-user',at:r.ingestedAt,entityVersion:r.baseVersion+1}]
  await open(page,()=>({json:{...sample('source-review-page'),items:[r],active:r}}));await page.getByRole('button',{name:'读取补充来源',exact:true}).click()
  await expect(page.locator('[data-active-source]')).toContainText('已过期，仅保留最后已知字段');await page.getByRole('button',{name:'查看生效字段与撤销'}).click()
  await expect(page.getByRole('button',{name:'确认字段选择'})).toHaveCount(0);await expect(page.getByRole('button',{name:'撤销并恢复最新主来源'})).toBeDisabled()
  await expect(page.getByRole('combobox',{name:'选择名称来源'})).toHaveCount(0)
  await expect(page.locator('[data-source-review-preview] td').filter({hasText:'采用补充来源'})).toHaveCount(2)
})
test('unknown submission outcome retries the identical import id and body',async ({page})=>{
  const bodies: Record<string,unknown>[]=[]
  await open(page,(_path,method,body)=>{if(method==='GET')return {json:{...sample('source-review-page'),items:[]}}
    bodies.push(body!);if(bodies.length===1)return {status:503,json:{error:'INVENTORY_READ_UNAVAILABLE'}}
    const r=record();return {json:{...r,id:body!.requestId,rawRecordRef:`source-review:${body!.requestId}`,baseVersion:body!.expectedEntityVersion,externalId:body!.externalId,values:body!.values,observedAt:body!.observedAt,expiresAt:new Date(Date.parse(body!.observedAt as string)+7*86400000).toISOString()}} })
  await page.getByRole('button',{name:'读取补充来源',exact:true}).click();await page.getByText('导入一条补充记录',{exact:true}).click()
  await page.getByRole('textbox',{name:'CMDB 外部编号'}).fill('import-retry');await page.getByRole('textbox',{name:'导入名称',exact:true}).fill('imported')
  await page.getByRole('button',{name:'暂存并预览'}).click();await expect(page.getByRole('button',{name:'按原请求重试'})).toBeVisible()
  await page.getByRole('button',{name:'按原请求重试'}).click();await expect(page.locator('[data-source-review-receipt]')).toContainText('PENDING')
  expect(bodies).toHaveLength(2);expect(bodies[1]).toEqual(bodies[0])
})
test('late review data cannot reappear after logout',async ({page})=>{
  let release!:()=>void,started!:()=>void;const gate=new Promise<void>(r=>{release=r}),sent=new Promise<void>(r=>{started=r})
  await open(page,async()=>{started();await gate;return {json:{...sample('source-review-page'),items:[record()]}}})
  await page.getByRole('button',{name:'读取补充来源',exact:true}).click();await sent
  await page.getByRole('button',{name:'清除开发会话',exact:true}).click();release()
  await expect(page.locator('[data-entity-detail]')).toHaveCount(0);await expect(page.locator('[data-source-review-record]')).toHaveCount(0)
})
