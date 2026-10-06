import type { ReactNode } from 'react'

export function PageHeader(props: { title: string; description: string; actions?: ReactNode }) {
  return <header className="console-page-header">
    <div><h2>{props.title}</h2><p>{props.description}</p></div>
    {props.actions ? <div className="console-page-actions">{props.actions}</div> : null}
  </header>
}

export function PageBody(props: { children: ReactNode; className?: string }) {
  return <div className={['console-page-body', props.className].filter(Boolean).join(' ')}>{props.children}</div>
}

export function QueryToolbar(props: { children: ReactNode; label?: string }) {
  return <div className="console-query-toolbar" role="group" aria-label={props.label ?? '查询条件与操作'}>{props.children}</div>
}

export function SummaryGrid(props: { children: ReactNode; label: string }) {
  return <div className="console-summary-grid" role="group" aria-label={props.label}>{props.children}</div>
}

export function SummaryCard(props: { label: string; value: ReactNode; hint: string; icon?: ReactNode; stat?: string; tone?: 'success' | 'danger' }) {
  return <div className="console-summary-card" data-tone={props.tone}>
    <div className="console-summary-label">{props.label}{props.icon}</div>
    <strong data-stat={props.stat}>{props.value}</strong><small>{props.hint}</small>
  </div>
}
