import { useRef, useState } from 'react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Textarea } from '@/components/ui/textarea'
import { PageHeader, PageBody } from '../PageLayout.tsx'
import { isUuid, type DiagnosisRequest, type InsightResult, type EvidenceDocument } from '../../api/insights.ts'
import type { AiRun } from '../../api/ai-runs.ts'
import type { ModelSpend } from '../../api/model-spend.ts'
import { InvestigationEvidence, InvestigationFindings, InvestigationRunSummary, InvestigationAdvanced } from './InvestigationSections.tsx'
import './investigation-workspace.css'

export type InvestigationMode = 'durable' | 'synchronous'
export type InvestigationWorkspaceProps = {
  mode: InvestigationMode
  incident: string
  question: string
  from: string
  to: string
  runLookup: string
  insightLookup: string
  result: InsightResult | null
  evidence: EvidenceDocument | null
  spend: ModelSpend | null
  activeRun: AiRun | null
  pending: DiagnosisRequest | null
  pendingSubmit: { requestId: string } | null
  busy: boolean
  error: string
  authenticated: boolean
  unknown: boolean
  canRetryOriginal?: boolean
  stale?: boolean
  forbidden?: boolean
  onFieldChange: (field: 'incident' | 'question' | 'from' | 'to', value: string) => void
  onLookupChange: (kind: 'run' | 'insight', value: string) => void
  onModeChange: (mode: InvestigationMode) => void
  onStart: () => void
  onReadRun: () => void
  onReadResult: () => void
  onReadSpend: () => void
  onViewEvidence: (id: string) => void
  onReset: () => void
  onRetryOriginal: () => void
  onRecoverSubmission: () => void
}

type WorkspaceTab = 'overview' | 'evidence' | 'investigation'

/** Presentation only. Identity, requests, scope checks and original commands belong to the controller. */
export function InvestigationWorkspace(props: InvestigationWorkspaceProps) {
  const [tab, setTab] = useState<WorkspaceTab>('overview')
  const reportRef = useRef<HTMLElement>(null)
  const record = props.result?.record
  const blocked = props.busy || !props.authenticated
  const locked = props.busy || props.unknown
  const hasContext = Boolean(record || props.activeRun)
  const canStart = isUuid(props.incident.trim()) && props.question.trim().length > 0
    && props.pending === null && props.pendingSubmit === null && !props.activeRun

  function showReport() {
    setTab('investigation')
    requestAnimationFrame(() => reportRef.current?.scrollIntoView({ block: 'start', behavior: 'instant' }))
  }

  function primaryAction() {
    if (props.unknown) {
      if (props.pendingSubmit) props.onRecoverSubmission()
      else props.onReadResult()
    } else if (record) showReport()
    else if (props.activeRun?.status === 'SUCCEEDED') {
      setTab('investigation')
      props.onReadResult()
    }
    else if (props.activeRun) props.onReadRun()
    else props.onStart()
  }

  const primaryLabel = props.unknown ? '回查原请求' : record ? '查看调查报告'
    : props.activeRun?.status === 'SUCCEEDED' ? '读取调查报告'
      : props.activeRun ? '读取调查进度' : props.mode === 'durable' ? '开始只读调查' : '开始同步诊断'
  const primaryDisabled = blocked || (!props.unknown && !record && !props.activeRun && !canStart)

  function viewEvidence(id: string) {
    setTab('evidence')
    props.onViewEvidence(id)
  }

  return (
    <section className="panel current-diagnosis ow-v2 investigation-workspace" data-page="current-diagnosis" data-investigation-workspace>
      <PageHeader title="故障调查" description="核查证据，区分观察、假设与数据缺口。" actions={
        <Button className="iw-primary" size="default" disabled={primaryDisabled} onClick={primaryAction}>
          {props.busy ? '处理中…' : primaryLabel}
        </Button>
      } />
      <PageBody className="iw-body">
        <div className="iw-context" aria-label="调查上下文">
          <span className="iw-context-label">当前知识 · 只读</span>
          <span>{record && !props.activeRun ? '已保存报告 · 无运行记录' : props.mode === 'durable' ? '持久调查任务' : '同步兼容诊断 · 非持久任务'}</span>
          {record ? <span>引用关联已校验</span> : props.activeRun ? <span>运行记录已受理</span> : <span>尚未开始</span>}
        </div>

        {!props.authenticated ? <div className="iw-status" data-state="forbidden" role="status">
          <strong>需要有效的平台会话</strong><p>建立会话后可提交或读取当前身份授权的调查。</p>
        </div> : null}
        {props.error ? <div className="iw-status" id="iw-request-error" data-state={props.forbidden ? 'forbidden' : props.stale ? 'stale' : props.canRetryOriginal && props.pendingSubmit ? 'empty' : 'error'} role="alert">
          <strong>{props.forbidden ? '授权不足' : props.stale ? '结果已失效' : props.canRetryOriginal && props.pendingSubmit ? '本次回查未找到原请求' : '请求未完成'}</strong><p>{props.error}</p>
        </div> : null}
        {props.unknown ? <div className="iw-status" data-state="unknown-write" data-diagnosis-pending role="status">
          <strong>提交结果待确认</strong>
          <p>已保留原请求及输入。先回查原请求，确认后才能原样重发；不会自动调用模型或生成新请求。</p>
          <div className="iw-actions">
            <Button variant="outline" disabled={blocked || !props.canRetryOriginal} onClick={props.onRetryOriginal}>原样重发原请求</Button>
            {!props.canRetryOriginal ? <span className="iw-muted">请先完成原请求回查</span> : null}
          </div>
        </div> : null}
        {props.busy ? <div className="iw-loading" role="status" aria-live="polite">正在处理本次明确请求，请稍候…</div> : null}

        <div className="iw-tabs" role="group" aria-label="调查工作区视图">
          {([['overview', '概要'], ['evidence', '证据'], ['investigation', '调查']] as const).map(([value, label]) => (
            <Button key={value} variant="ghost" aria-pressed={tab === value} onClick={() => setTab(value)}>{label}</Button>
          ))}
        </div>

        <div className="iw-workspace" data-active-tab={tab} aria-busy={props.busy}>
          <div className="iw-overview iw-tab-section" data-tab-section="overview">
            <details className="iw-setup" open={hasContext ? undefined : true}>
              <summary><span>调查范围</span><span className="iw-muted">{hasContext ? '查看对象、窗口与问题' : '选择对象与采样窗口'}</span></summary>
              <div className="iw-setup-fields">
                <div className="iw-form-grid">
                  <label htmlFor="iw-incident">Incident ID<Input id="iw-incident" value={props.incident} disabled={locked} onChange={e => props.onFieldChange('incident', e.currentTarget.value)} autoComplete="off" spellCheck={false} /></label>
                  <label htmlFor="iw-from">采样开始（UTC）<Input id="iw-from" value={props.from} disabled={locked} onChange={e => props.onFieldChange('from', e.currentTarget.value)} autoComplete="off" spellCheck={false} /></label>
                  <label htmlFor="iw-to">采样结束（UTC）<Input id="iw-to" value={props.to} disabled={locked} onChange={e => props.onFieldChange('to', e.currentTarget.value)} autoComplete="off" spellCheck={false} /></label>
                </div>
                <label htmlFor="iw-question">调查问题<Textarea id="iw-question" value={props.question} disabled={locked} maxLength={2000} onChange={e => props.onFieldChange('question', e.currentTarget.value)} aria-describedby="iw-scope-hint" /></label>
                <p id="iw-scope-hint" className="iw-muted">窗口不超过 1 小时，最多 5 个关联资产。采样窗口不表示当时已知的信息。</p>
              </div>
            </details>
            {hasContext ? <div className="iw-scope-summary">
              <span><span className="iw-muted">调查对象</span><code>{record?.incidentId ?? props.activeRun?.incidentId}</code></span>
              <span><span className="iw-muted">采样窗口 · UTC</span><span>{record?.queryWindow.from ?? props.activeRun?.timeRange.from}<br />至 {record?.queryWindow.to ?? props.activeRun?.timeRange.to}</span></span>
            </div> : <div className="iw-empty"><strong>从一个问题开始</strong><p>输入 Incident 与固定窗口，开始一次受控的只读调查；已有记录可从高级选项回查。</p></div>}
            <InvestigationRunSummary run={props.activeRun} result={props.result} />
          </div>

          <div className="iw-evidence-column iw-tab-section" data-tab-section="evidence">
            <InvestigationEvidence result={props.result} evidence={props.evidence} disabled={blocked} onViewEvidence={viewEvidence} />
          </div>
          <section ref={reportRef} className="iw-findings-column iw-tab-section" data-tab-section="investigation" aria-label="调查报告">
            <InvestigationFindings result={props.result} disabled={blocked} onViewEvidence={viewEvidence} />
          </section>
        </div>

        <InvestigationAdvanced {...props} onReadRun={() => { setTab('overview'); props.onReadRun() }}
          onReadResult={() => { setTab('investigation'); props.onReadResult() }} />
      </PageBody>
    </section>
  )
}
