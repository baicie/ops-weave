// Explicit fixture acceptance using the actual browser, Java API and PostgreSQL.
import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import { writeFile } from 'node:fs/promises'
import path from 'node:path'

export async function checkAssetIdentity({ page, expect, request, browserJson, entity, tenant, root }) {
  const key = randomUUID(), base = `/api/v1/entities/${entity.id}`, target = path.join(root, '.tmp/metrics-acceptance')
  const record = async (name, value) => writeFile(path.join(target, `${name}.json`), JSON.stringify(value, null, 2))
  const response = suffix => {
    const pending = page.waitForResponse(r => r.request().method() === 'POST' && new URL(r.url()).pathname.endsWith(suffix))
    // A click can wait on disabled UI longer than this response timeout. Attach a
    // handler immediately so the awaited failure reaches the caller's cleanup.
    pending.catch(() => {}); return pending
  }
  const before = await request(base)
  await page.getByRole('button', { name: '读取资产标识', exact: true }).click()
  await page.getByRole('textbox', { name: '待登记资产 UUID' }).fill(key)
  await page.getByRole('textbox', { name: '身份核对或撤销原因' }).fill('Fixture acceptance: manually verified asset register UUID')
  const assertedResponse = response('/identity-keys')
  await page.getByRole('button', { name: '登记已核对标识', exact: true }).click()
  const assertedHttp = await assertedResponse; assert.equal(assertedHttp.status(), 200)
  const receipt = await browserJson(assertedHttp), claim = assertedHttp.request().postDataJSON()
  assert.equal(receipt.identity.entityId, entity.id); assert.equal(receipt.identity.tenantId, tenant)
  assert.equal(receipt.identity.value, key); assert.equal(receipt.identity.namespace, 'acceptance-assets')
  assert.equal(receipt.identity.verification, 'operator-confirmed'); assert.equal(receipt.identity.status, 'ACTIVE')
  assert.equal(receipt.entityVersion, before.version + 1)
  assert.deepEqual(await request(`${base}/identity-keys`, 'POST', claim), receipt, 'Same key must return original receipt')
  await expect(page.locator('[data-identity-record]')).toContainText(key)
  await expect(page.getByRole('button', { name: '读取资产标识', exact: true })).toBeEnabled()

  await page.getByText('按已登记强标识定位', { exact: true }).click()
  await page.getByRole('textbox', { name: '查找资产 UUID', exact: true }).fill(key)
  const resolvedResponse = response('/resolve-identity')
  await page.getByRole('button', { name: '按强标识定位', exact: true }).click()
  const resolvedHttp = await resolvedResponse; assert.equal(resolvedHttp.status(), 200)
  const resolution = await browserJson(resolvedHttp)
  assert.deepEqual(resolution.identity, receipt.identity); assert.equal(resolution.storage, 'postgres')
  assert.equal(resolution.entityVersion, receipt.entityVersion)
  await expect(page.locator('[data-resolved-import]')).toContainText(key)
  assert(!page.url().includes(key), 'UUID and write preconditions must not enter selection URL')
  await page.getByRole('button', { name: '读取补充来源', exact: true }).click()
  await page.getByText('导入一条补充记录', { exact: true }).click()
  await page.getByRole('textbox', { name: 'CMDB 外部编号' }).fill('fixture-identity-cmdb-10084')
  await page.getByRole('textbox', { name: '导入负责人', exact: true }).fill('UUID verified fixture owner')
  const stagedResponse = response('/source-reviews')
  await page.getByRole('button', { name: '暂存并预览', exact: true }).click()
  const stagedHttp = await stagedResponse; assert.equal(stagedHttp.status(), 200)
  const staged = await browserJson(stagedHttp), imported = stagedHttp.request().postDataJSON()
  assert.equal(staged.status, 'PENDING'); assert.equal(staged.identity.id, receipt.identity.id)
  assert.deepEqual(staged.identity, imported.identity); assert.equal(staged.values.owner, 'UUID verified fixture owner')
  await expect(page.locator('[data-review-identity]')).toContainText(key)
  await page.getByRole('combobox', { name: '选择负责人来源' }).selectOption('SUPPLEMENTAL')
  await page.getByRole('textbox', { name: '核对或撤销原因' }).fill('Fixture: confirmed UUID and field provenance')
  const acceptedResponse = response('/decisions')
  await page.getByRole('button', { name: '确认字段选择', exact: true }).click()
  const acceptedHttp = await acceptedResponse; assert.equal(acceptedHttp.status(), 200)
  const accepted = await browserJson(acceptedHttp); assert.equal(accepted.status, 'ACCEPTED')
  await expect(page.getByRole('button', { name: '读取补充来源', exact: true })).toBeEnabled()
  const projected = await request(base)
  assert.equal(projected.attributes.owner, 'UUID verified fixture owner')
  assert.equal(projected.attributes.hostId, before.attributes.hostId)
  const revokeButton = page.getByRole('button', { name: `撤销标识 ${key}`, exact: true })
  await page.getByRole('button', { name: '读取资产标识', exact: true }).click()
  await page.getByRole('textbox', { name: '身份核对或撤销原因' }).fill('Fixture: cannot remove active field dependency')
  await expect(revokeButton).toBeEnabled()
  const blockedResponse = response('/revocations')
  await revokeButton.click()
  assert.equal((await blockedResponse).status(), 409)
  await expect(page.locator('[data-identity-error]')).not.toBeEmpty()
  assert.equal((await request(base)).version, projected.version, 'Blocked revocation must not partially mutate entity')
  await page.locator('[data-asset-identities]').scrollIntoViewIfNeeded()
  await page.screenshot({ path: path.join(target, 'asset-identity-dependency.png') })

  await page.getByRole('button', { name: '读取补充来源', exact: true }).click()
  await page.getByRole('button', { name: '查看生效字段与撤销', exact: true }).click()
  await page.getByRole('textbox', { name: '核对或撤销原因' }).fill('Fixture: undo field confirmation before identity')
  const undoneResponse = response('/decisions')
  await page.getByRole('button', { name: '撤销并恢复最新主来源', exact: true }).click()
  const undoneHttp = await undoneResponse; assert.equal(undoneHttp.status(), 200)
  const undone = await browserJson(undoneHttp); assert.equal(undone.status, 'REVOKED'); assert.deepEqual(undone.identity, staged.identity)
  await expect(page.getByRole('button', { name: '读取补充来源', exact: true })).toBeEnabled()
  await page.getByRole('button', { name: '读取资产标识', exact: true }).click()
  await page.getByRole('textbox', { name: '身份核对或撤销原因' }).fill('Fixture: revoke verified registry link after field rollback')
  const revokedResponse = response('/revocations')
  await revokeButton.click()
  const revokedHttp = await revokedResponse; assert.equal(revokedHttp.status(), 200)
  const revoked = await browserJson(revokedHttp)
  assert.equal(revoked.identity.status, 'REVOKED'); assert.equal(revoked.identity.version, 2)
  await expect(page.locator('[data-identity-record]')).toContainText('REVOKED')
  await expect(page.getByRole('button', { name: '读取资产标识', exact: true })).toBeEnabled()
  const restored = await request(base), history = await request(`${base}/identity-keys?limit=25`)
  assert.equal(restored.name, before.name); assert.equal(restored.attributes.owner, before.attributes.owner)
  assert.equal(restored.attributes.hostId, before.attributes.hostId); assert.deepEqual(history.items, [revoked.identity])
  const missingResponse = response('/resolve-identity')
  await page.getByRole('button', { name: '按强标识定位', exact: true }).click()
  assert.equal((await missingResponse).status(), 404)
  await expect(page.locator('[data-entity-detail]')).toHaveCount(0)
  for (const [name, value] of Object.entries({
    'asset-identity': revoked.identity, 'asset-identity-pin': staged.identity, 'asset-identity-claim': claim,
    'asset-identity-revoke': revokedHttp.request().postDataJSON(), 'asset-identity-receipt': receipt,
    'asset-identity-revocation-receipt': revoked, 'asset-identity-resolution': resolution,
    'asset-identity-resolve-request': resolvedHttp.request().postDataJSON(), 'asset-identity-page': history,
    'source-review-with-identity': undone, 'source-review-import-with-identity': imported,
  })) await record(name, value)
  console.log('PASS: Browser verified UUID registration -> PG unique registry/idempotent receipt -> authorized resolution -> pinned import/field acceptance -> dependent revoke 409 -> explicit field rollback -> key revoke and lookup 404; stable entity/telemetry identity (fixture).')
}
