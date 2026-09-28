import { useState } from 'react'
import { platformCredentials } from '../api/credential-session.ts'
import { usePlatformToken, oidcMode } from '../state/platform-session.ts'
import { OidcSessionBar } from './OidcSessionBar.tsx'
import { Input } from '@/components/ui/input'
import { Button } from '@/components/ui/button'

export function PlatformSessionBar() {
  if (oidcMode) return <OidcSessionBar />
  return <DevPlatformSessionBar />
}

function DevPlatformSessionBar() {
  const [error, setError] = useState('')
  const token = usePlatformToken()
  return (
    <section className="panel session-bar" data-platform-session>
      <label>平台开发 Token（仅保存在当前标签页内存）
        <Input type="password" autoComplete="off" value={token} onChange={event => {
          setError('')
          try { platformCredentials.replace(event.currentTarget.value) } catch (cause) { setError((cause as Error).message) }
        }} />
      </label>
      <div className="actions">
        <Button variant="outline" disabled={!token} onClick={() => platformCredentials.clear()}>清除开发会话</Button>
        <span>当前为开发凭据模式，最多保留 30 分钟；刷新或离开页面后清除，服务端逐请求授权。</span>
      </div>
      <p data-session-error>{error}</p>
    </section>
  )
}
