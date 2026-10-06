import { platformClient } from './http.ts'
import { parseDefinition as parseModel, type ModelDefinition } from './model-catalog.ts'
import { parseOutput, telemetryFields, outputInputFields, supportsStandardMetricMapping, parseStandardMetricRecord, type OutputTarget } from './workflow-output.ts'
import { parseMappingDefinition, sameMappingPin, type MappingDefinition, type MappingPin } from './metric-mappings.ts'
import { graphProblems } from '../state/workflow-graph.ts'

// Authoritative wire contract: contracts/schemas/v2/workflow-*.schema.json.
import { OPERATORS, builtInDescriptor, parseOperatorCatalog, pinNode, type NodeType, type Operator, type OperatorCatalog } from './workflow-operators.ts'
export { OPERATORS }
export type { Operator, NodeType }
export type WorkflowNode = { id: string; type: NodeType; version: '1'; config: Record<string, string>; operatorDigest?: string }
export type WorkflowMetricSourcePin = { inspectionId:string;itemId:string;hostId:string;sourceKey:string;sourceUnit:string;sourceValueType:'FLOAT'|'UNSIGNED';digest:string }
export type WorkflowLogSourcePin = Omit<WorkflowMetricSourcePin,'sourceValueType'> & {sourceValueType:'LOG'}
export type WorkflowSource = { kind: 'MANUAL_SAMPLE' | 'ZABBIX_HOST' | 'ZABBIX_METRIC' | 'ZABBIX_LOG'; instanceId: string; configuration?: { sourceId: string; revision: number; digest: string };metric?:WorkflowMetricSourcePin;log?:WorkflowLogSourcePin }
export type Definition = { schemaVersion: '2.0'; id: string; revision: number; name: string; source: WorkflowSource; target: OutputTarget; nodes: WorkflowNode[]; edges: { from: string; to: string }[] }
export type Layout = Record<string, { x: number; y: number }>
export type Receipt = { id: string; digest: string; inputDigest: string; origin: 'MANUAL_SAMPLE' | 'fixture' | 'zabbix-jsonrpc'; accepted: number; rejected: number; filtered: number; createdAt: string }
export type Entry = { definition: Definition; digest: string; state: 'DRAFT' | 'PUBLISHED'; editVersion: number; layout: Layout; updatedAt: string; preview: Receipt | null }
export type Model = { definition: ModelDefinition; digest: string }
export type Run = { workflowId: string; revision: number; mode: 'PREVIEW' | 'RUN'; receipt: Receipt }
export type Page<T> = { items: T[]; truncated: boolean }
export type Workspace = { schemaVersion: '2.0'; storage: 'memory' | 'postgres'; drafts: Page<Entry>; published: Page<Entry>; models: Model[]; modelsTruncated: boolean; zabbixSource: { instanceId: string; mode: string }; runs: Page<Run>; operatorCatalog?: OperatorCatalog; metricMappings?: MappingDefinition[] }
export type Step = { nodeId: string; type: NodeType; status: 'OK' | 'ERROR' | 'FILTERED' | 'SKIPPED'; values: Record<string, string | number | boolean | null | MappingPin | Record<string,string>>; issues: { field: string; code: string }[] }
export type Evaluation = { receipt: Receipt; evaluation: { rows: { index: number; status: 'ACCEPTED' | 'REJECTED' | 'FILTERED'; steps: Step[] }[]; accepted: number; rejected: number; filtered: number; dryRun: true; writesPerformed: false }; retainedCount: number; missingRaw: number; truncated: boolean; sourceStatus: string }
const kinds: NodeType[] = ['SOURCE', 'MAP', ...OPERATORS, 'VALIDATE', 'OUTPUT']
const digestPattern = /^sha256:[a-f0-9]{64}$/
const fieldPattern = /^[a-zA-Z][a-zA-Z0-9_]{0,47}$/
function invalid(): never { throw new Error('工作流内容不符合契约，请核对定义、版本和运行结果') }
function obj(v: unknown, keys: string[]): Record<string, unknown> { if (!v || typeof v !== 'object' || Array.isArray(v) || Object.keys(v).some(k => !keys.includes(k)) || keys.some(k => !Object.hasOwn(v, k))) invalid(); return v as Record<string, unknown> }
function text(v: unknown, max = 128, empty = false): asserts v is string { if (typeof v !== 'string' || v.length > max || !empty && !v.trim()) invalid() }
function integer(v: unknown, min: number, max: number) { if (!Number.isSafeInteger(v) || (v as number) < min || (v as number) > max) invalid() }
function arr(v: unknown, max: number): unknown[] { if (!Array.isArray(v) || v.length > max) invalid(); return v }
function digest(v: unknown) { text(v, 71); if (!digestPattern.test(v)) invalid() }
function date(v: unknown) { text(v, 40); if (!Number.isFinite(Date.parse(v))) invalid() }
function field(v: unknown) { text(v, 48); if (!fieldPattern.test(v) || ['tenantId','tenant_id','Authorization','authorization','constructor','prototype','secret','token','password'].includes(v)) invalid() }
export function parseWorkflowSource(value: unknown): WorkflowSource {
  const hasPin = !!value && typeof value === 'object' && Object.hasOwn(value, 'configuration')
  const hasLog=!!value&&typeof value==='object'&&Object.hasOwn(value,'log');
  const hasMetric=!!value&&typeof value==='object'&&Object.hasOwn(value,'metric');
  const s = obj(value, ['kind','instanceId',...(hasPin?['configuration']:[]),...(hasMetric?['metric']:[]),...(hasLog?['log']:[])])
  text(s.instanceId,64)
  if (!['MANUAL_SAMPLE','ZABBIX_HOST','ZABBIX_METRIC','ZABBIX_LOG'].includes(String(s.kind)) || !/^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$/.test(s.instanceId) || s.kind === 'MANUAL_SAMPLE' && (s.instanceId !== 'manual' || hasPin)) invalid()
  if (hasPin) { const c = obj(s.configuration,['sourceId','revision','digest']); text(c.sourceId,36); if (!/^[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}$/.test(c.sourceId)) invalid(); integer(c.revision,1,100); digest(c.digest) }
  if(s.kind==='ZABBIX_METRIC'? !hasPin||!hasMetric:hasMetric)invalid();
  if(s.kind==='ZABBIX_LOG'?!hasPin||!hasLog:hasLog)invalid();
  if(hasMetric||hasLog){const m=obj(hasMetric?s.metric:s.log,['inspectionId','itemId','hostId','sourceKey','sourceUnit','sourceValueType','digest']);text(m.inspectionId,36);if(!/^[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}$/.test(m.inspectionId))invalid();for(const key of ['itemId','hostId']){text(m[key],20);if(!/^[1-9][0-9]{0,19}$/.test(m[key] as string))invalid()}text(m.sourceKey,2048);text(m.sourceUnit,64,true);if(/[\x00-\x1f\x7f-\x9f]/.test(m.sourceKey+m.sourceUnit)||(hasMetric?!['FLOAT','UNSIGNED'].includes(String(m.sourceValueType)):m.sourceValueType!=='LOG'))invalid();digest(m.digest)}
  return value as WorkflowSource
}
export function parseWorkflow(v: unknown): Definition {
  const d = obj(v, ['schemaVersion','id','revision','name','source','target','nodes','edges']); if (d.schemaVersion !== '2.0') invalid()
  text(d.id, 48); if (!/^[a-z][a-z0-9_-]{0,47}$/.test(d.id)) invalid(); integer(d.revision, 1, 10000); text(d.name, 80)
  const s = parseWorkflowSource(d.source)
  const t = parseOutput(d.target)
  if(s.kind==='ZABBIX_LOG'&&(!('kind' in t)||t.kind!=='LOG'))invalid()
  if ('kind' in t && s.kind === 'ZABBIX_HOST' || s.kind==='ZABBIX_METRIC' && (!('mappingPin' in t)||!t.mappingPin)) invalid()
  const nodes = arr(d.nodes, 16); if (nodes.length < 4) invalid(); const ids: string[] = []
  for (const value of nodes) {
    const n = obj(value, !!value && typeof value === 'object' && Object.hasOwn(value,'operatorDigest') ? ['id','type','version','config','operatorDigest'] : ['id','type','version','config']); if ('operatorDigest' in n) digest(n.operatorDigest); text(n.id, 32); if (!/^[a-z][a-z0-9_-]{0,31}$/.test(n.id) || ids.includes(n.id) || !kinds.includes(n.type as NodeType) || n.version !== '1') invalid(); ids.push(n.id)
    if (!n.config || typeof n.config !== 'object' || Array.isArray(n.config) || Object.keys(n.config).length > 32) invalid()
    for (const value of Object.values(n.config)) text(value, 512, true)
    const c = n.config as Record<string,string>
    if (n.type === 'MAP') { if (!Object.keys(c).length || new Set(Object.values(c)).size !== Object.keys(c).length) invalid(); for (const [from,to] of Object.entries(c)) { field(from); field(to) } }
    else { const keys = builtInDescriptor(n.type as NodeType).parameters.map(p => p.name); obj(c, keys); if ('field' in c) field(c.field); if (n.type === 'SCALE' && (!/^-?(0|[1-9][0-9]*)(\.[0-9]+)?$/.test(c.factor) || !Number.isFinite(Number(c.factor)) || Number(c.factor) === 0 || Math.abs(Number(c.factor)) > 1e6)) invalid() }
  }
  const typed = nodes as WorkflowNode[]; if (typed[0]?.type !== 'SOURCE' || typed[1]?.type !== 'MAP' || typed.at(-2)?.type !== 'VALIDATE' || typed.at(-1)?.type !== 'OUTPUT' || typed.slice(2,-2).some(n => !OPERATORS.includes(n.type as Operator))) invalid()
  if ('kind' in t) { const fields = outputInputFields(t).map(f => f.id); if (typed.some(n => n.type === 'MAP' ? Object.values(n.config).some(f => !fields.includes(f)) : n.config.field !== undefined && !fields.includes(n.config.field))) invalid() }
  const edges = arr(d.edges, 32); edges.forEach(value => { const e = obj(value,['from','to']); if (!ids.includes(String(e.from)) || !ids.includes(String(e.to))) invalid() })
  if (graphProblems(v as Definition).length) invalid()
  return v as Definition
}
export function receipt(v: unknown): Receipt { const r = obj(v,['id','digest','inputDigest','origin','accepted','rejected','filtered','createdAt']); text(r.id,36); if (!/^[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}$/.test(r.id) || !['MANUAL_SAMPLE','fixture','zabbix-jsonrpc'].includes(String(r.origin))) invalid(); digest(r.digest); digest(r.inputDigest); date(r.createdAt); for (const k of ['accepted','rejected','filtered']) integer(r[k],0,5); const count = Number(r.accepted)+Number(r.rejected)+Number(r.filtered); if (count<1 || count>5) invalid(); return v as Receipt }
function entry(v: unknown): Entry { const e=obj(v,['definition','digest','state','editVersion','layout','updatedAt','preview']); const d=parseWorkflow(e.definition); digest(e.digest);date(e.updatedAt); if (!['DRAFT','PUBLISHED'].includes(String(e.state))) invalid();integer(e.editVersion,e.state==='DRAFT'?1:0,e.state==='DRAFT'?1000000:0); const layout=obj(e.layout,d.nodes.map(n=>n.id));for(const p of Object.values(layout)){const point=obj(p,['x','y']);integer(point.x,0,4000);integer(point.y,0,4000)}if(e.preview!==null && receipt(e.preview).digest!==e.digest)invalid();return v as Entry }
function paged<T>(v:unknown,parse:(x:unknown)=>T):Page<T>{const p=obj(v,['items','truncated']);if(typeof p.truncated!=='boolean')invalid();return {items:arr(p.items,20).map(parse),truncated:p.truncated}}
async function workspace(v:unknown):Promise<Workspace> {
 const hasCatalog=!!v&&typeof v==='object'&&Object.hasOwn(v,'operatorCatalog'),hasMappings=!!v&&typeof v==='object'&&Object.hasOwn(v,'metricMappings');
 const p=obj(v,['schemaVersion','storage','drafts','published','models','modelsTruncated','zabbixSource','runs',...(hasCatalog?['operatorCatalog']:[]),...(hasMappings?['metricMappings']:[])]);
 if(hasCatalog)parseOperatorCatalog(p.operatorCatalog);if(p.schemaVersion!=='2.0'||!['memory','postgres'].includes(String(p.storage))||typeof p.modelsTruncated!=='boolean')invalid();
 const drafts=paged(p.drafts,entry),published=paged(p.published,entry);if(drafts.items.some(e=>e.state!=='DRAFT')||published.items.some(e=>e.state!=='PUBLISHED'))invalid();
 for(const m of arr(p.models,55)){const model=obj(m,['definition','digest']);if(parseModel(model.definition).kind!=='ENTITY')invalid();digest(model.digest)}
 const source=obj(p.zabbixSource,['instanceId','mode']);text(source.instanceId,128,true);text(source.mode,32);
 paged(p.runs,v=>{const r=obj(v,['workflowId','revision','mode','receipt']);text(r.workflowId,48);integer(r.revision,1,10000);if(!['PREVIEW','RUN'].includes(String(r.mode)))invalid();receipt(r.receipt);return v as Run});
 const mappings=hasMappings?await Promise.all(arr(p.metricMappings,100).map(parseMappingDefinition)):[];if(new Set(mappings.map(m=>m.mappingPin.id)).size!==mappings.length)invalid();
 return {...v as Workspace,metricMappings:mappings};
}
function evaluation(v:unknown,e:Entry,mapping?:MappingDefinition):Evaluation {const r=obj(v,['receipt','evaluation','retainedCount','missingRaw','truncated','sourceStatus']);const rec=receipt(r.receipt);if(rec.digest!==e.digest||e.definition.source.kind==='MANUAL_SAMPLE'&&rec.origin!=='MANUAL_SAMPLE'||e.definition.source.kind!=='MANUAL_SAMPLE'&&rec.origin==='MANUAL_SAMPLE'||e.definition.source.configuration&&rec.origin!=='zabbix-jsonrpc')invalid();integer(r.retainedCount,0,Number.MAX_SAFE_INTEGER);integer(r.missingRaw,0,Number.MAX_SAFE_INTEGER);if(typeof r.truncated!=='boolean')invalid();text(r.sourceStatus,32);if(!['MANUAL_SAMPLE','SUCCEEDED','FAILED'].includes(r.sourceStatus)||e.definition.source.kind==='MANUAL_SAMPLE'&&(r.sourceStatus!=='MANUAL_SAMPLE'||r.missingRaw!==0||r.truncated!==false)||e.definition.source.kind!=='MANUAL_SAMPLE'&&r.sourceStatus==='MANUAL_SAMPLE')invalid();const result=obj(r.evaluation,['rows','accepted','rejected','filtered','dryRun','writesPerformed']);if(result.dryRun!==true||result.writesPerformed!==false)invalid();for(const k of ['accepted','rejected','filtered'])if(result[k]!==rec[k as 'accepted'|'rejected'|'filtered'])invalid();const rows=arr(result.rows,5);if(rows.length!==rec.accepted+rec.rejected+rec.filtered||Number(r.retainedCount)<rows.length)invalid();let accepted=0,rejected=0,filtered=0;
 for(const [i,value] of rows.entries()){const row=obj(value,['index','status','steps']);if(row.index!==i||!['ACCEPTED','REJECTED','FILTERED'].includes(String(row.status)))invalid();if(row.status==='ACCEPTED')accepted++;else if(row.status==='REJECTED')rejected++;else filtered++;const steps=arr(row.steps,16);if(steps.length!==e.definition.nodes.length)invalid();steps.forEach((value,index)=>{const s=obj(value,['nodeId','type','status','values','issues']);if(s.nodeId!==e.definition.nodes[index]?.id||s.type!==e.definition.nodes[index]?.type||!['OK','ERROR','FILTERED','SKIPPED'].includes(String(s.status)))invalid();if(!s.values||typeof s.values!=='object'||Array.isArray(s.values)||Object.keys(s.values).length>32)invalid();const standard='mappingPin' in e.definition.target&&e.definition.target.mappingPin&&s.status==='OK'&&['VALIDATE','OUTPUT'].includes(String(s.type));if(standard)parseStandardMetricRecord(s.values,e.definition.target,mapping);for(const [key,value]of Object.entries(s.values)){field(key);if(!standard&&(value!==null&&!['string','number','boolean'].includes(typeof value))||typeof value==='string'&&value.length>2048||typeof value==='number'&&!Number.isFinite(value))invalid()}for(const issue of arr(s.issues,64)){const issueRow=obj(issue,['field','code']);text(issueRow.field,80);text(issueRow.code,32);if(!/^[A-Z_]+$/.test(issueRow.code))invalid()}})}if(accepted!==rec.accepted||rejected!==rec.rejected||filtered!==rec.filtered)invalid();return v as Evaluation }
const messages:Record<string,string>={SOURCE_CHANGED:'来源项的主机、完整键、类型或单位已变化，请重新发现并显式选择',MAPPING_CHANGED:'标准指标映射与固定版本不匹配或不兼容，请核对完整摘要',OPERATOR_CHANGED:'固定算子摘要与当前实现不匹配，请核对版本',OPERATOR_PIN_REQUIRED:'请先固定算子版本，保存并重新预览后发布',CONFLICT:'草稿或版本已变化，请重新读取并比较后保存',PREVIEW_REQUIRED:'需要当前定义的有效预览：至少一条通过且没有拒绝记录，回执有效期15分钟',MODEL_CHANGED:'目标模型或固定版本已变化',CAPACITY:'工作流或运行回执已达到当前容量上限',FORBIDDEN:'当前身份没有此工作流、模型或来源的权限',INVALID_REQUEST:'配置或样本不符合工作流约束',INVALID_SAMPLE:'样本为空、超限或有无法读取的记录，请检查来源批次',SOURCE_UNAVAILABLE:'该来源当前不可用',BUSY:'工作流执行并发已满，请稍后显式重试'}
const request=(path:string,signal:AbortSignal,body?:unknown)=>platformClient.request('/api/v1/integrations/workflows'+path,{signal,body,error:(status,code)=>new Error(messages[code]??'工作流请求失败（HTTP '+status+'）')})
export const readWorkspace=async(signal:AbortSignal)=>workspace(await request('',signal))
export async function saveWorkflow(definition:Definition,layout:Layout,expectedEditVersion:number,signal:AbortSignal){parseWorkflow(definition);const e=entry(await request('/drafts',signal,{definition,layout,expectedEditVersion}));if(e.state!=='DRAFT'||e.editVersion!==expectedEditVersion+1||stable(e.definition)!==stable(definition)||stable(e.layout)!==stable(layout))invalid();return e}
export async function readWorkflow(id:string,revision:number,state:'DRAFT'|'PUBLISHED',signal:AbortSignal){const e=entry(await request('/'+(state==='DRAFT'?'drafts':'versions')+'/'+encodeURIComponent(id)+'/'+revision,signal));if(e.definition.id!==id||e.definition.revision!==revision||e.state!==state)invalid();return e}
export async function publishWorkflow(e:Entry,signal:AbortSignal){if(!e.preview)throw new Error('请先预览');const result=entry(await request('/publish',signal,{id:e.definition.id,revision:e.definition.revision,editVersion:e.editVersion,digest:e.digest,previewId:e.preview.id}));if(result.state!=='PUBLISHED'||result.digest!==e.digest||result.definition.id!==e.definition.id||result.definition.revision!==e.definition.revision)invalid();return result}
export async function evaluateWorkflow(e:Entry,samples:unknown,syncRunId:string,signal:AbortSignal,mapping?:MappingDefinition){if('mappingPin' in e.definition.target&&e.definition.target.mappingPin&&(!mapping||!sameMappingPin(mapping.mappingPin,e.definition.target.mappingPin)||mapping.metricKey!==e.definition.target.metricKey))throw new Error('当前标准指标映射不可用，请核对固定版本');const body={id:e.definition.id,revision:e.definition.revision,editVersion:e.editVersion,digest:e.digest,dryRun:true,...(e.definition.source.kind==='MANUAL_SAMPLE'?{samples}:e.definition.source.configuration?{}:{syncRunId})};return evaluation(await request(e.state==='DRAFT'?'/preview':'/run',signal,body),e,mapping)}
export function stable(v:unknown):string { if(Array.isArray(v))return '['+v.map(stable).join(',')+']';if(v&&typeof v==='object')return '{'+Object.entries(v).sort(([a],[b])=>a.localeCompare(b)).map(([k,v])=>JSON.stringify(k)+':'+stable(v)).join(',')+'}';return JSON.stringify(v) }
export function arrange(d:Definition):Layout {
  // 96px nodes leave 64px for a readable connector and arrow, including narrow viewports.
  const rowPitch = 160
  if(d.edges.length===d.nodes.length-1)return Object.fromEntries(d.nodes.map((n,i)=>[n.id,{x:180,y:40+i*rowPitch}]))
  const levels=new Map<string,number>()
  for(const node of d.nodes){const parents=d.edges.filter(edge=>edge.to===node.id);levels.set(node.id,parents.length?Math.max(...parents.map(edge=>levels.get(edge.from)??0))+1:0)}
  const rows=Array.from(new Set(levels.values()),level=>({level,peers:d.nodes.filter(node=>levels.get(node.id)===level)}))
  const center=Math.max(180,20+(Math.max(...rows.map(row=>row.peers.length))-1)*120)
  const layout:Layout={}
  for(const {level,peers} of rows)peers.forEach((node,i)=>{layout[node.id]={x:center+(i-(peers.length-1)/2)*240,y:40+level*rowPitch}})
  return layout
}
export function connect(d:Definition):Definition {return {...d,edges:d.nodes.slice(1).map((n,i)=>({from:d.nodes[i]!.id,to:n.id}))}}
export function template(model:Model,source:Definition['source'],catalog:OperatorCatalog):Definition {const mapping:Record<string,string>={};for(const f of model.definition.fields){if(source.kind==='ZABBIX_HOST'){if(f.id==='hostname'||f.id==='name')mapping.name=f.id;if(f.id==='ip')mapping.ip='ip'}else mapping[f.id]=f.id}if(!Object.keys(mapping).length)mapping.name=model.definition.fields[0]?.id??'name';const nodes:WorkflowNode[]=[{id:'source',type:'SOURCE',version:'1',config:{}},{id:'mapping',type:'MAP',version:'1',config:mapping},{id:'trim',type:'TRIM',version:'1',config:{}},{id:'validate',type:'VALIDATE',version:'1',config:{}},{id:'output',type:'OUTPUT',version:'1',config:{}}];return connect({schemaVersion:'2.0',id:'flow-'+crypto.randomUUID().slice(0,8),revision:1,name:source.kind==='ZABBIX_HOST'?'Zabbix 主机清洗':'实体数据清洗',source,target:{id:model.definition.id,revision:model.definition.revision,digest:model.digest},nodes:nodes.map(n=>pinNode(n,catalog)),edges:[]})}

export { entry as parseWorkflowEntry }
export function telemetryTemplate(kind: 'LOG' | 'METRIC', source: Definition['source'], catalog: OperatorCatalog): Definition {
  if (source.kind !== 'MANUAL_SAMPLE' && !(source.kind==='ZABBIX_LOG'&&kind==='LOG')) throw new Error('已有 Zabbix Host 批次只包含实体元数据，不能作为日志或指标样本')
  const nodes: WorkflowNode[] = [{ id: 'source', type: 'SOURCE', version: '1', config: {} }, { id: 'mapping', type: 'MAP', version: '1', config: source.kind==='ZABBIX_LOG'?{timestamp:'eventTime',body:'body'}:Object.fromEntries(telemetryFields(kind).map(f => [f.id, f.id])) }, { id: 'validate', type: 'VALIDATE', version: '1', config: {} }, { id: 'output', type: 'OUTPUT', version: '1', config: {} }]
  return connect({ schemaVersion: '2.0', id: 'flow-' + crypto.randomUUID().slice(0, 8), revision: 1, name: kind === 'LOG' ? '日志解析与清洗' : '指标数据转换', source, target: { kind, schemaVersion: '1.0' }, nodes: nodes.map(n=>pinNode(n,catalog)), edges: [] })
}

export function standardMetricTemplate(mapping:MappingDefinition,source:Definition['source'],catalog:OperatorCatalog):Definition {
 if(source.kind==='ZABBIX_HOST'||source.kind==='ZABBIX_LOG'||!supportsStandardMetricMapping(mapping)||source.metric&&source.metric.sourceKey!==mapping.sourceKey)throw new Error('这份映射不能用于当前指标格式');
 const d=telemetryTemplate('METRIC',{kind:'MANUAL_SAMPLE',instanceId:'manual'},catalog);return {...d,source,name:mapping.displayName+'转换',target:{kind:'METRIC',schemaVersion:'1.1',metricKey:mapping.metricKey,mappingPin:mapping.mappingPin},nodes:d.nodes.map(n=>n.type==='MAP'?{...n,config:{timestamp:'timestamp',sourceKey:'sourceKey',value:'value'}}:n)};
}
