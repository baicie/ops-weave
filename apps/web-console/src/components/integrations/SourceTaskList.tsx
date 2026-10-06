import { ChevronRight, Database } from 'lucide-react'
import { Button } from '../ui/button.tsx'
import type { Setup } from '../../api/source-setups.ts'
import { sourceTitles } from './source-catalog.ts'

export function SourceTaskList(p: {
  items: Setup[]
  loaded: boolean
  busy: boolean
  failed: boolean
  filtered: boolean
  truncated: boolean
  disabled: boolean
  onInspect: (setup: Setup) => void
  onContinue: (id: string) => void
  onCreate: () => void
}) {
  return <>
    <div className="integration-table-wrap" aria-busy={p.busy}><table className="integration-table source-config-table" aria-label="接入回执">
      <thead><tr><th scope="col">接入名称</th><th scope="col">接入类型</th><th scope="col">来源标记</th><th scope="col">来源实例</th><th scope="col">创建时间</th><th scope="col">操作</th></tr></thead>
      <tbody>{p.items.map(setup => <tr className="source-saved-row" key={setup.id}>
        <td><button className="integration-name-link" type="button" disabled={p.disabled} onClick={() => p.onInspect(setup)}>{setup.name}</button>{setup.description ? <small className="source-row-description">{setup.description}</small> : null}</td><td>{sourceTitles[setup.source.kind]}</td>
        <td><span className="source-data-mode" data-mode={setup.dataMode}>{setup.dataMode === 'fixture' ? 'Fixture 合成数据' : setup.dataMode === 'MANUAL_SAMPLE' ? '手工样本' : 'Zabbix 连接'}</span></td>
        <td><code>{setup.source.instanceId}</code></td><td><time dateTime={setup.createdAt}>{new Date(setup.createdAt).toLocaleString()}</time></td>
        <td><button type="button" data-slot="button" disabled={p.disabled} onClick={() => p.onInspect(setup)}>查看配置</button>{setup.source.instanceId !== 'connection-' + setup.id ? <><a href={'#/integrations/workflows?task=' + encodeURIComponent(setup.workflowId)}>查看版本</a><button className="source-continue" aria-label={"继续编排：" + setup.name} type="button" data-slot="button" disabled={p.disabled} onClick={() => p.onContinue(setup.id)}>继续编排<ChevronRight size={13}/></button></> : null}</td>
      </tr>)}</tbody>
    </table>{!p.items.length ? <div className="integration-list-empty"><Database size={25}/><strong>{p.busy ? '正在读取接入配置' : !p.loaded ? p.failed ? '接入配置读取失败' : '登录后查看接入配置' : p.filtered ? '没有匹配的接入任务' : '还没有接入配置'}</strong><p>{!p.loaded ? p.failed ? '请使用页面上方的重试读取。' : '授权会话就绪后自动读取。' : p.filtered ? '调整搜索或分类筛选。' : '选择接入类型，保存来源并配置处理流程。'}</p>{p.loaded && !p.filtered ? <Button variant="outline" onClick={p.onCreate}>选择接入类型</Button> : null}</div> : null}</div>
    {p.loaded ? <p className="integration-list-foot">{p.filtered ? '筛选结果' : '当前列表'} {p.items.length} 份配置{p.truncated ? ' · 仅显示最近20份' : ''} · 创建记录不表示采集已启用</p> : null}
  </>
}
