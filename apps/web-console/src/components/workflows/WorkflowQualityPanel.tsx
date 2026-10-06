import type { QualityBatch,QualityReport } from '../../api/workflow-quality.ts'
import './workflow-quality.css'

const states:Record<string,string>={ABANDONED:'已终止恢复',RUNNING:'运行中',STOPPED:'已停止',FAILED:'已失败',READY:'等待执行',IN_FLIGHT:'写入待确认',UNKNOWN:'结果未知',CONFIRMED:'已确认'}
const units={ENTITY:'实体',POINT:'指标点',LOG_RECORD:'日志条目'}
const count=(n:number|null)=>n===null?'—':String(n)
function ratio(n:number|null,d:number|null){return n===null||d===null||d===0?'—':(100*n/d).toLocaleString('zh-CN',{maximumFractionDigits:1})+'%'}
export function WorkflowQualityPanel(p:{report:QualityReport|null;selected:QualityBatch|null;busy:boolean;error:string;lookup:string;setLookup:(v:string)=>void;refresh:()=>void;find:()=>void;select:(b:QualityBatch|null)=>void}){
 const task=p.report?.task,b=p.selected,c=b?.counts
 return <section className="workflow-quality" aria-label="运行质量与异常">
  <header><h3>运行质量与异常</h3><button disabled={p.busy} onClick={p.refresh}>刷新质量</button></header>
  {p.error?<p role="alert">{p.error}</p>:null}{p.busy?<p role="status">正在读取质量记录…</p>:null}
  {p.report?<>
   <dl className="workflow-quality-summary"><div><dt>采集任务</dt><dd>{task?task.state==='FAILED'?'已暂停':states[task.state]:'未启动'}</dd></div><div><dt>任务更新时间</dt><dd>{task?<time>{task.updatedAt}</time>:'—'}</dd></div><div><dt>报告读取时间</dt><dd><time>{p.report.asOf}</time></dd></div></dl>
   {task?.error?<p role="alert">任务异常 <code>{task.error}</code></p>:null}{task?.pendingBatchId?<p>待确认批次 <button className="workflow-quality-id" disabled={p.busy} onClick={()=>{p.setLookup(task.pendingBatchId!);p.select(p.report!.batches.find(b=>b.id===task.pendingBatchId)??null)}}>{task.pendingBatchId}</button></p>:null}
   <form onSubmit={e=>{e.preventDefault();p.find()}} className="workflow-quality-lookup"><label>按批次查询<input aria-label="质量批次 UUID" value={p.lookup} onChange={e=>p.setLookup(e.target.value)} placeholder="完整批次 UUID" maxLength={36} /></label><button disabled={p.busy||!p.lookup}>查询批次</button></form>
   <div className="workflow-quality-table" role="region" aria-label="批次质量表" tabIndex={0}><table><caption>当前版本最近 {p.report.batches.length} 个批次{p.report.truncated?' · 还有更早记录':''}</caption><thead><tr><th>范围 / 批次</th><th>状态</th><th>输入</th><th>通过</th><th>拒绝</th><th>过滤</th><th>去重</th><th>确认</th><th>未知</th><th>确认率</th></tr></thead><tbody>{p.report.batches.map(row=><tr key={row.id} data-selected={row.id===b?.id}><td><button className="workflow-quality-id" onClick={()=>p.select(row)} aria-label={'查看质量批次 '+row.id}><code>{row.id}</code></button><small>{row.from?<><time>{row.from}</time> — <time>{row.till}</time></>:<time>{row.observedAt}</time>}{row.reconcilesBatchId?' · 迟到补采':''} · {units[row.unit]}</small></td><td>{states[row.state]}</td>{(['input','accepted','rejected','filtered','deduplicated','confirmed','unknown'] as const).map(key=><td key={key}>{count(row.counts[key])}</td>)}<td>{ratio(row.counts.confirmed,row.counts.outputExpected)}</td></tr>)}</tbody></table></div>
   {!p.report.batches.length?<p role="status">此版本暂无已记录批次。</p>:null}
   <p className="workflow-quality-note">逐批统计，不累加重叠窗口。确认率 = 本批确认数 / 本批预期输出数；分母缺失或为 0 时显示“—”。</p>
  </>:null}
  {b&&c?<article className="workflow-quality-detail" aria-label="质量批次详情"><header><h4>批次详情</h4><button onClick={()=>p.select(null)}>关闭详情</button></header><dl><dt>批次</dt><dd><code>{b.id}</code></dd><dt>状态</dt><dd>{states[b.state]}</dd><dt>统计范围</dt><dd>{b.coverage==='WINDOW'?'本次完整窗口读取':'本次来源分页'} · {units[b.unit]}</dd><dt>采样率</dt><dd>—（未记录总体分母）</dd><dt>转换通过 / 拒绝</dt><dd>{count(c.accepted)} / {count(c.rejected)}</dd><dt>预期输出 / 确认</dt><dd>{count(c.outputExpected)} / {count(c.confirmed)}</dd><dt>输出拒绝 / 未知 / 待确认</dt><dd>{count(c.outputRejected)} / {count(c.unknown)} / {count(c.pending)}</dd>{b.unit==='POINT'?<><dt>重复提交的指标点</dt><dd>{count(c.repeatedOutput)}</dd></>:null}<dt>迟到条目 / 点</dt><dd>{count(c.late)}</dd><dt>更新时间</dt><dd><time>{b.updatedAt}</time></dd>{b.reconcilesBatchId?<><dt>原批次</dt><dd><code>{b.reconcilesBatchId}</code></dd></>:null}{b.error?<><dt>异常代码</dt><dd><code>{b.error}</code></dd></>:null}</dl>{b.unit==='ENTITY'?<p>主机批次未保留转换分项和输出分母，确认数不用于推算通过率。</p>:null}</article>:null}
 </section>
}
