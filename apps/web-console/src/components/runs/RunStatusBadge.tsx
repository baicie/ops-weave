type RunStatus = 'SUCCEEDED' | 'PARTIAL' | 'FAILED' | 'FILTERED' | 'ACCEPTED' | 'REJECTED' | 'RUNNING'

/** Shared short status treatment for operational run tables and drawers. */
export function RunStatusBadge(props: { status: RunStatus | string; label: string }) {
  return <span className="run-badge" data-outcome={props.status}>{props.label}</span>
}
