import { useEffect, useRef, useState } from 'react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Textarea } from '@/components/ui/textarea'
import { createDiagnosis, type DiagnoseResult } from '../../api/diagnoses.ts'

export function DiagnosePage() {
  const [token, setToken] = useState('')
  const [question, setQuestion] = useState('为什么订单服务延迟升高？')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [result, setResult] = useState<DiagnoseResult | null>(null)
  const abortRef = useRef<AbortController | undefined>(undefined)
  const disposedRef = useRef(false)
  const requestIdRef = useRef(0)

  function identity(value: string) {
    abortRef.current?.abort()
    ++requestIdRef.current
    setBusy(false)
    setError('')
    setResult(null)
    setToken(value)
  }

  useEffect(() => {
    const leave = () => { identity(''); setQuestion('为什么订单服务延迟升高？') }
    window.addEventListener('pagehide', leave)
    return () => {
      disposedRef.current = true
      abortRef.current?.abort()
      window.removeEventListener('pagehide', leave)
    }
  }, [])

  async function diagnose() {
    abortRef.current?.abort()
    abortRef.current = new AbortController()
    const currentRequest = ++requestIdRef.current
    const timeout = window.setTimeout(() => abortRef.current?.abort(), 35000)
    setBusy(true)
    setError('')
    setResult(null)
    try {
      const value = await createDiagnosis({
        token,
        question,
        signal: abortRef.current.signal,
      })
      if (disposedRef.current || currentRequest !== requestIdRef.current) return
      setResult(value)
    } catch (cause) {
      if (disposedRef.current || currentRequest !== requestIdRef.current) return
      if (cause instanceof DOMException && cause.name === 'AbortError') {
        setError('诊断已取消或超时')
      } else {
        setError(cause instanceof Error ? cause.message : '请求失败')
      }
    } finally {
      window.clearTimeout(timeout)
      if (!disposedRef.current && currentRequest === requestIdRef.current) setBusy(false)
    }
  }

  function cancel() {
    abortRef.current?.abort()
  }

  function scrollToEvidence(id: string) {
    document.getElementById('evidence-' + id)?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  }

  return (
    <section className="panel" data-page="diagnose">
      <h2>只读诊断 Demo</h2>
      <p>使用固定的合成故障数据，不查询真实 Zabbix，也不执行生产动作。先在本机启动 Rust Demo。</p>
      <label>
        开发 Token（仅保存在当前页面内存）
        <Input
          type="password"
          autoComplete="off"
          value={token}
          onChange={event => identity(event.currentTarget.value)}
        />
      </label>
      <label>
        问题
        <Textarea
          maxLength={2000}
          value={question}
          onChange={event => setQuestion(event.currentTarget.value)}
        />
      </label>
      <div className="actions">
        <Button
          variant="default"
          disabled={busy || token.length < 32 || !question.trim()}
          onClick={() => { void diagnose() }}
        >
          {busy ? '正在诊断…' : '运行只读诊断'}
        </Button>
        <Button variant="outline" disabled={!busy} onClick={cancel}>
          取消
        </Button>
      </div>
      <p role="alert">{error}</p>
      {result ? (
        <DiagnoseResultView result={result} onEvidence={scrollToEvidence} />
      ) : null}
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
      {props.result.insight.findings.map((finding, index) => (
        <FindingView key={`${finding.kind}-${index}`} finding={finding} onEvidence={props.onEvidence} />
      ))}
      <h3>证据（合成数据）</h3>
      {props.result.context.evidence.map(item => (
        <EvidenceView key={item.id} item={item} />
      ))}
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
        {props.finding.evidenceRefs.map(id => (
          <EvidenceRefButton key={id} id={id} onEvidence={props.onEvidence} />
        ))}
      </p>
    </article>
  )
}

function EvidenceRefButton(props: {
  id: string
  onEvidence: (id: string) => void
}) {
  return (
    <button type="button" className="linkish" onClick={() => props.onEvidence(props.id)}>
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
