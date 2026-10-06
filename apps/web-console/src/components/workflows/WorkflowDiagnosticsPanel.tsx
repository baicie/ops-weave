import {WorkflowDiagnosticDetail} from './WorkflowDiagnosticDetail.tsx'
import type {DiagnosticObservation,DiagnosticReport} from '../../api/workflow-diagnostics.ts'
import './workflow-quality.css'
const states={CHECKED:'检查通过',REJECTED:'校验拒绝',SOURCE_FAILED:'检查未完成'},coverage={COMPLETE:'完整',PARTIAL:'部分',UNAVAILABLE:'不可用'}
const count=(v:number|null)=>v===null?'—':String(v)
export function WorkflowDiagnosticsPanel(p:{report:DiagnosticReport|null;selected:DiagnosticObservation|null;busy:boolean;error:string;lookup:string;setLookup:(v:string)=>void;refresh:()=>void;find:()=>void;select:(v:DiagnosticObservation|null)=>void}){
 const selected=p.selected,sourceMeasured=Boolean(p.report?.observations.some(o=>o.sourceRead))
 return <section className="workflow-quality" aria-label="检查统计"><header><h3>检查统计</h3><button disabled={p.busy} onClick={p.refresh}>刷新检查</button></header>
 {p.error?<p role="alert">{p.error}</p>:null}{p.busy?<p role="status">正在读取检查记录…</p>:null}
 {p.report?<><form className="workflow-quality-lookup" onSubmit={e=>{e.preventDefault();p.find()}}><label>按检查查询<input aria-label="检查 UUID" value={p.lookup} onChange={e=>p.setLookup(e.target.value)} maxLength={36} placeholder="完整检查 UUID"/></label><button disabled={p.busy||!p.lookup}>查询检查</button></form>
 <div className="workflow-quality-table" role="region" aria-label="检查统计表" tabIndex={0}><table><caption>当前版本最近 {p.report.observations.length} 次检查{p.report.truncated?' · 还有更早记录':''}</caption><thead><tr><th>时间 / 检查</th><th>结果</th>{sourceMeasured?<th>来源失败 / 尝试</th>:null}<th>输入</th><th>通过</th><th>拒绝</th><th>过滤</th><th>未检查</th><th>结构不符</th><th>缺少标识</th><th>时间异常</th><th>单位异常</th></tr></thead><tbody>{p.report.observations.map(o=><tr key={o.id} data-selected={o.id===selected?.id}><td><button className="workflow-quality-id" onClick={()=>p.select(o)} aria-label={'查看检查 '+o.id}><time>{o.completedAt}</time></button><small><code>{o.id}</code></small></td><td>{states[o.state]}<small>{coverage[o.result.coverage]}</small></td>{sourceMeasured?<td>{o.sourceRead?o.sourceRead.failed+' / '+o.sourceRead.attempts:'—'}</td>:null}{(['received','accepted','rejected','filtered','unknown','schemaMismatch','missingIdentity','invalidTimestamp','unitMismatch'] as const).map(k=><td key={k}>{count(o.result[k])}</td>)}</tr>)}</tbody></table></div>
 {!p.report.observations.length?<p role="status">此版本暂无检查统计。</p>:null}<p className="workflow-quality-note">通过数指转换校验通过，不代表写入确认。提前中止后的剩余记录计入未检查；异常类别可能重叠。</p></>:null}
 {selected?<WorkflowDiagnosticDetail selected={selected} onClose={()=>p.select(null)}/>:null}
 </section>
}
