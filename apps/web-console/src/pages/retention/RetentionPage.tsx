import { useEffect, useRef, useState } from 'react'
import { usePageCloseGuard } from '../../state/page-workspace.ts'
import { usePlatformSession } from '../../state/platform-session.ts'
import { Button } from '@/components/ui/button'
import { PageHeader, PageBody } from '../../components/PageLayout.tsx'
import { Input } from '@/components/ui/input'
import { isUuid } from '../../api/insights.ts'
import { applyRetention, getRetentionPreview, getRetentionReceipt, RetentionError, type RetentionReceipt, type RetentionView } from '../../api/ai-retention.ts'

const labels = { INSIGHT: '诊断结果正文', EVIDENCE: '证据正文', AUDIT: 'Tool 读取审计' }
export function RetentionPage() {
  const [review, setReview] = useState<RetentionView | null>(null)
  const [receipt, setReceipt] = useState<RetentionReceipt | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [pending, setPending] = useState(false)
  const [confirmed, setConfirmed] = useState(false)
  const [requestId, setRequestId] = useState(new URLSearchParams(location.hash.split('?')[1]).get('requestId') ?? '')
  const [now, setNow] = useState(Date.now())
  const activeRef = useRef<AbortController | undefined>(undefined)
  const sequenceRef = useRef(0)
  const disposedRef = useRef(false)

  const ready = usePlatformSession(change => {
    ++sequenceRef.current
    activeRef.current?.abort()
    setBusy(false)
    setReview(null)
    setReceipt(null)
    setConfirmed(false)
    setPending(false)
    setError(change.error?.message ?? '')
  })

  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 1000)
    return () => {
      disposedRef.current = true
      ++sequenceRef.current
      activeRef.current?.abort()
      window.clearInterval(timer)
    }
  }, [])

  const disabled = busy || !ready
  usePageCloseGuard(busy || pending ? { message: pending ? '清理结果待确认，请先查询原请求回执。' : '留存请求正在处理，请等待结果后关闭。', blocked: true } : null)

  async function run(work: (signal: AbortSignal, current: () => boolean) => Promise<void>) {
    activeRef.current?.abort()
    const controller = new AbortController()
    activeRef.current = controller
    const seq = ++sequenceRef.current
    const current = () => !disposedRef.current && seq === sequenceRef.current
    setBusy(true)
    setError('')
    try {
      await work(controller.signal, current)
    } catch (e) {
      if (!current()) return
      if (e instanceof RetentionError && [400, 401, 403, 409, 410].includes(e.status)) {
        setReview(null)
        setConfirmed(false)
        setPending(false)
        setReceipt(null)
      }
      setError(e instanceof Error ? e.message : '请求失败，提交结果待确认')
    } finally {
      if (current()) setBusy(false)
    }
  }

  function load() {
    setReview(null)
    setReceipt(null)
    setConfirmed(false)
    setPending(false)
    void run(async (signal, current) => {
      const v = await getRetentionPreview(signal)
      if (current()) { setReview(v); setNow(Date.now()) }
    })
  }

  function finish(v: RetentionReceipt) {
    setReceipt(v)
    setReview(null)
    setPending(false)
    setConfirmed(false)
    setRequestId(v.command.requestId)
  }

  function apply() {
    const r = review
    if (!r || !confirmed || !r.policy.allowPurge || Date.now() >= Date.parse(r.preview.expiresAt) || pending) return
    const body = { requestId: crypto.randomUUID(), policyDigest: r.preview.policyDigest, asOf: r.preview.asOf, previewDigest: r.preview.previewDigest }
    setRequestId(body.requestId)
    setPending(true)
    setConfirmed(false)
    window.history.replaceState(null, '', `#/ai/retention?requestId=${body.requestId}`)
    void run(async (signal, current) => {
      const v = await applyRetention(body, signal)
      if (current()) finish(v)
    })
  }

  function read() {
    const id = requestId
    void run(async (signal, current) => {
      const v = await getRetentionReceipt(id, signal)
      if (current()) finish(v)
    })
  }

  return (
    <section className="panel" data-page="retention">
      <PageHeader title="AI 数据留存" description="清理已过期的诊断、证据正文和读取审计。先预览数量，再确认清理。" actions={<Button variant="outline" disabled={disabled || pending} onClick={load}>预览留存清理</Button>} />
      <PageBody className="console-maintenance">
      <details className="retention-policy-note"><summary>留存规则</summary><p>策略由管理员配置。运行标识、关联元数据与费用记录继续保留；数据库备份和空间回收另行管理。</p></details>
      {review ? (
        <section data-retention-preview>
          <h3>待确认清理</h3>
          <p>仅清理下方这批内容，清除后无法通过本页面恢复。</p>
          <p>{`租户 ${review.policy.tenantId} · 操作者 ${review.preview.actor} · 策略 ${review.policy.version}`}</p>
          <p>{`结果 ${review.policy.insightDays} 天 · 证据 ${review.policy.evidenceDays} 天 · 审计 ${review.policy.auditDays} 天 · 保留 Incident ${review.policy.heldIncidents.length} 个`}</p>
          <ul>
            {review.preview.batches.map(row => (
              <li key={row.kind}>{`${labels[row.kind]}：${row.ids.length} 条 · 正文 ${row.logicalBytes} 字节${row.hasMore ? ' · 尚有后续批次' : ''}`}</li>
            ))}
          </ul>
          <p>{`预览有效至 ${review.preview.expiresAt}`}</p>
          {!review.policy.allowPurge ? <p>当前策略仅允许预览。</p> : null}
          {now >= Date.parse(review.preview.expiresAt) ? <p role="status">预览已过期，请重新预览。</p> : null}
          <label>
            <input
              type="checkbox"
              checked={confirmed}
              disabled={disabled || pending || !review.policy.allowPurge}
              onChange={e => setConfirmed(e.target.checked)}
            />
            我已核对策略与数量，确认清除本批内容
          </label>
          <Button
            variant="default"
            disabled={disabled || pending || !confirmed || !review.policy.allowPurge || now >= Date.parse(review.preview.expiresAt)}
            onClick={apply}
          >
            确认清理本批内容
          </Button>
        </section>
      ) : null}
      {pending ? <p data-retention-pending>提交结果待确认；请先查询原请求回执，不会自动重试或执行下一批。</p> : null}
      <details className="retention-receipt-lookup" open={pending || !!requestId}><summary>查询已有清理回执</summary>
      <label>清理请求标识<Input value={requestId} disabled={busy} onChange={e => setRequestId(e.currentTarget.value.trim())} /></label>
      <Button variant="outline" disabled={disabled || !isUuid(requestId)} onClick={read}>查询清理回执</Button>
      </details>
      <p role="alert">{error}</p>
      {busy ? <p role="status">正在请求…</p> : null}
      {receipt ? (
        <section data-retention-receipt>
          <h3>本批清理已完成</h3>
          <p>{`${receipt.tenantId} · ${receipt.actor} · ${receipt.completedAt}`}</p>
          <ul>
            {receipt.preview.batches.map(row => (
              <li key={row.kind}>{`${labels[row.kind]}：${row.ids.length} 条 · 正文 ${row.logicalBytes} 字节${row.hasMore ? ' · 尚有后续批次' : ''}`}</li>
            ))}
          </ul>
          <p>需要继续处理时，请重新预览下一批。</p>
        </section>
      ) : null}
      </PageBody>
    </section>
  )
}
