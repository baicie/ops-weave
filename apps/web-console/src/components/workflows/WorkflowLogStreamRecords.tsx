import type { LogStreamData } from '../../api/workflow-log-streams.ts'
import './workflow-log-output.css'

export function WorkflowLogStreamRecords({data,busy,previous,next,title='窗口日志'}:{data:LogStreamData;busy:boolean;previous:(()=>void)|null;next:(()=>void)|null;title?:string}){
 return <section className="workflow-log-data" aria-label={title+'记录'}>
  <header><strong>{title} · 本页 {data.records.length} 条 / 批次 {data.expectedRecords} 条</strong><span>读取时间 {data.readAt}</span></header>
  {!data.complete?<p role="alert">存储中的原批次尚未完整匹配，当前仅显示已读取记录。</p>:null}
  {data.records.map(row=><article key={row.index}><header><strong>记录 {row.index+1}</strong><time>{row.eventTime}</time></header><pre>{row.body}</pre><dl><dt>采集位置</dt><dd>{row.position}</dd>{(['severityText','serviceName','traceId','spanId'] as const).map((key,i)=>row[key]===null?null:<span className="workflow-log-context" key={key}><dt>{['级别','服务','Trace ID','Span ID'][i]}</dt><dd>{row[key]}</dd></span>)}</dl></article>)}
  {!data.records.length?<p>本页暂无日志记录。</p>:null}
  <div className="workflow-metric-output-actions"><button disabled={busy||!previous} onClick={previous??undefined}>上一页</button><button disabled={busy||!next} onClick={next??undefined}>下一页</button></div>
 </section>
}
