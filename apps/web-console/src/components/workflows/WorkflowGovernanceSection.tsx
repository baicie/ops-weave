import { useCallback, useEffect, useRef, useState } from 'react'
import {
  confirmRetentionCleanup, deleteEntity, previewRetentionCleanup, readCollectionBindings,
  readRetentionBudgets, submitCollectionBinding,
  type CollectionBinding, type CollectionBindingCommand, type RetentionPreview, type RetentionReport,
} from '../../api/governance.ts'

/**
 * Governance surface for the explicit collection binding, the receipt retention cleanup and the
 * operational delete entry. Publishing never appears here as an enable action: binding is its own
 * command with its own reason and its own receipt. Reads never clean and never delete, and an unknown
 * submit result keeps the original request so the operator can resend it byte for byte.
 */
export function WorkflowGovernanceSection({ identity }: { identity: string }) {
  const [bindings, setBindings] = useState<CollectionBinding[]>([])
  const [budgets, setBudgets] = useState<RetentionReport[]>([])
  const [preview, setPreview] = useState<RetentionPreview | null>(null)
  const [reason, setReason] = useState('')
  const [target, setTarget] = useState('')
  const [message, setMessage] = useState<string | null>(null)
  const [pending, setPending] = useState<{ label: string; run: () => Promise<void> } | null>(null)
  const [busy, setBusy] = useState(false)
  const [open, setOpen] = useState(false)
  const controller = useRef<AbortController | null>(null)

  // Private governance state never survives an identity change.
  useEffect(() => {
    setBindings([]); setBudgets([]); setPreview(null); setPending(null); setMessage(null); setReason(''); setTarget('')
  }, [identity])

  const load = useCallback(async () => {
    controller.current?.abort()
    const abort = new AbortController()
    controller.current = abort
    try {
      setBindings(await readCollectionBindings(abort.signal))
      setBudgets(await readRetentionBudgets(abort.signal))
      setMessage(null)
    } catch (failure) {
      if ((failure as Error).name !== 'AbortError') setMessage((failure as Error).message)
    }
  }, [])
  // Governance reads happen only after an explicit expand, so opening the page never issues them.
  useEffect(() => { if (open) void load(); return () => controller.current?.abort() }, [open, load])

  /** An unknown submit result keeps the original request; only an explicit retry resends it. */
  const run = useCallback(async (label: string, action: () => Promise<void>) => {
    setBusy(true)
    setPending({ label, run: action })
    try {
      await action()
      setPending(null)
      setMessage(null)
      await load()
    } catch (failure) {
      setMessage((failure as Error).message)
    } finally {
      setBusy(false)
    }
  }, [load])

  const bind = (item: CollectionBinding, operation: 'ENABLE' | 'DISABLE') => {
    const command: CollectionBindingCommand = {
      requestId: crypto.randomUUID(), id: item.id, revision: item.revision, digest: item.digest,
      sourceInstanceId: item.sourceInstanceId, operation, reason: reason.trim(),
    }
    void run('采集绑定', async () => { await submitCollectionBinding(command, controller.current!.signal) })
  }

  const cleanup = (report: RetentionReport) => {
    void run('回执清理', async () => {
      const planned = await previewRetentionCleanup(controller.current!.signal, { kind: report.kind, scopeKey: report.scopeKey, maxRemove: 50 })
      setPreview(planned)
      if (planned.removable === 0) return
      await confirmRetentionCleanup({
        requestId: crypto.randomUUID(), kind: planned.kind, scopeKey: planned.scopeKey,
        previewDigest: planned.previewDigest, reason: reason.trim(), maxRemove: planned.maxRemove,
      }, controller.current!.signal)
      setPreview(null)
    })
  }

  const remove = () => {
    const entityId = target.trim()
    void run('实体删除', async () => {
      await deleteEntity({ requestId: crypto.randomUUID(), entityId, reason: reason.trim() }, controller.current!.signal)
    })
  }

  return (
    <section className="workflow-governance" aria-label="采集绑定与留存治理">
      <h2>采集绑定与留存治理</h2>
      <p className="text-muted-foreground">发布不会自动启用采集；只有显式启用绑定后才允许受控批次消费。留存清理与删除都是显式命令，读取不会清理。</p>
      {!open ? <button type="button" onClick={() => setOpen(true)}>展开治理操作</button> : null}
    <div className="flex flex-wrap gap-2">
        <label className="flex flex-col gap-1">
          <span>理由（必填）</span>
          <input value={reason} onChange={e => setReason(e.target.value)} maxLength={256} className="border px-2 py-1" />
        </label>
        <button type="button" onClick={() => void load()} disabled={busy}>重新读取</button>
        {pending && <button type="button" onClick={() => void run(pending.label, pending.run)} disabled={busy}>按原请求标识确认{pending.label}</button>}
      </div>
      {message && <p role="alert" className="text-destructive">{message}</p>}
      <h3>采集绑定</h3>
      <table>
        <thead><tr><th>工作流</th><th>版本</th><th>来源实例</th><th>状态</th><th>操作</th></tr></thead>
        <tbody>
          {bindings.map(item => (
            <tr key={item.id + ':' + item.revision}>
              <td>{item.id}</td><td>{item.revision}</td><td>{item.sourceInstanceId}</td><td>{item.state}</td>
              <td className="flex gap-2">
                <button type="button" onClick={() => bind(item, 'ENABLE')} disabled={busy || reason.trim().length === 0}>启用采集绑定</button>
                <button type="button" onClick={() => bind(item, 'DISABLE')} disabled={busy || reason.trim().length === 0}>解除采集绑定</button>
              </td>
            </tr>
          ))}
          {bindings.length === 0 && <tr><td colSpan={5}>暂无采集绑定记录。</td></tr>}
        </tbody>
      </table>
      <h3>回执保留预算</h3>
      <table>
        <thead><tr><th>回执类型</th><th>范围</th><th>可清理</th><th>受保护</th><th>上限</th><th>状态</th><th>操作</th></tr></thead>
        <tbody>
          {budgets.map(item => (
            <tr key={item.kind + ':' + item.scopeKey}>
              <td>{item.kind}</td><td>{item.scopeKey}</td><td>{item.kept}</td><td>{item.keptProtected}</td>
              <td>{item.maxPerScope}</td><td>{item.status}</td>
              <td><button type="button" onClick={() => cleanup(item)} disabled={busy || reason.trim().length === 0}>预览并确认清理</button></td>
            </tr>
          ))}
          {budgets.length === 0 && <tr><td colSpan={7}>暂无回执留存预算。</td></tr>}
        </tbody>
      </table>
      {preview && (
        <div className="border p-2" role="status">
          <p>清理预览：可移除 {preview.removable} 条，保留受保护 {preview.keptProtected} 条，未结束记录 {preview.keptUnfinished} 条（从不删除）。</p>
          <ul>{preview.candidates.map(id => <li key={id}>{id}</li>)}</ul>
        </div>
      )}
      <h3>运维删除入口</h3>
      <label className="flex flex-col gap-1">
        <span>实体标识</span>
        <input value={target} onChange={e => setTarget(e.target.value)} className="border px-2 py-1" />
      </label>
      <button type="button" onClick={remove} disabled={busy || reason.trim().length === 0 || target.trim().length === 0}>受权删除该实体（软删除）</button>
    </section>
  )
}
