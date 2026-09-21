/** Zeus compiler 0.1.0 types For items as T, but mountFor passes Accessor<T>. */
export function forItem<T>(value: T | (() => T)): T {
  return typeof value === 'function' ? (value as () => T)() : value
}
