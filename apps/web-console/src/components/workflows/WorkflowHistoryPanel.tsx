import type {Entry} from '../../api/workflows.ts'
import type {DiagnosticObservation} from '../../api/workflow-diagnostics.ts'
import type {WorkflowHistoryPage} from '../../api/workflow-history.ts'
import {WorkflowDiagnosticDetail} from './WorkflowDiagnosticDetail.tsx'
import './workflow-history.css'
const states={CHECKED:'检查通过',REJECTED:'校验拒绝',SOURCE_FAILED:'检查未完成'},coverage={COMPLETE:'完整',PARTIAL:'部分',UNAVAILABLE:'不可用'}
const count=(v:number|null)=>v===null?'—':String(v)
const stateTone=(state:keyof typeof states)=>state==='CHECKED'?'success':state==='REJECTED'?'danger':'warning'
export function WorkflowHistoryPanel(p:{entries:Entry[];entry:Entry|null;report:WorkflowHistoryPage|null;items:DiagnosticObservation[];selected:DiagnosticObservation|null;busy:boolean;error:string;selectVersion:(key:string)=>void;select:(o:DiagnosticObservation|null)=>void;refresh:()=>void;more:()=>void}){
 const summary=p.items.reduce((result,item)=>({
  checks:result.checks+1,
  passed:result.passed+(item.state==='CHECKED'?1:0),
  rejected:result.rejected+(item.state==='REJECTED'?1:0),
  sourceFailed:result.sourceFailed+(item.state==='SOURCE_FAILED'?1:(item.sourceRead?.failed??0)>0?1:0),
 }),{checks:0,passed:0,rejected:0,sourceFailed:0})
 return <section className="workflow-history-panel" aria-label="运行检查历史"><div className="workflow-history-toolbar"><label>发布版本<select aria-label="历史发布版本" value={p.entry?p.entry.definition.id+'@'+p.entry.definition.revision:''} disabled={p.busy} onChange={e=>p.selectVersion(e.target.value)}><option value="">选择发布版本</option>{p.entries.map(e=><option key={e.definition.id+'@'+e.definition.revision} value={e.definition.id+'@'+e.definition.revision}>{e.definition.name+' · '+e.definition.id+' · v'+e.definition.revision}</option>)}</select></label><button disabled={p.busy||!p.entry} onClick={p.refresh}>刷新运行历史</button></div>
 {p.entry?<p className="workflow-history-version"><code>{p.entry.definition.id+' · v'+p.entry.definition.revision}</code><small><code>{p.entry.digest}</code></small></p>:<p role="status">{p.entries.length?'选择发布版本以读取历史。':'暂无可读取运行历史的固定来源发布版本。'}</p>}
 {p.error?<p role="alert">{p.error}</p>:null}{p.busy?<p role="status">正在读取运行历史…</p>:null}
 {p.report?<><div className="workflow-history-meta"><span>{'已显示 '+p.items.length+' / '+p.report.recordedCount+' 条'}</span><span>快照 <time>{p.report.snapshotAt}</time></span></div><div className="workflow-history-summary" aria-label="当前版本运行概览"><div><span>已记录检查</span><strong>{summary.checks}</strong><small>当前读取页</small></div><div data-tone="success"><span>检查通过</span><strong>{summary.passed}</strong><small>来源与转换均完成</small></div><div data-tone="danger"><span>校验拒绝</span><strong>{summary.rejected}</strong><small>需要查看节点明细</small></div><div data-tone="warning"><span>来源异常</span><strong>{summary.sourceFailed}</strong><small>未完成的检查</small></div></div><div className="workflow-quality-table" role="region" aria-label="运行检查历史表" tabIndex={0}><table><caption>当前版本已记录的来源与转换检查</caption><thead><tr><th>检查时间 / UUID</th><th>检查结果</th><th>来源失败 / 尝试</th><th>输入</th><th>通过</th><th>拒绝</th><th>过滤</th><th>未检查</th><th>本地排队</th><th>操作</th></tr></thead><tbody>{p.items.map(o=><tr key={o.id} data-selected={p.selected?.id===o.id}><td><time>{o.completedAt}</time><small><code>{o.id}</code></small></td><td><span className="workflow-history-state" data-tone={stateTone(o.state)}>{states[o.state]}</span><small>{coverage[o.result.coverage]}</small></td><td>{o.sourceRead?o.sourceRead.failed+' / '+o.sourceRead.attempts:'—'}</td>{(['received','accepted','rejected','filtered','unknown'] as const).map(k=><td key={k} data-count={k}>{count(o.result[k])}</td>)}<td>{o.queueWaitMillis===null?'—':o.queueWaitMillis+' ms'}</td><td><button onClick={()=>p.select(o)} aria-label={'查看检查 '+o.id}>节点明细</button></td></tr>)}</tbody></table></div>{!p.items.length?<p role="status">此版本暂无已记录的检查。</p>:null}<div className="workflow-history-footer"><p>仅覆盖已保存的检查；通过数表示转换校验通过，输出确认请查看关联批次。</p>{p.report.hasMore?<button disabled={p.busy} onClick={p.more}>加载更早记录</button>:<span>已到最早记录</span>}</div></>:null}
 {p.selected?<WorkflowDiagnosticDetail selected={p.selected} onClose={()=>p.select(null)}/>:null}
 </section>
}
