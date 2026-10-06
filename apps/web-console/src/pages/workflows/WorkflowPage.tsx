import {historyEligible} from '../../api/workflow-history.ts'
import { WorkflowLogSourceFields } from '../../components/workflows/WorkflowLogSourceFields.tsx'
import { builtInDescriptor, pinNode, pinOperators, operatorsPinned } from '../../api/workflow-operators.ts'
import { WorkflowSourcePanel } from './WorkflowSourcePanel.tsx'
import { configuredSourceWorkflow } from '../../api/configured-source-workflow.ts'
import { WorkflowMoreActions } from '../../components/workflows/WorkflowMoreActions.tsx'
import { SourceMetricCatalog } from '../integrations/SourceMetricCatalog.tsx'
import { WorkflowOperatorPalette } from '../../components/workflows/WorkflowOperatorPalette.tsx'
import { WorkflowRuntimeSection } from './WorkflowRuntimeSection.tsx'
import { WorkflowHostScheduleSection } from './WorkflowHostScheduleSection.tsx'
import { WorkflowMetricStreamSection } from './WorkflowMetricStreamSection.tsx'
import { WorkflowMetricOutputSection } from './WorkflowMetricOutputSection.tsx'
import { WorkflowLogOutputSection } from './WorkflowLogOutputSection.tsx'
import { WorkflowLogStreamSection } from './WorkflowLogStreamSection.tsx'
import { WorkflowQualitySection } from './WorkflowQualitySection.tsx'
import { WorkflowInspector, type WorkflowInspectorHandle } from '../../components/workflows/WorkflowInspector.tsx'
import { WorkflowConnections } from '../../components/workflows/WorkflowConnections.tsx'
import { connectionProblem, graphProblems, orderGraph } from '../../state/workflow-graph.ts'
import { WorkflowLibrary } from '../../components/workflows/WorkflowLibrary.tsx'
import { WorkflowComparisonPanel } from './WorkflowComparisonPanel.tsx'
import { usePageActive, usePageCloseGuard } from '../../state/page-workspace.ts'
import { WorkflowSequence } from '../../components/workflows/WorkflowSequence.tsx'
import { Database, Save, Play, Plus, Minus, PanelLeftOpen, PanelLeftClose, ChevronDown } from 'lucide-react'
import { WorkflowOutputConfig } from '../../components/workflows/WorkflowOutputConfig.tsx'
import { WorkflowEditorToolbar } from '../../components/workflows/WorkflowEditorToolbar.tsx'
import { issueLabels } from '../../api/workflow-runs.ts'
import { WorkflowCanvas } from '../../adapters/graph/WorkflowCanvas.tsx'
import { workflowSelection } from '../../state/workflow-selection.ts'
import { useEffect, useRef, useState } from 'react'
import { Button } from '@/components/ui/button'
import { Textarea } from '@/components/ui/textarea'
import { readSetup, type Setup } from '../../api/source-setups.ts'
import { outputKind, outputNodeNames, outputInputFields, supportsStandardMetricMapping, type OutputKind } from '../../api/workflow-output.ts'
import { sameMappingPin } from '../../api/metric-mappings.ts'
import { localPreviewMode, oidcMode, usePlatformSession } from '../../state/platform-session.ts'
import { OPERATORS, arrange, connect, evaluateWorkflow, publishWorkflow, readWorkflow, readWorkspace, saveWorkflow, stable, template, telemetryTemplate, standardMetricTemplate, type Definition, type Entry, type Evaluation, type Layout, type Model, type NodeType, type Operator, type WorkflowNode, type Workspace } from '../../api/workflows.ts'

const icons: Record<NodeType, string> = { SOURCE: '↳', MAP: '⇄', TRIM: 'Aa', EMPTY_TO_NULL: '∅', DEFAULT: '＋', ENUM_MAP: '≍', SCALE: '×', FILTER: '▽', MERGE: '⋈', VALIDATE: '✓', OUTPUT: '↗' }
const editable = (type: NodeType) => OPERATORS.includes(type as Operator)
const origins = { MANUAL_SAMPLE: '手工样本', fixture: 'Fixture 合成数据', 'zabbix-jsonrpc': 'Zabbix 采集' }
const rowStatuses = { ACCEPTED: '通过', REJECTED: '失败', FILTERED: '已过滤' }
const prettySample = (value: string) => JSON.stringify(JSON.parse(value), null, 2)
type Snapshot = { definition: Definition; layout: Layout }

export function WorkflowPage() {
  const pageActive = usePageActive()
  const pageActiveRef = useRef(pageActive)
  pageActiveRef.current = pageActive
  const lastReadHash = useRef('')
  const lastAttemptHash = useRef('')
  const [workspace, setWorkspace] = useState<Workspace | null>(null)
  const [onboarding, setOnboarding] = useState<Setup | null>(null)
  const [definition, setDefinition] = useState<Definition | null>(null)
  const [layout, setLayout] = useState<Layout>({})
  const [saved, setSaved] = useState<Entry | null>(null)
  const [selected, setSelected] = useState('source')
  const [report, setReport] = useState<Evaluation | null>(null)
  const [sample, setSample] = useState(prettySample('[{ "name": " Fixture 示例服务 ", "port": "8080" }]'))
  const [batch, setBatch] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [undo, setUndo] = useState<Snapshot[]>([])
  const [redo, setRedo] = useState<Snapshot[]>([])
  const [zoom, setZoom] = useState(1)
  const [fit, setFit] = useState(0)
  const [row, setRow] = useState(0)
  const [view, setView] = useState<'steps' | 'canvas'>('canvas')
  const [testOpen, setTestOpen] = useState(false)
  const [pickerOpen, setPickerOpen] = useState(window.innerWidth > 1000)
  const [taskId, setTaskId] = useState<string | null>(null)
  const [inspectorOpen, setInspectorOpen] = useState(false)
  const [metricReferenceOpen, setMetricReferenceOpen] = useState(false)
  const [comparisonRequest, setComparisonRequest] = useState<{nonce:number;entry:Entry}|null>(null)
  const comparisonSequence = useRef(0)
  const inspectorRef = useRef<WorkflowInspectorHandle | null>(null)
  const testRef = useRef<HTMLDetailsElement | null>(null)
  const activeRef = useRef<AbortController | undefined>(undefined)
  const disposedRef = useRef(false)
  const busyRef = useRef(false)
  const ready = usePlatformSession(change => {
    lastReadHash.current = ''; lastAttemptHash.current = change.reason === 'credentials' ? '' : location.hash; setInspectorOpen(false); setMetricReferenceOpen(false); setComparisonRequest(null)
    activeRef.current?.abort(); busyRef.current = false; setBusy(false); setWorkspace(null); setOnboarding(null); setTestOpen(false); setPickerOpen(window.innerWidth > 1000); setView('canvas'); setTaskId(null); setDefinition(null); setLayout({}); setSaved(null); setReport(null); setSample(''); setBatch(''); setUndo([]); setRedo([]); setNotice(''); setError(change.error?.message ?? '')
  })
  const dirty = Boolean(definition && (!saved || stable(definition) !== stable(saved.definition) || stable(layout) !== stable(saved.layout)))
  const catalog = workspace?.operatorCatalog
  const pinned = definition ? operatorsPinned(definition,catalog) : false
  const locked = busy || saved?.state === 'PUBLISHED'
  const node = definition?.nodes.find(n => n.id === selected)
  const model = workspace?.models.find(m => m.definition.id === definition?.target.id && m.definition.revision === definition?.target.revision)
  const output = definition ? outputKind(definition.target) : null
  const metricTarget = definition && 'mappingPin' in definition.target && definition.target.mappingPin ? definition.target : null
  const currentMapping = metricTarget ? workspace?.metricMappings?.find(m => supportsStandardMetricMapping(m) && sameMappingPin(m.mappingPin,metricTarget.mappingPin) && m.metricKey === metricTarget.metricKey) : undefined
  const mappingUnavailable = Boolean(metricTarget && !currentMapping)
  const fields = definition && output !== 'ENTITY' ? outputInputFields(definition.target) : model?.definition.fields ?? []
  const outputTitle = output === 'ENTITY' ? model?.definition.label ?? '实体' : output === 'LOG' ? '日志' : metricTarget?.metricKey ?? '指标'
  const fixtureSource = definition?.source.kind === 'ZABBIX_HOST' && !definition.source.configuration && (onboarding?.dataMode ?? workspace?.zabbixSource.mode) === 'fixture'
  const sourceTitle = definition?.source.kind === 'MANUAL_SAMPLE' ? '手工 JSON 样本' : fixtureSource ? 'Zabbix 主机 · Fixture 合成数据' : definition?.source.kind==='ZABBIX_METRIC' ? 'Zabbix 指标采样' : definition?.source.kind==='ZABBIX_LOG' ? 'Zabbix 日志样本' : 'Zabbix 主机记录'
  const currentSteps = report?.evaluation.rows[row]?.steps
  const currentIndex = currentSteps?.findIndex(s => s.nodeId === selected) ?? -1
  const currentStep = currentSteps?.[currentIndex]
  const inputs = definition?.edges.filter(edge => edge.to === selected).map(edge => currentSteps?.find(step => step.nodeId === edge.from)).filter(step => step?.status === 'OK') ?? []
  const nodeInput = node?.type === 'SOURCE' ? currentStep?.values : inputs.length === 1 ? inputs[0]!.values : inputs.length > 1 ? Object.fromEntries(inputs.map(step => [step!.nodeId, step!.values])) : undefined
  const nodeLabel = (type: NodeType) => type === 'OUTPUT' && output ? outputNodeNames[output] : type === 'VALIDATE' && output !== 'ENTITY' ? metricTarget ? '标准指标校验' : '格式校验' : builtInDescriptor(type).label
  const dirtyRef = useRef(dirty)
  const readyRef = useRef(ready)
  const actionRef = useRef<(kind: 'load' | 'save' | 'preview' | 'publish' | 'open', entry?: Entry) => Promise<void>>(async () => {})
  dirtyRef.current = dirty
  readyRef.current = ready
  usePageCloseGuard(busy ? { message: '工作流请求正在处理，请等待结果后关闭。', blocked: true } : dirty ? { message: '工作流有未保存的修改。' } : null)
  function markBusy(value: boolean) { busyRef.current = value; setBusy(value) }
  function selectStep(id: string) {
    setSelected(id)
    inspectorRef.current?.open(id)
  }

  function snapshot(): Snapshot { return { definition: structuredClone(definition!), layout: structuredClone(layout) } }
  function change(next: Definition, positions: Layout = layout, semantic = true) {
    if (locked) return
    if (definition) setUndo(v => [...v.slice(-29), snapshot()])
    setRedo([]); setDefinition(orderGraph(next)); setLayout(positions); setError(''); setNotice(''); if (semantic) setReport(null)
  }
  function restore(direction: 'undo' | 'redo') {
    if (locked || !definition) return
    const stack = direction === 'undo' ? undo : redo
    const previous = stack.at(-1)
    if (!previous) return
    const current = snapshot()
    if (direction === 'undo') { setUndo(stack.slice(0, -1)); setRedo(v => [...v, current]) }
    else { setRedo(stack.slice(0, -1)); setUndo(v => [...v, current]) }
    setDefinition(previous.definition); setLayout(previous.layout); setReport(null)
    if (!previous.definition.nodes.some(n => n.id === selected)) setSelected('source')
  }
  function chooseTemplate(kind: 'MANUAL_SAMPLE' | 'ZABBIX_HOST') {
    const available = workspace?.models ?? []
    const target = available.find(m => m.definition.id === (kind === 'ZABBIX_HOST' ? 'builtin.host' : 'builtin.service'))
    if (!target || !catalog) return
    if (dirty) { setError('当前草稿有未保存修改，请先保存再新建工作流。'); return }
    setOnboarding(null); setTaskId(null); setView('canvas'); setTestOpen(false); setPickerOpen(window.innerWidth > 1000)
    const d = template(target, { kind, instanceId: kind === 'MANUAL_SAMPLE' ? 'manual' : workspace!.zabbixSource.instanceId }, catalog)
    setDefinition(d); setLayout(arrange(d)); setSaved(null); setSelected('source'); setReport(null); setUndo([]); setRedo([]); setError(''); setNotice(''); setSample(prettySample('[{ "name": " Fixture 示例服务 ", "port": "8080" }]')); setBatch('')
  }
  function chooseOutput(kind: OutputKind, standalone = false) {
    if (!workspace || !catalog || busy || !standalone && saved?.state === 'PUBLISHED' || standalone && dirty || !standalone && output === kind) return
    const source = standalone ? { kind: 'MANUAL_SAMPLE' as const, instanceId: 'manual' } : definition?.source ?? onboarding?.source ?? { kind: 'MANUAL_SAMPLE' as const, instanceId: 'manual' }
    if(source.kind==='ZABBIX_METRIC'||source.kind==='ZABBIX_LOG'){setError('请先在输入节点切换为手工样本，再更换输出类型。');return}
    if (source.kind === 'ZABBIX_HOST' && kind !== 'ENTITY') { setError('Zabbix Host 批次只包含实体元数据；日志和指标需要对应的来源记录。'); return }
    const m = workspace.models.find(m => m.definition.id === (source.kind === 'ZABBIX_HOST' ? 'builtin.host' : 'builtin.service')) ?? workspace.models[0]
    if (kind === 'ENTITY' && !m) { setError('当前没有可用的实体模型，请检查模型目录权限。'); return }
    const fresh = kind === 'ENTITY' ? template(m!, source, catalog) : telemetryTemplate(kind, source, catalog)
    if (definition && !standalone) change({ ...fresh, id: definition.id, revision: definition.revision, name: definition.name }, arrange(fresh))
    else {
      const d = onboarding && !standalone ? { ...fresh, id: onboarding.workflowId, name: onboarding.name } : fresh
      setDefinition(d); setLayout(arrange(d)); setSaved(null); setReport(null); setUndo([]); setRedo([])
    }
    setView('canvas'); setTestOpen(false); setPickerOpen(window.innerWidth > 1000)
    if (standalone) { setOnboarding(null); setTaskId(null) }
    setSelected(definition && !standalone ? 'output' : 'mapping'); setBatch(''); setNotice('')
    setSample(prettySample(kind === 'LOG' ? JSON.stringify([{eventTime:new Date(Date.now()-1000).toISOString(),body:'Fixture 日志样本',severityText:'INFO',serviceName:'fixture-service'}])
      : kind === 'METRIC' ? '[{"timestamp":"2026-10-01T00:00:00Z","metricKey":"fixture.cpu.usage","value":0.5,"metricType":"GAUGE","unit":"ratio"}]' : '[{"name":"Fixture 示例服务","port":"8080"}]'))
  }
  function editConfig(key: string, value: string) {
    const d = definition; const n = node
    if (!d || !n) return
    change({ ...d, nodes: d.nodes.map(x => x.id === n.id ? { ...x, config: { ...x.config, [key]: value } } : x) })
  }
  function mapping(target: string, from: string) {
    const d = definition
    if (!d) return
    const n = d.nodes[1]!
    const config = Object.fromEntries(Object.entries(n.config).filter(([, to]) => to !== target))
    if (from.trim()) config[from.trim()] = target
    change({ ...d, nodes: d.nodes.map(x => x.id === n.id ? { ...x, config } : x) })
  }
  function target(value: string) {
    const d=definition;if(!d||!catalog||!workspace)return
    if(d.source.kind==='ZABBIX_METRIC'){setError('真实来源与标准指标映射已固定，请先更换输入来源。');return}
    let fresh:Definition
    if(value==='metric-generic'&&output==='METRIC')fresh=telemetryTemplate('METRIC',d.source,catalog)
    else if(value.startsWith('metric-mapping:')&&output==='METRIC'){
      const mapping=workspace.metricMappings?.find(m=>'metric-mapping:'+m.mappingPin.id===value);if(!mapping)return
      fresh=standardMetricTemplate(mapping,d.source,catalog)
    }else{const m=workspace.models.find(m=>m.definition.id+'@'+m.definition.revision===value);if(!m)return;fresh=template(m,d.source,catalog)}
    change({...d,target:fresh.target,nodes:fresh.nodes,edges:fresh.edges},arrange(fresh))
    setSelected(output==='METRIC'?'output':'mapping');setNotice('已重建字段映射和处理链，请重新校验样本。')
  }
  function insertMetricSample(){
    if(!currentMapping||definition?.source.kind!=='MANUAL_SAMPLE'||busy)return
    setSample(JSON.stringify([{timestamp:'2026-10-04T00:00:00Z',sourceKey:currentMapping.sourceKey,value:currentMapping.valueType==='INTEGER'?'10':'12.5'}],null,2))
    setReport(null);setSaved(e=>e?.state==='DRAFT'?{...e,preview:null}:e)
  }

  function add(type: Operator, position?: { x: number; y: number }) {
    const d = definition
    if (!d || !catalog || !fields.length || d.nodes.length >= 16 || locked) return
    const id = 'step-' + crypto.randomUUID().slice(0, 8), field = fields[0]?.id ?? 'name'
    const config: Record<string, string> = type === 'TRIM' || type === 'MERGE' ? {} : type === 'EMPTY_TO_NULL' ? { field } : type === 'DEFAULT' ? { field, value: '' } : type === 'ENUM_MAP' ? { field, from: '', to: '' } : type === 'SCALE' ? { field, factor: '1' } : { field, equals: '' }
    const nodes = [...d.nodes.slice(0, -2), pinNode({ id, type, version: '1' as const, config },catalog), ...d.nodes.slice(-2)]
    const after = d.nodes.find(n => n.id === selected && editable(n.type)) ?? d.nodes.at(-3)!
    const outgoing = d.edges.filter(edge => edge.from === after.id)
    const edges = position ? d.edges : [...d.edges.filter(edge => edge.from !== after.id), { from: after.id, to: id }, ...outgoing.map(edge => ({ from: id, to: edge.to }))]
    const next = orderGraph({ ...d, nodes, edges })
    change(next, position ? { ...layout, [id]: position } : arrange(next))
    selectStep(id)
    if (position) setNotice('已添加算子，请连接输入和输出后保存。')
  }
  function join(from: string, to: string) {
    if (!definition || locked) return
    const problem = connectionProblem(definition, from, to)
    if (problem) { setError(problem); return }
    change(orderGraph({ ...definition, edges: [...definition.edges, { from, to }] }))
  }
  function removeEdge(from: string, to: string) {
    if (!definition || locked || definition.nodes.find(n => n.id === from)?.type === 'SOURCE' || definition.nodes.find(n => n.id === to)?.type === 'OUTPUT') return
    change({ ...definition, edges: definition.edges.filter(edge => edge.from !== from || edge.to !== to) })
  }
  function reorder(delta: number) {
    const d = definition
    if (!d || d.edges.length !== d.nodes.length - 1 || graphProblems(d).length) return
    const i = d.nodes.findIndex(n => n.id === selected)
    const next = i + delta
    if (i < 2 || i >= d.nodes.length - 2 || next < 2 || next >= d.nodes.length - 2) return
    const nodes = [...d.nodes]; [nodes[i], nodes[next]] = [nodes[next]!, nodes[i]!]
    const def = connect({ ...d, nodes })
    change(def, arrange(def))
  }
  function remove() {
    const d = definition; const n = node
    if (!d || !n || !editable(n.type)) return
    const before = d.edges.filter(edge => edge.to === n.id), after = d.edges.filter(edge => edge.from === n.id)
    const edges = [...d.edges.filter(edge => edge.from !== n.id && edge.to !== n.id), ...before.flatMap(a => after.map(b => ({ from: a.from, to: b.to })))]
    const positions = { ...layout }; delete positions[n.id]
    const def = orderGraph({ ...d, nodes: d.nodes.filter(x => x.id !== n.id), edges: [...new Map(edges.map(edge => [edge.from + ':' + edge.to, edge])).values()] })
    change(def, positions); setSelected('mapping')
  }
  function acceptEntry(e: Entry, preserveSelection = false) {
    setTaskId(e.definition.id); setDefinition(e.definition); setLayout(e.layout); setSaved(e); setUndo([]); setRedo([]); setReport(null); if (!preserveSelection || !e.definition.nodes.some(n => n.id === selected)) setSelected('source')
    setWorkspace(w => {
      if (!w) return w
      const key = e.state === 'DRAFT' ? 'drafts' : 'published'
      const remaining = e.state === 'PUBLISHED' ? { ...w.drafts, items: w.drafts.items.filter(x => x.definition.id !== e.definition.id || x.definition.revision !== e.definition.revision) } : w.drafts
      return { ...w, drafts: remaining, [key]: { ...w[key], items: [e, ...w[key].items.filter(x => x.definition.id !== e.definition.id || x.definition.revision !== e.definition.revision)].slice(0, 20) } }
    })
  }
  async function action(kind: 'load' | 'save' | 'preview' | 'publish' | 'open', entry?: Entry) {
    if (busyRef.current || !readyRef.current) return
    if ((kind === 'open' || kind === 'load') && dirtyRef.current) { setError('请先保存当前修改，再读取其他草稿或刷新列表。'); return }
    if (kind === 'save' && definition && graphProblems(definition).length) { setError(graphProblems(definition).join('；')); return }
    if (kind === 'preview') setTestOpen(true)
    const controller = new AbortController(); activeRef.current?.abort(); activeRef.current = controller; markBusy(true); setError(''); setNotice('')
    const hash = location.hash
    const current = () => !disposedRef.current && activeRef.current === controller && !controller.signal.aborted && (!pageActiveRef.current || location.hash === hash)
    try {
      if (kind === 'load') {
        lastAttemptHash.current = hash
        const selection = workflowSelection(hash)
        const result = await readWorkspace(controller.signal)
        const source = selection && 'sourceSetup' in selection ? await readSetup(selection.sourceSetup, controller.signal) : null
        const chosen = source ? source.workflow : selection && 'id' in selection ? await readWorkflow(selection.id, selection.revision, selection.state, controller.signal) : null
        const configured = selection && 'sourceInstance' in selection ? await configuredSourceWorkflow(result,selection,controller.signal) : null
        if (current()) {
          lastReadHash.current = hash
          setWorkspace(result)
          setTaskId(selection && 'task' in selection ? selection.task : null)
          setOnboarding(source?.setup ?? null)
          if (configured) { setDefinition(configured); setLayout(arrange(configured)); setSaved(null); setSelected('source'); setReport(null); setUndo([]); setRedo([]); setSample(''); setBatch(''); setTestOpen(false); setView('canvas') }
          else if (chosen) { acceptEntry(chosen); setSample(''); setBatch('') }
          else { setDefinition(null); setSaved(null); setReport(null); setLayout({}) }
          setNotice(configured ? '已准备未保存的流程，连接配置 v' + configured.source.configuration!.revision + ' 已固定。保存、预览和发布需分别确认。' : chosen ? '已载入所选工作流。可配置清洗规则并手动预览。' : source ? '来源已保存，正在打开处理流程。' : '已读取工作流、模型和本人运行回执。')
        }
      } else if (kind === 'save') {
        if (!definition) return
        const result = await saveWorkflow(definition, layout, saved?.editVersion ?? 0, controller.signal)
        if (current()) { const oldReport = report; acceptEntry(result, true); setReport(oldReport); setNotice('草稿已保存；尚未发布或启用采集。') }
      } else if (kind === 'open') {
        if (!entry) return
        const e = await readWorkflow(entry.definition.id, entry.definition.revision, entry.state, controller.signal)
        if (current()) { acceptEntry(e); setSample(''); setBatch(''); setNotice(e.state === 'PUBLISHED' ? '已载入不可变版本，可测试运行或创建下一版。' : '已载入本人草稿。') }
      } else if (kind === 'publish') {
        if (!saved || dirtyRef.current) throw new Error('请先保存并预览当前定义')
        const result = await publishWorkflow(saved, controller.signal)
        if (current()) { acceptEntry(result); setNotice(result.definition.source.configuration ? '版本已发布，连接版本已固定。' : '版本已发布。测试运行只读；实体输出可在运行管理中显式启用。') }
      } else {
        if (!saved || dirtyRef.current) throw new Error('请先保存当前工作流')
        let samples: unknown
        if (definition?.source.kind === 'MANUAL_SAMPLE') {
          try { samples = JSON.parse(sample) } catch { throw new Error('手工样本应为 JSON 对象数组，最多5条。') }
        } else if (!definition?.source.configuration && !/^[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}$/.test(batch)) throw new Error('请输入有效的已有 Host 采集批次 ID')
        const result = await evaluateWorkflow(saved, samples, batch, controller.signal,currentMapping)
        if (current()) {
          setReport(result); setRow(0)
          if (saved?.state === 'DRAFT') setSaved(e => e ? { ...e, preview: result.receipt } : e)
          setWorkspace(w => w ? { ...w, runs: { ...w.runs, items: [{ workflowId: saved!.definition.id, revision: saved!.definition.revision, mode: saved!.state === 'DRAFT' ? 'PREVIEW' as const : 'RUN' as const, receipt: result.receipt }, ...w.runs.items].slice(0, 20) } } : w)
          setNotice(result.receipt.rejected ? '存在拒绝记录，点击节点查看原因。' : '测试完成，未写入实体、指标或日志存储。')
        }
      }
    } catch (e) { if (current()) setError(e instanceof Error ? e.message : '工作流请求失败') }
    finally { if (activeRef.current === controller) markBusy(false) }
  }
  actionRef.current = action

  useEffect(() => {
    disposedRef.current = false
    function navigate() {
      if (disposedRef.current || !pageActiveRef.current || location.hash.split('?')[0] !== '#/integrations/workflows' || location.hash === lastReadHash.current) return
      activeRef.current?.abort(); activeRef.current = undefined; markBusy(false)
      if (dirtyRef.current) { setError('当前画布有未保存修改，请保存或放弃后点击读取工作流。'); return }
      void actionRef.current('load')
    }
    window.addEventListener('hashchange', navigate)
    queueMicrotask(() => { if (!disposedRef.current && pageActiveRef.current && location.hash.includes('?') && readyRef.current && lastReadHash.current !== location.hash && lastAttemptHash.current !== location.hash && !dirtyRef.current) void actionRef.current('load') })
    return () => { disposedRef.current = true; activeRef.current?.abort(); window.removeEventListener('hashchange', navigate) }
  }, [])

  useEffect(() => {
    if (pageActive && ready && !dirtyRef.current && (!workspace || lastReadHash.current !== location.hash) && lastAttemptHash.current !== location.hash) void actionRef.current('load')
  }, [ready, pageActive])

  // A source deep link prepares an unsaved local draft; no save or preview request is sent.
  useEffect(() => {
    if (!onboarding || !workspace || definition || busy) return
    chooseOutput(workspace.models.length ? 'ENTITY' : onboarding.source.kind === 'MANUAL_SAMPLE' ? 'LOG' : 'ENTITY')
  }, [onboarding, workspace, definition, busy])

  function discard() {
    if (busy) return
    const e = saved
    if (e) acceptEntry(e)
    else { setOnboarding(null); setDefinition(null); setLayout({}); setUndo([]); setRedo([]); setReport(null) }
    setNotice('本地修改已放弃。')
  }
  function nextVersion() {
    const e = saved
    if (!e || e.state !== 'PUBLISHED') return
    setDefinition({ ...e.definition, revision: e.definition.revision + 1 }); setSaved(null); setReport(null); setUndo([]); setRedo([]); setNotice('已创建下一版的本地编辑副本，原发布版本保持不变。')
  }
  function showVersions() { if (busy || dirty) return; setDefinition(null); setSaved(null); setOnboarding(null); setReport(null); setLayout({}) }
  function compare(entry:Entry) { if (busy || dirty) return; setComparisonRequest({nonce:++comparisonSequence.current,entry}) }
  function createWorkflow(kind: 'ENTITY' | 'ZABBIX_HOST' | 'LOG' | 'METRIC') { if (kind === 'ENTITY') chooseTemplate('MANUAL_SAMPLE'); else if (kind === 'ZABBIX_HOST') chooseTemplate(kind); else chooseOutput(kind, true) }
  return <section className="workflow-page workflow-studio" data-page="workflows">
    <header className="studio-heading"><div><h2>数据工作流</h2></div><div className="studio-heading-actions" hidden={!!definition}><a href="#/integrations/workflows/runs">运行记录</a><Button variant="outline" aria-label="读取工作流" disabled={busy || !ready || dirty} onClick={() => void action('load')}>{workspace ? '刷新列表' : '打开工作流库'}</Button></div></header>
    {!ready ? <p className="source-access-hint" role="status">{localPreviewMode ? '本地会话连接中；连接失败时使用页面顶部的重新连接。' : oidcMode ? '请先登录平台。' : '请先在页面顶部建立开发会话。'}</p> : null}
    {error ? <p className="studio-alert" role="alert">{error}</p> : <p role="alert" hidden />}
    <p className="studio-notice" role="status" hidden={!busy && !notice}>{busy ? '正在处理…' : notice}</p>
    {!workspace ? <div className="workflow-welcome studio-start"><Database size={28} /><h3>{busy ? '正在打开你的工作流' : '创建一条数据处理流程'}</h3><p>从数据源中心配置来源，或打开工作流库继续编辑。</p></div> : null}
    {workspace ? <>{!catalog ? <p role="status">算子目录未提供，暂不能创建或发布新版本。请核对服务版本后刷新。</p> : null}
      {definition ? <><div className="studio-editor"><WorkflowEditorToolbar name={definition.name} revision={definition.revision} state={saved?.state === 'PUBLISHED' ? 'published' : dirty ? 'dirty' : 'saved'} disabled={locked} changeName={name => change({ ...definition, name })} actions={<div className="studio-actions">{report?.receipt.rejected ? <span>有失败记录，请检查对应步骤</span> : null}<div>{saved?.state !== 'PUBLISHED' ? <Button variant={dirty ? 'default' : 'outline'} disabled={locked || !dirty || graphProblems(definition).length > 0} onClick={() => void action('save')}><Save size={15} />保存草稿</Button> : null}<Button variant={!dirty && saved && !saved.preview ? 'default' : 'outline'} disabled={busy || dirty || !saved || mappingUnavailable} onClick={() => void action('preview')}><Play size={15} />{saved?.state === 'PUBLISHED' ? '测试运行已发布版本' : '预览当前草稿'}</Button>{saved?.state !== 'PUBLISHED' ? <Button variant={saved?.preview && !dirty ? 'default' : 'outline'} disabled={locked || dirty || !saved?.preview || saved.preview.rejected !== 0 || !saved.preview.accepted || !pinned || mappingUnavailable} title={!pinned ? '请先固定算子版本并重新预览' : undefined} onClick={() => void action('publish')}>发布版本</Button> : null}{saved?.state === 'PUBLISHED' ? <Button variant="outline" disabled={busy} onClick={nextVersion}>创建下一版</Button> : null}</div><WorkflowMoreActions active={pageActive}><button disabled={busy || dirty} onClick={showVersions}>返回版本列表</button><button disabled={busy || dirty || !saved} onClick={() => { if (saved) compare(saved) }}>比较版本</button><a href={saved?.state==='PUBLISHED'&&historyEligible(saved)?'#/integrations/workflows/runs?workflowId='+saved.definition.id+'&revision='+saved.definition.revision:'#/integrations/workflows/runs'}>运行记录</a><button aria-label="读取工作流" disabled={busy || !ready || dirty} onClick={() => void action('load')}>刷新列表</button><button disabled={busy || !dirty} onClick={discard}>放弃本地修改</button><hr /><button disabled={locked || !catalog || pinned} onClick={() => { if (definition && catalog) { change(pinOperators(definition,catalog)); setNotice('已固定算子版本，请保存并重新预览。') } }}>固定算子版本</button><button onClick={() => inspectorRef.current?.open()}>节点配置</button><button onClick={() => selectStep('output')}>输出配置</button><button aria-label="撤销工作流修改" disabled={locked || !undo.length} onClick={() => restore('undo')}>撤销修改</button><button aria-label="重做工作流修改" disabled={locked || !redo.length} onClick={() => restore('redo')}>重做修改</button><button aria-label="自动排列节点" disabled={locked} onClick={() => change(definition, arrange(definition), false)}>排列节点</button></WorkflowMoreActions></div>} />
        <div className="studio-workbench">
          <section className="studio-process" onKeyDown={event => { if (event.key === 'Escape' && pickerOpen && (event.target as HTMLElement).closest('.workflow-operator-palette')) { setPickerOpen(false); event.currentTarget.querySelector<HTMLButtonElement>('.studio-library-toggle')?.focus(); event.stopPropagation() } }}><header><div><h3>处理流程 <span className="studio-step-count">{definition.nodes.length} 个步骤</span>{fixtureSource ? <span className='studio-source-mode'>Fixture 合成数据</span> : null}</h3></div><div className="studio-process-tools"><WorkflowConnections active={pageActive} definition={definition} locked={locked} connect={join} remove={removeEdge} /><button className="studio-view-toggle" onClick={() => setView(view === 'steps' ? 'canvas' : 'steps')}>{view === 'steps' ? '画布视图' : '步骤视图'}</button></div></header>
          <div className="studio-flow-area" data-library-open={pickerOpen && saved?.state !== 'PUBLISHED'}>{pickerOpen && saved?.state !== 'PUBLISHED' ? <WorkflowOperatorPalette catalog={catalog} disabled={!catalog || locked || definition.nodes.length >= 16} add={add} close={() => { const process = document.activeElement?.closest('.studio-process'); setPickerOpen(false); process?.querySelector<HTMLButtonElement>('.studio-library-toggle')?.focus() }} output={{ kind: output!, disabled: { ENTITY: locked || !workspace.models.length || ['ZABBIX_METRIC','ZABBIX_LOG'].includes(definition.source.kind), METRIC: locked || ['ZABBIX_HOST','ZABBIX_LOG'].includes(definition.source.kind), LOG: locked || !['MANUAL_SAMPLE','ZABBIX_LOG'].includes(definition.source.kind) }, choose: kind => { chooseOutput(kind); selectStep('output') } }} /> : null}<div className="studio-flow-surface">
          <div className="studio-canvas-toolbar">{saved?.state !== 'PUBLISHED' ? <button type="button" className="studio-library-toggle" aria-label="节点库" title={pickerOpen ? '收起节点库' : '展开节点库'} aria-expanded={pickerOpen} disabled={busy} onClick={() => setPickerOpen(value => !value)}>{pickerOpen ? <PanelLeftClose size={16} /> : <PanelLeftOpen size={16} />}<span>节点库</span></button> : <span />}{view === 'canvas' ? <div className="studio-canvas-tools"><button aria-label="缩小画布" disabled={zoom <= .25} onClick={() => setZoom(z => Math.max(.25, z - .1))}><Minus size={14} /></button><span aria-label="画布缩放比例">{Math.round(zoom * 100) + '%'}</span><button aria-label="放大画布" disabled={zoom >= 1.5} onClick={() => setZoom(z => Math.min(1.5, z + .1))}><Plus size={14} /></button><button aria-label="适应画布" onClick={() => setFit(v => v + 1)}>适应</button></div> : null}</div>
          {view === 'steps' ? <WorkflowSequence definition={definition} fields={fields} selected={selected} select={selectStep} steps={currentSteps} locked={locked} /> : <div className="workflow-graph-editor"><WorkflowCanvas definition={definition} layout={layout} selected={selected} select={selectStep} zoom={zoom} onZoom={setZoom} fit={fit} steps={currentSteps} outputTitle={outputTitle} sourceTitle={sourceTitle} locked={locked} move={(id, x, y) => change(definition, { ...layout, [id]: { x, y } }, false)} add={add} connect={join} removeEdge={removeEdge} /></div>}
          </div></div>
          {graphProblems(definition).length ? <p className="workflow-graph-problems" role="status">{graphProblems(definition).join('；')}。完成连接后可以保存。</p> : null}
          </section>
          <WorkflowInspector active={pageActive} ref={inspectorRef} onOpenChange={setInspectorOpen}><header><span className="studio-eyebrow">{'步骤 ' + String(definition.nodes.findIndex(n => n.id === selected) + 1).padStart(2, '0')} · {saved?.state === 'PUBLISHED' ? '查看节点' : '编辑节点'}</span><h3>{node ? nodeLabel(node.type) : '配置步骤'}</h3><p>{node?.type === 'MAP' ? '将来源字段映射到输出字段。' : node?.type === 'VALIDATE' ? '执行时自动检查数据格式。' : node?.type === 'SOURCE' ? '选择用于这次处理的数据。' : node ? builtInDescriptor(node.type).hint : ''}</p></header><div className="studio-inspector-body"><dl className="studio-node-meta"><dt>节点标识</dt><dd>{node?.id}</dd><dt>算子版本</dt><dd>{node?.type} @ {node?.version}</dd><dt>固定摘要</dt><dd><code>{node?.operatorDigest ?? '尚未固定'}</code></dd></dl>
            <WorkflowSourcePanel visible={pageActive && inspectorOpen && node?.type === 'SOURCE'} source={definition.source} locked={locked} legacyId={workspace.zabbixSource.instanceId} entityOutput={output === 'ENTITY'} logOutput={output==='LOG'} metricMapping={currentMapping} onChange={source => change({ ...definition, source, nodes:source.kind==='ZABBIX_LOG'&&definition.source.kind!=='ZABBIX_LOG'?definition.nodes.map(n=>n.type==='MAP'?{...n,config:{timestamp:'eventTime',body:'body'}}:n):source.kind==='MANUAL_SAMPLE'&&definition.source.kind==='ZABBIX_LOG'?definition.nodes.map(n=>n.type==='MAP'?{...n,config:{eventTime:'eventTime',body:'body'}}:n):definition.nodes })} />
            {node?.type==='SOURCE'&&definition.source.kind==='ZABBIX_LOG'?<WorkflowLogSourceFields/>:null}
            {node?.type === 'SOURCE' ? <><p>{definition.source.kind==='ZABBIX_LOG' ? '读取最近10分钟的最多5条日志样本。' : definition.source.kind==='ZABBIX_METRIC' ? '读取最近10分钟的最多5个原始采样点。' : definition.source.configuration ? '按固定连接版本读取最多5条主机记录。' : definition.source.kind === 'ZABBIX_HOST' ? '使用已有主机采集批次，最多读取5条记录。' : '在测试数据区填写 JSON 记录。'}</p><Button variant="outline" onClick={() => { inspectorRef.current?.close(); setTestOpen(true); requestAnimationFrame(() => testRef.current?.scrollIntoView({ block: 'start' })) }}>{definition.source.configuration ? '查看预览' : '填写测试数据'}</Button></> : null}
            {node?.type === 'MAP' ? <><div className="studio-mapping-target"><span>输出：{output === 'ENTITY' ? model?.definition.label : output === 'LOG' ? '日志' : '指标'}</span><button onClick={() => selectStep('output')}>配置输出</button></div><div className="studio-map-heading"><span>来源字段</span><span>输出字段</span></div>{fields.map(field => <MappingField key={field.id} field={field} namespace={output === 'ENTITY' ? definition.target.id : undefined} nodes={definition.nodes} disabled={locked} update={mapping} />)}<p>留空表示不映射。缺失的必填字段会在校验节点报告。</p></> : null}
            {node?.config.field !== undefined ? <label>目标字段<select aria-label="节点目标字段" value={node.config.field ?? ''} disabled={locked} onChange={e => editConfig('field', (e.target as HTMLSelectElement).value)}>{fields.map(field => <option key={field.id} value={field.id}>{field.label + ' · ' + (output === 'ENTITY' ? definition.target.id + '.' : '') + field.id}</option>)}</select></label> : null}
            {node?.type === 'DEFAULT' ? <ConfigInput label="缺失时填入" value={node.config.value ?? ''} disabled={locked} change={value => editConfig('value', value)} /> : null}
            {node?.type === 'ENUM_MAP' ? <><ConfigInput label="原文本值" value={node.config.from ?? ''} disabled={locked} change={value => editConfig('from', value)} /><ConfigInput label="替换为" value={node.config.to ?? ''} disabled={locked} change={value => editConfig('to', value)} /></> : null}
            {node?.type === 'SCALE' ? <><ConfigInput label="换算系数" value={node.config.factor ?? ''} disabled={locked} change={value => editConfig('factor', value)} /><p>例如百分数转比例填0.01。转换失败保留拒绝原因。</p></> : null}
            {node?.type === 'FILTER' ? <><ConfigInput label="保留的文本值" value={node.config.equals ?? ''} disabled={locked} change={value => editConfig('equals', value)} /><p>过滤记录单独计数，不用于资源删除或退役。</p></> : null}
            {node?.type === 'TRIM' && output === 'LOG' ? <p className="workflow-format-note">此节点会显式裁剪日志正文首尾空白。需要保留原文时请移除此节点；日志模板默认不添加它。</p> : null}
            {node?.type === 'TRIM' ? <p className="studio-field-note">作用范围：当前记录的所有文本字段；数值、布尔值和缺失值保持。此算子无额外参数。</p> : null}
            {node?.type === 'MERGE' ? <p className="studio-field-note">合并所有已连接分支的同一条记录；同名字段值不同会拒绝该记录，不会覆盖。请在连接管理中核对输入分支。</p> : null}
            {output === 'METRIC' && ['MAP', 'OUTPUT', 'VALIDATE'].includes(node?.type ?? '') ? <details className="studio-metric-reference" onToggle={event => setMetricReferenceOpen(event.currentTarget.open)}><summary>指标标识与定义参考</summary><SourceMetricCatalog enabled={pageActive && inspectorOpen && metricReferenceOpen} /></details> : null}
            {node?.type === 'OUTPUT' ? <WorkflowOutputConfig definition={definition} models={workspace.models} metricMappings={workspace.metricMappings??[]} disabled={locked} target={target} /> : null}
            {node?.type === 'VALIDATE' ? <><p>{output === 'ENTITY' ? model?.definition.label : output === 'LOG' ? '日志格式' : '指标格式'}</p><p>{output === 'ENTITY' ? '按实体模型执行必填、枚举和数值范围校验。' : output === 'LOG' ? '校验日志时间和正文，保留未提供的级别与上下文，不推断实体归属。' : '校验采样时间、指标名、数值与明确的 GAUGE 类型，不推断单位或计数器语义。'}</p></> : null}
            {node && editable(node.type) ? <div className="workflow-node-actions"><button type="button" disabled={locked || definition.edges.length !== definition.nodes.length - 1 || graphProblems(definition).length > 0 || definition.nodes[2]?.id === selected} onClick={() => reorder(-1)}>上移</button><button type="button" disabled={locked || definition.edges.length !== definition.nodes.length - 1 || graphProblems(definition).length > 0 || definition.nodes.at(-3)?.id === selected} onClick={() => reorder(1)}>下移</button><button type="button" disabled={locked} onClick={remove}>删除节点</button></div> : null}

          </div><div className="workflow-step-picker"><label>切换步骤<select aria-label="选择工作流节点" value={selected} onChange={e => selectStep(e.currentTarget.value)}>{definition.nodes.map((item,i) => <NodeOption key={item.id} node={item} label={String(i+1).padStart(2,'0') + ' ' + nodeLabel(item.type)} />)}</select></label></div></WorkflowInspector>
        </div>
        <details className="studio-testbench" open={testOpen} onToggle={e => setTestOpen(e.currentTarget.open)} ref={testRef}><summary><span><Play size={16} /><strong>测试数据与结果</strong><small>{report ? '通过 ' + report.receipt.accepted + ' · 失败 ' + report.receipt.rejected + ' · 过滤 ' + report.receipt.filtered : '填入样本，查看每一步的输入和输出'}</small></span><ChevronDown size={16} /></summary>
        <div className="workflow-preview"><section><h3>输入样本</h3>{definition.source.kind === 'MANUAL_SAMPLE' ? <><label>手工样本 JSON（默认内容为 Fixture）<Textarea aria-label="工作流手工样本" rows={8} spellCheck={false} maxLength={30000} disabled={busy} value={sample} onInput={e => { setSample((e.target as HTMLTextAreaElement).value); setReport(null); setSaved(e => e?.state === 'DRAFT' ? { ...e, preview: null } : e) }} /></label><p>最多5条、每条32个标量字段。样本只用于本次执行，不随草稿保存。</p>{currentMapping?<Button variant="outline" disabled={busy} onClick={insertMetricSample}>填入 Fixture 指标示例</Button>:null}</> : null}{definition.source.kind === 'ZABBIX_HOST' && !definition.source.configuration ? <><label>已有 Host 采集批次 ID<input aria-label="工作流来源批次" value={batch} disabled={busy} onInput={e => { setBatch((e.target as HTMLInputElement).value); setReport(null); setSaved(e => e?.state === 'DRAFT' ? { ...e, preview: null } : e) }} /></label><p>{'来源实例：' + definition.source.instanceId + ' · 最多读取5条'}</p><a href="#/integrations/zabbix/runs">查找来源扫描记录 ↗</a></> : null}{definition.source.configuration ? <div className="workflow-source-config"><p>{'固定接入实例 ' + definition.source.configuration.sourceId + ' · 配置 v' + definition.source.configuration.revision}</p><p>预览读取当前来源数据，不写入业务存储。</p></div> : null}</section><section><h3>节点输出</h3>{!report ? <p className="workflow-preview-empty">保存草稿后点击预览，再选择步骤查看结果。</p> : null}{report ? <><div className="workflow-result-counts"><span>{'通过 ' + report.receipt.accepted}</span><span>{'拒绝 ' + report.receipt.rejected}</span><span>{'过滤 ' + report.receipt.filtered}</span><strong>{origins[report.receipt.origin]}</strong></div>{report.sourceStatus !== 'MANUAL_SAMPLE' ? <p>{(report.sourceStatus === 'FAILED' ? '来源采集失败' : definition.source.configuration ? '来源读取成功' : '来源采集完成') + (definition.source.configuration ? ' · 已读取 ' : ' · 已保留 ') + report.retainedCount + ' 条' + (report.missingRaw ? ' · 原始数据缺失 ' + report.missingRaw + ' 条' : '') + (report.truncated ? ' · 仅展示部分记录' : '')}</p> : null}<label>样本记录<select aria-label="查看样本记录" value={String(row)} onChange={e => setRow(Number((e.target as HTMLSelectElement).value))}>{report.evaluation.rows.map(item => <option key={item.index} value={String(item.index)}>{'记录 ' + (item.index + 1) + ' · ' + rowStatuses[item.status]}</option>)}</select></label><p>{(currentStep?.status === 'OK' ? '通过' : currentStep?.status === 'ERROR' ? '失败' : currentStep?.status === 'FILTERED' ? '已过滤' : '未执行') + ' · ' + (node ? nodeLabel(node.type) : '')}</p><details className="workflow-node-input"><summary>查看节点输入</summary><pre data-workflow-input>{JSON.stringify(nodeInput ?? {}, null, 2)}</pre></details><pre data-workflow-output>{JSON.stringify(currentStep?.values ?? {}, null, 2)}</pre>{(currentStep?.issues ?? []).map((issue, i) => <p key={i} className="workflow-issue">{issue.field + '：' + (issueLabels[issue.code] ?? issue.code) + '（' + issue.code + '）'}</p>)}</> : null}</section></div>
        </details></div>{saved?.state === 'PUBLISHED' && output === 'ENTITY' && (!definition?.source.configuration || definition.source.kind === 'ZABBIX_HOST') ? <WorkflowRuntimeSection key={saved.digest} entry={saved} sample={sample} batch={batch} names={fields} onBusy={markBusy} /> : null}{saved?.state === 'PUBLISHED' && output === 'ENTITY' && definition?.source.configuration && definition.source.kind === 'ZABBIX_HOST' ? <WorkflowHostScheduleSection key={'schedule-'+saved.digest} entry={saved} names={fields} onBusy={markBusy} /> : null}
        {saved?.state==='PUBLISHED'&&saved.definition.source.kind==='ZABBIX_METRIC'?<><WorkflowMetricOutputSection key={saved.digest} entry={saved} mapping={currentMapping} onBusy={markBusy}/><WorkflowMetricStreamSection key={'stream-'+saved.digest} entry={saved} onBusy={markBusy}/></>:null}
        {saved?.state==='PUBLISHED'&&output==='LOG'&&['MANUAL_SAMPLE','ZABBIX_LOG'].includes(saved.definition.source.kind)?<WorkflowLogOutputSection key={'logs-'+saved.digest} entry={saved} sample={sample} onBusy={markBusy}/>:null}
        {saved?.state==='PUBLISHED'&&output==='LOG'&&saved.definition.source.kind==='ZABBIX_LOG'?<WorkflowLogStreamSection key={'log-stream-'+saved.digest} entry={saved} onBusy={markBusy}/>:null}
        {saved?.state==='PUBLISHED'&&['ZABBIX_HOST','ZABBIX_METRIC','ZABBIX_LOG'].includes(saved.definition.source.kind)?<WorkflowQualitySection key={'quality-'+saved.digest} entry={saved} onBusy={markBusy}/>:null}
      </> : null}
      {!definition && !onboarding ? <WorkflowLibrary workspace={workspace} taskId={taskId} selectTask={setTaskId} disabled={busy || dirty} open={e => void action('open', e)} compare={compare} create={createWorkflow} /> : null}
      <WorkflowComparisonPanel active={pageActive} requested={comparisonRequest} entries={[...workspace.drafts.items,...workspace.published.items]} truncated={workspace.drafts.truncated||workspace.published.truncated}/>

    </> : null}
  </section>
}
function NodeOption(props: { node: WorkflowNode; label: string }) {
  return <option value={props.node.id}>{props.label}</option>
}
function ConfigInput(props: { label: string; value: string; disabled: boolean; change: (value: string) => void }) {
  return <label>{props.label}<input aria-label={props.label} maxLength={512} value={props.value} disabled={props.disabled} onInput={e => props.change((e.target as HTMLInputElement).value)} /></label>
}
function MappingField(props: { field: Model['definition']['fields'][number]; namespace?: string; nodes: WorkflowNode[]; disabled: boolean; update: (to: string, from: string) => void }) {
  return <div className="studio-map-row"><input aria-label={'来源字段 → ' + props.field.id} placeholder="来源字段名" maxLength={48} disabled={props.disabled} value={Object.entries(props.nodes[1]?.config ?? {}).find(([, to]) => to === props.field.id)?.[0] ?? ''} onChange={e => props.update(props.field.id, (e.target as HTMLInputElement).value)} /><span aria-hidden="true">→</span><span className="studio-map-output"><strong>{props.field.label}</strong>{props.field.required ? <span className="studio-required">必填</span> : null}<small>{props.namespace ? props.namespace + '.' : ''}{props.field.id}</small></span></div>
}
