import { Button } from '@/components/ui/button'
import type { AiRun, Reconciliation } from '../../api/ai-runs.ts'

const STATUS_LABELS: Record<string, string> = {
  PENDING: '待认领', RUNNING: '执行中', WAITING_TOOL: '工具等待', SUCCEEDED: '已完成', FAILED: '已失败', CANCELLED: '已取消',
}
const REASON_LABELS: Record<string, string> = {
  RUNTIME_UNAVAILABLE: '运行时不可用', DEADLINE: '超时', BUDGET_EXHAUSTED: '预算用尽',
  AUTHORIZATION_REVOKED: '授权已撤回', RESULT_LOST: '结果丢失', EXECUTION_FAILED: '执行失败', CANCELLED_BY_REQUEST: '按请求取消',
}

export function reconciliationText(value: Reconciliation) {
  const parts = [`已有模型调用 ${value.priorModelCalls} 次`, `结果未知费用 ${value.uncertainPriorFees} 次`]
  if (value.adoptedPriorResult) parts.push('认领了此前已保存的完整结果')
  if (value.rerun) parts.push('无既有费用，重新执行')
  if (value.duplicateFeeRisk) parts.push('费用已花而结果丢失：继续诊断须新提交并再次计费')
  return parts.join('；')
}

/** Pure display of one run record. Requests, identity and business gates stay in the page container. */
export function AiRunDetailPanel(props: {
  run: AiRun
  busy: boolean
  pending: { kind: 'cancel' | 'retry'; requestId: string } | null
  unknown: boolean
  onCancel: () => void
  onRetry: () => void
  onRefresh: () => void
  onResend: () => void
}) {
  const run = props.run
  const unfinished = ['PENDING', 'RUNNING', 'WAITING_TOOL'].includes(run.status)
  return (
    <section className="ai-run-detail" data-run-detail={run.runId}>
      <h3>运行详情</h3>
      <p data-run-status={`run-${run.runId}-status`}>
        <span className="run-badge" data-outcome={run.status}>{STATUS_LABELS[run.status] ?? run.status}</span>
        <span>{`状态 ${run.status} · 版本 ${run.statusVersion} · 第 ${run.attempt} 次尝试`}</span>
      </p>
      <p>{`运行 ${run.runId}`}</p>
      <p>{`提问：${run.question}`}</p>
      <p>{`时间窗 ${run.timeRange.from} ~ ${run.timeRange.to} · 知识模式 ${run.knowledgeMode}`}</p>
      <p>{`Skill ${run.skill.id}@${run.skill.version} · ${run.skill.digest}`}</p>
      <p>{`受理 ${run.createdAt} · 最近变更 ${run.updatedAt}`}</p>
      {run.cancelRequestedAt ? <p data-run-cancel-requested>{`已记录取消请求：${run.cancelRequestedAt}（执行器在安全点落 CANCELLED；取消后仍可能完成）`}</p> : null}
      {run.reason ? <p data-run-reason>{`落定原因：${REASON_LABELS[run.reason] ?? run.reason}（${run.reason}）`}</p> : null}
      {run.disclosure ? (
        <section data-run-disclosure>
          <h4>对账披露</h4>
          <p>{reconciliationText(run.disclosure)}</p>
          <p>费用照实保留、不退；本页不声称跨外部服务 exactly-once。</p>
        </section>
      ) : null}
      {!run.disclosure && run.attempt > 0 ? <p>本运行尚无未知完成状态对账披露。</p> : null}
      <div className="ai-run-actions">
        <Button variant="outline" disabled={props.busy} onClick={props.onRefresh}>刷新运行记录</Button>
        <Button variant="outline" disabled={props.busy || !unfinished || props.pending !== null} onClick={props.onCancel}>
          请求取消
        </Button>
        <Button variant="outline" disabled={props.busy || !unfinished || props.pending !== null} onClick={props.onRetry}>
          恢复/重试（同运行新尝试）
        </Button>
      </div>
      {run.status === 'SUCCEEDED' ? <p role="status">完整结论由 AIInsight 保存并授权读取，本页只显示运行记录。</p> : null}
      {!unfinished ? <p role="status">已终止运行不再迁移：取消是幂等无操作，重试不会改写结论；新的诊断请重新提交。</p> : null}
      {props.pending ? (
        <section data-run-pending>
          <h4>{props.pending.kind === 'cancel' ? '取消请求' : '恢复请求'}已提交</h4>
          <p>{`原请求标识 ${props.pending.requestId}`}</p>
          {props.unknown ? (
            <>
              <p role="alert">提交结果待确认。已保留原请求标识，不自动重试、不改用新标识。</p>
              <div className="ai-run-actions">
                <Button variant="outline" disabled={props.busy} onClick={props.onRefresh}>查询运行当前状态</Button>
                <Button variant="outline" disabled={props.busy} onClick={props.onResend}>原样重发同一请求</Button>
              </div>
            </>
          ) : <p role="status">正在等待回执…</p>}
        </section>
      ) : null}
    </section>
  )
}
