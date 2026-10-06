import {platformClient} from './http.ts'
import {logPosition} from './workflow-log-streams.ts'
import {parseDiagnosticObservation,type DiagnosticObservation} from './workflow-diagnostics.ts'
import type {Entry} from './workflows.ts'

export type WorkflowHistoryPage={schemaVersion:'2.0';asOf:string;snapshotAt:string;reference:DiagnosticObservation['reference'];coverage:'RECORDED_CHECKS';offset:number;recordedCount:number;items:DiagnosticObservation[];nextCursor:string|null;hasMore:boolean}
export class HistoryError extends Error{constructor(readonly status:number){super(status===409?'历史快照已失效，请刷新运行记录。':'运行历史读取失败 · HTTP '+status)}}
const token=/^h1\.[A-Za-z0-9_-]{1,1021}$/
function invalid():never{throw Error('运行历史响应不符合契约，请刷新运行记录。')}
function object(v:unknown,keys:string[]):Record<string,unknown>{if(!v||typeof v!=='object'||Array.isArray(v)||Object.keys(v).length!==keys.length||keys.some(k=>!Object.hasOwn(v,k)))invalid();return v as Record<string,unknown>}
function integer(v:unknown,min:number,max:number){if(!Number.isSafeInteger(v)||Number(v)<min||Number(v)>max)invalid();return Number(v)}
function order(a:DiagnosticObservation,b:DiagnosticObservation){const at=logPosition(a.completedAt),bt=logPosition(b.completedAt);return at===bt?a.id.localeCompare(b.id):at>bt?-1:1}
export function historyEligible(e:Entry){const s=e.definition.source,t=e.definition.target;return e.state==='PUBLISHED'&&Boolean(s.configuration)&&(s.kind==='ZABBIX_HOST'||s.kind==='ZABBIX_METRIC'&&s.metric&&'mappingPin' in t&&t.mappingPin||s.kind==='ZABBIX_LOG'&&s.log)&&(['ZABBIX_HOST','ZABBIX_METRIC','ZABBIX_LOG'].includes(s.kind))}
export function parseWorkflowHistory(v:unknown,entry:Entry,previous?:WorkflowHistoryPage):WorkflowHistoryPage{
 const p=object(v,['schemaVersion','asOf','snapshotAt','reference','coverage','offset','recordedCount','items','nextCursor','hasMore']),r=object(p.reference,['id','revision','digest']);if(p.schemaVersion!=='2.0'||p.coverage!=='RECORDED_CHECKS'||r.id!==entry.definition.id||r.revision!==entry.definition.revision||r.digest!==entry.digest||typeof p.hasMore!=='boolean'||!Array.isArray(p.items))invalid();const offset=integer(p.offset,0,180),total=integer(p.recordedCount,0,200),snapshot=logPosition(p.snapshotAt),now=logPosition(p.asOf)
 if(offset%20||offset>total||total>0&&offset>=total||snapshot>now||now-snapshot>=600000000000n||p.items.length!==Math.min(20,total-offset)||p.hasMore!==(offset+p.items.length<total)||p.hasMore!==(p.nextCursor!==null)||p.nextCursor!==null&&(typeof p.nextCursor!=='string'||!token.test(p.nextCursor)))invalid()
 const items=p.items.map(o=>parseDiagnosticObservation(o,entry));if(new Set(items.map(o=>o.id)).size!==items.length||items.some((o,i)=>logPosition(o.completedAt)>snapshot||i>0&&order(items[i-1],o)>=0))invalid()
 if(previous){if(!previous.hasMore||p.snapshotAt!==previous.snapshotAt||p.recordedCount!==previous.recordedCount||offset!==previous.offset+previous.items.length||now<logPosition(previous.asOf)||p.nextCursor!==null&&p.nextCursor===previous.nextCursor||items.some(o=>previous.items.some(old=>old.id===o.id))||previous.items.length&&items.length&&order(previous.items.at(-1)!,items[0])>=0)invalid()}else if(offset!==0)invalid()
 return v as WorkflowHistoryPage
}
export async function workflowHistory(entry:Entry,signal:AbortSignal,previous?:WorkflowHistoryPage){if(!historyEligible(entry))throw Error('请选择具有固定来源的发布版本。');const cursor=previous?.nextCursor;if(previous&&!cursor)invalid();const path='/api/v1/integrations/workflows/quality/workflows/'+entry.definition.id+'/versions/'+entry.definition.revision+'/history'+(cursor?'?cursor='+encodeURIComponent(cursor):'');return parseWorkflowHistory(await platformClient.request(path,{signal,error:status=>new HistoryError(status)}),entry,previous)}
