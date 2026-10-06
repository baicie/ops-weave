import { useEffect, useRef } from 'react'
import { X } from 'lucide-react'
import { Button } from '../ui/button.tsx'
import type { Entry } from '../../api/workflows.ts'
import type { ComparisonSection, WorkflowComparison, WorkflowChange } from '../../api/workflow-comparisons.ts'
export const comparisonEntryKey = (e:Entry) => e.state + ':' + e.definition.revision
const labels:Record<ComparisonSection,string>={NAME:'流程名称',SOURCE:'输入来源',TARGET:'输出与模型',NODE:'节点',OPERATOR:'算子版本',MAPPING:'字段映射',PARAMETER:'节点参数',EDGE:'连线',ORDER:'执行顺序'}
const keys:Record<string,string>={logInspectionId:'日志发现回执',logItemId:'日志项标识',logHostId:'日志来源主机',logSourceKey:'日志完整来源键',logSourceUnit:'日志来源单位',logSourceValueType:'日志来源类型',logSourceDigest:'日志元数据摘要',metricInspectionId:'发现回执',itemId:'指标项标识',hostId:'来源主机',sourceKey:'完整来源键',sourceUnit:'来源单位',sourceValueType:'来源类型',metricSourceDigest:'来源指标摘要',metricKey:'完整指标标识',mappingId:'映射标识',mappingRevision:'映射版本',mappingDigest:'映射摘要',kind:'类别',instanceId:'来源标识',sourceId:'接入实例',configurationRevision:'配置版本',connectionDigest:'配置摘要',id:'模型标识',revision:'模型版本',digest:'模型摘要',type:'节点类型',version:'算子版本',operatorDigest:'算子摘要',nodes:'节点顺序',edges:'连线顺序',name:'名称'}
const value=(v:string|null)=>v===null?'未设置':v===''?'空字符串':v
export function WorkflowComparisonDrawer(p:{active:boolean;open:boolean;entries:Entry[];base:string;candidate:string;report:WorkflowComparison|null;loading:boolean;error:string;truncated:boolean;changeBase:(key:string)=>void;changeCandidate:(key:string)=>void;compare:()=>void;close:()=>void;refresh:()=>void}) {
 const panel=useRef<HTMLElement>(null),opener=useRef<HTMLElement|null>(null)
 useEffect(()=>{if(p.active&&p.open&&!panel.current?.matches(':popover-open')){opener.current=document.activeElement instanceof HTMLElement?document.activeElement:null;panel.current?.showPopover();panel.current?.querySelector<HTMLButtonElement>('[aria-label="关闭版本比较"]')?.focus()}else if((!p.active||!p.open)&&panel.current?.matches(':popover-open'))panel.current.hidePopover()},[p.active,p.open])
 return <aside ref={panel} popover="auto" role="dialog" aria-modal="false" aria-label="版本比较" className="workflow-comparison-drawer" onToggle={event=>{if((event.nativeEvent as ToggleEvent).newState==='closed'){p.close();if(p.active&&opener.current?.isConnected&&opener.current.getClientRects().length)opener.current.focus()}}}>
  <header><h3>版本比较</h3><Button type="button" variant="ghost" size="icon" aria-label="关闭版本比较" onClick={p.close}><X size={18}/></Button></header>
  <div className="workflow-comparison-body"><div className="workflow-comparison-selection"><Version label="基准版本" value={p.base} entries={p.entries} change={p.changeBase}/><Version label="对比版本" value={p.candidate} entries={p.entries} change={p.changeCandidate}/></div>
  {p.truncated?<p>选项来自最近版本列表，较早版本可从原版本链接打开后比较。</p>:null}
  <div className="workflow-comparison-actions"><Button disabled={!p.base||!p.candidate||p.loading} onClick={p.compare}>{p.loading?'正在比较…':'比较所选版本'}</Button><Button variant="outline" disabled={p.loading} onClick={p.refresh}>刷新版本选项</Button></div>
  {p.error?<p role="alert">{p.error}</p>:null}
  {!p.report&&!p.error&&!p.loading?<p className="workflow-comparison-empty">选择两个保存的版本，查看处理配置的变化。</p>:null}
  {p.report?<><div className="workflow-comparison-summary" role="status"><strong>{p.report.changes.length?'共 '+p.report.changes.length+' 项变化':'处理配置相同'}</strong><time dateTime={p.report.comparedAt}>{new Date(p.report.comparedAt).toLocaleString()}</time></div><p>不比较画布位置与预览回执；差异不表示新版本已通过校验或兼容。</p>{Object.entries(labels).map(([section,label])=>{const changes=p.report!.changes.filter(c=>c.section===section);return changes.length?<section className="workflow-comparison-group" key={section}><h4>{label}<small>{changes.length}</small></h4><div className="workflow-comparison-table"><table aria-label={label+'差异'}><thead><tr><th>字段</th><th>基准</th><th>对比</th></tr></thead><tbody>{changes.map(c=><Change key={JSON.stringify([c.nodeId,c.key])} change={c}/>)}</tbody></table></div></section>:null})}</>:null}
  </div>
 </aside>
}
function Version(p:{label:string;value:string;entries:Entry[];change:(key:string)=>void}) {return <label>{p.label}<select aria-label={p.label} value={p.value} onChange={e=>p.change(e.target.value)}><option value="">请选择版本</option>{p.entries.map(e=><option key={comparisonEntryKey(e)} value={comparisonEntryKey(e)}>{'v'+e.definition.revision+' · '+(e.state==='PUBLISHED'?'已发布':'草稿 · 编辑 '+e.editVersion)}</option>)}</select></label>}
function Change({change:c}:{change:WorkflowChange}) {return <tr><th scope="row">{c.nodeId?<><code>{c.nodeId}</code><br/></>:null}{c.section==='MAPPING'||c.section==='PARAMETER'?<code>{c.key}</code>:keys[c.key]??c.key}</th><td><code>{value(c.before)}</code></td><td><code>{value(c.after)}</code></td></tr>}
