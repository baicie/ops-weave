import { useEffect, useRef, useState } from 'react'
import { oidcMode, usePlatformSession } from '../../state/platform-session.ts'
import { Button } from '@/components/ui/button'
import { Textarea } from '@/components/ui/textarea'
import { confirmSource, readSetup, readSources, workflowLink, type Command, type Setup, type SourcePage, type SourceType } from '../../api/source-setups.ts'
import { runConnectionCheck, type SourceConnectionCheck } from '../../api/source-connection-checks.ts'
import type { Model } from '../../api/workflows.ts'

const titles: Record<SourceType, string> = { ZABBIX_HOST: 'Zabbix', MANUAL_SAMPLE: 'JSON 手工样本', CMDB_SNAPSHOT: 'CMDB 快照' }
export function SourceCenterPage() {
  const [page, setPage] = useState<SourcePage | null>(null)
  const [kind, setKind] = useState<SourceType>('ZABBIX_HOST')
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [target, setTarget] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [pending, setPending] = useState<Command | null>(null)
  const [view, setView] = useState<Setup | null>(null)
  const [probe, setProbe] = useState<SourceConnectionCheck | null>(null)
  const dialogRef = useRef<HTMLDialogElement | null>(null)
  const focusRef = useRef<HTMLElement | null>(null)
  const activeRef = useRef<AbortController | undefined>(undefined)
  const disposedRef = useRef(false)
  const requestIdRef = useRef('')
  const ready = usePlatformSession(change => {
    activeRef.current?.abort(); activeRef.current = undefined; setBusy(false); setPage(null); setPending(null); setView(null); setName(''); setDescription(''); setTarget(''); setProbe(null); setError(change.error?.message ?? ''); setNotice(''); dialogRef.current?.close()
  })
  useEffect(() => () => { disposedRef.current = true; activeRef.current?.abort() }, [])
  const disabled = busy || !ready
  const option = page?.types.find(t => t.id === kind)
  const selectedModel = page?.models.find(m => m.definition.id + '@' + m.definition.revision === target)
  async function run(work: (signal: AbortSignal, current: () => boolean) => Promise<void>) {
    if (disabled) return; const c = new AbortController(); activeRef.current?.abort(); activeRef.current = c; setBusy(true); setError(''); setNotice(''); const current = () => !disposedRef.current && activeRef.current === c && !c.signal.aborted
    try { await work(c.signal, current) } catch (e) { if (current()) setError(e instanceof Error ? e.message : '数据源请求失败') } finally { if (current()) setBusy(false) }
  }
  function requireSession() {
    if (ready) return true
    setError(oidcMode ? '请先在页面顶部登录平台，再选择数据源类型。' : '请先在页面顶部填写平台开发 Token，再选择数据源类型。')
    const panel = document.querySelector<HTMLElement>('[data-platform-session]')
    panel?.scrollIntoView({ block: 'center' })
    const input = panel?.querySelector<HTMLInputElement>('input')
    input?.focus()
    return false
  }
  function open(type: SourceType, trigger: HTMLElement, sourcePage: SourcePage | null = page) {
    if (!ready || sourcePage?.types.find(t => t.id === type)?.status !== 'AVAILABLE') return
    focusRef.current = trigger; setKind(type); setView(null); setPending(null); setProbe(null); setError(''); setNotice('')
    setName(type === 'ZABBIX_HOST' ? 'Zabbix 主机接入' : '手工样本接入'); setDescription('')
    const m = sourcePage?.models.find(m => m.definition.id === (type === 'ZABBIX_HOST' ? 'builtin.host' : 'builtin.service')) ?? sourcePage?.models[0]
    setTarget(m ? m.definition.id + '@' + m.definition.revision : ''); requestIdRef.current = crypto.randomUUID(); dialogRef.current?.showModal()
  }
  function choose(type: SourceType) {
    if (busy || !requireSession()) return
    const trigger = document.activeElement as HTMLElement
    if (page) { open(type, trigger, page); return }
    void run(async (s, current) => {
      const p = await readSources(s); if (!current()) return; setPage(p)
      if (p.types.find(t => t.id === type)?.status !== 'AVAILABLE') { setError('当前身份无可用的该类型连接，请检查平台来源配置和权限。'); return }
      open(type, trigger, p)
    })
  }
  function load() { if (!requireSession()) return; void run(async (s, current) => { const p = await readSources(s); if (current()) { setPage(p); setNotice('已读取可用类型和本人接入配置。') } }) }
  function inspect(s: Setup) {
    focusRef.current = document.activeElement as HTMLElement; setView(s); setKind(s.source.kind); setName(s.name); setDescription(s.description)
    setTarget(s.initialTarget.id + '@' + s.initialTarget.revision); setPending(null); setProbe(null); setError(''); setNotice(''); dialogRef.current?.showModal()
  }
  function close() { if (!busy) dialogRef.current?.close() }
  function go(id: string) { void run(async (s, current) => { const r = await readSetup(id, s); if (current()) { dialogRef.current?.close(); location.hash = workflowLink(r.workflow) } }) }
  function confirm() {
    if (kind === 'CMDB_SNAPSHOT' || view) return
    const model = selectedModel, connection = option?.connection
    if (!pending && (!model || !connection || !name.trim())) return
    const command = pending ?? { requestId: requestIdRef.current, name: name.trim(), description: description.trim(), source: { kind: kind as 'ZABBIX_HOST' | 'MANUAL_SAMPLE', instanceId: connection!.instanceId }, connectionDigest: connection!.digest, target: { id: model!.definition.id, revision: model!.definition.revision, digest: model!.digest } }
    setPending(command)
    void run(async (s, current) => { const r = await confirmSource(command, s); if (current()) { setPending(null); dialogRef.current?.close(); location.hash = workflowLink(r.workflow) } })
  }
  function test() {
    setProbe(null)
    void run(async (s, current) => {
      const r = await runConnectionCheck(s)
      if (r.sourceInstanceId !== option?.connection?.instanceId) throw new Error('连接配置已变化，请重新读取数据源')
      if (current()) setProbe(r.check)
    })
  }
  return <section className="source-center" data-page="source-center">
    <header className="source-heading"><div><div className="model-eyebrow">DATA SOURCES · 数据接入</div><h2>数据源中心</h2><p>选择数据从哪里来，再把它整理成你的模型。</p></div><button type="button" disabled={busy} onClick={load}>读取数据源</button></header>
    {!ready ? <p className="source-access-hint" role="status">{oidcMode ? '尚未登录平台。请先在页面顶部登录，再创建接入配置。' : '尚未建立开发会话。请在页面顶部填写平台开发 Token；刷新或会话过期后需要重新填写。'}</p> : null}
    <ol className="source-steps" aria-label="接入步骤"><li><span>1</span><div><strong>选择类型</strong><small>确定来源与数据范围</small></div></li><li><span>2</span><div><strong>配置接入</strong><small>命名并选择初始目标模型</small></div></li><li><span>3</span><div><strong>画布编排</strong><small>映射、清洗与样本预览</small></div></li></ol>
    <div className="source-section-title"><h3>选择数据源类型</h3><span>{page ? (page.storage === 'postgres' ? 'PostgreSQL 持久化' : '开发内存 · 重启后丢失') : ready ? '直接选择类型即可读取并配置' : '先填写平台会话凭据'}</span></div>
    <div className="source-type-grid">
      <article className="source-type-card"><div className="source-card-top"><span className="source-mark source-mark-zabbix">Z</span><span className="model-pill">监控平台</span></div><h3>Zabbix</h3><p>读取已有主机采集批次，配置字段映射与默认清洗规则。</p><div className="source-card-meta"><span>Host 主机</span><span>版本化连接器</span></div><small>{page?.types.find(t => t.id === 'ZABBIX_HOST')?.connection?.dataMode === 'fixture' ? 'Fixture · 合成来源' : page?.types.find(t => t.id === 'ZABBIX_HOST')?.status === 'AVAILABLE' ? '平台已配置连接 · 连通性待测试' : page ? '当前身份无可用平台连接' : '读取后查看可用连接'}</small><button type="button" disabled={busy || !!page && page.types.find(t => t.id === 'ZABBIX_HOST')?.status !== 'AVAILABLE'} onClick={() => choose('ZABBIX_HOST')}>配置 Zabbix →</button></article>
      <article className="source-type-card"><div className="source-card-top"><span className="source-mark source-mark-json">{'{ }'}</span><span className="model-pill">样本输入</span></div><h3>JSON 手工样本</h3><p>从少量样本开始，验证自定义实体字段和清洗转换逻辑。</p><div className="source-card-meta"><span>内置 / 自定义实体</span><span>1–5 条样本</span></div><small>MANUAL_SAMPLE · 样本在画布中输入</small><button type="button" disabled={busy} onClick={() => choose('MANUAL_SAMPLE')}>配置手工样本 →</button></article>
      <article className="source-type-card source-type-legacy"><div className="source-card-top"><span className="source-mark">▦</span><span className="model-pill">已有导入入口</span></div><h3>CMDB 快照</h3><p>导入已有资源快照，核对来源对象与资源绑定。</p><div className="source-card-meta"><span>快照导入</span><span>来源核对</span></div><small>画布配置尚未开放</small><a href="#/integrations/cmdb">前往快照导入 ↗</a></article>
    </div>
    <div className="source-section-title source-saved-heading"><div><h3>我的接入配置</h3><p>保存的接入方案与初始工作流，不代表已启动采集。</p></div><a href="#/integrations/workflows">查看全部工作流 →</a></div>
    {!page?.setups.items.length ? <div className="source-empty"><span aria-hidden="true">↳</span><div><strong>{page ? '开始你的第一份接入配置' : '读取后查看已保存配置'}</strong><p>选择上方类型，确认配置后直接进入画布。</p></div></div> : null}
    <div className="source-saved-list">{(page?.setups.items ?? []).map(setup => <SetupRow key={setup.id} setup={setup} disabled={disabled} inspect={() => inspect(setup)} open={() => go(setup.id)} />)}</div>
    {page?.setups.truncated ? <p>仅显示最近20份配置；更多工作流可在工作流页查询。</p> : null}
    <p role="alert">{error}</p><p role="status">{busy ? '正在处理…' : notice}</p>
    <dialog className="model-drawer source-drawer" aria-label="数据源配置" ref={dialogRef} onCancel={e => { if (busy) e.preventDefault() }} onClose={() => focusRef.current?.focus()}>
      <header className="model-drawer-heading"><div><div className="model-eyebrow">{view ? '已保存 · 创建时配置' : '步骤 2 / 3 · 配置接入'}</div><h3>{titles[kind]}</h3></div><button type="button" className="model-close" aria-label="关闭数据源配置" disabled={busy} onClick={close}>×</button></header>
      <div className="model-drawer-body">
        <fieldset className="model-form" disabled={disabled || !!pending || !!view}><label>接入名称<input aria-label="接入名称" maxLength={80} value={name} onInput={e => setName((e.target as HTMLInputElement).value)} /></label><label>说明<Textarea aria-label="接入说明" maxLength={500} rows={2} value={description} onInput={e => setDescription((e.target as HTMLTextAreaElement).value)} /></label>
          {!view ? <label>初始目标模型<select aria-label="初始目标模型" value={target} onChange={e => setTarget((e.target as HTMLSelectElement).value)}>{(page?.models ?? []).map(model => <TargetOption key={model.definition.id + '@' + model.definition.revision} model={model} selected={target} />)}</select></label> : null}</fieldset>
        {view ? <><dl className="source-connection"><dt>来源实例</dt><dd>{view.source.instanceId}</dd><dt>创建时来源标记</dt><dd>{view.dataMode}</dd><dt>初始目标模型</dt><dd>{view.initialTarget.id + ' @ ' + view.initialTarget.revision}</dd><dt>创建时间</dt><dd>{view.createdAt}</dd></dl><p className="model-muted">这是创建时的配置快照。继续编排打开第一版工作流；后续修改以画布中保存的定义为准。</p></> : null}
        {!view && kind === 'ZABBIX_HOST' ? <section className="source-connection-box"><h4>使用平台已配置的连接</h4><dl className="source-connection"><dt>来源实例</dt><dd>{option?.connection?.instanceId}</dd><dt>API 地址</dt><dd>{option?.connection?.endpoint ?? 'Fixture · 无外部连接'}</dd><dt>凭据引用</dt><dd>{option?.connection?.credentialRef ?? '无需凭据'}</dd><dt>数据标记</dt><dd>{option?.connection?.dataMode}</dd></dl><p>本页选择已有连接。新增地址和凭据由平台配置，暂不支持多实例新增。</p><button type="button" disabled={disabled || !!pending} onClick={test}>测试连接</button>{probe ? <div className="source-probe" role="status"><strong>{probe.dataMode === 'labeled-fixture' ? 'Fixture 自检 · 非真实连接' : probe.reachable ? '连接测试成功' : '连接测试失败'}</strong><p>{'来源标记：' + probe.dataMode + ' · 状态：' + probe.statusCode}</p><p>{probe.reportedVersion ? '来源报告版本：' + probe.reportedVersion + '；连通性测试不等于完整采集兼容验收。' : '未取得真实版本信息。'}</p></div> : null}</section> : null}
        {!view && kind === 'MANUAL_SAMPLE' ? <div className="source-connection-box"><h4>使用手工 JSON 样本</h4><p>确认后在画布输入1–5条标量对象样本，用于只读转换预览。样本正文不保存在接入配置中。</p><span className="model-pill">MANUAL_SAMPLE</span></div> : null}
        {!view ? <section className="source-next"><h4>确认后，为你准备好</h4><p>数据输入 → 字段映射 → 去除空白 → 模型校验 → 输出预览</p><small>默认规则可在画布调整；确认只保存配置和草稿，不启动采集。</small></section> : null}
        {page?.modelsTruncated ? <p>模型列表仅包含前50个自定义版本。</p> : null}
        {pending ? <p className="source-pending">确认请求已固定。若结果待确认，可按原请求查询或重试；重新配置前请先查询，避免重复创建。</p> : null}<p role="alert">{error}</p><p role="status">{busy ? '正在处理…' : notice}</p>
      </div><footer className="model-drawer-footer"><button type="button" disabled={busy} onClick={close}>关闭</button>{pending ? <button type="button" disabled={disabled} onClick={() => go(pending.requestId)}>查询确认结果</button> : null}{!view ? <Button type="button" className="source-primary" disabled={disabled || !name.trim() || !selectedModel} onClick={confirm}>{pending ? '按原配置重试' : '确认并进入画布'}</Button> : null}{view ? <Button type="button" className="source-primary" disabled={disabled} onClick={() => go(view.id)}>继续编排</Button> : null}</footer>
    </dialog>
  </section>
}
function TargetOption(p: { model: Model; selected: string }) {
  const value = p.model.definition.id + '@' + p.model.definition.revision
  return <option value={value}>{p.model.definition.label + ' · ' + value}</option>
}
function SetupRow(p: { setup: Setup; disabled: boolean; inspect: () => void; open: () => void }) {
  return <article className="source-saved-row"><span className="source-mark">{p.setup.source.kind === 'ZABBIX_HOST' ? 'Z' : '{ }'}</span><div className="source-saved-info"><h4>{p.setup.name}</h4><p>{titles[p.setup.source.kind] + ' · ' + p.setup.dataMode + ' · ' + p.setup.source.instanceId + ' → ' + p.setup.initialTarget.id + ' @ ' + p.setup.initialTarget.revision}</p><small>{'接入方案已保存 · ' + new Date(p.setup.createdAt).toLocaleString()}</small></div><div className="source-saved-actions"><button type="button" disabled={p.disabled} onClick={p.inspect}>查看配置</button><button type="button" disabled={p.disabled} onClick={p.open}>继续编排 →</button></div></article>
}
