import { useEffect, useRef, useState } from 'react'
import { usePageCloseGuard } from '../../state/page-workspace.ts'
import { usePlatformSession } from '../../state/platform-session.ts'
import { Button } from '@/components/ui/button'
import { PageHeader, PageBody } from '../../components/PageLayout.tsx'
import { AiRunDetailPanel } from '../../components/runs/AiRunDetailPanel.tsx'
import { RunEventTimeline } from '../../components/runs/RunEventTimeline.tsx'
import {
  AiRunError, cancelAiRun, isUuid, listAiRuns, readAiRun, readAiRunEvents, retryAiRun,
  streamRunEvents, supportsEventStream,
  type AiRun, type RunEvent, type RunEventPage, type RunSnapshot,
} from '../../api/ai-runs.ts'

const TERMINAL = ['SUCCEEDED', 'FAILED', 'CANCELLED']

/**
 * The read-only run console (M5 5c): the caller's own runs, one run with its reconciliation
 * disclosure, and the ordered event timeline behind a cursor. Live updates come from the SSE push
 * channel and degrade to cursor polling; replaying, reconnecting and re-reading never execute
 * anything. The only writes are the existing idempotent cancel/retry commands, whose unknown
 * outcomes keep their original request id for an explicit identical resend.
 */
export function AiRunsPage() {
  const [runs, setRuns] = useState<AiRun[]>([])
  const [runsCursor, setRunsCursor] = useState<string | null>(null)
  const [runsMore, setRunsMore] = useState(false)
  const [selected, setSelected] = useState<AiRun | null>(null)
  const [events, setEvents] = useState<RunEvent[]>([])
  const [eventsMore, setEventsMore] = useState(false)
  const [gap, setGap] = useState(false)
  const [snapshot, setSnapshot] = useState<RunSnapshot | null>(null)
  const [transport, setTransport] = useState<'idle' | 'sse' | 'polling'>('idle')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [pending, setPending] = useState<{ kind: 'cancel' | 'retry'; runId: string; requestId: string } | null>(null)
  const [unknown, setUnknown] = useState(false)
  const activeRef = useRef<AbortController | undefined>(undefined)
  const liveRef = useRef<AbortController | undefined>(undefined)
  const timerRef = useRef<number | undefined>(undefined)
  const sequenceRef = useRef(0)
  const disposedRef = useRef(false)
  const loadedRef = useRef(false)
  const cursorRef = useRef(0)
  const snapshotRef = useRef<RunSnapshot | null>(null)

  const ready = usePlatformSession(change => {
    ++sequenceRef.current
    activeRef.current?.abort()
    liveRef.current?.abort()
    if (timerRef.current !== undefined) window.clearInterval(timerRef.current)
    loadedRef.current = false
    setBusy(false)
    setRuns([])
    setRunsCursor(null)
    setRunsMore(false)
    setSelected(null)
    setEvents([])
    setEventsMore(false)
    setGap(false)
    setSnapshot(null)
    snapshotRef.current = null
    cursorRef.current = 0
    setTransport('idle')
    setPending(null)
    setUnknown(false)
    setError(change.error?.message ?? '')
  })

  useEffect(() => () => {
    disposedRef.current = true
    ++sequenceRef.current
    activeRef.current?.abort()
    liveRef.current?.abort()
    if (timerRef.current !== undefined) window.clearInterval(timerRef.current)
  }, [])

  // The default active page reads its list once when the session becomes ready (explicit re-read above).
  useEffect(() => {
    if (!ready || loadedRef.current) return
    loadedRef.current = true
    loadRuns()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ready])

  usePageCloseGuard(pending ? {
    message: unknown ? '提交结果待确认，请先按原请求标识查询或原样重发。' : '控制命令正在处理，请等待回执后关闭。',
    blocked: true,
  } : null)

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
      setError(e instanceof Error ? e.message : '请求失败，结果待确认')
    } finally {
      if (current()) setBusy(false)
    }
  }

  function applyPage(page: RunEventPage) {
    setGap(previous => previous || page.gap)
    snapshotRef.current = page.snapshot
    setSnapshot(page.snapshot)
    setEventsMore(page.hasMore)
    if (page.items.length > 0) {
      cursorRef.current = page.items[page.items.length - 1].seq
      setEvents(previous => {
        const known = new Set(previous.map(event => event.seq))
        return [...previous, ...page.items.filter(event => !known.has(event.seq))]
      })
    }
  }

  function stopLive() {
    liveRef.current?.abort()
    liveRef.current = undefined
    if (timerRef.current !== undefined) window.clearInterval(timerRef.current)
    timerRef.current = undefined
    setTransport('idle')
  }

  function startPolling(runId: string, signal: AbortSignal) {
    setTransport('polling')
    timerRef.current = window.setInterval(() => {
      if (signal.aborted || disposedRef.current) return
      void readAiRunEvents(runId, cursorRef.current, signal)
        .then(applyPage)
        .catch(error => {
          if (signal.aborted || disposedRef.current || error instanceof DOMException) return
          setError(error instanceof Error ? error.message : '事件回读失败')
        })
    }, 3000)
  }

  /** The push channel is only a transport over the cursor protocol; polling stays the fallback. */
  function startLive(runId: string) {
    liveRef.current?.abort()
    if (timerRef.current !== undefined) window.clearInterval(timerRef.current)
    const controller = new AbortController()
    liveRef.current = controller
    if (!supportsEventStream()) {
      startPolling(runId, controller.signal)
      return
    }
    setTransport('sse')
    void (async () => {
      let failures = 0
      while (!controller.signal.aborted && !disposedRef.current && failures < 3) {
        try {
          await streamRunEvents({ runId, afterSeq: cursorRef.current, signal: controller.signal }, applyPage)
          if (controller.signal.aborted || disposedRef.current) return
          if (snapshotRef.current && TERMINAL.includes(snapshotRef.current.status)) return
          failures++
        } catch (failure) {
          if (controller.signal.aborted || disposedRef.current || failure instanceof DOMException) return
          failures++
        }
      }
      if (!controller.signal.aborted && !disposedRef.current) startPolling(runId, controller.signal)
    })()
  }

  function loadRuns() {
    void run(async (signal, current) => {
      const page = await listAiRuns(signal)
      if (current()) {
        setRuns(page.items)
        setRunsCursor(page.nextCursor)
        setRunsMore(page.hasMore)
      }
    })
  }

  function loadMoreRuns() {
    if (!runsCursor) return
    void run(async (signal, current) => {
      const page = await listAiRuns(signal, runsCursor)
      if (current()) {
        setRuns(previous => [...previous, ...page.items])
        setRunsCursor(page.nextCursor)
        setRunsMore(page.hasMore)
      }
    })
  }

  function open(item: AiRun) {
    stopLive()
    setSelected(item)
    setEvents([])
    setEventsMore(false)
    setGap(false)
    setSnapshot(null)
    snapshotRef.current = null
    cursorRef.current = 0
    void run(async (signal, current) => {
      const page = await readAiRunEvents(item.runId, 0, signal)
      if (!current()) return
      applyPage(page)
      startLive(item.runId)
    })
  }

  function loadMoreEvents() {
    const runId = selected?.runId
    if (!runId) return
    void run(async (signal, current) => {
      const page = await readAiRunEvents(runId, cursorRef.current, signal)
      if (current()) applyPage(page)
    })
  }

  function refreshRun() {
    const runId = selected?.runId
    if (!runId) return
    void run(async (signal, current) => {
      const fresh = await readAiRun(runId, signal)
      if (!current()) return
      setSelected(fresh)
      setRuns(previous => previous.map(item => (item.runId === fresh.runId ? fresh : item)))
      const page = await readAiRunEvents(runId, cursorRef.current, signal)
      if (current()) applyPage(page)
    })
  }

  function send(kind: 'cancel' | 'retry', requestId?: string) {
    const runId = selected?.runId
    if (!runId || (pending && !requestId)) return
    const id = requestId ?? crypto.randomUUID()
    setPending({ kind, runId, requestId: id })
    setUnknown(false)
    void run(async (signal, current) => {
      try {
        if (kind === 'cancel') await cancelAiRun(runId, id, signal)
        else await retryAiRun(runId, id, signal)
        if (!current()) return
        setPending(null)
        setUnknown(false)
        refreshRun()
      } catch (failure) {
        if (!current()) return
        const definitive = failure instanceof AiRunError && [400, 403, 404, 409].includes(failure.status)
        if (definitive) {
          setPending(null)
          setUnknown(false)
        } else {
          // Timeout, transport failure or an unavailable store: the outcome is unknown. The original
          // request id is kept and only an explicit identical resend is offered — never automatic.
          setUnknown(true)
        }
        setError(failure instanceof Error ? failure.message : '提交结果待确认')
      }
    })
  }

  return (
    <section className="panel" data-page="ai-runs">
      <PageHeader
        title="诊断运行"
        description="本人运行登记、状态与事件时间线。事件是记录不是命令：回读、重放与重连都不执行任何操作。"
        actions={<Button variant="outline" disabled={!ready || busy} onClick={loadRuns}>重新读取运行列表</Button>}
      />
      <PageBody className="console-maintenance">
        {!ready ? <p className="source-access-hint" role="status">请先在页面顶部建立平台会话，再读取本人运行记录。</p> : null}
        {ready && runs.length === 0 && !busy ? (
          <section className="ai-run-list">
            <h3>运行列表</h3>
            <p>当前没有可见的运行记录。新的诊断提交后会出现在这里。</p>
          </section>
        ) : null}
        {runs.length > 0 ? (
          <section className="ai-run-list">
            <h3>运行列表</h3>
            <table>
              <thead>
                <tr><th>运行</th><th>提问</th><th>状态</th><th>尝试</th><th>受理时间</th><th>操作</th></tr>
              </thead>
              <tbody>
                {runs.map(item => (
                  <tr key={item.runId} data-ai-run={item.runId}>
                    <td>{item.runId.slice(0, 8)}</td>
                    <td>{item.question}</td>
                    <td><span className="run-badge" data-outcome={item.status}>{item.status}</span></td>
                    <td>{item.attempt}</td>
                    <td>{item.createdAt}</td>
                    <td>
                      <Button variant="outline" disabled={busy} onClick={() => open(item)}>查看运行与事件</Button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            {runsMore ? (
              <Button variant="outline" disabled={busy} onClick={loadMoreRuns}>加载更多运行</Button>
            ) : <p role="status">已到运行列表末尾；空尾页是完整结果。</p>}
          </section>
        ) : null}
        {selected ? (
          <>
            <AiRunDetailPanel
              run={selected}
              busy={busy}
              pending={pending && pending.runId === selected.runId ? { kind: pending.kind, requestId: pending.requestId } : null}
              unknown={unknown}
              onCancel={() => send('cancel')}
              onRetry={() => send('retry')}
              onRefresh={refreshRun}
              onResend={() => pending && send(pending.kind, pending.requestId)}
            />
            <RunEventTimeline
              events={events}
              gap={gap}
              snapshot={snapshot}
              hasMore={eventsMore}
              transport={transport}
              busy={busy}
              onLoadMore={loadMoreEvents}
            />
          </>
        ) : null}
        <p role="alert">{error}</p>
        {busy ? <p role="status">正在请求…</p> : null}
      </PageBody>
    </section>
  )
}
