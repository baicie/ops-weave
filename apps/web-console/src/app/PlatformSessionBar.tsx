import { createSignal } from '@zeus-js/zeus'
import { platformCredentials } from '../api/credential-session.ts'
import { platformToken, oidcMode } from '../state/platform-session.ts'
import { OidcSessionBar } from './OidcSessionBar.tsx'
import { ZwInput } from '../adapters/zeus-ui/ZwInput.tsx'
import { ZwButton } from '../adapters/zeus-ui/ZwButton.tsx'

export function PlatformSessionBar() {
  if (oidcMode) return <OidcSessionBar />
  const [error, setError] = createSignal('')
  return <section class="panel session-bar" data-platform-session>
    <label>平台开发 Token（仅保存在当前标签页内存）<ZwInput type="password" autocomplete="off" value={platformToken()} onValueChange={value => {
      setError(''); try { platformCredentials.replace(value) } catch (cause) { setError((cause as Error).message) }
    }} /></label>
    <div class="actions"><ZwButton variant="outline" disabled={!platformToken()} onPress={() => platformCredentials.clear()}>清除开发会话</ZwButton>
      <span>当前为开发凭据模式，最多保留 30 分钟；刷新或离开页面后清除，服务端逐请求授权。</span></div>
    <p data-session-error>{error()}</p>
  </section>
}
