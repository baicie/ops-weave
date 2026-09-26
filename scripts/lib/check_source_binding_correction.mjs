// Owned fixture data through actual browser/Java/PostgreSQL; no vendor or historical migration claim.
import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import { writeFile } from 'node:fs/promises'
import path from 'node:path'

export async function checkSourceBindingCorrection({page,expect,request,browserJson,entity,target,tenant,token,root}){
  assert(target && target.id!==entity.id)
  const before=await request(`/api/v1/entities/${entity.id}/source-presence`),previous=before.items[0]
  assert(previous && previous.externalId==='fixture-snapshot-host-10084')
  const targetBefore=await request(`/api/v1/entities/${target.id}`),asset=randomUUID()
  await request(`/api/v1/entities/${target.id}/identity-keys`,'POST',{requestId:randomUUID(),expectedEntityVersion:targetBefore.version,expectedNamespace:'acceptance-assets',value:asset,reason:'Fixture correction: verified target UUID'})
  const response=(pathname,method='GET')=>{const p=page.waitForResponse(r=>new URL(r.url()).pathname===pathname && r.request().method()===method);p.catch(()=>{});return p}
  await page.getByRole('link',{name:'来源绑定更正',exact:true}).click()
  await page.getByRole('textbox',{name:'平台开发 Token（仅保存在当前标签页内存）'}).fill(token)
  await page.getByRole('textbox',{name:'原资产 ID',exact:true}).fill(entity.id)
  await page.getByRole('textbox',{name:'待更正来源外部 ID',exact:true}).fill(previous.externalId)
  await page.getByRole('textbox',{name:'目标已登记资产 UUID',exact:true}).fill(asset)
  await page.getByRole('textbox',{name:'更正来源字段 JSON',exact:true}).fill(JSON.stringify({owner:'Unreviewed corrected fixture owner'}))
  await page.getByRole('textbox',{name:'绑定更正原因',exact:true}).fill('Fixture: correct external object target; preserve historical ownership')
  await page.getByRole('button',{name:'读取更正预览',exact:true}).click()
  await expect(page.locator('[data-correction-preview]')).toContainText(target.id)
  await expect(page.getByRole('button',{name:'确认绑定更正',exact:true})).toBeDisabled()
  await page.screenshot({path:path.join(root,'.tmp/metrics-acceptance/source-correction-preview.png'),fullPage:true})
  await page.getByRole('checkbox',{name:'已核对新旧资产，确认更正当前绑定并保留历史归属'}).check()
  const saved=response('/api/v1/integrations/cmdb/binding-corrections','POST')
  await page.getByRole('button',{name:'确认绑定更正',exact:true}).click()
  const http=await saved,receipt=await browserJson(http)
  await expect(page.locator('[data-correction-receipt]')).toContainText('绑定更正已保存')
  assert.equal(receipt.tenantId,tenant);assert.equal(receipt.previous.entityId,entity.id);assert.equal(receipt.snapshot.resolved[0].entityId,target.id);assert.equal(receipt.command.targetIdentity.value,asset)
  assert.equal((await request(`/api/v1/entities/${entity.id}/source-presence`)).items.length,0)
  assert.equal((await request(`/api/v1/entities/${target.id}/source-presence`)).items[0].snapshotId,receipt.command.requestId)
  assert.equal((await request(`/api/v1/integrations/cmdb/snapshots/${previous.snapshotId}`)).resolved[0].entityId,entity.id)
  assert.notEqual((await request(`/api/v1/entities/${target.id}`)).attributes.owner,'Unreviewed corrected fixture owner')
  await page.reload();await expect(page.getByRole('textbox',{name:'原资产 ID',exact:true})).toHaveValue('')
  await page.getByRole('textbox',{name:'平台开发 Token（仅保存在当前标签页内存）'}).fill(token)
  const recovered=response(`/api/v1/integrations/cmdb/binding-corrections/${receipt.command.requestId}`)
  await page.getByRole('button',{name:'查询更正回执',exact:true}).click();assert.deepEqual(await browserJson(await recovered),receipt)
  await expect(page.locator('[data-correction-receipt]')).toBeVisible()
  await page.getByRole('textbox',{name:'原资产 ID',exact:true}).fill(entity.id)
  const history=response(`/api/v1/entities/${entity.id}/source-binding-corrections`)
  await page.getByRole('button',{name:'读取更正历史',exact:true}).click();const audit=await browserJson(await history)
  assert.deepEqual(audit.items,[receipt]);await expect(page.locator('[data-correction-history]')).toContainText(receipt.command.reason)
  await page.screenshot({path:path.join(root,'.tmp/metrics-acceptance/source-correction-history.png'),fullPage:true})
  for(const [name,value] of Object.entries({'source-binding-correction-input':http.request().postDataJSON(),'source-binding-correction-receipt':receipt,'source-binding-correction-page':audit}))await writeFile(path.join(root,'.tmp/metrics-acceptance',name+'.json'),JSON.stringify(value,null,2))
  await page.getByRole('textbox',{name:'平台开发 Token（仅保存在当前标签页内存）'}).fill('')
  await expect(page.locator('[data-correction-history]')).toHaveCount(0);await expect(page.getByRole('textbox',{name:'更正来源字段 JSON',exact:true})).toHaveValue('{}')
  console.log('PASS: Browser explicit old/target preview -> version/pin-checked PG binding correction + fresh pending observation -> unchanged original snapshot ownership -> receipt reload/history and session clearing (fixture).')
}
