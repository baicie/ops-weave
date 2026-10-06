import { platformClient } from './http.ts'
import type { Entry } from './workflows.ts'
export type VersionReference = { id:string;revision:number;state:Entry['state'];editVersion:number;digest:string }
export type ComparisonSection = 'NAME'|'SOURCE'|'TARGET'|'NODE'|'OPERATOR'|'MAPPING'|'PARAMETER'|'EDGE'|'ORDER'
export type WorkflowChange = { section:ComparisonSection;nodeId:string|null;key:string;before:string|null;after:string|null }
export type WorkflowComparison = { schemaVersion:'2.0';base:VersionReference;candidate:VersionReference;comparedAt:string;changes:WorkflowChange[] }
export const versionReference = (entry:Entry):VersionReference => ({id:entry.definition.id,revision:entry.definition.revision,state:entry.state,editVersion:entry.editVersion,digest:entry.digest})
function invalid():never { throw new Error('版本差异响应不符合所选版本，请重新读取并比较。') }
function object(value:unknown,keys:string[]):Record<string,any> { if(!value||typeof value!=='object'||Array.isArray(value)||Object.keys(value).length!==keys.length||keys.some(k=>!Object.hasOwn(value,k)))invalid();return value as Record<string,any> }
function reference(value:unknown,expected:VersionReference) {const r=object(value,['id','revision','state','editVersion','digest']);if(Object.entries(expected).some(([k,v])=>r[k]!==v))invalid() }
export async function compareWorkflows(base:Entry,candidate:Entry,signal:AbortSignal):Promise<WorkflowComparison> {
 const a=versionReference(base),b=versionReference(candidate);if(a.id!==b.id)invalid()
 const value=object(await platformClient.request('/api/v1/integrations/workflows/comparisons',{method:'POST',body:{base:a,candidate:b},signal,error:(status,code)=>new Error(code==='CONFLICT'?'所选草稿已变化，请刷新版本后重新比较。':code==='FORBIDDEN'?'当前身份没有所选版本或来源的读取权限。':code==='NOT_FOUND'?'当前身份下找不到所选版本，请刷新版本列表。':'版本比较失败（HTTP '+status+'）')}),['schemaVersion','base','candidate','comparedAt','changes'])
 if(value.schemaVersion!=='2.0'||typeof value.comparedAt!=='string'||value.comparedAt.length>40||!/^\d{4}-\d{2}-\d{2}T.*(?:Z|[+-]\d{2}:\d{2})$/.test(value.comparedAt)||!Number.isFinite(Date.parse(value.comparedAt))||!Array.isArray(value.changes)||value.changes.length>1200)invalid()
 reference(value.base,a);reference(value.candidate,b)
 const sections:ComparisonSection[]=['NAME','SOURCE','TARGET','NODE','OPERATOR','MAPPING','PARAMETER','EDGE','ORDER'],identifiers=new Set<string>()
 for(const change of value.changes) {
  const c=object(change,['section','nodeId','key','before','after'])
  if(!sections.includes(c.section)||typeof c.key!=='string'||!c.key||c.key.length>96||c.before===c.after||[c.before,c.after].some(v=>v!==null&&(typeof v!=='string'||v.length>4096)))invalid()
  const hasNode=['NODE','OPERATOR','MAPPING','PARAMETER'].includes(c.section)
  if(hasNode ? typeof c.nodeId!=='string'||!/^[a-z][a-z0-9_-]{0,31}$/.test(c.nodeId) : c.nodeId!==null)invalid()
  const id=JSON.stringify([c.section,c.nodeId,c.key]);if(identifiers.has(id))invalid();identifiers.add(id)
 }
 return value as WorkflowComparison
}
