import { ArrowDown, ChevronRight, Database, GitCompareArrows, ShieldCheck, SlidersHorizontal, ArrowDownToLine, Check, AlertCircle } from 'lucide-react'
import type { Definition, Step, Model } from '../../api/workflows.ts'
import { outputKind, outputNodeNames } from '../../api/workflow-output.ts'
import { useRef } from 'react'
import { WorkflowNodeMenu, type WorkflowNodeMenuHandle } from './WorkflowNodeMenu.tsx'

const titles = { SOURCE: '数据输入', MAP: '字段映射', TRIM: '去除空白', EMPTY_TO_NULL: '空串转空值', DEFAULT: '补充默认值', ENUM_MAP: '枚举替换', SCALE: '数值换算', FILTER: '条件过滤', MERGE: '分支合流', VALIDATE: '模型校验', OUTPUT: '输出预览' }
const descriptions = { TRIM: '去掉文本首尾的空白字符', EMPTY_TO_NULL: '将空白文本转换为空值', DEFAULT: '缺少字段时填入指定值', ENUM_MAP: '将指定文本替换为另一个值', SCALE: '按明确系数换算数值', FILTER: '只保留符合条件的记录', MERGE: '合并有效分支，字段冲突时报错' }
export function WorkflowSequence(props: { definition: Definition; selected: string; select: (id: string) => void; fields: Model['definition']['fields']; steps?: Step[]; locked: boolean }) {
  const menu = useRef<WorkflowNodeMenuHandle>(null)
  const output = outputKind(props.definition.target)
  return <><WorkflowNodeMenu ref={menu} readOnly={props.locked} edit={props.select} /><ol className="workflow-sequence" aria-label="按顺序执行的处理步骤">
    {props.definition.nodes.map((node, index) => {
      const phase = node.type === 'SOURCE' ? '来源' : node.type === 'OUTPUT' ? '输出' : node.type === 'VALIDATE' ? '校验' : '处理'
      const Icon = node.type === 'SOURCE' ? Database : node.type === 'MAP' ? GitCompareArrows : node.type === 'VALIDATE' ? ShieldCheck : node.type === 'OUTPUT' ? ArrowDownToLine : SlidersHorizontal
      const title = node.type === 'OUTPUT' ? outputNodeNames[output] : node.type === 'VALIDATE' && output !== 'ENTITY' ? '格式校验' : titles[node.type]
      const field = props.fields.find(f => f.id === node.config.field)?.label ?? node.config.field
      const summary = node.type === 'SOURCE' ? props.definition.source.kind === 'MANUAL_SAMPLE' ? '使用你提供的 JSON 样本' : '读取已采集的 Zabbix 主机记录'
        : node.type === 'MAP' ? '将 ' + Object.keys(node.config).length + ' 个来源字段对应到输出字段'
        : node.type === 'VALIDATE' ? output === 'ENTITY' ? '检查模型必填字段与字段类型' : output === 'LOG' ? '检查日志时间、正文与上下文' : '检查采样时间、数值、类型与单位'
        : node.type === 'OUTPUT' ? '生成' + (output === 'ENTITY' ? '实体' : output === 'LOG' ? '日志' : '指标') + '预览结果'
        : descriptions[node.type] + (field ? ' · ' + field : '')
      const status = props.steps?.find(s => s.nodeId === node.id)?.status
      return <li key={node.id}>
        <span className="workflow-sequence-number">{String(index + 1).padStart(2, '0')}</span>
        <button type="button" className={'workflow-node workflow-sequence-card' + (props.selected === node.id ? ' is-selected' : '')} data-node-id={node.id} data-kind={node.type} aria-label={title + '节点 ' + node.id} aria-pressed={props.selected === node.id} onClick={() => props.select(node.id)} onContextMenu={event => {
          event.preventDefault(); menu.current?.open({ id: node.id, x: event.clientX, y: event.clientY, trigger: event.currentTarget })
        }} onKeyDown={event => {
          if (event.key !== 'ContextMenu' && !(event.key === 'F10' && event.shiftKey)) return
          event.preventDefault(); const box = event.currentTarget.getBoundingClientRect()
          menu.current?.open({ id: node.id, x: box.left + 24, y: box.top + 24, trigger: event.currentTarget })
        }}>
          <span className="sequence-icon"><Icon size={19} /></span><span className="sequence-copy"><span className="sequence-title"><strong>{title}</strong><small>{phase}</small></span><span className="sequence-description">{summary}</span></span>
          {status ? <span className="sequence-result" data-status={status}>{status === 'OK' ? <Check size={14} /> : status === 'ERROR' ? <AlertCircle size={14} /> : null}{status === 'OK' ? '通过' : status === 'ERROR' ? '失败' : status === 'FILTERED' ? '过滤' : '跳过'}</span> : <ChevronRight className="sequence-chevron" size={17} />}
        </button>
        {index < props.definition.nodes.length - 1 ? <span className="sequence-connection" aria-hidden="true"><ArrowDown size={16} /></span> : null}
      </li>
    })}
  </ol></>
}
