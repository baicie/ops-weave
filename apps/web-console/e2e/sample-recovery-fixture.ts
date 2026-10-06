import {expect,type Page} from '@playwright/test'
import {createHash} from 'node:crypto'

// Explicit protocol fixture. It does not claim actual output or database durability.
const hash=(parts:string[])=>'sha256:'+createHash('sha256').update(parts.map(v=>Buffer.byteLength(v)+':'+v).join('')).digest('hex')
export async function sampleRecoveryFixture(page:Page,mode='normal',delay?:Promise<void>){
 const calls:{path:string;body:any}[]= [];let closure:any=null,posts=0
 await page.route(/\/api\/v1\/integrations\/workflows\/sample-recovery\//,async route=>{
  const path=new URL(route.request().url()).pathname,body=route.request().postDataJSON();calls.push({path,body})
  if(path.includes('/batches/')){if(mode==='status-fail'&&calls.filter(c=>c.path.includes('/batches/')).length===1)return route.fulfill({status:503,json:{error:'SOURCE_UNAVAILABLE'}});return route.fulfill({json:{schemaVersion:'2.0',closure}})}
  if(path.endsWith('/abandon')){
   posts++;closure={schemaVersion:'2.0',requestId:body.requestId,commandDigest:hash(['workflow-abandon-sample-v1',body.id,String(body.revision),body.digest,body.kind,body.batchId,body.batchDigest,body.expectedUpdatedAt,'true']),reference:{id:body.id,revision:body.revision,digest:body.digest},kind:body.kind,batchId:body.batchId,batchDigest:body.batchDigest,proofUpdatedAt:body.expectedUpdatedAt,acceptedAt:body.expectedUpdatedAt,state:'ABANDONED',uncertainRecords:1}
   if(mode==='lost'||mode==='missing'&&posts===1){if(mode==='missing')closure=null;return route.abort('failed')}
   if(mode==='bad-proof')return route.fulfill({json:{...closure,batchId:'10000000-0000-4000-8000-000000000000'}})
   if(mode==='deny')return route.fulfill({status:403,json:{error:'FORBIDDEN'}})
   return route.fulfill({json:closure})
  }
  if(path.includes('/commands/')){if(delay)await delay;if(!closure)return route.fulfill({status:404,json:{error:'NOT_FOUND'}});return route.fulfill({json:closure})}
  return route.fulfill({status:404,json:{error:'NOT_FOUND'}})
 });return calls
}
export const closurePanel=(page:Page)=>page.getByRole('region',{name:'终止样本确认',exact:true})
export async function acknowledgeSample(page:Page){const p=closurePanel(page);await expect(p.getByRole('button',{name:'终止确认',exact:true})).toBeDisabled();await p.getByRole('checkbox').check();await p.getByRole('button',{name:'终止确认',exact:true}).click()}
