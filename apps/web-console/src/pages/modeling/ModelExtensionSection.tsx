import { useEffect, useRef, useState, type ChangeEvent } from 'react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import {
  extensionDigest, parseExtension, readModelExtensions, saveModelExtension, publishModelExtension,
  type ModelExtension, type ModelExtensionEntry, type ModelExtensionField,
} from '../../api/model-extensions.ts'
import type { ModelRef } from '../../api/model-catalog.ts'

type Props = {
  base: ModelRef
  /** Remaining combined field budget: base body fields + extension fields stay bounded. */
  budget: number
  disabled: boolean
}

const types = ['TEXT', 'INTEGER', 'DECIMAL', 'BOOLEAN', 'ENUM', 'DATETIME'] as const
const reserved = ['id', 'tenant_id', 'tenantId', 'version', 'source', 'source_ref', 'created_at', 'updated_at', '__proto__', 'constructor', 'prototype']
const typeLabels: Record<string, string> = { TEXT: '文本', INTEGER: '整数', DECIMAL: '十进制数', BOOLEAN: '布尔', ENUM: '枚举', DATETIME: '日期时间' }

function errorMessage(cause: unknown): string {
  if (cause instanceof Error) return cause.message
  return typeof cause === 'string' ? cause : '请求失败'
}

function hasDefiniteHttpStatus(cause: unknown): boolean {
  if (!cause || typeof cause !== 'object' || !('status' in cause)) return false
  const status = (cause as { status?: unknown }).status
  return typeof status === 'number' && Number.isInteger(status) && status >= 400 && status < 500 && status !== 408
}

function newField(index: number, taken: Set<string>): ModelExtensionField {
  let n = index
  while (taken.has('field_' + n)) n++
  return { id: 'field_' + n, label: '新扩展字段', type: 'TEXT', required: false, maxLength: 255 }
}

/**
 * Tenant extension fields for one fixed base model version. The base body is never edited here:
 * overlays only add optional bounded scalar fields, drafts stay owner-private and publications are
 * immutable additive revisions.
 */
export function ModelExtensionSection(props: Props) {
  const [state, setState] = useState<'loading' | 'ready' | 'failed'>('loading')
  const [fields, setFields] = useState<ModelExtensionField[]>([])
  const [saved, setSaved] = useState<ModelExtensionEntry | null>(null)
  const [published, setPublished] = useState<ModelExtension[]>([])
  const [dirty, setDirty] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [pending, setPending] = useState<'save' | 'publish' | null>(null)
  const pendingRef = useRef<'save' | 'publish' | null>(null)
  const busyRef = useRef(false)
  const abort = useRef<AbortController | null>(null)

  useEffect(() => {
    void refresh()
    return () => { abort.current?.abort() }
    // The base identity keys the section: switching versions remounts and clears local state.
  }, [props.base.id, props.base.revision])

  async function refresh() {
    const controller = new AbortController()
    abort.current?.abort(); abort.current = controller
    setState('loading'); setError(''); setNotice('')
    try {
      const page = await readModelExtensions(controller.signal)
      const forBase = (entry: ModelExtensionEntry) => entry.extension.base.id === props.base.id && entry.extension.base.revision === props.base.revision
      const mine = page.drafts.items.filter(forBase).map(entry => entry.extension)
      const versions = page.published.items.filter(forBase).map(entry => entry.extension)
      const latest = versions[0] ?? null
      setPublished(versions)
      if (mine.length) {
        const draft = page.drafts.items.filter(forBase)[0]!
        setSaved(draft)
        setFields(draft.extension.fields.map(field => ({ ...field })))
      } else {
        setSaved(null)
        setFields((latest?.fields ?? []).map(field => ({ ...field })))
      }
      setDirty(false)
      setState('ready')
    } catch (cause) {
      if (cause instanceof DOMException && cause.name === 'AbortError') return
      setState('failed')
      setError(errorMessage(cause))
    }
  }

  function change(index: number, patch: Partial<ModelExtensionField>) {
    setFields(current => current.map((field, i) => i === index ? { ...field, ...patch } as ModelExtensionField : field))
    setDirty(true); setError(''); setNotice('')
  }
  function remove(index: number) {
    setFields(current => current.filter((_, i) => i !== index))
    setDirty(true); setError(''); setNotice('')
  }
  function add() {
    setFields(current => [...current, newField(current.length + 1, new Set(current.map(field => field.id)))])
    setDirty(true); setError(''); setNotice('')
  }
  function typeChanged(index: number, type: ModelExtensionField['type']) {
    const field = fields[index]
    if (!field) return
    const next: ModelExtensionField = {
      id: field.id, label: field.label, type, required: false,
      ...(['TEXT', 'ENUM'].includes(type) ? { maxLength: 255 } : {}),
      ...(type === 'ENUM' ? { choices: ['value'] } : {}),
    }
    change(index, next)
  }

  function validate(): ModelExtensionField[] | null {
    const ids = new Set<string>()
    for (const field of fields) {
      if (!/^[a-z][a-z0-9_]{0,47}$/.test(field.id) || reserved.includes(field.id)) { setError(`字段标识“${field.id}”不合法或属于系统保留名。`); return null }
      if (ids.has(field.id)) { setError(`字段标识“${field.id}”重复。`); return null }
      ids.add(field.id)
      if (!field.label.trim() || field.label.length > 80) { setError(`字段“${field.id}”的显示名称不合法。`); return null }
      if (['TEXT', 'ENUM'].includes(field.type)) {
        if (!Number.isSafeInteger(field.maxLength) || (field.maxLength as number) < 1 || (field.maxLength as number) > 2048) { setError(`字段“${field.id}”需要 1–2048 的最大长度。`); return null }
      } else if (field.maxLength !== undefined) { setError(`字段“${field.id}”的类型不支持最大长度。`); return null }
      if (field.type === 'ENUM') {
        const choices = field.choices ?? []
        if (!choices.length || choices.length > 32 || new Set(choices).size !== choices.length || choices.some(choice => !choice.trim() || choice !== choice.trim())) {
          setError(`字段“${field.id}”的枚举选项不合法。`); return null
        }
      } else if (field.choices !== undefined) { setError(`字段“${field.id}”的类型不支持枚举选项。`); return null }
      if (field.min !== undefined || field.max !== undefined) {
        if (!['INTEGER', 'DECIMAL'].includes(field.type)) { setError(`字段“${field.id}”的类型不支持数值范围。`); return null }
        for (const bound of [field.min, field.max]) if (bound !== undefined && (!Number.isFinite(bound) || Math.abs(bound) > Number.MAX_SAFE_INTEGER || field.type === 'INTEGER' && !Number.isSafeInteger(bound))) {
          setError(`字段“${field.id}”的数值范围不合法。`); return null
        }
        if (field.min !== undefined && field.max !== undefined && field.min > field.max) { setError(`字段“${field.id}”的数值范围颠倒。`); return null }
      }
    }
    if (!fields.length) { setError('请至少声明一个可选扩展字段。'); return null }
    if (fields.length > props.budget) { setError(`扩展字段与模型字段合计不能超过 32 个；当前最多可添加 ${props.budget} 个。`); return null }
    return fields
  }

  async function send(action: 'save' | 'publish', recovering: boolean) {
    if (busyRef.current) return
    const clean = action === 'save' ? validate() : fields
    if (action === 'save' && !clean) return
    const controller = new AbortController()
    abort.current?.abort(); abort.current = controller
    busyRef.current = true
    setBusy(true); setError('')
    let entry: ModelExtensionEntry
    try {
      if (action === 'save') {
        const expected = saved?.state === 'DRAFT' ? saved.editVersion : 0
        entry = await saveModelExtension(props.base, clean!, expected, controller.signal)
      } else {
        const draft = saved
        if (!draft || draft.state !== 'DRAFT') { busyRef.current = false; setBusy(false); return }
        entry = await publishModelExtension(draft, controller.signal)
      }
    } catch (cause) {
      const message = errorMessage(cause)
      // Definite 4xx answers are known results; everything else keeps the command for an identical retry.
      if (recovering || !hasDefiniteHttpStatus(cause)) { pendingRef.current = action; setPending(action) }
      setError(message)
      busyRef.current = false
      setBusy(false)
      return
    }
    try {
      if (action === 'save') {
        // Contract check: the stored draft must digest exactly the submitted overlay content.
        const digest = await extensionDigest({ base: entry.extension.base, revision: entry.extension.revision, fields: clean! })
        if (digest !== entry.extension.digest) throw new Error('扩展草稿回执与提交内容不一致')
        parseExtension(entry.extension)
        setSaved(entry)
        setNotice('扩展私有草稿已保存')
      } else {
        setSaved(null)
        setPublished(current => [entry.extension, ...current.filter(version => version.revision !== entry.extension.revision)])
        setNotice('扩展版本已发布；内置模型本体与旧扩展版本未被修改。')
      }
      setFields(entry.extension.fields.map(field => ({ ...field })))
      setDirty(false)
      setPending(null)
    } catch (cause) {
      setError(errorMessage(cause))
    } finally {
      busyRef.current = false
      setBusy(false)
    }
  }

  const controlsDisabled = props.disabled || busy || pending !== null

  return <section className="model-sample" data-model-extension aria-label="租户扩展字段">
    <h4>租户扩展字段 <span className="model-pill">附加可选字段</span></h4>
    <p className="model-muted">扩展只在该固定版本上增加可选字段；模型本体、已有字段语义与系统元信息不可覆盖。发布版本不可修改，只能追加。</p>
    {state === 'loading' ? <p role="status">正在读取扩展字段…</p> : null}
    {state === 'failed' ? <><p role="alert">{error}</p><Button variant="outline" disabled={controlsDisabled} onClick={() => { void refresh() }}>重试读取扩展字段</Button></> : null}
    {state === 'ready' ? <>
      {published.length ? <p className="model-muted">{`已发布扩展版本：${published.map(version => 'v' + version.revision).join('、')}（最新 v${published[0]!.revision}）`}</p> : <p className="model-muted">该版本当前没有已发布的租户扩展。</p>}
      {saved ? <p className="model-muted">{`我的私有草稿 · 编辑版本 ${saved.editVersion} · 预定发布 v${saved.extension.revision}`}</p> : null}
      <div className="model-fields">
        {fields.map((field, index) => <section className="model-field" key={index} data-extension-field={field.id} aria-label={'扩展字段 ' + (index + 1)}>
          <div className="model-field-heading"><strong>{'扩展字段 ' + (index + 1)}</strong><button type="button" className="model-text-button" disabled={controlsDisabled} onClick={() => remove(index)}>移除</button></div>
          <code className="model-field-full-name">{props.base.id + '.' + field.id}</code>
          <div className="model-form-grid">
            <label>字段标识<Input aria-label={'扩展字段标识 ' + (index + 1)} value={field.id} disabled={controlsDisabled} onChange={(event: ChangeEvent<HTMLInputElement>) => change(index, { id: event.target.value })} /></label>
            <label>字段名称<Input aria-label={'扩展字段名称 ' + (index + 1)} value={field.label} maxLength={80} disabled={controlsDisabled} onChange={(event: ChangeEvent<HTMLInputElement>) => change(index, { label: event.target.value })} /></label>
            <label>字段类型<select aria-label={'扩展字段类型 ' + (index + 1)} value={field.type} disabled={controlsDisabled} onChange={event => typeChanged(index, event.target.value as ModelExtensionField['type'])}>{types.map(type => <option key={type} value={type}>{typeLabels[type]}</option>)}</select></label>
            {['TEXT', 'ENUM'].includes(field.type) ? <label>最大长度<Input aria-label={'扩展字段最大长度 ' + (index + 1)} type="number" min={1} max={2048} value={String(field.maxLength ?? 255)} disabled={controlsDisabled} onChange={(event: ChangeEvent<HTMLInputElement>) => change(index, { maxLength: Number(event.target.value) })} /></label> : null}
            {['INTEGER', 'DECIMAL'].includes(field.type) ? <><label>最小值<Input aria-label={'扩展字段最小值 ' + (index + 1)} type="number" value={field.min === undefined ? '' : String(field.min)} disabled={controlsDisabled} onChange={(event: ChangeEvent<HTMLInputElement>) => change(index, { min: event.target.value === '' ? undefined : Number(event.target.value) })} /></label>
              <label>最大值<Input aria-label={'扩展字段最大值 ' + (index + 1)} type="number" value={field.max === undefined ? '' : String(field.max)} disabled={controlsDisabled} onChange={(event: ChangeEvent<HTMLInputElement>) => change(index, { max: event.target.value === '' ? undefined : Number(event.target.value) })} /></label></> : null}
            {field.type === 'ENUM' ? <label>枚举选项（逗号分隔）<Input aria-label={'扩展字段枚举选项 ' + (index + 1)} value={(field.choices ?? []).join(',')} disabled={controlsDisabled} onChange={(event: ChangeEvent<HTMLInputElement>) => change(index, { choices: event.target.value.split(',').map(choice => choice) })} /></label> : null}
          </div>
        </section>)}
      </div>
      <Button variant="outline" disabled={controlsDisabled || fields.length >= props.budget} onClick={add}>＋ 添加扩展字段</Button>
      {pending ? <section className="source-pending" role="status">
        <p>{pending === 'save' ? '草稿保存结果未知；保留了原提交内容，可原样重试。' : '发布结果未知；保留了原提交内容，可原样重试并读取原回执。'}</p>
      </section> : null}
      {error ? <p role="alert">{error}</p> : null}
      <p role="status">{notice}</p>
      <div className="model-drawer-footer">
        {pending ? <Button variant="outline" disabled={props.disabled || busy} onClick={() => { const action = pendingRef.current; if (action) void send(action, true) }}>原样重试</Button>
          : <><Button variant="outline" disabled={controlsDisabled || !dirty && saved?.state === 'DRAFT' || !fields.length} onClick={() => { void send('save', false) }}>{saved?.state === 'DRAFT' ? '保存扩展草稿' : '保存扩展草稿'}</Button>
            <Button variant="default" disabled={controlsDisabled || dirty || saved?.state !== 'DRAFT'} onClick={() => { void send('publish', false) }}>发布扩展版本</Button></>}
      </div>
    </> : null}
  </section>
}
