import { useEffect, useRef, useState } from 'react'
import { readLocalPreviewSession } from '../api/local-preview-session.ts'
import { usePlatformReady } from '../state/platform-session.ts'
import { Button } from '@/components/ui/button'

export function LocalPreviewSessionBar() {
  const ready = usePlatformReady()
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const activeRef = useRef<AbortController | undefined>(undefined)
  const disposedRef = useRef(false)
  async function connect() {
    activeRef.current?.abort()
    const active = new AbortController()
    activeRef.current = active
    setBusy(true); setError('')
    try { await readLocalPreviewSession(active.signal) }
    catch (e) { if (!disposedRef.current && !active.signal.aborted) setError(e instanceof Error ? e.message : '本地会话连接失败') }
    finally { if (!disposedRef.current && activeRef.current === active) setBusy(false) }
  }
  useEffect(() => {
    disposedRef.current = false
    if (!ready) void connect()
    return () => { disposedRef.current = true; activeRef.current?.abort() }
  }, [])
  if (ready) return null
  return <section className="panel session-bar" data-local-session>
    <p role="status">{busy ? '正在建立本地会话…' : error || '本地会话已结束，请重新连接。'}</p>
    <Button variant="outline" disabled={busy} onClick={() => { void connect() }}>重新连接</Button>
  </section>
}
