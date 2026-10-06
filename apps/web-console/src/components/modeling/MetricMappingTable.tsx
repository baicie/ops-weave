import type { MappingPage, MappingView } from '../../api/metric-mappings.ts'
import { Button } from '../ui/button.tsx'

export function MetricMappingTable(props: { page: MappingPage; disabled: boolean; inspect: (view: MappingView) => void }) {
  const { page, disabled, inspect } = props
  return <><div className="integration-table-wrap"><table className="integration-table" aria-label="指标来源绑定列表">
    <thead><tr><th scope="col">完整指标标识</th><th scope="col">来源 / 监控项</th><th scope="col">映射状态</th><th scope="col">操作</th></tr></thead>
    <tbody>{page.items.map(view => {
      const b = view.binding
      return <tr key={b.sourceInstanceId + ':' + b.externalItemId}>
        <td><code>{b.metricKey}</code></td><td><code>{b.sourceInstanceId}</code><small>{b.externalItemId}</small></td>
        <td>{b.mappingPin ? <><code>{b.mappingPin.id}</code><small>v{b.mappingPin.revision}</small></> : '未固定'}{b.lifecycle === 'INACTIVE' ? <small>已停用</small> : null}</td>
        <td><Button variant="ghost" disabled={disabled} onClick={() => inspect(view)}>{view.canConfigure && b.lifecycle === 'ACTIVE' ? '维护映射' : '查看映射'}</Button></td>
      </tr>
    })}</tbody>
  </table></div>{!page.items.length ? <p role="status">当前授权范围内没有可展示的指标来源绑定。</p> : null}
    <p className="model-muted">当前列表 {page.items.length} 项{page.truncated ? ' · 更多绑定请按原来源和监控项链接读取' : ''}</p></>
}
