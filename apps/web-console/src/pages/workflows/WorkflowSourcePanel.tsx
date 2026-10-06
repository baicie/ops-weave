import { useEffect, useRef, useState } from 'react'
import { WorkflowLogSourcePicker } from './WorkflowLogSourcePicker.tsx'
import { WorkflowMetricSourcePicker } from './WorkflowMetricSourcePicker.tsx'
import type { MappingDefinition } from '../../api/metric-mappings.ts'
import { WorkflowSourceConfig } from '../../components/workflows/WorkflowSourceConfig.tsx'
import { readInstances, type InstancePage } from '../../api/source-instances.ts'
import { readConnectionHistory, type ConnectionConfiguration } from '../../api/source-connections.ts'
import type { WorkflowSource } from '../../api/workflows.ts'
import { usePlatformSession } from '../../state/platform-session.ts'
import { usePageActive } from '../../state/page-workspace.ts'

export function WorkflowSourcePanel({ visible, source, locked, legacyId, entityOutput, logOutput, metricMapping, onChange }: { visible: boolean; source: WorkflowSource; locked: boolean; legacyId: string; entityOutput: boolean; logOutput?:boolean; metricMapping?:MappingDefinition; onChange: (source: WorkflowSource) => void }) {
  const [open, setOpen] = useState(false), [page, setPage] = useState<InstancePage | null>(null), [selectedId, setSelectedId] = useState(''), [revision, setRevision] = useState(''), [rows, setRows] = useState<ConnectionConfiguration[] | null>(null), [loading, setLoading] = useState(false), [error, setError] = useState('')
  const controller = useRef<AbortController | null>(null), attempted = useRef(false), cache = useRef(new Map<string, ConnectionConfiguration[]>())
  const active = usePageActive()
  const ready = usePlatformSession(change => { controller.current?.abort(); controller.current = null; attempted.current = false; cache.current.clear(); setPage(null); setOpen(false); setRows(null); setSelectedId(''); setRevision(''); setLoading(false); setError(change.error?.message ?? '') })
  useEffect(() => () => { controller.current?.abort() }, [])
  useEffect(() => { if (!active || !visible || !ready) { if (controller.current && !controller.current.signal.aborted && loading) setError('连接读取已取消，请重新读取。'); controller.current?.abort(); controller.current = null; setLoading(false) } }, [active, visible, ready, loading])
  async function load(id: string, refresh = false) {
    if (!ready || !active || !visible || locked) return
    const request = new AbortController(); controller.current?.abort(); controller.current = request; setLoading(true); setError('')
    const current = () => controller.current === request && !request.signal.aborted
    try {
      if (!page || refresh) { const p = await readInstances(request.signal); if (!current()) return; setPage(p) }
      if (id) { const c = !refresh && cache.current.get(id) || await readConnectionHistory(id, request.signal); if (!current()) return; cache.current.set(id, c); setRows(c) }
    } catch (e) { if (current()) setError(e instanceof Error ? e.message : '连接版本读取失败') }
    finally { if (current()) setLoading(false) }
  }
  function select(id: string) { setSelectedId(id); setRevision(''); setRows(null); setError(''); controller.current?.abort(); controller.current = null; if (id) void load(id); else setLoading(false) }
  function show() { setOpen(true); if (!attempted.current) { attempted.current = true; void load('') } }
  function bind() { const i = page?.items.find(i => i.id === selectedId), c = rows?.find(c => String(c.revision) === revision); if (!i || i.state !== 'ACTIVE' || !c || locked || loading) return; onChange({ kind: 'ZABBIX_HOST', instanceId: i.source.instanceId, configuration: { sourceId: i.id, revision: c.revision, digest: c.connectionDigest } }); setOpen(false) }
  return <div hidden={!visible}><WorkflowSourceConfig source={source} legacyId={legacyId} locked={locked} entityOutput={entityOutput} metricOutput={!!metricMapping} logOutput={logOutput} pickerOpen={open} page={page} selectedId={selectedId} revision={revision} rows={rows} loading={loading} error={error} onLegacy={kind => { setOpen(false); onChange({ kind, instanceId: kind === 'MANUAL_SAMPLE' ? 'manual' : legacyId }) }} onOpen={show} onSelect={select} onRevision={setRevision} onBind={bind} onRetry={() => { void load(selectedId, true) }} />{open&&metricMapping&&selectedId&&revision&&rows?.some(c=>String(c.revision)===revision)?<WorkflowMetricSourcePicker visible={visible&&active&&ready&&!locked} configuration={rows.find(c=>String(c.revision)===revision)!} sourceInstanceId={page?.items.find(i=>i.id===selectedId)?.source.instanceId??''} mapping={metricMapping} onBind={s=>{onChange(s);setOpen(false)}}/>:null}{open&&logOutput&&selectedId&&revision&&rows?.some(c=>String(c.revision)===revision)?<WorkflowLogSourcePicker visible={visible&&active&&ready&&!locked} configuration={rows.find(c=>String(c.revision)===revision)!} sourceInstanceId={page?.items.find(i=>i.id===selectedId)?.source.instanceId??''} onBind={s=>{onChange(s);setOpen(false)}}/>:null}</div>
}
