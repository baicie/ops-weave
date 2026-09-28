import { platformClient } from './http.ts'
export type TopologyNode={id:string;name:string;type:string;lifecycle:string;dataMode:'fixture'|'zabbix-jsonrpc'|'import'|'unknown'}
export type TopologyEdge={id:string;from:string;to:string;type:string;validFrom:string;validTo:string|null;dataMode:TopologyNode['dataMode']}
export type Topology={schemaVersion:'1.0';tenantId:string;centerId:string;asOf:string;coverage:'stored-current-one-hop';limit:50;truncated:boolean;nodes:TopologyNode[];edges:TopologyEdge[]}
function check(v:unknown):asserts v{if(!v)throw new Error('关系响应不符合契约或查询范围')}
const record=(v:unknown):v is Record<string,unknown>=>!!v&&typeof v==='object'&&!Array.isArray(v)
const uuid=(v:unknown):v is string=>typeof v==='string'&&/^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(v)
const text=(v:unknown,max:number):v is string=>typeof v==='string'&&!!v.trim()&&v.length<=max&&!/[\x00-\x1f\x7f-\x9f]/.test(v)
const mode=(v:unknown)=>['fixture','zabbix-jsonrpc','import','unknown'].includes(String(v))
const time=(v:unknown):v is string=>typeof v==='string'&&/^\d{4}-\d\d-\d\dT.*Z$/.test(v)&&Number.isFinite(Date.parse(v))
function keys(v:Record<string,unknown>,wanted:string[]){check(Object.keys(v).length===wanted.length&&Object.keys(v).every(k=>wanted.includes(k)))}
export function parseTopology(value:unknown,center:string,tenant:string):Topology{
 check(record(value));keys(value,['schemaVersion','tenantId','centerId','asOf','coverage','limit','truncated','nodes','edges']);check(value.schemaVersion==='1.0'&&value.tenantId===tenant&&value.centerId===center&&uuid(center)&&time(value.asOf)&&value.coverage==='stored-current-one-hop'&&value.limit===50&&typeof value.truncated==='boolean');
 check(Array.isArray(value.nodes)&&value.nodes.length>0&&value.nodes.length<=51&&Array.isArray(value.edges)&&value.edges.length<=50&&(!value.truncated||value.edges.length===50));const ids=new Set<string>();const edgeIds=new Set<string>();const reached=new Set<string>([center]);
 for(const n of value.nodes){check(record(n));keys(n,['id','name','type','lifecycle','dataMode']);check(uuid(n.id)&&!ids.has(n.id)&&text(n.name,255)&&text(n.type,64)&&['DISCOVERED','ACTIVE','INACTIVE','DELETED','ARCHIVED'].includes(String(n.lifecycle))&&mode(n.dataMode));ids.add(n.id)}check(ids.has(center));
 for(const e of value.edges){check(record(e));keys(e,['id','from','to','type','validFrom','validTo','dataMode']);check(uuid(e.id)&&!edgeIds.has(e.id)&&typeof e.from==='string'&&typeof e.to==='string'&&ids.has(e.from)&&ids.has(e.to)&&(e.from===center||e.to===center)&&text(e.type,64)&&mode(e.dataMode)&&time(e.validFrom)&&Date.parse(e.validFrom)<=Date.parse(value.asOf)&&(e.validTo===null||time(e.validTo)&&Date.parse(e.validTo)>Date.parse(value.asOf)));edgeIds.add(e.id);reached.add(e.from);reached.add(e.to)}check(reached.size===ids.size);return value as unknown as Topology
}
export async function readTopology(center:string,tenant:string,signal:AbortSignal){check(uuid(center));return parseTopology(await platformClient.request('/api/v1/entities/'+center+'/topology',{signal,error:status=>new Error(status===404?'该资产不存在或不在当前授权范围。':status===503?'关系查询暂不可用，请检查平台服务。':'读取关系失败（HTTP '+status+'）')}),center,tenant)}
export const originLabel=(mode:string)=>({fixture:'Fixture · 合成数据','zabbix-jsonrpc':'Zabbix 来源',import:'导入来源',unknown:'来源未记录'}[mode]??'来源未记录')
