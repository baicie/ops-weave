import { useEffect, useRef, useState } from 'react'
import { usePageActive, usePageCloseGuard, type PageCloseReason } from '../../state/page-workspace.ts'
import { useInitialPageRead } from '../../state/initial-page-read.ts'
import { localPreviewMode, oidcMode, usePlatformSession } from '../../state/platform-session.ts'
import { Button } from '@/components/ui/button'
import { confirmSource, readSetup, readSetupContinuation, readSources, setupWorkflowLink, type Command, type Setup, type SourcePage, type SourceType } from '../../api/source-setups.ts'
import { runConnectionCheck, type SourceConnectionCheck } from '../../api/source-connection-checks.ts'

import { IntegrationSearchField } from '../../components/integrations/IntegrationSearchField.tsx'
import { SourceCatalog } from '../../components/integrations/SourceCatalog.tsx'
import { IntegrationViewTabs } from '../../components/integrations/IntegrationViewTabs.tsx'
import { sourceCatalog, sourceTitles as titles } from '../../components/integrations/source-catalog.ts'
import { SourceTaskList } from '../../components/integrations/SourceTaskList.tsx'
import { SourceSetupDrawer } from '../../components/integrations/SourceSetupDrawer.tsx'
import { SourceMetricCatalog } from './SourceMetricCatalog.tsx'
import { SourceInstancePanel } from './SourceInstancePanel.tsx'
import { SourceCredentialPanel } from './SourceCredentialPanel.tsx'
import type { InstancePage } from '../../api/source-instances.ts'
export function SourceCenterPage() {
  const pageActive = usePageActive()
  const pageActiveRef = useRef(pageActive)
  pageActiveRef.current = pageActive
  const [instanceRequest, setInstanceRequest] = useState<{ nonce: number; id: string } | null>(null)
  const [tab, setTab] = useState<'catalog' | 'tasks' | 'instances' | 'credentials'>('instances')
  const [instancePage, setInstancePage] = useState<InstancePage | null>(null)
  const [instanceRefresh, setInstanceRefresh] = useState(0)
  const [instanceGuard, setInstanceGuard] = useState<PageCloseReason>(null)
  const [credentialGuard, setCredentialGuard] = useState<PageCloseReason>(null)
  const [query, setQuery] = useState('')
  const [typeFilter, setTypeFilter] = useState<SourceType | 'ALL'>('ALL')
  const [page, setPage] = useState<SourcePage | null>(null)
  const [kind, setKind] = useState<SourceType>('ZABBIX_HOST')
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [pending, setPending] = useState<Command | null>(null)
  const [view, setView] = useState<Setup | null>(null)
  const [probe, setProbe] = useState<SourceConnectionCheck | null>(null)
  const dialogRef = useRef<HTMLDialogElement | null>(null)
  const focusRef = useRef<HTMLElement | null>(null)
  const activeRef = useRef<AbortController | undefined>(undefined)
  const readingCatalog = useRef(false)
  const disposedRef = useRef(false)
  const requestIdRef = useRef('')
  const ready = usePlatformSession(change => {
    activeRef.current?.abort(); activeRef.current = undefined; setBusy(false); setPage(null); setInstancePage(null); setInstanceRequest(null); setTab('instances'); setQuery(''); setTypeFilter('ALL'); setPending(null); setView(null); setName(''); setDescription(''); setProbe(null); setError(change.error?.message ?? ''); setNotice(''); dialogRef.current?.close()
  })
  useEffect(() => { disposedRef.current = false; return () => { disposedRef.current = true; activeRef.current?.abort() } }, [])
  useEffect(() => {
    if (readingCatalog.current && (!pageActive || tab !== 'catalog' && tab !== 'tasks')) {
      activeRef.current?.abort(); activeRef.current = undefined; readingCatalog.current = false; setBusy(false)
    }
  }, [pageActive, tab])
  const disabled = busy || !ready
  usePageCloseGuard(busy || pending ? { message: pending ? '接入确认结果仍待核对，请先查询原请求回执。' : '接入请求正在处理，请等待结果后关闭。', blocked: true } : instanceGuard?.blocked ? instanceGuard : credentialGuard?.blocked ? credentialGuard : !view && name.trim() ? { message: '接入配置有尚未保存的内容。' } : instanceGuard ?? credentialGuard)
  const option = page?.types.find(t => t.id === kind)
  async function run(work: (signal: AbortSignal, current: () => boolean) => Promise<void>, catalogRead = false) {
    if (disabled) return; const c = new AbortController(); activeRef.current?.abort(); activeRef.current = c; readingCatalog.current = catalogRead; setBusy(true); setError(''); setNotice(''); const current = () => !disposedRef.current && activeRef.current === c && !c.signal.aborted
    try { await work(c.signal, current) } catch (e) { if (current()) setError(e instanceof Error ? e.message : '数据源请求失败') } finally { if (current()) { readingCatalog.current = false; setBusy(false) } }
  }
  function requireSession() {
    if (ready) return true
    setError(localPreviewMode ? '本地会话尚未就绪，请使用页面上方的重新连接按钮。' : oidcMode ? '请先在页面顶部登录平台，再选择数据源类型。' : '请先在页面顶部填写平台开发 Token，再选择数据源类型。')
    const panel = document.querySelector<HTMLElement>(localPreviewMode ? '[data-local-session]' : '[data-platform-session]')
    panel?.scrollIntoView({ block: 'center' })
    const input = panel?.querySelector<HTMLElement>(localPreviewMode ? 'button' : 'input')
    input?.focus()
    return false
  }
  function open(type: SourceType, trigger: HTMLElement, sourcePage: SourcePage | null = page) {
    if (!ready || sourcePage?.types.find(t => t.id === type)?.status !== 'AVAILABLE') return
    focusRef.current = trigger; setKind(type); setView(null); setPending(null); setProbe(null); setError(''); setNotice('')
    setName(type === 'ZABBIX_HOST' ? 'Zabbix 主机接入' : '手工样本接入'); setDescription('')
    requestIdRef.current = crypto.randomUUID(); dialogRef.current?.showModal()
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
  function load() { if (!requireSession()) return; void run(async (s, current) => { const p = await readSources(s); if (current()) setPage(p) }, true) }
  function inspect(s: Setup) {
    if (s.source.instanceId === 'connection-' + s.id) { setTab('instances'); setInstanceRequest({ nonce: Date.now(), id: s.id }); return }
    focusRef.current = document.activeElement as HTMLElement; setView(s); setKind(s.source.kind); setName(s.name); setDescription(s.description)
    setPending(null); setProbe(null); setError(''); setNotice(''); dialogRef.current?.showModal()
  }
  function close() { if (!busy) dialogRef.current?.close() }
  function remember(setup: Setup) {
    setPage(previous => {
      if (!previous) return previous
      const items = [setup, ...previous.setups.items.filter(item => item.id !== setup.id)].sort((a, b) => Date.parse(b.createdAt) - Date.parse(a.createdAt))
      return { ...previous, setups: { items: items.slice(0, 20), truncated: previous.setups.truncated || items.length > 20 } }
    })
  }
  function go(id: string) { void run(async (s, current) => {
    const r = await readSetup(id, s, pending ?? undefined)
    if (!current()) return
    if (pending) setInstanceRefresh(value => value + 1)
    setPending(null); setView(r.setup); setName(r.setup.name); setDescription(r.setup.description); remember(r.setup)
    const latest = await readSetupContinuation(id, s)
    if (current()) { dialogRef.current?.close(); if (pageActiveRef.current) location.hash = setupWorkflowLink(r.setup,latest) }
  }) }
  function confirm() {
    if (kind === 'CMDB_SNAPSHOT' || view) return
    const connection = option?.connection
    if (!pending && (!connection || !name.trim())) return
    const command = pending ?? { requestId: requestIdRef.current, name: name.trim(), description: description.trim(), source: { kind: kind as 'ZABBIX_HOST' | 'MANUAL_SAMPLE', instanceId: connection!.instanceId }, connectionDigest: connection!.digest }
    setPending(command)
    void run(async (s, current) => { const r = await confirmSource(command, s); if (current()) { setPending(null); setView(r.setup); remember(r.setup); setInstanceRefresh(value => value + 1); dialogRef.current?.close(); if (pageActiveRef.current) location.hash = setupWorkflowLink(r.setup,r.workflow) } })
  }
  function test() {
    setProbe(null)
    void run(async (s, current) => {
      const r = await runConnectionCheck(s)
      if (r.sourceInstanceId !== option?.connection?.instanceId) throw new Error('连接配置已变化，请重新读取数据源')
      if (current()) setProbe(r.check)
    })
  }

  function tasks(type: SourceType | 'ALL' = 'ALL') {
    setTab('tasks'); setTypeFilter(type); setQuery('')
  }
  useInitialPageRead({ ready, loaded: !!page, pending: busy, blocked: tab !== 'catalog' && tab !== 'tasks', read: load })
  function catalog() { setTab('catalog'); setTypeFilter('ALL'); setQuery('') }
  const matchedTypes = sourceCatalog.filter(t => (typeFilter === 'ALL' || typeFilter === t.id) && (titles[t.id] + ' ' + t.category + ' ' + t.description + ' ' + t.capabilities.join(' ')).toLowerCase().includes(query.trim().toLowerCase()))
  const matchedTasks = (page?.setups.items ?? []).filter(s => (typeFilter === 'ALL' || s.source.kind === typeFilter) && (s.name + ' ' + titles[s.source.kind] + ' ' + s.source.instanceId).toLowerCase().includes(query.trim().toLowerCase()))
  return <section className="source-center integration-center" data-page="source-center">
    <header className="source-heading"><div><h2>数据源中心</h2><p>选择来源，查看配置版本并继续编排处理流程</p></div><div className="source-heading-actions">{tab === 'catalog' || tab === 'tasks' ? <Button variant="outline" aria-label="读取数据源" disabled={busy || !ready} onClick={load}>{busy ? '正在读取…' : error && !page ? '重试读取' : '刷新列表'}</Button> : null}{tab !== 'catalog' ? <Button onClick={catalog}>选择接入类型</Button> : null}</div></header>
    {!ready ? <p className="source-access-hint" role="status">{localPreviewMode ? '本地会话尚未就绪，请使用页面上方的重新连接按钮。' : oidcMode ? '尚未登录平台。请先在页面顶部登录，再创建接入配置。' : '尚未建立开发会话。请在页面顶部填写平台开发 Token。'}</p> : null}
    <IntegrationViewTabs label="数据源视图" value={tab} items={[{ id: 'instances', label: '已配置接入' + (instancePage ? '（' + instancePage.items.length + (instancePage.truncated ? '+' : '') + '）' : '') }, { id: 'catalog', label: '接入类型' }, { id: 'credentials', label: '凭据管理' }, { id: 'tasks', label: '接入回执' }]} change={value => { setTypeFilter('ALL'); setQuery(''); setTab(value as typeof tab) }}/>
    <div className="integration-surface">
      <div hidden={tab === 'instances' || tab === 'credentials'} inert={tab === 'instances' || tab === 'credentials'}>
      <IntegrationSearchField label={tab === 'catalog' ? '搜索接入类型' : '搜索接入任务'} placeholder={tab === 'catalog' ? '搜索接入类型，例如 Zabbix' : '搜索接入名称或来源实例'} value={query} onChange={setQuery} clearLabel="清除接入搜索"/>
      <div className="integration-category-filters" aria-label="接入分类"><button data-slot="button" aria-pressed={typeFilter === 'ALL'} onClick={() => setTypeFilter('ALL')}>全部</button>{sourceCatalog.map(t => <button data-slot="button" key={t.id} aria-pressed={typeFilter === t.id} onClick={() => setTypeFilter(t.id)}>{t.category}</button>)}</div>
      {tab === 'catalog' ? <SourceCatalog items={matchedTypes.map(t => {
        const connection = page?.types.find(option => option.id === t.id)
        return { ...t,
          savedCount: page?.setups.items.filter(s => s.source.kind === t.id).length,
          truncated: page?.setups.truncated ?? false,
          createDisabled: busy || !!page && connection?.status !== 'AVAILABLE',
          note: t.id === 'ZABBIX_HOST' ? connection?.connection?.dataMode === 'fixture' ? 'Fixture · 合成来源' : connection?.status === 'AVAILABLE' ? '已有连接 · 可显式测试' : page ? '当前身份无可用连接' : '选择后读取连接' : t.id === 'MANUAL_SAMPLE' ? '手工 JSON · 1–5 条记录' : '已有快照导入入口',
        }
      })} tasksDisabled={busy} onCreate={choose} onTasks={tasks}/>
        : <SourceTaskList items={matchedTasks} loaded={!!page} busy={busy} failed={!!error} filtered={!!query.trim() || typeFilter !== 'ALL'} truncated={page?.setups.truncated ?? false} disabled={disabled} onInspect={inspect} onContinue={go} onCreate={catalog}/>}
      {tab === 'catalog' && !matchedTypes.length ? <div className="integration-list-empty"><strong>没有匹配的接入类型</strong><p>调整搜索或分类筛选。</p></div> : null}
      </div>
      <SourceInstancePanel requested={instanceRequest} active={pageActive && tab === 'instances'} sources={page} guard={setInstanceGuard} report={setInstancePage} refresh={instanceRefresh} kind={typeFilter} kindChange={setTypeFilter}/>
      <SourceCredentialPanel active={pageActive && tab === 'credentials'} guard={setCredentialGuard}/>
    </div>
    <p role="alert">{error}</p><p role="status">{busy ? '正在处理…' : notice}</p>
    <SourceSetupDrawer ref={dialogRef} active={pageActive} kind={kind} connection={option?.connection} models={page?.models ?? []} view={view} probe={probe} pending={pending} busy={busy} disabled={disabled} name={name} description={description} changeName={setName} changeDescription={setDescription} error={error} notice={notice} close={close} closed={() => focusRef.current?.focus()} test={test} confirm={confirm} go={go} metricsContent={enabled => <SourceMetricCatalog enabled={enabled} />} />
  </section>
}
