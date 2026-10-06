import type { Execution, RuntimeStatus, RuntimeTask } from '../../api/workflow-runtime.ts'
import type { Entry } from '../../api/workflows.ts'
import { WorkflowTaskAuthorization, runtimeFailureMessage } from './WorkflowTaskAuthorization.tsx'

type Props = {
 entry: Entry; status: RuntimeStatus | null; task: RuntimeTask | undefined
 busy: boolean; pending: boolean; controlPending: boolean; error: string; notice: string
 identity: string; name: string; names: { id: string; label: string }[]
 identityChange: (value: string) => void; nameChange: (value: string) => void
 run: () => void; control: (start: boolean) => void; resume: () => void; canResume: boolean; canStart: boolean; refresh: () => void; executions: Execution[]
}

export function WorkflowRuntimePanel(props: Props) {
 const { entry, task, busy, status } = props
 const sourceBatch = entry.definition.source.kind === 'ZABBIX_HOST'
 const fixed = Boolean(entry.definition.source.configuration)
 const delegated = status?.mode === 'DELEGATED_ENTITY'
 const locked = busy || props.pending || props.controlPending || task?.state === 'RUNNING' || fixed && props.canResume
 return <section className="workflow-runtime-panel" aria-label="已发布流程运行">
  <header><h3>运行管理</h3><button disabled={busy} onClick={props.refresh}>刷新状态</button></header>
  <p>执行会写入工作流的独立来源资产；同一来源标识更新同一实体。</p>
  <div className="workflow-runtime-settings">
   <label>来源标识字段<input aria-label="运行来源标识字段" value={props.identity} disabled={locked || fixed} placeholder={sourceBatch ? '如 entity_id' : '如 source_id 或业务 ID'} onChange={event => props.identityChange(event.target.value)} /></label>
   <label>实体名称字段<select aria-label="运行实体名称字段" value={props.name} disabled={locked} onChange={event => props.nameChange(event.target.value)}><option value="">请选择</option>{props.names.map(field => <option key={field.id} value={field.id}>{field.label} · {field.id}</option>)}</select></label>
  </div>
  <div className="workflow-runtime-actions">
   {!fixed?<button disabled={busy || props.controlPending || !status || !props.identity || !props.name} onClick={props.run}>{props.pending ? '按原标识确认执行' : '执行并写入资产'}</button>:null}
   {sourceBatch ? <>
    <button disabled={busy || props.pending || props.controlPending || !status || !props.identity || !props.name || task?.state === 'RUNNING' || (task?.generation ?? 0) >= 999999 || status.backgroundAvailable === false || !props.canStart} onClick={() => props.control(true)}>{fixed?'启动扫描':'启动持续处理'}</button>
    {fixed&&props.canResume?<button disabled={busy || props.pending || props.controlPending || !status || task?.state==='RUNNING' || (task?.generation??0)>=999999 || status.backgroundAvailable===false} onClick={props.resume}>恢复原扫描</button>:null}
    <button disabled={busy || props.pending || props.controlPending || !task || task.state !== 'RUNNING'} onClick={() => props.control(false)}>停止任务</button>
   </> : null}
   <span role="status">{task ? ({ ABANDONED: '已终止恢复',RUNNING: '运行中', STOPPED: '已停止', FAILED: '已失败' })[task.state] : '未启动'}{task ? ' · 固定 v' + task.revision : ''}</span>
  </div>
  <p className="workflow-runtime-boundary">{fixed?'每批最多5条，确认后继续下一批。扫描完成后停止；恢复保留原批次，需重新授权。':sourceBatch ? '每批最多5条，仅处理启动后新完成的来源批次；来源采集需单独启动。' : '每次最多5条，使用当前测试区的手工样本。'}</p>
  {delegated && status.backgroundAvailable === false ? <p>当前身份没有可用后台授权。</p> : null}
  {delegated && task?.state === 'RUNNING' && !task.authorization ? <p>这是开发任务，当前授权模式不会执行；停止后可重新申请授权启动。</p> : null}
  {delegated && status.backgroundAvailable ? <p>后台授权最长15分钟、最多20批。关闭页面或注销后任务仍可在授权期限内运行；可通过停止任务终止处理。</p> : null}
  <WorkflowTaskAuthorization task={task} />
  {task && task.generation >= 999999 && task.state !== 'RUNNING' ? <p role="status">任务已达到启动次数上限。</p> : null}
  {task?.error ? <p role="alert">任务已停止：{task.state==='ABANDONED'?'已终止恢复，原输出仍待确认。':fixed&&task.error==='OUTPUT_UNAVAILABLE'?'本批写入结果待确认，请核对后恢复原扫描。':runtimeFailureMessage(task.error)}</p> : null}
  {props.error ? <p role="alert">{props.error}</p> : null}
  {props.notice ? <p role="status">{props.notice}</p> : null}
  <div className="workflow-runtime-receipts">{props.executions.map(execution => <article key={execution.id}>
   <span>{execution.state === 'SUCCEEDED' ? '执行完成' : '执行失败'} · {execution.origin === 'fixture' ? 'Fixture 合成数据' : execution.origin === 'MANUAL_SAMPLE' ? '手工样本' : 'Zabbix'} · 已确认写入 {execution.entityIds.length} 条</span>
   <time>{new Date(execution.createdAt).toLocaleString()}</time>
   {execution.error ? <small>{execution.error}；请核对原执行回执及已确认写入。</small> : null}
   <small>执行标识 {execution.id}</small>
  </article>)}</div>
 </section>
}
