import { createSignal, onCleanup } from '@zeus-js/zeus'
import { platformCredentials, type SessionChange } from '../api/credential-session.ts'
import { readBrowserSession } from '../api/browser-session.ts'

export const oidcMode = import.meta.env.VITE_PLATFORM_AUTH === 'oidc'
if (import.meta.env.VITE_PLATFORM_AUTH && !['dev', 'oidc'].includes(import.meta.env.VITE_PLATFORM_AUTH)) throw new Error('平台会话模式配置不正确')
if (oidcMode) platformCredentials.enableCookieMode()

const [token, updateToken] = createSignal('')
const [ready, updateReady] = createSignal(false)
const [browserSession, updateBrowserSession] = createSignal(platformCredentials.browser())
platformCredentials.subscribe(() => { updateToken(platformCredentials.token()); updateReady(platformCredentials.ready()); updateBrowserSession(platformCredentials.browser()) })
export const platformToken = token
export const platformReady = ready
export const platformBrowserSession = browserSession
export function usePlatformSession(clear: (change: SessionChange) => void) {
  const unsubscribe = platformCredentials.subscribe(clear); onCleanup(unsubscribe)
  return platformReady
}
export function installSessionLifecycle() {
  const leave = () => platformCredentials.clear('pagehide')
  const resume = () => platformCredentials.expire()
  const timer = window.setInterval(resume, 1000)
  const channel = oidcMode && typeof BroadcastChannel !== 'undefined' ? new BroadcastChannel('opsweave-session') : null
  if (channel) channel.onmessage = event => { if (event.data === 'logout') platformCredentials.clear('unauthenticated') }
  const unsubscribe = platformCredentials.subscribe(change => { if (change.reason === 'logout') channel?.postMessage('logout') })
  let restore: AbortController | undefined
  const show = (event: PageTransitionEvent) => {
    if (event.persisted && oidcMode) {
      restore?.abort(); const active = new AbortController(); restore = active; const before = platformCredentials.capture(Date.now(), true)
      void readBrowserSession(active.signal).catch(() => { if (restore === active && !active.signal.aborted && platformCredentials.current(before)) platformCredentials.clear('expired') })
    }
  }
  window.addEventListener('pageshow', show)
  window.addEventListener('pagehide', leave); document.addEventListener('visibilitychange', resume)
  onCleanup(() => { unsubscribe(); restore?.abort(); channel?.close(); window.clearInterval(timer); window.removeEventListener('pageshow', show); window.removeEventListener('pagehide', leave); document.removeEventListener('visibilitychange', resume); leave() })
}
