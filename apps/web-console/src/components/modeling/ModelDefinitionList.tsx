import type {ModelDefinition} from '../../api/model-catalog.ts'
import './model-impact.css'
export function ModelDefinitionList({rows,label,open}:{rows:{definition:ModelDefinition;badge:string}[];label:string;open:(definition:ModelDefinition)=>void}){
 return <div className="model-impact-scroll model-definition-list"><table aria-label={label}><thead><tr><th>名称 / 完整标识</th><th>版本</th><th>字段 / 端点</th><th>状态</th></tr></thead><tbody>{rows.map(({definition:d,badge})=><tr key={d.id+'@'+d.revision}><th><button className="model-definition-button" aria-label={badge+' '+d.label+' '+d.id+' v'+d.revision} onClick={()=>open(d)}>{d.label}</button><code>{d.id}</code>{d.description?<p>{d.description}</p>:null}</th><td>v{d.revision}</td><td>{d.kind==='ENTITY'?d.fields.length+' 个字段':<><code>{d.endpoints?.from.id+'@'+d.endpoints?.from.revision}</code><span aria-hidden="true"> → </span><code>{d.endpoints?.to.id+'@'+d.endpoints?.to.revision}</code></>}</td><td>{badge}</td></tr>)}</tbody></table></div>
}
