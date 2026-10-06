import { useEffect, useRef, useSyncExternalStore } from 'react'
import { readBrowserSession } from '../api/browser-session.ts'
import { readLocalPreviewSession } from '../api/local-preview-session.ts'
import { platformCredentials, type BrowserSession, type SessionChange } from '../api/credential-session.ts'

export const oidcMode = import.meta.env.VITE_PLATFORM_AUTH === 'oidc'
export const localPreviewMode = import.meta.env.VITE_PLATFORM_AUTH === 'local-preview'
if (import.meta.env.VITE_PLATFORM_AUTH && !['dev', 'oidc', 'local-preview'].includes(import.meta.env.VITE_PLATFORM_AUTH)) throw new Error('平台会话模式配置不正确')
if (oidcMode) platformCredentials.enableCookieMode()
if (localPreviewMode) platformCredentials.enableLocalPreviewMode()

type Snapshot = { token: string; ready: boolean; browser: BrowserSession | null }

function current(): Snapshot {
  return { token: platformCredentials.token(), ready: platformCredentials.ready(), browser: platformCredentials.browser() }
}

let snapshot = current()
const listeners = new Set<() => void>()

function publish() {
  snapshot = current()
  for (const listener of listeners) listener()
}

platformCredentials.subscribe(() => publish())

function subscribe(listener: () => void) {
  listeners.add(listener)
  return () => { listeners.delete(listener) }
}

function getSnapshot() {
  return snapshot
}

export function usePlatformToken() {
  return useSyncExternalStore(subscribe, () => getSnapshot().token, () => '')
}

export function usePlatformReady() {
  return useSyncExternalStore(subscribe, () => getSnapshot().ready, () => false)
}

export function usePlatformBrowserSession() {
  return useSyncExternalStore(subscribe, () => getSnapshot().browser, () => null)
}

export function usePlatformSession(clear: (change: SessionChange) => void) {
  const ready = usePlatformReady()
  const clearRef = useRef(clear)
  clearRef.current = clear
  useEffect(() => platformCredentials.subscribe(change => clearRef.current(change)), [])
  return ready
}

export function useSessionLifecycle() {
  useEffect(() => {
    const leave = () => platformCredentials.clear('pagehide')
    const resume = () => platformCredentials.expire()
    const timer = window.setInterval(resume, 1000)
    const channel = oidcMode && typeof BroadcastChannel !== 'undefined' ? new BroadcastChannel('opsweave-session') : null
    if (channel) channel.onmessage = event => { if (event.data === 'logout') platformCredentials.clear('unauthenticated') }
    const unsubscribe = platformCredentials.subscribe(change => { if (change.reason === 'logout') channel?.postMessage('logout') })
    let restore: AbortController | undefined
    const show = (event: PageTransitionEvent) => {
      if (event.persisted && (oidcMode || localPreviewMode)) {
        restore?.abort()
        const active = new AbortController()
        restore = active
        const before = platformCredentials.capture(Date.now(), true)
        void (localPreviewMode ? readLocalPreviewSession(active.signal) : readBrowserSession(active.signal)).catch(() => {
          if (restore === active && !active.signal.aborted && platformCredentials.current(before)) platformCredentials.clear('expired')
        })
      }
    }
    window.addEventListener('pageshow', show)
    window.addEventListener('pagehide', leave)
    document.addEventListener('visibilitychange', resume)
    return () => {
      unsubscribe()
      restore?.abort()
      channel?.close()
      window.clearInterval(timer)
      window.removeEventListener('pageshow', show)
      window.removeEventListener('pagehide', leave)
      document.removeEventListener('visibilitychange', resume)
      leave()
    }
  }, [])
}
