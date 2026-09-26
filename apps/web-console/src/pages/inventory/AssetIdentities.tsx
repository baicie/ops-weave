import { createSignal, For, Show, onCleanup } from '@zeus-js/zeus'
import { usePlatformSession } from '../../state/platform-session.ts'
import { getEntity, EntityRequestError, type EntityItem } from '../../api/entities.ts'
import { readIdentities, submitIdentity, type IdentityPage, type IdentityRequest, type AssetIdentity } from '../../api/asset-identities.ts'
import { ZwButton } from '../../adapters/zeus-ui/ZwButton.tsx'
import { ZwInput } from '../../adapters/zeus-ui/ZwInput.tsx'
import { forItem } from '../../adapters/zeus-ui/for-item.ts'

export function AssetIdentities(props: {entity: EntityItem; changed: (entity: EntityItem) => void}) {
  const [page,setPage] = createSignal<IdentityPage | null>(null), [current,setCurrent] = createSignal(props.entity), [value,setValue] = createSignal(''), [reason,setReason] = createSignal('')
  const [busy,setBusy] = createSignal(false), [error,setError] = createSignal(''), [receipt,setReceipt] = createSignal(''), [pending,setPending] = createSignal<IdentityRequest | null>(null)
  let controller: AbortController | undefined, sequence = 0
  function clear() { controller?.abort(); sequence++; setPage(null); setValue(''); setReason(''); setPending(null); setError(''); setReceipt(''); setBusy(false) }
  const authenticated = usePlatformSession(change => { clear(); if(change.error) setError(change.error.message) }); onCleanup(clear)
  const enabled = () => authenticated() && !busy() && !pending()
  async function run(request: IdentityRequest | null = null,after: string | null = null) {
    controller?.abort(); controller = new AbortController(); const active = controller, seq = ++sequence; setBusy(true); setError(''); if(request) setPending(request)
    try {
      if(request) { const result = await submitIdentity(props.entity,request,active.signal); if(seq !== sequence) return; setPending(null); setReceipt(`已保存 ${request.action} · ${result.identity.value} · 资产版本 ${result.entityVersion}`); setReason('') }
      const [result,entity] = await Promise.all([readIdentities(props.entity,after,active.signal),getEntity(props.entity.id,active.signal)]); if(seq !== sequence) return
      if(entity.tenantId !== props.entity.tenantId) throw new Error('身份与资产租户不一致'); setPage(result); setCurrent(entity); props.changed(entity)
    } catch(cause) { if(seq !== sequence) return; if(cause instanceof EntityRequestError && [400,401,403,404,409].includes(cause.status)) setPending(null); setPage(null); setError(cause instanceof Error ? cause.message : '资产身份不可用') }
    finally { if(seq === sequence) setBusy(false) }
  }
  function claim() {
    const requestId = crypto.randomUUID(); void run({path:`/api/v1/entities/${props.entity.id}/identity-keys`,action:'ASSERT',identityId:requestId,
      body:{requestId,expectedEntityVersion:current().version,expectedNamespace:page()?.namespace ?? '',value:value().trim(),reason:reason()}})
  }
  function revoke(identity: AssetIdentity) { void run({path:`/api/v1/entities/${props.entity.id}/identity-keys/${identity.id}/revocations`,action:'REVOKE',identityId:identity.id,
    body:{requestId:crypto.randomUUID(),expectedEntityVersion:current().version,expectedNamespace:page()?.namespace ?? '',reason:reason()}}) }
  return <section data-asset-identities aria-label="资产强标识">
    <h3>资产强标识</h3><p>人工核对资产登记系统的 UUID 后登记。命名空间由平台配置；同一标识只能有一个生效目标，登记与撤销均保留操作人和原因。</p>
    <ZwButton variant="outline" disabled={!enabled()} onPress={() => { void run() }}>读取资产标识</ZwButton>
    <p data-identity-error>{error()}</p><p role="status" data-identity-receipt>{receipt()}</p>
    <Show when={pending()}><p>{`提交结果待确认，保留请求 ${pending()?.body.requestId}。`}</p><ZwButton variant="outline" disabled={busy()} onPress={() => { void run(pending()) }}>按原身份请求重试</ZwButton></Show>
    <Show when={page()}><p>{`命名空间 ${page()?.namespace} · ${page()?.storage} · 当前资产版本 ${current().version}`}</p>
      <label>待登记资产 UUID<ZwInput value={value()} onValueChange={setValue} /></label>
      <label>身份核对或撤销原因<ZwInput value={reason()} onValueChange={setReason} /></label>
      <ZwButton variant="outline" disabled={!enabled() || !value().trim() || !reason().trim()} onPress={claim}>登记已核对标识</ZwButton>
      <p>每个资产最多 16 个生效标识。若标识关联了生效的补充字段，请先撤销对应字段确认，再撤销标识。</p>
      <For each={page()?.items ?? []}>{row => <IdentityRow identity={forItem(row)} revoke={revoke} disabled={!enabled() || !reason().trim()} />}</For>
      <ZwButton variant="outline" disabled={!enabled() || !page()?.nextCursor} onPress={() => { void run(null,page()?.nextCursor ?? null) }}>下一页资产标识</ZwButton>
    </Show>
  </section>
}
function IdentityRow(props: {identity: AssetIdentity; disabled: boolean; revoke: (identity: AssetIdentity) => void}) {
  const r = props.identity
  return <article data-identity-record><p>{`${r.value} · ${r.status} · ${r.actor} · ${r.assertedAt} · ${r.reason}`}</p>
    <Show when={r.status === 'ACTIVE'}><ZwButton variant="outline" disabled={props.disabled} onPress={() => props.revoke(r)}>{`撤销标识 ${r.value}`}</ZwButton></Show>
    <Show when={r.revocation}><p>{`${r.revocation?.actor} · ${r.revocation?.at} · ${r.revocation?.reason}`}</p></Show></article>
}
