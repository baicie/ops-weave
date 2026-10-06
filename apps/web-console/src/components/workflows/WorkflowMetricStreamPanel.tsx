import type { MetricStreamStatus, MetricStreamCommand, MetricStreamReceipt } from '../../api/workflow-metric-streams.ts'
import type { Entry } from '../../api/workflows.ts'
import { metricDefinitionHash } from '../../state/metric-definition-selection.ts'
import './workflow-metric-output.css'

export function WorkflowMetricStreamPanel(p:{entry:Entry;status:MetricStreamStatus|null;busy:boolean;pending:MetricStreamCommand|null;missing:boolean;receipt:MetricStreamReceipt|null;error:string;refresh:()=>void;control:(operation:MetricStreamCommand['operation'])=>void;query:()=>void;resend:()=>void;verify:()=>void;dismiss:()=>void}){
 const task=p.status?.task,pendingBatch=p.status?.batches.find(b=>b.id===task?.pendingBatchId),locked=p.busy||Boolean(p.pending),metric='metricKey' in p.entry.definition.target?p.entry.definition.target.metricKey:''
 const states={ABANDONED:'已终止恢复',RUNNING:'采集中',STOPPED:'已停止',FAILED:'已暂停',IN_FLIGHT:'写入待确认',UNKNOWN:'结果待确认',CONFIRMED:'已确认'}
 const reasons:Record<string,string>={INVALID_SAMPLE:'窗口数据格式或精度不符合要求，进度已保留。',WINDOW_INCOMPLETE:'窗口读取超过点数、请求数或时间上限，进度已保留。',SOURCE_WINDOW_CHANGED:'上一已确认窗口的旧点缺失或值已改变，采集已暂停。',SOURCE_CHANGED:'来源指标定义已改变。',MAPPING_CHANGED:'固定映射不可用。',OUTPUT_UNCONFIRMED:'输出结果待确认，请先核验原批次。',OUTPUT_REJECTED:'输出被拒绝，恢复将重新读取同一窗口。',AUTHORIZATION_EXPIRED:'后台授权已到期。',AUTHORIZATION_REVOKED:'后台授权已撤销。',EXECUTION_LIMIT:'本次采集额度已用完。',SOURCE_UNAVAILABLE:'来源读取失败，进度已保留。'}
 return <section className="workflow-metric-output" aria-label="持续指标采集">
  <header><h3>持续指标采集</h3><button disabled={locked} onClick={p.refresh}>刷新状态</button></header>
  <dl><dt>标准指标</dt><dd><a href={metricDefinitionHash(metric)}>{metric}</a></dd><dt>采集窗口</dt><dd>60 秒 · 延后 10 秒读取 · 每窗最多 600 点 · 回看上一分钟</dd></dl>
  {p.status?.available===false?<p role="status">当前身份未获准后台采集。</p>:null}
  {p.status?.mode==='DELEGATED_METRIC'?<p>后台授权最多 15 分钟、20 个窗口；退出登录后仍独立运行，可在这里停止。</p>:null}
  <div className="workflow-metric-output-actions">{!task&&p.status?.control?.recoveryClosed?null:!task?<button disabled={locked||p.status?.available!==true||p.status?.control?.startAllowed===false} onClick={()=>p.control('START')}>开始采集</button>:task.state==='ABANDONED'?null:task.state==='RUNNING'?<button disabled={locked} onClick={()=>p.control('STOP')}>停止采集</button>:<button disabled={locked||p.status?.available!==true||Boolean(task.pendingBatchId&&pendingBatch?.state!=='FAILED')} onClick={()=>p.control('RESUME')}>恢复采集</button>}
   {task?.state!=='ABANDONED'&&task?.pendingBatchId&&pendingBatch&&['IN_FLIGHT','UNKNOWN'].includes(pendingBatch.state)?<button disabled={locked} onClick={p.verify}>核验原批次</button>:null}
   {p.pending?<button disabled={p.busy} onClick={p.query}>查询原控制回执</button>:null}{p.missing?<><button disabled={p.busy} onClick={p.resend}>重发原控制请求</button><button disabled={p.busy} onClick={p.dismiss}>放弃本次确认</button></>:null}
  </div>
  {!task&&p.status?.control?.recoveryClosed?<p role="status">此版本已终止恢复，原输出仍待确认。</p>:null}
  {p.error?<p role="alert">{p.error}</p>:null}{p.pending?<p role="status">控制请求 <code>{p.pending.requestId}</code> 的结果待确认。</p>:null}
  {p.receipt?<p role="status">原操作已受理 · {p.receipt.operation==='STOP'?'停止':p.receipt.operation==='RESUME'?'恢复':'开始'} · 代数 {p.receipt.task.generation}</p>:null}
  {task?<article className="workflow-metric-output-receipt"><strong>{states[task.state]}</strong><span>已确认 {task.confirmedWindows} 窗口 · {task.confirmedPoints} 点 · 本次 {task.sessionBatches}/20 批次</span><small>{task.confirmedWindows?'已确认至':'开始于'} <time>{new Date(task.cursor).toISOString()}</time></small>{task.error?<p role="alert">{task.state==='ABANDONED'?'已终止恢复，原输出仍待确认。':reasons[task.error]??'采集已暂停，进度已保留。'}（{task.error}）</p>:null}{task.authorization?<small>授权至 {new Date(task.authorization.expiresAt).toLocaleString()} · 已用 {task.authorization.consumedBatches}/{task.authorization.maxBatches}</small>:null}</article>:null}
  {p.status?.batches.length?<div className="workflow-metric-output-table"><table><caption>最近窗口 · {p.status.batches.length}</caption><thead><tr><th>时间窗口</th><th>类型</th><th>状态</th><th>输入 / 确认点</th><th>过滤 / 合并</th><th>批次</th></tr></thead><tbody>{p.status.batches.map(b=><tr key={b.id}><td><time>{new Date(b.from).toISOString()}</time><br/><time>{new Date(b.till).toISOString()}</time></td><td>{b.reconcilesBatchId?`迟到补采 · ${b.latePoints} 点`:'常规采集'}</td><td>{b.state==='FAILED'?'输出被拒绝':states[b.state]}</td><td>{b.inputCount} / {b.state==='CONFIRMED'?b.timestamps.length:0}</td><td>{b.filtered} / {b.collapsed}</td><td><details><summary>查看摘要</summary><code>{b.id}</code><br/><code>{b.batchDigest}</code>{b.reconcilesBatchId?<><br/>原批次 <code>{b.reconcilesBatchId}</code></>:null}</details></td></tr>)}</tbody></table></div>:null}
 </section>
}
