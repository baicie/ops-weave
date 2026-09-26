import { expect,test,type Page } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { TOKEN_OK } from './helpers.ts'
const sample = (name: string) => JSON.parse(readFileSync(new URL(`../../../contracts/examples/${name}.json`,import.meta.url),'utf8'))
const key = 'a2e021a0-96aa-4fdd-b5cf-5a6b9113d690'
async function fixture(page: Page,hook?: (path: string,method: string,body: Record<string,unknown> | null) => Promise<{status?:number;json:unknown} | undefined> | {status?:number;json:unknown} | undefined) {
  const entity = sample('entity'), initialVersion = entity.version, original = sample('asset-identity'); let identity: typeof original | null = null
  const writes: Record<string,unknown>[] = []
  await page.route('**/api/v1/**',async route => {
    const request = route.request(), path = new URL(request.url()).pathname, method = request.method(), body = request.postDataJSON()
    const override = await hook?.(path,method,body); if(override) return route.fulfill(override)
    if(path.endsWith('/entities/page')) return route.fulfill({json:{...sample('entity-page'),items:[entity],query:{q:'',type:'host',lifecycle:'',after:null,limit:25},nextCursor:null}})
    if(path.endsWith('/identity-keys') && method === 'GET') return route.fulfill({json:{...sample('asset-identity-page'),entityId:entity.id,tenantId:entity.tenantId,items:identity?[identity]:[]}})
    if(path.endsWith('/identity-keys') && method === 'POST') {
      writes.push(body); identity = {...original,id:body.requestId,entityId:entity.id,tenantId:entity.tenantId,value:body.value,reason:body.reason}; entity.version = Number(body.expectedEntityVersion)+1
      return route.fulfill({json:{schemaVersion:'1.0',requestId:body.requestId,action:'ASSERT',identity,entityVersion:entity.version}})
    }
    if(path.endsWith('/revocations') && method === 'POST' && identity) {
      writes.push(body); identity = {...identity,status:'REVOKED',version:2,revocation:{requestId:body.requestId,actor:'operator',reason:body.reason,at:new Date().toISOString()}}; entity.version = Number(body.expectedEntityVersion)+1
      return route.fulfill({json:{schemaVersion:'1.0',requestId:body.requestId,action:'REVOKE',identity,entityVersion:entity.version}})
    }
    if(path.endsWith('/resolve-identity')) return route.fulfill(identity ? {json:{schemaVersion:'1.0',storage:'postgres',method:'registered-asset-uuid',identity,entityVersion:entity.version}} : {status:404,json:{error:'NOT_FOUND'}})
    if(path.endsWith('/source-reviews') && method === 'GET') return route.fulfill({json:{...sample('source-review-page'),entityId:entity.id,tenantId:entity.tenantId,items:[],active:null}})
    if(path.endsWith('/source-reviews') && method === 'POST') {
      writes.push(body); const now = new Date().toISOString()
      return route.fulfill({json:{...sample('source-review'),id:body.requestId,rawRecordRef:`source-review:${body.requestId}`,tenantId:entity.tenantId,entityId:entity.id,baseVersion:body.expectedEntityVersion,values:body.values,externalId:body.externalId,
        observedAt:body.observedAt,ingestedAt:now,expiresAt:new Date(Date.parse(body.observedAt)+7*86400000).toISOString(),identity:body.identity}})
    }
    return route.fulfill({json:entity})
  })
  await page.goto('/#/inventory'); await page.getByRole('textbox',{name:'平台开发 Token（仅保存在当前标签页内存）'}).fill(TOKEN_OK)
  await page.getByRole('button',{name:'刷新列表',exact:true}).click(); await page.getByRole('button',{name:'查看详情',exact:true}).click()
  return {writes,entity,initialVersion}
}
test('verified UUID registration resolves the target and pins subsequent source review provenance',async ({page}) => {
  const f = await fixture(page)
  await page.getByRole('button',{name:'读取资产标识',exact:true}).click()
  await page.getByRole('textbox',{name:'待登记资产 UUID',exact:true}).fill(key); await page.getByRole('textbox',{name:'身份核对或撤销原因',exact:true}).fill('<img src=x onerror=alert(1)> checked register')
  await page.getByRole('button',{name:'登记已核对标识',exact:true}).click(); await expect(page.locator('[data-identity-receipt]')).toContainText('ASSERT')
  await expect(page.locator('[data-identity-record] img')).toHaveCount(0); await expect(page.locator('[data-identity-record]')).toContainText('<img src=x onerror=alert(1)>')
  expect(f.writes[0]).toMatchObject({expectedEntityVersion:f.initialVersion,expectedNamespace:'enterprise-assets',value:key}); expect(f.writes[0]).not.toHaveProperty('tenantId'); expect(f.writes[0]).not.toHaveProperty('actor')
  await page.getByText('按已登记强标识定位',{exact:true}).click(); await page.getByRole('textbox',{name:'查找资产 UUID',exact:true}).fill(key); await page.getByRole('button',{name:'按强标识定位',exact:true}).click()
  await expect(page.locator('[data-resolved-import]')).toContainText(key)
  await page.getByRole('button',{name:'读取补充来源',exact:true}).click(); await page.getByText('导入一条补充记录',{exact:true}).click()
  await page.getByRole('textbox',{name:'CMDB 外部编号',exact:true}).fill('cmdb-registered'); await page.getByRole('textbox',{name:'导入负责人',exact:true}).fill('source owner')
  await page.getByRole('button',{name:'暂存并预览',exact:true}).click(); await expect(page.locator('[data-review-identity]')).toContainText(key)
  expect(f.writes[1]).toMatchObject({expectedEntityVersion:f.initialVersion+1,identity:{id:f.writes[0].requestId,namespace:'enterprise-assets',value:key,version:1}})
})
test('unknown registration outcome retains the exact request and namespace for explicit retry',async ({page}) => {
  const writes: Record<string,unknown>[] = []
  await fixture(page,(path,method,body) => { if(path.endsWith('/identity-keys') && method === 'POST') { writes.push(body!); if(writes.length === 1)return {status:503,json:{error:'UNAVAILABLE'}} } })
  await page.getByRole('button',{name:'读取资产标识',exact:true}).click(); await page.getByRole('textbox',{name:'待登记资产 UUID',exact:true}).fill(key); await page.getByRole('textbox',{name:'身份核对或撤销原因',exact:true}).fill('verified')
  await page.getByRole('button',{name:'登记已核对标识',exact:true}).click(); await expect(page.getByRole('button',{name:'按原身份请求重试',exact:true})).toBeVisible()
  await page.getByRole('button',{name:'按原身份请求重试',exact:true}).click(); await expect(page.locator('[data-identity-receipt]')).toContainText('ASSERT'); expect(writes).toHaveLength(2); expect(writes[0]).toEqual(writes[1])
})

test('revocation controls follow reason edits and preserve the latest reviewed namespace and version',async ({page}) => {
  const f = await fixture(page)
  await page.getByRole('button',{name:'读取资产标识',exact:true}).click()
  await page.getByRole('textbox',{name:'待登记资产 UUID',exact:true}).fill(key)
  const reason = page.getByRole('textbox',{name:'身份核对或撤销原因',exact:true})
  await reason.fill('verified'); await page.getByRole('button',{name:'登记已核对标识',exact:true}).click()
  await expect(page.locator('[data-identity-receipt]')).toContainText('ASSERT')
  const revoke = page.getByRole('button',{name:`撤销标识 ${key}`,exact:true})
  await expect(revoke).toBeDisabled(); await reason.fill('Incorrect register mapping')
  await expect(revoke).toBeEnabled(); await reason.fill(''); await expect(revoke).toBeDisabled()
  await reason.fill('Explicitly revoke verified mapping'); await revoke.click()
  await expect(page.locator('[data-identity-receipt]')).toContainText('REVOKE')
  await expect(page.locator('[data-identity-record]')).toContainText('REVOKED'); await expect(revoke).toHaveCount(0)
  expect(f.writes[1]).toMatchObject({expectedEntityVersion:f.initialVersion+1,expectedNamespace:'enterprise-assets',reason:'Explicitly revoke verified mapping'})
})
for(const fault of ['namespace','tenant','revoked','uuid-suffix','namespace-suffix','time-suffix']) test(`rejects ${fault} identity data before rendering or pinning`,async ({page}) => {
  const e = sample('entity'), identity = {...sample('asset-identity'),entityId:e.id,tenantId:e.tenantId}
  if(fault === 'namespace') identity.namespace = 'foreign'; if(fault === 'tenant') identity.tenantId = 'foreign'; if(fault === 'revoked') {identity.status = 'REVOKED'; identity.version = 2}
  if(fault === 'uuid-suffix') identity.value += '\n'; if(fault === 'namespace-suffix') identity.namespace += '\n'; if(fault === 'time-suffix') identity.assertedAt += '\n'
  await fixture(page,(path,method) => path.endsWith('/identity-keys') && method === 'GET' ? {json:{...sample('asset-identity-page'),...(fault === 'namespace-suffix' ? {namespace:identity.namespace} : {}),entityId:e.id,tenantId:e.tenantId,items:[identity]}} : undefined)
  await page.getByRole('button',{name:'读取资产标识',exact:true}).click(); await expect(page.locator('[data-identity-error]')).toContainText('范围不正确'); await expect(page.locator('[data-identity-record]')).toHaveCount(0)
})
test('unmapped UUID shows no target and does not create an asset or import',async ({page}) => {
  const f = await fixture(page); await page.getByText('按已登记强标识定位',{exact:true}).click(); await page.getByRole('textbox',{name:'查找资产 UUID',exact:true}).fill(key); await page.getByRole('button',{name:'按强标识定位',exact:true}).click()
  await expect(page.locator('[data-page="inventory"] > [role="alert"]')).toContainText('未找到'); await expect(page.locator('[data-entity-detail]')).toHaveCount(0); expect(f.writes).toHaveLength(0)
})
test('late identity page cannot reappear after session is cleared',async ({page}) => {
  let release!:()=>void,started!:()=>void; const gate = new Promise<void>(r => {release=r}), sent = new Promise<void>(r => {started=r})
  await fixture(page,async (path,method) => { if(path.endsWith('/identity-keys') && method === 'GET') {started();await gate} return undefined })
  await page.getByRole('button',{name:'读取资产标识',exact:true}).click(); await sent; await page.getByRole('button',{name:'清除开发会话',exact:true}).click(); release()
  await expect(page.locator('[data-asset-identities]')).toHaveCount(0); await expect(page.locator('[data-identity-record]')).toHaveCount(0)
})
