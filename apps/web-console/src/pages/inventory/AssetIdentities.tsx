import { useEffect, useRef, useState } from 'react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { usePlatformSession } from '../../state/platform-session.ts'
import { getEntity, EntityRequestError, type EntityItem } from '../../api/entities.ts'
import { readIdentities, submitIdentity, type IdentityPage, type IdentityRequest, type AssetIdentity } from '../../api/asset-identities.ts'

export function AssetIdentities(props: { entity: EntityItem; changed: (entity: EntityItem) => void }) {
  const [page, setPage] = useState<IdentityPage | null>(null)
  const [current, setCurrent] = useState(props.entity)
  const [value, setValue] = useState('')
  const [reason, setReason] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [receipt, setReceipt] = useState('')
  const [pending, setPending] = useState<IdentityRequest | null>(null)
  const controllerRef = useRef<AbortController | undefined>(undefined)
  const sequenceRef = useRef(0)

  function clear() {
    controllerRef.current?.abort(); sequenceRef.current++; setPage(null); setValue(''); setReason(''); setPending(null); setError(''); setReceipt(''); setBusy(false)
  }
  const authenticated = usePlatformSession(change => { clear(); if (change.error) setError(change.error.message) })
  useEffect(() => () => { clear() }, [])
  const enabled = authenticated && !busy && !pending

  async function run(request: IdentityRequest | null = null, after: string | null = null) {
    controllerRef.current?.abort(); controllerRef.current = new AbortController(); const active = controllerRef.current, seq = ++sequenceRef.current; setBusy(true); setError(''); if (request) setPending(request)
    try {
      if (request) { const result = await submitIdentity(props.entity, request, active.signal); if (seq !== sequenceRef.current) return; setPending(null); setReceipt(`已保存 ${request.action} · ${result.identity.value} · 资产版本 ${result.entityVersion}`); setReason('') }
      const [result, entity] = await Promise.all([readIdentities(props.entity, after, active.signal), getEntity(props.entity.id, active.signal)]); if (seq !== sequenceRef.current) return
      if (entity.tenantId !== props.entity.tenantId) throw new Error('身份与资产租户不一致'); setPage(result); setCurrent(entity); props.changed(entity)
    } catch (cause) { if (seq !== sequenceRef.current) return; if (cause instanceof EntityRequestError && [400, 401, 403, 404, 409].includes(cause.status)) setPending(null); setPage(null); setError(cause instanceof Error ? cause.message : '资产身份不可用') }
    finally { if (seq === sequenceRef.current) setBusy(false) }
  }
  function claim() {
    const requestId = crypto.randomUUID(); void run({
      path: `/api/v1/entities/${props.entity.id}/identity-keys`, action: 'ASSERT', identityId: requestId,
      body: { requestId, expectedEntityVersion: current.version, expectedNamespace: page?.namespace ?? '', value: value.trim(), reason: reason },
    })
  }
  function revoke(identity: AssetIdentity) {
    void run({
      path: `/api/v1/entities/${props.entity.id}/identity-keys/${identity.id}/revocations`, action: 'REVOKE', identityId: identity.id,
      body: { requestId: crypto.randomUUID(), expectedEntityVersion: current.version, expectedNamespace: page?.namespace ?? '', reason: reason },
    })
  }
  return <section data-asset-identities aria-label="资产强标识">
    <h3>资产强标识</h3><p>人工核对资产登记系统的 UUID 后登记。命名空间由平台配置；同一标识只能有一个生效目标，登记与撤销均保留操作人和原因。</p>
    <Button variant="outline" disabled={!enabled} onClick={() => { void run() }}>读取资产标识</Button>
    <p data-identity-error>{error}</p><p role="status" data-identity-receipt>{receipt}</p>
    {pending ? <><p>{`提交结果待确认，保留请求 ${pending.body.requestId}。`}</p><Button variant="outline" disabled={busy} onClick={() => { void run(pending) }}>按原身份请求重试</Button></> : null}
    {page ? <>
      <p>{`命名空间 ${page.namespace} · ${page.storage} · 当前资产版本 ${current.version}`}</p>
      <label>待登记资产 UUID<Input value={value} onChange={event => setValue(event.currentTarget.value)} /></label>
      <label>身份核对或撤销原因<Input value={reason} onChange={event => setReason(event.currentTarget.value)} /></label>
      <Button variant="outline" disabled={!enabled || !value.trim() || !reason.trim()} onClick={claim}>登记已核对标识</Button>
      <p>每个资产最多 16 个生效标识。若标识关联了生效的补充字段，请先撤销对应字段确认，再撤销标识。</p>
      {page.items.map(row => <IdentityRow key={row.id} identity={row} revoke={revoke} disabled={!enabled || !reason.trim()} />)}
      <Button variant="outline" disabled={!enabled || !page.nextCursor} onClick={() => { void run(null, page.nextCursor ?? null) }}>下一页资产标识</Button>
    </> : null}
  </section>
}

function IdentityRow(props: { identity: AssetIdentity; disabled: boolean; revoke: (identity: AssetIdentity) => void }) {
  const r = props.identity
  return <article data-identity-record><p>{`${r.value} · ${r.status} · ${r.actor} · ${r.assertedAt} · ${r.reason}`}</p>
    {r.status === 'ACTIVE' ? <Button variant="outline" disabled={props.disabled} onClick={() => props.revoke(r)}>{`撤销标识 ${r.value}`}</Button> : null}
    {r.revocation ? <p>{`${r.revocation.actor} · ${r.revocation.at} · ${r.revocation.reason}`}</p> : null}</article>
}
