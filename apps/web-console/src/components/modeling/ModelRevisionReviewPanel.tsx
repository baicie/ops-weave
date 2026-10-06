import type { ModelReview,ModelReferenceReport } from '../../api/model-impact.ts'
import './model-impact.css'
const labels:Record<string,string>={LABEL:'显示名称',DESCRIPTION:'说明',KIND:'模型类型',REVISION:'版本',FIELD:'字段',TYPE:'字段类型',REQUIRED:'必填',MAX_LENGTH:'最大长度',MIN:'最小值',MAX:'最大值',CHOICES:'枚举值',FROM:'起点版本',TO:'终点版本',CARDINALITY:'基数'}
const reasons:Record<string,string>={NON_CONSECUTIVE_REVISION:'版本必须接续最新已发布版本。',INCOMPATIBLE_CHANGE:'存在不兼容修改，需保留旧版本；当前发布只允许追加可选字段及修改说明。',UNKNOWN_ENTITY_TYPE:'关系端点需要可读取的已发布实体版本。'}
export function ModelRevisionReviewPanel({review,references,busy,error,refresh}:{review:ModelReview|null;references:ModelReferenceReport|null;busy:boolean;error:string;refresh:()=>void}){
 return <section className="model-impact" aria-label="模型发布检查"><header><h4>发布检查</h4><button disabled={busy} onClick={refresh}>重新检查版本</button></header>{!review?<p role="status">{busy?'正在检查已保存版本…':'尚未完成发布检查。'}</p>:<>
  <p role="status">{review.compatible?'版本兼容性通过':'该版本不可发布'} · {review.base?'v'+review.base.revision+' → v'+review.candidate.revision:'新类型 v'+review.candidate.revision}</p>{review.reasons.map(reason=><p role="alert" key={reason}>{reasons[reason]}</p>)}
  <div className="model-impact-scroll"><table aria-label="模型字段差异"><thead><tr><th>字段 / 属性</th><th>原版本</th><th>候选版本</th><th>兼容性</th></tr></thead><tbody>{review.changes.map((c,i)=><tr key={i}><th>{c.fieldId?<code>{review.candidate.id+'.'+c.fieldId}</code>:null}<span>{labels[c.property]}</span></th><td>{c.before??'—'}</td><td>{c.after??'—'}</td><td>{c.compatible?'兼容':'不兼容'}</td></tr>)}</tbody></table></div>{!review.changes.length?<p>字段及模型属性保持。</p>:null}
  {review.base?<p>旧版本引用继续固定；发布不会迁移历史资产或更改任务。</p>:null}{references?<p>{references.workflowsAvailable?'当前可见引用 '+references.references.items.length+' 项':'当前身份未读取工作流引用'}{references.references.truncated?' · 列表不完整':''}</p>:null}
 </>}{error?<p role="alert">{error}</p>:null}</section>
}
