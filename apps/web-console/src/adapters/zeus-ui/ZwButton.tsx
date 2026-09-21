import { listen } from './events.ts'

type ButtonVariant = 'default' | 'primary' | 'secondary' | 'outline' | 'ghost' | 'danger'

export function ZwButton(props: {
  variant?: ButtonVariant
  disabled?: boolean
  loading?: boolean
  onPress?: () => void
  children?: JSX.Element
}) {
  return (
    <zw-button
      variant={props.variant ?? 'primary'}
      disabled={props.disabled}
      loading={props.loading}
      ref={(node: HTMLElement | null) => {
        listen(node, 'press', () => props.onPress?.())
      }}
    >
      {props.children}
    </zw-button>
  )
}
