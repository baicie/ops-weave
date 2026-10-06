import { Button } from '@/components/ui/button'
import type { WorkflowSource } from '../../api/workflows.ts'
import type { SourceInstance, InstancePage } from '../../api/source-instances.ts'
import type { ConnectionConfiguration } from '../../api/source-connections.ts'

type Props = { source: WorkflowSource; legacyId: string; locked: boolean; entityOutput: boolean; metricOutput?:boolean; logOutput?:boolean; pickerOpen: boolean; page: InstancePage | null; selectedId: string; revision: string; rows: ConnectionConfiguration[] | null; loading: boolean; error: string; onLegacy: (kind: 'MANUAL_SAMPLE' | 'ZABBIX_HOST') => void; onOpen: () => void; onSelect: (id: string) => void; onRevision: (revision: string) => void; onBind: () => void; onRetry: () => void }

export function WorkflowSourceConfig(p: Props) {
  const pin = p.source.configuration
  const selected: SourceInstance | undefined = p.page?.items.find(i => i.id === p.selectedId)
  const row = p.rows?.find(c => String(c.revision) === p.revision)
  return <section className="workflow-source-config">
    <label>输入来源<select aria-label="输入来源" disabled={p.locked} value={pin ? 'CONFIGURED' : p.source.kind} onChange={e => p.onLegacy(e.target.value as 'MANUAL_SAMPLE' | 'ZABBIX_HOST')}>
      <option value="MANUAL_SAMPLE">手工样本</option><option value="ZABBIX_HOST" disabled={!p.legacyId || !p.entityOutput}>已有 Zabbix 主机批次</option>{pin ? <option value="CONFIGURED" disabled>已配置接入实例</option> : null}
    </select></label>
    {pin ? <dl><dt>接入实例</dt><dd><code>{pin.sourceId}</code></dd><dt>配置版本</dt><dd>{'v' + pin.revision}</dd><dt>配置摘要</dt><dd><code>{pin.digest}</code></dd></dl> : null}
    {p.source.metric?<dl><dt>来源指标</dt><dd><code>{p.source.metric.sourceKey}</code></dd><dt>系列</dt><dd>主机 {p.source.metric.hostId} · 指标项 {p.source.metric.itemId}</dd><dt>类型与单位</dt><dd>{p.source.metric.sourceValueType} · {p.source.metric.sourceUnit||'未声明单位'}</dd><dt>元数据摘要</dt><dd><code>{p.source.metric.digest}</code></dd></dl>:null}
    {p.source.log?<dl><dt>来源日志</dt><dd><code>{p.source.log.sourceKey}</code></dd><dt>日志项</dt><dd>主机 {p.source.log.hostId} · 日志项 {p.source.log.itemId}</dd><dt>元数据摘要</dt><dd><code>{p.source.log.digest}</code></dd></dl>:null}
    {!p.locked && (p.entityOutput||p.metricOutput||p.logOutput) ? <Button variant="outline" onClick={p.onOpen}>选择接入实例</Button> : null}
    {p.pickerOpen && !p.locked ? <div className="workflow-source-picker">
      {p.loading ? <p role="status">正在读取连接版本…</p> : null}
      {p.error ? <div role="alert"><p>{p.error}</p><Button variant="outline" disabled={p.loading} onClick={p.onRetry}>重新读取连接</Button></div> : null}
      {p.page ? <><label>接入实例<select aria-label="工作流接入实例" disabled={p.loading} value={p.selectedId} onChange={e => p.onSelect(e.target.value)}><option value="">请选择接入实例</option>{p.page.items.filter(i => i.source.kind === 'ZABBIX_HOST').map(i => <option key={i.id} value={i.id} disabled={i.state !== 'ACTIVE'}>{i.name + (i.state === 'ARCHIVED' ? ' · 已归档' : '')}</option>)}</select></label>{p.page.truncated ? <p>仅显示最近20份可读实例。</p> : null}{!p.page.items.some(i => i.source.kind === 'ZABBIX_HOST') ? <p>暂无可读的主机接入实例。</p> : null}</> : null}
      {p.selectedId && p.rows ? p.rows.length ? <><label>配置版本<select aria-label="工作流连接版本" value={p.revision} disabled={p.loading} onChange={e => p.onRevision(e.target.value)}><option value="">请选择固定版本</option>{p.rows.map(c => <option key={c.revision} value={String(c.revision)}>{'v' + c.revision + ' · ' + c.endpoint.name}</option>)}</select></label>{row ? <dl><dt>登记地址</dt><dd><code>{row.endpoint.address}</code></dd><dt>主机组范围</dt><dd><code>{row.hostGroupIds.length ? row.hostGroupIds.join(', ') : '未固定，需维护连接'}</code></dd><dt>凭据版本</dt><dd><code>{row.credentialPin.credentialId + ' · v' + row.credentialPin.revision}</code></dd><dt>秘密版本标识</dt><dd><code>{row.credentialPin.versionId}</code></dd></dl> : null}<Button hidden={!p.entityOutput} disabled={!row || !row.hostGroupIds.length || !selected || selected.state !== 'ACTIVE' || p.loading} onClick={p.onBind}>使用此版本</Button></> : <p>这份实例尚未保存地址、主机组范围与凭据的固定配置。</p> : null}
    </div> : null}
  </section>
}
