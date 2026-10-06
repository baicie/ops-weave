import { Button } from '../ui/button.tsx'
import type { LogSourcePage } from '../../api/workflow-log-sources.ts'

export function WorkflowLogSourceList(p:{page:LogSourcePage|null;selectedId:string;busy:boolean;error:string;choose:(id:string)=>void;bind:()=>void;retry:()=>void}){
 const selected=p.page?.items.find(i=>i.source.log?.itemId===p.selectedId),expired=!!selected&&Date.now()>=Date.parse(selected.expiresAt)
 return <section className="workflow-source-picker" aria-label="日志来源选择">
  {p.busy?<p role="status">正在读取已发现的日志项…</p>:null}{p.error?<p role="alert">{p.error}</p>:null}
  {p.page?<><label>来源日志项<select aria-label="工作流来源日志项" value={p.selectedId} disabled={p.busy} onChange={e=>p.choose(e.target.value)}><option value="">请选择来源日志项</option>{p.page.items.map(i=><option key={i.item.itemId} value={i.item.itemId} disabled={Date.now()>=Date.parse(i.expiresAt)}>{i.item.name+' · 主机 '+i.item.hostId+' · '+i.item.itemId}</option>)}</select></label>
   {!p.page.items.length?<p>没有可用的日志项发现记录。<a href="#/integrations/sources">前往来源中心发现字段</a></p>:null}
   {p.page.truncated?<p>清单已截断，可到来源中心查看其他发现记录。</p>:null}
   {selected?<dl><dt>完整来源键</dt><dd><code>{selected.item.sourceKey}</code></dd><dt>数据类型</dt><dd>LOG</dd><dt>发现时间</dt><dd>{new Date(selected.asOf).toLocaleString()}</dd></dl>:null}
   {expired?<p role="alert">发现记录已过期，请重新发现后选择。</p>:null}
   <Button disabled={p.busy||!!p.error||!selected||expired} onClick={p.bind}>使用此日志来源</Button>
  </>:null}
  {p.error||p.page?<Button variant="outline" disabled={p.busy} onClick={p.retry}>重新读取日志清单</Button>:null}
 </section>
}
