import type { Definition, Model } from '../../api/workflows.ts'
import { outputKind } from '../../api/workflow-output.ts'
import type { MappingDefinition } from '../../api/metric-mappings.ts'
import { WorkflowMetricMappingConfig } from './WorkflowMetricMappingConfig.tsx'

export function WorkflowOutputConfig(props: {
  definition: Definition
  models: Model[]
  metricMappings: MappingDefinition[]
  disabled: boolean
  target: (value: string) => void
}) {
  const kind = outputKind(props.definition.target)
  return <div className="studio-output-config">
    {kind === 'ENTITY' ? <label>实体模型<select aria-label="实体模型" title="更换模型会重建字段映射和处理链，可撤销" disabled={props.disabled} value={props.definition.target.id + '@' + props.definition.target.revision} onChange={e => props.target(e.target.value)}>
      {props.models.filter(m => m.definition.fields.length).map(m => <option key={m.definition.id + '@' + m.definition.revision} value={m.definition.id + '@' + m.definition.revision}>{m.definition.label + ' · ' + m.definition.id + ' @ ' + m.definition.revision}</option>)}
    </select></label> : null}
    {kind !== 'ENTITY' ? <dl className="studio-output-format"><dt>数据格式</dt><dd>{kind === 'LOG' ? '日志 · v1' : 'mappingPin' in props.definition.target&&props.definition.target.mappingPin ? '标准指标 · v1.1' : '指标 · GAUGE v1'}</dd></dl> : null}
    {kind === 'METRIC' ? <WorkflowMetricMappingConfig target={props.definition.target} mappings={props.metricMappings} disabled={props.disabled || !!props.definition.source.metric} choose={props.target} /> : null}
  </div>
}
