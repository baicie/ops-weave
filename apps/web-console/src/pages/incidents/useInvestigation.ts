import { useEffect, useRef, useState } from 'react'
import { usePlatformSession } from '../../state/platform-session.ts'
import { usePageActive, usePageCloseGuard } from '../../state/page-workspace.ts'
import { listAiRuns, readAiRun, requireRunCommand, submitAiRun, AiRunError, type AiRun, type AiRunSubmit } from '../../api/ai-runs.ts'
import { CURRENT_DIAGNOSIS_SKILL } from '../../api/current-skill.ts'
import { diagnose, getEvidence, getInsight, InsightRequestError, parseInsight, validateDiagnosis, type DiagnosisRequest, type EvidenceDocument, type InsightResult } from '../../api/insights.ts'
import { getModelSpend, type ModelSpend } from '../../api/model-spend.ts'

const defaultQuestion = '请基于当前 Incident 和 CPU 指标总结观察、待验证假设与数据缺口。'
const utc = (offset: number) => new Date(Math.floor((Date.now() + offset) / 1000) * 1000).toISOString()
type Mode = 'durable' | 'synchronous'
type Fields = { incident: string; question: string; from: string; to: string }

/** Request lifetime and authority live here; the workspace view has no network side effects. */
export function useInvestigation() {
  const query = new URLSearchParams(location.hash.split('?')[1])
  const [fields, setFields] = useState<Fields>(() => ({ incident: query.get('incidentId') ?? '', question: defaultQuestion, from: utc(-900000), to: utc(0) }))
  const [mode, setMode] = useState<Mode>('durable')
  const [runLookup, setRunLookup] = useState(query.get('aiRunId') ?? '')
  const [insightLookup, setInsightLookup] = useState(query.get('runId') ?? '')
  const [activeRun, setActiveRun] = useState<AiRun | null>(null)
  const [result, setResult] = useState<InsightResult | null>(null)
  const [evidence, setEvidence] = useState<EvidenceDocument | null>(null)
  const [spend, setSpend] = useState<ModelSpend | null>(null)
  const [pending, setPending] = useState<DiagnosisRequest | null>(null)
  const [pendingSubmit, setPendingSubmit] = useState<AiRunSubmit | null>(null)
  const [unknown, setUnknown] = useState(false)
  const [canRetryOriginal, setCanRetryOriginal] = useState(false)
  const [busy, setBusy] = useState(false)
  const [stale, setStale] = useState(false)
  const [error, setError] = useState('')
  const [forbidden, setForbidden] = useState(false)
  const request = useRef<{ controller: AbortController; sequence: number } | null>(null)
  const sequence = useRef(0)
  const mounted = useRef(true)
  const sessionSeen = useRef(false)
  const resultRef = useRef(result)
  const evidenceRef = useRef(evidence)
  resultRef.current = result
  evidenceRef.current = evidence
  const active = usePageActive()

  function abort() { ++sequence.current; request.current?.controller.abort(); request.current = null; setBusy(false) }
  function clearPrivate() { setResult(null); setEvidence(null); setSpend(null); setActiveRun(null); setStale(false) }
  const authenticated = usePlatformSession(change => {
    abort(); clearPrivate(); setPending(null); setPendingSubmit(null); setUnknown(false); setCanRetryOriginal(false)
    if (sessionSeen.current) {
      setFields({ incident: '', question: defaultQuestion, from: utc(-900000), to: utc(0) })
      setRunLookup(''); setInsightLookup('')
      if (active && location.hash.split('?')[0] === '#/incidents/current-diagnose') history.replaceState(null, '', '#/incidents/current-diagnose')
    } else setFields(value => ({ ...value, question: defaultQuestion }))
    setError(change.error?.message ?? '')
    setForbidden(change.reason === 'forbidden' || change.reason === 'unauthenticated')
  })
  useEffect(() => { if (authenticated) sessionSeen.current = true }, [authenticated])
  usePageCloseGuard(pendingSubmit || pending ? { message: '调查提交或保存结果待确认。请先按原标识回读，关闭页面会丢失内存中的原请求。', blocked: true } : null)
  useEffect(() => { if (!active) abort() }, [active])
  useEffect(() => {
    mounted.current = true
    const timer = window.setInterval(() => {
      if (resultRef.current && Date.parse(resultRef.current.record.expiresAt) <= Date.now()) {
        abort(); setResult(null); setEvidence(null); setSpend(null); setStale(true)
        setError('结果或证据已过期，请重新读取或准备新调查。')
      } else if (evidenceRef.current && Date.parse(evidenceRef.current.evidence.expiresAt) <= Date.now()) {
        setEvidence(null); setError('证据快照已过期，请重新授权读取。')
      }
    }, 1000)
    return () => { mounted.current = false; ++sequence.current; request.current?.controller.abort(); window.clearInterval(timer) }
  }, [])

  async function perform<T>(work: (signal: AbortSignal) => Promise<T>, accept: (value: T) => void,
    mutation: 'durable' | 'sync' | null = null) {
    if (request.current || !authenticated || !active) return
    const controller = new AbortController(), current = ++sequence.current
    request.current = { controller, sequence: current }; setBusy(true); setError(''); setForbidden(false)
    if (mutation) { setUnknown(true); setCanRetryOriginal(false) }
    const timer = window.setTimeout(() => controller.abort(), mutation === 'sync' ? 80000 : 25000)
    try {
      const value = await work(controller.signal)
      if (!mounted.current || current !== sequence.current) return
      accept(value)
    } catch (cause) {
      if (!mounted.current || current !== sequence.current) return
      const knownRejection = (cause instanceof AiRunError || cause instanceof InsightRequestError) && cause.status >= 400 && cause.status < 500
      if (mutation && knownRejection) { setUnknown(false); setPendingSubmit(null); setPending(null) }
      if (cause instanceof InsightRequestError && cause.status === 410) { setResult(null); setEvidence(null); setSpend(null); setStale(true) }
      const message = cause instanceof DOMException && cause.name === 'AbortError' ? '请求已中断或超时' : cause instanceof Error ? cause.message : '请求失败'
      setError(mutation && !knownRejection ? `${message}。保存状态待确认（UNKNOWN）；先查询原请求，不会自动重试。` : message)
    } finally {
      window.clearTimeout(timer)
      if (mounted.current && current === sequence.current) { request.current = null; setBusy(false) }
    }
  }

  function acceptRun(run: AiRun) {
    setActiveRun(run); setPendingSubmit(null); setUnknown(false); setCanRetryOriginal(false); setRunLookup(run.runId); setInsightLookup(run.runId)
    setFields({ incident: run.incidentId, question: run.question, ...run.timeRange })
    history.replaceState(null, '', `#/incidents/current-diagnose?aiRunId=${run.runId}&runId=${run.runId}`)
  }
  function acceptResult(saved: InsightResult) {
    setResult(saved); setEvidence(null); setSpend(null); setPending(null); setUnknown(false); setStale(false); setInsightLookup(saved.record.id)
    setFields({ incident: saved.record.incidentId, question: saved.record.question, ...saved.record.queryWindow })
    history.replaceState(null, '', `#/incidents/current-diagnose?${activeRun ? `aiRunId=${activeRun.runId}&` : ''}runId=${saved.record.id}`)
  }
  function start() {
    if (busy || pending || pendingSubmit || activeRun || result || !authenticated) return
    const command: DiagnosisRequest = { runId: crypto.randomUUID(), incidentId: fields.incident.trim(), question: fields.question.trim(),
      timeRange: { from: fields.from, to: fields.to }, knowledgeMode: 'current' }
    try { validateDiagnosis(command) } catch { setError('请提供有效 Incident、非空问题和不超过 1 小时的完整 UTC 窗口，结束时间不能晚于当前时间。'); return }
    clearPrivate()
    if (mode === 'synchronous') {
      setPending(command); setInsightLookup(command.runId)
      history.replaceState(null, '', `#/incidents/current-diagnose?runId=${command.runId}`)
      void perform(signal => diagnose(command, signal), acceptResult, 'sync')
    } else {
      const body: AiRunSubmit = Object.freeze({ requestId: command.runId, incidentId: command.incidentId, question: command.question,
        timeRange: Object.freeze({ ...command.timeRange }), knowledgeMode: 'current', skill: CURRENT_DIAGNOSIS_SKILL })
      setPendingSubmit(body)
      void perform(signal => submitAiRun(body, signal), acceptRun, 'durable')
    }
  }
  function readRun() {
    if (pending) { setError('同步结果保存状态待确认，请先按原结果请求标识回读；不能用其他运行替换原请求。'); return }
    const expected = pendingSubmit ?? activeRun
    void perform(async signal => {
      const run = await readAiRun(runLookup.trim(), signal)
      if (expected) requireRunCommand(run, expected)
      if (activeRun?.runId === run.runId) {
        if (run.commandDigest !== activeRun.commandDigest || run.createdAt !== activeRun.createdAt) throw new Error('运行的不可变审计依据发生变化。')
        if (run.statusVersion < activeRun.statusVersion || Date.parse(run.updatedAt) < Date.parse(activeRun.updatedAt)) throw new Error('运行响应版本倒退，请重新读取。')
        if (['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(activeRun.status) && run.status !== activeRun.status) throw new Error('运行终态不能再次迁移。')
      }
      return run
    }, run => { setResult(null); setEvidence(null); setSpend(null); acceptRun(run) })
  }
  function readResult() {
    if (pendingSubmit) { setError('请先确认原提交对应的 AIRun，再读取报告。'); return }
    if (activeRun && activeRun.status !== 'SUCCEEDED') { setError('运行尚未成功，请先读取运行进度。'); return }
    setEvidence(null); setSpend(null); setResult(null)
    void perform(async signal => {
      const id = pending?.runId ?? activeRun?.runId ?? insightLookup.trim()
      const saved = await getInsight(id, signal)
      if (activeRun) {
        parseInsight(saved, activeRun.runId, { ...activeRun, runId: activeRun.runId })
        if (saved.record.skill.digest !== activeRun.skill.digest) throw new Error('报告与运行的固定 Skill 不一致。')
      } else if (pending) parseInsight(saved, pending.runId, pending)
      return saved
    }, acceptResult)
  }
  function recoverSubmission() {
    const original = pendingSubmit
    if (!original || busy) return
    void perform(async signal => {
      let cursor: string | undefined
      const seen = new Set<string>()
      for (let page = 0; page < 5; page++) {
        const response = await listAiRuns(signal, cursor)
        const found = response.items.find(run => run.requestId === original.requestId)
        if (found) { requireRunCommand(found, original); return found }
        if (!response.hasMore) break
        if (!response.nextCursor || seen.has(response.nextCursor)) throw new Error('运行分页游标重复。')
        seen.add(response.nextCursor); cursor = response.nextCursor
      }
      return null
    }, found => {
      if (found) acceptRun(found)
      else { setCanRetryOriginal(true); setError('本次在当前身份可见的最近最多 100 条记录中未找到原请求；这不能证明未受理。可显式原样重放同一请求，服务端按原标识幂等处理。') }
    })
  }
  function retryOriginal() {
    if (pendingSubmit && unknown && canRetryOriginal) void perform(signal => submitAiRun(pendingSubmit, signal), acceptRun, 'durable')
  }
  function reset() {
    if (pendingSubmit || pending || busy) return
    abort(); clearPrivate(); setRunLookup(''); setInsightLookup(''); setUnknown(false); setCanRetryOriginal(false); setError('')
    history.replaceState(null, '', '#/incidents/current-diagnose')
  }
  function editField(field: keyof Fields, value: string) {
    if (pendingSubmit || pending || busy) return
    clearPrivate(); setError(''); setFields(previous => ({ ...previous, [field]: value }))
  }
  function editLookup(kind: 'run' | 'insight', value: string) {
    if (pendingSubmit || pending || busy) return
    clearPrivate(); setError(''); (kind === 'run' ? setRunLookup : setInsightLookup)(value)
  }
  return {
    ...fields, mode, runLookup, insightLookup, activeRun, result, evidence, spend, pending, pendingSubmit, busy, error, authenticated, unknown, canRetryOriginal, stale, forbidden,
    onFieldChange: editField, onLookupChange: editLookup, onStart: start, onReadRun: readRun, onReadResult: readResult,
    onReset: reset, onRecoverSubmission: recoverSubmission, onRetryOriginal: retryOriginal,
    onModeChange: (value: Mode) => { if (!pendingSubmit && !pending && !busy) { reset(); setMode(value) } },
    onReadSpend: () => { const id = activeRun?.runId ?? insightLookup.trim(); setSpend(null); void perform(signal => getModelSpend(id, signal, result?.record), setSpend) },
    onViewEvidence: (id: string) => { if (result) { setEvidence(null); void perform(signal => getEvidence(id, result.record, signal), setEvidence) } },
  }
}
