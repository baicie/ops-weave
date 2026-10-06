import type {ReactNode} from 'react'
import {Button} from '@/components/ui/button'
import {metricDefinitionHash} from '../../state/metric-definition-selection.ts'
import type {Entry} from '../../api/workflows.ts'
import type {ReplayPlan as MetricReplayPlan,ReplayReceipt} from '../../api/workflow-metric-replay.ts'
import type {ReplayPlan as LogReplayPlan} from '../../api/workflow-log-replay.ts'
type ReplayPlan=MetricReplayPlan|LogReplayPlan
import './workflow-metric-replay.css'

const states:Record<string,string>={PREPARING:'范围检查待确认',READY:'待执行',PENDING:'执行结果待确认',UNKNOWN:'输出待确认',CONFIRMED:'已确认',FAILED:'已拒绝'}
const rangeStates={PREPARING:'检查待确认',READY:'范围已固定',FAILED:'检查失败'}
export type WorkflowReplayPanelProps={entry:Entry;start:string;lookup:string;plans:ReplayPlan[];truncated:boolean;plan:ReplayPlan|null;receipt:ReplayReceipt|null;busy:boolean;pending:boolean;missing:boolean;error:string;loaded:boolean;changeStart:(s:string)=>void;changeLookup:(s:string)=>void;prepare:()=>void;execute:()=>void;query:()=>void;resend:()=>void;dismiss:()=>void;select:(id:string)=>void;lookupPlan:()=>void;refresh:()=>void;verify:()=>void;readData?:()=>void;children?:ReactNode}
export function WorkflowReplayPanel(p:WorkflowReplayPanelProps&{kind:'metric'|'log'}){
 const locked=p.busy||p.pending,proof=p.plan?.proof,log=p.kind==='log',title=log?'日志历史重放':'指标历史重放'
 const size=(r:ReplayPlan)=>r.proof?('indices' in r.proof?r.proof.indices.length:r.proof.timestamps.length):0
 return <section className="workflow-metric-replay-panel" aria-label={title}>
  <p className="workflow-replay-impact">{log?'重建过去24小时内的60秒日志窗，最多1,000条。结果保存在独立重放日志；不改变持续任务检查点，不发送通知或执行动作。':'重建过去24小时内的60秒指标窗，最多60条。结果保存在独立重放系列；不改变持续任务检查点，不发送通知或执行动作。'}</p>
  <div className="workflow-replay-form"><label>历史起始时间<input type="datetime-local" step="1" aria-label="重放起始时间" value={p.start} disabled={locked} onChange={e=>p.changeStart(e.target.value)}/></label><Button variant="outline" disabled={locked||!p.start} onClick={p.prepare}>检查重放范围</Button><Button variant="ghost" disabled={locked} onClick={p.refresh}>刷新重放记录</Button></div>
  {p.error?<p role="alert">{p.error}</p>:null}{p.busy?<p role="status">正在处理…</p>:null}
  {p.pending?<div className="workflow-replay-pending"><p>响应尚未确认，请查询原请求。关闭页面不会取消服务器执行。</p><Button variant="outline" disabled={p.busy} onClick={p.query}>查询原重放请求</Button>{p.missing?<><Button variant="outline" disabled={p.busy} onClick={p.resend}>原样重发</Button><Button variant="ghost" disabled={p.busy} onClick={p.dismiss}>放弃本地确认</Button></>:null}</div>:null}
  {p.plan?<section className="workflow-replay-selection" aria-label="重放范围与结果"><div className="workflow-replay-state"><strong>{states[p.receipt?.state??p.plan.state]}</strong><code>{p.plan.requestId}</code></div><dl><dt>固定版本</dt><dd>{p.entry.definition.id+' · v'+p.entry.definition.revision}</dd><dt>{log?'完整来源字段':'完整指标'}</dt><dd>{log?<code>{p.entry.definition.source.log?.sourceKey??'—'}</code>:'metricKey' in p.entry.definition.target?<a href={metricDefinitionHash(p.entry.definition.target.metricKey)}>{p.entry.definition.target.metricKey}</a>:'—'}</dd><dt>时间范围</dt><dd>{p.plan.from+' → '+p.plan.till}</dd><dt>{log?'输入 / 过滤 / 存储条数':'输入 / 过滤 / 存储点'}</dt><dd>{proof?proof.inputCount+' / '+proof.filtered+' / '+size(p.plan):'—'}</dd><dt>范围有效至</dt><dd>{p.plan.expiresAt}</dd><dt>{log?'输出位置':'输出系列'}</dt><dd>{log?'独立重放日志':'独立重放系列'} · {p.plan.requestId}</dd>{proof?<><dt>输入摘要</dt><dd><code>{proof.inputDigest}</code></dd><dt>输出摘要</dt><dd><code>{proof.batchDigest}</code></dd></>:null}</dl>{p.plan.error||p.receipt?.error?<p>{p.receipt?.error??p.plan.error}</p>:null}
   <div className="workflow-replay-actions">{p.plan.state==='READY'&&!p.receipt?<Button disabled={locked||Date.parse(p.plan.expiresAt)<=Date.now()} onClick={p.execute}>确认重放</Button>:null}
   {p.receipt&&['UNKNOWN','PENDING'].includes(p.receipt.state)?<Button variant="outline" disabled={locked} onClick={p.verify}>核验原输出</Button>:null}
   {log&&p.receipt&&p.readData?<Button variant="outline" disabled={locked} onClick={p.readData}>查看重放日志</Button>:null}</div>
   {p.receipt?<p className="workflow-replay-receipt">执行请求 <code>{p.receipt.requestId}</code> · {p.receipt.updatedAt}</p>:null}
  </section>:null}
  {p.children}
  <div className="workflow-replay-table" role="region" aria-label="重放记录表" tabIndex={0}><table><caption>重放范围记录 · 最近20条</caption><thead><tr><th>历史时间窗</th><th>范围检查</th><th>{log?'输入 / 存储条数':'输入 / 存储点'}</th><th>操作</th></tr></thead><tbody>{p.plans.map(r=><tr key={r.requestId}><td><time>{r.from}</time><small><code>{r.requestId}</code></small></td><td>{rangeStates[r.state]}</td><td>{r.proof?r.proof.inputCount+' / '+size(r):'—'}</td><td><Button variant="ghost" disabled={locked} onClick={()=>p.select(r.requestId)}>查看重放</Button></td></tr>)}</tbody></table></div>
  {p.loaded&&!p.plans.length?<p>暂无历史重放选择。</p>:null}{p.truncated?<p>当前列表已截断，更早的选择可按 UUID 查询。</p>:null}
  <div className="workflow-replay-form"><label>按范围记录 UUID 回查<input aria-label="重放选择 UUID" value={p.lookup} maxLength={36} disabled={locked} onChange={e=>p.changeLookup(e.target.value)}/></label><Button variant="outline" disabled={locked||!p.lookup} onClick={p.lookupPlan}>查询重放范围</Button></div>
 </section>
}
