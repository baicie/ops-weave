import { WorkflowCanvas } from '../../adapters/graph/WorkflowCanvas.tsx'
import { workflowSelection } from '../../state/workflow-selection.ts'
import { useEffect, useRef, useState } from 'react'
import { Button } from '@/components/ui/button'
import { Textarea } from '@/components/ui/textarea'
import { oidcMode, usePlatformSession } from '../../state/platform-session.ts'
import { OPERATORS, arrange, connect, evaluateWorkflow, publishWorkflow, readWorkflow, readWorkspace, saveWorkflow, stable, template, type Definition, type Entry, type Evaluation, type Layout, type Model, type NodeType, type Operator, type WorkflowNode, type Workspace } from '../../api/workflows.ts'

const labels: Record<NodeType, string> = { SOURCE: '数据输入', MAP: '字段映射', TRIM: '去除空白', EMPTY_TO_NULL: '空串转空值', DEFAULT: '补充默认值', ENUM_MAP: '枚举替换', SCALE: '数值换算', FILTER: '条件过滤', VALIDATE: '模型校验', OUTPUT: '输出预览' }
const hints: Record<NodeType, string> = { SOURCE: '手工样本或已有 Zabbix 采集批次', MAP: '将来源字段对应到目标实体字段', TRIM: '去除所有文本值的首尾空白', EMPTY_TO_NULL: '将指定字段的空白字符串转为 null', DEFAULT: '仅为缺失字段填入显式默认值，保留 null', ENUM_MAP: '精确替换指定字段的一个文本值', SCALE: '将数值乘以固定系数，不猜测单位', FILTER: '保留字段文本值完全相等的记录', VALIDATE: '执行固定模型版本的默认转换与约束', OUTPUT: '返回转换结果，测试运行不写入资产' }
const icons: Record<NodeType, string> = { SOURCE: '↳', MAP: '⇄', TRIM: 'Aa', EMPTY_TO_NULL: '∅', DEFAULT: '＋', ENUM_MAP: '≍', SCALE: '×', FILTER: '▽', VALIDATE: '✓', OUTPUT: '↗' }
const editable = (type: NodeType) => OPERATORS.includes(type as Operator)
type Snapshot = { definition: Definition; layout: Layout }

export function WorkflowPage() {
  const [workspace, setWorkspace] = useState<Workspace | null>(null)
  const [definition, setDefinition] = useState<Definition | null>(null)
  const [layout, setLayout] = useState<Layout>({})
  const [saved, setSaved] = useState<Entry | null>(null)
  const [selected, setSelected] = useState('source')
  const [report, setReport] = useState<Evaluation | null>(null)
  const [sample, setSample] = useState('[{ "name": " Fixture 示例服务 ", "port": "8080" }]')
  const [batch, setBatch] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [undo, setUndo] = useState<Snapshot[]>([])
  const [redo, setRedo] = useState<Snapshot[]>([])
  const [zoom, setZoom] = useState(1)
  const [fit, setFit] = useState(0)
  const [row, setRow] = useState(0)
  const activeRef = useRef<AbortController | undefined>(undefined)
  const disposedRef = useRef(false)
  const busyRef = useRef(false)
  const ready = usePlatformSession(change => {
    activeRef.current?.abort(); busyRef.current = false; setBusy(false); setWorkspace(null); setDefinition(null); setLayout({}); setSaved(null); setReport(null); setSample(''); setBatch(''); setUndo([]); setRedo([]); setNotice(''); setError(change.error?.message ?? '')
  })
  const dirty = Boolean(definition && (!saved || stable(definition) !== stable(saved.definition) || stable(layout) !== stable(saved.layout)))
  const locked = busy || saved?.state === 'PUBLISHED'
  const node = definition?.nodes.find(n => n.id === selected)
  const model = workspace?.models.find(m => m.definition.id === definition?.target.id && m.definition.revision === definition?.target.revision)
  const currentStep = report?.evaluation.rows[row]?.steps.find(s => s.nodeId === selected)
  const dirtyRef = useRef(dirty)
  const readyRef = useRef(ready)
  const actionRef = useRef<(kind: 'load' | 'save' | 'preview' | 'publish' | 'open', entry?: Entry) => Promise<void>>(async () => {})
  dirtyRef.current = dirty
  readyRef.current = ready
  function markBusy(value: boolean) { busyRef.current = value; setBusy(value) }

  function snapshot(): Snapshot { return { definition: structuredClone(definition!), layout: structuredClone(layout) } }
  function change(next: Definition, positions: Layout = layout, semantic = true) {
    if (locked) return
    if (definition) setUndo(v => [...v.slice(-29), snapshot()])
    setRedo([]); setDefinition(connect(next)); setLayout(positions); setError(''); setNotice(''); if (semantic) setReport(null)
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
    if (!target) return
    if (dirty) { setError('当前草稿有未保存修改，请先保存再新建工作流。'); return }
    const d = template(target, { kind, instanceId: kind === 'MANUAL_SAMPLE' ? 'manual' : workspace!.zabbixSource.instanceId })
    setDefinition(d); setLayout(arrange(d)); setSaved(null); setSelected('source'); setReport(null); setUndo([]); setRedo([]); setError(''); setNotice('已生成模板。可配置映射与清洗节点；保存后进行预览。'); setSample('[{ "name": " Fixture 示例服务 ", "port": "8080" }]'); setBatch('')
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
    const m = workspace?.models.find(m => m.definition.id + '@' + m.definition.revision === value)
    const d = definition
    if (!m || !d) return
    const fresh = template(m, d.source)
    change({ ...d, target: fresh.target, nodes: fresh.nodes }, arrange(fresh))
    setSelected('mapping'); setNotice('已按目标模型重建默认映射和清洗链，请重新校验样本。')
  }
  function add(type: Operator) {
    const d = definition; const m = model
    if (!d || !m || d.nodes.length >= 16 || locked) return
    const id = 'step-' + crypto.randomUUID().slice(0, 8)
    const field = m.definition.fields[0]?.id ?? 'name'
    const config: Record<string, string> = type === 'TRIM' ? {} : type === 'EMPTY_TO_NULL' ? { field } : type === 'DEFAULT' ? { field, value: '' } : type === 'ENUM_MAP' ? { field, from: '', to: '' } : type === 'SCALE' ? { field, factor: '1' } : { field, equals: '' }
    const nodes = [...d.nodes.slice(0, -2), { id, type, version: '1' as const, config }, ...d.nodes.slice(-2)]
    const next = connect({ ...d, nodes })
    change(next, arrange(next)); setSelected(id)
  }
  function reorder(delta: number) {
    const d = definition
    if (!d) return
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
    const def = connect({ ...d, nodes: d.nodes.filter(x => x.id !== n.id) })
    change(def, arrange(def)); setSelected('mapping')
  }
  function acceptEntry(e: Entry) {
    setDefinition(e.definition); setLayout(e.layout); setSaved(e); setUndo([]); setRedo([]); setReport(null); setSelected('source')
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
    const controller = new AbortController(); activeRef.current?.abort(); activeRef.current = controller; markBusy(true); setError(''); setNotice('')
    const hash = location.hash
    const current = () => !disposedRef.current && activeRef.current === controller && !controller.signal.aborted && location.hash === hash
    try {
      if (kind === 'load') {
        const selection = workflowSelection(hash)
        const result = await readWorkspace(controller.signal)
        const chosen = selection ? await readWorkflow(selection.id, selection.revision, selection.state, controller.signal) : null
        if (current()) {
          setWorkspace(result)
          if (chosen) { acceptEntry(chosen); setSample(''); setBatch('') }
          else { setDefinition(null); setSaved(null); setReport(null); setLayout({}) }
          setNotice(chosen ? '已载入所选工作流。可配置清洗规则并手动预览。' : '已读取工作流、模型和本人运行回执。')
        }
      } else if (kind === 'save') {
        if (!definition) return
        const result = await saveWorkflow(definition, layout, saved?.editVersion ?? 0, controller.signal)
        if (current()) { const oldReport = report; acceptEntry(result); setReport(oldReport); setNotice('草稿已保存；尚未发布或启用采集。') }
      } else if (kind === 'open') {
        if (!entry) return
        const e = await readWorkflow(entry.definition.id, entry.definition.revision, entry.state, controller.signal)
        if (current()) { acceptEntry(e); setSample(''); setBatch(''); setNotice(e.state === 'PUBLISHED' ? '已载入不可变版本，可测试运行或创建下一版。' : '已载入本人草稿。') }
      } else if (kind === 'publish') {
        if (!saved || dirtyRef.current) throw new Error('请先保存并预览当前定义')
        const result = await publishWorkflow(saved, controller.signal)
        if (current()) { acceptEntry(result); setNotice('版本已发布。当前支持只读测试运行，未启用自动采集或写入。') }
      } else {
        if (!saved || dirtyRef.current) throw new Error('请先保存当前工作流')
        let samples: unknown
        if (definition?.source.kind === 'MANUAL_SAMPLE') {
          try { samples = JSON.parse(sample) } catch { throw new Error('手工样本应为 JSON 对象数组，最多5条。') }
        } else if (!/^[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}$/.test(batch)) throw new Error('请输入有效的已有 Host 采集批次 ID')
        const result = await evaluateWorkflow(saved, samples, batch, controller.signal)
        if (current()) {
          setReport(result); setRow(0)
          if (saved?.state === 'DRAFT') setSaved(e => e ? { ...e, preview: result.receipt } : e)
          setWorkspace(w => w ? { ...w, runs: { ...w.runs, items: [{ workflowId: saved!.definition.id, revision: saved!.definition.revision, mode: saved!.state === 'DRAFT' ? 'PREVIEW' as const : 'RUN' as const, receipt: result.receipt }, ...w.runs.items].slice(0, 20) } } : w)
          setNotice(result.receipt.rejected ? '存在拒绝记录，点击节点查看原因。' : '测试完成，未写入资产或调用模型。')
        }
      }
    } catch (e) { if (current()) setError(e instanceof Error ? e.message : '工作流请求失败') }
    finally { if (activeRef.current === controller) markBusy(false) }
  }
  actionRef.current = action

  useEffect(() => {
    function navigate() {
      if (disposedRef.current || location.hash.split('?')[0] !== '#/integrations/workflows') return
      activeRef.current?.abort(); activeRef.current = undefined; markBusy(false)
      if (dirtyRef.current) { setError('当前画布有未保存修改，请保存或放弃后点击读取工作流。'); return }
      void actionRef.current('load')
    }
    window.addEventListener('hashchange', navigate)
    queueMicrotask(() => { if (!disposedRef.current && location.hash.includes('?') && readyRef.current) void actionRef.current('load') })
    return () => { disposedRef.current = true; activeRef.current?.abort(); window.removeEventListener('hashchange', navigate) }
  }, [])

  function discard() {
    if (busy) return
    const e = saved
    if (e) acceptEntry(e)
    else { setDefinition(null); setLayout({}); setUndo([]); setRedo([]); setReport(null) }
    setNotice('本地修改已放弃。')
  }
  function nextVersion() {
    const e = saved
    if (!e || e.state !== 'PUBLISHED') return
    setDefinition({ ...e.definition, revision: e.definition.revision + 1 }); setSaved(null); setReport(null); setUndo([]); setRedo([]); setNotice('已创建下一版的本地编辑副本，原发布版本保持不变。')
  }
  return <section className="workflow-page" data-page="workflows">
    <div className="workflow-heading"><div><div className="model-eyebrow">DATA WORKFLOW · 数据接入</div><h2>工作流</h2><p>从模板开始，配置清洗规则，把来源数据转换为你的实体模型。</p></div><button type="button" disabled={busy || !ready || dirty} onClick={() => void action('load')}>读取工作流</button></div>
    {!ready ? <p className="source-access-hint" role="status">{oidcMode ? '尚未登录平台。请先在页面顶部登录，再读取或创建工作流。' : '尚未建立开发会话。请在页面顶部填写平台开发 Token，再读取或创建工作流。'}</p> : null}
    <div className="workflow-boundary"><a href="#/integrations/sources">← 数据源中心</a><span className="lifecycle-pill">只读测试运行</span><span>预览与已发布版本均不写入资产；自动采集绑定和关系输出后续接入。</span><a href="#/integrations/workflows/runs">运行记录 ↗</a><a href="#/integrations/pipelines">Host 采集维护 ↗</a></div>
    <p role="alert">{error}</p><p role="status">{busy ? '正在处理…' : notice}</p>
    {!workspace ? <div className="workflow-welcome"><span className="workflow-welcome-icon">⌘</span><h3>让每一步转换都清晰可见</h3><p>授权后读取工作流，选择模板与固定模型版本，再使用样本逐步验证。</p><div>数据输入 → 字段映射 → 清洗转换 → 模型校验 → 输出预览</div></div> : null}
    {workspace ? <>
      <div className="workflow-template-bar"><button type="button" disabled={busy || dirty} onClick={() => chooseTemplate('MANUAL_SAMPLE')}>＋ 自定义实体模板</button><button type="button" disabled={busy || dirty || !workspace.zabbixSource.instanceId} onClick={() => chooseTemplate('ZABBIX_HOST')}>＋ Zabbix 主机模板</button><span>{workspace.storage === 'postgres' ? 'PostgreSQL 持久化' : '开发内存 · 重启后丢失'}</span><button type="button" disabled title="AI建议将在后续开放">✧ AI 协助（后续开放）</button></div>
      {workspace.modelsTruncated ? <p>模型目录仅显示前50个自定义版本，请先在模型中心确认需要的类型。</p> : null}
      {definition ? <>
        <div className="workflow-editor-header"><label>工作流名称<input aria-label="工作流名称" value={definition.name ?? ''} disabled={locked} maxLength={80} onInput={e => change({ ...definition, name: (e.target as HTMLInputElement).value })} /></label><label>目标模型<select aria-label="目标模型" value={(definition.target.id ?? '') + '@' + definition.target.revision} disabled={locked} onChange={e => target((e.target as HTMLSelectElement).value)}>{workspace.models.filter(m => m.definition.fields.length).map(item => <ModelOption key={item.definition.id + '@' + item.definition.revision} model={item} />)}</select></label><div className="workflow-state"><strong>{saved?.state === 'PUBLISHED' ? '已发布' : dirty ? '未保存修改' : '草稿已保存'}</strong><small>{'v' + definition.revision + ' · ' + definition.id}</small></div></div>
        <div className="workflow-actions"><button type="button" disabled={locked || !dirty} onClick={() => void action('save')}>保存草稿</button><button type="button" disabled={busy || dirty || !saved} onClick={() => void action('preview')}>{saved?.state === 'PUBLISHED' ? '测试运行已发布版本' : '预览当前草稿'}</button><Button className="workflow-publish" type="button" disabled={locked || dirty || !saved?.preview || saved?.preview?.rejected !== 0 || !saved?.preview?.accepted} onClick={() => void action('publish')}>发布版本</Button>{saved?.state === 'PUBLISHED' ? <button type="button" disabled={busy} onClick={nextVersion}>创建下一版</button> : null}<button type="button" disabled={busy || !dirty} onClick={discard}>放弃本地修改</button><span>发布需有效预览；变更规则后重新预览。</span></div>
        <div className="workflow-editor">
          <aside className="workflow-library"><h3>清洗节点</h3><p>点击添加，按顺序执行</p>{OPERATORS.map(type => <LibraryItem key={type} type={type} disabled={locked || definition.nodes.length >= 16} add={add} />)}<small>最多16个节点 · 单条主链<br />不执行脚本或任意网络请求</small></aside>
          <div className="workflow-canvas-column"><div className="workflow-canvas-toolbar"><span>{definition.nodes.length + ' 个节点 · 单链执行'}</span><div><button type="button" aria-label="撤销工作流修改" disabled={locked || !undo.length} onClick={() => restore('undo')}>↶</button><button type="button" aria-label="重做工作流修改" disabled={locked || !redo.length} onClick={() => restore('redo')}>↷</button><button type="button" aria-label="自动排列节点" disabled={locked} onClick={() => change(definition, arrange(definition), false)}>排列</button><button type="button" aria-label="缩小画布" disabled={zoom <= .25} onClick={() => setZoom(z => Math.max(.25, Math.round((z - .1) * 10) / 10))}>−</button><span>{Math.round(zoom * 100) + '%'}</span><button type="button" aria-label="放大画布" disabled={zoom >= 1.5} onClick={() => setZoom(z => Math.min(1.5, Math.round((z + .1) * 10) / 10))}>＋</button><button type="button" aria-label="适应画布" onClick={() => setFit(v => v + 1)}>适应</button></div></div>
            <WorkflowCanvas definition={definition} layout={layout} selected={selected} select={setSelected} zoom={zoom} onZoom={setZoom} fit={fit} locked={locked} move={(id, x, y) => change(definition, { ...layout, [id]: { x, y } }, false)} />
            <div className="workflow-step-picker"><label>选择节点<select aria-label="选择工作流节点" value={selected} onChange={e => setSelected((e.target as HTMLSelectElement).value)}>{definition.nodes.map(item => <NodeOption key={item.id} node={item} />)}</select></label></div>
          </div>
          <aside className="workflow-inspector"><h3>{node ? labels[node.type] : '节点配置'}</h3><p>{node ? hints[node.type] : ''}</p>
            {node?.type === 'SOURCE' ? <><label>输入来源<select aria-label="输入来源" disabled={locked} value={definition.source.kind ?? 'MANUAL_SAMPLE'} onChange={e => { const kind = (e.target as HTMLSelectElement).value as 'MANUAL_SAMPLE' | 'ZABBIX_HOST'; change({ ...definition, source: { kind, instanceId: kind === 'MANUAL_SAMPLE' ? 'manual' : workspace.zabbixSource.instanceId } }) }}><option value="MANUAL_SAMPLE">手工样本</option><option value="ZABBIX_HOST" disabled={!workspace.zabbixSource.instanceId}>已有 Zabbix 主机批次</option></select></label><p>读取已有批次经过 v1 映射与对象授权的 name、ip、lifecycle、entity_id 字段，不触发新采集。</p></> : null}
            {node?.type === 'MAP' ? <>{(model?.definition.fields ?? []).map(field => <MappingField key={field.id} field={field} nodes={definition.nodes} disabled={locked} update={mapping} />)}<p>留空表示不映射。缺失的必填字段会在校验节点报告。</p></> : null}
            {node?.config.field !== undefined ? <label>目标字段<select aria-label="节点目标字段" value={node.config.field ?? ''} disabled={locked} onChange={e => editConfig('field', (e.target as HTMLSelectElement).value)}>{(model?.definition.fields ?? []).map(field => <option key={field.id} value={field.id}>{field.label + ' · ' + field.id}</option>)}</select></label> : null}
            {node?.type === 'DEFAULT' ? <ConfigInput label="缺失时填入" value={node.config.value ?? ''} disabled={locked} change={value => editConfig('value', value)} /> : null}
            {node?.type === 'ENUM_MAP' ? <><ConfigInput label="原文本值" value={node.config.from ?? ''} disabled={locked} change={value => editConfig('from', value)} /><ConfigInput label="替换为" value={node.config.to ?? ''} disabled={locked} change={value => editConfig('to', value)} /></> : null}
            {node?.type === 'SCALE' ? <><ConfigInput label="换算系数" value={node.config.factor ?? ''} disabled={locked} change={value => editConfig('factor', value)} /><p>例如百分数转比例填0.01。转换失败保留拒绝原因。</p></> : null}
            {node?.type === 'FILTER' ? <><ConfigInput label="保留的文本值" value={node.config.equals ?? ''} disabled={locked} change={value => editConfig('equals', value)} /><p>过滤记录单独计数，不用于资源删除或退役。</p></> : null}
            {node?.type === 'VALIDATE' ? <><p>{'固定模型：' + definition.target.id + ' @ ' + definition.target.revision}</p><p>按模型执行默认标量转换、必填、枚举和数值范围校验。</p></> : null}
            {node && editable(node.type) ? <div className="workflow-node-actions"><button type="button" disabled={locked || definition.nodes[2]?.id === selected} onClick={() => reorder(-1)}>上移</button><button type="button" disabled={locked || definition.nodes.at(-3)?.id === selected} onClick={() => reorder(1)}>下移</button><button type="button" disabled={locked} onClick={remove}>删除节点</button></div> : null}
          </aside>
        </div>
        <div className="workflow-preview"><section><h3>输入样本</h3>{definition.source.kind === 'MANUAL_SAMPLE' ? <><label>手工样本 JSON（默认内容为 Fixture）<Textarea aria-label="工作流手工样本" rows={8} spellCheck={false} maxLength={30000} disabled={busy} value={sample} onInput={e => { setSample((e.target as HTMLTextAreaElement).value); setReport(null) }} /></label><p>最多5条、每条32个标量字段。样本只用于本次执行，不随草稿保存。</p></> : null}{definition.source.kind === 'ZABBIX_HOST' ? <><label>已有 Host 采集批次 ID<input aria-label="工作流来源批次" value={batch} disabled={busy} onInput={e => { setBatch((e.target as HTMLInputElement).value); setReport(null) }} /></label><p>{'来源实例：' + definition.source.instanceId + ' · 最多读取5条'}</p><a href="#/integrations/zabbix/runs">查找来源扫描记录 ↗</a></> : null}</section><section><h3>节点输出</h3>{!report ? <p className="workflow-preview-empty">保存并运行预览后，选择画布节点查看这一阶段的值与问题。</p> : null}{report ? <><div className="workflow-result-counts"><span>{'通过 ' + report.receipt.accepted}</span><span>{'拒绝 ' + report.receipt.rejected}</span><span>{'过滤 ' + report.receipt.filtered}</span><strong>{report.receipt.origin}</strong></div><p>{'来源状态：' + report.sourceStatus + ' · 保留 ' + report.retainedCount + ' · 缺失 Raw ' + report.missingRaw + (report.truncated ? ' · 仅展示有界样本' : '')}</p><label>样本记录<select aria-label="查看样本记录" value={String(row)} onChange={e => setRow(Number((e.target as HTMLSelectElement).value))}>{report.evaluation.rows.map(item => <option key={item.index} value={String(item.index)}>{'记录 ' + (item.index + 1) + ' · ' + item.status}</option>)}</select></label><p>{(currentStep?.status ?? '') + ' · ' + (node ? labels[node.type] : '')}</p><pre data-workflow-output>{JSON.stringify(currentStep?.values ?? {}, null, 2)}</pre>{(currentStep?.issues ?? []).map((issue, i) => <p key={i} className="workflow-issue">{issue.field + '：' + issue.code}</p>)}</> : null}</section></div>
      </> : null}
      <div className="workflow-saved"><section><h3>我的草稿</h3>{!workspace.drafts.items.length ? <p>还没有保存的工作流。</p> : null}{workspace.drafts.items.map(entry => <SavedRow key={entry.definition.id + '@' + entry.definition.revision + ':draft:' + entry.editVersion} entry={entry} disabled={busy || dirty} open={e => void action('open', e)} />)}{workspace.drafts.truncated ? <p>仅显示最近20项。</p> : null}</section><section><h3>已发布版本</h3>{!workspace.published.items.length ? <p>预览通过后即可发布固定版本。</p> : null}{workspace.published.items.map(entry => <SavedRow key={entry.definition.id + '@' + entry.definition.revision + ':pub'} entry={entry} disabled={busy || dirty} open={e => void action('open', e)} />)}{workspace.published.truncated ? <p>仅显示最近20项。</p> : null}</section></div>
      <section className="workflow-history"><h3>我的运行记录</h3><p>新运行保存逐条状态、失败节点和错误码，可在运行记录页回查。样本正文只在本次预览中显示。</p>{!workspace.runs.items.length ? <p>尚无运行记录。</p> : null}{workspace.runs.items.map(run => <RunRow key={run.receipt.id} run={run} />)}{workspace.runs.truncated ? <p>仅显示最近20项运行回执。</p> : null}</section>
    </> : null}
  </section>
}
function ModelOption(props: { model: Model }) {
  return <option value={props.model.definition.id + '@' + props.model.definition.revision}>{props.model.definition.label + ' · v' + props.model.definition.revision + ' · ' + (props.model.definition.id.startsWith('builtin.') ? '内置' : '自定义')}</option>
}
function NodeOption(props: { node: WorkflowNode }) {
  return <option value={props.node.id}>{labels[props.node.type] + ' · ' + props.node.id}</option>
}
function LibraryItem(props: { type: Operator; disabled: boolean; add: (type: Operator) => void }) {
  return <button type="button" className="workflow-library-item" disabled={props.disabled} onClick={() => props.add(props.type)}><b>{icons[props.type]}</b><span>{labels[props.type]}<small>{hints[props.type]}</small></span><em>＋</em></button>
}
function ConfigInput(props: { label: string; value: string; disabled: boolean; change: (value: string) => void }) {
  return <label>{props.label}<input aria-label={props.label} maxLength={512} value={props.value} disabled={props.disabled} onInput={e => props.change((e.target as HTMLInputElement).value)} /></label>
}
function MappingField(props: { field: Model['definition']['fields'][number]; nodes: WorkflowNode[]; disabled: boolean; update: (to: string, from: string) => void }) {
  return <label className="workflow-mapping-field">{props.field.label + (props.field.required ? ' *' : '')}<small>{props.field.id + ' · ' + props.field.type}</small><input aria-label={'来源字段 → ' + props.field.id} placeholder="来源字段名" maxLength={48} disabled={props.disabled} value={Object.entries(props.nodes[1]?.config ?? {}).find(([, to]) => to === props.field.id)?.[0] ?? ''} onChange={e => props.update(props.field.id, (e.target as HTMLInputElement).value)} /></label>
}
function SavedRow(props: { entry: Entry; disabled: boolean; open: (e: Entry) => void }) {
  return <div className="workflow-saved-row"><span><strong>{props.entry.definition.name}</strong><small>{props.entry.definition.id + ' · v' + props.entry.definition.revision + ' · ' + (props.entry.state === 'DRAFT' ? '编辑号 ' + props.entry.editVersion : '不可变版本')}</small></span><button type="button" disabled={props.disabled} onClick={() => props.open(props.entry)}>打开</button></div>
}
function RunRow(props: { run: Workspace['runs']['items'][number] }) {
  return <div className="workflow-run-row"><span>{props.run.workflowId + ' · v' + props.run.revision}<small>{props.run.receipt.createdAt}</small></span><span>{(props.run.mode === 'PREVIEW' ? '草稿预览' : '版本测试') + ' · ' + props.run.receipt.origin}</span><span>{'通过 ' + props.run.receipt.accepted + ' / 拒绝 ' + props.run.receipt.rejected + ' / 过滤 ' + props.run.receipt.filtered}</span><a href={'#/integrations/workflows/runs?runId=' + props.run.receipt.id}>查看明细 →</a></div>
}
