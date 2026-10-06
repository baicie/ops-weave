import { useEffect, useId, useMemo, useRef, useState, type ChangeEvent, type FormEvent } from 'react'
import { X } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { createEntityInstance, type EntityItem } from '../../api/entities.ts'
import type { ModelDefinition, ModelField } from '../../api/model-catalog.ts'

type EntityLifecycle = 'DISCOVERED' | 'ACTIVE' | 'INACTIVE' | 'DELETED' | 'ARCHIVED'
type EntityInstanceCommand = {
  requestId: string
  entityId: string
  model: { id: string; revision: number }
  name: string
  lifecycle?: EntityLifecycle
  attributes: Record<string, string | number | boolean>
  expectedVersion?: number
}

type Props = {
  models: ModelDefinition[]
  publishedTruncated: boolean
  disabled: boolean
  onClose: () => void
  onCreated: (entity: EntityItem, model: ModelDefinition) => void
}

const focusableSelector = 'button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), a[href], [tabindex]:not([tabindex="-1"])'

function modelKey(model: ModelDefinition) { return `${model.id}@${model.revision}` }

function errorMessage(cause: unknown): string {
  if (cause instanceof Error) return cause.message
  return typeof cause === 'string' ? cause : '请求失败'
}

function hasDefiniteHttpStatus(cause: unknown): boolean {
  if (!cause || typeof cause !== 'object' || !('status' in cause)) return false
  const status = (cause as { status?: unknown }).status
  return typeof status === 'number' && Number.isInteger(status) && status >= 400 && status < 500 && status !== 408
}

function attributesFor(model: ModelDefinition, values: Record<string, string>): Record<string, string | number | boolean> {
  const attributes: Record<string, string | number | boolean> = {}
  for (const field of model.fields) {
    const raw = values[field.id] ?? ''
    if (!raw.trim()) {
      if (field.required) throw new Error(`请填写必填字段：${field.label}`)
      continue
    }

    let value: string | number | boolean
    switch (field.type) {
      case 'TEXT':
        value = raw.trim()
        if (value.length > (field.maxLength ?? 2048)) throw new Error(`字段“${field.label}”超出最大长度`)
        break
      case 'ENUM':
        value = raw
        if (!field.choices?.includes(raw)) throw new Error(`字段“${field.label}”不在允许的选项范围内`)
        if (raw.length > (field.maxLength ?? 2048)) throw new Error(`字段“${field.label}”超出最大长度`)
        break
      case 'INTEGER':
      case 'DECIMAL': {
        const number = Number(raw)
        if (!Number.isFinite(number) || (field.type === 'INTEGER' && !Number.isSafeInteger(number))) {
          throw new Error(`字段“${field.label}”不是有效的${field.type === 'INTEGER' ? '整数' : '数值'}`)
        }
        if (Math.abs(number) > Number.MAX_SAFE_INTEGER || (field.min !== undefined && number < field.min) || (field.max !== undefined && number > field.max)) {
          throw new Error(`字段“${field.label}”超出允许范围`)
        }
        value = number
        break
      }
      case 'BOOLEAN':
        if (raw !== 'true' && raw !== 'false') throw new Error(`请选择字段“${field.label}”的值`)
        value = raw === 'true'
        break
      case 'DATETIME': {
        const parsed = new Date(raw)
        if (!Number.isFinite(parsed.getTime())) throw new Error(`字段“${field.label}”不是有效时间`)
        value = parsed.toISOString()
        break
      }
    }
    attributes[field.id] = value
  }
  return attributes
}

function fieldControl(field: ModelField, id: string, value: string, disabled: boolean, change: (value: string) => void) {
  const common = { id, name: field.id, required: field.required, disabled, value, onChange: (event: ChangeEvent<HTMLInputElement | HTMLSelectElement>) => change(event.target.value) }
  if (field.type === 'ENUM' || field.type === 'BOOLEAN') {
    return <select {...common} aria-label={field.label}>
      <option value="">{field.required ? '请选择' : '不设置'}</option>
      {field.type === 'BOOLEAN' ? <><option value="true">是</option><option value="false">否</option></> : field.choices?.map(choice => <option key={choice} value={choice}>{choice}</option>)}
    </select>
  }
  const type = field.type === 'INTEGER' || field.type === 'DECIMAL' ? 'number' : field.type === 'DATETIME' ? 'datetime-local' : 'text'
  return <Input {...common} type={type} maxLength={field.type === 'TEXT' ? field.maxLength : undefined}
    min={field.type === 'INTEGER' || field.type === 'DECIMAL' ? field.min : undefined}
    max={field.type === 'INTEGER' || field.type === 'DECIMAL' ? field.max : undefined}
    step={field.type === 'INTEGER' ? 1 : field.type === 'DECIMAL' ? 'any' : undefined} aria-label={field.label} />
}

export function EntityInstanceDrawer(props: Props) {
  const id = useId()
  const dialog = useRef<HTMLDialogElement | null>(null)
  const returnFocus = useRef<HTMLElement | null>(null)
  const abort = useRef<AbortController | null>(null)
  const callbacks = useRef(props)
  callbacks.current = props
  const [modelId, setModelId] = useState(() => {
    const first = props.models.find(model => model.kind === 'ENTITY')
    return first ? modelKey(first) : ''
  })
  const [name, setName] = useState('')
  const [values, setValues] = useState<Record<string, string>>({})
  const [busy, setBusy] = useState(false)
  const [pendingCommand, setPendingCommand] = useState<EntityInstanceCommand | null>(null)
  const pendingRef = useRef<EntityInstanceCommand | null>(null)
  const busyRef = useRef(false)
  const [error, setError] = useState('')
  const models = useMemo(() => props.models.filter(model => model.kind === 'ENTITY'), [props.models])
  const selectedModel = models.find(model => modelKey(model) === modelId)
  const locked = busy || pendingCommand !== null
  const lockedRef = useRef(locked)
  lockedRef.current = locked

  useEffect(() => {
    if (!models.some(model => modelKey(model) === modelId)) {
      setModelId(models[0] ? modelKey(models[0]) : '')
      setValues({})
    }
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

  function setPending(command: EntityInstanceCommand | null) {
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

  async function send(command: EntityInstanceCommand, recovering: boolean) {
    if (busyRef.current) return
    const controller = new AbortController()
    abort.current?.abort()
    abort.current = controller
    busyRef.current = true
    lockedRef.current = true
    setBusy(true)
    setError('')
    let created: EntityItem | null = null
    try {
      const result = await createEntityInstance(command, controller.signal)
      if (result.entity.id !== command.entityId || result.model.id !== command.model.id || result.model.revision !== command.model.revision) {
        throw new Error('服务端回执与本次请求不一致')
      }
      created = result.entity
    } catch (cause) {
      const message = errorMessage(cause)
      if (recovering || !hasDefiniteHttpStatus(cause)) {
        setPending(command)
        setError(message)
      } else {
        setError(message)
      }
    } finally {
      busyRef.current = false
      lockedRef.current = pendingRef.current !== null
      setBusy(false)
    }
    if (!created) return
    setPending(null)
    callbacks.current.onCreated(created, selectedModel!)
    dialog.current?.close('created')
  }

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!selectedModel || props.disabled || pendingRef.current || busyRef.current) return
    const cleanName = name.trim()
    if (!cleanName || cleanName.length > 255) { setError('请输入不超过 255 个字符的名称。'); return }
    let attributes: Record<string, string | number | boolean>
    try { attributes = attributesFor(selectedModel, values) }
    catch (cause) { setError(errorMessage(cause)); return }
    const command: EntityInstanceCommand = {
      requestId: crypto.randomUUID(),
      entityId: crypto.randomUUID(),
      model: { id: selectedModel.id, revision: selectedModel.revision },
      name: cleanName,
      lifecycle: 'ACTIVE',
      attributes,
    }
    void send(command, false)
  }

  return <dialog ref={dialog} className="model-drawer source-drawer entity-instance-drawer" aria-label="创建实体实例" tabIndex={-1}
    onCancel={event => { if (lockedRef.current) event.preventDefault() }} onClose={closed}>
    <header className="model-drawer-heading">
      <div><div className="model-eyebrow">实体实例</div><h3 id={id + '-title'}>创建实体</h3></div>
      <button type="button" className="model-close" aria-label="关闭创建实体" disabled={locked} onClick={close}><X size={18} /></button>
    </header>
    <div className="model-drawer-body">
      <form id={id + '-form'} onSubmit={submit}>
      <fieldset className="model-form" disabled={props.disabled || locked}>
        <label htmlFor={id + '-model'}>实体类型
          <select id={id + '-model'} data-autofocus required value={modelId} onChange={event => { setModelId(event.target.value); setValues({}); setError('') }}>
            <option value="">请选择实体类型</option>
            {models.map(model => <option key={modelKey(model)} value={modelKey(model)}>{model.label} · {model.id}@{model.revision}</option>)}
          </select>
        </label>
        {props.publishedTruncated ? <p role="status">已发布模型目录超过展示上限，部分实体类型未显示。</p> : null}
        <label htmlFor={id + '-name'}>名称
          <Input id={id + '-name'} maxLength={255} required value={name} onChange={event => setName(event.target.value)} />
        </label>
        {selectedModel?.fields.map(field => <label key={field.id} htmlFor={id + '-' + field.id}>
          <span>{field.label}{field.required ? ' · 必填' : ''}<small className="model-muted">{field.id}</small></span>
          {fieldControl(field, id + '-' + field.id, values[field.id] ?? '', props.disabled || locked, value => {
            setValues(current => ({ ...current, [field.id]: value })); setError('')
          })}
        </label>)}
        {!models.length ? <p role="status">没有可创建实例的实体模型。</p> : null}
      </fieldset>
      </form>
      {pendingCommand ? <section className="source-pending" role="status">
        <p>提交结果未知；保留了原请求编号和内容，可按原内容重试并读取原回执。</p>
        <code>{pendingCommand.requestId}</code>
      </section> : null}
      {error ? <p role="alert">{error}</p> : null}
    </div>
    <footer className="model-drawer-footer">
      {pendingCommand ? <Button type="button" variant="outline" disabled={busy || props.disabled} onClick={() => { void send(pendingCommand, true) }}>查询原请求结果（原样重试）</Button>
        : <Button type="submit" form={id + '-form'} disabled={props.disabled || busy || !selectedModel}>{busy ? '正在创建…' : '创建实体'}</Button>}
    </footer>
  </dialog>
}
