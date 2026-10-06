import { useState } from 'react'
import { platformCredentials } from '../api/credential-session.ts'
import { usePlatformToken, usePlatformReady, oidcMode, localPreviewMode } from '../state/platform-session.ts'
import { LocalPreviewSessionBar } from './LocalPreviewSessionBar.tsx'
import { OidcSessionBar } from './OidcSessionBar.tsx'
import { Input } from '@/components/ui/input'
import { Button } from '@/components/ui/button'
import { KeyRound } from 'lucide-react'

export function PlatformSessionBar() {
  if (localPreviewMode) return <LocalPreviewSessionBar />
  if (oidcMode) return <OidcSessionBar />
  return <DevPlatformSessionBar />
}

function DevPlatformSessionBar() {
  const [error, setError] = useState('')
  const token = usePlatformToken()
  const ready = usePlatformReady()
  return (
    <section className="panel session-bar" data-platform-session>
      <div className="session-identity"><KeyRound /><span><strong>平台会话</strong><small>{ready ? '开发凭据已填写' : token ? '需要 32 位且无空白' : '等待配置凭据'}</small></span></div>
      <label><span className="sr-only">平台开发 Token（仅保存在当前标签页内存）</span>
        <Input type="password" placeholder="输入平台开发 Token" autoComplete="off" value={token} onChange={event => {
          setError('')
          try { platformCredentials.replace(event.currentTarget.value) } catch (cause) { setError((cause as Error).message) }
        }} />
      </label>
      <div className="actions">
        <Button variant="outline" disabled={!token} onClick={() => platformCredentials.clear()}>清除开发会话</Button>
        <span>仅保留在当前标签页内存，最多 30 分钟。服务端逐请求授权。</span>
      </div>
      <p data-session-error>{error}</p>
    </section>
  )
}
