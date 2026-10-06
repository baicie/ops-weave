import type {ModelRef} from '../api/model-catalog.ts'
export type ModelSelection=ModelRef&{field?:string}
export function modelDefinitionHash(path:string,value:ModelSelection|null){return '#'+path+(value?'?'+new URLSearchParams({definition:value.id,revision:String(value.revision),...(value.field?{field:value.field}:{})}):'')}
export function modelDefinitionSelection(hash:string,path:string):ModelSelection|null{
 const [base,query='']=hash.split('?');const p=new URLSearchParams(query)
 if(base!=='#'+path||[...p.keys()].some(k=>!['definition','revision','field'].includes(k))||[...p.keys()].some(k=>p.getAll(k).length!==1))throw new Error('模型定义链接无效')
 if(!p.size)return null;const id=p.get('definition')??'',version=p.get('revision')??'',field=p.get('field')
 if(!/^(builtin|custom)\.[a-z][a-z0-9_]{0,47}$/.test(id)||!/^\d{1,5}$/.test(version)||Number(version)<1||Number(version)>10000||field!==null&&!/^[a-z][a-z0-9_]{0,47}$/.test(field))throw new Error('模型定义链接无效')
 return {id,revision:Number(version),...(field?{field}:{})}
}
