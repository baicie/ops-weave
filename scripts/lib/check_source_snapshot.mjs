// Explicit fixture import through actual browser/Java/PostgreSQL. No CMDB vendor connection is claimed.
import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import { writeFile } from 'node:fs/promises'
import path from 'node:path'

export async function checkSourceSnapshot({ page, expect, request, browserJson, entity, tenant, token, root }) {
  const base=`/api/v1/entities/${entity.id}`,asset=randomUUID(),requestId=randomUUID()
  const before=await request(base)
  await request(`${base}/identity-keys`,'POST',{requestId,expectedEntityVersion:before.version,expectedNamespace:'acceptance-assets',value:asset,reason:'Fixture snapshot: independently verified register UUID'})
  const response=(pathname,method='GET')=>{const p=page.waitForResponse(r=>new URL(r.url()).pathname===pathname && r.request().method()===method);p.catch(()=>{});return p}
  await page.getByRole('link',{name:'CMDB 快照',exact:true}).click()
  await page.getByRole('textbox',{name:'平台开发 Token（仅保存在当前标签页内存）'}).fill(token)
  const configResponse=response('/api/v1/integrations/cmdb/snapshots/config')
  await page.getByRole('button',{name:'读取快照配置',exact:true}).click()
  const config=await browserJson(await configResponse)
  assert.equal(config.tenantId,tenant);assert.equal(config.sourceInstanceId,'cmdb-import-dev');assert.equal(config.dataMode,'import')
  await page.getByRole('textbox',{name:'来源记录 JSON',exact:true}).fill(JSON.stringify([{externalId:'fixture-snapshot-host-10084',assetUuid:asset,values:{owner:'Unreviewed snapshot owner'}}]))
  await page.getByRole('checkbox',{name:'已核对标识、观测时间与完整性，确认导入本批'}).check()
  const savedResponse=response('/api/v1/integrations/cmdb/snapshots','POST')
  await page.getByRole('button',{name:'导入来源快照',exact:true}).click()
  const savedHttp=await savedResponse;const receipt=await browserJson(savedHttp)
  await expect(page.locator('[data-snapshot-receipt]')).toContainText('快照已保存')
  await expect(page.locator('[data-snapshot-receipt] li')).toHaveCount(1)
  assert.equal(receipt.tenantId,tenant);assert.equal(receipt.resolved[0].entityId,entity.id);assert.equal(receipt.resolved[0].identity.value,asset);assert.equal(receipt.markedAbsent,0)
  assert.notEqual((await request(base)).attributes.owner,'Unreviewed snapshot owner','Source import must not auto-accept fields')
  await page.screenshot({path:path.join(root,'.tmp/metrics-acceptance/source-snapshot.png'),fullPage:true})
  await page.reload();await page.getByRole('textbox',{name:'平台开发 Token（仅保存在当前标签页内存）'}).fill(token)
  const reload=response(`/api/v1/integrations/cmdb/snapshots/${receipt.input.requestId}`)
  await page.getByRole('button',{name:'查询快照回执',exact:true}).click();assert.deepEqual(await browserJson(await reload),receipt)
  await expect(page.locator('[data-snapshot-receipt]')).toBeVisible()
  await page.getByRole('link',{name:'资产',exact:true}).click();await page.getByRole('button',{name:'刷新列表',exact:true}).click()
  await page.getByRole('row').filter({has:page.getByRole('cell',{name:entity.attributes.hostId,exact:true})}).getByRole('button',{name:'查看详情',exact:true}).click()
  const presenceResponse=response(`${base}/source-presence`);await page.getByRole('button',{name:'读取来源状态',exact:true}).click();const presence=await browserJson(await presenceResponse)
  await expect(page.locator('[data-source-presence]')).toContainText('PRESENT');assert.equal(presence.entityId,entity.id);assert.equal(presence.items[0].snapshotId,receipt.input.requestId)
  await page.screenshot({path:path.join(root,'.tmp/metrics-acceptance/source-presence.png'),fullPage:true})
  for(const [name,value] of Object.entries({'source-snapshot-config':config,'source-snapshot-input':savedHttp.request().postDataJSON(),'source-snapshot-receipt':receipt,'source-presence-page':presence}))await writeFile(path.join(root,'.tmp/metrics-acceptance',name+'.json'),JSON.stringify(value,null,2))
  await page.getByRole('textbox',{name:'平台开发 Token（仅保存在当前标签页内存）'}).fill('');await expect(page.locator('[data-source-presence]')).toHaveCount(0)
  console.log('PASS: Browser explicit CMDB snapshot -> registered scoped UUID -> atomic PG observation/pending field review/presence -> original receipt reload -> authorized presence view and identity clearing; no vendor connection or automatic field acceptance claimed.')
}
