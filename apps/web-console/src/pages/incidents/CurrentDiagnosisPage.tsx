import { useEffect, useRef, useState } from 'react'
import { usePlatformSession } from '../../state/platform-session.ts'
import { Button } from '@/components/ui/button'
import { PageHeader, PageBody } from '../../components/PageLayout.tsx'
import { Input } from '@/components/ui/input'
import { Textarea } from '@/components/ui/textarea'
import { diagnose, getInsight, getEvidence, isUuid, InsightRequestError, type DiagnosisRequest, type InsightResult, type EvidenceDocument } from '../../api/insights.ts'
import { getModelSpend, dollars, type ModelSpend } from '../../api/model-spend.ts'

export function CurrentDiagnosisPage() {
  const query = new URLSearchParams(location.hash.split('?')[1])
  const utc = (offset: number) => new Date(Math.floor((Date.now() + offset) / 1000) * 1000).toISOString()
  const [incident, setIncident] = useState(query.get('incidentId') ?? '')
  const [question, setQuestion] = useState('请基于当前 Incident 和 CPU 指标总结观察、待验证假设与数据缺口。')
  const [from, setFrom] = useState(utc(-900000))
  const [to, setTo] = useState(utc(0))
  const [runId, setRunId] = useState(query.get('runId') ?? '')
  const [result, setResult] = useState<InsightResult | null>(null)
  const [evidence, setEvidence] = useState<EvidenceDocument | null>(null)
  const [spend, setSpend] = useState<ModelSpend | null>(null)
  const [pending, setPending] = useState<DiagnosisRequest | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const controllerRef = useRef<AbortController | undefined>(undefined)
  const sequenceRef = useRef(0)
  const disposedRef = useRef(false)
  const resultRef = useRef(result)
  resultRef.current = result

  function invalidate() {
    ++sequenceRef.current
    controllerRef.current?.abort()
    setBusy(false)
    setResult(null)
    setEvidence(null)
    setSpend(null)
    setError('')
  }

  const authenticated = usePlatformSession(change => {
    invalidate()
    setPending(null)
    setQuestion('请基于当前 Incident 和 CPU 指标总结观察、待验证假设与数据缺口。')
    if (change.error) setError(change.error.message)
  })

  useEffect(() => {
    const expiry = window.setInterval(() => {
      if (resultRef.current && Date.parse(resultRef.current.record.expiresAt) <= Date.now()) {
        invalidate()
        setError('结果或证据已过期，请开始新诊断')
      }
    }, 1000)
    return () => {
      disposedRef.current = true
      ++sequenceRef.current
      controllerRef.current?.abort()
      window.clearInterval(expiry)
    }
  }, [])

  function edit(set: (value: string) => void, value: string) {
    invalidate()
    setPending(null)
    set(value)
  }

  const disabled = busy || !authenticated

  async function run(work: (signal: AbortSignal) => Promise<InsightResult | EvidenceDocument | ModelSpend>, kind: 'result' | 'evidence' | 'spend') {
    controllerRef.current?.abort()
    const active = new AbortController()
    controllerRef.current = active
    const seq = ++sequenceRef.current
    setBusy(true)
    setError('')
    setEvidence(null)
    setSpend(null)
    if (kind === 'result') setResult(null)
    const timer = window.setTimeout(() => active.abort(), kind === 'result' ? 80000 : 18000)
    try {
      const value = await work(active.signal)
      if (disposedRef.current || seq !== sequenceRef.current) return
      if (kind === 'result') {
        const saved = value as InsightResult
        setResult(saved)
        setPending(null)
        setRunId(saved.record.id)
        setIncident(saved.record.incidentId)
        setQuestion(saved.record.question)
        setFrom(saved.record.queryWindow.from)
        setTo(saved.record.queryWindow.to)
        history.replaceState(null, '', `#/incidents/current-diagnose?runId=${saved.record.id}`)
      } else if (kind === 'spend') setSpend(value as ModelSpend)
      else setEvidence(value as EvidenceDocument)
    } catch (cause) {
      if (disposedRef.current || seq !== sequenceRef.current) return
      if (cause instanceof InsightRequestError && [401, 403, 410].includes(cause.status)) {
        setResult(null)
        setEvidence(null)
        setSpend(null)
      }
      setError(cause instanceof DOMException && cause.name === 'AbortError' ? '请求已超时，保存状态待确认；请用请求标识读取结果。' : cause instanceof Error ? cause.message : '请求失败')
    } finally {
      window.clearTimeout(timer)
      if (!disposedRef.current && seq === sequenceRef.current) setBusy(false)
    }
  }

  function start() {
    const request: DiagnosisRequest = {
      runId: crypto.randomUUID(),
      incidentId: incident.trim(),
      question: question.trim(),
      timeRange: { from, to },
      knowledgeMode: 'current',
    }
    setPending(request)
    setRunId(request.runId)
    history.replaceState(null, '', `#/incidents/current-diagnose?runId=${request.runId}`)
    void run(signal => diagnose(request, signal), 'result')
  }

  function read() {
    const id = runId.trim()
    void run(signal => getInsight(id, signal), 'result')
  }

  function viewEvidence(id: string) {
    const saved = result
    if (saved) void run(signal => getEvidence(id, saved.record, signal), 'evidence')
  }

  return (
    <section className="panel current-diagnosis" data-page="current-diagnosis">
      <PageHeader title="平台只读诊断" description="读取当前可见的 Incident 与所选窗口的 CPU 指标。采样窗口不代表当时已知的信息；引用关联通过校验也不代表根因成立。" />
      <PageBody>
      <div className="metric-controls">
        <label>Incident ID<Input value={incident} disabled={busy} onChange={e => edit(setIncident, e.currentTarget.value)} /></label>
        <label>采样开始（UTC）<Input value={from} disabled={busy} onChange={e => edit(setFrom, e.currentTarget.value)} /></label>
        <label>采样结束（UTC）<Input value={to} disabled={busy} onChange={e => edit(setTo, e.currentTarget.value)} /></label>
      </div>
      <label>诊断问题
        <Textarea value={question} disabled={busy} maxLength={2000} onChange={e => edit(setQuestion, e.currentTarget.value)} />
      </label>
      <p>窗口不超过 1 小时。每次最多读取 5 个关联资产，模型调用 1 次，证据读取与复核合计 4 次。</p>
      <div className="actions">
        <Button variant="default" disabled={disabled || !isUuid(incident.trim()) || pending !== null} onClick={start}>开始只读诊断</Button>
        <Button variant="outline" disabled={busy} onClick={() => { invalidate(); setPending(null); setRunId('') }}>准备新诊断</Button>
      </div>
      <label>结果请求标识<Input value={runId} disabled={busy} onChange={e => edit(setRunId, e.currentTarget.value)} /></label>
      <Button variant="outline" disabled={disabled || !isUuid(runId.trim())} onClick={read}>读取已保存结果</Button>
      <Button variant="outline" disabled={disabled || !isUuid(runId.trim())} onClick={() => {
        const id = runId.trim()
        const saved = result?.record
        void run(signal => getModelSpend(id, signal, saved), 'spend')
      }}>读取用量与费用</Button>
      {pending ? <p data-diagnosis-pending>请求已创建。出现超时或连接中断时，请先读取已保存结果；不会自动重试模型。刷新页面后可用同一请求标识查询。</p> : null}
      <p role="alert">{error}</p>
      {busy ? <p role="status">正在读取或诊断…</p> : null}
      {spend ? (
        <section data-model-spend>
          <h3>模型用量与费用</h3>
          <p>{`${spend.record.policy.provider} / ${spend.record.policy.model} · ${spend.storage} · 费率版本 ${spend.record.policy.priceVersion}`}</p>
          <p>金额为配置费率估算，并非提供方账单。用量记录不代表诊断成功。</p>
          {spend.record.state === 'REPORTED' ? (
            <>
              <p>{`已上报 · 输入 ${spend.record.usage?.inputTokens} Token · 输出 ${spend.record.usage?.outputTokens} Token · 缓存输入 ${spend.record.usage?.cachedInputTokens} Token`}</p>
              <p>{`估算费用 ${dollars(spend.record.estimatedMicros ?? 0)}`}</p>
            </>
          ) : null}
          {spend.record.state !== 'REPORTED' ? <p>费用待确认：尚无可核验用量，预留额度继续占用，不会自动退款或重试。</p> : null}
          <p>{`预留 ${dollars(spend.record.reservedMicros ?? 0)} · 当前占用 ${dollars(spend.record.accountedMicros ?? 0)}`}</p>
          {spend.record.policy.provider === 'mock-deterministic' ? <p>显式 mock：未调用外部模型，Token 和费用均为零。</p> : null}
        </section>
      ) : null}
      {result ? (
        <section data-insight-result>
          <h3>诊断结果</h3>
          <p><strong>{`${result.record.model.provider} / ${result.record.model.name}`}</strong>{` · ${result.storage} · ${result.record.dataModes.join(', ')}`}</p>
          {result.record.model.provider === 'mock-deterministic' ? <p className="notice">这是确定性 mock 输出，用于本地链路验证，不是实际模型推理。</p> : null}
          <p>{`Incident ${result.record.incidentId} · 版本 ${result.record.incidentVersion} · 租户 ${result.record.tenantId}`}</p>
          <p>{`采样窗口 ${result.record.queryWindow.from} 至 ${result.record.queryWindow.to}`}</p>
          <p>{`知识截止 ${result.record.asOf} · 保存 ${result.record.savedAt} · 有效至 ${result.record.expiresAt}`}</p>
          <p>{`Skill ${result.record.skill.id}@${result.record.skill.version}`}</p>
          <code>{result.record.skill.digest}</code>
          <p>{result.record.question}</p>
          <p className="insight-summary">{result.record.insight.summary}</p>
          {result.record.insight.findings.map((finding, index) => (
            <article key={`${finding.kind}-${index}`} className="incident-problem">
              <h4>{finding.kind === 'observation' ? '观察' : '待验证假设'}</h4>
              <p>{finding.statement}</p>
              <div className="actions">
                {finding.evidenceRefs.map(ref => (
                  <Button key={ref} variant="outline" disabled={disabled} onClick={() => viewEvidence(ref)}>{`查看证据 ${ref}`}</Button>
                ))}
              </div>
            </article>
          ))}
          <h4>数据缺口</h4>
          <ul>{result.record.insight.missingData.map(row => <li key={row}>{row}</li>)}</ul>
          <h4>限制</h4>
          <ul>{result.record.insight.limitations.map(row => <li key={row}>{row}</li>)}</ul>
          <h4>证据快照</h4>
          <div className="actions">
            {result.record.evidenceIds.map(row => (
              <Button key={row} variant="outline" disabled={disabled} onClick={() => viewEvidence(row)}>{`读取快照 ${row}`}</Button>
            ))}
          </div>
        </section>
      ) : null}
      {evidence ? (
        <section data-insight-evidence>
          <h3>{`证据：${evidence.evidence.kind}`}</h3>
          <p>{evidence.evidence.summary}</p>
          <p>{`${evidence.producerTool} · ${evidence.dataModes.join(', ')}`}</p>
          <p>{`可见 ${evidence.evidence.availableAt} · 观测 ${evidence.evidence.observedAt} · 过期 ${evidence.evidence.expiresAt}`}</p>
          <p>{evidence.warnings.join(' · ')}</p>
          <pre>{JSON.stringify(evidence.data, null, 2)}</pre>
        </section>
      ) : null}
      </PageBody>
    </section>
  )
}
