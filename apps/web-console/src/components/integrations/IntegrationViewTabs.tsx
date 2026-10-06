import { useRef } from 'react'

/** Navigation only; reads and query state belong to the page. */
export function IntegrationViewTabs<T extends string>(props: {
  label: string
  value: T
  items: { id: T; label: string }[]
  change: (value: T) => void
}) {
  const list = useRef<HTMLDivElement>(null)
  return <div className="integration-tabs" role="tablist" aria-label={props.label} ref={list}>
    {props.items.map((item, index) => <button key={item.id} type="button" data-slot="button" role="tab" aria-selected={props.value === item.id} tabIndex={props.value === item.id ? 0 : -1} onClick={() => props.change(item.id)} onKeyDown={event => {
      if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return
      event.preventDefault()
      const next = event.key === 'Home' ? 0 : event.key === 'End' ? props.items.length - 1 : (index + (event.key === 'ArrowRight' ? 1 : -1) + props.items.length) % props.items.length
      props.change(props.items[next]!.id)
      list.current?.querySelectorAll<HTMLButtonElement>('button')[next]?.focus()
    }}>{item.label}</button>)}
  </div>
}
