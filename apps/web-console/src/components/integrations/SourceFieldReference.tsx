import { useState } from 'react'
import type { ModelField } from '../../api/model-catalog.ts'
import type { Model } from '../../api/workflows.ts'
import { telemetryFields } from '../../api/workflow-output.ts'
import { IntegrationSearchField } from './IntegrationSearchField.tsx'

const hostFields = [
  { id: 'name', label: '主机名称', type: 'TEXT', description: '由已有 Host 批次生成，默认映射到主机模型的 hostname。' },
  { id: 'ip', label: 'IP 地址', type: 'TEXT', description: '由主机接口地址生成；来源缺失时保持缺失。' },
  { id: 'lifecycle', label: '生命周期', type: 'TEXT', description: '已有主机处理规则生成的生命周期值。' },
  { id: 'entity_id', label: '来源实体 ID', type: 'TEXT', description: '已有主机候选的实体标识，用于追溯来源。' },
]
const types: Record<string, string> = { TEXT: '文本', INTEGER: '整数', DECIMAL: '数值', BOOLEAN: '布尔值', ENUM: '枚举', DATETIME: '时间' }
const descriptions: Record<string, string> = {
  timestamp: '带时区的 ISO 8601 时间，例如 2026-10-01T00:00:00Z。',
  metricKey: '完整指标标识，例如 host.cpu.usage.user；以字母开头，最多128字符，支持字母、数字及 _ . : / -。',
  value: '有限数值，绝对值不超过安全整数上限，最多12位小数。',
  metricType: '当前预览格式固定为 GAUGE。', unit: '可选单位，最多128字符；例如 ratio、s。',
  eventTime: '带时区的 ISO 8601 日志时间。', body: '日志正文最多2048字符，不能全部为空白。',
  severityText: '可选日志级别，最多128字符。', serviceName: '可选服务名称，最多128字符。',
  traceId: '可选，32位小写十六进制字符。', spanId: '可选，16位小写十六进制字符。',
}
const examples = {
  METRIC: [{ timestamp: '2026-10-01T00:00:00Z', metricKey: 'fixture.cpu.usage', value: 0.5, metricType: 'GAUGE', unit: 'ratio' }],
  LOG: [{ eventTime: '2026-10-01T00:00:00Z', body: 'Fixture example log', severityText: 'INFO', serviceName: 'fixture-service' }],
}

/** Reference only: choosing a format here never changes the source or workflow output. */
export function SourceFieldReference(props: { host: boolean; models: Model[] }) {
  const [format, setFormat] = useState<'ENTITY' | 'METRIC' | 'LOG'>('METRIC')
  const [query, setQuery] = useState('')
  const model = props.models.find(m => m.definition.id === 'builtin.host') ?? props.models[0]
  const fields = props.host ? hostFields : format === 'ENTITY' ? (model?.definition.fields ?? []).map(field => ({ ...field, description: modelDescription(field) })) : telemetryFields(format).map(field => ({ ...field, description: descriptions[field.id] ?? '' }))
  const shown = fields.filter(field => (field.id + ' ' + field.label + ' ' + field.description).toLowerCase().includes(query.trim().toLowerCase()))
  return <section className="source-reference" aria-label="接入字段参考">
    <header><h4>{props.host ? '主机批次字段' : '输出字段参考'}</h4><p>{props.host ? '这里列出工作流读取已有 Host 批次后可用的字段，不是 Zabbix 监控项。' : '字段由输出格式决定。此处仅供查阅，输出类型和映射在处理画布中设置。'}</p></header>
    {!props.host ? <div className="source-format-switch" aria-label="字段参考格式">{(['ENTITY', 'METRIC', 'LOG'] as const).map(kind => <button key={kind} type="button" data-slot="button" aria-pressed={format === kind} onClick={() => { setFormat(kind); setQuery('') }}>{kind === 'ENTITY' ? '实体字段' : kind === 'METRIC' ? '指标字段' : '日志字段'}</button>)}</div> : null}
    {!props.host && format === 'ENTITY' ? <p className="source-reference-note">{model ? '模型示例：' + model.definition.label + ' · ' + model.definition.id + ' @ ' + model.definition.revision : '当前授权目录中没有实体模型。可先发布有权限使用的模型，再配置输出。'}</p> : null}
    <div className="source-reference-search"><IntegrationSearchField label="搜索数据字段" placeholder="搜索字段名称、标识或说明" value={query} onChange={setQuery} clearLabel="清除字段搜索" /></div>
    <div className="source-reference-table" tabIndex={0} role="region" aria-label="数据字段表"><table><thead><tr><th>字段 / 标识</th><th>类型</th><th>{props.host ? '用途' : '要求'}</th><th>说明</th></tr></thead><tbody>{shown.map(field => <tr key={field.id}><td><strong>{field.label}</strong><code>{field.id}</code></td><td>{types[field.type] ?? field.type}</td><td>{props.host ? '来源字段' : 'required' in field && field.required ? <span className="source-required">必填</span> : '可选'}</td><td>{field.description}</td></tr>)}</tbody></table>{!shown.length ? <p className="source-reference-empty">{fields.length ? '没有匹配的字段，请调整搜索。' : '当前没有可展示的字段。'}</p> : null}</div>
    <p className="source-reference-note">{query ? '筛选结果 ' : '当前格式 '}{shown.length} 个字段{props.host ? ' · Host 批次最多读取5条。' : ' · JSON 样本为1–5条对象，每条最多32个标量字段。'}</p>
    {!props.host && format !== 'ENTITY' ? <details className="source-sample-reference"><summary>{format === 'METRIC' ? '查看指标样本' : '查看日志样本'}（Fixture）</summary><p>合成示例仅供参考，不会自动填入或发送。手工样本在工作流测试区填写。</p><pre>{JSON.stringify(examples[format], null, 2)}</pre></details> : null}
    {!props.host && format === 'METRIC' ? <p className="source-reference-note">上表为一条指标记录的5个格式字段，不是监控指标数量。<a href="#/modeling/metrics">查看完整指标标识与定义 ↗</a></p> : null}
    {props.host ? <p className="source-capability-note">此接入的处理流程仅支持主机实体。指标定义语义、单位与来源映射可在“指标定义参考”中查阅，实际采样请到指标页面核对。</p> : <p className="source-capability-note">日志和指标支持有界转换预览与版本测试；连续采集及日志、指标存储写入尚未接入此流程。</p>}
  </section>
}
function modelDescription(field: ModelField) {
  return [field.maxLength ? '最多' + field.maxLength + '字符' : '', field.choices?.length ? '可选值：' + field.choices.join('、') : '', field.min !== undefined ? '最小值：' + field.min : '', field.max !== undefined ? '最大值：' + field.max : ''].filter(Boolean).join('；') || '按固定模型版本校验，缺失值不会自动补造。'
}
