import { detailOf, listen } from './events.ts'

export function ZwInput(props: {
  type?: 'text' | 'password' | 'search' | 'email'
  placeholder?: string
  autocomplete?: string
  disabled?: boolean
  value?: string
  onValueChange?: (value: string) => void
}) {
  return (
    <zw-input
      type={props.type ?? 'text'}
      placeholder={props.placeholder}
      autocomplete={props.autocomplete}
      disabled={props.disabled}
      prop:value={props.value}
      ref={(node: HTMLElement | null) => {
        listen(node, 'value-change', event => {
          const detail = detailOf<{ value?: string }>(event)
          if (typeof detail?.value === 'string') props.onValueChange?.(detail.value)
        })
      }}
    />
  )
}
