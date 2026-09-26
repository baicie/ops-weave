import { createSignal, onCleanup, Show } from '@zeus-js/zeus'
import { platformBrowserSession, platformReady } from '../state/platform-session.ts'
import { readBrowserSession, logoutBrowserSession } from '../api/browser-session.ts'
import { platformCredentials } from '../api/credential-session.ts'
import { ZwButton } from '../adapters/zeus-ui/ZwButton.tsx'

export function OidcSessionBar() {
  const [busy, setBusy] = createSignal(false), [error, setError] = createSignal('')
  let controller: AbortController | undefined, disposed = false
  onCleanup(() => { disposed = true; controller?.abort() })
  async function run(logout = false) {
    controller?.abort(); const active = new AbortController(); controller = active; setBusy(true); setError('')
    const before = platformCredentials.capture(Date.now(), true)
    try { if (logout) await logoutBrowserSession(active.signal); if (!disposed && controller === active) await readBrowserSession(active.signal) }
    catch (cause) { if (!disposed && controller === active && !(cause instanceof DOMException && cause.name === 'AbortError')) {
      if (!logout && platformCredentials.current(before)) platformCredentials.clear('expired')
      setError(cause instanceof Error ? cause.message : '会话请求失败')
    } }
    finally { if (!disposed && controller === active) setBusy(false) }
  }
  void run()
  return <section class="panel session-bar" data-oidc-session>
    <h2>平台登录</h2>
    <Show when={platformBrowserSession()?.dataMode === 'oidc-protocol-test'}><p>本机 OIDC 协议测试 · 未连接真实登录提供方</p></Show>
    <Show when={platformReady()}><p>{`用户 ${platformBrowserSession()?.principal?.subjectId} · 租户 ${platformBrowserSession()?.principal?.tenantId} · 会话到期 ${platformBrowserSession()?.expiresAt}`}</p></Show>
    <Show when={!platformReady()}><p>请登录后读取资源。刷新只恢复选择，数据仍需重新读取。</p></Show>
    <div class="actions">
      <Show when={!platformReady() && platformBrowserSession()}><a href="/api/v1/auth/login/opsweave">通过身份提供方登录</a></Show>
      <ZwButton variant="outline" disabled={busy()} onPress={() => { void run() }}>读取当前会话</ZwButton>
      <Show when={platformReady()}><ZwButton variant="outline" disabled={busy()} onPress={() => { void run(true) }}>退出平台登录</ZwButton></Show>
    </div>
    <p>平台退出会撤销当前会话。身份提供方的登录状态由该提供方管理。</p>
    <p role="status">{busy() ? '正在读取会话…' : error()}</p>
  </section>
}
