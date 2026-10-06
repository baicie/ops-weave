import { createContext, useContext, useEffect, useRef } from 'react'

export type PageCloseReason = { message: string; blocked?: boolean } | null
export const PageWorkspaceContext = createContext({
  active: true,
  registerGuard: (_guard: () => PageCloseReason): (() => void) => () => {},
})

export function usePageActive() { return useContext(PageWorkspaceContext).active }

/** Pages report actual edits or pending operations; the shell never infers them from DOM styling. */
export function usePageCloseGuard(reason: PageCloseReason) {
  const { registerGuard } = useContext(PageWorkspaceContext)
  const current = useRef(reason)
  current.current = reason
  useEffect(() => registerGuard(() => current.current), [registerGuard])
}
