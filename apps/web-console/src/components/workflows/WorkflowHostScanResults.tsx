import type { HostScan } from '../../api/workflow-host-scan.ts'

export function WorkflowHostScanResults({scan}:{scan:HostScan}){
 const c=scan.checkpoint
 return <section className="workflow-host-scan-results" aria-label="固定接入资产批次">
  <p role="status">{c.complete?'扫描已完成':'扫描未完成'} · 已确认 {c.confirmedBatches} 批 / {c.confirmedRecords} 条来源记录</p>
  {c.pendingBatchId?<p>待处理批次 <code>{c.pendingBatchId}</code></p>:null}
  <div className="table-scroll"><table><caption>最近批次</caption><thead><tr><th>批次</th><th>状态</th><th>来源记录</th><th>确认写入</th><th>扫描末批</th><th>读取时间</th></tr></thead><tbody>{scan.batches.map(b=><tr key={b.id}><td><details><summary>第 {b.sequence} 批 · v{b.revision}</summary><code>{b.id}</code>{b.error?<p>{b.error}</p>:null}{b.entityIds.map(id=><p key={id}><code>{id}</code></p>)}</details></td><td>{{READY:'待处理',IN_FLIGHT:'处理中',UNKNOWN:'结果待确认',FAILED:'失败',CONFIRMED:'已确认'}[b.state]}</td><td>{b.recordCount}</td><td>{b.entityIds.length}</td><td>{b.complete?'是':'否'}</td><td>{new Date(b.observedAt).toLocaleString()}</td></tr>)}</tbody></table></div>
  <details><summary>扫描标识</summary><code>{c.scanId}</code></details>
 </section>
}
