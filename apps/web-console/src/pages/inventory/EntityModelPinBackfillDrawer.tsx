import { useEffect, useId, useMemo, useRef, useState, type FormEvent } from 'react'
import { X } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import type { ModelDefinition } from '../../api/model-catalog.ts'
import {
  BackfillUnknownError, backfillEntityModelPins, readBackfillReceipt,
  type EntityModelPinBackfillCommand, type EntityModelPinBackfillReceipt,
} from '../../api/model-pin-backfill.ts'

type Props = {
  models: ModelDefinition[]
  disabled: boolean
  onClose: () => void
}

const focusableSelector = 'button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), a[href], [tabindex]:not([tabindex="-1"])'

function modelKey(model: ModelDefinition) { return `${model.id}@${model.revision}` }

/** Mirrors the server's entity type mapping; the server rechecks and rejects mismatches. */
function entityTypeOf(model: ModelDefinition): string {
  const builtins: Record<string, string> = {
    'builtin.host': 'Host', 'builtin.application': 'Application', 'builtin.service': 'Service',
    'builtin.database': 'Database', 'builtin.network_interface': 'NetworkInterface',
  }
  return builtins[model.id] ?? model.id
}

function errorMessage(cause: unknown): string {
  if (cause instanceof Error) return cause.message
  return typeof cause === 'string' ? cause : '请求失败'
}

function hasDefiniteHttpStatus(cause: unknown): boolean {
  if (!cause || typeof cause !== 'object' || !('status' in cause)) return false
  const status = (cause as { status?: unknown }).status
  return typeof status === 'number' && Number.isInteger(status) && status >= 400 && status < 500 && status !== 408
}

export function EntityModelPinBackfillDrawer(props: Props) {
  const id = useId()
  const dialog = useRef<HTMLDialogElement | null>(null)
  const returnFocus = useRef<HTMLElement | null>(null)
  const abort = useRef<AbortController | null>(null)
  const callbacks = useRef(props)
  callbacks.current = props
  const models = useMemo(() => props.models.filter(model => model.kind === 'ENTITY'), [props.models])
  const [modelId, setModelId] = useState(() => (models[0] ? modelKey(models[0]) : ''))
  const [maxUpdates, setMaxUpdates] = useState('100')
  const [busy, setBusy] = useState(false)
  const [pendingCommand, setPendingCommand] = useState<EntityModelPinBackfillCommand | null>(null)
  const pendingRef = useRef<EntityModelPinBackfillCommand | null>(null)
  const busyRef = useRef(false)
  const [error, setError] = useState('')
  const [receipt, setReceipt] = useState<EntityModelPinBackfillReceipt | null>(null)
  const selectedModel = models.find(model => modelKey(model) === modelId)
  const locked = busy || pendingCommand !== null
  const readonly = props.disabled || locked || receipt !== null
  const lockedRef = useRef(locked)
  lockedRef.current = locked

  useEffect(() => {
    if (!models.some(model => modelKey(model) === modelId)) setModelId(models[0] ? modelKey(models[0]) : '')
  }, [models, modelId])

  useEffect(() => {
    const element = dialog.current
    if (!element) return
    const drawer = element as HTMLDialogElement
    returnFocus.current = document.activeElement instanceof HTMLElement ? document.activeElement : null
    if (!drawer.open) drawer.showModal()
    const autofocus = drawer.querySelector<HTMLElement>('[data-autofocus]')
    autofocus?.focus()
    function keyboard(event: KeyboardEvent) {
      if (event.key === 'Escape') {
        if (lockedRef.current) event.preventDefault()
        return
      }
      if (event.key !== 'Tab') return
      const focusable = [...drawer.querySelectorAll<HTMLElement>(focusableSelector)]
        .filter(node => !node.hasAttribute('hidden') && node.getAttribute('aria-hidden') !== 'true' && node.getClientRects().length > 0)
      if (!focusable.length) { event.preventDefault(); drawer.focus(); return }
      const first = focusable[0]!, last = focusable[focusable.length - 1]!
      if (event.shiftKey && (document.activeElement === first || !drawer.contains(document.activeElement))) { event.preventDefault(); last.focus() }
      else if (!event.shiftKey && (document.activeElement === last || !drawer.contains(document.activeElement))) { event.preventDefault(); first.focus() }
    }
    drawer.addEventListener('keydown', keyboard)
    return () => {
      drawer.removeEventListener('keydown', keyboard)
      abort.current?.abort()
    }
  }, [])

  function setPending(command: EntityModelPinBackfillCommand | null) {
    pendingRef.current = command
    setPendingCommand(command)
  }

  function close() {
    if (lockedRef.current) return
    dialog.current?.close('dismiss')
  }

  function closed() {
    window.requestAnimationFrame(() => {
      if (returnFocus.current?.isConnected) returnFocus.current.focus()
    })
    callbacks.current.onClose()
  }

  async function send(command: EntityModelPinBackfillCommand, recovering: boolean) {
    if (busyRef.current) return
    const controller = new AbortController()
    abort.current?.abort()
    abort.current = controller
    busyRef.current = true
    setBusy(true)
    setError('')
    try {
      const value = await backfillEntityModelPins(command, controller.signal)
      if (value.requestId.toLowerCase() !== command.requestId.toLowerCase()) throw new Error('服务端回执与本次请求不一致')
      setPending(null)
      setReceipt(value)
    } catch (cause) {
      const message = errorMessage(cause)
      if (recovering || cause instanceof BackfillUnknownError || !hasDefiniteHttpStatus(cause)) setPending(command)
      setError(message)
    } finally {
      busyRef.current = false
      setBusy(false)
    }
  }

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!selectedModel || props.disabled || pendingRef.current || busyRef.current || receipt) return
    const budget = Number(maxUpdates)
    if (!Number.isSafeInteger(budget) || budget < 1 || budget > 500) { setError('每次回填请指定 1–500 条的处理上限。'); return }
    void send({
      requestId: crypto.randomUUID(),
      entityType: entityTypeOf(selectedModel),
      model: { id: selectedModel.id, revision: selectedModel.revision },
      maxUpdates: budget,
    }, false)
  }

  async function confirmPending() {
    const command = pendingRef.current
    if (!command) return
    // An explicit lookup of the original request is safe before resending the identical body.
    try {
      const stored = await readBackfillReceipt(command, abort.current?.signal ?? new AbortController().signal)
      if (stored) {
        setPending(null)
        setReceipt(stored)
        setError('')
        return
      }
    } catch (cause) {
      if (cause instanceof DOMException && cause.name === 'AbortError') return
    }
    void send(command, true)
  }

  return <dialog ref={dialog} className="model-drawer source-drawer entity-instance-drawer" aria-label="回填实体模型版本" tabIndex={-1}
    onCancel={event => { if (lockedRef.current) event.preventDefault() }} onClose={closed}>
    <header className="model-drawer-heading">
      <div><div className="model-eyebrow">旧实体记录</div><h3 id={id + '-title'}>回填模型版本</h3></div>
      <button type="button" className="model-close" aria-label="关闭回填模型版本" disabled={locked} onClick={close}><X size={18} /></button>
    </header>
    <div className="model-drawer-body">
      <form id={id + '-form'} onSubmit={submit}>
        <fieldset className="model-form" disabled={readonly}>
          <p className="model-muted">按指定的已发布模型版本逐条核对旧记录；只有完全符合的记录写入版本指纹。无法确定、来源维护或已有指纹的记录保持不动并在回执计数，不做猜测。</p>
          <label htmlFor={id + '-model'}>模型版本
            <select id={id + '-model'} data-autofocus required value={modelId} onChange={event => { setModelId(event.target.value); setError('') }}>
              <option value="">请选择模型版本</option>
              {models.map(model => <option key={modelKey(model)} value={modelKey(model)}>{model.label} · {model.id}@{model.revision}</option>)}
            </select>
          </label>
          <label htmlFor={id + '-entity-type'}>实体类型
            <Input id={id + '-entity-type'} value={selectedModel ? entityTypeOf(selectedModel) : ''} readOnly aria-readonly="true" />
          </label>
          <label htmlFor={id + '-max'}>本次处理上限（1–500）
            <Input id={id + '-max'} type="number" min={1} max={500} step={1} required value={maxUpdates}
              onChange={event => { setMaxUpdates(event.target.value); setError('') }} />
          </label>
        </fieldset>
      </form>
      {pendingCommand ? <section className="source-pending" role="status">
        <p>回填结果未知；保留了原请求编号和内容，可按原内容重试并读取原回执。</p>
        <code>{pendingCommand.requestId}</code>
      </section> : null}
      {receipt ? <section className="model-preview" data-backfill-receipt role="status">
        <strong>{receipt.replayed ? '已按原请求读取回填回执' : '回填完成'}</strong>
        <p>{`核对 ${receipt.counts.scanned} 条 · 写入版本 ${receipt.counts.updated} 条 · 保持无版本 ${receipt.counts.keptWithoutPin} 条`}</p>
        <p>{`已有版本跳过 ${receipt.counts.skippedPinned} 条 · 来源维护跳过 ${receipt.counts.skippedSource} 条`}</p>
        <p className="model-muted">{`请求 ${receipt.requestId} · 完成时间 ${receipt.completedAt}`}</p>
        <p className="model-muted">计数只描述本次命令；未处理的候选记录可在更换请求编号后继续回填。</p>
      </section> : null}
      {error ? <p role="alert">{error}</p> : null}
    </div>
    <footer className="model-drawer-footer">
      {receipt ? <Button type="button" variant="outline" onClick={close}>关闭</Button>
        : pendingCommand ? <Button type="button" variant="outline" disabled={busy || props.disabled} onClick={() => { void confirmPending() }}>查询原请求结果（原样重试）</Button>
          : <Button type="submit" form={id + '-form'} disabled={props.disabled || busy || !selectedModel}>{busy ? '正在回填…' : '开始回填'}</Button>}
    </footer>
  </dialog>
}
