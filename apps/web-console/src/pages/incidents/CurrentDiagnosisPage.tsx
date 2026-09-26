import { usePlatformSession } from '../../state/platform-session.ts'
import { createSignal, For, onCleanup, Show } from '@zeus-js/zeus'
import { forItem } from '../../adapters/zeus-ui/for-item.ts'
import { ZwButton } from '../../adapters/zeus-ui/ZwButton.tsx'
import { ZwInput } from '../../adapters/zeus-ui/ZwInput.tsx'
import { diagnose, getInsight, getEvidence, isUuid, InsightRequestError, type DiagnosisRequest, type InsightResult, type EvidenceDocument } from '../../api/insights.ts'
import { getModelSpend, dollars, type ModelSpend } from '../../api/model-spend.ts'

export function CurrentDiagnosisPage() {
  const query = new URLSearchParams(location.hash.split('?')[1])
  const utc = (offset: number) => new Date(Math.floor((Date.now() + offset) / 1000) * 1000).toISOString()
  const authenticated = usePlatformSession(change => { invalidate(); setPending(null); setQuestion('请基于当前 Incident 和 CPU 指标总结观察、待验证假设与数据缺口。'); if (change.error) setError(change.error.message) })
  const [incident, setIncident] = createSignal(query.get('incidentId') ?? '')
  const [question, setQuestion] = createSignal('请基于当前 Incident 和 CPU 指标总结观察、待验证假设与数据缺口。')
  const [from, setFrom] = createSignal(utc(-900000)); const [to, setTo] = createSignal(utc(0))
  const [runId, setRunId] = createSignal(query.get('runId') ?? '')
  const [result, setResult] = createSignal<InsightResult | null>(null)
  const [evidence, setEvidence] = createSignal<EvidenceDocument | null>(null)
  const [spend, setSpend] = createSignal<ModelSpend | null>(null)
  const [pending, setPending] = createSignal<DiagnosisRequest | null>(null)
  const [busy, setBusy] = createSignal(false); const [error, setError] = createSignal('')
  let controller: AbortController | undefined; let sequence = 0; let disposed = false
  const expiry = window.setInterval(() => { if (result() && Date.parse(result()!.record.expiresAt) <= Date.now()) { invalidate(); setError('结果或证据已过期，请开始新诊断') } }, 1000)
  onCleanup(() => { disposed = true; ++sequence; controller?.abort(); window.clearInterval(expiry) })
  function invalidate() { ++sequence; controller?.abort(); setBusy(false); setResult(null); setEvidence(null); setSpend(null); setError('') }
  function edit(set: (value: string) => void, value: string) { invalidate(); setPending(null); set(value) }
  const disabled = () => busy() || !authenticated()
  async function run(work: (signal: AbortSignal) => Promise<InsightResult | EvidenceDocument | ModelSpend>, kind: 'result' | 'evidence' | 'spend') {
    controller?.abort(); const active = new AbortController(); controller = active; const seq = ++sequence
    setBusy(true); setError(''); setEvidence(null); setSpend(null)
    if (kind === 'result') setResult(null)
    const timer = window.setTimeout(() => active.abort(), kind === 'result' ? 80000 : 18000)
    try {
      const value = await work(active.signal); if (disposed || seq !== sequence) return
      if (kind === 'result') { const saved = value as InsightResult; setResult(saved); setPending(null); setRunId(saved.record.id)
        setIncident(saved.record.incidentId); setQuestion(saved.record.question); setFrom(saved.record.queryWindow.from); setTo(saved.record.queryWindow.to)
        history.replaceState(null, '', `#/incidents/current-diagnose?runId=${saved.record.id}`) }
      else if (kind === 'spend') setSpend(value as ModelSpend)
      else setEvidence(value as EvidenceDocument)
    } catch (cause) {
      if (disposed || seq !== sequence) return
      if (cause instanceof InsightRequestError && [401, 403, 410].includes(cause.status)) { setResult(null); setEvidence(null); setSpend(null) }
      setError(cause instanceof DOMException && cause.name === 'AbortError' ? '请求已超时，保存状态待确认；请用请求标识读取结果。' : cause instanceof Error ? cause.message : '请求失败')
    } finally { window.clearTimeout(timer); if (!disposed && seq === sequence) setBusy(false) }
  }
  function start() {
    const request: DiagnosisRequest = { runId: crypto.randomUUID(), incidentId: incident().trim(), question: question().trim(), timeRange: { from: from(), to: to() }, knowledgeMode: 'current' }
    setPending(request); setRunId(request.runId)
    history.replaceState(null, '', `#/incidents/current-diagnose?runId=${request.runId}`)
    void run(signal => diagnose(request, signal), 'result')
  }
  function read() { const id = runId().trim(); void run(signal => getInsight(id, signal), 'result') }
  function viewEvidence(id: string) { const saved = result(); if (saved) void run(signal => getEvidence(id, saved.record, signal), 'evidence') }
  return <section class="panel current-diagnosis" data-page="current-diagnosis">
    <h2>平台只读诊断</h2>
    <p>读取当前可见的 Incident 与所选窗口的 CPU 指标。采样窗口不代表当时已知的信息；引用关联通过校验也不代表根因成立。</p>
    <div class="metric-controls">
      <label>Incident ID<ZwInput value={incident()} disabled={busy()} onValueChange={v => edit(setIncident, v)} /></label>
      <label>采样开始（UTC）<ZwInput value={from()} disabled={busy()} onValueChange={v => edit(setFrom, v)} /></label>
      <label>采样结束（UTC）<ZwInput value={to()} disabled={busy()} onValueChange={v => edit(setTo, v)} /></label>
    </div>
    <label>诊断问题<textarea prop:value={question()} disabled={busy()} maxLength={2000} onInput={e => edit(setQuestion, (e.target as HTMLTextAreaElement).value)} /></label>
    <p>窗口不超过 1 小时。每次最多读取 5 个关联资产，模型调用 1 次，证据读取与复核合计 4 次。</p>
    <div class="actions"><ZwButton variant="primary" disabled={disabled() || !isUuid(incident().trim()) || pending() !== null} onPress={start}>开始只读诊断</ZwButton>
      <ZwButton variant="outline" disabled={busy()} onPress={() => { invalidate(); setPending(null); setRunId('') }}>准备新诊断</ZwButton></div>
    <label>结果请求标识<ZwInput value={runId()} disabled={busy()} onValueChange={v => edit(setRunId, v)} /></label>
    <ZwButton variant="outline" disabled={disabled() || !isUuid(runId().trim())} onPress={read}>读取已保存结果</ZwButton>
    <ZwButton variant="outline" disabled={disabled() || !isUuid(runId().trim())} onPress={() => { const id = runId().trim(), saved = result()?.record; void run(signal => getModelSpend(id, signal, saved), 'spend') }}>读取用量与费用</ZwButton>
    <Show when={pending()}><p data-diagnosis-pending>请求已创建。出现超时或连接中断时，请先读取已保存结果；不会自动重试模型。刷新页面后可用同一请求标识查询。</p></Show>
    <p role="alert">{error()}</p><Show when={busy()}><p role="status">正在读取或诊断…</p></Show>
    <Show when={spend()}><section data-model-spend><h3>模型用量与费用</h3>
      <p>{`${spend()?.record.policy.provider} / ${spend()?.record.policy.model} · ${spend()?.storage} · 费率版本 ${spend()?.record.policy.priceVersion}`}</p>
      <p>金额为配置费率估算，并非提供方账单。用量记录不代表诊断成功。</p>
      <Show when={spend()?.record.state === 'REPORTED'}><p>{`已上报 · 输入 ${spend()?.record.usage?.inputTokens} Token · 输出 ${spend()?.record.usage?.outputTokens} Token · 缓存输入 ${spend()?.record.usage?.cachedInputTokens} Token`}</p><p>{`估算费用 ${dollars(spend()?.record.estimatedMicros ?? 0)}`}</p></Show>
      <Show when={spend()?.record.state !== 'REPORTED'}><p>费用待确认：尚无可核验用量，预留额度继续占用，不会自动退款或重试。</p></Show>
      <p>{`预留 ${dollars(spend()?.record.reservedMicros ?? 0)} · 当前占用 ${dollars(spend()?.record.accountedMicros ?? 0)}`}</p>
      <Show when={spend()?.record.policy.provider === 'mock-deterministic'}><p>显式 mock：未调用外部模型，Token 和费用均为零。</p></Show>
    </section></Show>
    <Show when={result()}><section data-insight-result>
      <h3>诊断结果</h3><p><strong>{`${result()?.record.model.provider} / ${result()?.record.model.name}`}</strong>{` · ${result()?.storage} · ${result()?.record.dataModes.join(', ')}`}</p>
      <Show when={result()?.record.model.provider === 'mock-deterministic'}><p class="notice">这是确定性 mock 输出，用于本地链路验证，不是实际模型推理。</p></Show>
      <p>{`Incident ${result()?.record.incidentId} · 版本 ${result()?.record.incidentVersion} · 租户 ${result()?.record.tenantId}`}</p>
      <p>{`采样窗口 ${result()?.record.queryWindow.from} 至 ${result()?.record.queryWindow.to}`}</p>
      <p>{`知识截止 ${result()?.record.asOf} · 保存 ${result()?.record.savedAt} · 有效至 ${result()?.record.expiresAt}`}</p>
      <p>{`Skill ${result()?.record.skill.id}@${result()?.record.skill.version}`}</p><code>{result()?.record.skill.digest}</code>
      <p>{result()?.record.question}</p><p class="insight-summary">{result()?.record.insight.summary}</p>
      <For each={result()?.record.insight.findings ?? []}>{row => <article class="incident-problem"><h4>{forItem(row).kind === 'observation' ? '观察' : '待验证假设'}</h4><p>{forItem(row).statement}</p>
        <div class="actions"><For each={forItem(row).evidenceRefs}>{ref => <ZwButton variant="outline" disabled={disabled()} onPress={() => viewEvidence(forItem(ref))}>{`查看证据 ${forItem(ref)}`}</ZwButton>}</For></div>
      </article>}</For>
      <h4>数据缺口</h4><ul><For each={result()?.record.insight.missingData ?? []}>{row => <li>{forItem(row)}</li>}</For></ul>
      <h4>限制</h4><ul><For each={result()?.record.insight.limitations ?? []}>{row => <li>{forItem(row)}</li>}</For></ul>
      <h4>证据快照</h4><div class="actions"><For each={result()?.record.evidenceIds ?? []}>{row => <ZwButton variant="outline" disabled={disabled()} onPress={() => viewEvidence(forItem(row))}>{`读取快照 ${forItem(row)}`}</ZwButton>}</For></div>
    </section></Show>
    <Show when={evidence()}><section data-insight-evidence><h3>{`证据：${evidence()?.evidence.kind}`}</h3><p>{evidence()?.evidence.summary}</p>
      <p>{`${evidence()?.producerTool} · ${evidence()?.dataModes.join(', ')}`}</p><p>{`可见 ${evidence()?.evidence.availableAt} · 观测 ${evidence()?.evidence.observedAt} · 过期 ${evidence()?.evidence.expiresAt}`}</p>
      <p>{evidence()?.warnings.join(' · ')}</p><pre>{JSON.stringify(evidence()?.data, null, 2)}</pre></section></Show>
  </section>
}
