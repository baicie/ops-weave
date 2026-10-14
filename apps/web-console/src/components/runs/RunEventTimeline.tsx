import { Button } from '@/components/ui/button'
import type { RunEvent, RunSnapshot } from '../../api/ai-runs.ts'
import { reconciliationText } from './AiRunDetailPanel.tsx'

const TYPE_LABELS: Record<string, string> = {
  SUBMITTED: '提交受理', CLAIMED: '执行器认领', STATUS_CHANGED: '状态流转', TOOL_WAIT: '进入工具等待',
  CANCEL_REQUESTED: '记录取消请求', RECONCILED: '对账披露', FINISHED: '落终态',
}

function payloadText(event: RunEvent) {
  const payload = event.payload
  const parts: string[] = []
  if (payload.status) parts.push(`状态 ${payload.status}`)
  if (typeof payload.attempt === 'number') parts.push(`第 ${payload.attempt} 次尝试`)
  if (payload.reason) parts.push(`原因 ${payload.reason}`)
  if (payload.disclosure) parts.push(reconciliationText(payload.disclosure))
  return parts.join('；')
}

/** Pure display of the ordered event timeline. Requests and live transport stay in the page container. */
export function RunEventTimeline(props: {
  events: RunEvent[]
  gap: boolean
  snapshot: RunSnapshot | null
  hasMore: boolean
  transport: 'idle' | 'sse' | 'polling'
  busy: boolean
  onLoadMore: () => void
}) {
  return (
    <section className="ai-run-events" data-run-events>
      <h3>事件时间线</h3>
      <p className="event-transport" data-run-events-transport={props.transport}>
        {props.transport === 'sse' ? '推送通道已连接（SSE）；断线后从游标续读，重放不产生任何副作用。'
          : props.transport === 'polling' ? '当前使用轮询回读（客户端无流式能力或推送已降级）；与推送同一游标协议。'
            : '事件来自持久记录的游标回读；重放/重连只重新投递记录。'}
      </p>
      {props.gap ? (
        <p role="alert" data-run-events-gap>
          事件缺口：游标已过期，下方是服务端保留窗口内的记录与当前快照，缺失的历史事件不再保留、也不补猜。
        </p>
      ) : null}
      {props.snapshot ? (
        <p data-run-events-snapshot>{`当前快照：${props.snapshot.status} · 版本 ${props.snapshot.statusVersion} · 第 ${props.snapshot.attempt} 次尝试 · ${props.snapshot.updatedAt}`}</p>
      ) : null}
      <ol className="event-list">
        {props.events.map(event => (
          <li key={`${event.runId}-${event.seq}`} data-run-event={event.type}>
            <span className="event-seq">#{event.seq}</span>
            <strong>{TYPE_LABELS[event.type] ?? event.type}</strong>
            <span>{event.type}</span>
            <time dateTime={event.occurredAt}>{event.occurredAt}</time>
            <p>{payloadText(event)}</p>
          </li>
        ))}
      </ol>
      {props.events.length === 0 ? <p>当前保留窗口内没有事件记录。</p> : null}
      {props.hasMore ? (
        <Button variant="outline" disabled={props.busy} onClick={props.onLoadMore} data-run-events-more>
          加载更多记录
        </Button>
      ) : <p role="status">已到当前保留窗口的末尾；空尾页是完整结果。</p>}
    </section>
  )
}
