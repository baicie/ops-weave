import { useEffect, useRef, useState } from 'react'
import { usePlatformSession } from '../../state/platform-session.ts'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { getIncident, IncidentRequestError, type IncidentDetail } from '../../api/incidents.ts'
import { reorganize, getReorganization, listReorganizations, keyOf, uuid, ReorganizationError,
  type ProblemKey, type ReorganizationRequest, type ReorganizationResult, type ReorganizationPage as HistoryPage } from '../../api/reorganizations.ts'

export function ReorganizationPage() {
  const params = new URLSearchParams(location.hash.split('?')[1])
  const [sourceId, setSourceId] = useState(params.get('incidentId') ?? '')
  const [targetId, setTargetId] = useState('')
  const [kind, setKind] = useState<'MERGE' | 'SPLIT'>('MERGE')
  const [source, setSource] = useState<IncidentDetail | null>(null)
  const [target, setTarget] = useState<IncidentDetail | null>(null)
  const [selected, setSelected] = useState<ProblemKey[]>([])
  const [title, setTitle] = useState('')
  const [reason, setReason] = useState('')
  const [review, setReview] = useState<ReorganizationRequest | null>(null)
  const [pending, setPending] = useState(false)
  const [receipt, setReceipt] = useState<ReorganizationResult | null>(null)
  const [historyPage, setHistoryPage] = useState<HistoryPage | null>(null)
  const [requestKey, setRequestKey] = useState(params.get('requestKey') ?? '')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const activeRef = useRef<AbortController | undefined>(undefined)
  const sequenceRef = useRef(0)
  const disposedRef = useRef(false)

  function clear() {
    setSource(null)
    setTarget(null)
    setSelected([])
    setReview(null)
    setReceipt(null)
    setHistoryPage(null)
    setPending(false)
  }

  const authenticated = usePlatformSession(change => {
    ++sequenceRef.current
    activeRef.current?.abort()
    setBusy(false)
    setError('')
    clear()
    setTitle('')
    setReason('')
    if (change.error) setError(change.error.message)
  })

  useEffect(() => () => {
    disposedRef.current = true
    ++sequenceRef.current
    activeRef.current?.abort()
  }, [])

  const disabled = busy || !authenticated
  const editingDisabled = busy || pending

  function edit(set: (value: string) => void, value: string) {
    setReview(null)
    setReceipt(null)
    set(set === setSourceId ? value.trim() : value)
    if (set === setSourceId) { setSource(null); setSelected([]); setHistoryPage(null) }
    if (set === setTargetId) setTarget(null)
  }

  async function run(work: (signal: AbortSignal, current: () => boolean) => Promise<void>) {
    activeRef.current?.abort()
    const controller = new AbortController()
    activeRef.current = controller
    const seq = ++sequenceRef.current
    const current = () => !disposedRef.current && seq === sequenceRef.current
    const timer = window.setTimeout(() => controller.abort(), 20000)
    setBusy(true)
    setError('')
    try {
      await work(controller.signal, current)
    } catch (cause) {
      if (!current()) return
      if ((cause instanceof IncidentRequestError || cause instanceof ReorganizationError) && [401, 403].includes(cause.status)) clear()
      if (cause instanceof ReorganizationError && cause.status === 409) {
        setSource(null)
        setTarget(null)
        setReview(null)
        setPending(false)
      }
      setError(cause instanceof DOMException && cause.name === 'AbortError' ? '请求超时；若已提交，请先读取关联记录确认结果。' : cause instanceof Error ? cause.message : '请求失败')
    } finally {
      window.clearTimeout(timer)
      if (current()) setBusy(false)
    }
  }

  function load(which: 'source' | 'target', overrideId?: string) {
    setReview(null)
    setReceipt(null)
    const id = overrideId ?? (which === 'source' ? sourceId : targetId)
    if (which === 'source') setSource(null)
    else setTarget(null)
    void run(async (signal, current) => {
      const value = await getIncident(id, signal)
      if (current()) {
        if (which === 'source') { setSource(value); setSelected([]) }
        else setTarget(value)
      }
    })
  }

  function preview() {
    setError('')
    const s = source
    const t = target
    if (!s || s.record.organization?.mergedInto || s.record.incident.status === 'CLOSED') { setError('请读取可调整的来源 Incident'); return }
    if (!reason.trim() || reason.trim().length > 500) { setError('请填写 1–500 字的人工调整原因'); return }
    if (kind === 'MERGE' && (!t || t.record.incident.id === s.record.incident.id || t.record.incident.tenantId !== s.record.incident.tenantId
      || t.record.organization?.mergedInto || !['OPEN', 'INVESTIGATING'].includes(t.record.incident.status))) { setError('请选择同租户、未合并且处于 OPEN / INVESTIGATING 的目标'); return }
    if (kind === 'SPLIT' && (!title.trim() || title.trim().length > 300 || selected.length === 0 || selected.length >= s.record.problems.length)) { setError('请填写 1–300 字的新标题并选择部分问题；来源至少保留一个问题'); return }
    const input: ReorganizationRequest = {
      requestKey: crypto.randomUUID(),
      kind,
      sourceIncidentId: s.record.incident.id,
      expectedSourceVersion: s.record.incident.version,
      targetIncidentId: kind === 'MERGE' ? t!.record.incident.id : crypto.randomUUID(),
      expectedTargetVersion: kind === 'MERGE' ? t!.record.incident.version : 0,
      problemKeys: kind === 'SPLIT' ? [...selected] : [],
      title: kind === 'SPLIT' ? title.trim() : null,
      reason: reason.trim(),
    }
    setReview(input)
    setRequestKey(input.requestKey)
    setReceipt(null)
  }

  function save() {
    const input = review
    if (!input) return
    setPending(true)
    window.history.replaceState(null, '', `#/incidents/reorganize?incidentId=${input.sourceIncidentId}&requestKey=${input.requestKey}`)
    void run(async (signal, current) => {
      const result = await reorganize(input, signal)
      if (current()) finish(result)
    })
  }

  function finish(value: ReorganizationResult) {
    setReceipt(value)
    setPending(false)
    setReview(null)
    setSource(null)
    setTarget(null)
    setHistoryPage(null)
    setSelected([])
    setRequestKey(value.change.request.requestKey)
  }

  function readResult() {
    const key = requestKey
    void run(async (signal, current) => {
      const value = await getReorganization(key, signal)
      if (current()) finish(value)
    })
  }

  function history(next: boolean) {
    const id = sourceId
    const after = next ? historyPage?.nextCursor ?? null : null
    void run(async (signal, current) => {
      const value = await listReorganizations(id, after, signal)
      if (current()) setHistoryPage(value)
    })
  }

  function toggle(key: ProblemKey, checked: boolean) {
    setReview(null)
    setSelected(checked ? [...selected, key] : selected.filter(k => keyOf(k) !== keyOf(key)))
  }

  return (
    <section className="panel" data-page="reorganization">
      <h2>人工调整 Incident 归属</h2>
      <p>合并将问题的后续观测交给目标，来源保留历史快照；拆分将选中问题移入新的 OPEN Incident。关联版本变化会使旧诊断证据失效。</p>
      <label>来源 Incident ID<Input value={sourceId} disabled={editingDisabled} onChange={e => edit(setSourceId, e.currentTarget.value)} /></label>
      <div className="actions">
        <Button variant="outline" disabled={disabled || pending || !uuid(sourceId)} onClick={() => load('source')}>读取来源 Incident</Button>
        <Button variant="outline" disabled={disabled || !uuid(sourceId)} onClick={() => history(false)}>读取关联历史</Button>
      </div>
      {source ? (
        <section data-reorganization-source>
          <h3>{source.record.incident.title}</h3>
          <p>{`${source.record.incident.status} · 版本 ${source.record.incident.version} · ${source.storage}`}</p>
          {source.record.organization?.mergedInto ? <p>{`已合并至 ${source.record.organization?.mergedInto}`}</p> : null}
          <p>{`问题 ${source.record.problems.length} · 来源 ${source.record.problems.map(p => `${p.observation.sourceInstanceId}/${p.dataMode}`).join(', ')}`}</p>
        </section>
      ) : null}
      <label>调整方式
        <select value={kind} disabled={editingDisabled} onChange={e => {
          setKind(e.target.value as 'MERGE' | 'SPLIT')
          setReview(null)
          setSelected([])
          setTarget(null)
        }}>
          <option value="MERGE">合并到现有 Incident</option>
          <option value="SPLIT">拆分到新 Incident</option>
        </select>
      </label>
      {kind === 'MERGE' ? (
        <>
          <label>目标 Incident ID<Input value={targetId} disabled={editingDisabled} onChange={e => edit(setTargetId, e.currentTarget.value)} /></label>
          <Button variant="outline" disabled={disabled || pending || !uuid(targetId)} onClick={() => load('target')}>读取目标 Incident</Button>
          {target ? (
            <p data-reorganization-target>{`${target.record.incident.title} · ${target.record.incident.status} · 版本 ${target.record.incident.version} · 问题 ${target.record.problems.length}`}</p>
          ) : null}
        </>
      ) : null}
      {kind === 'SPLIT' ? (
        <>
          <label>新 Incident 标题<Input value={title} disabled={editingDisabled} onChange={e => edit(setTitle, e.currentTarget.value)} /></label>
          <fieldset>
            <legend>选择要移出的外部问题</legend>
            {source?.record.problems.map(problem => {
              const key = `${problem.observation.sourceInstanceId}:${problem.observation.problemEventId}`
              return (
                <label key={key} className="incident-link">
                  <input
                    type="checkbox"
                    disabled={editingDisabled}
                    checked={selected.some(k => keyOf(k) === key)}
                    onChange={e => toggle(
                      { sourceInstanceId: problem.observation.sourceInstanceId, problemEventId: problem.observation.problemEventId },
                      e.target.checked,
                    )}
                  />
                  {`${problem.observation.sourceInstanceId}:${problem.observation.problemEventId} · ${problem.observation.title} · ${problem.dataMode}`}
                </label>
              )
            }) ?? null}
          </fieldset>
        </>
      ) : null}
      <label>人工调整原因<Input value={reason} disabled={editingDisabled} onChange={e => edit(setReason, e.currentTarget.value)} /></label>
      <Button variant="default" disabled={disabled || pending || !source} onClick={preview}>预览关联调整</Button>
      {review ? (
        <section data-reorganization-review>
          <h3>待确认调整</h3>
          <p>{`${review.kind} · 来源 ${review.sourceIncidentId}（版本 ${review.expectedSourceVersion}） → 目标 ${review.targetIncidentId}（版本 ${review.expectedTargetVersion}）`}</p>
          <p>{`移出问题：${review.kind === 'MERGE' ? '来源全部问题' : review.problemKeys.map(keyOf).join(', ')} · ${review.reason}`}</p>
          <p>这会改变问题的后续入库归属，并使变更前的诊断证据不可继续读取。不会宣告来源问题已恢复。</p>
          <Button variant="default" disabled={disabled} onClick={save}>{pending ? '重试同一关联请求' : '确认关联调整'}</Button>
        </section>
      ) : null}
      {pending ? <p data-reorganization-pending>提交结果待确认。可以用相同请求标识重试，或先读取关联记录；刷新页面保留请求标识，Token 需要重新输入。</p> : null}
      <label>关联请求标识<Input value={requestKey} disabled={editingDisabled} onChange={e => setRequestKey(e.currentTarget.value)} /></label>
      <Button variant="outline" disabled={disabled || !uuid(requestKey)} onClick={readResult}>读取关联记录</Button>
      <p role="alert">{error}</p>
      {busy ? <p role="status">正在请求…</p> : null}
      {receipt ? (
        <section data-reorganization-result>
          <h3>已保存关联调整</h3>
          <p>{`${receipt.change.request.kind} · ${receipt.storage} · ${receipt.change.actor} · ${receipt.change.occurredAt}`}</p>
          <p>{`${receipt.change.request.sourceIncidentId}（版本 ${receipt.change.sourceVersion}） → ${receipt.change.request.targetIncidentId}（版本 ${receipt.change.targetVersion}）`}</p>
          <p>{receipt.change.request.reason}</p>
          <p>{receipt.change.movedProblems.map(keyOf).join(', ')}</p>
          <Button variant="outline" disabled={disabled} onClick={() => {
            const id = receipt.change.request.targetIncidentId
            if (id) { setSourceId(id); setHistoryPage(null); load('source', id) }
          }}>读取调整后的目标</Button>
        </section>
      ) : null}
      {historyPage ? (
        <section data-reorganization-history>
          <h3>关联调整历史</h3>
          <p>{`${historyPage.storage} · 本页 ${historyPage.items.length} 条`}</p>
          {historyPage.items.map(item => (
            <article key={item.request.requestKey} className="incident-problem">
              <p>{`${item.request.kind} · ${item.actor} · ${item.occurredAt}`}</p>
              <p>{item.request.reason}</p>
              <p>{`${item.request.sourceIncidentId} → ${item.request.targetIncidentId}`}</p>
              <p>{`请求 ${item.request.requestKey}`}</p>
            </article>
          ))}
          <Button variant="outline" disabled={disabled || !historyPage.nextCursor} onClick={() => history(true)}>下一页关联历史</Button>
        </section>
      ) : null}
    </section>
  )
}
