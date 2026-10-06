import { test, expect, type Page } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { createHash } from 'node:crypto'
import { TOKEN_OK, WORKFLOW_OPERATORS } from './helpers.ts'
const sample = (name: string) => JSON.parse(readFileSync(new URL('../../../contracts/' + name, import.meta.url), 'utf8'))
const view = sample('examples/v2/source-connection-read.json')
const scoped = sample('examples/v2/source-connection-configuration-scoped.json')
view.connection = scoped; view.instance.configurationRevision = scoped.revision; view.instance.connectionDigest = scoped.connectionDigest
const hash = (parts: string[]) => { const h = createHash('sha256'); for (const p of parts) { const b = Buffer.from(p); h.update(b.length + ':'); h.update(b) }; return 'sha256:' + h.digest('hex') }
const link = (revision = 1, digest = view.connection.connectionDigest) => '#/integrations/workflows?' + new URLSearchParams({ sourceInstance: view.instance.id, configurationRevision: String(revision), connectionDigest: digest })
async function fixture(page: Page, mode?: 'rotated' | 'missing' | 'archived' | 'forbidden' | 'unavailable' | 'existing') {
  const calls: { method: string; path: string; body: any }[] = []
  const instance = structuredClone(view.instance), history = sample('examples/v2/source-connection-history.json')
  history.items = [structuredClone(scoped)]
  const workspace = { schemaVersion: '2.0', storage: 'memory', operatorCatalog: WORKFLOW_OPERATORS, drafts: { items: [] as any[], truncated: false }, published: { items: [], truncated: false }, models: sample('catalog/opsweave-core-1.0.0.json').definitions.filter((d: any) => d.kind === 'ENTITY').map((definition: any) => ({ definition, digest: 'sha256:' + 'a'.repeat(64) })), modelsTruncated: false, zabbixSource: { instanceId: '', mode: 'fixture' }, runs: { items: [], truncated: false } }
  if (mode === 'rotated') {
    const newer = structuredClone(history.items[0]); newer.revision = 2; newer.credentialPin.revision = 2; newer.credentialPin.versionId = '33333333-3333-4333-8333-333333333333'; newer.createdAt = '2026-10-03T01:00:00Z'
    newer.connectionDigest = hash(['source-connection-v3',newer.sourceId,newer.connectorVersion,newer.endpoint.id,newer.endpoint.digest,newer.credentialPin.credentialId,'2',newer.credentialPin.versionId,String(newer.hostGroupIds.length),...newer.hostGroupIds]); history.items.unshift(newer)
    Object.assign(instance, { configurationRevision: 2, connectionDigest: newer.connectionDigest, editVersion: 2, updatedAt: newer.createdAt })
  }
  if (mode === 'missing') history.items = []
  if (mode === 'archived') instance.state = 'ARCHIVED'
  if (mode === 'existing') { const d = sample('examples/v2/workflow-definition.json'); d.id = instance.workflowId; workspace.drafts.items = [{ definition:d,layout:{},state:'DRAFT',digest:'sha256:'+'b'.repeat(64),editVersion:1,preview:null,updatedAt:instance.updatedAt }] }
  await page.route(/\/api\/(?:v1\/integrations|v2\/data-sources)(?:\/|$)/, route => {
    const path = new URL(route.request().url()).pathname; calls.push({ method: route.request().method(), path, body: route.request().postDataJSON() })
    if (path.endsWith('/workflows')) return route.fulfill({ json: workspace })
    if (path.endsWith('/connection/history')) return mode === 'unavailable' ? route.fulfill({ status: 503, json:{error:'UNAVAILABLE'} }) : route.fulfill({ json: history })
    if (path === '/api/v2/data-sources') return route.fulfill({ json:{schemaVersion:'2.0',storage:'memory',items:[instance],truncated:false} })
    if (path === '/api/v2/data-sources/' + instance.id) return mode === 'forbidden' ? route.fulfill({ status:403,json:{error:'FORBIDDEN'} }) : route.fulfill({ json:{schemaVersion:'2.0',instance} })
    if (path.endsWith('/drafts')) return route.fulfill({ json:{definition:route.request().postDataJSON().definition,layout:route.request().postDataJSON().layout,state:'DRAFT',digest:'sha256:'+'b'.repeat(64),editVersion:1,preview:null,updatedAt:instance.updatedAt} })
    return route.fulfill({ status:404,json:{error:'NOT_FOUND'} })
  })
  await page.goto('/' + link()); await page.getByRole('textbox',{name:'平台开发 Token（仅保存在当前标签页内存）'}).fill(TOKEN_OK)
  return calls
}
for (const mode of [undefined,'rotated'] as const) test('explicit fixed configuration prepares only a local draft' + (mode ? ' after rotation' : ''), async ({ page }) => {
  const calls = await fixture(page,mode)
  await expect(page.getByRole('textbox',{name:'工作流名称',exact:true})).toHaveValue(view.instance.name)
  await page.getByRole('button',{name:'数据输入节点 source',exact:true}).click()
  const inspector = page.locator('.studio-inspector'); await expect(inspector).toContainText(view.instance.id); await expect(inspector).toContainText(view.connection.connectionDigest); await expect(inspector).toContainText('v1')
  expect(calls.map(c=>c.path)).toEqual(['/api/v1/integrations/workflows','/api/v2/data-sources/'+view.instance.id,'/api/v2/data-sources/'+view.instance.id+'/connection/history']); expect(calls.every(c=>c.method==='GET')).toBe(true)
  await page.getByRole('button',{name:'关闭节点配置',exact:true}).click(); await page.getByRole('button',{name:'保存草稿',exact:true}).click()
  await expect(page.locator('.workflow-page')).toContainText('草稿已保存')
  const body = calls.find(c=>c.method==='POST')!.body
  expect(body.expectedEditVersion).toBe(0); expect(body.definition.id).toBe(view.instance.workflowId)
  expect(body.definition.source).toEqual({...view.instance.source,configuration:{sourceId:view.instance.id,revision:1,digest:view.connection.connectionDigest}})
  expect(body.definition.nodes.every((n:any)=>n.operatorDigest===WORKFLOW_OPERATORS.operators.find((o:any)=>o.type===n.type).digest)).toBe(true)
})
for (const mode of ['missing','archived','forbidden','unavailable','existing'] as const) test('configuration bootstrap retains ' + mode + ' without fallback or writes', async ({ page }) => {
  const calls = await fixture(page,mode)
  await expect(page.locator('.workflow-page > [role=alert]')).not.toBeEmpty(); await expect(page.getByRole('textbox',{name:'工作流名称',exact:true})).toHaveCount(0)
  expect(calls.every(c=>c.method==='GET')).toBe(true)
  expect(calls.some(c=>c.path.endsWith('/sources')||c.path.includes('/zabbix/'))).toBe(false)
  await page.getByRole('button',{name:'切换到深色模式'}).click(); expect(calls.filter(c=>c.path.endsWith('/workflows'))).toHaveLength(1)
})
test('closed pinned selector rejects extra, duplicate, mixed and invalid pins before reading', async ({ page }) => {
  let requests = 0; await page.route('**/api/**', route => { requests++; return route.fulfill({status:500,json:{}}) })
  await page.goto('/' + link() + '&task=source-invalid'); await page.getByRole('textbox',{name:'平台开发 Token（仅保存在当前标签页内存）'}).fill(TOKEN_OK)
  for (const value of [link()+'&tenant=other',link()+'&sourceInstance='+view.instance.id,link(0),link(101),link(1,'sha256:bad'),link()+'&state=DRAFT']) {
    await page.evaluate(v=>{location.hash=v},value); await expect(page.getByRole('alert').filter({hasText:'工作流地址参数无效'})).toBeVisible()
  }
  expect(requests).toBe(0)
})
