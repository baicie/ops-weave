import { platformClient } from './http.ts'
import { itemDigest, type DiscoveredMetric } from './source-inspections.ts'
import { parseWorkflowSource, stable, type WorkflowSource } from './workflows.ts'
import type { ConnectionConfiguration } from './source-connections.ts'

export type MetricSourceSelection={source:WorkflowSource;item:DiscoveredMetric;asOf:string;expiresAt:string}
export type MetricSourcePage={schemaVersion:'2.0';source:WorkflowSource;items:MetricSourceSelection[];truncated:boolean}
function invalid():never{throw new Error('指标来源清单与固定连接或元数据摘要不一致')}
function obj(v:unknown,keys:string[]):Record<string,unknown>{if(!v||typeof v!=='object'||Array.isArray(v)||Object.keys(v).length!==keys.length||keys.some(k=>!Object.hasOwn(v,k)))invalid();return v as Record<string,unknown>}
async function pinDigest(parts:string[]){const bytes=parts.map(p=>{const b=new TextEncoder().encode(p);const n=new TextEncoder().encode(b.length+':');const result=new Uint8Array(n.length+b.length);result.set(n);result.set(b,n.length);return result});const joined=new Uint8Array(bytes.reduce((s,b)=>s+b.length,0));let offset=0;for(const b of bytes){joined.set(b,offset);offset+=b.length}return 'sha256:'+Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',joined)),b=>b.toString(16).padStart(2,'0')).join('')}
export async function readMetricSources(configuration:ConnectionConfiguration,instanceId:string,signal:AbortSignal):Promise<MetricSourcePage>{
 const v=obj(await platformClient.request('/api/v2/data-sources/'+configuration.sourceId+'/connection/'+configuration.revision+'/workflow-metrics',{signal,error:(status,code)=>new Error(code==='FORBIDDEN'?'当前身份无此指标来源权限':code==='SOURCE_UNAVAILABLE'?'固定连接当前不可用':'指标来源读取失败（HTTP '+status+'）')}),['schemaVersion','source','items','truncated'])
 const source=parseWorkflowSource(v.source),expected={sourceId:configuration.sourceId,revision:configuration.revision,digest:configuration.connectionDigest}
 if(v.schemaVersion!=='2.0'||source.kind!=='ZABBIX_HOST'||source.instanceId!==instanceId||stable(source.configuration)!==stable(expected)||typeof v.truncated!=='boolean'||!Array.isArray(v.items)||v.items.length>100)invalid()
 const ids=new Set<string>();for(const value of v.items){const r=obj(value,['source','item','asOf','expiresAt']),s=parseWorkflowSource(r.source),m=s.metric;await itemDigest([r.item]);const i=r.item as DiscoveredMetric
  if(s.kind!=='ZABBIX_METRIC'||!m||s.instanceId!==instanceId||stable(s.configuration)!==stable(expected)||ids.has(m.itemId)||i.mappingStatus!=='MAPPED'||i.itemId!==m.itemId||i.hostId!==m.hostId||i.sourceKey!==m.sourceKey||i.sourceUnit!==m.sourceUnit||i.sourceValueType!==m.sourceValueType)invalid();ids.add(m.itemId)
  if(m.digest!==await pinDigest(['workflow-metric-source-v1',m.inspectionId,m.itemId,m.hostId,m.sourceKey,m.sourceUnit,m.sourceValueType]))invalid()
  if(typeof r.asOf!=='string'||typeof r.expiresAt!=='string'||!Number.isFinite(Date.parse(r.asOf))||!Number.isFinite(Date.parse(r.expiresAt))||Date.parse(r.asOf)>Date.now()||Date.parse(r.expiresAt)<=Date.parse(r.asOf))invalid()
 }
 return v as MetricSourcePage
}
