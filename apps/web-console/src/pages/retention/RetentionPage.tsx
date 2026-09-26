import { createSignal, For, onCleanup, Show } from '@zeus-js/zeus'
import { usePlatformSession } from '../../state/platform-session.ts'
import { forItem } from '../../adapters/zeus-ui/for-item.ts'
import { ZwButton } from '../../adapters/zeus-ui/ZwButton.tsx'
import { ZwInput } from '../../adapters/zeus-ui/ZwInput.tsx'
import { isUuid } from '../../api/insights.ts'
import { applyRetention, getRetentionPreview, getRetentionReceipt, RetentionError, type RetentionReceipt, type RetentionView } from '../../api/ai-retention.ts'

const labels = { INSIGHT: '诊断结果正文', EVIDENCE: '证据正文', AUDIT: 'Tool 读取审计' }
export function RetentionPage() {
  const [review, setReview] = createSignal<RetentionView | null>(null), [receipt, setReceipt] = createSignal<RetentionReceipt | null>(null)
  const [busy, setBusy] = createSignal(false), [error, setError] = createSignal(''), [pending, setPending] = createSignal(false), [confirmed, setConfirmed] = createSignal(false)
  const [requestId, setRequestId] = createSignal(new URLSearchParams(location.hash.split('?')[1]).get('requestId') ?? ''), [now, setNow] = createSignal(Date.now())
  let active: AbortController | undefined; let sequence = 0; let disposed = false
  const ready = usePlatformSession(change => { ++sequence; active?.abort(); setBusy(false); setReview(null); setReceipt(null); setConfirmed(false); setPending(false); setError(change.error?.message ?? '') })
  const timer = window.setInterval(() => setNow(Date.now()), 1000)
  onCleanup(() => { disposed = true; ++sequence; active?.abort(); window.clearInterval(timer) })
  const disabled = () => busy() || !ready()
  async function run(work: (signal: AbortSignal, current: () => boolean) => Promise<void>) {
    active?.abort(); const controller = new AbortController(); active = controller; const seq = ++sequence; const current = () => !disposed && seq === sequence
    setBusy(true); setError('')
    try { await work(controller.signal, current) } catch (e) { if (!current()) return; if (e instanceof RetentionError && [400, 401, 403, 409, 410].includes(e.status)) { setReview(null); setConfirmed(false); setPending(false); setReceipt(null) } setError(e instanceof Error ? e.message : '请求失败，提交结果待确认') } finally { if (current()) setBusy(false) }
  }
  function load() { setReview(null); setReceipt(null); setConfirmed(false); setPending(false); void run(async (signal, current) => { const v = await getRetentionPreview(signal); if (current()) { setReview(v); setNow(Date.now()) } }) }
  function finish(v: RetentionReceipt) { setReceipt(v); setReview(null); setPending(false); setConfirmed(false); setRequestId(v.command.requestId) }
  function apply() { const r = review(); if (!r || !confirmed() || !r.policy.allowPurge || Date.now() >= Date.parse(r.preview.expiresAt) || pending()) return
    const body = { requestId: crypto.randomUUID(), policyDigest: r.preview.policyDigest, asOf: r.preview.asOf, previewDigest: r.preview.previewDigest }; setRequestId(body.requestId); setPending(true); setConfirmed(false)
    window.history.replaceState(null, '', `#/ai/retention?requestId=${body.requestId}`)
    void run(async (signal, current) => { const v = await applyRetention(body, signal); if (current()) finish(v) })
  }
  function read() { const id = requestId(); void run(async (signal, current) => { const v = await getRetentionReceipt(id, signal); if (current()) finish(v) }) }
  return <section class="panel" data-page="retention"><h2>AI 数据留存</h2>
    <p>按租户策略清理已过期的诊断与证据正文，以及超过留存期的读取审计。运行标识、授权范围、关联元数据和费用记录继续保留。</p>
    <p>策略由管理员的可信配置文件提供。清理仅作用于当前预览的一批内容；正文清除不可通过本页面恢复。数据库备份与存储空间回收需要单独管理。</p>
    <ZwButton variant="outline" disabled={disabled() || pending()} onPress={load}>预览留存清理</ZwButton>
    <Show when={review()}><section data-retention-preview><h3>待确认清理</h3><p>{`租户 ${review()?.policy.tenantId} · 操作者 ${review()?.preview.actor} · 策略 ${review()?.policy.version}`}</p>
      <p>{`结果 ${review()?.policy.insightDays} 天 · 证据 ${review()?.policy.evidenceDays} 天 · 审计 ${review()?.policy.auditDays} 天 · 保留 Incident ${review()?.policy.heldIncidents.length} 个`}</p>
      <ul><For each={review()?.preview.batches ?? []}>{row => <li>{`${labels[forItem(row).kind]}：${forItem(row).ids.length} 条 · 正文 ${forItem(row).logicalBytes} 字节${forItem(row).hasMore ? ' · 尚有后续批次' : ''}`}</li>}</For></ul><p>{`预览有效至 ${review()?.preview.expiresAt}`}</p>
      <Show when={!review()?.policy.allowPurge}><p>当前策略仅允许预览。</p></Show>
      <Show when={!!review() && now() >= Date.parse(review()?.preview.expiresAt ?? '')}><p role="status">预览已过期，请重新预览。</p></Show>
      <label><input type="checkbox" checked={confirmed()} disabled={disabled() || pending() || !review()?.policy.allowPurge} onChange={e => setConfirmed((e.target as HTMLInputElement).checked)} />我已核对策略与数量，确认清除本批内容</label>
      <ZwButton variant="primary" disabled={disabled() || pending() || !confirmed() || !review()?.policy.allowPurge || now() >= Date.parse(review()?.preview.expiresAt ?? '')} onPress={apply}>确认清理本批内容</ZwButton>
    </section></Show>
    <Show when={pending()}><p data-retention-pending>提交结果待确认；请先查询原请求回执，不会自动重试或执行下一批。</p></Show>
    <label>清理请求标识<ZwInput value={requestId()} disabled={busy()} onValueChange={v => setRequestId(v.trim())} /></label>
    <ZwButton variant="outline" disabled={disabled() || !isUuid(requestId())} onPress={read}>查询清理回执</ZwButton>
    <p role="alert">{error()}</p><Show when={busy()}><p role="status">正在请求…</p></Show>
    <Show when={receipt()}><section data-retention-receipt><h3>本批清理已完成</h3><p>{`${receipt()?.tenantId} · ${receipt()?.actor} · ${receipt()?.completedAt}`}</p><ul><For each={receipt()?.preview.batches ?? []}>{row => <li>{`${labels[forItem(row).kind]}：${forItem(row).ids.length} 条 · 正文 ${forItem(row).logicalBytes} 字节${forItem(row).hasMore ? ' · 尚有后续批次' : ''}`}</li>}</For></ul><p>需要继续处理时，请重新预览下一批。</p></section></Show>
  </section>
}
