import { parseWorkflowSource } from './workflows.ts'
import { outputKind, parseOutput, type OutputTarget } from './workflow-output.ts'
import { platformClient } from './http.ts'
import { receipt, type Run, type NodeType } from './workflows.ts'
export const nodeLabels:Record<NodeType,string>={SOURCE:'数据输入',MAP:'字段映射',TRIM:'去除空白',EMPTY_TO_NULL:'空串转空值',DEFAULT:'补充默认值',ENUM_MAP:'枚举替换',SCALE:'数值换算',FILTER:'条件过滤',MERGE:'分支合流',VALIDATE:'模型校验',OUTPUT:'输出预览'}
export const issueLabels:Record<string,string>={UNKNOWN_FIELD:'输出未定义该字段',MISSING_REQUIRED:'缺少必填字段',NULL_REQUIRED:'必填字段为空值',TOO_LONG:'文本超过长度限制',EMPTY_REQUIRED:'必填字段为空文本',ENUM_MISMATCH:'值不在允许的枚举范围',OUT_OF_RANGE:'数值超出允许范围',TYPE_MISMATCH:'字段类型不匹配',NUMERIC_LIMIT:'数值超出精度或安全范围',TRANSFORM_FAILED:'清洗转换失败',MERGE_CONFLICT:'合流分支的同字段值冲突'}
export type TraceStep={nodeId:string;type:NodeType;status:'OK'|'ERROR'|'FILTERED'|'SKIPPED';issues:{field:string;code:string}[]}
export type TraceRow={index:number;status:'ACCEPTED'|'REJECTED'|'FILTERED';steps:TraceStep[]}
export type Trace={source:import('./workflows.ts').WorkflowSource;target:OutputTarget;syncRunId:string|null;startedAt:string;durationMillis:number;retainedCount:number;missingRaw:number;truncated:boolean;sourceStatus:'MANUAL_SAMPLE'|'SUCCEEDED'|'FAILED';dryRun:true;writesPerformed:false;rows:TraceRow[]}
export type RunDetail={schemaVersion:'2.0';run:Run;trace:Trace|null}
export const runOutcome=(run:Run)=>run.receipt.rejected?(run.receipt.accepted||run.receipt.filtered?'PARTIAL':'FAILED'):run.receipt.accepted?'SUCCEEDED':'FILTERED'
export const outcomeLabels:Record<string,string>={SUCCEEDED:'全部通过',PARTIAL:'部分失败',FAILED:'全部失败',FILTERED:'全部过滤'}
function invalid():never{throw new Error('运行记录不符合契约，未展示未验证明细')}
function obj(v:unknown,keys:string[]):Record<string,unknown>{if(!v||typeof v!=='object'||Array.isArray(v)||Object.keys(v).some(k=>!keys.includes(k))||keys.some(k=>!Object.hasOwn(v,k)))invalid();return v as Record<string,unknown>}
function arr(v:unknown,min:number,max:number):unknown[]{if(!Array.isArray(v)||v.length<min||v.length>max)invalid();return v}
function text(v:unknown,pattern:RegExp):asserts v is string{if(typeof v!=='string'||!pattern.test(v))invalid()}
function integer(v:unknown,min=0,max=Number.MAX_SAFE_INTEGER){if(!Number.isSafeInteger(v)||(v as number)<min||(v as number)>max)invalid()}
const uuid=/^[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}$/
export function parseRunDetail(v:unknown,id:string):RunDetail{
 const d=obj(v,['schemaVersion','run','trace']);if(d.schemaVersion!=='2.0')invalid();const r=obj(d.run,['workflowId','revision','mode','receipt']);text(r.workflowId,/^[a-z][a-z0-9_-]{0,47}$/);integer(r.revision,1,10000);if(!['PREVIEW','RUN'].includes(String(r.mode)))invalid();const rec=receipt(r.receipt);if(rec.id!==id)invalid();if(d.trace===null)return v as RunDetail
 const t=obj(d.trace,['source','target','syncRunId','startedAt','durationMillis','retainedCount','missingRaw','truncated','sourceStatus','dryRun','writesPerformed','rows']);const source=parseWorkflowSource(t.source);text(source.instanceId,/^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$/);if(!['MANUAL_SAMPLE','ZABBIX_HOST','ZABBIX_METRIC'].includes(String(source.kind)))invalid();const target=parseOutput(t.target);if(source.kind==='ZABBIX_HOST'&&outputKind(target)!=='ENTITY'||source.kind==='ZABBIX_METRIC'&&(!('mappingPin' in target)||!target.mappingPin))invalid()
 if(t.syncRunId!==null)text(t.syncRunId,uuid);if(typeof t.startedAt!=='string'||!Number.isFinite(Date.parse(t.startedAt))||Date.parse(t.startedAt)>Date.parse(rec.createdAt))invalid();for(const key of ['durationMillis','retainedCount','missingRaw'])integer(t[key]);if(typeof t.truncated!=='boolean'||t.dryRun!==true||t.writesPerformed!==false)invalid()
 if(source.configuration && rec.origin!=='zabbix-jsonrpc')invalid();const manual=source.kind==='MANUAL_SAMPLE';if(manual?(source.instanceId!=='manual'||t.syncRunId!==null||rec.origin!=='MANUAL_SAMPLE'||t.sourceStatus!=='MANUAL_SAMPLE'||t.missingRaw!==0||t.truncated):((source.configuration?t.syncRunId!==null:t.syncRunId===null)||rec.origin==='MANUAL_SAMPLE'||!['SUCCEEDED','FAILED'].includes(String(t.sourceStatus))))invalid()
 const rows=arr(t.rows,1,5);if(Number(t.retainedCount)<rows.length||manual&&t.retainedCount!==rows.length)invalid();let accepted=0,rejected=0,filtered=0;let signature=''
 rows.forEach((value,i)=>{const row=obj(value,['index','status','steps']);if(row.index!==i)invalid();const steps=arr(row.steps,4,16);let error=false,branchFiltered=false;const ids=new Set();const keys:string[]=[]
  steps.forEach((value,j)=>{const step=obj(value,['nodeId','type','status','issues']);text(step.nodeId,/^[a-z][a-z0-9_-]{0,31}$/);if(ids.has(step.nodeId)||!Object.hasOwn(nodeLabels,String(step.type))||!['OK','ERROR','FILTERED','SKIPPED'].includes(String(step.status)))invalid();ids.add(step.nodeId);keys.push(step.nodeId+':'+step.type)
   if(j===0?step.type!=='SOURCE':j===1?step.type!=='MAP':j===steps.length-2?step.type!=='VALIDATE':j===steps.length-1?step.type!=='OUTPUT':['SOURCE','MAP','VALIDATE','OUTPUT'].includes(String(step.type)))invalid()
   const issues=arr(step.issues,0,64);if((step.status==='ERROR')!==(issues.length>0)||step.status==='FILTERED'&&step.type!=='FILTER')invalid();for(const value of issues){const issue=obj(value,['field','code']);text(issue.field,/^[a-zA-Z][a-zA-Z0-9_]{0,47}$/);if(!Object.hasOwn(issueLabels,String(issue.code)))invalid()}
   error ||= step.status==='ERROR';branchFiltered ||= step.status==='FILTERED';if(j===0&&step.status!=='OK')invalid()
  });const status=error?'REJECTED':(steps.at(-1) as Record<string,unknown>).status==='OK'?'ACCEPTED':branchFiltered?'FILTERED':'INVALID';if(row.status!==status)invalid();const key=keys.join('|');if(i===0)signature=key;else if(signature!==key)invalid();if(status==='ACCEPTED')accepted++;else if(status==='REJECTED')rejected++;else filtered++
 });if(accepted!==rec.accepted||rejected!==rec.rejected||filtered!==rec.filtered)invalid();return v as RunDetail
}
export async function readWorkflowRun(id:string,signal:AbortSignal){if(!uuid.test(id))throw new Error('请输入有效的运行 ID（UUID）');const value=await platformClient.request('/api/v1/integrations/workflows/runs/'+id,{signal,error:status=>new Error(status===404?'记录不存在或当前会话无权读取':status===403?'当前会话无运行记录读取权限':'读取运行记录失败（HTTP '+status+'）')});return parseRunDetail(value,id)}
