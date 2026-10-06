import { SourceConnectionPanel } from './SourceConnectionPanel.tsx'
import { useEffect, useRef, useState } from 'react'
import { readConnection, type ConnectionConfiguration } from '../../api/source-connections.ts'
import { Button } from '../../components/ui/button.tsx'
import { IntegrationSearchField } from '../../components/integrations/IntegrationSearchField.tsx'
import { SourceInstanceTable } from '../../components/integrations/SourceInstanceTable.tsx'
import { SourceInstanceDrawer } from '../../components/integrations/SourceInstanceDrawer.tsx'
import { SourceInspectionResults } from '../../components/integrations/SourceInspectionResults.tsx'
import { RegisteredItemSyncPanel } from './RegisteredItemSyncPanel.tsx'
import { InspectionRejectedError, readStoredInspection, sameMetricManifest, readInspection, readInspections, runInspection, type InspectionKind, type InspectionView, type PendingInspection } from '../../api/source-inspections.ts'
import { editInstance, InstanceRejectedError, readConfigurations, readInstance, readInstanceCommand, readInstances, type Configuration, type InstanceEdit, type InstancePage, type SourceInstance } from '../../api/source-instances.ts'
import type { SourcePage, SourceType } from '../../api/source-setups.ts'
import { usePlatformSession } from '../../state/platform-session.ts'
import { useInitialPageRead } from '../../state/initial-page-read.ts'
import type { PageCloseReason } from '../../state/page-workspace.ts'
export function SourceInstancePanel(props: { requested?: { nonce: number; id: string } | null; active: boolean; sources: SourcePage | null; guard: (reason: PageCloseReason) => void; report: (page: InstancePage | null) => void; refresh: number; kind: SourceType | 'ALL'; kindChange: (kind: SourceType | 'ALL') => void }) {
  const [connectionTarget, setConnectionTarget] = useState<{ nonce: number; instance: SourceInstance | null } | null>(null), [connectionGuard, setConnectionGuard] = useState<PageCloseReason>(null)
  const [page, setPage] = useState<InstancePage | null>(null), [selected, setSelected] = useState<SourceInstance | null>(null), [connectionSnapshot, setConnectionSnapshot] = useState<ConnectionConfiguration | null>(null)
  const [name, setName] = useState(''), [description, setDescription] = useState(''), [digest, setDigest] = useState('')
  const [versions, setVersions] = useState<Configuration[] | null>(null), [query, setQuery] = useState(''), [state, setState] = useState<'ALL' | 'ACTIVE' | 'ARCHIVED'>('ACTIVE')
  const [busy, setBusy] = useState(false), [pending, setPending] = useState<InstanceEdit | null>(null), [error, setError] = useState(''), [notice, setNotice] = useState('')
  const [inspections, setInspections] = useState<InspectionView[] | null>(null), [inspectionPending, setInspectionPending] = useState<PendingInspection | null>(null)
  const requestedAttempt = useRef(0)
  const refreshAttempt = useRef(props.refresh)
  const inspectionReadAttempt = useRef(false)
  const dialog = useRef<HTMLDialogElement>(null), controller = useRef<AbortController | null>(null), reading = useRef(true), disposed = useRef(false), trigger = useRef<HTMLElement | null>(null)
  const ready = usePlatformSession(change => { controller.current?.abort(); controller.current = null; inspectionReadAttempt.current = false; setBusy(false); setPending(null); setInspectionPending(null); setInspections(null); setConnectionTarget(null); setConnectionGuard(null); setPage(null); setSelected(null); setConnectionSnapshot(null); setVersions(null); setName(''); setDescription(''); setDigest(''); setQuery(''); setState('ACTIVE'); setError(change.error?.message ?? ''); setNotice(''); dialog.current?.close() })
  const dirty = !!selected && (name !== selected.name || description !== selected.description || digest !== selected.connectionDigest)
  useEffect(() => { props.guard(connectionGuard?.blocked ? connectionGuard : pending || inspectionPending ? { message: '实例请求结果待确认，请先查询原请求回执。', blocked: true } : busy ? { message: '实例请求正在处理。', blocked: true } : dirty ? { message: '接入实例有未保存的修改。' } : connectionGuard) }, [props.guard, pending, inspectionPending, busy, dirty, connectionGuard])
  useEffect(() => { disposed.current = false; return () => { disposed.current = true; controller.current?.abort(); props.guard(null) } }, [props.guard])
  useEffect(() => { if (!props.active && reading.current) { controller.current?.abort(); controller.current = null; setBusy(false) } }, [props.active])
  useEffect(() => { props.report(page) }, [page, props.report])
  async function run(work: (signal: AbortSignal, current: () => boolean) => Promise<void>, read = true) {
    if (!ready || busy) return
    const c = new AbortController(); controller.current?.abort(); controller.current = c; reading.current = read; setBusy(true); setError(''); setNotice('')
    const current = () => !disposed.current && controller.current === c && !c.signal.aborted
    try { await work(c.signal, current) } catch (cause) { if (current()) { setError(cause instanceof Error ? cause.message : '实例请求失败'); if (cause instanceof InstanceRejectedError) setPending(null); if (cause instanceof InspectionRejectedError) setInspectionPending(null) } } finally { if (current()) setBusy(false) }
  }
  function load() { if (pending || inspectionPending) return; void run(async (signal, current) => { const p = await readInstances(signal); if (current()) setPage(p) }) }
  useInitialPageRead({ ready, loaded: !!page, pending: busy, blocked: !props.active, read: load })
  useEffect(() => {
    if (props.refresh === refreshAttempt.current || !props.active || !ready || busy || pending || inspectionPending || connectionGuard?.blocked) return
    refreshAttempt.current = props.refresh
    if (page) load()
  }, [props.refresh, props.active, ready, busy, pending, inspectionPending, connectionGuard, page])
  function populate(i: SourceInstance, connection: ConnectionConfiguration | null = null) { setSelected(i); setConnectionSnapshot(connection); setName(i.name); setDescription(i.description); setDigest(i.connectionDigest); setInspections(previous => previous?.map(v => i.state !== 'ACTIVE' || i.configurationRevision !== v.inspection.configurationRevision || i.connectionDigest !== v.inspection.connectionDigest ? { ...v, validity: 'STALE' } : v) ?? null) }
  function remember(i: SourceInstance) { setPage(previous => { if (!previous || previous.items.some(row => row.id === i.id && row.editVersion > i.editVersion)) return previous; const items = [i, ...previous.items.filter(row => row.id !== i.id)].sort((a, b) => Date.parse(b.updatedAt) - Date.parse(a.updatedAt)); return { ...previous, items: items.slice(0, 20), truncated: previous.truncated || items.length > 20 } }) }
  function open(id: string) {
    if (busy || pending || inspectionPending) return
    trigger.current = document.activeElement as HTMLElement
    if (selected?.id === id) { dialog.current?.showModal(); return }
    if (dirty) { setError('当前实例有未保存的修改，请先保存或放弃修改。'); dialog.current?.showModal(); return }
    void run(async (signal, current) => { const i = await readInstance(id, signal); const c = i.source.instanceId === 'connection-' + i.id ? (await readConnection(i.id, signal)).connection : null; if (!current()) return; populate(i, c); setVersions(null); setInspections(null); inspectionReadAttempt.current = false; dialog.current?.showModal() })
  }
  useEffect(() => { if (!props.requested || requestedAttempt.current === props.requested.nonce || !props.active || !ready || busy || pending || inspectionPending || connectionGuard) return; requestedAttempt.current = props.requested.nonce; open(props.requested.id) }, [props.requested, props.active, ready, busy, pending, inspectionPending, connectionGuard])
  function reset() { if (!selected || pending || inspectionPending) return; void run(async (signal, current) => { const i = await readInstance(selected.id, signal); const c = i.source.instanceId === 'connection-' + i.id ? (await readConnection(i.id, signal)).connection : null; if (!current()) return; populate(i, c); remember(i); setVersions(null); setNotice('已读取最新实例') }) }
  function confirmResult(i: SourceInstance) { if (selected && (i.source.kind !== selected.source.kind || i.source.instanceId !== selected.source.instanceId || i.createdAt !== selected.createdAt || i.configurationRevision !== selected.configurationRevision + (i.connectionDigest === selected.connectionDigest ? 0 : 1))) throw new Error('实例来源或配置版本不匹配，原请求仍需核对'); if (selected?.configurationRevision !== i.configurationRevision) setVersions(null); populate(i); remember(i); setPending(null); setNotice('实例维护已确认') }
  function save(nextState: SourceInstance['state']) {
    if (!selected || pending || inspectionPending || busy) return
    const command: InstanceEdit = { requestId: crypto.randomUUID(), expectedEditVersion: selected.editVersion, name: name.trim(), description, connectionDigest: digest, state: nextState }
    setPending(command)
    void run(async (signal, current) => { const i = await editInstance(selected.id, command, signal); if (current()) confirmResult(i) }, false)
  }
  function lookup() { if (!selected || !pending) return; void run(async (signal, current) => { const i = await readInstanceCommand(selected.id, pending, signal); if (current()) confirmResult(i) }, false) }
  function loadVersions() { if (!selected || pending || inspectionPending) return; void run(async (signal, current) => { const v = await readConfigurations(selected.id, signal); if (current()) setVersions(v) }) }
  function acceptInspection(v: InspectionView) { setInspections(previous => [v, ...(previous ?? []).filter(r => r.inspection.requestId !== v.inspection.requestId)].slice(0, 200)); if (v.inspection.state !== 'PENDING') setInspectionPending(null); setNotice(v.inspection.state === 'COMPLETED' ? '本次结果已确认' : v.inspection.state === 'UNKNOWN' ? '原请求结果未知；未重复执行' : '请求仍在处理，请按原请求查询') }
  function inspect(kind: InspectionKind, previous?: InspectionView) { if (!selected || pending || inspectionPending || busy || dirty || selected.state !== 'ACTIVE' || selected.dataMode === 'MANUAL_SAMPLE') return; if (previous && (previous.validity !== 'CURRENT' || previous.inspection.kind !== 'DISCOVER_METRIC_PAGE' || previous.inspection.sourceId !== selected.id || previous.inspection.configurationRevision !== selected.configurationRevision || previous.inspection.connectionDigest !== selected.connectionDigest || previous.inspection.metricPage?.nextOffset == null || Date.now() >= Date.parse(previous.inspection.metricPage.manifest?.expiresAt ?? ''))) return; const c: PendingInspection = { kind, dataMode: selected.dataMode, command: { requestId: crypto.randomUUID(), configurationRevision: selected.configurationRevision, connectionDigest: selected.connectionDigest, ...(kind === 'DISCOVER_METRIC_PAGE' ? { previousRequestId: previous?.inspection.requestId ?? null } : {}) }, ...(previous ? { previous: previous.inspection } : {}) }; setInspectionPending(c); void run(async (signal, current) => { const v = await runInspection(selected.id, c, signal); if (current()) acceptInspection(v) }, false) }
  function previousMetricPage(child: InspectionView) {
    const parentId = child.inspection.previousRequestId; if (!selected || !parentId || pending || inspectionPending || busy) return
    void run(async (signal, current) => {
      const v = await readStoredInspection(selected.id, parentId, signal), p = v.inspection, c = child.inspection
      if (p.kind !== 'DISCOVER_METRIC_PAGE' || p.state !== 'COMPLETED' || p.configurationRevision !== c.configurationRevision || p.connectionDigest !== c.connectionDigest || p.dataMode !== c.dataMode || !sameMetricManifest(p.metricPage?.manifest, c.metricPage?.manifest) || p.metricPage?.nextOffset !== c.metricPage?.offset || Date.parse(p.availableAt ?? '') > Date.parse(c.asOf)) throw new Error('上一页回执与指标清单不一致')
      if (current()) acceptInspection(v)
    })
  }
  function nextMetricPage(parent: InspectionView) {
    if (busy || pending || inspectionPending) return
    const cached = inspections?.find(v => v.inspection.previousRequestId === parent.inspection.requestId && sameMetricManifest(v.inspection.metricPage?.manifest, parent.inspection.metricPage?.manifest))
    if (cached) { acceptInspection(cached); return }
    inspect('DISCOVER_METRIC_PAGE', parent)
  }
  function lookupInspection() { if (!selected || !inspectionPending) return; void run(async (signal, current) => { const v = await readInspection(selected.id, inspectionPending, signal); if (current()) acceptInspection(v) }, false) }
  function loadInspections() { if (!selected || pending || inspectionPending) return; void run(async (signal, current) => { const v = await readInspections(selected.id, signal); if (current()) setInspections(v) }) }
  function ensureInspections() { if (!selected || !dialog.current?.open || !ready || busy || pending || inspectionPending || inspectionReadAttempt.current) return; inspectionReadAttempt.current = true; loadInspections() }
  const catalogConnection = props.sources?.types.find(t => t.id === selected?.source.kind)?.connection ?? null
  const allowedConnection = catalogConnection?.instanceId === selected?.source.instanceId ? catalogConnection : null
  const displayConnection = connectionSnapshot ?? allowedConnection
  const registeredConnection = displayConnection && 'sourceId' in displayConnection ? displayConnection : null
  const itemSync = selected?.source.kind === 'ZABBIX_HOST' && registeredConnection
    ? (active: boolean) => <RegisteredItemSyncPanel active={active} instance={selected} connection={registeredConnection} ready={ready && !busy && !pending && !inspectionPending && !connectionGuard?.blocked}/>
    : undefined
  const rows = page?.items.filter(i => (props.kind === 'ALL' || props.kind === i.source.kind) && (state === 'ALL' || i.state === state) && (i.name + ' ' + i.description + ' ' + i.source.instanceId).toLowerCase().includes(query.trim().toLowerCase())) ?? []
  return <section className="source-instance-panel" aria-label="实例管理" hidden={!props.active} inert={!props.active}><div className="source-instance-toolbar"><IntegrationSearchField label="搜索接入实例" placeholder="搜索实例名称或来源" value={query} onChange={setQuery} clearLabel="清除实例搜索"/><select aria-label="按接入类型筛选实例" value={props.kind} onChange={e => props.kindChange(e.target.value as SourceType | 'ALL')}><option value="ALL">全部类型</option><option value="ZABBIX_HOST">Zabbix 主机</option><option value="MANUAL_SAMPLE">手工样本</option><option value="CMDB_SNAPSHOT">资产快照</option></select><Button type="button" variant="outline" disabled={!ready || busy || !!pending || !!inspectionPending || !!connectionGuard?.blocked} onClick={load}>刷新实例列表</Button><Button type="button" disabled={!ready || busy || !!pending || !!inspectionPending || !!connectionGuard?.blocked} onClick={() => setConnectionTarget({ nonce: Date.now(), instance: null })}>{connectionGuard ? '继续配置连接' : '新增连接'}</Button></div><div className="integration-category-filters" aria-label="实例状态">{(['ACTIVE', 'ARCHIVED', 'ALL'] as const).map(value => <button type="button" data-slot="button" key={value} aria-pressed={state === value} onClick={() => setState(value)}>{value === 'ACTIVE' ? '可维护' : value === 'ARCHIVED' ? '已归档' : '全部'}</button>)}</div>
    {pending || inspectionPending ? <Button type="button" variant="outline" disabled={!ready} onClick={() => dialog.current?.showModal()}>继续核对实例请求</Button> : null}
    <SourceInstanceTable items={rows} loaded={!!page} busy={busy} disabled={!ready || busy || !!pending || !!inspectionPending || !!connectionGuard?.blocked} filtered={!!query || state !== 'ALL' || props.kind !== 'ALL'} failed={!!error} truncated={page?.truncated ?? false} edit={open}/>{error && !dialog.current?.open ? <p role="alert">{error}</p> : null}
    <SourceInstanceDrawer connectionEdit={() => { if (!selected || dirty || pending || inspectionPending || busy) return; dialog.current?.close(); setConnectionTarget({ nonce: Date.now(), instance: selected }) }} dialog={dialog} active={props.active} instance={selected} connection={displayConnection} itemSync={itemSync} name={name} description={description} digest={digest} versions={versions} busy={busy} pending={pending} inspectionPending={!!inspectionPending} inspectLookup={lookupInspection} inspectReadOnce={ensureInspections} inspections={<SourceInspectionResults items={inspections} busy={busy} pending={!!pending || !!inspectionPending} canRun={ready && !dirty && selected?.state === 'ACTIVE'} ready={ready} run={inspect} previous={previousMetricPage} next={nextMetricPage} read={loadInspections}/>} ready={ready} error={error} notice={notice} dirty={dirty} nameChange={setName} descriptionChange={setDescription} adopt={() => { if (allowedConnection && !inspectionPending) setDigest(allowedConnection.digest) }} save={save} lookup={lookup} reset={reset} versionsRead={loadVersions} closed={() => trigger.current?.focus()}/>
    <SourceConnectionPanel active={props.active} target={connectionTarget} guard={setConnectionGuard} saved={i => { populate(i); remember(i); setVersions(null) }}/>
  </section>
}
