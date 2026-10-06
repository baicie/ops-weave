import { platformClient } from './http.ts'
import type { ModelEntry,ModelRef } from './model-catalog.ts'

export type ModelPin=ModelRef&{digest:string}
export type ModelChange={fieldId:string|null;property:'LABEL'|'DESCRIPTION'|'KIND'|'REVISION'|'FIELD'|'TYPE'|'REQUIRED'|'MAX_LENGTH'|'MIN'|'MAX'|'CHOICES'|'FROM'|'TO'|'CARDINALITY';before:string|null;after:string|null;compatible:boolean}
export type ModelReview={candidate:ModelPin;editVersion:number;base:ModelPin|null;reviewedAt:string;compatible:boolean;reasons:string[];changes:ModelChange[]}
export type ModelUsage={kind:'RELATION'|'WORKFLOW';id:string;revision:number;digest:string;label:string;state:'PUBLISHED'|'DRAFT';editVersion:number;roles:('OUTPUT'|'FROM'|'TO')[];fieldIds:string[];tasks:{kind:'HOST_SCAN'|'HOST_SCHEDULE';state:'RUNNING'|'STOPPED'|'FAILED';generation:number}[]}
export type ModelReferenceReport={target:ModelPin;inspectedAt:string;workflowsAvailable:boolean;references:{items:ModelUsage[];truncated:boolean}}
export class ModelImpactError extends Error{constructor(readonly status:number){super('模型影响检查失败（HTTP '+status+'）')}}
const model=/^(builtin|custom)\.[a-z][a-z0-9_]{0,47}$/,workflow=/^[a-z][a-z0-9_-]{0,47}$/,field=/^[a-z][a-z0-9_]{0,47}$/,digest=/^sha256:[a-f0-9]{64}$/
function invalid():never{throw new Error('模型影响响应不符合契约')}
function obj(v:unknown,keys:string[]):Record<string,unknown>{if(!v||typeof v!=='object'||Array.isArray(v)||Object.keys(v).length!==keys.length||keys.some(k=>!Object.hasOwn(v,k)))invalid();return v as Record<string,unknown>}
function int(v:unknown,min:number,max:number){if(!Number.isSafeInteger(v)||Number(v)<min||Number(v)>max)invalid()}
function text(v:unknown,max:number,empty=false){if(typeof v!=='string'||v.length>max||!empty&&!v.trim())invalid()}
function date(v:unknown){text(v,40);if(!Number.isFinite(Date.parse(String(v))))invalid()}
function pin(v:unknown):ModelPin{const p=obj(v,['id','revision','digest']);if(!model.test(String(p.id))||!digest.test(String(p.digest)))invalid();int(p.revision,1,10000);return p as ModelPin}
export function parseModelReview(value:unknown,entry:ModelEntry):ModelReview{
 const envelope=obj(value,['schemaVersion','review']);if(envelope.schemaVersion!=='1.0')invalid();const r=obj(envelope.review,['candidate','editVersion','base','reviewedAt','compatible','reasons','changes']),c=pin(r.candidate);if(c.id!==entry.definition.id||c.revision!==entry.definition.revision||c.digest!==entry.digest||r.editVersion!==entry.editVersion)invalid();if(r.base!==null&&pin(r.base).id!==c.id)invalid();date(r.reviewedAt);if(typeof r.compatible!=='boolean'||!Array.isArray(r.reasons)||r.reasons.length>3||new Set(r.reasons).size!==r.reasons.length||r.reasons.some(v=>!['NON_CONSECUTIVE_REVISION','INCOMPATIBLE_CHANGE','UNKNOWN_ENTITY_TYPE'].includes(v))||r.compatible!==(r.reasons.length===0)||!Array.isArray(r.changes)||r.changes.length>300)invalid()
 for(const raw of r.changes){const v=obj(raw,['fieldId','property','before','after','compatible']);if(v.fieldId!==null&&!field.test(String(v.fieldId))||!['LABEL','DESCRIPTION','KIND','REVISION','FIELD','TYPE','REQUIRED','MAX_LENGTH','MIN','MAX','CHOICES','FROM','TO','CARDINALITY'].includes(String(v.property))||typeof v.compatible!=='boolean'||v.before===v.after)invalid();for(const key of ['before','after'])if(v[key]!==null)text(v[key],4096,true)}
 if(r.compatible&&(r.changes.some(change=>!(change as ModelChange).compatible)||(r.base===null?c.revision!==1:c.revision!==pin(r.base).revision+1)))invalid()
 return r as ModelReview
}
export function parseModelReferences(value:unknown,ref:ModelRef,expectedDigest?:string):ModelReferenceReport{
 const envelope=obj(value,['schemaVersion','report']);if(envelope.schemaVersion!=='1.0')invalid();const r=obj(envelope.report,['target','inspectedAt','workflowsAvailable','references']),p=pin(r.target);if(p.id!==ref.id||p.revision!==ref.revision||expectedDigest&&p.digest!==expectedDigest||typeof r.workflowsAvailable!=='boolean')invalid();date(r.inspectedAt);const page=obj(r.references,['items','truncated']);if(typeof page.truncated!=='boolean'||!Array.isArray(page.items)||page.items.length>50)invalid();const seen=new Set<string>()
 for(const raw of page.items){const v=obj(raw,['kind','id','revision','digest','label','state','editVersion','roles','fieldIds','tasks']);if(!['RELATION','WORKFLOW'].includes(String(v.kind))||!(v.kind==='RELATION'?model:workflow).test(String(v.id))||!digest.test(String(v.digest))||!['DRAFT','PUBLISHED'].includes(String(v.state))||v.kind==='WORKFLOW'&&!r.workflowsAvailable)invalid();int(v.revision,1,v.kind==='RELATION'?10000:1000000);int(v.editVersion,v.state==='DRAFT'?1:0,v.state==='DRAFT'?1000000:0);text(v.label,160);const key=[v.kind,v.id,v.revision,v.state].join('|');if(seen.has(key))invalid();seen.add(key)
  if(!Array.isArray(v.roles)||v.roles.length<1||v.roles.length>2||new Set(v.roles).size!==v.roles.length||(v.kind==='WORKFLOW'?JSON.stringify(v.roles)!=='["OUTPUT"]':v.roles.some(role=>!['FROM','TO'].includes(role))))invalid()
  if(!Array.isArray(v.fieldIds)||v.fieldIds.length>32||new Set(v.fieldIds).size!==v.fieldIds.length||v.fieldIds.some(f=>!field.test(f))||!Array.isArray(v.tasks)||v.tasks.length>2||v.kind==='RELATION'&&(v.fieldIds.length>0||v.tasks.length>0||v.state!=='PUBLISHED'))invalid();const kinds=new Set<string>();for(const task of v.tasks){const t=obj(task,['kind','state','generation']);if(!['HOST_SCAN','HOST_SCHEDULE'].includes(String(t.kind))||!['RUNNING','STOPPED','FAILED'].includes(String(t.state))||kinds.has(String(t.kind)))invalid();int(t.generation,1,1000001);kinds.add(String(t.kind))}
 }
 return r as ModelReferenceReport
}
const options=(signal:AbortSignal)=>({signal,error:(status:number)=>new ModelImpactError(status)})
export async function reviewModel(entry:ModelEntry,signal:AbortSignal){return parseModelReview(await platformClient.request('/api/v1/catalog/revisions/review',{...options(signal),body:{ref:{id:entry.definition.id,revision:entry.definition.revision},expectedEditVersion:entry.editVersion,digest:entry.digest}}),entry)}
export async function readModelReferences(ref:ModelRef,signal:AbortSignal,expectedDigest?:string){return parseModelReferences(await platformClient.request('/api/v1/catalog/versions/'+encodeURIComponent(ref.id)+'/'+ref.revision+'/references',options(signal)),ref,expectedDigest)}
