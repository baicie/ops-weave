import { useEffect, useRef, useState } from 'react'
import { usePlatformBrowserSession, usePlatformReady } from '../state/platform-session.ts'
import { readBrowserSession, logoutBrowserSession } from '../api/browser-session.ts'
import { platformCredentials } from '../api/credential-session.ts'
import { Button } from '@/components/ui/button'

export function OidcSessionBar() {
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const browserSession = usePlatformBrowserSession()
  const ready = usePlatformReady()
  const controllerRef = useRef<AbortController | undefined>(undefined)
  const disposedRef = useRef(false)

  async function run(logout = false) {
    controllerRef.current?.abort()
    const active = new AbortController()
    controllerRef.current = active
    setBusy(true)
    setError('')
    const before = platformCredentials.capture(Date.now(), true)
    try {
      if (logout) await logoutBrowserSession(active.signal)
      if (!disposedRef.current && controllerRef.current === active) await readBrowserSession(active.signal)
    } catch (cause) {
      if (!disposedRef.current && controllerRef.current === active && !(cause instanceof DOMException && cause.name === 'AbortError')) {
        if (!logout && platformCredentials.current(before)) platformCredentials.clear('expired')
        setError(cause instanceof Error ? cause.message : '会话请求失败')
      }
    } finally {
      if (!disposedRef.current && controllerRef.current === active) setBusy(false)
    }
  }

  useEffect(() => {
    disposedRef.current = false
    void run()
    return () => {
      disposedRef.current = true
      controllerRef.current?.abort()
    }
  }, [])

  return (
    <section className="panel session-bar" data-oidc-session>
      <h2>平台登录</h2>
      {browserSession?.dataMode === 'oidc-protocol-test' ? <p>本机 OIDC 协议测试 · 未连接真实登录提供方</p> : null}
      {ready ? <p>{`用户 ${browserSession?.principal?.subjectId} · 租户 ${browserSession?.principal?.tenantId} · 会话到期 ${browserSession?.expiresAt}`}</p> : null}
      {!ready ? <p>请登录后读取资源。刷新只恢复选择，数据仍需重新读取。</p> : null}
      <div className="actions">
        {!ready && browserSession ? <a href="/api/v1/auth/login/opsweave">通过身份提供方登录</a> : null}
        <Button variant="outline" disabled={busy} onClick={() => { void run() }}>读取当前会话</Button>
        {ready ? <Button variant="outline" disabled={busy} onClick={() => { void run(true) }}>退出平台登录</Button> : null}
      </div>
      <p>平台退出会撤销当前会话。身份提供方的登录状态由该提供方管理。</p>
      <p role="status">{busy ? '正在读取会话…' : error}</p>
    </section>
  )
}
