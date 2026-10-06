import { Button } from '@/components/ui/button'
import type { SourceType } from '../../api/source-setups.ts'
import { sourceCatalog, sourceTitles } from './source-catalog.ts'
type CatalogItem = (typeof sourceCatalog)[number] & {
  note: string
  savedCount: number | undefined
  truncated: boolean
  createDisabled: boolean
}

export function SourceCatalog(p: {
  items: CatalogItem[]
  tasksDisabled: boolean
  onCreate: (type: SourceType) => void
  onTasks: (type: SourceType) => void
}) {
  return <div className="integration-catalog-grid">{p.items.map(item => <article key={item.id} className="integration-source-card" data-source-kind={item.id}>
    <div className="integration-source-logo">
      <span className="integration-logo-mark"><item.icon size={22}/></span><div><h3>{sourceTitles[item.id]}</h3><span className="integration-source-category">{item.category}</span></div>
      {item.savedCount !== undefined ? <span className="integration-created">已保存 {item.savedCount}{item.truncated ? '+' : ''}</span> : null}
    </div>
    <div className="integration-source-copy"><p>{item.description}</p><small>{item.note}</small></div>
    <div className="integration-source-actions">{item.id === 'CMDB_SNAPSHOT'
      ? <Button variant="outline" asChild><a href="#/integrations/cmdb">前往快照导入 ↗</a></Button>
      : <><Button aria-label={item.id === 'ZABBIX_HOST' ? '配置 Zabbix →' : '配置手工样本 →'} disabled={item.createDisabled} onClick={() => p.onCreate(item.id)}>创建接入</Button><Button variant="outline" disabled={p.tasksDisabled} onClick={() => p.onTasks(item.id)}>查看任务</Button></>}
    </div>
  </article>)}</div>
}
