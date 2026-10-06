import { useEffect, useRef, useState } from 'react'
import { ArrowRight, ArrowUpRight, Database, Layers3, RefreshCw, ShieldCheck } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader } from '@/components/ui/card'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { PlatformSessionBar } from '../../app/PlatformSessionBar.tsx'
import { RouteIcon } from '../../app/navigation.tsx'
import { pageEntities, type EntityPage } from '../../api/entities.ts'
import { listIncidents, type IncidentPage } from '../../api/incidents.ts'
import { usePlatformSession } from '../../state/platform-session.ts'
import { incidentHash, incidentDefault } from '../../state/view-selection.ts'
import { pathFor, ROUTES, type RouteName } from '../../state/routes.ts'

const shortcuts: { route: RouteName; description: string }[] = [
  { route: 'inventory', description: '检索资源与来源信息' },
  { route: 'metrics', description: '检查曲线与数据缺口' },
  { route: 'incidents', description: '查看告警与事件时间线' },
  { route: 'current-diagnose', description: '运行只读分析，核对证据' },
  { route: 'workflows', description: '映射字段与验证转换' },
  { route: 'source-scan-runs', description: '追踪采集批次与失败原因' },
]
const journeys: { title: string; description: string; route: RouteName; action: string }[] = [
  { title: '接入数据源', description: '选择 Zabbix 或手工 JSON，保存来源与连接配置。', route: 'source-center', action: '配置数据源' },
  { title: '编排与验证', description: '选择实体、指标或日志输出，映射字段并逐步验证样本。', route: 'workflows', action: '打开工作流' },
  { title: '检查处理结果', description: '查询成功、失败与过滤记录，定位节点和字段。', route: 'workflow-runs', action: '查看运行记录' },
]
const lifecycleLabels = { ACTIVE: '活跃', DISCOVERED: '已发现', INACTIVE: '未活跃', ARCHIVED: '已归档', DELETED: '已删除' }
const statusLabels = { OPEN: '待处理', INVESTIGATING: '调查中', MITIGATED: '已缓解', RESOLVED: '已解决', CLOSED: '已关闭' }

export function StartPage() {
  const [entities, setEntities] = useState<EntityPage | null>(null)
  const [incidents, setIncidents] = useState<IncidentPage | null>(null)
  const [errors, setErrors] = useState({ assets: '', incidents: '' })
  const [busy, setBusy] = useState(false)
  const [sessionError, setSessionError] = useState('')
  const [updatedAt, setUpdatedAt] = useState('')
  const controllerRef = useRef<AbortController | null>(null)
  function clear() {
    controllerRef.current?.abort(); controllerRef.current = null
    setEntities(null); setIncidents(null); setErrors({ assets: '', incidents: '' }); setUpdatedAt(''); setBusy(false)
  }
  const ready = usePlatformSession(change => { clear(); setSessionError(change.error?.message ?? '') })
  useEffect(() => () => { controllerRef.current?.abort(); controllerRef.current = null }, [])

  async function load() {
    controllerRef.current?.abort()
    const active = new AbortController()
    controllerRef.current = active
    setBusy(true); setEntities(null); setIncidents(null); setUpdatedAt(''); setSessionError(''); setErrors({ assets: '', incidents: '' })
    const timeout = window.setTimeout(() => active.abort(), 15_000)
    try {
      const [assetResult, incidentResult] = await Promise.allSettled([
        pageEntities({ q: '', type: '', lifecycle: '' }, null, active.signal),
        listIncidents('', null, active.signal),
      ])
      if (controllerRef.current !== active) return
      const message = (result: PromiseSettledResult<unknown>) => result.status === 'rejected'
        ? active.signal.aborted ? '读取已取消或超时，请重新读取。' : result.reason instanceof Error ? result.reason.message : '读取失败，请重试。' : ''
      if (assetResult.status === 'fulfilled') setEntities(assetResult.value)
      if (incidentResult.status === 'fulfilled') setIncidents(incidentResult.value)
      setErrors({ assets: message(assetResult), incidents: message(incidentResult) })
      if (assetResult.status === 'fulfilled' || incidentResult.status === 'fulfilled') setUpdatedAt(new Date().toLocaleTimeString())
    } finally {
      window.clearTimeout(timeout)
      if (controllerRef.current === active) setBusy(false)
    }
  }
  const activeEntities = entities?.items.filter(item => item.lifecycle === 'ACTIVE').length
  const openIncidents = incidents?.items.filter(item => ['OPEN', 'INVESTIGATING'].includes(item.status)).length
  return <section className="dashboard" data-page="start">
    <header className="dashboard-heading">
      <div><h1>运维工作台</h1><p>资源、事件与数据接入，集中在一个工作空间。</p></div>
      <div className="dashboard-heading-actions"><Button variant="outline" disabled={!ready || busy} onClick={() => { void load() }}><RefreshCw className={busy ? 'is-spinning' : undefined} />{busy ? '正在读取…' : '读取概览'}</Button><Button asChild><a href={pathFor('source-center')}><Database />接入数据源</a></Button></div>
    </header>
    <PlatformSessionBar />
    <Tabs defaultValue="overview">
      <div className="dashboard-tab-row"><TabsList aria-label="工作台视图"><TabsTrigger value="overview">运维概览</TabsTrigger><TabsTrigger value="onboarding">接入指南</TabsTrigger></TabsList><span className="dashboard-updated" role="status">{busy ? '正在读取当前授权范围…' : updatedAt ? '读取于 ' + updatedAt : '概览尚未读取'}</span></div>
      {sessionError ? <p className="dashboard-error" role="alert">{sessionError}</p> : null}
      <TabsContent value="overview">
        <div className="dashboard-stats">
          <Summary title="资产记录" value={entities?.items.length} route="inventory" note="当前查询页 · 最多 25 条" />
          <Summary title="活跃资产" value={activeEntities} route="metrics" note="当前页生命周期为 ACTIVE" />
          <Summary title="故障事件" value={incidents?.items.length} route="incidents" note="当前查询页 · 最多 25 条" />
          <Summary title="待处理 / 调查中" value={openIncidents} route="current-diagnose" note="当前页 OPEN / INVESTIGATING" />
        </div>
        <div className="dashboard-grid">
          <Card className="dashboard-incidents"><CardHeader><div><h2>故障事件</h2><p>当前查询页的事件，按创建时间展示前 5 条。</p></div><a href={pathFor('incidents')} className="card-more">查看全部 <ArrowUpRight /></a></CardHeader>
            <CardContent>{errors.incidents ? <div className="dashboard-error" role="alert">{errors.incidents}</div> : incidents?.items.length ? <div className="dashboard-table-wrap"><table><thead><tr><th>事件</th><th>状态</th><th>级别</th><th>创建时间</th></tr></thead><tbody>{[...incidents.items].sort((a, b) => b.createdAt.localeCompare(a.createdAt)).slice(0, 5).map(item => <tr key={item.id}><td><a href={incidentHash({ ...incidentDefault(), incidentId: item.id })}>{item.title}</a><small>{item.id.slice(0, 8)}</small></td><td><span className="event-status" data-status={item.status}>{statusLabels[item.status]}</span></td><td><span className="severity-label">S{item.severity}</span></td><td><time dateTime={item.createdAt}>{new Date(item.createdAt).toLocaleString()}</time></td></tr>)}</tbody></table></div> : <DashboardEmpty route="incidents" title={incidents ? '当前查询页没有故障事件' : '尚未读取故障事件'} description={incidents ? '可进入故障事件页面调整筛选范围。' : '建立平台会话后，点击「读取概览」。'} />}
            </CardContent><div className="dashboard-card-foot">{incidents ? (incidents.storage === 'memory' ? '开发内存' : 'PostgreSQL') + ' · ' + (incidents.nextCursor ? '还有后续页，当前统计不完整' : '当前查询页已读完') : '数据由平台 API 按权限返回'}</div>
          </Card>
          <Card><CardHeader><div><h2>资产生命周期</h2><p>已读取资产的状态分布。</p></div><Layers3 /></CardHeader><CardContent>{errors.assets ? <div className="dashboard-error" role="alert">{errors.assets}</div> : entities?.items.length ? <div className="lifecycle-distribution">{Object.entries(lifecycleLabels).map(([status, label]) => {
            const count = entities.items.filter(item => item.lifecycle === status).length
            return <div key={status}><div><span><i data-lifecycle={status} />{label}</span><strong>{count}<small> / {entities.items.length}</small></strong></div><div className="distribution-track" aria-hidden="true"><span data-lifecycle={status} style={{ width: String(count / entities.items.length * 100) + '%' }} /></div></div>
          })}</div> : <DashboardEmpty route="inventory" title={entities ? '当前查询页没有资产' : '尚未读取资产'} description="读取后展示实际状态分布。" />}</CardContent><div className="dashboard-card-foot">{entities ? (entities.storage === 'memory' ? '开发内存' : 'PostgreSQL') + ' · ' + (entities.nextCursor ? '还有后续页' : '当前查询页已读完') + (entities.items.some(item => item.attributes.dataMode === 'labeled-fixture') ? ' · 含 Fixture 资产' : '') : '未读取时不推断资源状态'}</div></Card>
        </div>
        <Card className="quick-access"><CardHeader><div><h2>常用操作</h2><p>从资源观测到事件调查，快速进入工作页面。</p></div><span className="quiet-caption">工作空间</span></CardHeader><CardContent><div className="shortcut-grid">{shortcuts.map(item => <a key={item.route} href={pathFor(item.route)}><span className="shortcut-icon"><RouteIcon name={item.route} /></span><span><strong>{ROUTES.find(route => route.name === item.route)?.label}</strong><small>{item.description}</small></span><ArrowUpRight /></a>)}</div></CardContent></Card>
        <div className="dashboard-boundary"><ShieldCheck /><span>平台诊断执行只读分析，证据引用需要核对来源与有效期。</span><a href={pathFor('current-diagnose')}>进入诊断 <ArrowRight /></a></div>
      </TabsContent>
      <TabsContent value="onboarding"><Card className="onboarding-card"><CardHeader><div><h2>建立你的数据链路</h2><p>配置接入、验证转换，再检查处理记录。</p></div><Database /></CardHeader><CardContent><div className="onboarding-steps">{journeys.map((item, index) => <a key={item.route} href={pathFor(item.route)}><span className="onboarding-number">0{index + 1}</span><div><h3>{item.title}</h3><p>{item.description}</p><strong>{item.action} <ArrowRight /></strong></div><RouteIcon name={item.route} /></a>)}</div><div className="onboarding-note"><h3>模型、资产和工作流</h3><p>实体模型定义资产结构；指标和日志使用各自的输出格式。工作流负责转换输入，发布版本后仍需另行启用采集与存储。</p><a href={pathFor('model-entities')}>管理实体模型 <ArrowUpRight /></a></div></CardContent></Card></TabsContent>
    </Tabs>
  </section>
}

function Summary(props: { title: string; value: number | undefined; route: RouteName; note: string }) {
  return <Card className="dashboard-stat"><CardContent><div className="dashboard-stat-label"><span>{props.title}</span><RouteIcon name={props.route} /></div><strong data-overview-stat={props.route}>{props.value ?? '—'}</strong><p>{props.note}</p></CardContent></Card>
}
function DashboardEmpty(props: { route: RouteName; title: string; description: string }) {
  return <div className="dashboard-empty"><span><RouteIcon name={props.route} /></span><strong>{props.title}</strong><p>{props.description}</p></div>
}
