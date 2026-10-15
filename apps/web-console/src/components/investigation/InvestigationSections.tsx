import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { isUuid, type InsightResult, type EvidenceDocument } from '../../api/insights.ts'
import { dollars } from '../../api/model-spend.ts'
import type { AiRun } from '../../api/ai-runs.ts'
import { reconciliationText } from '../runs/AiRunDetailPanel.tsx'
import { RunStatusBadge } from '../runs/RunStatusBadge.tsx'
import type { InvestigationWorkspaceProps } from './InvestigationWorkspace.tsx'

const statusLabels = { PENDING: '待认领', RUNNING: '执行中', WAITING_TOOL: '工具等待', SUCCEEDED: '运行已完成', FAILED: '运行失败', CANCELLED: '已取消' }
const modeLabels: Record<string, string> = { 'labeled-fixture': 'Fixture · 合成数据', 'zabbix-jsonrpc': '受控来源 · JSON-RPC', unknown: '来源未知' }

function DataModes({ modes, mock = false }: { modes: string[]; mock?: boolean }) {
  return <div className="iw-data-modes" aria-label="数据与模型来源">
    {modes.map(mode => <span key={mode} className="iw-source" data-mode={mode}>{modeLabels[mode] ?? `来源：${mode}`}</span>)}
    {mock ? <span className="iw-source" data-mode="mock">Mock · 确定性输出，未调用外部模型</span> : null}
  </div>
}

export function InvestigationRunSummary({ run, result }: { run: AiRun | null; result: InsightResult | null }) {
  if (!run) return null
  return <section className="iw-run-summary" aria-label="调查运行状态" data-run-detail={run.runId}>
    <div className="iw-section-heading"><h3>调查进度</h3><RunStatusBadge status={run.status} label={statusLabels[run.status]} /></div>
    <p>最近记录 <time dateTime={run.updatedAt}>{run.updatedAt}</time> · 第 {run.attempt} 次尝试</p>
    {run.status === 'SUCCEEDED' ? <p>{result ? '已读取保存报告。运行完成仍需核查观察、假设和证据。' : '运行已完成。请明确读取已保存报告，完成状态不表示根因已确认。'}</p> : null}
    {run.status === 'PENDING' || run.status === 'RUNNING' || run.status === 'WAITING_TOOL' ? <p className="iw-muted">当前显示最近一次运行记录，读取进度由你明确触发。</p> : null}
    {run.reason ? <p>结束原因：<code>{run.reason}</code></p> : null}
    {run.cancelRequestedAt ? <p>取消已请求，最终状态以运行记录为准。</p> : null}
    {run.disclosure ? <p className="iw-notice">{reconciliationText(run.disclosure)}</p> : null}
    <a className="iw-text-link" href="#/ai/runs">查看运行记录与事件</a>
  </section>
}

export function InvestigationFindings({ result, disabled, onViewEvidence }: { result: InsightResult | null; disabled: boolean; onViewEvidence: (id: string) => void }) {
  const record = result?.record
  return <div className="iw-findings" data-insight-result={record ? '' : undefined}>
    <div className="iw-section-heading"><h3>发现与假设</h3><span className="iw-muted">只读调查报告</span></div>
    {!record ? <div className="iw-empty"><strong>报告尚未读取</strong><p>调查受理或运行完成后，明确读取已保存报告。这里不会推测根因或填充示例结论。</p></div> : <>
      <DataModes modes={record.dataModes} mock={record.model.provider === 'mock-deterministic'} />
      <div className="iw-report-summary"><p>{record.insight.summary}</p></div>
      <p className="iw-notice">仅校验引用关联，尚未确认根因。当前知识模式不能证明采样时刻已知的事实。</p>
      {record.insight.findings.length === 0 ? <p className="iw-empty">报告未列出观察或假设；不能据此判定系统健康。</p> : <ol className="iw-finding-list">
        {record.insight.findings.map((finding, index) => <li key={`${finding.kind}-${index}`} data-finding-kind={finding.kind}>
          <div className="iw-finding-label"><span>{String(index + 1).padStart(2, '0')}</span><strong>{finding.kind === 'observation' ? '观察' : '待验证假设'}</strong></div>
          <p>{finding.statement}</p>
          <div className="iw-reference-actions" aria-label="关联证据引用">
            {finding.evidenceRefs.map(id => <Button key={id} variant="link" disabled={disabled} onClick={() => onViewEvidence(id)}>{`证据 ${record.evidenceIds.indexOf(id) + 1}`}</Button>)}
            <span className="iw-muted">关联引用，不等于因果证明</span>
          </div>
        </li>)}
      </ol>}
      <div className="iw-report-boundaries" data-state={record.insight.missingData.length > 0 ? 'partial' : 'ready'}>
        <h4>数据缺口</h4>
        {record.insight.missingData.length > 0 ? <ul>{record.insight.missingData.map((row, index) => <li key={index}>{row}</li>)}</ul> : <p>报告未列出数据缺口，来源完整性仍未单独证明。</p>}
        <h4>结论限制</h4>
        {record.insight.limitations.length > 0 ? <ul>{record.insight.limitations.map((row, index) => <li key={index}>{row}</li>)}</ul> : <p>报告未列出额外限制。</p>}
        {record.warnings.length > 0 ? <details><summary>查看原始警告（{record.warnings.length}）</summary><ul>{record.warnings.map((row, index) => <li key={index}><code>{row}</code></li>)}</ul></details> : null}
      </div>
    </>}
  </div>
}

export function InvestigationEvidence({ result, evidence, disabled, onViewEvidence }: { result: InsightResult | null; evidence: EvidenceDocument | null; disabled: boolean; onViewEvidence: (id: string) => void }) {
  const ids = result?.record.evidenceIds ?? []
  return <section className="iw-evidence" aria-label="授权证据">
    <div className="iw-section-heading"><h3>证据</h3><span className="iw-muted">{result ? `${ids.length} 条报告引用` : '尚无报告引用'}</span></div>
    <p className="iw-muted">逐条授权读取。未打开的引用尚无可展示的来源、时间与内容。</p>
    {ids.length === 0 ? <div className="iw-empty"><strong>等待已保存报告</strong><p>报告提供实际引用后，可在这里核查来源和快照。</p></div> : <ol className="iw-evidence-list">
      {ids.map((id, index) => <li key={id} data-selected={evidence?.evidence.id === id}>
        <div><strong>证据 {index + 1}</strong><span className="iw-muted">{evidence?.evidence.id === id ? '已授权读取' : '引用尚未读取'}</span></div>
        <Button variant="outline" disabled={disabled} onClick={() => onViewEvidence(id)} aria-label={`读取证据 ${index + 1}`}>{evidence?.evidence.id === id ? '重新读取' : '读取证据'}</Button>
      </li>)}
    </ol>}
    {evidence ? <div className="iw-evidence-detail" data-insight-evidence>
      <h4>{evidence.evidence.kind === 'incident' ? 'Incident 快照' : 'Metric 快照'}</h4>
      <p>{evidence.evidence.summary}</p>
      <DataModes modes={evidence.dataModes} />
      <dl className="iw-metadata">
        <div><dt>来源工具</dt><dd>{evidence.producerTool}</dd></div>
        <div><dt>观测时间</dt><dd><time dateTime={evidence.evidence.observedAt}>{evidence.evidence.observedAt}</time></dd></div>
        <div><dt>可见时间</dt><dd><time dateTime={evidence.evidence.availableAt}>{evidence.evidence.availableAt}</time></dd></div>
        <div><dt>有效至</dt><dd><time dateTime={evidence.evidence.expiresAt}>{evidence.evidence.expiresAt}</time></dd></div>
      </dl>
      {evidence.warnings.length > 0 ? <div className="iw-notice"><strong>证据警告</strong><ul>{evidence.warnings.map((warning, index) => <li key={index}><code>{warning}</code></li>)}</ul></div> : null}
      <details className="iw-raw-evidence"><summary>查看快照数据与定位条件</summary>
        <p className="iw-muted">以下为授权快照的纯文本；未提供的单位、聚合和采样信息保持未知。</p>
        <dl className="iw-metadata"><div><dt>证据标识</dt><dd><code>{evidence.evidence.id}</code></dd></div><div><dt>受控定位</dt><dd><code>{evidence.evidence.sourceRef}</code></dd></div><div><dt>查询窗口</dt><dd>{evidence.queryWindow.from} 至 {evidence.queryWindow.to}</dd></div></dl>
        <pre tabIndex={0} aria-label="授权证据快照数据">{JSON.stringify(evidence.data, null, 2)}</pre>
      </details>
    </div> : null}
  </section>
}

export function InvestigationAdvanced(props: InvestigationWorkspaceProps) {
  const record = props.result?.record
  const spend = props.spend?.record
  const blocked = props.busy || !props.authenticated
  return <details className="iw-advanced">
    <summary>高级选项与审计依据<span className="iw-muted">原标识回查 · 固定版本 · 用量</span></summary>
    <div className="iw-advanced-body">
      <div className="iw-lookup-grid">
        <label htmlFor="iw-run-lookup">AIRun 运行标识<Input id="iw-run-lookup" value={props.runLookup} disabled={props.busy || props.unknown} onChange={e => props.onLookupChange('run', e.currentTarget.value)} autoComplete="off" spellCheck={false} /></label>
        <Button variant="outline" disabled={blocked || !isUuid(props.runLookup.trim())} onClick={props.onReadRun}>读取运行记录</Button>
        <label htmlFor="iw-insight-lookup">AIInsight 结果请求标识<Input id="iw-insight-lookup" value={props.insightLookup} disabled={props.busy || props.unknown} onChange={e => props.onLookupChange('insight', e.currentTarget.value)} autoComplete="off" spellCheck={false} /></label>
        <Button variant="outline" disabled={blocked || !isUuid(props.insightLookup.trim())} onClick={props.onReadResult}>读取已保存结果</Button>
      </div>
      <p className="iw-muted">持久任务的请求标识、AIRun 标识与报告引用分别核对；同步诊断只保存结果，不创建 AIRun。</p>
      {props.pendingSubmit ? <p>原提交请求标识：<code>{props.pendingSubmit.requestId}</code></p> : null}
      {props.pending ? <p>同步结果请求标识：<code>{props.pending.runId}</code></p> : null}
      <div className="iw-mode-row">
        <label htmlFor="iw-mode">提交方式<select id="iw-mode" value={props.mode} disabled={props.busy || props.unknown || Boolean(props.pending || props.pendingSubmit || props.activeRun || record)} onChange={e => props.onModeChange(e.currentTarget.value as 'durable' | 'synchronous')}>
          <option value="durable">持久调查任务</option><option value="synchronous">同步兼容诊断（非持久任务）</option>
        </select></label>
        <Button variant="outline" disabled={props.busy || props.unknown} onClick={props.onReset}>准备新调查</Button>
      </div>
      {record ? <dl className="iw-metadata iw-audit-metadata">
        <div><dt>报告标识</dt><dd><code>{record.id}</code></dd></div>
        <div><dt>模型</dt><dd>{record.model.provider} / {record.model.name}</dd></div>
        <div><dt>固定 Skill</dt><dd>{record.skill.id}@{record.skill.version}<br /><code>{record.skill.digest}</code></dd></div>
        <div><dt>Incident 版本</dt><dd>{record.incidentVersion}</dd></div>
        <div><dt>知识截止</dt><dd>{record.asOf}</dd></div>
        <div><dt>保存 / 有效至</dt><dd>{record.savedAt}<br />{record.expiresAt}</dd></div>
        <div><dt>保存介质</dt><dd>{props.result?.storage}</dd></div>
      </dl> : null}
      <div className="iw-spend" data-model-spend={spend ? '' : undefined}>
        <div className="iw-section-heading"><h4>模型用量与费用</h4><Button variant="outline" disabled={blocked || !isUuid(props.insightLookup.trim())} onClick={props.onReadSpend}>读取用量与费用</Button></div>
        {!spend ? <p className="iw-muted">尚未读取费用记录；不能据此确认费用为零。</p> : <>
          <p>{spend.policy.provider} / {spend.policy.model} · 费率版本 {spend.policy.priceVersion}</p>
          {spend.state === 'REPORTED' && spend.usage ? <>
            <p>已上报 · 输入 {spend.usage.inputTokens} Token · 输出 {spend.usage.outputTokens} Token · 缓存输入 {spend.usage.cachedInputTokens} Token</p>
            <p>估算费用 {spend.estimatedMicros === null ? '待确认' : dollars(spend.estimatedMicros)}</p>
          </> : <p className="iw-notice">费用待确认：没有可核验的已上报用量，预留额度仍占用，不会自动退款或重试。</p>}
          <p>预留 {dollars(spend.reservedMicros)} · 当前占用 {dollars(spend.accountedMicros)}</p>
          <p className="iw-muted">金额按配置费率估算，并非提供方账单。用量记录不表示调查成功。</p>
          {spend.policy.provider === 'mock-deterministic' ? <p className="iw-notice">显式 Mock：未调用外部模型，用量与费用为已报告的零值。</p> : null}
        </>}
      </div>
    </div>
  </details>
}
