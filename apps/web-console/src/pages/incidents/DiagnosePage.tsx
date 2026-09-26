import { createSignal, For, onCleanup, Show } from '@zeus-js/zeus'
import { forItem } from '../../adapters/zeus-ui/for-item.ts'
import { ZwButton } from '../../adapters/zeus-ui/ZwButton.tsx'
import { ZwInput } from '../../adapters/zeus-ui/ZwInput.tsx'
import { createDiagnosis, type DiagnoseResult } from '../../api/diagnoses.ts'

export function DiagnosePage() {
  const [token, setToken] = createSignal('')
  const [question, setQuestion] = createSignal('为什么订单服务延迟升高？')
  const [busy, setBusy] = createSignal(false)
  const [error, setError] = createSignal('')
  const [result, setResult] = createSignal<DiagnoseResult | null>(null)
  let abort: AbortController | undefined
  let disposed = false
  let requestId = 0
  function identity(value: string) { abort?.abort(); ++requestId; setBusy(false); setError(''); setResult(null); setToken(value) }
  const leave = () => { identity(''); setQuestion('为什么订单服务延迟升高？') }
  window.addEventListener('pagehide', leave)

  onCleanup(() => {
    disposed = true
    abort?.abort()
    window.removeEventListener('pagehide', leave)
  })

  async function diagnose() {
    abort?.abort()
    abort = new AbortController()
    const currentRequest = ++requestId
    const timeout = window.setTimeout(() => abort?.abort(), 35000)
    setBusy(true)
    setError('')
    setResult(null)
    try {
      const value = await createDiagnosis({
        token: token(),
        question: question(),
        signal: abort.signal,
      })
      if (disposed || currentRequest !== requestId) return
      setResult(value)
    } catch (cause) {
      if (disposed || currentRequest !== requestId) return
      if (cause instanceof DOMException && cause.name === 'AbortError') {
        setError('诊断已取消或超时')
      } else {
        setError(cause instanceof Error ? cause.message : '请求失败')
      }
    } finally {
      window.clearTimeout(timeout)
      if (!disposed && currentRequest === requestId) setBusy(false)
    }
  }

  function cancel() {
    abort?.abort()
  }

  function scrollToEvidence(id: string) {
    document.getElementById('evidence-' + id)?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  }

  return (
    <section class="panel" data-page="diagnose">
      <h2>只读诊断 Demo</h2>
      <p>使用固定的合成故障数据，不查询真实 Zabbix，也不执行生产动作。先在本机启动 Rust Demo。</p>
      <label>
        开发 Token（仅保存在当前页面内存）
        <ZwInput
          type="password"
          autocomplete="off"
          value={token()}
          onValueChange={identity}
        />
      </label>
      <label>
        问题
        <textarea
          maxlength={2000}
          prop:value={question()}
          onInput={event => setQuestion((event.currentTarget as HTMLTextAreaElement).value)}
        />
      </label>
      <div class="actions">
        <ZwButton
          variant="primary"
          disabled={busy() || token().length < 32 || !question().trim()}
          loading={busy()}
          onPress={() => { void diagnose() }}
        >
          {busy() ? '正在诊断…' : '运行只读诊断'}
        </ZwButton>
        <ZwButton variant="outline" disabled={!busy()} onPress={cancel}>
          取消
        </ZwButton>
      </div>
      <p role="alert">{error()}</p>
      <Show when={result()}>
        <DiagnoseResultView result={result() as DiagnoseResult} onEvidence={scrollToEvidence} />
      </Show>
    </section>
  )
}

function DiagnoseResultView(props: {
  result: DiagnoseResult
  onEvidence: (id: string) => void
}) {
  return (
    <section aria-live="polite">
      <h2>{props.result.insight.summary}</h2>
      <p>
        <code>{`${props.result.dataMode} / ${props.result.modelProvider}`}</code>
      </p>
      <For each={props.result.insight.findings}>
        {row => <FindingView finding={forItem(row)} onEvidence={props.onEvidence} />}
      </For>
      <h3>证据（合成数据）</h3>
      <For each={props.result.context.evidence}>
        {row => <EvidenceView item={forItem(row)} />}
      </For>
      <h3>缺少的数据</h3>
      <p>{props.result.insight.missingData.join('；')}</p>
      <h3>限制</h3>
      <p>{props.result.insight.limitations.join('；')}</p>
      <small>{`Run: ${props.result.runId} · ${props.result.verification}`}</small>
    </section>
  )
}

function FindingView(props: {
  finding: DiagnoseResult['insight']['findings'][number]
  onEvidence: (id: string) => void
}) {
  return (
    <article>
      <b>{props.finding.kind === 'hypothesis' ? '候选解释' : '观测'}</b>
      <p>{props.finding.statement}</p>
      <p>
        <For each={props.finding.evidenceRefs}>
          {ref => <EvidenceRefButton id={forItem(ref)} onEvidence={props.onEvidence} />}
        </For>
      </p>
    </article>
  )
}

function EvidenceRefButton(props: {
  id: string
  onEvidence: (id: string) => void
}) {
  return (
    <button type="button" class="linkish" onClick={() => props.onEvidence(props.id)}>
      {props.id}
    </button>
  )
}

function EvidenceView(props: { item: DiagnoseResult['context']['evidence'][number] }) {
  return (
    <details id={'evidence-' + props.item.id}>
      <summary>{`${props.item.id} · ${props.item.kind}`}</summary>
      <p>{props.item.summary}</p>
      <code>{props.item.sourceRef}</code>
    </details>
  )
}
