import { onCleanup } from '@zeus-js/zeus'

export function listen(
  node: EventTarget | null,
  type: string,
  handler: EventListener,
): void {
  if (!node) return
  node.addEventListener(type, handler)
  onCleanup(() => node.removeEventListener(type, handler))
}

export function detailOf<T>(event: Event): T | undefined {
  if (!('detail' in event)) return undefined
  return (event as CustomEvent<T>).detail
}
