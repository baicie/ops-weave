import { useSyncExternalStore } from 'react'

export type ConsoleLayout = 'standard' | 'tabs'
const key = 'opsweave.ui.layout'
const valid = (value: unknown): ConsoleLayout => value === 'standard' ? 'standard' : 'tabs'
function initial(): ConsoleLayout {
  try { return valid(localStorage.getItem(key)) } catch { return 'tabs' }
}
let layout = initial()
const listeners = new Set<() => void>()
function publish() { for (const listener of listeners) listener() }
function storage(event: StorageEvent) {
  if (event.key === key || event.key === null) { layout = valid(event.newValue); publish() }
}
function subscribe(listener: () => void) {
  if (!listeners.size) window.addEventListener('storage', storage)
  listeners.add(listener)
  return () => { listeners.delete(listener); if (!listeners.size) window.removeEventListener('storage', storage) }
}
export function setConsoleLayout(value: ConsoleLayout) {
  layout = valid(value)
  try { localStorage.setItem(key, layout) } catch { /* Appearance remains usable without browser storage. */ }
  publish()
}
export function useConsoleLayout() { return useSyncExternalStore(subscribe, () => layout, () => 'tabs' as const) }
