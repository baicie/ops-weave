import { useEffect, useId, useImperativeHandle, useRef, useState, type ReactNode, type Ref } from 'react'
import { Activity, BookOpen, Database, FileJson, Settings2, TableProperties, X } from 'lucide-react'
import { Button } from '../ui/button.tsx'
import { Input } from '../ui/input.tsx'
import { Textarea } from '../ui/textarea.tsx'
import type { Command, Connection, Setup, SourceType } from '../../api/source-setups.ts'
import type { Model } from '../../api/workflows.ts'
import type { SourceConnectionCheck } from '../../api/source-connection-checks.ts'
import { sourceTitles } from './source-catalog.ts'
import { SourceFieldReference } from './SourceFieldReference.tsx'
import { SourceSetupGuide } from './SourceSetupGuide.tsx'

const tabs = [{ id: 'config', label: '接入配置', icon: Settings2 }, { id: 'fields', label: '数据字段', icon: TableProperties }, { id: 'metrics', label: '监控指标', icon: Activity }, { id: 'guide', label: '接入教程', icon: BookOpen }] as const
type Tab = typeof tabs[number]['id']
type Props = {
  ref: Ref<HTMLDialogElement>; active: boolean; kind: SourceType; connection?: Connection | null; models: Model[];
  view: Setup | null; probe: SourceConnectionCheck | null; pending: Command | null; busy: boolean; disabled: boolean;
  name: string; description: string; changeName: (name: string) => void; changeDescription: (description: string) => void;
  error: string; notice: string; close: () => void; closed: () => void; test: () => void; confirm: () => void; go: (id: string) => void;
  metricsContent: (enabled: boolean) => ReactNode;
}

/** Presentation and reference tabs; source confirmation and trusted state stay in the page. */
export function SourceSetupDrawer(props: Props) {
  const [tab, setTab] = useState<Tab>('config')
  const [referenceSession, setReferenceSession] = useState(0)
  const [metricsVisited, setMetricsVisited] = useState(false)
  const dialog = useRef<HTMLDialogElement>(null)
  const body = useRef<HTMLDivElement>(null)
  const tablist = useRef<HTMLDivElement>(null)
  const id = useId()
  useImperativeHandle(props.ref, () => dialog.current!, [])
  useEffect(() => { if (!props.active) dialog.current?.close() }, [props.active])
  const host = props.kind === 'ZABBIX_HOST'
  const fixture = (props.view?.dataMode ?? props.connection?.dataMode) === 'fixture'
  const Icon = host ? Database : FileJson
  function choose(value: Tab) { setTab(value); if (value === 'metrics') setMetricsVisited(true); body.current?.scrollTo({ top: 0 }) }
  return <dialog className="model-drawer source-drawer source-setup-drawer" aria-label="数据源配置" ref={dialog} onCancel={event => { if (props.busy) event.preventDefault() }} onClose={() => { setTab('config'); setMetricsVisited(false); setReferenceSession(value => value + 1); props.closed() }}>
    <header className="model-drawer-heading source-setup-heading"><div className="source-setup-identity"><span className="source-setup-icon"><Icon size={25} /></span><div><div className="model-eyebrow">{props.view ? '已保存 · 创建时配置' : '创建接入'}</div><h3>{sourceTitles[props.kind]}</h3><p>{host ? '主机清单与已有采集批次' : '实体、指标与日志的 JSON 转换预览'}</p></div></div><button type="button" className="model-close" aria-label="关闭数据源配置" disabled={props.busy} onClick={props.close}><X size={17} /></button></header>
    <div className="source-setup-tabs" ref={tablist} role="tablist" aria-label="接入配置内容">{tabs.map(item => <button key={item.id} type="button" data-slot="button" id={id + '-' + item.id} role="tab" aria-selected={tab === item.id} aria-controls={id + '-panel-' + item.id} tabIndex={tab === item.id ? 0 : -1} onClick={() => choose(item.id)} onKeyDown={event => {
      if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return
      event.preventDefault()
      const index = tabs.findIndex(entry => entry.id === item.id)
      const next = event.key === 'Home' ? 0 : event.key === 'End' ? tabs.length - 1 : (index + (event.key === 'ArrowRight' ? 1 : -1) + tabs.length) % tabs.length
      choose(tabs[next]!.id); tablist.current?.querySelectorAll<HTMLButtonElement>('button')[next]?.focus()
    }}><item.icon size={15} />{item.label}</button>)}</div>
    <div className="model-drawer-body source-setup-body" ref={body}>
      <div role="tabpanel" id={id + '-panel-config'} aria-labelledby={id + '-config'} hidden={tab !== 'config'}>
        <div className="source-config-grid"><section className="source-config-section"><header><h4>基本信息</h4><p>为来源命名，方便后续查找接入和版本。</p></header><fieldset className="model-form" disabled={props.disabled || !!props.pending || !!props.view}><label>接入名称 <span className="source-required">必填</span><Input aria-label="接入名称" aria-required="true" maxLength={80} value={props.name} onInput={event => props.changeName(event.currentTarget.value)} /><small>最多80字符。</small></label><label>说明 <span className="source-optional">选填</span><Textarea aria-label="接入说明" placeholder="说明这份接入的用途或范围" maxLength={500} rows={3} value={props.description} onInput={event => props.changeDescription(event.currentTarget.value)} /><small>最多500字符，请勿填写密钥或样本正文。</small></label></fieldset></section>
        <section className="source-config-section"><header><h4>{props.view ? '创建时来源快照' : host ? '来源连接' : '样本输入'}</h4><p>{props.view ? '保存时固定的配置，当前工作流以版本为准。' : host ? '使用平台已配置的连接，可显式测试。' : '使用手工 JSON 对象验证转换规则。'}</p></header>
          {props.view ? <><dl className="source-connection"><dt>来源实例</dt><dd>{props.view.source.instanceId}</dd><dt>创建时来源标记</dt><dd>{props.view.dataMode === 'fixture' ? 'Fixture 合成数据' : props.view.dataMode === 'MANUAL_SAMPLE' ? '手工样本' : 'Zabbix 连接'}</dd>{props.view.initialTarget ? <><dt>旧版创建时实体模型</dt><dd>{props.view.initialTarget.id + ' @ ' + props.view.initialTarget.revision}</dd></> : <><dt>输出配置</dt><dd>在输出节点配置</dd></>}<dt>创建时间</dt><dd>{props.view.createdAt}</dd></dl><p className="source-reference-note">这是创建时的来源快照。继续编排后在输出节点配置目标，已有草稿会按原版本打开。</p></> : host ? <><dl className="source-connection"><dt>来源实例</dt><dd>{props.connection?.instanceId ?? '无可用连接'}</dd><dt>API 地址</dt><dd>{props.connection?.endpoint ?? (fixture ? 'Fixture · 无外部连接' : '未配置')}</dd><dt>凭据引用</dt><dd>{props.connection?.credentialRef ?? '无需凭据'}</dd><dt>数据标记</dt><dd>{fixture ? 'Fixture 合成数据' : 'Zabbix 连接'}</dd></dl><p className="source-reference-note">本页选择已有连接。新增地址和凭据由平台配置，暂不支持多实例新增。</p><Button type="button" variant="outline" disabled={props.disabled || !!props.pending || !props.connection} onClick={props.test}>测试连接</Button>{props.probe ? <div className="source-probe" role="status"><strong>{props.probe.dataMode === 'labeled-fixture' ? 'Fixture 自检 · 非真实连接' : props.probe.reachable ? '连接测试成功' : '连接测试失败'}</strong><p>{'来源标记：' + props.probe.dataMode + ' · 状态：' + props.probe.statusCode}</p><p>{props.probe.reportedVersion ? '来源报告版本：' + props.probe.reportedVersion + '；连通性测试不等于完整采集兼容验收。' : '未取得真实版本信息。'}</p></div> : null}</> : <><div className="source-input-summary"><FileJson size={24} /><strong>手工 JSON 样本</strong><span>1–5条记录 · 每条最多32个标量字段</span></div><p className="source-reference-note">保存后在处理画布的测试区填写样本。样本不会随接入配置保存，也不会从此页面自动发送。</p><button type="button" className="source-inline-reference" onClick={() => choose('fields')}>查看指标、日志与实体字段 →</button></>}
        </section></div>
        {!props.view ? <section className="source-setup-next"><div><h4>下一步：配置处理流程</h4><p>保存来源后进入画布，设置字段映射、清洗规则与输出。保存接入不会启动采集。</p></div><button type="button" onClick={() => choose('guide')}><BookOpen size={14} />查看教程</button></section> : null}
      </div>
      <div role="tabpanel" id={id + '-panel-fields'} aria-labelledby={id + '-fields'} hidden={tab !== 'fields'}><SourceFieldReference key={props.kind + referenceSession} host={host} models={props.models} /></div>
      <div role="tabpanel" id={id + '-panel-metrics'} aria-labelledby={id + '-metrics'} hidden={tab !== 'metrics'}>{metricsVisited ? props.metricsContent(props.active && !!dialog.current?.open && tab === 'metrics') : null}</div>
      <div role="tabpanel" id={id + '-panel-guide'} aria-labelledby={id + '-guide'} hidden={tab !== 'guide'}><SourceSetupGuide host={host} fixture={fixture} configure={() => choose('config')} /></div>
      {props.pending ? <p className="source-pending">确认请求已固定。若结果待确认，可按原请求查询或重试；重新配置前请先查询，避免重复创建。</p> : null}{props.error ? <p role="alert">{props.error}</p> : null}<p role="status">{props.busy ? '正在处理…' : props.notice}</p>
    </div>
    <footer className="model-drawer-footer source-setup-footer"><span>{props.view ? '来源快照只读' : props.pending ? '请先核对原确认结果' : '保存来源，不启动采集'}</span><button type="button" disabled={props.busy} onClick={props.close}>关闭</button>{props.pending ? <button type="button" disabled={props.disabled} onClick={() => props.go(props.pending!.requestId)}>查询确认结果</button> : null}{!props.view ? <Button type="button" disabled={props.disabled || !props.name.trim() || !props.pending && !props.connection} onClick={props.confirm}>{props.pending ? '按原配置重试' : '保存并配置流程'}</Button> : <Button type="button" disabled={props.disabled} onClick={() => props.go(props.view!.id)}>继续编排</Button>}</footer>
  </dialog>
}
