import type { Definition, Model } from '../../api/workflows.ts'
import { outputKind, relationFieldProblem, targetModelPin, type RelationTarget } from '../../api/workflow-output.ts'
import type { MappingDefinition } from '../../api/metric-mappings.ts'
import { WorkflowMetricMappingConfig } from './WorkflowMetricMappingConfig.tsx'

export function WorkflowOutputConfig(props: {
  definition: Definition
  models: Model[]
  relationModels: Model[]
  relationLoading: boolean
  relationError: string
  relationRetry: () => void
  metricMappings: MappingDefinition[]
  disabled: boolean
  target: (value: string) => void
  relationField: (key: 'fromField' | 'toField', value: string) => void
}) {
  const kind = outputKind(props.definition.target)
  const relation = kind === 'RELATION' ? props.definition.target as RelationTarget : null
  const entityPin = kind === 'ENTITY' ? targetModelPin(props.definition.target) : null
  const pin = relation ? relation.relation.id + '@' + relation.relation.revision : ''
  return <div className="studio-output-config">
    {entityPin ? <label>实体模型<select aria-label="实体模型" title="更换模型会重建字段映射和处理链，可撤销" disabled={props.disabled} value={entityPin.id + '@' + entityPin.revision} onChange={e => props.target(e.target.value)}>
      {props.models.filter(m => m.definition.fields.length).map(m => <option key={m.definition.id + '@' + m.definition.revision} value={m.definition.id + '@' + m.definition.revision}>{m.definition.label + ' · ' + m.definition.id + ' @ ' + m.definition.revision}</option>)}
    </select></label> : null}
    {relation ? <>
      <label>关系模型<select aria-label="关系模型" title="更换关系模型固定版本会改变输出摘要；字段映射保持不变，请重新预览" disabled={props.disabled || props.relationLoading} value={pin} onChange={e => props.target('relation:' + e.target.value)}>
        {props.relationModels.some(m => m.definition.id + '@' + m.definition.revision === pin) ? null : <option value={pin}>{relation.relation.id + ' · @ ' + relation.relation.revision + '（仅已固定版本）'}</option>}
        {props.relationModels.map(m => <option key={m.definition.id + '@' + m.definition.revision} value={m.definition.id + '@' + m.definition.revision}>{m.definition.label + ' · ' + m.definition.id + ' @ ' + m.definition.revision}</option>)}
      </select></label>
      <label>起点身份字段<input aria-label="起点身份字段" maxLength={64} disabled={props.disabled} value={relation.fromField} onChange={e => props.relationField('fromField', e.target.value)} /></label>
      <label>终点身份字段<input aria-label="终点身份字段" maxLength={64} disabled={props.disabled} value={relation.toField} onChange={e => props.relationField('toField', e.target.value)} /></label>
      {relationFieldProblem(relation).map(problem => <p role="alert" key={problem}>{problem}</p>)}
      <p>两个字段保存行内的显式来源身份，须为小写蛇形标识且互不相同。端点只经已登记外部链接按身份确定性解析，解析不到进入待处理（FROM_UNRESOLVED/TO_UNRESOLVED），不按名字或 IP 猜测。</p>
      {props.relationLoading ? <p role="status">正在读取已发布关系模型目录…</p> : null}
      {props.relationError ? <><p role="alert">{props.relationError}</p><button type="button" disabled={props.relationLoading} onClick={props.relationRetry}>重试读取关系模型目录</button></> : null}
      {!props.relationLoading && !props.relationError && !props.relationModels.length ? <p role="status">当前没有已读取的已发布关系模型；只展示已固定版本。</p> : null}
    </> : null}
    {kind !== 'ENTITY' && kind !== 'RELATION' ? <dl className="studio-output-format"><dt>数据格式</dt><dd>{kind === 'LOG' ? '日志 · v1' : 'mappingPin' in props.definition.target&&props.definition.target.mappingPin ? '标准指标 · v1.1' : '指标 · GAUGE v1'}</dd></dl> : null}
    {kind === 'METRIC' ? <WorkflowMetricMappingConfig target={props.definition.target} mappings={props.metricMappings} disabled={props.disabled || !!props.definition.source.metric} choose={props.target} /> : null}
  </div>
}
