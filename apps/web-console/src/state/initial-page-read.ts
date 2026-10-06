import { useEffect, useRef, useState } from 'react'
import { platformCredentials } from '../api/credential-session.ts'
import { usePageActive } from './page-workspace.ts'

/** One initial read per authorized page session; failed reads require an explicit retry. */
export function useInitialPageRead(props: { ready: boolean; loaded: boolean; pending: boolean; blocked?: boolean; read: () => void }) {
  const active = usePageActive()
  const attempted = useRef(false), read = useRef(props.read)
  const [session, setSession] = useState(0)
  read.current = props.read
  useEffect(() => platformCredentials.subscribe(change => {
    attempted.current = change.reason !== 'credentials'
    setSession(value => value + 1)
  }), [])
  useEffect(() => {
    if ((!active || props.blocked) && props.pending && !props.loaded) attempted.current = false
    if (active && props.ready && !props.blocked && !props.loaded && !props.pending && !attempted.current) {
      attempted.current = true
      read.current()
    }
  }, [active, props.ready, props.loaded, props.pending, props.blocked, session])
}
