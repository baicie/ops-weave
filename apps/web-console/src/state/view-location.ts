import { useLayoutEffect, useRef } from 'react'
import { platformCredentials, type SessionChange } from '../api/credential-session.ts'

/** The same-path popstate/hashchange pair is processed once; self writes do not discard current results. */
export function useViewLocation<T>(path: string, decode: (hash: string) => T, encode: (value: T) => string,
  restore: (value: T) => void, reject: (error: Error) => void) {
  const seen = useRef('')
  const decodeRef = useRef(decode)
  const restoreRef = useRef(restore)
  const rejectRef = useRef(reject)
  decodeRef.current = decode
  restoreRef.current = restore
  rejectRef.current = reject
  useLayoutEffect(() => {
    function read() {
      const current = window.location.hash
      if (current === seen.current || current.split('?')[0] !== `#${path}`) return
      seen.current = current
      try { restoreRef.current(decodeRef.current(current)) } catch (cause) { rejectRef.current(cause instanceof Error ? cause : new Error('地址参数无效')) }
    }
    window.addEventListener('hashchange', read)
    window.addEventListener('popstate', read)
    read()
    return () => {
      window.removeEventListener('hashchange', read)
      window.removeEventListener('popstate', read)
    }
  }, [path])
  return {
    write(value: T, replace = false) {
      const next = encode(value)
      if (window.location.hash.split('?')[0] !== `#${path}` || next === window.location.hash) return
      window.history[replace ? 'replaceState' : 'pushState'](null, '', window.location.pathname + window.location.search + next)
      seen.current = next
    },
  }
}

/** Typing the initial credential preserves a link until its first read. Established sessions clear selections. */
export function useSelectionSessionReset() {
  const established = useRef(platformCredentials.ready())
  const api = useRef({
    beginRead() { established.current = true },
    changed(change: SessionChange) {
      const reset = change.reason === 'pagehide' ? false : change.reason === 'credentials' ? established.current : true
      if (change.reason !== 'forbidden') established.current = false
      return reset
    },
  })
  return api.current
}
