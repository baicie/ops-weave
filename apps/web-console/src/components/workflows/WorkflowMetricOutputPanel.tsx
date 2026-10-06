import type { MetricOutputData, MetricOutputReceipt } from '../../api/workflow-metric-outputs.ts'
import type { Entry } from '../../api/workflows.ts'
import { metricDefinitionHash } from '../../state/metric-definition-selection.ts'
import './workflow-metric-output.css'

export function WorkflowMetricOutputPanel(p:{entry:Entry;available:boolean|null;busy:boolean;pendingId:string|null;missing:boolean;receipt:MetricOutputReceipt|null;records:MetricOutputReceipt[];truncated:boolean;data:MetricOutputData|null;error:string;notice:string;write:()=>void;query:()=>void;verify:()=>void;points:()=>void;refresh:()=>void;resubmit:()=>void;dismiss:()=>void;select:(receipt:MetricOutputReceipt)=>void}){
 const receipt=p.receipt,states={PENDING:'待确认',UNKNOWN:'结果待确认',CONFIRMED:'已确认',FAILED:'写入失败'}
 const target=p.entry.definition.target,metric='metricKey' in target?target.metricKey:''
 return <section className="workflow-metric-output" aria-label="指标样本输出">
  <header><h3>指标样本输出</h3><button disabled={p.busy||Boolean(p.pendingId)} onClick={p.refresh}>刷新记录</button></header>
  <dl><dt>标准指标</dt><dd><a href={metricDefinitionHash(metric)}>{metric}</a></dd><dt>完整来源键</dt><dd><code>{p.entry.definition.source.metric?.sourceKey}</code></dd></dl>
  <p>写入当前固定版本读取的最多5个样本点；完整批次回读匹配后确认。持续采集在下方独立管理。</p>
  {p.available===false?<p role="status">当前环境未配置指标输出。</p>:null}
  <div className="workflow-metric-output-actions"><button disabled={p.busy||p.available!==true||Boolean(p.pendingId)} onClick={p.write}>写入样本</button>
   {p.pendingId?<><button disabled={p.busy} onClick={p.query}>查询原回执</button><button disabled={p.busy||p.missing} onClick={p.verify}>验证批次</button></>:null}
   {p.missing?<><button disabled={p.busy} onClick={p.resubmit}>重发原请求</button><button disabled={p.busy} onClick={p.dismiss}>放弃本次确认</button></>:null}
   {receipt?<button disabled={p.busy} onClick={p.points}>回读指标点</button>:null}
  </div>
  {p.pendingId?<p role="status">原请求 <code>{p.pendingId}</code>{p.missing?' 尚未找到；重发仅使用原内容。放弃不会取消服务器写入。':' 的结果待确认。'}</p>:null}
  {p.error?<p role="alert">{p.error}</p>:null}{p.notice?<p role="status">{p.notice}</p>:null}
  {receipt?<article className="workflow-metric-output-receipt"><strong>{states[receipt.state]}</strong><span>确认 {receipt.confirmed} · 失败 {receipt.failed} · 未知 {receipt.unknown} · 过滤 {receipt.filtered} · 合并 {receipt.collapsed}</span><small>请求 <code>{receipt.requestId}</code></small><time>{new Date(receipt.updatedAt).toLocaleString()}</time><details><summary>批次与系列</summary><dl><dt>系列摘要</dt><dd><code>{receipt.seriesHash}</code></dd><dt>批次摘要</dt><dd><code>{receipt.batchDigest}</code></dd><dt>来源指标项</dt><dd>{receipt.labels.external_item_id}</dd><dt>来源主机</dt><dd>{receipt.labels.host_external_id}</dd><dt>固定版本</dt><dd>v{receipt.revision}</dd></dl></details></article>:null}
  {p.data?<div className="workflow-metric-output-data"><p role="status">本次回读 {p.data.points.length}/{p.data.expectedPoints} 点 · {p.data.proofMatches?'完整批次匹配':'批次尚未完整匹配'} · {new Date(p.data.queriedAt).toLocaleString()}</p><div className="workflow-metric-output-table"><table><thead><tr><th>时间</th><th>标准值</th><th>单位</th></tr></thead><tbody>{p.data.points.map(point=><tr key={point.timestampMillis}><td><time>{new Date(point.timestampMillis).toISOString()}</time></td><td>{point.value}</td><td>{receipt?.labels.unit}</td></tr>)}</tbody></table></div></div>:null}
  {p.records.length?<details><summary>最近写入记录 · {p.records.length}{p.truncated?'（部分）':''}</summary><ul className="workflow-metric-output-history">{p.records.map(r=><li key={r.requestId}><button disabled={p.busy||Boolean(p.pendingId)} onClick={()=>p.select(r)}><span>{states[r.state]} · {new Date(r.createdAt).toLocaleString()}</span><code>{r.requestId}</code></button></li>)}</ul></details>:null}
 </section>
}
