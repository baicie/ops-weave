import { useRef, useState } from 'react'
import { Plus, ArrowLeft, GitBranch, Database, FileJson, FileText, Activity } from 'lucide-react'
import { Button } from '@/components/ui/button'
import type { Entry, Run, Workspace } from '../../api/workflows.ts'
import { outputKind } from '../../api/workflow-output.ts'
import { IntegrationSearchField } from '../integrations/IntegrationSearchField.tsx'

const kinds = { ENTITY: '实体', METRIC: '指标', LOG: '日志' }
const origins = { MANUAL_SAMPLE: '手工样本', fixture: 'Fixture 合成数据', 'zabbix-jsonrpc': 'Zabbix 采集' }
const date = (value: string) => new Date(value).toLocaleString()
export function WorkflowLibrary(p: {
 workspace: Workspace; taskId: string | null; selectTask: (id: string | null) => void; disabled: boolean;
 open: (entry: Entry) => void; compare: (entry: Entry) => void; create: (kind: 'ENTITY' | 'ZABBIX_HOST' | 'LOG' | 'METRIC') => void
}) {
 const [search, setSearch] = useState('')
 const [filter, setFilter] = useState('ALL')
 const dialog = useRef<HTMLDialogElement>(null)
 const entries = [...p.workspace.drafts.items, ...p.workspace.published.items].sort((a,b) => b.definition.revision-a.definition.revision || b.updatedAt.localeCompare(a.updatedAt))
 const versions = entries.filter(e => e.definition.id === p.taskId)
 const latest = versions[0]
 const tasks = [...new Map(entries.map(e => [e.definition.id, e])).keys()].map(id => entries.find(e => e.definition.id === id)!)
 const shown = tasks.filter(e => (filter === 'ALL' || outputKind(e.definition.target) === filter) && (e.definition.name + ' ' + e.definition.source.instanceId).toLowerCase().includes(search.trim().toLowerCase()))
 const runs = p.workspace.runs.items.filter(r => !p.taskId || r.workflowId === p.taskId).slice(0,5)
 const truncated = p.workspace.drafts.truncated || p.workspace.published.truncated
 function create(kind: 'ENTITY' | 'ZABBIX_HOST' | 'LOG' | 'METRIC') { if (!p.workspace.operatorCatalog) return; dialog.current?.close(); p.create(kind) }
 return <section className="integration-workflow-list" aria-label={p.taskId ? '工作流版本' : '处理流程列表'}>
  {p.taskId ? <><button data-slot="button" className="integration-back" disabled={p.disabled} onClick={() => p.selectTask(null)}><ArrowLeft size={14}/>全部处理流程</button>
   <header className="integration-task-summary"><span className="integration-task-icon"><GitBranch size={24}/></span><div><h3>{latest?.definition.name ?? '处理流程'}</h3><p>{latest ? (latest.definition.source.kind === 'ZABBIX_HOST' ? 'Zabbix 主机' : latest.definition.source.kind==='ZABBIX_LOG' ? 'Zabbix 日志' : latest.definition.source.kind==='ZABBIX_METRIC' ? 'Zabbix 指标' : 'JSON 样本') + ' · ' + kinds[outputKind(latest.definition.target)] : '最近列表中没有此流程的版本，请刷新列表或从已有版本链接打开。'}</p></div><a href="#/integrations/workflows/runs">运行记录</a></header>
   <div className="integration-section-heading"><h3>数据流版本</h3><span>草稿可编辑，已发布版本只读</span></div>
   <div className="integration-table-wrap"><table className="integration-table"><thead><tr><th>版本</th><th>状态</th><th>处理步骤</th><th>更新时间</th><th>操作</th></tr></thead><tbody>
    {versions.map(e => <tr key={e.state + e.definition.revision}><td><strong>v{e.definition.revision}</strong></td><td><span className="integration-status" data-state={e.state}>{e.state === 'DRAFT' ? '草稿' : '已发布'}</span></td><td>{e.definition.nodes.length} 步</td><td>{date(e.updatedAt)}</td><td><button data-slot="button" disabled={p.disabled} onClick={() => p.open(e)}>{e.state === 'DRAFT' ? '编辑流程' : '查看流程'}</button><button data-slot="button" disabled={p.disabled} onClick={() => p.compare(e)}>比较版本</button></td></tr>)}
   </tbody></table>{!versions.length ? <div className="integration-list-empty">当前列表未找到版本。</div> : null}</div>
  </> : <><div className="integration-list-toolbar"><Button disabled={p.disabled || !p.workspace.operatorCatalog} onClick={() => dialog.current?.showModal()}><Plus size={15}/>新建工作流</Button><IntegrationSearchField label="搜索处理流程" placeholder="搜索流程名称或来源" value={search} onChange={setSearch}/><select aria-label="按输出类型筛选流程" value={filter} onChange={e => setFilter(e.target.value)}><option value="ALL">全部输出</option><option value="ENTITY">实体</option><option value="METRIC">指标</option><option value="LOG">日志</option></select></div>
   <div className="integration-table-wrap"><table className="integration-table"><thead><tr><th>流程名称</th><th>来源</th><th>输出类型</th><th>当前版本</th><th>更新时间</th><th>操作</th></tr></thead><tbody>
    {shown.map(e => <tr key={e.definition.id}><td><button data-slot="button" className="integration-name-link" disabled={p.disabled} onClick={() => p.selectTask(e.definition.id)}>{e.definition.name}</button></td><td>{e.definition.source.kind === 'ZABBIX_HOST' ? 'Zabbix 主机' : e.definition.source.kind==='ZABBIX_LOG' ? 'Zabbix 日志' : e.definition.source.kind==='ZABBIX_METRIC' ? 'Zabbix 指标' : 'JSON 样本'}</td><td>{kinds[outputKind(e.definition.target)]}</td><td><span className="integration-status" data-state={e.state}>{'v' + e.definition.revision + ' · ' + (e.state === 'DRAFT' ? '草稿' : '已发布')}</span></td><td>{date(e.updatedAt)}</td><td><button data-slot="button" disabled={p.disabled} onClick={() => p.selectTask(e.definition.id)}>查看版本</button><button data-slot="button" disabled={p.disabled} onClick={() => p.open(e)}>{e.state === 'DRAFT' ? '编辑' : '查看'}</button></td></tr>)}
   </tbody></table>{!shown.length ? <div className="integration-list-empty"><GitBranch size={26}/><strong>{tasks.length ? '没有匹配的流程' : '还没有处理流程'}</strong><p>{tasks.length ? '调整搜索或输出类型筛选。' : '点击新建工作流，或先在数据源中心配置来源。'}</p></div> : null}</div>
   <p className="integration-list-foot">{search || filter !== 'ALL' ? '筛选结果 ' : '当前列表 '}{shown.length} 个流程{truncated ? ' · 仅包含最近20份草稿和20个发布版本' : ''}</p>
  </>}
  {p.taskId && runs.length ? <details className="workflow-history integration-recent-runs"><summary>最近测试记录</summary>{runs.map(r => <RunRow key={r.receipt.id} run={r}/>)}</details> : null}
  {p.taskId && truncated ? <p className="integration-list-foot">版本来自最近列表，较早版本请使用原版本链接打开。</p> : null}
  <dialog className="integration-template-dialog" aria-label="新建工作流" ref={dialog}><header><h3>新建工作流</h3><button data-slot="button" aria-label="关闭新建工作流" onClick={() => dialog.current?.close()}>×</button></header><p>从一个样本模板开始，进入画布后配置处理规则。</p><div>
   <Template icon={Database} title="＋ 自定义实体模板" description="字段映射、清洗与模型校验" disabled={p.disabled || !p.workspace.operatorCatalog || !p.workspace.models.length} create={() => create('ENTITY')}/>
   <Template icon={Database} title="＋ Zabbix 主机模板" description="转换已有的主机采集批次" disabled={p.disabled || !p.workspace.operatorCatalog || !p.workspace.models.length || !p.workspace.zabbixSource.instanceId} create={() => create('ZABBIX_HOST')}/>
   <Template icon={FileText} title="＋ 日志样本模板" description="保留正文、校验时间与上下文" disabled={p.disabled || !p.workspace.operatorCatalog} create={() => create('LOG')}/>
   <Template icon={Activity} title="＋ 指标样本模板" description="校验采样时间、数值与单位" disabled={p.disabled || !p.workspace.operatorCatalog} create={() => create('METRIC')}/>
  </div><small><FileJson size={14}/>模板使用明确标记的 Fixture 样本，仅用于预览。</small></dialog>
 </section>
}
function Template(p: { icon: typeof Database; title: string; description: string; disabled: boolean; create: () => void }) {
 return <button data-slot="button" className="integration-template" aria-label={p.title} disabled={p.disabled} onClick={p.create}><p.icon size={23}/><span><strong>{p.title}</strong><small>{p.description}</small></span></button>
}
function RunRow(p: {run: Run}) { return <div className="workflow-run-row"><span>{date(p.run.receipt.createdAt)}</span><span>{p.run.mode === 'PREVIEW' ? '草稿预览' : '版本测试'} · {origins[p.run.receipt.origin]}</span><span>通过 {p.run.receipt.accepted} / 失败 {p.run.receipt.rejected} / 过滤 {p.run.receipt.filtered}</span><a href={'#/integrations/workflows/runs?runId=' + p.run.receipt.id}>查看明细</a></div> }
