import { onCleanup } from '@zeus-js/zeus'
import { platformCredentials, type SessionChange } from '../api/credential-session.ts'

/** The same-path popstate/hashchange pair is processed once; self writes do not discard current results. */
export function useViewLocation<T>(path: string, decode: (hash: string) => T, encode: (value: T) => string,
  restore: (value: T) => void, reject: (error: Error) => void) {
  let seen = ''
  function read() {
    const current = window.location.hash
    if (current === seen || current.split('?')[0] !== `#${path}`) return
    seen = current
    try { restore(decode(current)) } catch (cause) { reject(cause instanceof Error ? cause : new Error('地址参数无效')) }
  }
  window.addEventListener('hashchange', read); window.addEventListener('popstate', read)
  onCleanup(() => { window.removeEventListener('hashchange', read); window.removeEventListener('popstate', read) })
  read()
  return { write(value: T, replace = false) {
    const next = encode(value)
    if (window.location.hash.split('?')[0] !== `#${path}` || next === window.location.hash) return
    window.history[replace ? 'replaceState' : 'pushState'](null, '', window.location.pathname + window.location.search + next)
    seen = next
  } }
}

/** Typing the initial credential preserves a link until its first read. Established sessions clear selections. */
export function selectionSessionReset() {
  let established = platformCredentials.ready()
  return {
    beginRead() { established = true },
    changed(change: SessionChange) {
      const reset = change.reason === 'pagehide' ? false : change.reason === 'credentials' ? established : true
      if (change.reason !== 'forbidden') established = false
      return reset
    },
  }
}
