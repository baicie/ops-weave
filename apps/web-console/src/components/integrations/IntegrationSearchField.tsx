import { Search } from 'lucide-react'

export function IntegrationSearchField(p: {
  label: string
  placeholder: string
  value: string
  onChange: (value: string) => void
  clearLabel?: string
}) {
  return <label className="integration-search">
    <Search size={16}/>
    <input aria-label={p.label} placeholder={p.placeholder} value={p.value} onChange={e => p.onChange(e.target.value)}/>
    {p.value && p.clearLabel ? <button type="button" data-slot="button" aria-label={p.clearLabel} onClick={() => p.onChange('')}>×</button> : null}
  </label>
}
