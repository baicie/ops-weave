import type { ModelReferenceReport } from '../../api/model-impact.ts'
import './model-impact.css'
import {modelDefinitionHash} from '../../state/model-definition-selection.ts'
export function ModelReferenceTable({report}:{report:ModelReferenceReport}){
 return <section className="model-impact" aria-label="模型固定版本引用"><p><code>{report.target.id}</code> · 固定 v{report.target.revision}</p>{!report.workflowsAvailable?<p>当前身份未读取工作流引用。</p>:null}
  <div className="model-impact-scroll"><table aria-label="模型引用列表"><thead><tr><th>引用</th><th>版本 / 状态</th><th>用途 / 字段</th><th>我的任务</th></tr></thead><tbody>{report.references.items.map(row=><tr key={[row.kind,row.id,row.revision,row.state].join(':')}><th><a href={row.kind==='WORKFLOW'?'#/integrations/workflows?'+new URLSearchParams({id:row.id,revision:String(row.revision),state:row.state}):'#/modeling/relations?'+new URLSearchParams({definition:row.id,revision:String(row.revision)})}>{row.label}</a><code>{row.id}</code></th><td>v{row.revision} · {row.state==='PUBLISHED'?'已发布':'我的草稿'}</td><td>{row.roles.map(role=>({OUTPUT:'输出模型',FROM:'起点',TO:'终点'})[role]).join(' / ')}{row.fieldIds.map(id=><a key={id} href={modelDefinitionHash('/modeling/entities',{id:report.target.id,revision:report.target.revision,field:id})}><code>{report.target.id+'.'+id}</code></a>)}</td><td>{row.tasks.map(task=><span key={task.kind}>{task.kind==='HOST_SCAN'?'扫描':'周期'} · {{RUNNING:'运行中',STOPPED:'已停止',FAILED:'已失败'}[task.state]}</span>)}{!row.tasks.length?'—':null}</td></tr>)}</tbody></table></div>
  {!report.references.items.length?<p>{report.workflowsAvailable?'当前授权范围未发现引用。':'当前授权范围未发现关系类型引用。'}</p>:null}{report.references.truncated?<p role="status">仅显示前50项，当前列表不完整。</p>:null}<p className="model-muted">检查时间 {new Date(report.inspectedAt).toLocaleString()}</p>
 </section>
}
