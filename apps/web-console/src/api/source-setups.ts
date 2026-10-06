import { platformClient } from './http.ts'
import { parseDefinition as parseModel } from './model-catalog.ts'
import { parseWorkflowEntry, stable, type Entry, type Definition, type Model } from './workflows.ts'
import type { EntityTarget } from './workflow-output.ts'
export type SourceType='ZABBIX_HOST'|'MANUAL_SAMPLE'|'CMDB_SNAPSHOT'
export type Connection={instanceId:string;digest:string;dataMode:'zabbix-jsonrpc'|'fixture'|'MANUAL_SAMPLE';endpoint:string|null;credentialRef:string|null}
export type SourceOption={id:SourceType;status:'AVAILABLE'|'UNAVAILABLE'|'LEGACY_IMPORT';connection:Connection|null}
export type Setup={id:string;name:string;description:string;source:{kind:'MANUAL_SAMPLE'|'ZABBIX_HOST';instanceId:string};connectionDigest:string;dataMode:Connection['dataMode'];initialTarget:EntityTarget|null;digest:string;createdAt:string;workflowId:string}
export type SourcePage={schemaVersion:'1.0';storage:'postgres'|'memory';types:SourceOption[];models:Model[];modelsTruncated:boolean;setups:{items:Setup[];truncated:boolean}}
export type Command={requestId:string;name:string;description:string;source:Setup['source'];connectionDigest:string;target?:EntityTarget}
export type Confirmed={setup:Setup;workflow:Entry|null}
const uuid=/^[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}$/
const hash=/^sha256:[a-f0-9]{64}$/
function invalid():never{throw new Error('数据源配置响应不符合契约，请重新读取')}
function object(v:unknown,keys:string[]):Record<string,unknown>{if(!v||typeof v!=='object'||Array.isArray(v)||Object.keys(v).some(k=>!keys.includes(k))||keys.some(k=>!Object.hasOwn(v,k)))invalid();return v as Record<string,unknown>}
function text(v:unknown,max:number,empty=false):asserts v is string{if(typeof v!=='string'||v.length>max||!empty&&!v.trim())invalid()}
function digest(v:unknown){text(v,71);if(!hash.test(v))invalid()}
function target(v:unknown){const t=object(v,['id','revision','digest']);text(t.id,56);if(!/^(builtin|custom)\.[a-z][a-z0-9_]{0,47}$/.test(t.id)||!Number.isInteger(t.revision)||Number(t.revision)<1||Number(t.revision)>10000)invalid();digest(t.digest)}
function source(v:unknown){const s=object(v,['kind','instanceId']);text(s.instanceId,64);if(!['ZABBIX_HOST','MANUAL_SAMPLE'].includes(String(s.kind))||!/^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$/.test(s.instanceId)||s.kind==='MANUAL_SAMPLE'&&s.instanceId!=='manual')invalid()}
function setup(v:unknown):Setup{const s=object(v,['id','name','description','source','connectionDigest','dataMode','initialTarget','digest','createdAt','workflowId']);text(s.id,36);if(!uuid.test(s.id)||s.workflowId!=='source-'+s.id)invalid();text(s.name,80);text(s.description,500,true);source(s.source);if(!['fixture','zabbix-jsonrpc','MANUAL_SAMPLE'].includes(String(s.dataMode))||((s.source as Definition['source']).kind==='MANUAL_SAMPLE')!==(s.dataMode==='MANUAL_SAMPLE'))invalid();if(s.initialTarget!==null)target(s.initialTarget);digest(s.connectionDigest);digest(s.digest);text(s.createdAt,40);if(!Number.isFinite(Date.parse(s.createdAt)))invalid();return v as Setup}
function connection(v:unknown,kind:SourceType){const c=object(v,['instanceId','digest','dataMode','endpoint','credentialRef']);digest(c.digest);text(c.instanceId,64);if(!/^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$/.test(c.instanceId)||!['fixture','zabbix-jsonrpc','MANUAL_SAMPLE'].includes(String(c.dataMode)))invalid();if(c.endpoint!==null){text(c.endpoint,1024);const u=new URL(c.endpoint);if(!['http:','https:'].includes(u.protocol)||u.username||u.password||u.search||u.hash)invalid()}if(c.credentialRef!==null){text(c.credentialRef,132);if(!/^env:[A-Z][A-Z0-9_]{0,127}$/.test(c.credentialRef))invalid()}if(kind==='MANUAL_SAMPLE'&&(c.instanceId!=='manual'||c.dataMode!=='MANUAL_SAMPLE'||c.endpoint!==null||c.credentialRef!==null)||kind==='ZABBIX_HOST'&&(c.dataMode==='MANUAL_SAMPLE'||c.dataMode==='fixture'&&(c.endpoint!==null||c.credentialRef!==null)||c.dataMode==='zabbix-jsonrpc'&&(c.endpoint===null||c.credentialRef===null)))invalid()}
function page(v:unknown):SourcePage{const p=object(v,['schemaVersion','storage','types','models','modelsTruncated','setups']);if(p.schemaVersion!=='1.0'||!['memory','postgres'].includes(String(p.storage))||typeof p.modelsTruncated!=='boolean'||!Array.isArray(p.types)||p.types.length!==3||new Set(p.types.map(x=>x.id)).size!==3)invalid();for(const row of p.types){const t=object(row,['id','status','connection']);if(!['ZABBIX_HOST','MANUAL_SAMPLE','CMDB_SNAPSHOT'].includes(String(t.id))||!['AVAILABLE','UNAVAILABLE','LEGACY_IMPORT'].includes(String(t.status)))invalid();if(t.id==='CMDB_SNAPSHOT'&&(t.status!=='LEGACY_IMPORT'||t.connection!==null)||t.id==='MANUAL_SAMPLE'&&t.status!=='AVAILABLE'||t.id==='ZABBIX_HOST'&&t.status==='LEGACY_IMPORT')invalid();if(t.status==='AVAILABLE')connection(t.connection,t.id as SourceType);else if(t.connection!==null)invalid()}if(!Array.isArray(p.models)||p.models.length>55)invalid();for(const row of p.models){const m=object(row,['definition','digest']);const d=parseModel(m.definition);if(d.kind!=='ENTITY'||!d.fields.length)invalid();digest(m.digest)}const items=object(p.setups,['items','truncated']);if(typeof items.truncated!=='boolean'||!Array.isArray(items.items)||items.items.length>20)invalid();items.items.forEach(setup);return v as SourcePage}
function confirmed(v:unknown):Confirmed{const c=object(v,['setup','workflow']);const s=setup(c.setup),w=c.workflow===null?null:parseWorkflowEntry(c.workflow);if(w&&(w.definition.id!==s.workflowId||w.definition.revision!==1)||!w&&s.initialTarget!==null)invalid();return {setup:s,workflow:w}}
const messages:Record<string,string>={CONFLICT:'同一确认请求的配置已变化，请查询原记录或重新开始',SOURCE_UNAVAILABLE:'连接配置已变化或当前身份不可用，请重新读取数据源',CAPACITY:'已达到接入记录或草稿数量上限',MODEL_CHANGED:'目标模型版本已变化，请重新选择',NOT_FOUND:'当前身份下找不到这份接入配置',FORBIDDEN:'当前身份没有所需的来源、工作流或模型权限',INVALID_REQUEST:'配置字段不符合要求'}
const request=(path:string,signal:AbortSignal,body?:unknown)=>platformClient.request('/api/v1/integrations/sources'+path,{signal,body,error:(status,code)=>new Error(messages[code]??'数据源请求失败（HTTP '+status+'）')})
export async function readSources(signal:AbortSignal){return page(await request('',signal))}
function verifyCommand(s:Setup,c:Command){if(s.id!==c.requestId||s.name!==c.name||s.description!==c.description||stable(s.source)!==stable(c.source)||stable(s.initialTarget)!==stable(c.target??null)||s.connectionDigest!==c.connectionDigest)invalid()}
export async function confirmSource(c:Command,signal:AbortSignal){const r=confirmed(await request('/confirm',signal,c));verifyCommand(r.setup,c);return r}
export async function readSetup(id:string,signal:AbortSignal,expected?:Command){if(!uuid.test(id))invalid();const r=confirmed(await request('/'+id,signal));if(r.setup.id!==id)invalid();if(expected)verifyCommand(r.setup,expected);return r}
export async function readSetupContinuation(id:string,signal:AbortSignal):Promise<Entry|null>{
 if(!uuid.test(id))invalid()
 const response=object(await request('/'+id+'/continuation',signal),['schemaVersion','setupId','workflow'])
 if(response.schemaVersion!=='1.0'||response.setupId!==id)invalid()
 const workflow=response.workflow===null?null:parseWorkflowEntry(response.workflow)
 if(workflow&&workflow.definition.id!=='source-'+id)invalid()
 return workflow
}
export function workflowLink(e:Entry){return '#/integrations/workflows?id='+encodeURIComponent(e.definition.id)+'&revision='+e.definition.revision+'&state='+e.state}

export function setupWorkflowLink(s:Setup,e:Entry|null){return e?workflowLink(e):"#/integrations/workflows?sourceSetup="+encodeURIComponent(s.id)}
